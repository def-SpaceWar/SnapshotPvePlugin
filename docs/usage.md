# Extending the Example Code

### PVE cameras

Install PacketEvents 2.14.0 or newer alongside this plugin; it provides the version-aware camera
packets and must be enabled before SnapshotPvePlugin.

Camera definitions are saved in `plugins/SnapshotPvePlugin/snapshotpvecamera.yml`.
Create a polynomial camera with `/pvecam create <name> polynomial`, or an interpolation camera
with `/pvecam create <name> interpolation`. Omitting the type keeps the original behavior and
creates a polynomial camera. Camera type is saved with each definition; old camera files without
a type continue to load as polynomial cameras.

```text
/pvecam create flyby polynomial
/pvecam list
/pvecam set flyby 100 100+0.2*t 65 -30 0 90
/pvecam set flyby 40 120-0.1*t 65 -30 0 90
/pvecam play flyby @a[tag=playingPVE,distance=..32,dimension=pveLobby0,inPVELobby=true]
/pvecam stop
/pvecam reset flyby
/pvecam remove flyby
```

Polynomial `set` syntax is `/pvecam set <name> <ticks> <x> <y> <z> <pitch> <yaw>`. Each set
appends a timed segment containing five polynomial equations. Use local elapsed ticks (`t`) and
powers up to 12; equations are compact (no spaces), for example `100+0.2*t-0.001*t^2`.
Supported operators are `+`, `-`, `*`, division by a constant, parentheses, and integer powers.
Arbitrary functions such as `sin(t)` are not supported. Coordinates are absolute and are applied
in each target's current world. Pitch and yaw are in degrees. To avoid a snap between segments,
the next segment must start where the previous segment ended.

Interpolation cameras use numbered points and interpolate between consecutive points:

```text
/pvecam create dolly interpolation
/pvecam setpoint dolly 1 40 6 72 35 0 0 linear 0
/pvecam setpoint dolly 2 60 12 76 35 0 90 sinusoidal 2
/pvecam setpoint dolly 3 40 6 72 35 0 180 exponential 1
/pvecam setpoint dolly 4 0 linear 0
/pvecam setpoint edit dolly 2 yaw 135
/pvecam pointlist dolly
/pvecam play dolly @a
```

`setpoint` syntax is `/pvecam setpoint <name> <point#> <ticks-to-next> [<x> <y> <z> <pitch> <yaw>] <linear|sinusoidal|exponential> <easing 0-4>`.
Point numbers start at 1 and must be consecutive; setting an existing number replaces that point.
Each point's duration, interpolation type, and easing factor control its segment to the next
point. The final point has no outgoing segment, so its ticks-to-next value is ignored. A playable
path needs at least two points. Coordinates are absolute and applied in each target's current
world. If all five position and rotation values are omitted, the point captures the executing
player's current position and rotation, or the command block's position. This form requires a
player or command-block sender; console must provide explicit coordinates. The easing argument
is required in both forms; use `0` for the selected curve's default easing.

Use `/pvecam setpoint edit <name> <point#> <ticks|x|y|z|pitch|yaw|type|easing> <value>`
to change one parameter of an existing point without replacing its other settings. `ticks` is
the duration to the next point, `type` accepts the interpolation types below, and `easing`
accepts 0–4. The same position, rotation, and duration restrictions as `setpoint` apply.

`linear` uses a linear curve, `sinusoidal` uses a cosine ease-in/out curve, and `exponential`
uses an exponential ease-in/out curve. Easing factor 0 uses that curve unchanged. Factors 1–4
progressively strengthen the ease by remapping progress `v` to
`v^(1+factor) / (v^(1+factor) + (1-v)^(1+factor))`. The alternate spelling `sinosudial` is also
accepted.

`pointlist <name>` displays numbered points and their outgoing interpolation settings.
`pointreset <name> <point#>` removes a point and renumbers later points; `pointdeleteall <name>`
clears all points. `interpolateAlter <name> <point#> <type>` changes the outgoing curve for that
point. `changeCamType <name>` toggles between polynomial and interpolation and clears the path.
`reset <name>` clears the current path but keeps its type; `remove <name>` deletes the camera.
Resetting, deleting points, changing type, or removing a camera stops its active sessions and
restores affected players. `list` displays cameras, types, and path sizes.

Polynomial equations and interpolation points are stored on the named camera, so `play` only
needs its name and a player selector.

Only player selectors `@a`, `@p`, and `@r` are accepted. Optional filters are scoreboard
`tag=`, `distance=`, `dimension=`, `gamemode=`, and `inPVELobby=`. Distance is measured from the
player or command-block execution position; `@p` also requires that position. `dimension=` accepts
a world name or a vanilla dimension name. `inPVELobby=true` selects players in the same managed
PVE lobby instance as the player or command block running the command; run it from that lobby.
`inPVELobby=false` selects players outside all managed PVE lobbies.

