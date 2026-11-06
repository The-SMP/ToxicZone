package tw.aurora.toxiczone;

import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;

final class ZoneMenus implements Listener {
  private final ToxicZonePlugin plugin;

  ZoneMenus(ToxicZonePlugin plugin) {
    this.plugin = plugin;
  }

  private ItemStack item(String key, Material material, Map<String, ?> args) {
    ItemStack item = new ItemStack(material);
    var meta = item.getItemMeta();
    meta.setDisplayName(plugin.messages.text(key + ".name", args));
    meta.setLore(plugin.messages.lore(key + ".lore", args));
    item.setItemMeta(meta);
    return item;
  }

  private void put(Inventory inv, int slot, String key, Material material, Map<String, ?> args) {
    inv.setItem(slot, item(key, material, args));
  }

  void settings(Player player, Arena a) {
    var args = plugin.commands.values(a);
    args.put("arena-short", a.id.length() > 16 ? a.id.substring(0, 15) + "…" : a.id);
    var holder =
        new ToxicZoneMenuHolder(
            plugin,
            ToxicZoneMenuHolder.View.SETTINGS,
            a,
            List.of(),
            0,
            plugin.messages.text("gui-title", args));
    Inventory inv = holder.getInventory();
    for (int i = 0; i < inv.getSize(); i++)
      put(inv, i, "gui-filler", Material.GRAY_STAINED_GLASS_PANE, Map.of());
    put(inv, 0, "gui-arenas", Material.CHEST, args);
    put(inv, 4, "gui-status", Material.MAP, args);
    put(inv, 8, "gui-guide", Material.BOOK, Map.of());
    put(inv, 10, "gui-center", Material.COMPASS, Map.of());
    put(inv, 11, "gui-radius-fine-down", Material.REDSTONE, Map.of());
    put(inv, 12, "gui-radius-down", Material.RED_DYE, Map.of());
    put(inv, 13, "gui-radius", Material.PAPER, Map.of("value", Messages.number(a.start)));
    put(inv, 14, "gui-radius-up", Material.LIME_DYE, Map.of());
    put(inv, 15, "gui-radius-fine-up", Material.GLOWSTONE_DUST, Map.of());
    put(inv, 16, "gui-minimum-down", Material.RED_DYE, Map.of());
    put(inv, 17, "gui-minimum", Material.PAPER, Map.of("value", Messages.number(a.minimum)));
    put(inv, 18, "gui-minimum-up", Material.LIME_DYE, Map.of());
    put(inv, 20, "gui-time-fine-down", Material.REDSTONE, Map.of());
    put(inv, 21, "gui-time-down", Material.RED_DYE, Map.of());
    put(inv, 22, "gui-time", Material.CLOCK, Map.of("value", a.duration));
    put(inv, 23, "gui-time-up", Material.LIME_DYE, Map.of());
    put(inv, 24, "gui-time-fine-up", Material.GLOWSTONE_DUST, Map.of());
    put(inv, 25, "gui-damage-down", Material.RED_DYE, Map.of());
    put(inv, 26, "gui-damage", Material.PAPER, Map.of("value", Messages.number(a.damage)));
    put(inv, 27, "gui-damage-up", Material.LIME_DYE, Map.of());
    put(inv, 30, "gui-interval-down", Material.RED_DYE, Map.of());
    put(inv, 31, "gui-interval", Material.CLOCK, Map.of("value", Messages.number(a.interval)));
    put(inv, 32, "gui-interval-up", Material.LIME_DYE, Map.of());
    put(inv, 34, "gui-wand", Material.CARROT_ON_A_STICK, args);
    put(inv, 36, "gui-delete", Material.TNT, args);
    put(
        inv,
        40,
        a.running ? "gui-stop" : "gui-start",
        a.running ? Material.BARRIER : Material.LIME_CONCRETE,
        args);
    put(inv, 42, "gui-reload", Material.WRITABLE_BOOK, Map.of());
    player.openInventory(inv);
  }

  void list(Player player, int requested) {
    var all = new ArrayList<>(plugin.arenas.arenas.values());
    int last = Math.max(0, (all.size() - 1) / 36);
    int page = Math.max(0, Math.min(requested, last));
    var entries = all.subList(page * 36, Math.min(all.size(), (page + 1) * 36));
    var holder =
        new ToxicZoneMenuHolder(
            plugin,
            ToxicZoneMenuHolder.View.LIST,
            null,
            entries,
            page,
            plugin.messages.text("gui-arenas-title", Map.of("page", page + 1, "pages", last + 1)));
    Inventory inv = holder.getInventory();
    for (int i = 0; i < entries.size(); i++) {
      Arena a = entries.get(i);
      put(
          inv,
          i,
          "gui-arena-entry",
          a.running ? Material.LIME_CONCRETE : Material.MAP,
          plugin.commands.values(a));
    }
    if (page > 0) put(inv, 36, "gui-previous", Material.ARROW, Map.of());
    put(inv, 41, "gui-arena-create", Material.NETHER_STAR, Map.of());
    if (page < last) put(inv, 44, "gui-next", Material.ARROW, Map.of());
    player.openInventory(inv);
  }

  private void confirmDelete(Player player, Arena a) {
    var holder =
        new ToxicZoneMenuHolder(
            plugin,
            ToxicZoneMenuHolder.View.DELETE,
            a,
            List.of(),
            0,
            plugin.messages.text("gui-delete-title", Map.of()));
    put(holder.getInventory(), 20, "gui-delete-confirm", Material.TNT, Map.of("arena", a.id));
    put(holder.getInventory(), 24, "gui-back", Material.ARROW, Map.of());
    player.openInventory(holder.getInventory());
  }

