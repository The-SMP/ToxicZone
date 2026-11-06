package tw.aurora.toxiczone;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

final class VersionChecker implements AutoCloseable {
  private final ToxicZonePlugin plugin;
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(r -> new Thread(r, "ToxicZone-version"));
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private CompletableFuture<String> pending;
  private String cached;
  private long checked;
  private String repository = "";

  VersionChecker(ToxicZonePlugin plugin) {
    this.plugin = plugin;
  }

  // All callbacks return to the server thread before accessing Bukkit state.
  void check(java.util.function.Consumer<String> callback) {
    String repo =
        plugin.config.getString("settings.github-repository", "catdevteam/ToxicZone").trim();
    if (!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
      callback.accept(null);
      return;
    }
    if (!repo.equals(repository)) {
      repository = repo;
      checked = 0;
      pending = null;
    }
    if (System.currentTimeMillis() - checked < 600000) {
      callback.accept(cached);
      return;
    }
    if (pending == null) {
      pending = CompletableFuture.supplyAsync(() -> fetch(repo), worker);
      pending.thenAccept(
          value -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler()
                .runTask(
                    plugin,
                    () -> {
                      if (repo.equals(repository)) {
                        cached = value;
                        checked = System.currentTimeMillis();
                        pending = null;
                      }
                    });
          });
    }
    pending.thenAccept(
        value -> {
          if (plugin.isEnabled())
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(value));
        });
  }

  private String fetch(String repo) {
    try {
      var request =
          HttpRequest.newBuilder(
                  URI.create("https://api.github.com/repos/" + repo + "/releases/latest"))
              .timeout(Duration.ofSeconds(8))
              .header("User-Agent", "ToxicZone")
              .header("Accept", "application/vnd.github+json")
              .build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) return null;
      var matcher = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").matcher(response.body());
      return matcher.find() ? matcher.group(1) : null;
    } catch (Exception e) {
      return null;
    }
  }

  static boolean newer(String first, String second) {
    int[] a = parts(first), b = parts(second);
    if (a == null || b == null) return false;
    for (int i = 0; i < 3; i++) if (a[i] != b[i]) return a[i] > b[i];
    return !first.contains("-") && second.contains("-");
  }

  private static int[] parts(String value) {
    if (value == null) return null;
    var matcher =
        Pattern.compile("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-[A-Za-z0-9.-]+)?$").matcher(value);
    if (!matcher.matches()) return null;
    try {
      return new int[] {
        Integer.parseInt(matcher.group(1)),
        Integer.parseInt(matcher.group(2)),
        Integer.parseInt(matcher.group(3))
      };
    } catch (NumberFormatException e) {
      return null;
    }
  }

  void tell(CommandSender sender) {
    check(
        latest -> {
          String current = plugin.getDescription().getVersion();
          String key =
              latest == null
                  ? "version-unavailable"
                  : newer(latest, current)
                      ? "update-available"
                      : newer(current, latest) ? "dev-build" : "up-to-date";
          plugin.messages.send(
              sender, key, Map.of("latest", latest == null ? "" : latest, "current", current));
        });
  }

  void startup() {
    check(
        latest -> {
          String current = plugin.getDescription().getVersion();
          String key =
              latest == null
                  ? "version-unavailable"
                  : newer(latest, current)
                      ? "update-available"
                      : newer(current, latest) ? "dev-build" : "up-to-date";
          plugin.messages.send(
              Bukkit.getConsoleSender(),
              key,
              Map.of("latest", latest == null ? "" : latest, "current", current));
        });
  }

  @Override
  public void close() {
    worker.shutdownNow();
  }
}
