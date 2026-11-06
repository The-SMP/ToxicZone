package tw.aurora.toxiczone;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.bukkit.configuration.file.YamlConfiguration;

final class PluginFiles implements AutoCloseable {
  record Loaded(YamlConfiguration config, YamlConfiguration messages) {}

  private final ToxicZonePlugin plugin;
  private final ExecutorService io =
      Executors.newSingleThreadExecutor(r -> new Thread(r, "ToxicZone-files"));
  private final AtomicReference<YamlConfiguration> pending = new AtomicReference<>();
  private final AtomicBoolean writing = new AtomicBoolean();

  PluginFiles(ToxicZonePlugin plugin) {
    this.plugin = plugin;
  }

  void migrateFolder() throws IOException {
    Path target = plugin.getDataFolder().toPath();
    Path old = target.resolveSibling("AuroraToxicZone");
    Files.createDirectories(target);
    for (String name : List.of("config.yml", "messages.yml", "resource-pack.zip")) {
      Path source = old.resolve(name), destination = target.resolve(name);
      if (!Files.exists(destination) && Files.isRegularFile(source))
        Files.copy(source, destination);
    }
  }

  Loaded load() throws Exception {
    flush();
    return new Loaded(loadFile("config.yml"), loadFile("messages.yml"));
  }

  private YamlConfiguration loadFile(String name) throws Exception {
    Path file = plugin.getDataFolder().toPath().resolve(name);
    YamlConfiguration live = new YamlConfiguration();
    if (Files.exists(file)) live.load(file.toFile());
    YamlConfiguration defaults =
        YamlConfiguration.loadConfiguration(
            new InputStreamReader(
                Objects.requireNonNull(plugin.getResource(name)), StandardCharsets.UTF_8));
    for (String key : defaults.getKeys(true)) {
      if (!defaults.isConfigurationSection(key) && !live.contains(key))
        live.set(key, defaults.get(key));
    }
    if (name.equals("config.yml")
        && Set.of("The-SMP/ToxicZone", "clre20/ToxicZone")
            .contains(live.getString("settings.github-repository", ""))) {
      live.set("settings.github-repository", "catdevteam/ToxicZone");
    }
    if (name.equals("messages.yml")) {
      // Update bundled text while preserving administrator overrides.
      Map<String, Object> previous = new HashMap<>(defaults.getValues(true));
      try (var input = plugin.getResource("messages-previous.yml")) {
        if (input != null) {
          var old =
              YamlConfiguration.loadConfiguration(
                  new InputStreamReader(input, StandardCharsets.UTF_8));
          previous = old.getValues(true);
        }
      }
      for (String key : defaults.getKeys(true)) {
        if (!defaults.isConfigurationSection(key)
            && Objects.equals(live.get(key), previous.get(key))) {
          live.set(key, defaults.get(key));
        }
      }
    }
    write(file, live.saveToString());
    return live;
  }

  static YamlConfiguration snapshot(YamlConfiguration source) {
    YamlConfiguration copy = new YamlConfiguration();
    for (String key : source.getKeys(true)) {
      if (source.isConfigurationSection(key)) continue;
      Object value = source.get(key);
      copy.set(key, value instanceof List<?> list ? new ArrayList<>(list) : value);
    }
    return copy;
  }

  void save(YamlConfiguration config) {
    pending.set(snapshot(config));
    if (writing.compareAndSet(false, true)) io.execute(this::drain);
  }

  private void drain() {
    try {
      flush();
    } catch (Exception e) {
      plugin.getLogger().severe("無法儲存設定 " + e.getMessage());
    } finally {
      writing.set(false);
      if (pending.get() != null && writing.compareAndSet(false, true)) io.execute(this::drain);
    }
  }

  private void flush() throws IOException {
    YamlConfiguration config;
    while ((config = pending.getAndSet(null)) != null) {
      write(plugin.getDataFolder().toPath().resolve("config.yml"), config.saveToString());
    }
  }

  private static void write(Path file, String content) throws IOException {
    Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
    Files.writeString(temporary, content, StandardCharsets.UTF_8);
    try {
      Files.move(
          temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  CompletableFuture<Loaded> reload() {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return load();
          } catch (Exception e) {
            throw new CompletionException(e);
          }
        },
        io);
  }

  @Override
  public void close() {
    io.execute(this::drain);
    io.shutdown();
    try {
      if (!io.awaitTermination(15, TimeUnit.SECONDS)) plugin.getLogger().severe("設定寫入尚未結束");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
