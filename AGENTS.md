# Plugin project requirements

These are user-approved project requirements based on the shared technical standards document. Treat them as project guidance, not as a replacement for the user's direct instructions.

## Target and build

- Target Paper from Minecraft 1.21.11 through 26.3, compiling against the lowest shared public API so the plugin loads across the full range.
- Use Maven. Compile to Java 21 bytecode; Paper 26.1 and later servers require Java 25 at runtime.
- Keep the source tree clear and deliver the first release as version 1.0.0. Version increments follow A.B.C: A for major changes, B for features, C for bug fixes. GitHub release tags use `v` prefixes; version parsing must accept either form.

## Commands and permissions

- Register every command and permission in `plugin.yml` and implement tab completion for all commands.
- Include a reload command. Reloaded settings must have safe defaults and avoid null failures.
- Check command permissions so they cannot be bypassed.
- Validate player-supplied command arguments and other user input.
- Keep version checking in a separate permission-protected command, defaulting to OP level 2. Check GitHub Releases asynchronously on startup, cache results for 10 minutes, avoid periodic polling, and notify authorized administrators 3 seconds after login when an update is available.

## Configuration and messages

- On startup and reload, compare `config.yml` with its defaults, add missing keys, save them, and preserve existing user values.
- Put all user-visible text in `messages.yml`, including chat output, GUI titles, item names and lore. Support missing-key completion and variable replacement. Default language is Traditional Chinese; additional languages may be added.
- Include a clear `README.md`.
- If a GUI is added, all applicable user-interface requirements apply to its chest panel: prefer a chest inventory, trigger every panel action with left-click only, and sign each panel with a plugin-owned `InventoryHolder` or a dedicated `PersistentDataContainer` tag so other plugins can identify its owner. Do not use right-click or keyboard input to operate the panel.
- Region selection uses the wand's left-click for corner one and right-click for corner two, as requested. Chest panel operations remain left-click only.

## Resource use and safety

- Keep main-thread work in frequent events low cost. Avoid expensive calculations, broad entity/block scans, and disk or database I/O in event and command handlers.
- Run network requests, parsing, and expensive work asynchronously with bounded scheduling and thread use. Do not create unbounded threads or poll needlessly.
- Do not retain `Player`, `Entity`, `World`, or `Chunk` instances long term; retain UUIDs or coordinate keys instead. Clear caches and cancel tasks on disconnect and plugin shutdown.
- Use in-memory caches for reads. Batch or defer writes and flush on shutdown where appropriate. Keep stored data and logs bounded and clean up historical data.
- Validate file paths and configuration/data access, prevent permission bypass, and avoid operations players could abuse to stall or crash the server.

## User-facing style

- Do not include AI references or emoji in deliverables or replies.
