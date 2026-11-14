package com.defspacemine.snapshotpve;

import com.defspacemine.snapshotpvp.SnapshotPvpPlugin;
import com.defspacemine.snapshotpvp.lobby.LobbyType;
import com.defspacemine.snapshotpvp.map.GameMapUtil;
import com.defspacemine.snapshotpvp.scoreboard.GlobalScoreboardManager;
import com.sk89q.worldedit.math.BlockVector3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.util.BoundingBox;

// PVE-specific lobby worlds, game state, and player flows
public class PveLobbyType extends LobbyType {

    // Pause between each bit of post-death dialogue
    private static final long DIALOGUE_DELAY_TICKS = 90L;
    // Active lobby instances, keyed by their main world name
    private static final Map<String, PveLobbyInstance> INSTANCES =
        new HashMap<>();

    // Short prefix used by the shared lobby system
    @Override
    public String getPrefix() {
        return "pvelobby";
    }

    // Build the PVE lobby wrapper for this world
    @Override
    public LobbyInstance make(World world) {
        SnapshotPvpPlugin.server.sendMessage(
            Component.text("Pve Lobby!" + world.getName())
        );
        return new PveLobbyInstance(world);
    }

    // The broad phases of a PVE lobby
    enum LobbyState {
        WAITING, // everyone needs to ready up
        VOTE_CONFIG, // pick game settings
        KIT_SELECTION, // choose a kit
        IN_GAME,
    }

    // The three world slots a PVE level can use
    enum WorldType {
        OVERWORLD("overworld"),
        NETHER("nether"),
        END("end");

        // Bukkit world-name suffix
        private final String name;

        // Keep the readable suffix for world lookup
        private WorldType(String name) {
            this.name = name;
        }

        // Expose the suffix to the lobby naming helpers
        String getName() {
            return name;
        }
    }

    // A configured level and its source-world map bounds
    enum CurrentLevel {
        LEVEL1(
            WorldType.OVERWORLD,
            1,
            new Location(null, -429.5, -11, -168.5, -180, 0),
            BlockVector3.at(-509, -19, -313),
            BlockVector3.at(-369, 65, -114)
        );

        // Dimension containing this level's playable area
        public final WorldType world;
        // Level number stored in the config
        public final int levelNumber;
        // Worldless template; getSpawnPoint(World) supplies the live world
        private final Location spawnPoint;
        // Opposite corners of the level map
        public final BlockVector3 min;
        public final BlockVector3 max;

        // Keep level metadata together in the enum entry
        private CurrentLevel(
            WorldType world,
            int levelNumber,
            Location spawnPoint,
            BlockVector3 min,
            BlockVector3 max
        ) {
            this.world = world;
            this.levelNumber = levelNumber;
            this.spawnPoint = spawnPoint;
            this.min = min;
            this.max = max;
        }

        // Find a configured level by its number
        public static CurrentLevel fromNumber(int levelNumber) {
            for (CurrentLevel level : values()) {
                if (level.levelNumber == levelNumber) {
                    return level;
                }
            }
            return null;
        }

        /** Source world holding the original level map */
        public String getSourceWorldName() {
            return "pvelvl" + levelNumber;
        }

        /** Fresh destination-world spawn; shared enum template left untouched */
        public Location getSpawnPoint(World destWorld) {
            Location spawn = spawnPoint.clone();
            spawn.setWorld(destWorld);
            return spawn;
        }

        /** Map copy into this lobby, with players moved to the level spawn */
        @SuppressWarnings("LoggerStringConcat")
        public boolean activateWorld(PveLobbyInstance lobby) {
            World srcWorld = Bukkit.getWorld(getSourceWorldName());
            World destWorld = lobby.getWorldType(world);
            // Missing source or destination worlds means there's nowhere safe to copy
            if (srcWorld == null || destWorld == null) {
                SnapshotPvePlugin.getInstance()
                    .getLogger()
                    .severe(
                        "Cannot activate " +
                            name() +
                            ": source world " +
                            getSourceWorldName() +
                            " or destination world " +
                            (destWorld == null
                                ? lobby.dimensionName(world)
                                : destWorld.getName()) +
                            " is not loaded."
                    );
                return false;
            }
            // Copy the level bounds, then gather the lobby players at its spawn
            GameMapUtil.cloneMap(srcWorld, min, max, destWorld, min);
            Location spawn = getSpawnPoint(destWorld);
            for (Player player : lobby.getPlayers()) {
                player.teleport(spawn);
            }
            return true;
        }

