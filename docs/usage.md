# Extending the Example Code

### Summoned mob dialogue and prologue sequences

Summon any spawnable entity type in the executing player's, entity's, or command block's world.
Use absolute coordinates or `~`/`~offset` relative to the sender. Provide SNBT as a compound, or
use `-` when no custom NBT is needed. Each dialogue entry uses
`COLOR%STYLE%weight%text`, with commas between entries. Add an optional `|sound=<sound|NONE>`
suffix to play a sound for that line; append `%<volume>%<pitch>` to set its sound level. Use
`DEFAULT` for WHITE and `NONE` for plain text:

```text
/pvesummon <mob> <x> <y> <z> [<snbt>|-] <color>%<style>%<weight>%<text>,...
/pvesummon minecraft:villager ~ ~ ~ {NoAI:1b,CustomName:'{"text":"Guide"}'} GOLD%BOLD%70%Welcome traveler.|sound=ENTITY_VILLAGER_YES%0.8%1.2,RED%ITALIC%30%The road is dangerous.|sound=ENTITY_VILLAGER_NO
/pvesummon minecraft:zombie ~2 ~ ~ {IsBaby:1b} DEFAULT%NONE%80%Brains!,WHITE%BOLD%20%Run!
```

One weighted line is picked on each right-click; weights don't need to add up to 100. For a fixed
sequence, use `sequence=PLAYER` to message only the player who right-clicks, or `sequence=PLAYINGPVE`
to message every online player with the `playingPVE` tag. The `|` character is reserved for line
settings and cannot be included in dialogue text:

```text
/pvesummon minecraft:villager ~ ~ ~ - sequence=PLAYER GOLD%BOLD%0%Welcome traveler.,WHITE%NONE%40%Follow the path ahead.
/pvesummon minecraft:villager ~ ~ ~ - sequence=PLAYER GOLD%BOLD%0%The gate clicks open.|sound=BLOCK_IRON_DOOR_OPEN%0.7%1.2,WHITE%NONE%30%Step inside.|sound=ENTITY_VILLAGER_YES
/pvesummon minecraft:villager ~ ~ ~ - sequence=PLAYINGPVE GOLD%BOLD%0%The gate is open.|append=WHITE%NONE%Move quickly.|check=Will you enter?|yes=tp %player% 0 64 0|no=-
/pvesummon minecraft:villager ~ ~ ~ - sequence=PLAYER DARK_GREEN%NONE%0%The road forks.|check=Choose a route.|option=Forest%GREEN%BOLD%tp %player% 24 72 24|option=River%AQUA%ITALIC%tp %player% 36 64 12|option=Turn back%RED%NONE%-
/pvesummon minecraft:villager ~ ~ ~ {NoAI:1b,Invulnerable:1b,CustomName:'{"text":"Gatekeeper"}'} sequence=PLAYINGPVE GOLD%BOLD%100%The gate shudders open.|append=YELLOW%ITALIC%Runes blaze across the stone.|check=Will your group enter?|yes=tp @a[tag=playingPVE] 120 64 -30|no=tp @a[tag=playingPVE] 96 64 8,GRAY%NONE%40%The gatekeeper watches the decision.,AQUA%NONE%60%The runes fade away.
/pvesummon minecraft:villager ~1 ~ ~ {NoAI:1b,CustomName:'{"text":"Cartographer"}'} sequence=PLAYER DARK_AQUA%BOLD%0%The map is incomplete.|append=GRAY%ITALIC%The ink is smudged.|check=Follow the marked trail?|yes=tp %player% 24 72 24|no=tp %player% 0 64 0,WHITE%NONE%20%The map folds shut.,GOLD%NONE%50%The cartographer waits for your next move.
```

Each sequential line uses `COLOR%STYLE%delayTicks%text`. Its delay is the number of ticks before
that line appears (including the first line after the right-click). Optional `|sound=<sound|NONE>`
plays a sound with default volume and pitch `1`; use `|sound=<sound>%<volume>%<pitch>` to customize
them. Sound names can be Bukkit names such as `ENTITY_VILLAGER_YES` or namespaced keys. Optional
`|append=COLOR%STYLE%text` adds separately styled text to the same message. Optional
`|check=prompt|yes=command|no=command` adds clickable Yes/No choices and pauses the sequence until
someone chooses. For custom choices,
replace `yes=` and `no=` with one or more `|option=<label>%<color>%<style>%<command>` entries.
The label, named color, and style (or `NONE`) control each button; use `-` for no action. A command
runs as the player who chose, and `%player%` is replaced with their name. For `PLAYINGPVE`, the
first player to choose decides for everyone, and the group is told who chose what. Commas separate
dialogue lines and `|` separates options, so those characters cannot be included in the text or
commands.

While a sequence is active, its NPC cannot be clicked again. A player already receiving a sequence
cannot start or receive dialogue from another dialogue NPC until the sequence finishes. Dialogue is
held in memory and removed when the entity dies or the plugin shuts down.

### Custom checkpoint choices

