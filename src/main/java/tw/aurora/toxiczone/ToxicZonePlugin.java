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
    private long startedAt;
    private long lastDamageAt;
    private volatile long cachedAt;
    private volatile String cachedLatest;
    private volatile boolean cacheValid;
    private volatile CompletableFuture<String> fetchInFlight;
    private BukkitTask tickTask;
    private BukkitTask particleTask;
    private BukkitTask configSaveTask;
    private boolean configDirty;
    private NamespacedKey wandKey;
    private final Map<UUID, RegionSelection> selections = new HashMap<>();
    private final Set<UUID> eliminatedPlayers = new HashSet<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final Pattern VERSION = Pattern.compile("[vV]?(\\d+)\\.(\\d+)\\.(\\d+)");

    @Override public void onEnable() {
        ensureConfig(); loadMessages();
        loadEliminatedPlayers();
        wandKey = new NamespacedKey(this, "region_wand");
        PluginCommand command = getCommand("toxiczone");
        if (command != null) { command.setExecutor(this); command.setTabCompleter(this); }
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getConfig().getBoolean("arena.running", false)) startedAt = System.currentTimeMillis();
        tickTask = Bukkit.getScheduler().runTaskTimer(this, this::tickZone, 20L, 20L);
        long particleInterval = Math.max(2L, getConfig().getLong("settings.particles.interval-ticks", 10L));
        particleTask = Bukkit.getScheduler().runTaskTimer(this, this::renderBoundary, particleInterval, particleInterval);
        checkGitHubAsync(result -> { if (result == null) getLogger().info("GitHub 版本檢查未取得可用的 Release 資訊。"); else getLogger().info("GitHub 最新版本：" + result + (isNewer(getDescription().getVersion(), result) ? "（目前版本為開發版）" : "")); });
    }

    @Override public void onDisable() { if (tickTask != null) tickTask.cancel(); if (particleTask != null) particleTask.cancel(); if (configSaveTask != null) configSaveTask.cancel(); if (configDirty) saveConfig(); selections.clear(); eliminatedPlayers.clear(); }

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

    private void loadEliminatedPlayers() {
        eliminatedPlayers.clear();
        if (!getConfig().getBoolean("arena.running", false)) return;
        for (String value : getConfig().getStringList("arena.eliminated-players")) {
            try { eliminatedPlayers.add(UUID.fromString(value)); }
            catch (IllegalArgumentException ignored) { getLogger().warning("忽略無效的淘汰玩家 UUID 設定值。"); }
        }
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
        if (args.length == 0) {
            if (sender instanceof Player player) openMenu(player); else send(sender,"only-player");
            return true;
        }
        if (args[0].equalsIgnoreCase("help")) { send(sender,"help"); return true; }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("version")) { if (!versionPerm(sender)) return true; checkGitHubAsync(latest -> Bukkit.getScheduler().runTask(this, () -> tellVersion(sender, latest))); return true; }
        switch (sub) {
            case "status" -> {
                double radius = currentRadius(); long remain = Math.max(0, getConfig().getLong("arena.shrink-duration-seconds") - (System.currentTimeMillis()-startedAt)/1000);
                send(sender,"status",Map.of("world",getConfig().getString("arena.world","world"),"x",getConfig().getDouble("arena.center-x"),"z",getConfig().getDouble("arena.center-z"),"radius",String.format(Locale.ROOT,"%.1f",radius),"minimum",getConfig().getDouble("arena.minimum-radius"),"remaining",remain,"state",messages.getString(getConfig().getBoolean("arena.running")?"state-running":"state-stopped")));
            }
            case "start" -> startZone(sender);
            case "stop" -> stopZone(sender);
            case "center" -> setCenter(sender,args);
            case "wand" -> giveWand(sender);
            case "radius" -> setRadius(sender,args);
            case "time" -> setTime(sender,args);
            case "damage" -> setDamage(sender,args);
            case "reload" -> { if (configDirty) { if (configSaveTask != null) configSaveTask.cancel(); saveConfig(); configDirty=false; configSaveTask=null; } ensureConfig(); loadMessages(); send(sender,"reload"); }
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
        getConfig().set("arena.world",world);getConfig().set("arena.center-x",x);getConfig().set("arena.center-z",z);getConfig().set("arena.selection-boundary.enabled",false);markConfigDirty();
        send(s,"center-set",Map.of("world",world,"x",x,"z",z));
    }
    private void setRadius(CommandSender s,String[] a){try{if(a.length<2||a.length>3)throw new Exception();double r=Double.parseDouble(a[1]),m=a.length==3?Double.parseDouble(a[2]):getConfig().getDouble("arena.minimum-radius");if(!Double.isFinite(r)||!Double.isFinite(m)||r<=0||m<=0||m>r)throw new Exception();getConfig().set("arena.start-radius",r);getConfig().set("arena.minimum-radius",m);markConfigDirty();send(s,"radius-set",Map.of("radius",r,"minimum",m));}catch(Exception e){send(s,"radius-usage");}}
    private void setTime(CommandSender s,String[] a){try{long n=Long.parseLong(a[1]);if(a.length!=2||n<1)throw new Exception();getConfig().set("arena.shrink-duration-seconds",n);markConfigDirty();send(s,"time-set",Map.of("seconds",n));}catch(Exception e){send(s,"time-usage");}}
    private void setDamage(CommandSender s,String[] a){try{double d=Double.parseDouble(a[1]),i=a.length==3?Double.parseDouble(a[2]):getConfig().getDouble("settings.damage-interval-seconds");if(a.length<2||a.length>3||!Double.isFinite(d)||!Double.isFinite(i)||d<=0||i<0.1)throw new Exception();getConfig().set("settings.damage",d);getConfig().set("settings.damage-interval-seconds",i);markConfigDirty();send(s,"damage-set",Map.of("damage",d,"interval",i,"hearts",d/2));}catch(Exception e){send(s,"damage-usage");}}

    private void startZone(CommandSender sender) {
        eliminatedPlayers.clear(); getConfig().set("arena.eliminated-players",List.of());
        if(!getConfig().getBoolean("arena.selection-boundary.enabled")){
            double x=getConfig().getDouble("arena.center-x"),z=getConfig().getDouble("arena.center-z"),radius=getConfig().getDouble("arena.start-radius");
            saveSelectionBoundary(x-radius,x+radius,z-radius,z+radius);
        }
        startedAt=System.currentTimeMillis(); lastDamageAt=0; getConfig().set("arena.running",true); markConfigDirty(); send(sender,"started");
    }
    private void stopZone(CommandSender sender) { double frozen=currentRadius(); getConfig().set("arena.start-radius",frozen); getConfig().set("arena.minimum-radius",Math.min(getConfig().getDouble("arena.minimum-radius"),frozen)); getConfig().set("arena.running",false); markConfigDirty(); send(sender,"stopped"); }

    private void openMenu(Player player) {
        String title = format("gui-title", Map.of());
        ToxicZoneMenuHolder holder = new ToxicZoneMenuHolder(this, title);
        Inventory inventory = holder.getInventory();
        ItemStack filler = guiItem("gui-filler", Material.GRAY_STAINED_GLASS_PANE, Map.of());
        for (int slot=0; slot<inventory.getSize(); slot++) inventory.setItem(slot, filler);
        String state = messages.getString(getConfig().getBoolean("arena.running") ? "state-running" : "state-stopped", "");
        Map<String,Object> status = new HashMap<>();
        status.put("world",getConfig().getString("arena.world","world")); status.put("x",fmt(getConfig().getDouble("arena.center-x"))); status.put("z",fmt(getConfig().getDouble("arena.center-z")));
        status.put("radius",fmt(currentRadius())); status.put("minimum",fmt(getConfig().getDouble("arena.minimum-radius"))); status.put("state",ChatColor.translateAlternateColorCodes('&',state));
        inventory.setItem(4,guiItem("gui-status",Material.MAP,status));
        inventory.setItem(10,guiItem("gui-center",Material.COMPASS,Map.of()));
        inventory.setItem(12,guiItem("gui-radius-down",Material.RED_DYE,Map.of())); inventory.setItem(13,guiItem("gui-radius",Material.PAPER,Map.of("value",fmt(getConfig().getDouble("arena.start-radius"))))); inventory.setItem(14,guiItem("gui-radius-up",Material.LIME_DYE,Map.of()));
        inventory.setItem(16,guiItem("gui-minimum-down",Material.RED_DYE,Map.of())); inventory.setItem(17,guiItem("gui-minimum",Material.PAPER,Map.of("value",fmt(getConfig().getDouble("arena.minimum-radius"))))); inventory.setItem(18,guiItem("gui-minimum-up",Material.LIME_DYE,Map.of()));
        inventory.setItem(21,guiItem("gui-time-down",Material.RED_DYE,Map.of())); inventory.setItem(22,guiItem("gui-time",Material.CLOCK,Map.of("value",getConfig().getLong("arena.shrink-duration-seconds")))); inventory.setItem(23,guiItem("gui-time-up",Material.LIME_DYE,Map.of()));
        inventory.setItem(25,guiItem("gui-damage-down",Material.RED_DYE,Map.of())); inventory.setItem(26,guiItem("gui-damage",Material.PAPER,Map.of("value",fmt(getConfig().getDouble("settings.damage"))))); inventory.setItem(27,guiItem("gui-damage-up",Material.LIME_DYE,Map.of()));
        inventory.setItem(30,guiItem("gui-interval-down",Material.RED_DYE,Map.of())); inventory.setItem(31,guiItem("gui-interval",Material.CLOCK,Map.of("value",fmt(getConfig().getDouble("settings.damage-interval-seconds"))))); inventory.setItem(32,guiItem("gui-interval-up",Material.LIME_DYE,Map.of()));
        inventory.setItem(34,guiItem("gui-wand",Material.CARROT_ON_A_STICK,Map.of()));
        inventory.setItem(40,guiItem(getConfig().getBoolean("arena.running")?"gui-stop":"gui-start",getConfig().getBoolean("arena.running")?Material.BARRIER:Material.LIME_CONCRETE,Map.of()));
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
        switch(event.getRawSlot()) {
            case 10 -> { Location loc=player.getLocation(); getConfig().set("arena.world",loc.getWorld().getName()); getConfig().set("arena.center-x",loc.getX()); getConfig().set("arena.center-z",loc.getZ()); getConfig().set("arena.selection-boundary.enabled",false); markConfigDirty(); send(player,"center-set",Map.of("world",loc.getWorld().getName(),"x",fmt(loc.getX()),"z",fmt(loc.getZ()))); }
            case 12 -> adjustRadius(-10); case 14 -> adjustRadius(10);
            case 16 -> adjustMinimum(-1); case 18 -> adjustMinimum(1);
            case 21 -> adjustTime(-60); case 23 -> adjustTime(60);
            case 25 -> adjustDamage(-0.5); case 27 -> adjustDamage(0.5);
            case 30 -> adjustInterval(-0.5); case 32 -> adjustInterval(0.5);
            case 34 -> giveWand(player);
            case 40 -> { if(getConfig().getBoolean("arena.running")) stopZone(player); else startZone(player); }
            case 42 -> { flushConfig(); ensureConfig(); loadMessages(); send(player,"reload"); }
            default -> { return; }
        }
        if(player.isOnline() && player.getOpenInventory().getTopInventory().getHolder() instanceof ToxicZoneMenuHolder) openMenu(player);
    }

    @EventHandler public void onMenuDrag(InventoryDragEvent event) {
        Inventory top=event.getView().getTopInventory();
        if(top.getHolder() instanceof ToxicZoneMenuHolder holder && holder.getPlugin()==this) event.setCancelled(true);
    }

    private void adjustRadius(double delta) { double radius=Math.max(1,getConfig().getDouble("arena.start-radius")+delta); getConfig().set("arena.start-radius",radius); if(getConfig().getDouble("arena.minimum-radius")>radius)getConfig().set("arena.minimum-radius",radius); markConfigDirty(); }
    private void adjustMinimum(double delta) { double radius=getConfig().getDouble("arena.start-radius"),min=Math.max(1,Math.min(radius,getConfig().getDouble("arena.minimum-radius")+delta));getConfig().set("arena.minimum-radius",min);markConfigDirty(); }
    private void adjustTime(long delta) { getConfig().set("arena.shrink-duration-seconds",Math.max(60,getConfig().getLong("arena.shrink-duration-seconds")+delta));markConfigDirty(); }
    private void adjustDamage(double delta) { getConfig().set("settings.damage",Math.max(0.5,getConfig().getDouble("settings.damage")+delta));markConfigDirty(); }
    private void adjustInterval(double delta) { getConfig().set("settings.damage-interval-seconds",Math.max(0.5,getConfig().getDouble("settings.damage-interval-seconds")+delta));markConfigDirty(); }

    private void flushConfig() { if(configSaveTask!=null)configSaveTask.cancel(); if(configDirty){saveConfig();configDirty=false;} configSaveTask=null; }

    private void giveWand(CommandSender sender) {
        if (!(sender instanceof Player player)) { send(sender,"only-player"); return; }
        ItemStack item = new ItemStack(Material.CARROT_ON_A_STICK);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', messages.getString("wand-name", "區域選取釣竿")));
        meta.setLore(List.of(ChatColor.translateAlternateColorCodes('&', messages.getString("wand-lore", "左鍵選第一角，右鍵選第二角。"))));
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        overflow.values().forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        send(player,"wand-given");
    }

    @EventHandler public void onWandUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getItem() == null || wandKey == null) return;
        ItemMeta meta = event.getItem().getItemMeta();
        if (meta == null || !meta.getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE)) return;
        Action action = event.getAction();
        Player player = event.getPlayer();
        if (action == Action.RIGHT_CLICK_AIR) { event.setCancelled(true); return; }
        if ((action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) || event.getClickedBlock() == null) return;
        event.setCancelled(true);
        if (!player.isOp() && !player.hasPermission("auroratoxiczone.admin")) { send(player,"no-permission"); return; }
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
        getConfig().set("arena.world", first.worldName());
        getConfig().set("arena.center-x", centerX);
        getConfig().set("arena.center-z", centerZ);
        getConfig().set("arena.start-radius", radius);
        getConfig().set("arena.minimum-radius", Math.min(getConfig().getDouble("arena.minimum-radius", 5.0), radius));
        saveSelectionBoundary(Math.min(first.x(),second.x()),Math.max(first.x(),second.x())+1.0,Math.min(first.z(),second.z()),Math.max(first.z(),second.z())+1.0);
        if (getConfig().getBoolean("arena.running")) { startedAt = System.currentTimeMillis(); lastDamageAt = 0; }
        markConfigDirty();
        selections.remove(player.getUniqueId());
        send(player,"selection-complete",Map.of("x",String.format(Locale.ROOT,"%.1f",centerX),"z",String.format(Locale.ROOT,"%.1f",centerZ),"radius",String.format(Locale.ROOT,"%.1f",radius)));
    }

    private void saveSelectionBoundary(double minX,double maxX,double minZ,double maxZ) {
        getConfig().set("arena.selection-boundary.enabled",true);
        getConfig().set("arena.selection-boundary.min-x",minX);
        getConfig().set("arena.selection-boundary.max-x",maxX);
        getConfig().set("arena.selection-boundary.min-z",minZ);
        getConfig().set("arena.selection-boundary.max-z",maxZ);
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { selections.remove(event.getPlayer().getUniqueId()); }
    @EventHandler public void onDeath(PlayerDeathEvent event) {
        if (!getConfig().getBoolean("arena.running")) return;
        eliminatedPlayers.add(event.getEntity().getUniqueId());
        getConfig().set("arena.eliminated-players",eliminatedPlayers.stream().map(UUID::toString).toList());
        markConfigDirty();
    }

    private double currentRadius(){double start=getConfig().getDouble("arena.start-radius",200),min=getConfig().getDouble("arena.minimum-radius",5);if(!getConfig().getBoolean("arena.running"))return start;double duration=Math.max(1,getConfig().getDouble("arena.shrink-duration-seconds",1800));double progress=Math.min(1,(System.currentTimeMillis()-startedAt)/1000.0/duration);return start-(start-min)*progress;}
    private void tickZone(){
        if(!getConfig().getBoolean("arena.running"))return;
        long now=System.currentTimeMillis();
        double duration=Math.max(1,getConfig().getDouble("arena.shrink-duration-seconds",1800));
        if((now-startedAt)/1000.0>=duration){
            double minimum=getConfig().getDouble("arena.minimum-radius",5.0);
            getConfig().set("arena.start-radius",minimum);
            getConfig().set("arena.running",false);
            markConfigDirty();
            World arenaWorld=Bukkit.getWorld(getConfig().getString("arena.world","world"));
            if(arenaWorld!=null)for(Player player:arenaWorld.getPlayers())send(player,"shrink-complete",Map.of("radius",fmt(minimum)));
            return;
        }
        String worldName=getConfig().getString("arena.world","world");World world=Bukkit.getWorld(worldName);if(world==null)return;double radius=currentRadius();
        double interval=Math.max(.1,getConfig().getDouble("settings.damage-interval-seconds",1));if(now-lastDamageAt<interval*1000)return;lastDamageAt=now;double cx=getConfig().getDouble("arena.center-x"),cz=getConfig().getDouble("arena.center-z"),damage=Math.max(.1,getConfig().getDouble("settings.damage",3));
        for(Player p:world.getPlayers()){
            UUID playerId=p.getUniqueId();
            if(eliminatedPlayers.contains(playerId)||p.isDead()||p.getHealth()<=0.0||p.getGameMode()==org.bukkit.GameMode.SPECTATOR)continue;
            Location l=p.getLocation();double dx=l.getX()-cx,dz=l.getZ()-cz;
            if(dx*dx+dz*dz>radius*radius&&!p.isDead()&&p.getHealth()>0.0)p.damage(damage);
        }
    }
    private void renderBoundary(){
        if(!getConfig().getBoolean("settings.particles.enabled",true))return;
        World world=Bukkit.getWorld(getConfig().getString("arena.world","world"));
        if(world==null||world.getPlayers().isEmpty())return;
        double cx=getConfig().getDouble("arena.center-x"),cz=getConfig().getDouble("arena.center-z");
        int maxPoints=Math.max(24,Math.min(1440,getConfig().getInt("settings.particles.points",720)));
        int layers=Math.max(4,Math.min(24,getConfig().getInt("settings.particles.layers",12)));
        double verticalSpacing=Math.max(0.5,Math.min(2.0,getConfig().getDouble("settings.particles.vertical-spacing",1.0)));
        double viewDistance=Math.max(24,Math.min(128,getConfig().getDouble("settings.particles.view-distance",64.0)));
        double viewDistanceSquared=viewDistance*viewDistance;
        float dustSize=(float)Math.max(1.0,Math.min(4.0,getConfig().getDouble("settings.particles.dust-size",2.0)));
        Particle.DustOptions dust=new Particle.DustOptions(Color.fromRGB(70,255,90),dustSize);
        boolean running=getConfig().getBoolean("arena.running");
        double radius=currentRadius();
        double minX,maxX,minZ,maxZ;
        if(getConfig().getBoolean("arena.selection-boundary.enabled")){
            minX=getConfig().getDouble("arena.selection-boundary.min-x");maxX=getConfig().getDouble("arena.selection-boundary.max-x");
            minZ=getConfig().getDouble("arena.selection-boundary.min-z");maxZ=getConfig().getDouble("arena.selection-boundary.max-z");
        } else {
            minX=cx-getConfig().getDouble("arena.start-radius");maxX=cx+getConfig().getDouble("arena.start-radius");
            minZ=cz-getConfig().getDouble("arena.start-radius");maxZ=cz+getConfig().getDouble("arena.start-radius");
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
    @EventHandler public void onJoin(PlayerJoinEvent event){Player player=event.getPlayer();if(!player.hasPermission("auroratoxiczone.version"))return;
        Bukkit.getScheduler().runTaskLater(this,()->checkGitHubAsync(latest->{if(latest==null||!isNewer(latest,getDescription().getVersion()))return;Bukkit.getScheduler().runTask(this,()->{if(player.isOnline()&&player.hasPermission("auroratoxiczone.version"))send(player,"update-available",Map.of("latest",latest,"current",getDescription().getVersion()));});}),20L*Math.max(0,getConfig().getInt("settings.reminder-delay-seconds",3)));
    }
    private void checkGitHubAsync(java.util.function.Consumer<String> callback){String repo=getConfig().getString("settings.github-repository","").trim();if(repo.isEmpty()){callback.accept(null);return;}if(!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")){getLogger().warning("settings.github-repository 格式無效，請使用 owner/repository。");callback.accept(null);return;}if(cacheValid&&System.currentTimeMillis()-cachedAt<600000){callback.accept(cachedLatest);return;}CompletableFuture<String> active=fetchInFlight;if(active!=null){active.thenAccept(callback);return;}
        CompletableFuture<String> request=CompletableFuture.supplyAsync(()->{try{HttpRequest req=HttpRequest.newBuilder(URI.create("https://api.github.com/repos/"+repo+"/releases/latest")).timeout(Duration.ofSeconds(8)).header("Accept","application/vnd.github+json").header("User-Agent","AuroraToxicZone").GET().build();HttpResponse<String> response=http.send(req,HttpResponse.BodyHandlers.ofString());if(response.statusCode()!=200)return null;Matcher m=Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").matcher(response.body());return m.find()?m.group(1):null;}catch(Exception e){return null;}});fetchInFlight=request;request.thenAccept(v->{cachedLatest=v;cachedAt=System.currentTimeMillis();cacheValid=true;fetchInFlight=null;callback.accept(v);});
    }
    private void tellVersion(CommandSender s,String latest){if(latest==null){send(s,"version-unavailable");return;}String current=getDescription().getVersion();if(isNewer(latest,current))send(s,"update-available",Map.of("latest",latest,"current",current));else if(isNewer(current,latest))send(s,"dev-build");else send(s,"up-to-date",Map.of("current",current));}
    private boolean isNewer(String a,String b){int[] x=parts(a),y=parts(b);if(x==null||y==null)return false;for(int i=0;i<3;i++){if(x[i]!=y[i])return x[i]>y[i];}return false;}
    private int[] parts(String s){Matcher m=VERSION.matcher(s);if(!m.find())return null;return new int[]{Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3))};}
    @Override public List<String> onTabComplete(CommandSender s,Command c,String a,String[] args){if(!s.isOp()&&!s.hasPermission("auroratoxiczone.admin"))return List.of();if(args.length==1)return List.of("help","status","start","stop","center","wand","radius","time","damage","reload","version").stream().filter(x->x.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();if(args[0].equalsIgnoreCase("center")&&args.length==2)return Bukkit.getWorlds().stream().map(World::getName).filter(x->x.startsWith(args[1])).toList();return List.of();}
}
