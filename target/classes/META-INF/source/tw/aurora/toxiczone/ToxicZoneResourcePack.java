package tw.aurora.toxiczone;

import clre20.httpHub.api.HttpHubService;
import clre20.httpHub.api.Route;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;

final class ToxicZoneResourcePack {
    private final ToxicZonePlugin plugin;
    private HttpHubService hub;
    private Route route;
    private byte[] sha1;

    ToxicZoneResourcePack(ToxicZonePlugin plugin) {
        this.plugin = plugin;
    }

    void start() {
        try {
            hub = HttpHubService.get();
            if (hub == null || !hub.running()) {
                plugin.getLogger().severe("找不到正在運作的 HttpHub；無法提供並強制套用伺服器資源包。");
                return;
            }

            byte[] content = Files.readAllBytes(plugin.getDataFolder().toPath().resolve("resource-pack.zip"));
            sha1 = MessageDigest.getInstance("SHA-1").digest(content);
            route = hub.serve(plugin, "resource-pack.zip", content, "application/zip");
            plugin.getLogger().info("資源包已掛載至 HttpHub 路徑 " + route.path() + "，SHA-1 " + HexFormat.of().formatHex(sha1));
            for (Player player : Bukkit.getOnlinePlayers()) {
                Bukkit.getScheduler().runTask(plugin, () -> send(player));
            }
        } catch (Exception e) {
            plugin.getLogger().severe("無法將資源包掛載至 HttpHub：" + e.getMessage());
        }
    }

    void send(Player player) {
        if (hub == null || route == null || !route.active() || !player.isOnline()) return;
        String url = hub.url(player, route);
        if (url == null || url.isBlank()) {
            plugin.getLogger().warning("無法取得玩家 " + player.getName() + " 的 HttpHub 公開網址。");
            return;
        }
        player.setResourcePack(url, sha1, true);
    }

    void stop() {
        if (route != null && route.active()) route.remove();
        route = null;
        hub = null;
        sha1 = null;
    }
}
