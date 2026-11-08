package com.defspacemine.snapshotpve;

import com.defspacemine.snapshotpvp.SnapshotPvpPlugin;
import com.defspacemine.snapshotpvp.lobby.LobbyType;
import com.defspacemine.snapshotpvp.map.GameMapUtil;
import com.defspacemine.snapshotpvp.scoreboard.GlobalScoreboardManager;
import com.sk89q.worldedit.math.BlockVector3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

public class PveLobbyType extends LobbyType {

    private static final long DIALOGUE_DELAY_TICKS = 90L;
    private static final Map<String, PveLobbyInstance> INSTANCES =
        new HashMap<>();

    @Override
    public String getPrefix() {
        return "pvelobby";
    }

    @Override
    public LobbyInstance make(World world) {
        SnapshotPvpPlugin.server.sendMessage(
            Component.text("Pve Lobby!" + world.getName())
        );
        return new PveLobbyInstance(world);
    }

    enum LobbyState {
        WAITING, // everyone needs to ready up
        VOTE_CONFIG, // game settings
        KIT_SELECTION, // choose kits
        IN_GAME,
    }

    enum WorldType {
        OVERWORLD("overworld"),
        NETHER("nether"),
        END("end");

        private final String name;

        private WorldType(String name) {
            this.name = name;
        }

        String getName() {
            return name;
        }
    }

    enum CurrentLevel {
        LEVEL1(
            WorldType.OVERWORLD,
            1,
            new Location(null, -429.5, -11, -168.5, -180, 0),
            BlockVector3.at(-509, -19, -313),
            BlockVector3.at(-369, 65, -114)
        );

        public final WorldType world;
        public final int levelNumber;
        // template only (no world); use getSpawnPoint(World) to get a usable copy
        private final Location spawnPoint;
        public final BlockVector3 min;
        public final BlockVector3 max;

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

        public static CurrentLevel fromNumber(int levelNumber) {
            for (CurrentLevel level : values()) {
                if (level.levelNumber == levelNumber) {
                    return level;
                }
            }
            return null;
        }

        /** Name of the world that holds the original copy of this level. */
        public String getSourceWorldName() {
            return "pvelvl" + levelNumber;
        }

        /**
         * Returns a fresh copy of the spawn point in the given world. The enum constant is
         * shared by every lobby, so it must never be given a world directly.
         */
        public Location getSpawnPoint(World destWorld) {
            Location spawn = spawnPoint.clone();
            spawn.setWorld(destWorld);
            return spawn;
        }

        /** Copies the level into the lobby's dimension world and teleports the players in. */
        public boolean activateWorld(PveLobbyInstance lobby) {
            World srcWorld = Bukkit.getWorld(getSourceWorldName());
            World destWorld = lobby.getWorldType(world);
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
            GameMapUtil.cloneMap(srcWorld, min, max, destWorld, min);
            Location spawn = getSpawnPoint(destWorld);
            for (Player player : lobby.getPlayers()) {
                player.teleport(spawn);
            }
            return true;
        }

