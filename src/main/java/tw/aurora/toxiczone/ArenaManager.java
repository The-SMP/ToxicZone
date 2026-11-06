package tw.aurora.toxiczone;

import java.util.*;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

final class ArenaManager {
  private final ToxicZonePlugin plugin;
  final Map<String, Arena> arenas = new LinkedHashMap<>();
  private final Map<UUID, String> selected = new HashMap<>();

  ArenaManager(ToxicZonePlugin plugin) {
    this.plugin = plugin;
  }

  void load() {
    arenas.clear();
    var config = plugin.config;
    if (!config.getBoolean("arena.deleted"))
      read("default", config.getConfigurationSection("arena"), true);
    var section = config.getConfigurationSection("arenas");
    if (section != null)
      for (String id : section.getKeys(false)) {
        if (id.matches("[a-z0-9_-]{1,32}") && !id.equals("default"))
          read(id, section.getConfigurationSection(id), false);
      }
    selected.values().removeIf(id -> !arenas.containsKey(id));
  }

  static boolean validName(String id) {
    return id.matches("[a-z0-9_-]{1,32}")
        && !Set.of(
                "arena", "start", "stop", "status", "help", "center", "radius", "time", "damage",
                "reload", "version", "wand")
            .contains(id);
  }

  private void read(String id, ConfigurationSection c, boolean legacy) {
    if (c == null) return;
    Arena a = new Arena(id);
    a.token = c.getString("token", a.token);
    a.world = c.getString("world", "world");
    a.x = finite(c.getDouble("center-x", .5), .5);
    a.z = finite(c.getDouble("center-z", .5), .5);
    a.start = positive(c.getDouble("start-radius", 200), 200);
    a.minimum = Math.min(a.start, positive(c.getDouble("minimum-radius", 5), 5));
    a.duration = Math.max(1, Math.min(31536000L, c.getLong("shrink-duration-seconds", 1800)));
    a.damage =
        positive(
            legacy ? plugin.config.getDouble("settings.damage", 3) : c.getDouble("damage", 3), 3);
    a.interval =
        Math.max(
            .1,
            positive(
                legacy
                    ? plugin.config.getDouble("settings.damage-interval-seconds", 1)
                    : c.getDouble("damage-interval-seconds", 1),
                1));
    if (c.getBoolean("selection-boundary.enabled")) {
      a.bounds(
          finite(c.getDouble("selection-boundary.min-x"), a.x - a.start),
          finite(c.getDouble("selection-boundary.min-z"), a.z - a.start),
          finite(c.getDouble("selection-boundary.max-x"), a.x + a.start),
          finite(c.getDouble("selection-boundary.max-z"), a.z + a.start));
    }
    a.startedAt = c.getLong("started-at");
    a.running = c.getBoolean("running") && a.startedAt > 0;
    a.announcedStage = c.getInt("announced-stage", 0);
    for (String uuid : c.getStringList("eliminated-players")) {
      try {
        a.eliminated.add(UUID.fromString(uuid));
      } catch (IllegalArgumentException ignored) {
      }
    }
    arenas.put(id, a);
  }

  private static double finite(double n, double fallback) {
    return Double.isFinite(n) ? n : fallback;
  }

  private static double positive(double n, double fallback) {
    return Double.isFinite(n) && n > 0 ? n : fallback;
  }

  Arena selected(Player player) {
    return arenas.get(selected.getOrDefault(player.getUniqueId(), "default"));
  }

  void select(Player player, String id) {
    selected.put(player.getUniqueId(), id);
    if (plugin.wand != null) plugin.wand.forget(player.getUniqueId());
  }

  void forget(UUID player) {
    selected.remove(player);
  }

  Arena create(String id, Location location) {
    if (!validName(id) || arenas.containsKey(id)) return null;
    Arena a = new Arena(id);
    a.world = location.getWorld().getName();
    a.x = location.getX();
    a.z = location.getZ();
    a.start = 200;
    a.minimum = 5;
    a.duration = 1800;
    a.damage = 3;
    a.interval = 1;
    arenas.put(id, a);
    persist();
    return a;
  }

