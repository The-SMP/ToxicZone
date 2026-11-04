package tw.aurora.toxiczone;

import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class ToxicZoneMenuHolder implements InventoryHolder {
    private final ToxicZonePlugin plugin;
    private final Inventory inventory;
    private final int page;
    private final List<String> arenaEntries;

    public ToxicZoneMenuHolder(ToxicZonePlugin plugin, String title) {
        this(plugin, title, -1, List.of());
    }

    ToxicZoneMenuHolder(ToxicZonePlugin plugin, String title, int page, List<String> arenaEntries) {
        this.plugin = plugin;
        this.page = page;
        this.arenaEntries = List.copyOf(arenaEntries);
        this.inventory = Bukkit.createInventory(this, 45, title);
    }

    int page() { return page; }
    List<String> arenaEntries() { return arenaEntries; }

    public ToxicZonePlugin getPlugin() { return plugin; }

    @Override public Inventory getInventory() { return inventory; }
}