  @EventHandler
  public void click(InventoryClickEvent event) {
    if (!(event.getView().getTopInventory().getHolder() instanceof ToxicZoneMenuHolder holder)
        || holder.plugin != plugin) return;
    event.setCancelled(true);
    if (event.getClick() != ClickType.LEFT
        || event.getClickedInventory() != holder.getInventory()
        || !(event.getWhoClicked() instanceof Player player)) return;
    int slot = event.getRawSlot();
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              if (!player.isOnline()
                  || player.getOpenInventory().getTopInventory().getHolder() != holder
                  || !plugin.admin(player)) return;
              if (plugin.reloading) {
                plugin.messages.send(player, "reload-busy");
                return;
              }
              Arena a = holder.target;
              if (a != null && plugin.arenas.arenas.get(a.id) != a) {
                player.closeInventory();
                return;
              }
              if (holder.view == ToxicZoneMenuHolder.View.LIST) {
                if (slot < holder.entries.size()) {
                  Arena entry = holder.entries.get(slot);
                  if (plugin.arenas.arenas.get(entry.id) != entry) {
                    list(player, holder.page);
                    return;
                  }
                  plugin.arenas.select(player, entry.id);
                  plugin.messages.send(player, "arena-selected", Map.of("arena", entry.id));
                  settings(player, entry);
                } else if (slot == 36 || slot == 44)
                  list(player, holder.page + (slot == 36 ? -1 : 1));
                else if (slot == 41) {
                  int n = 1;
                  while (plugin.arenas.arenas.containsKey("arena-" + n)) n++;
                  Arena created = plugin.arenas.create("arena-" + n, player.getLocation());
                  plugin.arenas.select(player, created.id);
                  plugin.messages.send(player, "arena-selected", Map.of("arena", created.id));
                  settings(player, created);
                }
                return;
              }
              if (holder.view == ToxicZoneMenuHolder.View.DELETE) {
                if (slot == 20) {
                  plugin.arenas.delete(a);
                  plugin.messages.send(player, "arena-deleted", Map.of("arena", a.id));
                  list(player, 0);
                } else if (slot == 24) settings(player, a);
                return;
              }
              settingsClick(player, a, slot);
            });
  }

  private void settingsClick(Player p, Arena a, int slot) {
    String sub;
    String[] args;
    switch (slot) {
      case 0 -> {
        list(p, 0);
        return;
      }
      case 8 -> {
        p.closeInventory();
        plugin.messages.send(p, "help");
        return;
      }
      case 36 -> {
        confirmDelete(p, a);
        return;
      }
      case 42 -> {
        plugin.reload(p);
        return;
      }
      case 10 -> {
        sub = "center";
        args = new String[] {sub};
      }
      case 34 -> {
        p.closeInventory();
        plugin.wand.give(p, a);
        return;
      }
      case 40 -> {
        sub = a.running ? "stop" : "start";
        args = new String[] {sub};
      }
      case 11, 12, 14, 15 -> {
        double step =
            switch (slot) {
              case 11 -> -1;
              case 12 -> -10;
              case 14 -> 10;
              default -> 1;
            };
        double value = Math.max(.5, a.start + step);
        sub = "radius";
        args =
            new String[] {sub, Double.toString(value), Double.toString(Math.min(value, a.minimum))};
      }
      case 16, 18 -> {
        sub = "radius";
        args =
            new String[] {
              sub,
              Double.toString(a.start),
              Double.toString(Math.max(.5, Math.min(a.start, a.minimum + (slot == 16 ? -1 : 1))))
            };
      }
      case 20, 21, 23, 24 -> {
        int step =
            switch (slot) {
              case 20 -> -10;
              case 21 -> -60;
              case 23 -> 60;
              default -> 10;
            };
        sub = "time";
        args = new String[] {sub, Long.toString(Math.max(1, a.duration + step))};
      }
      case 25, 27 -> {
        sub = "damage";
        args =
            new String[] {sub, Double.toString(Math.max(.5, a.damage + (slot == 25 ? -.5 : .5)))};
      }
      case 30, 32 -> {
        sub = "damage";
        args =
            new String[] {
              sub,
              Double.toString(a.damage),
              Double.toString(Math.max(.1, a.interval + (slot == 30 ? -.5 : .5)))
            };
      }
      default -> {
        return;
      }
    }
    plugin.commands.execute(p, a, sub, args);
    settings(p, a);
  }

  @EventHandler
  public void drag(InventoryDragEvent event) {
    if (event.getView().getTopInventory().getHolder() instanceof ToxicZoneMenuHolder h
        && h.plugin == plugin) event.setCancelled(true);
  }

  void closeArena(String id) {
    for (Player p : Bukkit.getOnlinePlayers()) {
      if (p.getOpenInventory().getTopInventory().getHolder() instanceof ToxicZoneMenuHolder h
          && h.plugin == plugin
          && h.target != null
          && h.target.id.equals(id)) p.closeInventory();
    }
  }

  void closeAll() {
    for (Player p : Bukkit.getOnlinePlayers())
      if (p.getOpenInventory().getTopInventory().getHolder() instanceof ToxicZoneMenuHolder h
          && h.plugin == plugin) p.closeInventory();
  }
}
