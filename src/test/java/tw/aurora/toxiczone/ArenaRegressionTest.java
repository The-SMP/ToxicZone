package tw.aurora.toxiczone;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;

class ArenaRegressionTest {
  private ToxicZonePlugin plugin;
  private ArenaManager manager;

  @BeforeEach
  void prepare() {
    plugin = mock(ToxicZonePlugin.class);
    plugin.config = new YamlConfiguration();
    plugin.files = mock(PluginFiles.class);
    plugin.messages = mock(Messages.class);
    plugin.menus = mock(ZoneMenus.class);
    manager = new ArenaManager(plugin);
    plugin.arenas = manager;
  }

  private Arena arena(String id, double x) {
    Arena a = new Arena(id);
    a.world = "world";
    a.x = x;
    a.z = 0;
    a.start = 100;
    a.minimum = 5;
    a.duration = 600;
    a.damage = 3;
    a.interval = 1;
    a.bounds(x - 100, -100, x + 100, 100);
    manager.arenas.put(id, a);
    return a;
  }

  @Test
  void restartAndReloadPreserveElapsedTimeAndEliminations() {
    Arena a = arena("default", 0);
    a.running = true;
    a.startedAt = 100000;
    a.eliminated.add(UUID.randomUUID());
    manager.persist();
    ArenaManager loaded = new ArenaManager(plugin);
    loaded.load();
    Arena restored = loaded.arenas.get("default");
    assertEquals(a.startedAt, restored.startedAt);
    assertEquals(a.eliminated, restored.eliminated);
    assertEquals(a.radius(400000), restored.radius(400000));
    assertEquals(52.5, restored.radius(400000), 0.0001);
  }

  @Test
  void finalCircleKeepsHurtingOnlyPlayersInsideItsArena() {
    Arena a = arena("default", 0);
    a.running = true;
    a.startedAt = System.currentTimeMillis() - 700000;
    World world = mock(World.class);
    Player outsideCircle = mock(Player.class),
        safe = mock(Player.class),
        otherArena = mock(Player.class);
    int n = 0;
    for (Player p : List.of(outsideCircle, safe, otherArena)) {
      when(p.getUniqueId()).thenReturn(UUID.randomUUID());
      when(p.getHealth()).thenReturn(20.0);
      when(p.getGameMode()).thenReturn(GameMode.SURVIVAL);
      when(p.getLocation()).thenReturn(new Location(world, n == 0 ? 50 : n == 1 ? 0 : 500, 64, 0));
      n++;
    }
    when(world.getName()).thenReturn("world");
    when(world.getPlayers()).thenReturn(List.of(outsideCircle, safe, otherArena));
    try (var bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
      manager.tick();
      assertTrue(a.running);
      assertEquals(5, a.radius(System.currentTimeMillis()));
      verify(outsideCircle).damage(3);
      verify(safe, never()).damage(anyDouble());
      verify(otherArena, never()).damage(anyDouble());
    }
  }

  @Test
  void administratorsAndExplicitCommandsHaveIndependentTargets() {
    Arena a = arena("a", 0), b = arena("b", 500);
    Player first = mock(Player.class), second = mock(Player.class);
    when(first.getUniqueId()).thenReturn(UUID.randomUUID());
    when(second.getUniqueId()).thenReturn(UUID.randomUUID());
    when(first.isOp()).thenReturn(true);
    when(second.isOp()).thenReturn(true);
    when(plugin.admin(any())).thenReturn(true);
    manager.select(first, "a");
    manager.select(second, "b");
    ZoneCommands commands = new ZoneCommands(plugin);
    plugin.commands = commands;
    commands.onCommand(first, null, "tz", new String[] {"b", "start"});
    assertTrue(b.running);
    assertFalse(a.running);
    assertSame(a, manager.selected(first));
    assertSame(b, manager.selected(second));
  }

  @Test
  void deletingDefaultPersistsATombstoneAndAllowsRecreation() {
    Arena a = arena("default", 0);
    manager.delete(a);
    manager.load();
    assertFalse(manager.arenas.containsKey("default"));
    verify(plugin.menus).closeArena("default");
    World world = mock(World.class);
    when(world.getName()).thenReturn("world");
    assertNotNull(manager.create("default", new Location(world, 0, 64, 0)));
    manager.load();
    assertTrue(manager.arenas.containsKey("default"));
  }

  @Test
  void overlappingActiveArenasCannotStart() {
    Arena a = arena("a", 0), b = arena("b", 50), c = arena("c", 500);
    assertEquals("started", manager.start(a));
    assertEquals("arena-overlap", manager.start(b));
    assertFalse(b.running);
    assertEquals("started", manager.start(c));
  }

  @Test
  void invalidNumbersDoNotPartiallyChangeSettings() {
    Arena a = arena("a", 0);
    ZoneCommands commands = new ZoneCommands(plugin);
    commands.execute(mock(Player.class), a, "damage", new String[] {"damage", "9", "NaN"});
    assertEquals(3, a.damage);
    assertEquals(1, a.interval);
    verify(plugin.messages).send(any(), eq("invalid-number"));
  }