Playback switches selected players to spectator mode and uses a client-side camera marker. The
player is moved only when the camera is more than one chunk away, to keep camera-area chunks
streaming; normal player movement and external teleports are blocked during playback. The
player receives the scoreboard tag `pveCam` while the camera is active; the plugin removes that
tag when playback ends if it added it. Camera chunk-staging and player-restoration teleports are
still allowed. A short initial chunk warm-up is used. On completion or `/pvecam stop`, the camera
is reset and the player's saved location, game mode, flight state, fall distance, and velocity
are restored. With no selector, a player stops their own camera; console or a command block
stops all active cameras. `stop` also accepts the same selector syntax. The command requires
`snapshotpve.camera` (operators by default).

### Delayed commands and Minecraft functions

```text
/pvewait <delayTicks> <function|#function_tag>
/pvewait <delayTicks> run <command...>
/pvewait 40 my_datapack:open_gate
/pvewait 100 #my_datapack:wave_finished
/pvewait 40 run say The gate is opening!
/pvewait 40 run /setblock ~ ~1 ~ minecraft:redstone_block
```

Runs a Minecraft datapack function, function tag, or command once after the specified number of
ticks. Use `run` before a regular command; a leading slash is optional. It uses the original
command sender rather than elevating to console, so the sender must also have permission to run
the requested command. The delay must be a whole number from 0 to 2,147,483,647; zero runs on the
next scheduler tick. Pending work is cancelled if the plugin is disabled or the server stops.
Requires `snapshotpve.wait` (operators by default).

### Execute based on an entity tag

```text
/pveexecute if tag=playingPVE run execute if block -247 57 -72 minecraft:crimson_button run fill -247 57 -72 -247 57 -72 air replace minecraft:crimson_button
/pveexecute unless tag=playingPVE run setblock -247 57 -72 minecraft:crimson_button[facing=east]
```

`if tag=<scoreboard-tag>` checks entities in all loaded worlds, including outside PVE lobbies.
`if` dispatches the command after `run` once if at least one entity matches; `unless` dispatches
it once if none match. It runs as the original sender, not as a matching entity. In a command
block it uses that block's world and position; the sender must have permission to run the nested
command. The tag check repeats on every invocation. In a repeating command block, the `unless`
example will attempt `setblock` on every tick while no entity matches; it is not a one-shot trigger.
Requires `snapshotpve.execute`.

### Rectangular particle spirals

```text
/pveparticlerectangularspiral <particle> <x> <y> <z> <width> <length> <horizontalStep> <yStep> <height> <intervalTicks> [sound|NONE] [soundTargetSelector] [color=RRGGBB] [angle=degrees]
/pveparticlerectangularspiral minecraft:flame ~ ~ ~ 4 6 0.25 0.1 15 1
/pveparticlerectangularspiral flame 100 64 -30 4 6 0.25 0.1 15 2 BLOCK_NOTE_BLOCK_PLING @a[tag=playingPVE]
/pveparticlerectangularspiral dust ~ ~ ~ 4 6 0.25 0.1 15 2 color=FF6600
/pveparticlerectangularspiral dust ~ ~ ~ 4 6 0.25 0.1 15 2 color=FF6600 angle=5
```

Coordinates specify the center of the base in the sender's world, and support `~` offsets.
Width is along X and length along Z. Each interval emits a complete ring starting at a randomly
chosen corner, with particles spaced no more than `horizontalStep` blocks apart along each side.
The first ring appears one `yStep` above the base, and subsequent rings rise by `yStep`.
Particles are given upward velocity, though some Minecraft particle types ignore it. Height is
relative to the base: the number of rings is `ceil(height / yStep)`, with the last ring capped
at the requested height. For example, height 15 and yStep 0.1 produce 150 rings. The interval
is in ticks, at least 1. Dimensions and steps must be positive finite numbers. A ring may contain
at most 1,024 particles; increase `horizontalStep` if the command rejects the ring size.
Optional `angle=` rotates each successive ring around the center by that many degrees per height
step; the first ring is unrotated. For example, `angle=5` turns the second ring 5 degrees and the
third 10 degrees. Negative values reverse the rotation; omitting it leaves the rings unrotated.

Particle names are Bukkit names or namespaced keys. Dust requires an initial six-digit RGB hex
`color=RRGGBB`; its rings fade from that color to white over the height of the effect. A single
ring uses the initial color. Other particles requiring extra data (such as block particles) are
not supported. An optional sound plays once per ring with volume and pitch 1. Without a selector,
it is audible normally to players in the world; a Minecraft
selector limits it to matching players resolved when the command runs. The command requires
`snapshotpve.particlespiral` (operators by default).

### Summoned mob dialogue and prologue sequences