        /** This level's map cleared from the lobby world */
        public void removeWorld(PveLobbyInstance lobby) {
            World destWorld = lobby.getWorldType(world);
            // Nothing to clean up if the destination isn't loaded
            if (destWorld == null) {
                return;
            }
            GameMapUtil.removeMap(min, max, destWorld, min);
        }
    }

    public class PveLobbyInstance
        extends LobbyType.LobbyInstance
        implements Listener
    {

        // Main lobby world
        final World world;
        // Main lobby plus its overworld, nether, and end world names
        private final List<String> managedWorldNames;
        // Respawn locations waiting for Bukkit's respawn event
        final Map<UUID, Location> pendingRespawns = new HashMap<>();
        // Players who should return to adventure mode after respawning
        private final Set<UUID> pendingAdventureRespawns = new HashSet<>();
        // No-survivors warning, shown once per blocked-movement stretch
        private final Set<UUID> playersWarnedNoActiveTargets = new HashSet<>();

        // Current level, saved against the main lobby world
        CurrentLevel currentLevel;
        // Whether its map is currently copied into the dimension world
        boolean levelLoaded;
        // Current lobby/game phase
        LobbyState lobbyState = LobbyState.WAITING;
        // One post-death dialogue flow at a time
        boolean teamDialogueActive;
        // Lobby cleanup already started
        boolean destroyed;
        // Game ended after a death
        boolean gameOver = false;

        // Wire world tracking, config, events, and scoreboard hooks
        public PveLobbyInstance(World world) {
            this.world = world;

            List<String> names = new ArrayList<>();
            names.add(world.getName());
            // Track all dimension-world names alongside the lobby world
            for (WorldType type : WorldType.values()) {
                names.add(world.getName() + "_" + type.getName());
            }
            this.managedWorldNames = List.copyOf(names);
            this.currentLevel = loadLevel();

            SnapshotPvpPlugin.pluginManager.registerEvents(
                this,
                SnapshotPvpPlugin.instance
            );

            INSTANCES.put(world.getName(), this);
            managedWorldNames.forEach(this::registerScoreboardHandler);
        }

        // Predict this lobby's world name for a given dimension
        String dimensionName(WorldType type) {
            return world.getName() + "_" + type.getName();
        }

        /** Loaded world for this dimension, or null */
        public World getWorldType(WorldType type) {
            return Bukkit.getWorld(dimensionName(type));
        }

        // Hand scoreboard refreshes for this world back to the lobby
        private void registerScoreboardHandler(String worldName) {
            GlobalScoreboardManager.registerBoardHandler(
                worldName,
                this::scoreboardHandler
            );
        }

        // Read saved selection, falling back cleanly when it's missing or stale
        @SuppressWarnings("LoggerStringConcat")
        private CurrentLevel loadLevel() {
            String path = "pve-levels." + world.getName();
            var config = SnapshotPvePlugin.getInstance().getConfig();
            if (config.isInt(path)) {
                CurrentLevel saved = CurrentLevel.fromNumber(
                    config.getInt(path)
                );
                // Use the saved level only if it still exists
                if (saved != null) {
                    return saved;
                }
            }
            if (config.contains(path)) {
                // Keep a bad config value visible instead of silently ignoring it
                SnapshotPvePlugin.getInstance()
                    .getLogger()
                    .warning(
                        "Ignoring invalid PVE level at " +
                            path +
                            "; no such level exists."
                    );
            }
            return CurrentLevel.LEVEL1;
        }

        /** Old map unloaded and next level saved; startLevel() places it */
        void switchLevel(CurrentLevel next) {
            unloadLevel();
            currentLevel = next;
            SnapshotPvePlugin plugin = SnapshotPvePlugin.getInstance();
            plugin
                .getConfig()
                .set("pve-levels." + world.getName(), next.levelNumber);
            plugin.saveConfig();
        }

        /** Selected map copied in, unless already active */
        public boolean startLevel() {
            if (levelLoaded) {
                return false;
            }
            levelLoaded = currentLevel.activateWorld(this);
            return levelLoaded;
        }

        /** Current level map removed, if loaded */
        void unloadLevel() {
            if (!levelLoaded) {
                return;
            }
            levelLoaded = false;
            currentLevel.removeWorld(this);
        }

        /** Checkpoint location in the level's dimension world */
        private Location getCheckpoint() {
            World dimension = getWorldType(currentLevel.world);
            return dimension == null ? null : new Location(dimension, 0, 65, 0);
        }

        // Fresh level spawn in its destination dimension
        private Location getLevelSpawn() {
            World dimension = getWorldType(currentLevel.world);
            return dimension == null
                ? null
                : currentLevel.getSpawnPoint(dimension);
        }

        // Check a world name against this lobby's full world set
        private boolean managesWorld(World candidate) {
            return managedWorldNames.contains(candidate.getName());
        }

        /** Active lobby owning this world, or null */
        public static PveLobbyInstance forWorld(World candidate) {
            for (PveLobbyInstance instance : INSTANCES.values()) {
                if (instance.managesWorld(candidate)) {
                    return instance;
                }
            }
            return null;
        }

        /** PvP setting across every loaded dimension in active lobbies */
        public static int setPvpForAllLobbies(boolean enabled) {
            int updatedWorlds = 0;
            for (PveLobbyInstance instance : INSTANCES.values()) {
                for (WorldType type : WorldType.values()) {
                    World dimension = instance.getWorldType(type);
                    if (dimension != null) {
                        dimension.setPVP(enabled);
                        updatedWorlds++;
                    }
                }
            }
            return updatedWorlds;
        }

        /** Active maps removed before shutdown; lobby references cleared */
        public static void clearInstances() {
            for (PveLobbyInstance instance : new ArrayList<>(
                INSTANCES.values()
            )) {
                try {
                    instance.unloadLevel();
                } catch (RuntimeException exception) {
                    SnapshotPvePlugin.getInstance()
                        .getLogger()
                        .log(
                            java.util.logging.Level.WARNING,
                            "Could not remove the level from " +
                                instance.world.getName(),
                            exception
                        );
                }
            }
            INSTANCES.clear();
        }

        // Add scoreboard refresh handling when one of our worlds loads
        @EventHandler
        public void onWorldLoad(WorldLoadEvent event) {
            String loadedName = event.getWorld().getName();
            if (managedWorldNames.contains(loadedName)) {
                registerScoreboardHandler(loadedName);
            }
        }

        // Refresh the sidebar when a relevant player arrives in a managed world
        @EventHandler
        public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
            Player player = event.getPlayer();
            if (
                canSeePveSidebar(player) &&
                managedWorldNames.contains(player.getWorld().getName())
            ) {
                scoreboardHandler(player);
            }
        }

