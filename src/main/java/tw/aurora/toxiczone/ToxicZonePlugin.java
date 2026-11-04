package tw.aurora.toxiczone;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.NamespacedKey;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ToxicZonePlugin extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {
    private YamlConfiguration messages;
    private String activeArena = "default";
    private final Map<String, Long> lastDamageAt = new HashMap<>();
    private final Map<UUID, String> selectedArenas = new HashMap<>();
    private String consoleArena = "default";
    private volatile long cachedAt;
    private volatile String cachedLatest;
    private volatile boolean cacheValid;
    private volatile CompletableFuture<String> fetchInFlight;
    private BukkitTask tickTask;
    private BukkitTask particleTask;
    private BukkitTask configSaveTask;
    private boolean configDirty;
    private NamespacedKey wandKey;
    private NamespacedKey wandIdKey;
    private ToxicZoneResourcePack resourcePack;
    private final Map<UUID, RegionSelection> selections = new HashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final Pattern VERSION = Pattern.compile("[vV]?(\\d+)\\.(\\d+)\\.(\\d+)");

    @Override public void onEnable() {
        ensureConfig(); loadMessages();
        forEachArena(this::recoverLegacyRound);
        wandKey = new NamespacedKey(this, "region_wand");
        wandIdKey = new NamespacedKey(this, "wand_id");
        saveResource("resource-pack.zip", false);
        org.bukkit.plugin.Plugin httpHub = Bukkit.getPluginManager().getPlugin("HttpHub");
        if (httpHub != null && httpHub.isEnabled()) {
            resourcePack = new ToxicZoneResourcePack(this);
            resourcePack.start();
        } else {
            getLogger().severe("未安裝或未啟用 HttpHub；伺服器資源包不會提供或強制套用。");
        }
        PluginCommand command = getCommand("toxiczone");
        if (command != null) { command.setExecutor(this); command.setTabCompleter(this); }
        Bukkit.getPluginManager().registerEvents(this, this);
        tickTask = Bukkit.getScheduler().runTaskTimer(this, () -> forEachArena(this::tickZone), 20L, 20L);
        long particleInterval = Math.max(2L, getConfig().getLong("settings.particles.interval-ticks", 10L));
        particleTask = Bukkit.getScheduler().runTaskTimer(this, () -> forEachArena(this::renderBoundary), particleInterval, particleInterval);
        checkGitHubAsync(result -> { if (result == null) getLogger().info("GitHub 版本檢查未取得可用的 Release 資訊。"); else getLogger().info("GitHub 最新版本：" + result + (isNewer(getDescription().getVersion(), result) ? "（目前版本為開發版）" : "")); });
    }

    @Override public void onDisable() { if (tickTask != null) tickTask.cancel(); if (particleTask != null) particleTask.cancel(); if (configSaveTask != null) configSaveTask.cancel(); if (configDirty) saveConfig(); if (resourcePack != null) resourcePack.stop(); selections.clear(); selectedArenas.clear(); lastDamageAt.clear(); }

    private record BlockPoint(UUID worldId, String worldName, int x, int y, int z) {}
    private static final class RegionSelection { private BlockPoint first; private BlockPoint second; }

    private void markConfigDirty() {
        configDirty = true;
        if (configSaveTask != null && !configSaveTask.isCancelled()) return;
        configSaveTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (configDirty) { saveConfig(); configDirty = false; }
            configSaveTask = null;
        }, 40L);
    }

    private String arenaKey(String key) {
        return (activeArena.equals("default") ? "arena." : "arenas." + activeArena + ".") + key;
    }

    private String damageKey(String key) {
        return activeArena.equals("default") ? "settings." + key : arenaKey(key);
    }

    private List<String> arenaNames() {
        List<String> names = new ArrayList<>();
        names.add("default");
        var section = getConfig().getConfigurationSection("arenas");
        if (section != null) {
            for (String name : section.getKeys(false)) {
                if (name.matches("[a-z0-9_-]{1,32}") && !name.equals("default")
                        && section.isConfigurationSection(name)) names.add(name);
            }
        }
        return names;
    }

    private String selectedArena(CommandSender sender) {
        String name = sender instanceof Player player
                ? selectedArenas.getOrDefault(player.getUniqueId(), "default") : consoleArena;
        return arenaNames().contains(name) ? name : "default";
    }

    private void inArena(String name, Runnable action) {
        String previous = activeArena;
        activeArena = name;
        try {
            action.run();
        } finally {
            activeArena = previous;
        }
    }

    private void forEachArena(Runnable action) {
        for (String name : arenaNames()) inArena(name, action);
    }

    private long elapsedMillis(long now) {
        return Math.max(0, now - getConfig().getLong(arenaKey("started-at"), now));
    }

    private void recoverLegacyRound() {
        if (getConfig().getBoolean(arenaKey("running")) && getConfig().getLong(arenaKey("started-at")) <= 0) {
            getConfig().set(arenaKey("running"), false);
            saveConfig();
        }
    }

    private void manageArena(CommandSender sender, String[] args) {
        if (args.length == 2 && args[1].equalsIgnoreCase("list")) {
            send(sender, "arena-list", Map.of("arenas", String.join(", ", arenaNames())));
            return;
        }
        if (args.length != 3 || !args[2].matches("[a-z0-9_-]{1,32}")) {
            send(sender, "arena-usage");
            return;
        }
        String name = args[2];
        boolean exists = arenaNames().contains(name);
        if (args[1].equalsIgnoreCase("create") && !exists) {
            var defaults = getConfig().getDefaults().getConfigurationSection("arena");
            var arena = getConfig().createSection("arenas." + name);
            for (String key : defaults.getKeys(true)) {
                if (!defaults.isConfigurationSection(key)) arena.set(key, defaults.get(key));
            }
            if (sender instanceof Player player) {
                Location location = player.getLocation();
                arena.set("world", location.getWorld().getName());
                arena.set("center-x", location.getX());
                arena.set("center-z", location.getZ());
            }
            arena.set("damage", getConfig().getDouble("settings.damage"));
            arena.set("damage-interval-seconds", getConfig().getDouble("settings.damage-interval-seconds"));
            saveConfig();
        } else if (!args[1].equalsIgnoreCase("use") || !exists) {
            send(sender, "arena-usage");
            return;
        }
        if (sender instanceof Player player) {
            selectedArenas.put(player.getUniqueId(), name);
            selections.remove(player.getUniqueId());
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof ToxicZoneMenuHolder) {
                player.closeInventory();
            }
        } else {
            consoleArena = name;
        }
        send(sender, "arena-selected", Map.of("arena", name));
    }

    private void ensureConfig() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        File file = new File(getDataFolder(), "config.yml");
        if (!file.exists()) saveResource("config.yml", false);
        YamlConfiguration live = YamlConfiguration.loadConfiguration(file);
        YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(Objects.requireNonNull(getResource("config.yml")), StandardCharsets.UTF_8));
        boolean changed = false;
        for (String key : defaults.getKeys(true)) if (!defaults.isConfigurationSection(key) && !live.contains(key)) { live.set(key, defaults.get(key)); changed = true; }
        if (changed) try { live.save(file); } catch (IOException e) { getLogger().severe("無法補齊 config.yml: " + e.getMessage()); }
        reloadConfig();
    }

    private void loadMessages() {
        File file = new File(getDataFolder(), "messages.yml");
        if (!file.exists()) saveResource("messages.yml", false);
        messages = YamlConfiguration.loadConfiguration(file);
        YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(Objects.requireNonNull(getResource("messages.yml")), StandardCharsets.UTF_8));
        boolean changed = false;
        for (String key : defaults.getKeys(true)) if (!defaults.isConfigurationSection(key) && !messages.contains(key)) { messages.set(key, defaults.get(key)); changed = true; }
        String previousHelp = "&e/toxiczone help | status | start | stop | center [世界 x z] | radius <起始半徑> [最小半徑] | time <秒數> | damage <傷害值> [間隔秒數]";
        String earlierHelp = String.join("\n", "&b&l毒圈指令", "&f/tz status &8- &7查看目前狀態", "&f/tz center [世界 x z] &8- &7設定中心位置", "&f/tz radius <起始半徑> [最小半徑] &8- &7設定安全區大小", "&f/tz time <秒數> &8- &7設定縮圈時間", "&f/tz damage <傷害> [間隔秒數] &8- &7設定毒圈傷害", "&f/tz start / stop &8- &7開始或停止縮圈", "&f/tz reload &8- &7重新載入設定", "&f/tz version &8- &7檢查插件版本");
        if ("&8毒圈控制面板".equals(messages.getString("gui-title"))) {
            messages.set("gui-title", defaults.getString("gui-title"));
            changed = true;
        }
        String currentHelp = messages.getString("help");
        String previousWandHelp = defaults.getString("help", "").replace("區域選取釣竿", "區域選取木棍");
        if (previousHelp.equals(currentHelp) || earlierHelp.equals(currentHelp) || previousWandHelp.equals(currentHelp)) { messages.set("help", defaults.getString("help")); changed = true; }
        if ("&b區域選取木棍".equals(messages.getString("wand-name"))) { messages.set("wand-name", defaults.getString("wand-name")); changed = true; }
        if ("&a已取得區域選取木棍。左鍵選第一角，右鍵選第二角。".equals(messages.getString("wand-given"))) { messages.set("wand-given", defaults.getString("wand-given")); changed = true; }
        if ("&7依序左鍵兩個方塊設定選區。".equals(messages.getString("wand-lore"))) { messages.set("wand-lore", defaults.getString("wand-lore")); changed = true; }
        if ("&a已取得區域選取釣竿。依序左鍵兩個方塊設定選區。".equals(messages.getString("wand-given"))) { messages.set("wand-given", defaults.getString("wand-given")); changed = true; }
        if (changed) try { messages.save(file); } catch (IOException e) { getLogger().severe("無法補齊 messages.yml: " + e.getMessage()); }
    }

    private void send(CommandSender sender, String key, Map<String, ?> vars) {
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', messages.getString("prefix", "") + format(key, vars)));
    }
    private void send(CommandSender sender, String key) { send(sender, key, Map.of()); }
    private String format(String key, Map<String, ?> vars) {
        String text = messages.getString(key, "&c缺少訊息: " + key);
        for (var entry : vars.entrySet()) text = text.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        return ChatColor.translateAlternateColorCodes('&', text);
    }
    private boolean admin(CommandSender s) { if (s.isOp() || s.hasPermission("auroratoxiczone.admin")) return true; send(s,"no-permission"); return false; }
    private boolean versionPerm(CommandSender s) { if (s.hasPermission("auroratoxiczone.version")) return true; send(s,"no-permission"); return false; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!admin(sender)) return true;
        if (args.length > 0 && args[0].equalsIgnoreCase("arena")) {
            manageArena(sender, args);
            return true;
        }
        inArena(selectedArena(sender), () -> handleArenaCommand(sender, args));
        return true;
    }

    private boolean handleArenaCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) openMenu(player); else send(sender,"only-player");
            return true;
        }
        if (args[0].equalsIgnoreCase("help")) { send(sender,"help"); return true; }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("version")) { if (!versionPerm(sender)) return true; checkGitHubAsync(latest -> Bukkit.getScheduler().runTask(this, () -> tellVersion(sender, latest))); return true; }
        if (getConfig().getBoolean(arenaKey("running"))
                && Set.of("start", "center", "radius", "time").contains(sub)) {
            send(sender, "arena-running");
            return true;
        }
        switch (sub) {
            case "status" -> {
                double radius = currentRadius();
                long remain = getConfig().getBoolean(arenaKey("running"))
                        ? Math.max(0, getConfig().getLong(arenaKey("shrink-duration-seconds")) - elapsedMillis(System.currentTimeMillis()) / 1000) : 0;
                send(sender,"status",Map.of("world",getConfig().getString(arenaKey("world"),"world"),"x",getConfig().getDouble(arenaKey("center-x")),"z",getConfig().getDouble(arenaKey("center-z")),"radius",String.format(Locale.ROOT,"%.1f",radius),"minimum",getConfig().getDouble(arenaKey("minimum-radius")),"remaining",remain,"state",messages.getString(getConfig().getBoolean(arenaKey("running"))?"state-running":"state-stopped")));
            }
            case "start" -> startZone(sender);
            case "stop" -> stopZone(sender);
            case "center" -> setCenter(sender,args);
            case "wand" -> giveWand(sender);
            case "radius" -> setRadius(sender,args);
            case "time" -> setTime(sender,args);
            case "damage" -> setDamage(sender,args);
            case "reload" -> { if (configDirty) { if (configSaveTask != null) configSaveTask.cancel(); saveConfig(); configDirty=false; configSaveTask=null; } ensureConfig(); forEachArena(this::recoverLegacyRound); loadMessages(); send(sender,"reload"); }
            default -> send(sender,"help");
        }
        return true;
    }

    private void setCenter(CommandSender s,String[] a) {
        String world; double x,z;
        try { if(a.length==1 && s instanceof Player p){ world=p.getWorld().getName(); x=p.getLocation().getX(); z=p.getLocation().getZ(); }
            else if(a.length==4){ world=a[1]; if(Bukkit.getWorld(world)==null) throw new IllegalArgumentException(); x=Double.parseDouble(a[2]); z=Double.parseDouble(a[3]); }
            else { send(s,"center-usage"); return; }
        } catch(Exception e){send(s,"center-usage");return;}
        if (!Double.isFinite(x) || !Double.isFinite(z)) { send(s,"center-usage"); return; }
        getConfig().set(arenaKey("world"),world);getConfig().set(arenaKey("center-x"),x);getConfig().set(arenaKey("center-z"),z);getConfig().set(arenaKey("selection-boundary.enabled"),false);markConfigDirty();
        send(s,"center-set",Map.of("world",world,"x",x,"z",z));
    }
    private void setRadius(CommandSender s,String[] a){try{if(a.length<2||a.length>3)throw new Exception();double r=Double.parseDouble(a[1]),m=a.length==3?Double.parseDouble(a[2]):getConfig().getDouble(arenaKey("minimum-radius"));if(!Double.isFinite(r)||!Double.isFinite(m)||r<=0||m<=0||m>r)throw new Exception();getConfig().set(arenaKey("start-radius"),r);getConfig().set(arenaKey("minimum-radius"),m);markConfigDirty();send(s,"radius-set",Map.of("radius",r,"minimum",m));}catch(Exception e){send(s,"radius-usage");}}
    private void setTime(CommandSender s,String[] a){try{long n=Long.parseLong(a[1]);if(a.length!=2||n<1)throw new Exception();getConfig().set(arenaKey("shrink-duration-seconds"),n);markConfigDirty();send(s,"time-set",Map.of("seconds",n));}catch(Exception e){send(s,"time-usage");}}
    private void setDamage(CommandSender s,String[] a){try{double d=Double.parseDouble(a[1]),i=a.length==3?Double.parseDouble(a[2]):getConfig().getDouble(damageKey("damage-interval-seconds"));if(a.length<2||a.length>3||!Double.isFinite(d)||!Double.isFinite(i)||d<=0||i<0.1)throw new Exception();getConfig().set(damageKey("damage"),d);getConfig().set(damageKey("damage-interval-seconds"),i);markConfigDirty();send(s,"damage-set",Map.of("damage",d,"interval",i,"hearts",d/2));}catch(Exception e){send(s,"damage-usage");}}

    private void startZone(CommandSender sender) {
        if(!getConfig().getBoolean(arenaKey("selection-boundary.enabled"))){
            double x=getConfig().getDouble(arenaKey("center-x")),z=getConfig().getDouble(arenaKey("center-z")),radius=getConfig().getDouble(arenaKey("start-radius"));
            saveSelectionBoundary(x-radius,x+radius,z-radius,z+radius);
        }
        String prefix = arenaKey("selection-boundary.");
        var config = getConfig();
        for (String name : arenaNames()) {
            if (name.equals(activeArena)) continue;
            String other = name.equals("default") ? "arena." : "arenas." + name + ".";
            String bounds = other + "selection-boundary.";
            if (config.getBoolean(other + "running")
                    && config.getString(arenaKey("world"), "world").equals(config.getString(other + "world", "world"))
                    && config.getDouble(prefix + "min-x") <= config.getDouble(bounds + "max-x")
                    && config.getDouble(prefix + "max-x") >= config.getDouble(bounds + "min-x")
                    && config.getDouble(prefix + "min-z") <= config.getDouble(bounds + "max-z")
                    && config.getDouble(prefix + "max-z") >= config.getDouble(bounds + "min-z")) {
                send(sender, "arena-overlap", Map.of("arena", name));
                return;
            }
        }
        config.set(arenaKey("eliminated-players"), List.of());
        getConfig().set(arenaKey("started-at"), System.currentTimeMillis()); lastDamageAt.remove(activeArena); getConfig().set(arenaKey("running"),true); saveConfig(); send(sender,"started");
    }
    private void stopZone(CommandSender sender) { double frozen=currentRadius(); getConfig().set(arenaKey("start-radius"),frozen); getConfig().set(arenaKey("minimum-radius"),Math.min(getConfig().getDouble(arenaKey("minimum-radius")),frozen)); getConfig().set(arenaKey("running"),false); saveConfig(); send(sender,"stopped"); }

    private void openMenu(Player player) {
        String title = format("gui-title", Map.of("arena", activeArena));
        ToxicZoneMenuHolder holder = new ToxicZoneMenuHolder(this, title);
        Inventory inventory = holder.getInventory();
        ItemStack filler = guiItem("gui-filler", Material.GRAY_STAINED_GLASS_PANE, Map.of());
        for (int slot=0; slot<inventory.getSize(); slot++) inventory.setItem(slot, filler);
        String state = messages.getString(getConfig().getBoolean(arenaKey("running")) ? "state-running" : "state-stopped", "");
        Map<String,Object> status = new HashMap<>();
        status.put("world",getConfig().getString(arenaKey("world"),"world")); status.put("x",fmt(getConfig().getDouble(arenaKey("center-x")))); status.put("z",fmt(getConfig().getDouble(arenaKey("center-z"))));
        status.put("radius",fmt(currentRadius())); status.put("minimum",fmt(getConfig().getDouble(arenaKey("minimum-radius")))); status.put("state",ChatColor.translateAlternateColorCodes('&',state));
        inventory.setItem(4,guiItem("gui-status",Material.MAP,status));
        inventory.setItem(10,guiItem("gui-center",Material.COMPASS,Map.of()));
        inventory.setItem(12,guiItem("gui-radius-down",Material.RED_DYE,Map.of())); inventory.setItem(13,guiItem("gui-radius",Material.PAPER,Map.of("value",fmt(getConfig().getDouble(arenaKey("start-radius")))))); inventory.setItem(14,guiItem("gui-radius-up",Material.LIME_DYE,Map.of()));
        inventory.setItem(16,guiItem("gui-minimum-down",Material.RED_DYE,Map.of())); inventory.setItem(17,guiItem("gui-minimum",Material.PAPER,Map.of("value",fmt(getConfig().getDouble(arenaKey("minimum-radius")))))); inventory.setItem(18,guiItem("gui-minimum-up",Material.LIME_DYE,Map.of()));
        inventory.setItem(21,guiItem("gui-time-down",Material.RED_DYE,Map.of())); inventory.setItem(22,guiItem("gui-time",Material.CLOCK,Map.of("value",getConfig().getLong(arenaKey("shrink-duration-seconds"))))); inventory.setItem(23,guiItem("gui-time-up",Material.LIME_DYE,Map.of()));
        inventory.setItem(25,guiItem("gui-damage-down",Material.RED_DYE,Map.of())); inventory.setItem(26,guiItem("gui-damage",Material.PAPER,Map.of("value",fmt(getConfig().getDouble(damageKey("damage")))))); inventory.setItem(27,guiItem("gui-damage-up",Material.LIME_DYE,Map.of()));
        inventory.setItem(30,guiItem("gui-interval-down",Material.RED_DYE,Map.of())); inventory.setItem(31,guiItem("gui-interval",Material.CLOCK,Map.of("value",fmt(getConfig().getDouble(damageKey("damage-interval-seconds")))))); inventory.setItem(32,guiItem("gui-interval-up",Material.LIME_DYE,Map.of()));
        inventory.setItem(34,guiItem("gui-wand",Material.CARROT_ON_A_STICK,Map.of()));
        inventory.setItem(40,guiItem(getConfig().getBoolean(arenaKey("running"))?"gui-stop":"gui-start",getConfig().getBoolean(arenaKey("running"))?Material.BARRIER:Material.LIME_CONCRETE,Map.of()));
        inventory.setItem(42,guiItem("gui-reload",Material.WRITABLE_BOOK,Map.of()));
        player.openInventory(inventory);
    }

    private ItemStack guiItem(String key, Material material, Map<String,?> vars) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(format(key + ".name", vars));
            List<String> lore = messages.getStringList(key + ".lore").stream().map(line -> formatLine(line, vars)).toList();
            if (!lore.isEmpty()) meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }
    private String formatLine(String line, Map<String,?> vars) { String out=line; for(var entry:vars.entrySet()) out=out.replace("{"+entry.getKey()+"}",String.valueOf(entry.getValue())); return ChatColor.translateAlternateColorCodes('&',out); }
    private String fmt(double value) { return String.format(Locale.ROOT,"%.1f",value); }

    @EventHandler public void onMenuClick(InventoryClickEvent event) {
        Inventory top=event.getView().getTopInventory();
        if (!(top.getHolder() instanceof ToxicZoneMenuHolder holder) || holder.getPlugin()!=this) return;
        event.setCancelled(true);
        if (event.getClickedInventory()!=top || event.getClick()!=ClickType.LEFT || !(event.getWhoClicked() instanceof Player player)) return;
        if (!player.isOp() && !player.hasPermission("auroratoxiczone.admin")) { player.closeInventory(); send(player,"no-permission"); return; }
        inArena(selectedArena(player), () -> handleMenuClick(event.getRawSlot(), player, holder));
    }

    private void handleMenuClick(int slot, Player player, ToxicZoneMenuHolder holder) {
        if (getConfig().getBoolean(arenaKey("running")) && Set.of(10, 12, 14, 16, 18, 21, 23).contains(slot)) {
            send(player, "arena-running");
            return;
        }
        switch(slot) {
            case 10 -> { Location loc=player.getLocation(); getConfig().set(arenaKey("world"),loc.getWorld().getName()); getConfig().set(arenaKey("center-x"),loc.getX()); getConfig().set(arenaKey("center-z"),loc.getZ()); getConfig().set(arenaKey("selection-boundary.enabled"),false); markConfigDirty(); send(player,"center-set",Map.of("world",loc.getWorld().getName(),"x",fmt(loc.getX()),"z",fmt(loc.getZ()))); }
            case 12,14 -> { adjustRadius(slot==12?-10:10); send(player,"radius-set",Map.of("radius",fmt(getConfig().getDouble(arenaKey("start-radius"))),"minimum",fmt(getConfig().getDouble(arenaKey("minimum-radius"))))); }
            case 16,18 -> { adjustMinimum(slot==16?-1:1); send(player,"minimum-set",Map.of("minimum",fmt(getConfig().getDouble(arenaKey("minimum-radius"))))); }
            case 21,23 -> { adjustTime(slot==21?-60:60); send(player,"time-set",Map.of("seconds",getConfig().getLong(arenaKey("shrink-duration-seconds")))); }
            case 25,27 -> { adjustDamage(slot==25?-0.5:0.5); sendDamageSettings(player); }
            case 30,32 -> { adjustInterval(slot==30?-0.5:0.5); sendDamageSettings(player); }
            case 34 -> giveWand(player);
            case 40 -> { if(getConfig().getBoolean(arenaKey("running"))) stopZone(player); else startZone(player); }
            case 42 -> { flushConfig(); ensureConfig(); forEachArena(this::recoverLegacyRound); loadMessages(); send(player,"reload"); }
            default -> { return; }
        }
        Bukkit.getScheduler().runTask(this, () -> {
            if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder() == holder) {
                inArena(selectedArena(player), () -> openMenu(player));
            }
        });
    }

    @EventHandler public void onMenuDrag(InventoryDragEvent event) {
        Inventory top=event.getView().getTopInventory();
        if(top.getHolder() instanceof ToxicZoneMenuHolder holder && holder.getPlugin()==this) event.setCancelled(true);
    }

    private void adjustRadius(double delta) { double radius=Math.max(1,getConfig().getDouble(arenaKey("start-radius"))+delta); getConfig().set(arenaKey("start-radius"),radius); if(getConfig().getDouble(arenaKey("minimum-radius"))>radius)getConfig().set(arenaKey("minimum-radius"),radius); markConfigDirty(); }
    private void adjustMinimum(double delta) { double radius=getConfig().getDouble(arenaKey("start-radius")),min=Math.max(1,Math.min(radius,getConfig().getDouble(arenaKey("minimum-radius"))+delta));getConfig().set(arenaKey("minimum-radius"),min);markConfigDirty(); }
    private void adjustTime(long delta) { getConfig().set(arenaKey("shrink-duration-seconds"),Math.max(60,getConfig().getLong(arenaKey("shrink-duration-seconds"))+delta));markConfigDirty(); }
    private void adjustDamage(double delta) { getConfig().set(damageKey("damage"),Math.max(0.5,getConfig().getDouble(damageKey("damage"))+delta));markConfigDirty(); }
    private void adjustInterval(double delta) { getConfig().set(damageKey("damage-interval-seconds"),Math.max(0.5,getConfig().getDouble(damageKey("damage-interval-seconds"))+delta));markConfigDirty(); }
    private void sendDamageSettings(Player player) { send(player,"damage-set",Map.of("damage",fmt(getConfig().getDouble(damageKey("damage"))),"interval",fmt(getConfig().getDouble(damageKey("damage-interval-seconds"))),"hearts",fmt(getConfig().getDouble(damageKey("damage"))/2))); }

    private void flushConfig() { if(configSaveTask!=null)configSaveTask.cancel(); if(configDirty){saveConfig();configDirty=false;} configSaveTask=null; }

    private void giveWand(CommandSender sender) {
        if (!(sender instanceof Player player)) { send(sender,"only-player"); return; }
        ItemStack item = new ItemStack(Material.CARROT_ON_A_STICK);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', messages.getString("wand-name", "區域選取釣竿")));
        meta.setLore(List.of(ChatColor.translateAlternateColorCodes('&', messages.getString("wand-lore", "左鍵選第一角，右鍵選第二角。"))));
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(wandIdKey, PersistentDataType.INTEGER, 1001);
        var customModelData = meta.getCustomModelDataComponent();
        customModelData.setStrings(List.of("1001"));
        customModelData.setFloats(List.of(1001.0f));
        meta.setCustomModelDataComponent(customModelData);
        item.setItemMeta(meta);
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        overflow.values().forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        send(player,"wand-given");
    }

    @EventHandler public void onWandUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getItem() == null
                || event.getItem().getType() != Material.CARROT_ON_A_STICK) return;
        inArena(selectedArena(event.getPlayer()), () -> handleWandUse(event));
    }

    private void handleWandUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getItem() == null || wandKey == null || wandIdKey == null) return;
        ItemMeta meta = event.getItem().getItemMeta();
        if (meta == null || !meta.getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE)
                || meta.getPersistentDataContainer().getOrDefault(wandIdKey, PersistentDataType.INTEGER, 0) != 1001) return;
        Action action = event.getAction();
        Player player = event.getPlayer();
        if (action == Action.RIGHT_CLICK_AIR) { event.setCancelled(true); return; }
        if ((action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) || event.getClickedBlock() == null) return;
        event.setCancelled(true);
        if (!player.isOp() && !player.hasPermission("auroratoxiczone.admin")) { send(player,"no-permission"); return; }
        if (getConfig().getBoolean(arenaKey("running"))) {
            send(player, "arena-running");
            return;
        }
        BlockPoint point = new BlockPoint(player.getWorld().getUID(), player.getWorld().getName(), event.getClickedBlock().getX(), event.getClickedBlock().getY(), event.getClickedBlock().getZ());
        RegionSelection selection = selections.computeIfAbsent(player.getUniqueId(), ignored -> new RegionSelection());
        if (action == Action.LEFT_CLICK_BLOCK) {
            selection.first = point;
            selection.second = null;
            send(player,"selection-first",Map.of("world",point.worldName(),"x",point.x(),"y",point.y(),"z",point.z()));
        } else {
            if (selection.first == null) { send(player,"selection-first-needed"); return; }
            if (!selection.first.worldId().equals(point.worldId())) { selections.remove(player.getUniqueId()); send(player,"selection-world-error"); return; }
            selection.second = point;
            send(player,"selection-second",Map.of("world",point.worldName(),"x",point.x(),"y",point.y(),"z",point.z()));
        }
        if (selection.first != null && selection.second != null) applySelection(player, selection);
    }

    private void applySelection(Player player, RegionSelection selection) {
        BlockPoint first = selection.first, second = selection.second;
        double centerX = (first.x() + second.x()) / 2.0 + 0.5;
        double centerZ = (first.z() + second.z()) / 2.0 + 0.5;
        double width = Math.abs(first.x() - second.x()) + 1.0;
        double depth = Math.abs(first.z() - second.z()) + 1.0;
        double radius = Math.max(0.5, Math.min(width, depth) / 2.0);
        getConfig().set(arenaKey("world"), first.worldName());
        getConfig().set(arenaKey("center-x"), centerX);
        getConfig().set(arenaKey("center-z"), centerZ);
        getConfig().set(arenaKey("start-radius"), radius);
        getConfig().set(arenaKey("minimum-radius"), Math.min(getConfig().getDouble(arenaKey("minimum-radius"), 5.0), radius));
        saveSelectionBoundary(Math.min(first.x(),second.x()),Math.max(first.x(),second.x())+1.0,Math.min(first.z(),second.z()),Math.max(first.z(),second.z())+1.0);
        markConfigDirty();
        selections.remove(player.getUniqueId());
        send(player,"selection-complete",Map.of("x",String.format(Locale.ROOT,"%.1f",centerX),"z",String.format(Locale.ROOT,"%.1f",centerZ),"radius",String.format(Locale.ROOT,"%.1f",radius)));
    }

    private void saveSelectionBoundary(double minX,double maxX,double minZ,double maxZ) {
        getConfig().set(arenaKey("selection-boundary.enabled"),true);
        getConfig().set(arenaKey("selection-boundary.min-x"),minX);
        getConfig().set(arenaKey("selection-boundary.max-x"),maxX);
        getConfig().set(arenaKey("selection-boundary.min-z"),minZ);
        getConfig().set(arenaKey("selection-boundary.max-z"),maxZ);
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { selections.remove(event.getPlayer().getUniqueId()); selectedArenas.remove(event.getPlayer().getUniqueId()); }
    @EventHandler public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        forEachArena(() -> {
            if (!getConfig().getBoolean(arenaKey("running")) || !insideArena(player.getLocation())) return;
            String key = arenaKey("eliminated-players");
            List<String> eliminated = getConfig().getStringList(key);
            String id = player.getUniqueId().toString();
            if (!eliminated.contains(id)) {
                eliminated.add(id);
                getConfig().set(key, eliminated);
                markConfigDirty();
            }
        });
    }

    private boolean insideArena(Location location) {
        return location.getWorld().getName().equals(getConfig().getString(arenaKey("world"), "world"))
                && getConfig().getBoolean(arenaKey("selection-boundary.enabled"))
                && location.getX() >= getConfig().getDouble(arenaKey("selection-boundary.min-x"))
                && location.getX() <= getConfig().getDouble(arenaKey("selection-boundary.max-x"))
                && location.getZ() >= getConfig().getDouble(arenaKey("selection-boundary.min-z"))
                && location.getZ() <= getConfig().getDouble(arenaKey("selection-boundary.max-z"));
    }

    private double currentRadius(){double start=getConfig().getDouble(arenaKey("start-radius"),200),min=getConfig().getDouble(arenaKey("minimum-radius"),5);if(!getConfig().getBoolean(arenaKey("running")))return start;double duration=Math.max(1,getConfig().getDouble(arenaKey("shrink-duration-seconds"),1800));double progress=Math.min(1,elapsedMillis(System.currentTimeMillis())/1000.0/duration);return start-(start-min)*progress;}
    private void tickZone(){
        if(!getConfig().getBoolean(arenaKey("running")))return;
        long now=System.currentTimeMillis();
        double duration=Math.max(1,getConfig().getDouble(arenaKey("shrink-duration-seconds"),1800));
        if(elapsedMillis(now)/1000.0>=duration){
            double minimum=getConfig().getDouble(arenaKey("minimum-radius"),5.0);
            getConfig().set(arenaKey("running"),false);
            saveConfig();
            World arenaWorld=Bukkit.getWorld(getConfig().getString(arenaKey("world"),"world"));
            if(arenaWorld!=null)for(Player player:arenaWorld.getPlayers())if(insideArena(player.getLocation()))send(player,"shrink-complete",Map.of("radius",fmt(minimum)));
            return;
        }
        String worldName=getConfig().getString(arenaKey("world"),"world");World world=Bukkit.getWorld(worldName);if(world==null)return;double radius=currentRadius();
        double interval=Math.max(.1,getConfig().getDouble(damageKey("damage-interval-seconds"),1));if(now-lastDamageAt.getOrDefault(activeArena, 0L)<interval*1000)return;lastDamageAt.put(activeArena, now);double cx=getConfig().getDouble(arenaKey("center-x")),cz=getConfig().getDouble(arenaKey("center-z")),damage=Math.max(.1,getConfig().getDouble(damageKey("damage"),3));
        Set<String> eliminated = new HashSet<>(getConfig().getStringList(arenaKey("eliminated-players")));
        for(Player p:world.getPlayers()){
            String playerId=p.getUniqueId().toString();
            if(eliminated.contains(playerId)||p.isDead()||p.getHealth()<=0.0||p.getGameMode()==org.bukkit.GameMode.SPECTATOR)continue;
            Location l=p.getLocation();double dx=l.getX()-cx,dz=l.getZ()-cz;
            if(insideArena(l)&&dx*dx+dz*dz>radius*radius&&!p.isDead()&&p.getHealth()>0.0)p.damage(damage);
        }
    }
    private void renderBoundary(){
        if(!getConfig().getBoolean("settings.particles.enabled",true))return;
        World world=Bukkit.getWorld(getConfig().getString(arenaKey("world"),"world"));
        if(world==null||world.getPlayers().isEmpty())return;
        double cx=getConfig().getDouble(arenaKey("center-x")),cz=getConfig().getDouble(arenaKey("center-z"));
        int maxPoints=Math.max(24,Math.min(1440,getConfig().getInt("settings.particles.points",720)));
        int layers=Math.max(4,Math.min(24,getConfig().getInt("settings.particles.layers",12)));
        double verticalSpacing=Math.max(0.5,Math.min(2.0,getConfig().getDouble("settings.particles.vertical-spacing",1.0)));
        double viewDistance=Math.max(24,Math.min(128,getConfig().getDouble("settings.particles.view-distance",64.0)));
        double viewDistanceSquared=viewDistance*viewDistance;
        float dustSize=(float)Math.max(1.0,Math.min(4.0,getConfig().getDouble("settings.particles.dust-size",2.0)));
        Particle.DustOptions dust=new Particle.DustOptions(Color.fromRGB(70,255,90),dustSize);
        boolean running=getConfig().getBoolean(arenaKey("running"));
        double radius=currentRadius();
        double minX,maxX,minZ,maxZ;
        if(getConfig().getBoolean(arenaKey("selection-boundary.enabled"))){
            minX=getConfig().getDouble(arenaKey("selection-boundary.min-x"));maxX=getConfig().getDouble(arenaKey("selection-boundary.max-x"));
            minZ=getConfig().getDouble(arenaKey("selection-boundary.min-z"));maxZ=getConfig().getDouble(arenaKey("selection-boundary.max-z"));
        } else {
            minX=cx-getConfig().getDouble(arenaKey("start-radius"));maxX=cx+getConfig().getDouble(arenaKey("start-radius"));
            minZ=cz-getConfig().getDouble(arenaKey("start-radius"));maxZ=cz+getConfig().getDouble(arenaKey("start-radius"));
        }
        double lineSpacing=Math.max(0.5,Math.min(2.0,getConfig().getDouble("settings.particles.line-spacing",0.75)));
        for(Player viewer:world.getPlayers()){
            Location eye=viewer.getLocation();
            double baseY=Math.max(world.getMinHeight()+0.5,eye.getY()-3.0);
            if(running){
                int points=Math.min(maxPoints,Math.max(24,(int)Math.ceil(Math.PI*2*radius/3.0)));
                for(int i=0;i<points;i++){
                    double angle=(Math.PI*2*i)/points;
                    double x=cx+Math.cos(angle)*radius,z=cz+Math.sin(angle)*radius;
                    drawParticleColumn(world,viewer,eye,x,z,baseY,layers,verticalSpacing,viewDistanceSquared,dust);
                }
            } else {
                drawSquareEdgeX(world,viewer,eye,minX,maxX,minZ,baseY,layers,verticalSpacing,viewDistance,viewDistanceSquared,lineSpacing,dust);
                drawSquareEdgeX(world,viewer,eye,minX,maxX,maxZ,baseY,layers,verticalSpacing,viewDistance,viewDistanceSquared,lineSpacing,dust);
                drawSquareEdgeZ(world,viewer,eye,minZ,maxZ,minX,baseY,layers,verticalSpacing,viewDistance,viewDistanceSquared,lineSpacing,dust);
                drawSquareEdgeZ(world,viewer,eye,minZ,maxZ,maxX,baseY,layers,verticalSpacing,viewDistance,viewDistanceSquared,lineSpacing,dust);
            }
        }
    }

    private void drawSquareEdgeX(World world,Player viewer,Location eye,double minX,double maxX,double z,double baseY,int layers,double verticalSpacing,double viewDistance,double viewDistanceSquared,double lineSpacing,Particle.DustOptions dust){
        if(Math.abs(z-eye.getZ())>viewDistance)return;
        double start=Math.max(minX,eye.getX()-viewDistance),end=Math.min(maxX,eye.getX()+viewDistance);
        drawLine(world,viewer,eye,start,end,z,true,baseY,layers,verticalSpacing,viewDistanceSquared,lineSpacing,dust);
    }
    private void drawSquareEdgeZ(World world,Player viewer,Location eye,double minZ,double maxZ,double x,double baseY,int layers,double verticalSpacing,double viewDistance,double viewDistanceSquared,double lineSpacing,Particle.DustOptions dust){
        if(Math.abs(x-eye.getX())>viewDistance)return;
        double start=Math.max(minZ,eye.getZ()-viewDistance),end=Math.min(maxZ,eye.getZ()+viewDistance);
        drawLine(world,viewer,eye,start,end,x,false,baseY,layers,verticalSpacing,viewDistanceSquared,lineSpacing,dust);
    }
    private void drawLine(World world,Player viewer,Location eye,double start,double end,double fixed,boolean horizontal,double baseY,int layers,double verticalSpacing,double viewDistanceSquared,double lineSpacing,Particle.DustOptions dust){
        if(end<start)return;
        int segments=Math.max(1,Math.min(1440,(int)Math.ceil((end-start)/lineSpacing)));
        for(int i=0;i<=segments;i++){
            double along=start+(end-start)*i/segments;
            double x=horizontal?along:fixed,z=horizontal?fixed:along;
            drawParticleColumn(world,viewer,eye,x,z,baseY,layers,verticalSpacing,viewDistanceSquared,dust);
        }
    }
    private void drawParticleColumn(World world,Player viewer,Location eye,double x,double z,double baseY,int layers,double verticalSpacing,double viewDistanceSquared,Particle.DustOptions dust){
        double dx=x-eye.getX(),dz=z-eye.getZ();
        if(dx*dx+dz*dz>viewDistanceSquared)return;
        int chunkX=((int)Math.floor(x))>>4,chunkZ=((int)Math.floor(z))>>4;
        if(!world.isChunkLoaded(chunkX,chunkZ))return;
        for(int layer=0;layer<layers;layer++){
            double y=baseY+layer*verticalSpacing;
            if(y>=world.getMaxHeight())break;
            viewer.spawnParticle(Particle.DUST,x,y,z,1,0,0,0,0,dust,true);
        }
    }
    @EventHandler public void onJoin(PlayerJoinEvent event){Player player=event.getPlayer();if(resourcePack!=null)Bukkit.getScheduler().runTaskLater(this,()->{if(player.isOnline())resourcePack.send(player);},20L);if(!player.hasPermission("auroratoxiczone.version"))return;
        Bukkit.getScheduler().runTaskLater(this,()->checkGitHubAsync(latest->{if(latest==null||!isNewer(latest,getDescription().getVersion()))return;Bukkit.getScheduler().runTask(this,()->{if(player.isOnline()&&player.hasPermission("auroratoxiczone.version"))send(player,"update-available",Map.of("latest",latest,"current",getDescription().getVersion()));});}),20L*Math.max(0,getConfig().getInt("settings.reminder-delay-seconds",3)));
    }
    @EventHandler public void onResourcePackStatus(PlayerResourcePackStatusEvent event){String status=event.getStatus().name();if(status.equals("SUCCESSFULLY_LOADED"))getLogger().info("資源包已由玩家 " + event.getPlayer().getName() + " 載入。");else if(status.equals("DECLINED")||status.equals("FAILED_DOWNLOAD")||status.equals("INVALID_URL"))getLogger().warning("玩家 " + event.getPlayer().getName() + " 的資源包狀態：" + status);}
    private void checkGitHubAsync(java.util.function.Consumer<String> callback){String repo=getConfig().getString("settings.github-repository","").trim();if(repo.isEmpty()){callback.accept(null);return;}if(!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")){getLogger().warning("settings.github-repository 格式無效，請使用 owner/repository。");callback.accept(null);return;}if(cacheValid&&System.currentTimeMillis()-cachedAt<600000){callback.accept(cachedLatest);return;}CompletableFuture<String> active=fetchInFlight;if(active!=null){active.thenAccept(callback);return;}
        CompletableFuture<String> request=CompletableFuture.supplyAsync(()->{try{HttpRequest req=HttpRequest.newBuilder(URI.create("https://api.github.com/repos/"+repo+"/releases/latest")).timeout(Duration.ofSeconds(8)).header("Accept","application/vnd.github+json").header("User-Agent","AuroraToxicZone").GET().build();HttpResponse<String> response=http.send(req,HttpResponse.BodyHandlers.ofString());if(response.statusCode()!=200)return null;Matcher m=Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").matcher(response.body());return m.find()?m.group(1):null;}catch(Exception e){return null;}});fetchInFlight=request;request.thenAccept(v->{cachedLatest=v;cachedAt=System.currentTimeMillis();cacheValid=true;fetchInFlight=null;callback.accept(v);});
    }
    private void tellVersion(CommandSender s,String latest){if(latest==null){send(s,"version-unavailable");return;}String current=getDescription().getVersion();if(isNewer(latest,current))send(s,"update-available",Map.of("latest",latest,"current",current));else if(isNewer(current,latest))send(s,"dev-build");else send(s,"up-to-date",Map.of("current",current));}
    private boolean isNewer(String a,String b){int[] x=parts(a),y=parts(b);if(x==null||y==null)return false;for(int i=0;i<3;i++){if(x[i]!=y[i])return x[i]>y[i];}return false;}
    private int[] parts(String s){Matcher m=VERSION.matcher(s);if(!m.find())return null;return new int[]{Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3))};}
    @Override public List<String> onTabComplete(CommandSender s,Command c,String a,String[] args){if(!s.isOp()&&!s.hasPermission("auroratoxiczone.admin"))return List.of();if(args.length==1)return List.of("arena","help","status","start","stop","center","wand","radius","time","damage","reload","version").stream().filter(x->x.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();if(args[0].equalsIgnoreCase("arena") && args.length==2)return List.of("create","use","list").stream().filter(x->x.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();if(args[0].equalsIgnoreCase("arena") && args.length==3 && args[1].equalsIgnoreCase("use"))return arenaNames().stream().filter(x->x.startsWith(args[2])).toList();if(args[0].equalsIgnoreCase("center")&&args.length==2)return Bukkit.getWorlds().stream().map(World::getName).filter(x->x.startsWith(args[1])).toList();return List.of();}
}