  @Test
  void versionTagsAndResourceYamlAreValid() throws Exception {
    assertTrue(VersionChecker.newer("v1.3.0", "1.2.0"));
    assertFalse(VersionChecker.newer("v1.3.0-beta", "1.3.0"));
    for (String name : List.of("config.yml", "messages.yml", "plugin.yml")) {
      var yaml = new YamlConfiguration();
      try (var input = getClass().getResourceAsStream("/" + name)) {
        assertNotNull(input);
        yaml.load(new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8));
      }
    }
  }

  @Test
  void particleReloadCancelsOldTaskAndUsesNewInterval() {
    var scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
    var first = mock(org.bukkit.scheduler.BukkitTask.class);
    var second = mock(org.bukkit.scheduler.BukkitTask.class);
    when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong()))
        .thenReturn(first, second);
    try (var bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
      ParticleDisplay display = new ParticleDisplay(plugin);
      plugin.config.set("settings.particles.interval-ticks", 10);
      display.restart();
      plugin.config.set("settings.particles.interval-ticks", 3);
      display.restart();
      verify(first).cancel();
      verify(scheduler).runTaskTimer(eq(plugin), any(Runnable.class), eq(3L), eq(3L));
      display.stop();
    }
  }

  @Test
  void disabledUpdateNotificationsDoNotRequestVersionChecks() {
    plugin.config.set("settings.update-notifications", false);
    plugin.versions = mock(VersionChecker.class);
    Player player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    var event = mock(org.bukkit.event.player.PlayerJoinEvent.class);
    when(event.getPlayer()).thenReturn(player);
    var scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
    doCallRealMethod().when(plugin).join(event);
    try (var bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
      plugin.join(event);
      verifyNoInteractions(plugin.versions);
    }
  }

  @Test
  void fileReloadMigratesOldRepositoryAndPreservesCustomText(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
    when(plugin.getDataFolder()).thenReturn(temp.toFile());
    when(plugin.getResource(anyString()))
        .thenAnswer(call -> getClass().getResourceAsStream("/" + call.getArgument(0)));
    java.nio.file.Files.writeString(
        temp.resolve("config.yml"),
        "settings:\n  github-repository: The-SMP/ToxicZone\n  particles:\n    interval-ticks: 7\n");
    java.nio.file.Files.writeString(temp.resolve("messages.yml"), "started: 'custom {arena}'\n");
    try (PluginFiles files = new PluginFiles(plugin)) {
      var loaded = files.reload().join();
      assertEquals("catdevteam/ToxicZone", loaded.config().getString("settings.github-repository"));
      assertEquals(7, loaded.config().getInt("settings.particles.interval-ticks"));
      assertEquals("custom {arena}", loaded.messages().getString("started"));
      loaded.config().set("arena.started-at", 123456L);
      files.save(loaded.config());
      assertEquals(123456L, files.reload().join().config().getLong("arena.started-at"));
    }
  }

  @Test
  void reloadKeepsExternallyEditedIntervalAndRestartsParticles() throws Exception {
    var loadedConfig = new YamlConfiguration();
    loadedConfig.set("settings.particles.interval-ticks", 3);
    when(plugin.files.reload())
        .thenReturn(
            java.util.concurrent.CompletableFuture.completedFuture(
                new PluginFiles.Loaded(loadedConfig, new YamlConfiguration())));
    plugin.wand = mock(SelectionWand.class);
    var particles = mock(ParticleDisplay.class);
    var field = ToxicZonePlugin.class.getDeclaredField("particles");
    field.setAccessible(true);
    field.set(plugin, particles);
    when(plugin.isEnabled()).thenReturn(true);
    doCallRealMethod().when(plugin).reload(any());
    plugin.config.set("settings.particles.interval-ticks", 10);
    var scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
    when(scheduler.runTask(eq(plugin), any(Runnable.class)))
        .thenAnswer(
            call -> {
              call.getArgument(1, Runnable.class).run();
              return mock(org.bukkit.scheduler.BukkitTask.class);
            });
    try (var bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
      bukkit
          .when(Bukkit::getConsoleSender)
          .thenReturn(mock(org.bukkit.command.ConsoleCommandSender.class));
      plugin.reload(mock(org.bukkit.command.ConsoleCommandSender.class));
      assertSame(loadedConfig, plugin.config);
      assertEquals(3, plugin.config.getInt("settings.particles.interval-ticks"));
      assertFalse(plugin.reloading);
      verify(particles).restart();
    }
  }

  @Test
  void longPanelLoreWrapsWithoutLosingArenaName() throws Exception {
    YamlConfiguration values = new YamlConfiguration();
    values.set("test", List.of("&7" + "測試".repeat(30)));
    var lines = new Messages(values).lore("test", Map.of());
    assertTrue(lines.size() > 1);
    for (String line : lines) assertTrue(ChatColor.stripColor(line).length() <= 21);
  }
}