        // Keep PVE spectators from teleporting out of their lobby's world set
        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onSpectatorTeleport(PlayerTeleportEvent event) {
            Player player = event.getPlayer();
            World destination = event.getTo().getWorld();
            if (
                player.getGameMode() == GameMode.SPECTATOR &&
                managedWorldNames.contains(player.getWorld().getName()) &&
                (destination == null ||
                    !managedWorldNames.contains(destination.getName()))
            ) {
                event.setCancelled(true);
                player.sendMessage(
                    Component.text(
                        "You cannot teleport outside PVE while in spectator mode.",
                        NamedTextColor.RED
                    )
                );
            }
        }

        @Override
        public World getWorld() {
            return world;
        }

        // Lobby API view of the players currently in any managed world
        @Override
        public List<Player> getPlayers() {
            return getLobbyPlayers();
        }

        // PVE tags or the shared PVP team names both mark participation
        private boolean isPveParticipant(Player player) {
            return (
                PveDialogue.hasPveTag(player) ||
                isOnTeam(player, "survivor") ||
                isOnTeam(player, "spectator")
            );
        }

        // Drop only the PVE-specific role tags
        private void clearPveRoleTags(Player player) {
            player.removeScoreboardTag("playingPVE");
            player.removeScoreboardTag("spectatingPVE");
        }

        // Restore a safe mode before leaving, then remove PVE tags on success
        private boolean teleportToLobby(Player player, Location destination) {
            boolean hasPveRoleTag = PveDialogue.hasPveTag(player);
            GameMode previousGameMode = player.getGameMode();
            if (hasPveRoleTag) {
                player.setGameMode(GameMode.ADVENTURE);
            }
            if (!player.teleport(destination)) {
                // Put their old mode back if Bukkit rejected the teleport
                if (hasPveRoleTag) {
                    player.setGameMode(previousGameMode);
                }
                return false;
            }
            clearPveRoleTags(player);
            return true;
        }

        // Reuse the host plugin's team lookup
        private boolean isOnTeam(Player player, String teamName) {
            var team = SnapshotPvpPlugin.getTeam(player);
            return team != null && team.getName().equals(teamName);
        }

        // Gather everyone from the lobby and its loaded dimensions
        private List<Player> getLobbyPlayers() {
            List<Player> players = new ArrayList<>();
            for (String name : managedWorldNames) {
                World managed = Bukkit.getWorld(name);
                if (managed != null) {
                    players.addAll(managed.getPlayers());
                }
            }
            return players;
        }

