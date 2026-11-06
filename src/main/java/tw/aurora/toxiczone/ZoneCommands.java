package tw.aurora.toxiczone;

import java.util.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

final class ZoneCommands implements CommandExecutor, TabCompleter {
  private static final List<String> COMMANDS =
      List.of(
          "help", "arena", "status", "start", "stop", "center", "wand", "radius", "time", "damage",
          "reload", "version");
  private final ToxicZonePlugin plugin;

  ZoneCommands(ToxicZonePlugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] input) {
    if (!plugin.admin(sender)) return true;
    String[] args = input;
    Arena arena = sender instanceof Player player ? plugin.arenas.selected(player) : null;
    boolean explicit =
        args.length > 1
            && COMMANDS.contains(args[1].toLowerCase(Locale.ROOT))
            && plugin.arenas.arenas.containsKey(args[0]);
    if (explicit) {
      arena = plugin.arenas.arenas.get(args[0]);
      args = Arrays.copyOfRange(args, 1, args.length);
    }
    if (args.length == 0) {
      if (sender instanceof Player player) {
        if (arena == null) plugin.menus.list(player, 0);
        else plugin.menus.settings(player, arena);
      } else plugin.messages.send(sender, "help");
      return true;
    }
    String sub = args[0].toLowerCase(Locale.ROOT);
    if (sub.equals("help")) {
      plugin.messages.send(sender, "help");
      return true;
    }
    if (sub.equals("arena")) {
      manage(sender, args);
      return true;
    }
    if (sub.equals("reload")) {
      plugin.reload(sender);
      return true;
    }
    if (sub.equals("version")) {
      if (sender.hasPermission("auroratoxiczone.version")) plugin.versions.tell(sender);
      else plugin.messages.send(sender, "no-permission");
      return true;
    }
    if (arena == null) {
      plugin.messages.send(sender, "arena-required");
      return true;
    }
    execute(sender, arena, sub, args);
    return true;
  }

  void execute(CommandSender sender, Arena a, String sub, String[] args) {
    if (plugin.reloading) {
      plugin.messages.send(sender, "reload-busy");
      return;
    }
    if (plugin.arenas.arenas.get(a.id) != a) {
      plugin.messages.send(sender, "arena-required");
      return;
    }
    if (a.running && Set.of("center", "radius", "time").contains(sub)) {
      plugin.messages.send(sender, "arena-running", Map.of("arena", a.id));
      return;
    }
    try {
      switch (sub) {
        case "status" -> {
          if (args.length != 1) throw new Usage();
          plugin.messages.send(sender, "status", values(a));
        }
        case "start" -> {
          if (args.length != 1) throw new Usage();
          plugin.messages.send(sender, plugin.arenas.start(a), values(a));
        }
        case "stop" -> {
          if (args.length != 1) throw new Usage();
          plugin.arenas.stop(a);
          plugin.messages.send(sender, "stopped", values(a));
        }
        case "wand" -> {
          if (args.length != 1) throw new Usage();
          if (sender instanceof Player player) plugin.wand.give(player, a);
          else plugin.messages.send(sender, "only-player");
        }
        case "center" -> {
          String world;
          double x, z;
          if (args.length == 1 && sender instanceof Player player) {
            Location l = player.getLocation();
            world = l.getWorld().getName();
            x = l.getX();
            z = l.getZ();
          } else if (args.length == 4 && Bukkit.getWorld(args[1]) != null) {
            world = args[1];
            x = coordinate(args[2]);
            z = coordinate(args[3]);
          } else throw new Usage();
          a.world = world;
          a.x = x;
          a.z = z;
          a.bounded = false;
          plugin.arenas.persist();
          plugin.messages.send(sender, "center-set", values(a));
        }
        case "radius" -> {
          if (args.length < 2 || args.length > 3) throw new Usage();
          double start = positive(args[1]), min = args.length == 3 ? positive(args[2]) : a.minimum;
          if (min > start || start > 30000000) throw new NumberFormatException();
          a.start = start;
          a.minimum = min;
          plugin.arenas.persist();
          plugin.messages.send(sender, "radius-set", values(a));
        }
        case "time" -> {
          if (args.length != 2) throw new Usage();
          long seconds = Long.parseLong(args[1]);
          if (seconds < 1 || seconds > 31536000) throw new NumberFormatException();
          a.duration = seconds;
          plugin.arenas.persist();
          plugin.messages.send(sender, "time-set", values(a));
        }
        case "damage" -> {
          if (args.length < 2 || args.length > 3) throw new Usage();
          double damage = positive(args[1]),
              interval = args.length == 3 ? positive(args[2]) : a.interval;
          if (damage > 2048 || interval < .1 || interval > 86400) throw new NumberFormatException();
          a.damage = damage;
          a.interval = interval;
          plugin.arenas.persist();
          plugin.messages.send(sender, "damage-set", values(a));
        }
        default -> plugin.messages.send(sender, "help");
      }
    } catch (NumberFormatException e) {
      plugin.messages.send(sender, "invalid-number");
    } catch (Usage e) {
      plugin.messages.send(sender, "help");
    }
  }

  private static final class Usage extends RuntimeException {}

  private static double positive(String text) {
    double value = Double.parseDouble(text);
    if (!Double.isFinite(value) || value <= 0) throw new NumberFormatException();
    return value;
  }

  private static double coordinate(String text) {
    double value = Double.parseDouble(text);
    if (!Double.isFinite(value) || Math.abs(value) > 30000000) throw new NumberFormatException();
    return value;
  }

  Map<String, Object> values(Arena a) {
    Map<String, Object> args = new HashMap<>();
    args.put("arena", a.id);
    args.put("arena-short", a.id.length() > 16 ? a.id.substring(0, 15) + "…" : a.id);
    args.put("world", a.world);
    args.put("x", Messages.number(a.x));
    args.put("z", Messages.number(a.z));
    args.put("radius", Messages.number(a.radius(System.currentTimeMillis())));
    args.put("start", Messages.number(a.start));
    args.put("minimum", Messages.number(a.minimum));
    args.put("seconds", a.duration);
    args.put("damage", Messages.number(a.damage));
    args.put("hearts", Messages.number(a.damage / 2));
    args.put("interval", Messages.number(a.interval));
    args.put(
        "remaining",
        a.running
            ? Math.max(0, a.duration - (System.currentTimeMillis() - a.startedAt) / 1000)
            : 0);
    String state =
        !a.running ? "state-stopped" : a.announcedStage >= 4 ? "state-final" : "state-running";
    args.put("state", plugin.messages.text(state, Map.of()));
    return args;
  }

  private void manage(CommandSender sender, String[] args) {
    if (plugin.reloading) {
      plugin.messages.send(sender, "reload-busy");
      return;
    }
    if (args.length == 2 && args[1].equalsIgnoreCase("list")) {
      plugin.messages.send(
          sender, "arena-list", Map.of("arenas", String.join(", ", plugin.arenas.arenas.keySet())));
      return;
    }
    if (args.length < 3 || !ArenaManager.validName(args[2])) {
      plugin.messages.send(sender, "arena-usage");
      return;
    }
    Arena a = plugin.arenas.arenas.get(args[2]);
    switch (args[1].toLowerCase(Locale.ROOT)) {
      case "create" -> {
        if (args.length != 3 || !(sender instanceof Player player) || a != null) {
          plugin.messages.send(sender, "arena-usage");
          return;
        }
        a = plugin.arenas.create(args[2], player.getLocation());
        plugin.arenas.select(player, a.id);
        plugin.messages.send(sender, "arena-selected", Map.of("arena", a.id));
      }
      case "use" -> {
        if (args.length != 3 || a == null || !(sender instanceof Player player)) {
          plugin.messages.send(sender, "arena-required");
          return;
        }
        plugin.arenas.select(player, a.id);
        player.closeInventory();
        plugin.messages.send(sender, "arena-selected", Map.of("arena", a.id));
      }
      case "delete" -> {
        if (a == null) {
          plugin.messages.send(sender, "arena-required");
          return;
        }
        if (args.length != 4 || !args[3].equalsIgnoreCase("confirm")) {
          plugin.messages.send(sender, "arena-delete-confirm", Map.of("arena", a.id));
          return;
        }
        plugin.arenas.delete(a);
        plugin.messages.send(sender, "arena-deleted", Map.of("arena", a.id));
      }
      default -> plugin.messages.send(sender, "arena-usage");
    }
  }

  @Override
  public List<String> onTabComplete(
      CommandSender sender, Command command, String label, String[] input) {
    if (!sender.isOp() && !sender.hasPermission("auroratoxiczone.admin")) return List.of();
    String[] args = input;
    if (args.length > 1 && plugin.arenas.arenas.containsKey(args[0]))
      args = Arrays.copyOfRange(args, 1, args.length);
    List<String> choices = new ArrayList<>();
    if (args.length == 1) {
      choices.addAll(COMMANDS);
      if (args == input) choices.addAll(plugin.arenas.arenas.keySet());
    } else if (args[0].equalsIgnoreCase("arena")) {
      if (args.length == 2) choices.addAll(List.of("create", "use", "list", "delete"));
      if (args.length == 3 && Set.of("use", "delete").contains(args[1]))
        choices.addAll(plugin.arenas.arenas.keySet());
      if (args.length == 4 && args[1].equalsIgnoreCase("delete")) choices.add("confirm");
    } else if (args[0].equalsIgnoreCase("center")) {
      if (args.length == 2) Bukkit.getWorlds().forEach(world -> choices.add(world.getName()));
      if (args.length == 3 || args.length == 4) {
        if (sender instanceof Player player)
          choices.add(
              Messages.number(
                  args.length == 3 ? player.getLocation().getX() : player.getLocation().getZ()));
        else choices.add("0");
      }
    } else if (args[0].equalsIgnoreCase("radius") && args.length <= 3)
      choices.addAll(List.of("5", "10", "50", "100", "200"));
    else if (args[0].equalsIgnoreCase("time") && args.length == 2)
      choices.addAll(List.of("60", "300", "600", "1800"));
    else if (args[0].equalsIgnoreCase("damage") && args.length <= 3)
      choices.addAll(args.length == 2 ? List.of("1", "2", "3", "4") : List.of("0.5", "1", "2"));
    String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
    return choices.stream()
        .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
        .toList();
  }
}
