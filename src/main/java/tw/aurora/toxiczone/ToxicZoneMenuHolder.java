package tw.aurora.toxiczone;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.inventory.*;

public final class ToxicZoneMenuHolder implements InventoryHolder {
  enum View {
    SETTINGS,
    LIST,
    DELETE
  }

  final ToxicZonePlugin plugin;
  final View view;
  final Arena target;
  final List<Arena> entries;
  final int page;
  private final Inventory inventory;

  ToxicZoneMenuHolder(
      ToxicZonePlugin plugin,
      View view,
      Arena target,
      List<Arena> entries,
      int page,
      String title) {
    this.plugin = plugin;
    this.view = view;
    this.target = target;
    this.entries = List.copyOf(entries);
    this.page = page;
    inventory = Bukkit.createInventory(this, 45, title);
  }

  public ToxicZonePlugin getPlugin() {
    return plugin;
  }

  @Override
  public Inventory getInventory() {
    return inventory;
  }
}
