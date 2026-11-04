package tw.aurora.toxiczone;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class ToxicZoneMenuHolder implements InventoryHolder {
    private final ToxicZonePlugin plugin;
    private final Inventory inventory;

    public ToxicZoneMenuHolder(ToxicZonePlugin plugin, String title) {
        this.plugin = plugin;
        this.inventory = Bukkit.createInventory(this, 45, title);
    }

    public ToxicZonePlugin getPlugin() { return plugin; }

    @Override public Inventory getInventory() { return inventory; }
}