Summon any spawnable entity type in the executing player's, entity's, or command block's world.
Use absolute coordinates or `~`/`~offset` relative to the sender. Provide SNBT as a compound, or
use `-` when no custom NBT is needed. Each dialogue entry uses
`COLOR%STYLE%weight%text`, with commas between entries. Add an optional `|sound=<sound|NONE>`
suffix to play a sound for that line; append `%<volume>%<pitch>` to set its sound level. Use
`DEFAULT` for WHITE and `NONE` for plain text. Escape a comma in text as `\,`, a literal pipe as
`\|`, a semicolon as `\;`, and a literal backslash as `\\`:

```text
/pvesummon <mob> <x> <y> <z> [--no-push] [<snbt>|-] <color>%<style>%<weight>%<text>,...
/pvesummon minecraft:villager ~ ~ ~ {NoAI:1b,CustomName:'{"text":"Guide"}'} GOLD%BOLD%70%Welcome traveler.|sound=ENTITY_VILLAGER_YES%0.8%1.2,RED%ITALIC%30%The road is dangerous.|sound=ENTITY_VILLAGER_NO
/pvesummon minecraft:zombie ~2 ~ ~ {IsBaby:1b} DEFAULT%NONE%80%Brains!,WHITE%BOLD%20%Run!
/pvesummon minecraft:villager ~ ~ ~ --no-push - DEFAULT%NONE%100%Welcome traveler.
/pvesummon minecraft:villager ~ ~ ~ - DEFAULT%NONE%100%Don't tell anyone\, but the guide said hello\|goodbye.
```

Use `--no-push` immediately after the coordinates to disable entity collisions for a living entity.
One weighted line is picked on each right-click; weights don't need to add up to 100. For a fixed
sequence, use `sequence=PLAYER` to message only the player who right-clicks, or `sequence=PLAYINGPVE`
to message every online player with the `playingPVE` tag. To choose a weighted sequence branch, use
`sequence=WEIGHTED_PLAYER` or `sequence=WEIGHTED_PLAYINGPVE`; branches use `<weight>:<sequence-lines>`
and are separated by semicolons:

```text
/pvesummon minecraft:villager ~ ~ ~ - sequence=PLAYER GOLD%BOLD%0%Welcome traveler.,WHITE%NONE%40%Follow the path ahead.
/pvesummon minecraft:villager ~ ~ ~ - sequence=PLAYER GOLD%BOLD%0%The gate clicks open.|sound=BLOCK_IRON_DOOR_OPEN%0.7%1.2,WHITE%NONE%30%Step inside.|sound=ENTITY_VILLAGER_YES
/pvesummon minecraft:villager ~ ~ ~ - sequence=PLAYINGPVE GOLD%BOLD%0%The gate is open.|append=WHITE%NONE%Move quickly.|check=Will you enter?|yes=tp %player% 0 64 0|no=-
/pvesummon minecraft:villager ~ ~ ~ - sequence=PLAYER DARK_GREEN%NONE%0%The road forks.|check=Choose a route.|option=Forest%GREEN%BOLD%tp %player% 24 72 24|option=River%AQUA%ITALIC%tp %player% 36 64 12|option=Turn back%RED%NONE%-
/pvesummon minecraft:villager ~ ~ ~ - sequence=WEIGHTED_PLAYER 95:WHITE%NONE%0%Go away. I'm eating.;5:WHITE%NONE%40%Have you ever considered this?,WHITE%NONE%40%They put preservatives in our foods.
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
from `yes=`, `no=`, or `option=` runs as the summoned entity, and `%player%` is replaced with the
name of the player who chose. `|run=<console-command>` runs when that sequence line appears, as the server console; it
requires `snapshotpve.summon.console` (default: operators). For `PLAYINGPVE`, the
first player to choose decides for everyone, and the group is told who chose what. Commas separate dialogue lines, `|` separates options, and semicolons separate weighted branches.
Escape them as `\,`, `\|`, and `\;` to include them in dialogue text or commands.

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

For colored PVE dialogue, command blocks can call `/pveprologuedialogue` or the
`/pvedefdialogue` variant, which adds the defSpaceMine speaker prefix. Add
`append=<color> <style> <text...>` to give the appended text its own color and style. Both commands
also accept a trailing `run=<console-command...>` parameter. It runs once after the delay, even if
no dialogue recipient is online, and requires `snapshotpve.summon.console` (default: operators).
When invoked from a command block, the command runs as that block to preserve its world context;
other senders run it as console.

```text
/pveprologuedialogue 0 GOLD NONE 1 1 The journey begins. target=@a[tag=playingPVE] run=setblock -346 65 -183 minecraft:redstone_block
/pvedefdialogue 40 WHITE NONE 1 1 Follow the path append=GOLD BOLD ahead. run=setblock -346 65 -183 minecraft:redstone_block
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