        // Assemble this viewer's PVE sidebar from the current lobby state
        private void scoreboardHandler(Player viewer) {
            if (!canSeePveSidebar(viewer)) {
                return;
            }
            if (gameOver) {
                // Restore the shared board after the game is over
                viewer.setScoreboard(
                    Bukkit.getScoreboardManager().getMainScoreboard()
                );
                return;
            }

            List<Player> players = new ArrayList<>(getTeamPlayers());
            // Count survivors separately from spectators
            long survivorCount = players
                .stream()
                .filter(player ->
                    player.getScoreboardTags().contains("playingPVE")
                )
                .count();
            Scoreboard board = GlobalScoreboardManager.makeNewScoreboard(
                viewer,
                players
            );
            GlobalScoreboardManager.setupScoreboardHP(board, players);
            Objective objective = GlobalScoreboardManager.initializeCombatList(
                board
            );
            objective.displayName(
                Component.text("---- PVE ----")
                    .color(NamedTextColor.GOLD)
                    .decorate(TextDecoration.BOLD)
            );

            // Clear previous hidden entries before rebuilding the sidebar
            for (String entry : board.getEntries()) {
                objective.getScore(entry).resetScore();
            }

            int position = 100;
            // Status and optional lobby-rule rows
            GlobalScoreboardManager.setHiddenScore(objective, "", position--);
            GlobalScoreboardManager.setHiddenScore(
                objective,
                ChatColor.GRAY + "STATUS",
                ChatColor.YELLOW + lobbyState.name().replace('_', ' '),
                position--
            );
            GlobalScoreboardManager.setHiddenScore(
                objective,
                ChatColor.GRAY + "NUMBER OF SURVIVORS",
                ChatColor.WHITE + String.valueOf(survivorCount),
                position--
            );
            GlobalScoreboardManager.setHiddenScore(objective, " ", position--);

            List<Player> lobbyPlayers = getLobbyPlayers();
            if (hasLobbyTag(lobbyPlayers, "randomPveEffects")) {
                GlobalScoreboardManager.setHiddenScore(
                    objective,
                    ChatColor.GRAY + "RANDOMIZED EFFECTS",
                    ChatColor.GREEN + "ON",
                    position--
                );
            }
            if (hasLobbyTag(lobbyPlayers, "creakingPVE")) {
                GlobalScoreboardManager.setHiddenScore(
                    objective,
                    ChatColor.GRAY + "CREAKING",
                    ChatColor.GREEN + "ON",
                    position--
                );
            }
            if (hasLobbyTag(lobbyPlayers, "timedPVE")) {
                GlobalScoreboardManager.setHiddenScore(
                    objective,
                    ChatColor.GRAY + "TIMED",
                    ChatColor.GREEN + "ON",
                    position--
                );
            }

            Boolean pvpEnabled = getLobbyPvpSetting();
            // No loaded dimension means the setting can't be confirmed
            String friendlyFire =
                pvpEnabled == null
                    ? ChatColor.GRAY + "UNKNOWN"
                    : pvpEnabled
                      ? ChatColor.GREEN + "ENABLED"
                      : ChatColor.RED + "DISABLED";
            GlobalScoreboardManager.setHiddenScore(
                objective,
                ChatColor.GRAY + "FRIENDLY FIRE",
                friendlyFire,
                position--
            );

            String difficulty = getLobbyDifficulty(lobbyPlayers);
            if (difficulty != null) {
                GlobalScoreboardManager.setHiddenScore(
                    objective,
                    ChatColor.GRAY + "DIFFICULTY",
                    difficulty,
                    position--
                );
            }

            players.sort((first, second) ->
                first.getName().compareToIgnoreCase(second.getName())
            );
            // Stable player order, role labels, and health totals
            for (Player player : players) {
                boolean playing = player
                    .getScoreboardTags()
                    .contains("playingPVE");
                // Keep role tags from contradicting each other
                if (playing) {
                    player.removeScoreboardTag("spectatingPVE");
                }
                String status = playing
                    ? ChatColor.DARK_GREEN.toString() +
                      ChatColor.BOLD +
                      "[SURVIVOR]"
                    : gradientBoldText("[SPECTATOR]");
                String leftSide =
                    status + ChatColor.RESET + " " + player.getName();

                double health = player.getHealth();
                // Health color tracks how close they are to trouble
                ChatColor healthColor =
                    health >= 15
                        ? ChatColor.GREEN
                        : health >= 5
                          ? ChatColor.YELLOW
                          : ChatColor.RED;
                String rightSide =
                    healthColor.toString() + (int) Math.ceil(health) + "❤";
                double absorption = player.getAbsorptionAmount();
                // Show golden hearts separately from regular health
                if (absorption > 0) {
                    rightSide +=
                        ChatColor.YELLOW.toString() +
                        " +" +
                        (int) Math.ceil(absorption) +
                        "❤";
                }
                GlobalScoreboardManager.setHiddenScore(
                    objective,
                    leftSide,
                    rightSide,
                    position--
                );
            }

            GlobalScoreboardManager.setupScoreboardHP(board, players);
        }

