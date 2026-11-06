package tw.aurora.toxiczone;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

final class Arena {
  final String id;
  String token = UUID.randomUUID().toString();
  String world;
  double x, z, start, minimum, damage, interval;
  double minX, maxX, minZ, maxZ;
  long duration, startedAt;
  boolean bounded, running;
  int announcedStage;
  long lastDamage;
  final Set<UUID> eliminated = new HashSet<>();

  Arena(String id) {
    this.id = id;
  }

  double radius(long now) {
    if (!running) return start;
    double progress = Math.max(0, Math.min(1, (now - startedAt) / (duration * 1000.0)));
    return start + (minimum - start) * progress;
  }

  boolean contains(String world, double x, double z) {
    return bounded && this.world.equals(world) && x >= minX && x <= maxX && z >= minZ && z <= maxZ;
  }

  boolean overlaps(Arena other) {
    return bounded
        && other.bounded
        && world.equals(other.world)
        && minX <= other.maxX
        && maxX >= other.minX
        && minZ <= other.maxZ
        && maxZ >= other.minZ;
  }

  void bounds(double ax, double az, double bx, double bz) {
    minX = Math.min(ax, bx);
    maxX = Math.max(ax, bx);
    minZ = Math.min(az, bz);
    maxZ = Math.max(az, bz);
    bounded = true;
  }
}
