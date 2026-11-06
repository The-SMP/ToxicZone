package tw.aurora.toxiczone;

import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;

final class SelectionWand implements Listener {
  private record Point(String arena, String token, String world, int x, int z) {}

  private final ToxicZonePlugin plugin;
  private final NamespacedKey marker = new NamespacedKey("auroratoxiczone", "region_wand");
  private final NamespacedKey number = new NamespacedKey("auroratoxiczone", "wand_id");
  private final NamespacedKey target = new NamespacedKey("toxiczone", "arena");
  private final NamespacedKey token = new NamespacedKey("toxiczone", "arena_token");
  private final Map<UUID, Point> points = new HashMap<>();

  SelectionWand(ToxicZonePlugin plugin) {
    this.plugin = plugin;
  }

  void give(Player player, Arena arena) {
    var item = new ItemStack(Material.CARROT_ON_A_STICK);
    var meta = item.getItemMeta();
    meta.setDisplayName(plugin.messages.text("wand-name", Map.of()));
    meta.setLore(
        List.of(
            plugin.messages.text("wand-lore", Map.of()),
            plugin.messages.text("wand-arena", Map.of("arena", arena.id))));
    meta.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
    meta.getPersistentDataContainer().set(number, PersistentDataType.INTEGER, 1001);
    meta.getPersistentDataContainer().set(target, PersistentDataType.STRING, arena.id);
    meta.getPersistentDataContainer().set(token, PersistentDataType.STRING, arena.token);
    var model = meta.getCustomModelDataComponent();
    model.setFloats(List.of(1001f));
    model.setStrings(List.of("1001"));
    meta.setCustomModelDataComponent(model);
    item.setItemMeta(meta);
    player
        .getInventory()
        .addItem(item)
        .values()
        .forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
    plugin.messages.send(player, "wand-given", Map.of("arena", arena.id));
  }

  @EventHandler
  public void use(PlayerInteractEvent event) {
    if (event.getHand() != EquipmentSlot.HAND
        || event.getItem() == null
        || event.getItem().getType() != Material.CARROT_ON_A_STICK) return;
    var data = event.getItem().getItemMeta().getPersistentDataContainer();
    if (data.getOrDefault(number, PersistentDataType.INTEGER, 0) != 1001
        || !data.has(marker, PersistentDataType.BYTE)) return;
    event.setCancelled(true);
    Player player = event.getPlayer();
    if (!plugin.admin(player)) return;
    if (plugin.reloading) {
      plugin.messages.send(player, "reload-busy");
      return;
    }
    String id = data.get(target, PersistentDataType.STRING);
    Arena arena = id == null ? null : plugin.arenas.arenas.get(id);
    if (arena == null || !arena.token.equals(data.get(token, PersistentDataType.STRING))) {
      plugin.messages.send(player, "wand-unbound");
      return;
    }
    if (arena.running) {
      plugin.messages.send(player, "arena-running", Map.of("arena", arena.id));
      return;
    }
    if (event.getClickedBlock() == null) return;
    var block = event.getClickedBlock();
    if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
      points.put(
          player.getUniqueId(),
          new Point(arena.id, arena.token, block.getWorld().getName(), block.getX(), block.getZ()));
      plugin.messages.send(
          player,
          "selection-first",
          Map.of(
              "arena",
              arena.id,
              "world",
              block.getWorld().getName(),
              "x",
              block.getX(),
              "y",
              block.getY(),
              "z",
              block.getZ()));
    } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
      Point first = points.get(player.getUniqueId());
      if (first == null || (!first.arena.equals(arena.id) || !first.token.equals(arena.token))) {
        plugin.messages.send(player, "selection-first-needed");
        return;
      }
      if (!first.world.equals(block.getWorld().getName())) {
        plugin.messages.send(player, "selection-world-error");
        return;
      }
      arena.world = first.world;
      arena.x = ((double) first.x + block.getX() + 1) / 2;
      arena.z = ((double) first.z + block.getZ() + 1) / 2;
      arena.start =
          Math.min(
                  Math.abs((double) first.x - block.getX()) + 1,
                  Math.abs((double) first.z - block.getZ()) + 1)
              / 2;
      arena.minimum = Math.min(arena.minimum, arena.start);
      arena.bounds(
          Math.min(first.x, block.getX()),
          Math.min(first.z, block.getZ()),
          Math.max(first.x, block.getX()) + 1.0,
          Math.max(first.z, block.getZ()) + 1.0);
      plugin.arenas.persist();
      points.remove(player.getUniqueId());
      plugin.messages.send(player, "selection-complete", plugin.commands.values(arena));
    }
  }

  @EventHandler
  public void quit(PlayerQuitEvent event) {
    UUID id = event.getPlayer().getUniqueId();
    points.remove(id);
    plugin.arenas.forget(id);
  }

  void clear() {
    points.clear();
  }
}