        // Sidebar is for participants and people still in the lobby phase
        private boolean canSeePveSidebar(Player player) {
            return (
                isPveParticipant(player) ||
                player.getScoreboardTags().contains("inPVELobby")
            );
        }

        // Lobby-wide options are stored as tags on any lobby player
        private boolean hasLobbyTag(List<Player> lobbyPlayers, String tag) {
            return lobbyPlayers
                .stream()
                .anyMatch(player -> player.getScoreboardTags().contains(tag));
        }

        // First loaded dimension is enough to report the lobby PvP setting
        private Boolean getLobbyPvpSetting() {
            for (WorldType type : WorldType.values()) {
                World dimension = getWorldType(type);
                if (dimension != null) {
                    return dimension.getPVP();
                }
            }
            return null;
        }

        // Turn the first matching difficulty tag into a sidebar label
        private String getLobbyDifficulty(List<Player> lobbyPlayers) {
            String[] names = { "DEBUG", "EASY", "NORMAL", "HARD", "ASCENDED" };
            for (int difficulty = 0; difficulty < names.length; difficulty++) {
                String tag = "difficulty" + difficulty;
                if (hasLobbyTag(lobbyPlayers, tag)) {
                    ChatColor color =
                        difficulty == 0
                            ? ChatColor.GREEN
                            : difficulty == 4
                              ? ChatColor.RED
                              : ChatColor.WHITE;
                    String style =
                        difficulty == 0 || difficulty == 4
                            ? ChatColor.BOLD.toString()
                            : "";
                    return color + style + names[difficulty];
                }
            }
            return null;
        }

        // Color the spectator label from dark green through white
        private String gradientBoldText(String text) {
            Component gradient = Component.empty();

            for (int i = 0; i < text.length(); i++) {
                float progress =
                    text.length() == 1 ? 0 : (float) i / (text.length() - 1);
                NamedTextColor color;
                if (progress < 0.4F) {
                    color = NamedTextColor.DARK_GREEN;
                } else if (progress < 0.7F) {
                    color = NamedTextColor.GREEN;
                } else {
                    color = NamedTextColor.WHITE;
                }
                gradient = gradient.append(
                    Component.text(text.charAt(i), color).decorate(
                        TextDecoration.BOLD
                    )
                );
            }

            return LegacyComponentSerializer.legacySection().serialize(
                gradient
            );
        }

        @Override
        public void onTick() {
            // Keep spectator tags and game modes in sync
            for (Player player : getLobbyPlayers()) {
                if (
                    player.getScoreboardTags().contains("spectatingPVE") &&
                    player.getGameMode() != GameMode.SPECTATOR
                ) {
                    player.setGameMode(GameMode.SPECTATOR);
                }
            }
            // Future per-tick game-rule logic
            {}
        }

        // Keep spectators inside solid blocks pinned to a live survivor
        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onSpectatorMove(PlayerMoveEvent event) {
            Player player = event.getPlayer();
            if (
                !managedWorldNames.contains(player.getWorld().getName()) ||
                !player.getScoreboardTags().contains("spectatingPVE")
            ) {
                return;
            }
            if (player.getGameMode() != GameMode.SPECTATOR) {
                player.setGameMode(GameMode.SPECTATOR);
            }

            Location from = event.getFrom();
            Location to = event.getTo();
            if (
                to == null ||
                !from.getWorld().equals(to.getWorld()) ||
                !intersectsSolidBlock(player, to)
            ) {
                // Clear the warning cooldown once they're out of the blocked spot
                playersWarnedNoActiveTargets.remove(player.getUniqueId());
                return;
            }

            Player nearestPlayer = getLobbyPlayers()
                .stream()
                .filter(
                    target ->
                        target != player &&
                        target.isOnline() &&
                        target.getScoreboardTags().contains("playingPVE")
                )
                .min((first, second) ->
                    Double.compare(
                        distanceSquared(to, first.getLocation()),
                        distanceSquared(to, second.getLocation())
                    )
                )
                .orElse(null);
            if (nearestPlayer == null) {
                // No survivor to follow; undo the move and warn only once
                event.setTo(from);
                if (playersWarnedNoActiveTargets.add(player.getUniqueId())) {
                    player.sendMessage(
                        Component.text(
                            "Cannot teleport: there are no players tagged playingPVE in this PVE lobby.",
                            NamedTextColor.RED
                        )
                    );
                }
                return;
            }
            // Put them at the nearest active player
            playersWarnedNoActiveTargets.remove(player.getUniqueId());
            player.teleport(nearestPlayer.getLocation());
        }

