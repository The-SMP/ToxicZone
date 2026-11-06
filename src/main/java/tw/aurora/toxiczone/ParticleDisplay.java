package tw.aurora.toxiczone;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

final class ParticleDisplay {
  private record View(UUID player, UUID world, double x, double y, double z, int minY, int maxY) {}

  private record Shape(
      boolean circle,
      double x,
      double z,
      double radius,
      double minX,
      double maxX,
      double minZ,
      double maxZ,
      List<View> views) {}

  private record Point(UUID player, UUID world, double x, double y, double z) {}

  private record Settings(
      int points, int layers, double vertical, double distance, double spacing, float size) {}

  private final ToxicZonePlugin plugin;
  private final AtomicBoolean calculating = new AtomicBoolean();
  private BukkitTask task;
  private int generation;

  ParticleDisplay(ToxicZonePlugin plugin) {
    this.plugin = plugin;
  }

  void restart() {
    generation++;
    if (task != null) task.cancel();
    long interval = Math.max(2, plugin.config.getLong("settings.particles.interval-ticks", 10));
    task = Bukkit.getScheduler().runTaskTimer(plugin, this::capture, interval, interval);
  }

  void invalidate() {
    generation++;
  }

  void stop() {
    generation++;
    if (task != null) task.cancel();
  }

  private void capture() {
    if (!plugin.config.getBoolean("settings.particles.enabled", true)
        || !calculating.compareAndSet(false, true)) return;
    Settings settings =
        new Settings(
            Math.max(24, Math.min(1440, plugin.config.getInt("settings.particles.points", 720))),
            Math.max(1, Math.min(24, plugin.config.getInt("settings.particles.layers", 12))),
            Math.max(
                .5, Math.min(2, plugin.config.getDouble("settings.particles.vertical-spacing", 1))),
            Math.max(
                24, Math.min(128, plugin.config.getDouble("settings.particles.view-distance", 64))),
            Math.max(
                .5, Math.min(2, plugin.config.getDouble("settings.particles.line-spacing", .75))),
            (float)
                Math.max(
                    1, Math.min(4, plugin.config.getDouble("settings.particles.dust-size", 2))));
    List<Shape> shapes = new ArrayList<>();
    for (Arena a : plugin.arenas.arenas.values()) {
      World world = Bukkit.getWorld(a.world);
      if (world == null) continue;
      List<View> viewers = new ArrayList<>();
      for (Player player : world.getPlayers()) {
        Location l = player.getLocation();
        viewers.add(
            new View(
                player.getUniqueId(),
                world.getUID(),
                l.getX(),
                l.getY(),
                l.getZ(),
                world.getMinHeight(),
                world.getMaxHeight()));
      }
      double minX = a.bounded ? a.minX : a.x - a.start;
      double maxX = a.bounded ? a.maxX : a.x + a.start;
      double minZ = a.bounded ? a.minZ : a.z - a.start;
      double maxZ = a.bounded ? a.maxZ : a.z + a.start;
      shapes.add(
          new Shape(
              a.running,
              a.x,
              a.z,
              a.radius(System.currentTimeMillis()),
              minX,
              maxX,
              minZ,
              maxZ,
              List.copyOf(viewers)));
    }
    int version = generation;
    Bukkit.getScheduler()
        .runTaskAsynchronously(
            plugin,
            () -> {
              try {
                List<Point> points = plan(shapes, settings);
                Bukkit.getScheduler()
                    .runTask(
                        plugin,
                        () -> {
                          if (version != generation) return;
                          Particle.DustOptions dust =
                              new Particle.DustOptions(Color.fromRGB(70, 255, 90), settings.size);
                          for (Point point : points) {
                            Player player = Bukkit.getPlayer(point.player);
                            World world = Bukkit.getWorld(point.world);
                            if (player == null || world == null || player.getWorld() != world)
                              continue;
                            if (!world.isChunkLoaded(
                                ((int) Math.floor(point.x)) >> 4, ((int) Math.floor(point.z)) >> 4))
                              continue;
                            player.spawnParticle(
                                Particle.DUST,
                                point.x,
                                point.y,
                                point.z,
                                1,
                                0,
                                0,
                                0,
                                0,
                                dust,
                                true);
                          }
                        });
              } finally {
                calculating.set(false);
              }
            });
  }

  private static List<Point> plan(List<Shape> shapes, Settings s) {
    List<Point> points = new ArrayList<>();
    for (Shape shape : shapes)
      for (View v : shape.views) {
        if (shape.circle) {
          int count =
              Math.min(s.points, Math.max(24, (int) Math.ceil(2 * Math.PI * shape.radius / 3)));
          for (int i = 0; i < count; i++) {
            double angle = 2 * Math.PI * i / count;
            column(
                points,
                v,
                shape.x + Math.cos(angle) * shape.radius,
                shape.z + Math.sin(angle) * shape.radius,
                s);
          }
        } else {
          line(points, v, shape.minX, shape.maxX, shape.minZ, true, s);
          line(points, v, shape.minX, shape.maxX, shape.maxZ, true, s);
          line(points, v, shape.minZ, shape.maxZ, shape.minX, false, s);
          line(points, v, shape.minZ, shape.maxZ, shape.maxX, false, s);
        }
      }
    return points;
  }

  private static void line(
      List<Point> points,
      View v,
      double min,
      double max,
      double fixed,
      boolean horizontal,
      Settings s) {
    double center = horizontal ? v.x : v.z;
    if (Math.abs(fixed - (horizontal ? v.z : v.x)) > s.distance) return;
    double start = Math.max(min, center - s.distance), end = Math.min(max, center + s.distance);
    int count = Math.min(1440, Math.max(1, (int) Math.ceil((end - start) / s.spacing)));
    if (end < start) return;
    for (int i = 0; i <= count; i++) {
      double along = start + (end - start) * i / count;
      column(points, v, horizontal ? along : fixed, horizontal ? fixed : along, s);
    }
  }

  private static void column(List<Point> points, View v, double x, double z, Settings s) {
    if (points.size() >= 20000) return;
    double dx = x - v.x, dz = z - v.z;
    if (dx * dx + dz * dz > s.distance * s.distance) return;
    double base = Math.max(v.minY + .5, v.y - 3);
    for (int layer = 0; layer < s.layers; layer++) {
      double y = base + layer * s.vertical;
      if (y >= v.maxY || points.size() >= 20000) break;
      points.add(new Point(v.player, v.world, x, y, z));
    }
  }
}