`/pvecheckpoint` shows Yes and No by default. Keep the legacy `yes=` and `no=` callbacks to set
their commands, or define one or more custom choices with repeated `option=` entries. Each option
sets its own button label, named color, style, and command. At least one custom option is required
when using this format; a command of `-` leaves that choice without an action.

```text
/pvecheckpoint 40 GOLD BOLD UI_BUTTON_CLICK 0.8 1.2 yes=tp %player% 0 64 0 no=- message=Continue to the checkpoint scope=PLAYER
/pvecheckpoint 40 GOLD BOLD UI_BUTTON_CLICK 0.8 1.2 option=Enter%GREEN%BOLD%tp @a[tag=playingPVE] 120 64 -30 option=Gather%YELLOW%ITALIC%- option=Leave%RED%STRIKETHROUGH%tp @a[tag=playingPVE] 0 64 0 message=Choose your path target=@a[tag=playingPVE] scope=EVERYONE
```

In `scope=EVERYONE`, the first recipient to click decides for the group; each command runs as
that player, and everyone is told which label they chose. With no `option=`, `yes=`, or `no=`
entries, the default Yes/No buttons have no callbacks.

For prologue dialogue, command blocks can call the existing `/pveprologuedialogue` command for each
line in a sequence. Chain command blocks or use delays to control the order; for example:

```text
/pveprologuedialogue 0 GOLD NONE 1 1 The journey begins. target=@a[tag=playingPVE]
/pveprologuedialogue 40 WHITE NONE 1 1 Follow the path ahead. target=@a[tag=playingPVE]
```

### Adding new top-level commands
To add an entirely new command (e.g. `/fly`), follow the `ExampleCommand` pattern:

1. Create a class extending `BaseCommand` (from [cw-commons](https://github.com/CrimsonWarpedcraft/cw-commons)); build the full `CommandAPICommand` tree in the constructor and pass it to `super(...)`
2. Call `.register()` on an instance of it in `ExamplePlugin.onEnable()`, alongside the existing `new ExampleCommand(config, creeperKillsManager, this).register()`
3. Add its permissions to `plugin.yml`. Do not add the command itself. CommandAPI registers it programmatically
4. Write unit tests for each executor class following the `PingTest`/`GreetTest` pattern

### Adding subcommands
To add a subcommand to `/example`, follow the `Ping`/`Greet` pattern:

1. Create a class implementing CommandAPI's `CommandExecutor`; inject any config values via the constructor
2. Add a `.withSubcommand(...)` call in `ExampleCommand`'s constructor
3. Write a unit test following `PingTest` or `GreetTest` — mock `CommandSender` and `CommandArguments`, call `run()`, verify `sendRichMessage()`
4. If the subcommand needs a config value, add it to `PluginConfig` and `config.yml`

### Adding new config fields
This project provides an example of loading config files using Jackson with Hibernate Validator,
via cw-commons' `Config` interface and `BukkitConfigManagerBuilder`
(`com.crimsonwarpedcraft.cwcommons.config.bukkit`).

To define your own config, add fields annotated with a Bean Validation constraint and a `@JsonProperty` YAML key to `PluginConfig` (`config/PluginConfig.java`):

```java
@NotBlank
@JsonProperty("my-message")
private String myMessage = "default";
```

Then add a getter. The config is validated upfront on every startup. If any constraint fails, the plugin logs the offending fields and disables itself cleanly.

Add a matching entry to `src/main/resources/config.yml` for every field you add, with a comment if desired:

```yaml
# Description of what the setting controls.
# Supports MiniMessage formatting: https://docs.advntr.dev/minimessage/format.html
my-message: "default"
```

Comments are preserved because `saveDefaultConfig()` writes this file once on first startup and never overwrites it. Schema migrations rewrite the file, but those are rare, and require custom code to handle.

### Adding persistent per-player data
cw-commons' `BukkitDataStoreBuilder`/`Repository`/`PlayerDataManager` stores any JSON-shaped data per player, not just counters. To add a new field, follow the `PlayerData`/`CreeperKillListener` pattern:

1. Add a field with a getter/setter to `PlayerData` (`data/PlayerData.java`)
2. Reuse the existing `Repository<UUID, PlayerData>`/`PlayerDataManager<PlayerData>` pair built once from the `BukkitDataStoreBuilder` store in `ExamplePlugin.onEnable()` — one repository backs every field on `PlayerData`, so adding a field never requires a new repository
3. Read and write it with `PlayerDataManager#get`/`#save`, e.g. from a `Listener` like `CreeperKillListener` or a command executor like `CreepersKilled`
4. Write unit tests mocking `PlayerDataManager`, following `CreeperKillListenerTest`/`CreepersKilledTest`
5. (Optional) Periodically flush data stores using `AutoFlushTask.builder(...).build().start()` to prevent session data loss from an unexpected shutdown.

Note: `PlayerDataManager` is just a `Player`-keyed convenience wrapper. For data that isn't tied to a specific player, call `DataStore#repository` directly with a different `KeySerializer` (e.g. `KeySerializers.forString()`) to get a standalone `Repository` for that data.