        // Check the player's full hitbox against nearby solid blocks
        private boolean intersectsSolidBlock(Player player, Location to) {
            double halfWidth = player.getWidth() / 2.0;
            BoundingBox playerBox = new BoundingBox(
                to.getX() - halfWidth,
                to.getY(),
                to.getZ() - halfWidth,
                to.getX() + halfWidth,
                to.getY() + player.getHeight(),
                to.getZ() + halfWidth
            );
            int minX = (int) Math.floor(playerBox.getMinX());
            int minY = (int) Math.floor(playerBox.getMinY());
            int minZ = (int) Math.floor(playerBox.getMinZ());
            int maxX = (int) Math.floor(playerBox.getMaxX());
            int maxY = (int) Math.floor(playerBox.getMaxY());
            int maxZ = (int) Math.floor(playerBox.getMaxZ());
            World destinationWorld = to.getWorld();
            // No world means there are no blocks to test
            if (destinationWorld == null) {
                return false;
            }

            // Only count blocks that actually overlap the player's box
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        Block block = destinationWorld.getBlockAt(x, y, z);
                        if (
                            !block.isPassable() &&
                            block.getBoundingBox().overlaps(playerBox)
                        ) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        // Squared distance keeps nearest-player sorting cheap
        private double distanceSquared(Location first, Location second) {
            double x = first.getX() - second.getX();
            double y = first.getY() - second.getY();
            double z = first.getZ() - second.getZ();
            return x * x + y * y + z * z;
        }

        // Queue the dead player's return and start the group flow once
        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        @SuppressWarnings("LoggerStringConcat")
        public void onPlayerDeath(PlayerDeathEvent event) {
            Player deadPlayer = event.getPlayer();
            if (
                !levelLoaded ||
                !isPveParticipant(deadPlayer) ||
                !deadPlayer.getWorld().equals(getWorldType(currentLevel.world))
            ) {
                return;
            }
            deadPlayer.setGameMode(GameMode.SPECTATOR);

            Location checkpoint = getCheckpoint();
            if (checkpoint == null) {
                // Leave a clear log trail instead of queuing an invalid spawn
                SnapshotPvePlugin.getInstance()
                    .getLogger()
                    .severe(
                        "Cannot respawn player from " +
                            currentLevel.name() +
                            ": " +
                            dimensionName(currentLevel.world) +
                            " is not loaded."
                    );
                return;
            }
            pendingRespawns.put(deadPlayer.getUniqueId(), checkpoint);
            // A group prompt is already running; just keep the new respawn queued
            if (teamDialogueActive) {
                return;
            }
            teamDialogueActive = true;

            Bukkit.getScheduler().runTaskLater(
                SnapshotPvpPlugin.instance,
                () -> {
                    if (destroyed) {
                        return;
                    }

                    // Clear tagged hunters from the checkpoint area
                    for (Entity entity : checkpoint.getWorld().getEntities()) {
                        var team = SnapshotPvpPlugin.getTeamE(entity);
                        if (team != null && team.getName().equals("hunter")) {
                            entity.remove();
                        }
                    }

                    // move everyone out first, then take the map away
                    for (Player player : getTeamPlayers()) {
                        if (player != deadPlayer) {
                            player.teleport(checkpoint);
                        }
                    }
                    unloadLevel();

                    checkpointTellraw();
                },
                2L
            );
        }

        // Back to default lobby rules once the group opts out
        private void resetLobbySettings() {
            for (WorldType type : WorldType.values()) {
                World dimension = getWorldType(type);
                if (dimension != null) {
                    dimension.setPVP(false);
                }
            }
            for (Player player : getLobbyPlayers()) {
                player.removeScoreboardTag("creakingPVE");
                player.removeScoreboardTag("randomPveEffects");
                player.removeScoreboardTag("timedPVE");
            }
        }

        // Apply the stored destination during Bukkit's respawn flow
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onPlayerRespawn(PlayerRespawnEvent event) {
            Player player = event.getPlayer();
            Location destination = pendingRespawns.remove(player.getUniqueId());
            if (destination == null) {
                return;
            }
            event.setRespawnLocation(destination);
            // Continuing players need adventure mode once they land
            boolean returnToLevel = pendingAdventureRespawns.remove(
                player.getUniqueId()
            );
            Bukkit.getScheduler().runTaskLater(
                SnapshotPvpPlugin.instance,
                () -> {
                    // Respawn callback may run after a disconnect or lobby change
                    if (!player.isOnline() || !isPveParticipant(player)) {
                        return;
                    }
                    if (returnToLevel) {
                        if (player.teleport(destination)) {
                            player.setGameMode(GameMode.ADVENTURE);
                        }
                    } else if (world.equals(destination.getWorld())) {
                        teleportToLobby(player, destination);
                    } else {
                        player.teleport(destination);
                    }
                },
                2L
            );
        }

        // Unhook the instance and clear its map, timers, and listeners
        @Override
        public void onDestroy() {
            destroyed = true;
            teamDialogueActive = false;
            unloadLevel();
            for (Player player : getLobbyPlayers()) {
                PveDialogue.clearPendingChoice(player);
                pendingRespawns.remove(player.getUniqueId());
                pendingAdventureRespawns.remove(player.getUniqueId());
                playersWarnedNoActiveTargets.remove(player.getUniqueId());
            }
            INSTANCES.remove(world.getName(), this);
            managedWorldNames.forEach(
                GlobalScoreboardManager::removeBoardHandler
            );
            HandlerList.unregisterAll(this);

            SnapshotPvpPlugin.server.sendMessage(
                Component.text("No more pve lobby. ;( " + world.getName())
            );
        }

        // Online PVE participants across the full lobby world set
        private List<Player> getTeamPlayers() {
            return getLobbyPlayers()
                .stream()
                .filter(Player::isOnline)
                .filter(this::isPveParticipant)
                .toList();
        }

        // Start the group's post-death dialogue after a wipe or setback
        public void checkpointTellraw() {
            List<Player> players = getTeamPlayers();
            if (players.isEmpty()) {
                teamDialogueActive = false;
                return;
            }
            String deathMessage =
                players.size() == 1 ? "You have died." : "One of you has died.";
            sendSpeakerLine(
                players,
                Component.text(deathMessage, NamedTextColor.RED)
            );

            scheduleTeamDialogue(
                // Branch into the short prompt or the longer explanation
                () ->
                    offerChoices(
                        "Have you died before?",
                        (clicker, hasDiedBefore) -> {
                            if (hasDiedBefore) {
                                askToContinue();
                            } else {
                                startExplanation();
                            }
                        }
                    ),
                DIALOGUE_DELAY_TICKS
            );
        }

        // Delayed dialogue needs to recheck the lobby before it runs
        private void scheduleTeamDialogue(Runnable dialogue, long delay) {
            Bukkit.getScheduler().runTaskLater(
                SnapshotPvePlugin.getInstance(),
                () -> {
                    // Stop queued lines if the lobby or its audience went away
                    if (destroyed) {
                        teamDialogueActive = false;
                        return;
                    }
                    if (!teamDialogueActive) {
                        return;
                    }
                    if (getTeamPlayers().isEmpty()) {
                        teamDialogueActive = false;
                        return;
                    }
                    dialogue.run();
                },
                delay
            );
        }

        // Ask the group whether to reload the level
        private void askToContinue() {
            offerChoices(
                "Would you like to continue?",
                (clicker, wantsToContinue) ->
                    resolveContinueChoice(wantsToContinue)
            );
        }

        // The longer story path for players new to the death flow
        private void startExplanation() {
            List<Component> lines = List.of(
                Component.text(
                    "For most of those in the world, death is the end of everything.",
                    NamedTextColor.RED
                ),
                Component.text(
                    "However, for those who have been blessed with power by ",
                    NamedTextColor.RED
                )
                    .append(
                        Component.text(
                            "defSpaceMine",
                            NamedTextColor.RED
                        ).decorate(TextDecoration.OBFUSCATED)
                    )
                    .append(
                        Component.text(
                            ", death is NOT the end.",
                            NamedTextColor.RED
                        )
                    ),
                Component.text(
                    "The world, upon your creation, deems you worthy or unworthy.",
                    NamedTextColor.RED
                ),
                Component.text(
                    "In situations in which the enemies that have come to face you have been conquered, the world will always deem you worthy.",
                    NamedTextColor.RED
                ),
                Component.text(
                    "However, in times of uncertainty or defeat, the world seeks to restore the itself to the last point at which it has considered you worthy.",
                    NamedTextColor.RED
                ),
                Component.text(
                    "In doing so, you may return anew and prepared to face the enemies that had once bested you.",
                    NamedTextColor.RED
                )
            );
            sendExplanationLine(lines, 0);
        }

        // Send one story line, then queue the next line or the follow-up prompt
        private void sendExplanationLine(List<Component> lines, int index) {
            List<Player> players = getTeamPlayers();
            if (destroyed || !teamDialogueActive || players.isEmpty()) {
                return;
            }
            if (index >= lines.size()) {
                // Story's done; ask whether they want another shot
                scheduleTeamDialogue(
                    () ->
                        offerChoices(
                            "With that knowledge, would you like to be given another chance?",
                            (clicker, wantsAnotherChance) ->
                                resolveContinueChoice(wantsAnotherChance)
                        ),
                    DIALOGUE_DELAY_TICKS
                );
                return;
            }

            // One line per delay, so the chat stays readable
            sendSpeakerLine(players, lines.get(index));
            scheduleTeamDialogue(
                () -> sendExplanationLine(lines, index + 1),
                DIALOGUE_DELAY_TICKS
            );
        }

        // One answer token shared by everybody in the current group
        private void offerChoices(
            String prompt,
            BiConsumer<Player, Boolean> callback
        ) {
            List<Player> players = getTeamPlayers();
            if (!players.isEmpty()) {
                Component choice = PveDialogue.choicePrompt(
                    prompt,
                    players,
                    callback
                );
                for (Player player : players) {
                    player.sendMessage(choice);
                }
            }
        }

        // Shared redacted speaker prefix for story lines
        private Component speakerLine(Component message) {
            return Component.empty()
                .append(Component.text("<", NamedTextColor.RED))
                .append(
                    Component.text("defSpaceMine", NamedTextColor.RED).decorate(
                        TextDecoration.OBFUSCATED
                    )
                )
                .append(Component.text("> ", NamedTextColor.RED))
                .append(message);
        }

        // Send the same speaker line and sound to each recipient
        private void sendSpeakerLine(
            List<Player> recipients,
            Component message
        ) {
            Component line = speakerLine(message);
            for (Player recipient : recipients) {
                recipient.sendMessage(line);
            }
            // Only tagged PVE players hear the speaker sound
            for (Player player : recipients) {
                if (PveDialogue.hasPveTag(player)) {
                    player.playSound(
                        player.getLocation(),
                        Sound.BLOCK_BAMBOO_PLACE,
                        2.0F,
                        1.0F
                    );
                }
            }
        }

        // Reload the level or send everyone back to the main lobby
        private void resolveContinueChoice(boolean wantsToContinue) {
            List<Player> players = getTeamPlayers();
            if (players.isEmpty()) {
                teamDialogueActive = false;
                return;
            }
            sendSpeakerLine(
                players,
                Component.text("I understand.", NamedTextColor.RED)
            );

            // Continuing reloads the map; declining heads back to the main lobby
            Location spawn = wantsToContinue
                ? getLevelSpawn()
                : world.getSpawnLocation();
            if (wantsToContinue && !levelLoaded && !startLevel()) {
                for (Player player : players) {
                    player.sendMessage(
                        Component.text(
                            "Could not reload the PVE level.",
                            NamedTextColor.RED
                        )
                    );
                }
                teamDialogueActive = false;
                return;
            }
            if (spawn == null) {
                // Don't teleport into a missing dimension
                for (Player player : players) {
                    player.sendMessage(
                        Component.text(
                            "The return world " +
                                dimensionName(currentLevel.world) +
                                " is not loaded.",
                            NamedTextColor.RED
                        )
                    );
                }
                teamDialogueActive = false;
                return;
            }

            if (!wantsToContinue) {
                resetLobbySettings();
            }
            // Dead players need a queued respawn; living players can move now
            for (Player player : players) {
                if (player.isDead()) {
                    pendingRespawns.put(player.getUniqueId(), spawn);
                    if (wantsToContinue) {
                        pendingAdventureRespawns.add(player.getUniqueId());
                    }
                } else if (wantsToContinue) {
                    if (player.teleport(spawn)) {
                        player.setGameMode(GameMode.ADVENTURE);
                    }
                } else {
                    teleportToLobby(player, spawn);
                }
            }
            teamDialogueActive = false;
        }
    }
}