  void delete(Arena arena) {
    arenas.remove(arena.id);
    selected.values().removeIf(id -> id.equals(arena.id));
    if (arena.id.equals("default")) plugin.config.set("arena.deleted", true);
    else plugin.config.set("arenas." + arena.id, null);
    plugin.menus.closeArena(arena.id);
    persist();
  }

  void persist() {
    for (Arena a : arenas.values()) {
      String base = a.id.equals("default") ? "arena" : "arenas." + a.id;
      ConfigurationSection c = plugin.config.getConfigurationSection(base);
      if (c == null) c = plugin.config.createSection(base);
      c.set("deleted", false);
      c.set("token", a.token);
      c.set("world", a.world);
      c.set("center-x", a.x);
      c.set("center-z", a.z);
      c.set("start-radius", a.start);
      c.set("minimum-radius", a.minimum);
      c.set("shrink-duration-seconds", a.duration);
      c.set("running", a.running);
      c.set("started-at", a.startedAt);
      c.set("announced-stage", a.announcedStage);
      c.set("selection-boundary.enabled", a.bounded);
      c.set("selection-boundary.min-x", a.minX);
      c.set("selection-boundary.max-x", a.maxX);
      c.set("selection-boundary.min-z", a.minZ);
      c.set("selection-boundary.max-z", a.maxZ);
      c.set("eliminated-players", a.eliminated.stream().map(UUID::toString).toList());
      if (a.id.equals("default")) {
        plugin.config.set("settings.damage", a.damage);
        plugin.config.set("settings.damage-interval-seconds", a.interval);
      } else {
        c.set("damage", a.damage);
        c.set("damage-interval-seconds", a.interval);
      }
    }
    plugin.invalidateParticles();
    plugin.files.save(plugin.config);
  }

  String start(Arena a) {
    if (a.running) return "arena-running";
    if (!a.bounded) a.bounds(a.x - a.start, a.z - a.start, a.x + a.start, a.z + a.start);
    for (Arena other : arenas.values())
      if (other != a && other.running && a.overlaps(other)) return "arena-overlap";
    a.running = true;
    a.startedAt = System.currentTimeMillis();
    a.lastDamage = 0;
    a.announcedStage = 0;
    a.eliminated.clear();
    persist();
    return "started";
  }

  void stop(Arena a) {
    a.start = a.radius(System.currentTimeMillis());
    a.minimum = Math.min(a.minimum, a.start);
    a.running = false;
    persist();
  }

  void tick() {
    long now = System.currentTimeMillis();
    for (Arena a : arenas.values()) {
      if (!a.running) continue;
      World world = Bukkit.getWorld(a.world);
      if (world == null) continue;
      int stage = Math.min(4, (int) (Math.max(0, now - a.startedAt) / (a.duration * 250.0)));
      if (stage > a.announcedStage) {
        a.announcedStage = stage;
        persist();
        for (Player p : world.getPlayers())
          if (inside(a, p.getLocation())) {
            plugin.messages.send(
                p,
                stage == 4 ? "shrink-complete" : "zone-shrunk",
                Map.of("arena", a.id, "radius", Messages.number(a.radius(now))));
          }
      }
      if (now - a.lastDamage < a.interval * 1000) continue;
      a.lastDamage = now;
      double radius = a.radius(now);
      for (Player p : world.getPlayers()) {
        if (a.eliminated.contains(p.getUniqueId())
            || p.isDead()
            || p.getHealth() <= 0
            || p.getGameMode() == GameMode.SPECTATOR) continue;
        Location l = p.getLocation();
        double dx = l.getX() - a.x, dz = l.getZ() - a.z;
        if (inside(a, l) && dx * dx + dz * dz > radius * radius) p.damage(a.damage);
      }
    }
  }

  static boolean inside(Arena a, Location l) {
    return a.contains(l.getWorld().getName(), l.getX(), l.getZ());
  }

  void died(Player player) {
    boolean changed = false;
    for (Arena a : arenas.values()) {
      if (a.running && inside(a, player.getLocation()))
        changed |= a.eliminated.add(player.getUniqueId());
    }
    if (changed && !plugin.reloading) persist();
  }
}