        /** Removes the level from the lobby's dimension world. */
        public void removeWorld(PveLobbyInstance lobby) {
            World destWorld = lobby.getWorldType(world);
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

        final World world; // main lobby world
        // names of the lobby world and its overworld/nether/end worlds
        private final List<String> managedWorldNames;
        final Map<UUID, Location> pendingRespawns = new HashMap<>();

        CurrentLevel currentLevel; // the level this lobby is on (persisted in config)
        boolean levelLoaded; // whether currentLevel's map is currently in the world
        LobbyState lobbyState = LobbyState.WAITING;
        boolean teamDialogueActive;
        boolean destroyed;
        boolean gameOver = false; // if the game ended bc someone died, then this should be true

        public PveLobbyInstance(World world) {
            this.world = world;

            List<String> names = new ArrayList<>();
            names.add(world.getName());
            for (WorldType type : WorldType.values()) {
                names.add(dimensionName(type));
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

        String dimensionName(WorldType type) {
            return world.getName() + "_" + type.getName();
        }

        /** The lobby's world for the given dimension, or null if it isn't loaded. */
        public World getWorldType(WorldType type) {
            return Bukkit.getWorld(dimensionName(type));
        }

        private void registerScoreboardHandler(String worldName) {
            GlobalScoreboardManager.registerBoardHandler(
                worldName,
                this::scoreboardHandler
            );
        }

        private CurrentLevel loadLevel() {
            String path = "pve-levels." + world.getName();
            var config = SnapshotPvePlugin.getInstance().getConfig();
            if (config.isInt(path)) {
                CurrentLevel saved = CurrentLevel.fromNumber(
                    config.getInt(path)
                );
                if (saved != null) {
                    return saved;
                }
            }
            if (config.contains(path)) {
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

        /**
         * Switches to another level: removes the current level's map, then
         * remembers the new level. Call startLevel() to actually place it.
         */
        void switchLevel(CurrentLevel next) {
            unloadLevel();
            currentLevel = next;
            SnapshotPvePlugin plugin = SnapshotPvePlugin.getInstance();
            plugin
                .getConfig()
                .set("pve-levels." + world.getName(), next.levelNumber);
            plugin.saveConfig();
        }

        /** Copies the current level into the world and teleports the players in. */
        public boolean startLevel() {
            if (levelLoaded) {
                return false;
            }
            levelLoaded = currentLevel.activateWorld(this);
            return levelLoaded;
        }

        /** Removes the current level's map from the world, if it's there. */
        void unloadLevel() {
            if (!levelLoaded) {
                return;
            }
            levelLoaded = false;
            currentLevel.removeWorld(this);
        }

        /** Where players go when a level ends: the spawn of the level's dimension world. */
        private Location getCheckpoint() {
            World dimension = getWorldType(currentLevel.world);
            return dimension == null ? null : dimension.getSpawnLocation();
        }

        private boolean managesWorld(World candidate) {
            return managedWorldNames.contains(candidate.getName());
        }

        /** The active lobby that owns this world (lobby world or one of its dimensions), or null. */
        public static PveLobbyInstance forWorld(World candidate) {
            for (PveLobbyInstance instance : INSTANCES.values()) {
                if (instance.managesWorld(candidate)) {
                    return instance;
                }
            }
            return null;
        }

        /** Called on plugin disable: take every lobby's level map out of its world. */
        public static void clearInstances() {
            for (PveLobbyInstance instance : new ArrayList<>(INSTANCES.values())) {
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

        @EventHandler
        public void onWorldLoad(WorldLoadEvent event) {
            String loadedName = event.getWorld().getName();
            if (managedWorldNames.contains(loadedName)) {
                registerScoreboardHandler(loadedName);
            }
        }

        @EventHandler
        public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
            Player player = event.getPlayer();
            if (
                isPveParticipant(player) &&
                managedWorldNames.contains(player.getWorld().getName())
            ) {
                scoreboardHandler(player);
            }
        }

        @Override
        public World getWorld() {
            return world;
        }

        @Override
        public List<Player> getPlayers() {
            return getLobbyPlayers();
        }

        private boolean isPveParticipant(Player player) {
            return (
                PveDialogue.hasPveTag(player) ||
                isOnTeam(player, "survivor") ||
                isOnTeam(player, "spectator")
            );
        }

        private boolean isOnTeam(Player player, String teamName) {
            var team = SnapshotPvpPlugin.getTeam(player);
            return team != null && team.getName().equals(teamName);
        }

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

        private void scoreboardHandler(Player viewer) {
            if (!isPveParticipant(viewer)) {
                return;
            }
            if (gameOver) {
                viewer.setScoreboard(
                    Bukkit.getScoreboardManager().getMainScoreboard()
                );
                return;
            }

            List<Player> players = new ArrayList<>(getTeamPlayers());
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

            for (String entry : board.getEntries()) {
                objective.getScore(entry).resetScore();
            }

            int position = 100;
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

            players.sort((first, second) ->
                first.getName().compareToIgnoreCase(second.getName())
            );
            for (Player player : players) {
                boolean playing = player
                    .getScoreboardTags()
                    .contains("playingPVE");
                if (playing) {
                    player.removeScoreboardTag("spectatingPVE");
                }
                String status = playing
                    ? ChatColor.DARK_GREEN.toString() +
                      ChatColor.BOLD +
                      "[SURVIVOR]"
                    : gradientBoldText("[SPECTATOR]");
                String leftSide =
                    status + ChatColor.RESET + " " + player.getName() + ":";

                double health = player.getHealth();
                ChatColor healthColor =
                    health >= 15
                        ? ChatColor.GREEN
                        : health >= 5
                          ? ChatColor.YELLOW
                          : ChatColor.RED;
                String rightSide =
                    healthColor.toString() + (int) Math.ceil(health) + "❤";
                double absorption = player.getAbsorptionAmount();
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
            // if (lobbyState == LobbyState.IN_GAME)
            {
                // run stuff
            }
        }

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onPlayerDeath(PlayerDeathEvent event) {
            Player deadPlayer = event.getPlayer();
            if (
                !levelLoaded ||
                !isPveParticipant(deadPlayer) ||
                !deadPlayer.getWorld().equals(getWorldType(currentLevel.world))
            ) {
                return;
            }

            Location checkpoint = getCheckpoint();
            if (checkpoint == null) {
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

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onPlayerRespawn(PlayerRespawnEvent event) {
            Player player = event.getPlayer();
            Location destination = pendingRespawns.remove(player.getUniqueId());
            if (destination == null) {
                return;
            }
            event.setRespawnLocation(destination);
            Bukkit.getScheduler().runTaskLater(
                SnapshotPvpPlugin.instance,
                () -> {
                    if (
                        player.isOnline() &&
                        isPveParticipant(player) &&
                        player.teleport(destination) &&
                        world.equals(destination.getWorld())
                    ) {
                        player.removeScoreboardTag("playingPVE");
                    }
                },
                2L
            );
        }

        @Override
        public void onDestroy() {
            destroyed = true;
            teamDialogueActive = false;
            unloadLevel();
            for (Player player : getLobbyPlayers()) {
                PveDialogue.clearPendingChoice(player);
                pendingRespawns.remove(player.getUniqueId());
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

        private List<Player> getTeamPlayers() {
            return getLobbyPlayers()
                .stream()
                .filter(Player::isOnline)
                .filter(this::isPveParticipant)
                .toList();
        }

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

        private void scheduleTeamDialogue(Runnable dialogue, long delay) {
            Bukkit.getScheduler().runTaskLater(
                SnapshotPvePlugin.getInstance(),
                () -> {
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

        private void askToContinue() {
            offerChoices(
                "Would you like to continue?",
                (clicker, wantsToContinue) ->
                    resolveContinueChoice(wantsToContinue)
            );
        }

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

        private void sendExplanationLine(List<Component> lines, int index) {
            List<Player> players = getTeamPlayers();
            if (destroyed || !teamDialogueActive || players.isEmpty()) {
                return;
            }
            if (index >= lines.size()) {
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

            sendSpeakerLine(players, lines.get(index));
            scheduleTeamDialogue(
                () -> sendExplanationLine(lines, index + 1),
                DIALOGUE_DELAY_TICKS
            );
        }

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

        private void sendSpeakerLine(
            List<Player> recipients,
            Component message
        ) {
            Component line = speakerLine(message);
            for (Player recipient : recipients) {
                recipient.sendMessage(line);
            }
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

            // continue: stay in the level's dimension world; otherwise: back to the main lobby
            Location spawn = wantsToContinue
                ? getCheckpoint()
                : world.getSpawnLocation();
            if (spawn == null) {
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

            for (Player player : players) {
                if (player.isDead()) {
                    pendingRespawns.put(player.getUniqueId(), spawn);
                } else if (
                    player.teleport(spawn) &&
                    !wantsToContinue
                ) {
                    player.removeScoreboardTag("playingPVE");
                }
            }
            teamDialogueActive = false;
        }
    }
}
