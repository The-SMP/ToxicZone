package tw.aurora.toxiczone;

import java.util.*;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class ToxicZonePlugin extends JavaPlugin implements Listener {
  YamlConfiguration config;
  Messages messages;
  PluginFiles files;
  ArenaManager arenas;
  ZoneCommands commands;
  ZoneMenus menus;
  SelectionWand wand;
  VersionChecker versions;
  private ParticleDisplay particles;
  private ToxicZoneResourcePack resourcePack;
  private BukkitTask tick;
  boolean reloading;

  @Override
  public void onEnable() {
    try {
      files = new PluginFiles(this);
      files.migrateFolder();
      var loaded = files.load();
      config = loaded.config();
      messages = new Messages(loaded.messages());
      arenas = new ArenaManager(this);
      commands = new ZoneCommands(this);
      menus = new ZoneMenus(this);
      wand = new SelectionWand(this);
      versions = new VersionChecker(this);
      particles = new ParticleDisplay(this);
      arenas.load();
      arenas.persist();
      var command = Objects.requireNonNull(getCommand("toxiczone"));
      command.setExecutor(commands);
      command.setTabCompleter(commands);
      var events = getServer().getPluginManager();
      events.registerEvents(this, this);
      events.registerEvents(menus, this);
      events.registerEvents(wand, this);
      tick =
          Bukkit.getScheduler()
              .runTaskTimer(
                  this,
                  () -> {
                    if (!reloading) arenas.tick();
                  },
                  2,
                  2);
      particles.restart();
      if (!getDataFolder().toPath().resolve("resource-pack.zip").toFile().exists())
        saveResource("resource-pack.zip", false);
      var hub = events.getPlugin("HttpHub");
      if (hub != null && hub.isEnabled()) {
        resourcePack = new ToxicZoneResourcePack(this);
        resourcePack.start();
      } else messages.send(Bukkit.getConsoleSender(), "pack-hub-missing");
      versions.startup();
    } catch (Exception e) {
      getLogger().severe("ToxicZone 啟動失敗 " + e.getMessage());
      getServer().getPluginManager().disablePlugin(this);
    }
  }

  void invalidateParticles() {
    if (particles != null) particles.invalidate();
  }

  boolean admin(CommandSender sender) {
    if (sender.isOp() || sender.hasPermission("auroratoxiczone.admin")) return true;
    messages.send(sender, "no-permission");
    return false;
  }

  void reload(CommandSender sender) {
    if (reloading) {
      messages.send(sender, "reload-busy");
      return;
    }
    reloading = true;
    menus.closeAll();
    wand.clear();
    particles.stop();
    UUID playerId = sender instanceof Player player ? player.getUniqueId() : null;
    files
        .reload()
        .whenComplete(
            (loaded, error) -> {
              if (!isEnabled()) return;
              Bukkit.getScheduler()
                  .runTask(
                      this,
                      () -> {
                        CommandSender recipient =
                            playerId == null
                                ? Bukkit.getConsoleSender()
                                : Bukkit.getPlayer(playerId);
                        try {
                          if (error != null) throw new IllegalStateException(error);
                          Map<String, Set<UUID>> eliminated = new HashMap<>();
                          arenas.arenas.forEach(
                              (id, arena) -> eliminated.put(id, Set.copyOf(arena.eliminated)));
                          config = loaded.config();
                          messages.replace(loaded.messages());
                          arenas.load();
                          arenas.arenas.forEach(
                              (id, arena) -> {
                                if (arena.running)
                                  arena.eliminated.addAll(eliminated.getOrDefault(id, Set.of()));
                              });
                          arenas.persist();
                          if (recipient != null) messages.send(recipient, "reload");
                        } catch (Exception e) {
                          if (recipient != null) messages.send(recipient, "reload-failed");
                          getLogger().warning("ToxicZone reload " + e.getMessage());
                        } finally {
                          reloading = false;
                          particles.restart();
                        }
                      });
            });
  }

  @EventHandler
  public void death(PlayerDeathEvent event) {
    arenas.died(event.getEntity());
  }

  @EventHandler
  public void join(PlayerJoinEvent event) {
    UUID id = event.getPlayer().getUniqueId();
    Bukkit.getScheduler()
        .runTaskLater(
            this,
            () -> {
              Player player = Bukkit.getPlayer(id);
              if (player != null && resourcePack != null) resourcePack.send(player);
            },
            20);
    if (!config.getBoolean("settings.update-notifications", true)) return;
    long delay =
        Math.max(0, Math.min(60, config.getLong("settings.reminder-delay-seconds", 3))) * 20;
    Bukkit.getScheduler()
        .runTaskLater(
            this,
            () -> {
              Player player = Bukkit.getPlayer(id);
              if (player == null
                  || !player.hasPermission("auroratoxiczone.version")
                  || !config.getBoolean("settings.update-notifications", true)) return;
              versions.check(
                  latest -> {
                    Player online = Bukkit.getPlayer(id);
                    if (online != null
                        && online.hasPermission("auroratoxiczone.version")
                        && config.getBoolean("settings.update-notifications", true)
                        && VersionChecker.newer(latest, getDescription().getVersion())) {
                      messages.send(
                          online,
                          "update-available",
                          Map.of("latest", latest, "current", getDescription().getVersion()));
                    }
                  });
            },
            delay);
  }

  @EventHandler
  public void packStatus(PlayerResourcePackStatusEvent event) {
    String status = event.getStatus().name();
    if (status.equals("SUCCESSFULLY_LOADED"))
      messages.send(
          Bukkit.getConsoleSender(), "pack-loaded", Map.of("player", event.getPlayer().getName()));
    if (Set.of("DECLINED", "FAILED_DOWNLOAD", "INVALID_URL").contains(status)) {
      messages.send(
          Bukkit.getConsoleSender(),
          "pack-failed",
          Map.of("player", event.getPlayer().getName(), "status", status));
    }
  }

  @Override
  public void onDisable() {
    if (tick != null) tick.cancel();
    if (particles != null) particles.stop();
    if (menus != null) menus.closeAll();
    if (arenas != null && config != null) arenas.persist();
    if (resourcePack != null) resourcePack.stop();
    if (versions != null) versions.close();
    if (files != null) files.close();
  }
}
