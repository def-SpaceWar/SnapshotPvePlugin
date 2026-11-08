package com.defspacemine.snapshotpve;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

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

import com.defspacemine.snapshotpvp.SnapshotPvpPlugin;
import com.defspacemine.snapshotpvp.lobby.LobbyType;
import com.defspacemine.snapshotpvp.scoreboard.GlobalScoreboardManager;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public class PveLobbyType extends LobbyType {

    private static final long DIALOGUE_DELAY_TICKS = 90L;
    private static final Map<String, PveLobbyInstance> INSTANCES = new HashMap<>();

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

    public class PveLobbyInstance
            extends LobbyType.LobbyInstance
            implements Listener {

        private final World world;
        private final Integer levelNumber;
        private int level;
        private final String checkpointWorldName;
        private final String levelWorldName;
        private World checkpointWorld;
        private World levelWorld;
        private final Map<UUID, Location> pendingRespawns = new HashMap<>();
        private boolean teamDialogueActive;
        private boolean destroyed;
        LobbyState lobbyState;
        boolean gameOver = false; // if the game ended bc someone died, then this should be true

        public PveLobbyInstance(World world) {
            this.world = world;
            levelNumber = getLevelNumber(world.getName());
            level = loadLevel(levelNumber);
            levelWorldName = levelNumber == null ? null : "pvelvl" + levelNumber;
            checkpointWorldName = levelNumber == null
                    ? null
                    : world.getName() + "_lvl" + levelNumber;
            checkpointWorld = checkpointWorldName == null
                    ? null
                    : Bukkit.getWorld(checkpointWorldName);
            levelWorld = levelWorldName == null ? null : Bukkit.getWorld(levelWorldName);
            lobbyState = LobbyState.WAITING;

            SnapshotPvpPlugin.pluginManager.registerEvents(
                    this,
                    SnapshotPvpPlugin.instance
            );

            INSTANCES.put(world.getName(), this);
            if (levelNumber == null) {
                return;
            }

            registerScoreboardHandler(world.getName());
            if (checkpointWorld != null) {
                registerScoreboardHandler(checkpointWorld.getName());
            }
            if (levelWorld != null) {
                registerScoreboardHandler(levelWorld.getName());
            }
        }

        private void registerScoreboardHandler(String worldName) {
            GlobalScoreboardManager.registerBoardHandler(worldName, this::scoreboardHandler);
        }

        private int loadLevel(Integer defaultLevel) {
            if (defaultLevel == null) {
                return 0;
            }
            String path = "pve-levels." + world.getName();
            var config = SnapshotPvePlugin.getInstance().getConfig();
            if (!config.contains(path)) {
                return defaultLevel;
            }
            if (config.isInt(path)) {
                int configuredLevel = config.getInt(path);
                if (configuredLevel >= 1 && configuredLevel <= 20) {
                    return configuredLevel;
                }
            }
            SnapshotPvePlugin.getInstance().getLogger().warning(
                    "Ignoring invalid PVE level at " + path + "; expected an integer from 1 to 20."
            );
            return defaultLevel;
        }

        private void setLevel(int newLevel) {
            level = newLevel;
            SnapshotPvePlugin plugin = SnapshotPvePlugin.getInstance();
            plugin.getConfig().set("pve-levels." + world.getName(), newLevel);
            plugin.saveConfig();
        }

        private boolean managesWorld(World candidate) {
            String candidateName = candidate.getName();
            return world.getName().equals(candidateName)
                    || candidateName.equals(checkpointWorldName)
                    || candidateName.equals(levelWorldName);
        }

        public static boolean setPveLevel(World targetWorld, int newLevel) {
            for (PveLobbyInstance instance : INSTANCES.values()) {
                if (instance.managesWorld(targetWorld)) {
                    instance.setLevel(newLevel);
                    return true;
                }
            }
            return false;
        }

        public static void clearInstances() {
            INSTANCES.clear();
        }

        private Integer getLevelNumber(String lobbyWorldName) {
            String lobbyPrefix = "pvelobby";
            if (!lobbyWorldName.matches("pvelobby\\d+")) {
                return null;
            }
            try {
                int lobbyNumber = Integer.parseInt(lobbyWorldName.substring(lobbyPrefix.length()));
                return lobbyNumber < 0 ? null : Math.addExact(lobbyNumber, 1);
            } catch (NumberFormatException | ArithmeticException exception) {
                return null;
            }
        }

        @EventHandler
        public void onWorldLoad(WorldLoadEvent event) {
            if (levelNumber == null) {
                return;
            }
            World loadedWorld = event.getWorld();
            if (loadedWorld.getName().equals(world.getName())) {
                registerScoreboardHandler(loadedWorld.getName());
            } else if (loadedWorld.getName().equals(checkpointWorldName)) {
                checkpointWorld = loadedWorld;
                registerScoreboardHandler(loadedWorld.getName());
            } else if (loadedWorld.getName().equals(levelWorldName)) {
                levelWorld = loadedWorld;
                registerScoreboardHandler(loadedWorld.getName());
            }
        }

        @EventHandler
        public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
            if (levelNumber == null) {
                return;
            }
            Player player = event.getPlayer();
            String destinationName = player.getWorld().getName();
            if (isPveParticipant(player)
                    && (destinationName.equals(world.getName())
                    || destinationName.equals(checkpointWorldName)
                    || destinationName.equals(levelWorldName))) {
                scoreboardHandler(player);
            }
        }

        @Override
        public World getWorld() {
            return world;
        }

        @Override
        public List<Player> getPlayers() {
            // obviously it should just check who's in the game world, but i'm just getting this somewhat setup
            List<Player> players = new ArrayList<>(getLobbyPlayers().stream()
                    .filter(this::isPveParticipant)
                    .toList());

            if (!gameOver) {
                return players;
            }

            for (Player p : players) {
                if (isPveParticipant(p)) {
                    p.teleport(new Location(world, 0.5, 65, 0.5));
                }
            }
            return null;
        }

        private boolean isPveParticipant(Player player) {
            if (player.getScoreboardTags().contains("playingPVE")
                    || player.getScoreboardTags().contains("spectatingPVE")) {
                return true;
            }
            return isOnTeam(player, "survivor") || isOnTeam(player, "spectator");
        }

        private boolean isOnTeam(Player player, String teamName) {
            var team = SnapshotPvpPlugin.getTeam(player);
            return team != null && team.getName().equals(teamName);
        }

        private List<Player> getLobbyPlayers() {
            List<Player> players = new ArrayList<>(world.getPlayers());
            if (checkpointWorld != null) {
                players.addAll(checkpointWorld.getPlayers());
            }
            if (levelWorld != null) {
                players.addAll(levelWorld.getPlayers());
            }
            return players;
        }

        private void scoreboardHandler(Player viewer) {
            if (!isPveParticipant(viewer)) {
                return;
            }
            if (gameOver) {
                viewer.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
                return;
            }

            List<Player> players = new ArrayList<>(getLobbyPlayers().stream()
                    .filter(this::isPveParticipant)
                    .toList());
            long survivorCount = players.stream()
                    .filter(player -> player.getScoreboardTags().contains("playingPVE"))
                    .count();
            Scoreboard board = GlobalScoreboardManager.makeNewScoreboard(viewer, players);
            GlobalScoreboardManager.setupScoreboardHP(board, players);
            Objective objective = GlobalScoreboardManager.initializeCombatList(board);
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

            players.sort((first, second) -> first.getName().compareToIgnoreCase(second.getName()));
            for (Player player : players) {
                if (!isPveParticipant(player)) {
                    continue;
                }
                boolean playing = player.getScoreboardTags().contains("playingPVE");
                if (playing) {
                    player.removeScoreboardTag("spectatingPVE");
                }
                String status = playing
                        ? ChatColor.DARK_GREEN.toString() + ChatColor.BOLD + "[SURVIVOR]"
                        : gradientBoldText("[SPECTATOR]");
                String leftSide = status + ChatColor.RESET + " " + player.getName() + ":";

                double health = player.getHealth();
                ChatColor healthColor = health >= 15
                        ? ChatColor.GREEN
                        : health >= 5 ? ChatColor.YELLOW : ChatColor.RED;
                String rightSide = healthColor.toString()
                        + (int) Math.ceil(health)
                        + "❤";
                double absorption = player.getAbsorptionAmount();
                if (absorption > 0) {
                    rightSide += ChatColor.YELLOW.toString()
                            + " +"
                            + (int) Math.ceil(absorption)
                            + "❤";
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
                float progress = text.length() == 1
                        ? 0
                        : (float) i / (text.length() - 1);
                NamedTextColor color;
                if (progress < 0.4F) {
                    color = NamedTextColor.DARK_GREEN;
                } else if (progress < 0.7F) {
                    color = NamedTextColor.GREEN;
                } else {
                    color = NamedTextColor.WHITE;
                }
                gradient = gradient.append(
                        Component.text(text.charAt(i), color).decorate(TextDecoration.BOLD)
                );
            }

            return LegacyComponentSerializer.legacySection().serialize(gradient);
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
            if (!isPveParticipant(deadPlayer)
                    || levelWorldName == null
                    || !deadPlayer.getWorld().getName().equals(levelWorldName)) {
                return;
            }

            if (checkpointWorld == null) {
                SnapshotPvePlugin.getInstance().getLogger().severe(
                        "Cannot respawn player from " + levelWorldName
                                + ": its checkpoint world is not loaded."
                );
                return;
            }
            Location checkpoint = new Location(checkpointWorld, 0, 66, 0);
            pendingRespawns.put(deadPlayer.getUniqueId(), checkpoint);
            if (teamDialogueActive) {
                return;
            }
            teamDialogueActive = true;

            Bukkit.getScheduler().runTaskLater(SnapshotPvpPlugin.instance, () -> {
                if (destroyed) {
                    return;
                }

                World deathWorld = Bukkit.getWorld(levelWorldName);
                if (deathWorld != null) {
                    for (Entity entity : deathWorld.getEntities()) {
                        var team = SnapshotPvpPlugin.getTeamE(entity);
                        if (team != null && team.getName().equals("hunter")) {
                            entity.remove();
                        }
                    }
                }

                List<Player> players = getTeamPlayers();
                if (players.isEmpty()) {
                    teamDialogueActive = false;
                    return;
                }
                for (Player player : players) {
                    if (player != deadPlayer) {
                        player.teleport(checkpoint);
                    }
                }

                checkpointTellraw();
            }, 2L);
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onPlayerRespawn(PlayerRespawnEvent event) {
            Player player = event.getPlayer();
            Location destination = pendingRespawns.remove(player.getUniqueId());
            if (destination != null) {
                event.setRespawnLocation(destination);
                Bukkit.getScheduler().runTaskLater(SnapshotPvpPlugin.instance, () -> {
                    if (player.isOnline() && isPveParticipant(player)) {
                        if (player.teleport(destination)
                                && "pvelobby0".equals(destination.getWorld().getName())) {
                            player.removeScoreboardTag("playingPVE");
                        }
                    }
                }, 2L);
            }
        }

    @Override
        public void onDestroy() {
            destroyed = true;
            teamDialogueActive = false;
            for (Player player : getLobbyPlayers()) {
                PveDialogue.clearPendingChoice(player);
                pendingRespawns.remove(player.getUniqueId());
            }
            INSTANCES.remove(world.getName(), this);
            if (levelNumber != null) {
                GlobalScoreboardManager.removeBoardHandler(world.getName());
                if (checkpointWorldName != null) {
                    GlobalScoreboardManager.removeBoardHandler(checkpointWorldName);
                }
                if (levelWorldName != null) {
                    GlobalScoreboardManager.removeBoardHandler(levelWorldName);
                }
            }
            HandlerList.unregisterAll(this);

            SnapshotPvpPlugin.server.sendMessage(
                    Component.text("No more pve lobby. ;( " + world.getName())
            );
        }

        private List<Player> getTeamPlayers() {
            return getLobbyPlayers().stream()
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
            String deathMessage = players.size() == 1
                    ? "You have died."
                    : "One of you has died.";
            sendSpeakerLine(players, Component.text(deathMessage, NamedTextColor.RED));

            scheduleTeamDialogue(() ->
                    offerChoices("Have you died before?", (clicker, hasDiedBefore) -> {
                        if (hasDiedBefore) {
                            askToContinue();
                        } else {
                            startExplanation();
                        }
                    }), DIALOGUE_DELAY_TICKS);
        }

        private void scheduleTeamDialogue(Runnable dialogue, long delay) {
            Bukkit.getScheduler().runTaskLater(SnapshotPvePlugin.getInstance(), () -> {
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
            }, delay);
        }

        private void askToContinue() {
            offerChoices("Would you like to continue?", (clicker, wantsToContinue) ->
                    resolveContinueChoice(wantsToContinue)
            );
        }

        private void startExplanation() {
            List<Component> lines = List.of(
                    Component.text("For most of those in the world, death is the end of everything.", NamedTextColor.RED),
                    Component.text("However, for those who have been blessed with power by ", NamedTextColor.RED)
                            .append(Component.text("defSpaceMine", NamedTextColor.RED).decorate(TextDecoration.OBFUSCATED))
                            .append(Component.text(", death is NOT the end.", NamedTextColor.RED)),
                    Component.text("The world, upon your creation, deems you worthy or unworthy.", NamedTextColor.RED),
                    Component.text("In situations in which the enemies that have come to face you have been conquered, the world will always deem you worthy.", NamedTextColor.RED),
                    Component.text("However, in times of uncertainty or defeat, the world seeks to restore the itself to the last point at which it has considered you worthy.", NamedTextColor.RED),
                    Component.text("In doing so, you may return anew and prepared to face the enemies that had once bested you.", NamedTextColor.RED)
            );
            sendExplanationLine(lines, 0);
        }

        private void sendExplanationLine(List<Component> lines, int index) {
            List<Player> players = getTeamPlayers();
            if (destroyed || !teamDialogueActive || players.isEmpty()) {
                return;
            }
            if (index >= lines.size()) {
                scheduleTeamDialogue(() ->
                        offerChoices("With that knowledge, would you like to be given another chance?",
                                (clicker, wantsAnotherChance) -> resolveContinueChoice(wantsAnotherChance)
                        ), DIALOGUE_DELAY_TICKS);
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
                Component choice = PveDialogue.choicePrompt(prompt, players, callback);
                for (Player player : players) {
                    player.sendMessage(choice);
                }
            }
        }

        private Component speakerLine(Component message) {
            return Component.empty()
                    .append(Component.text("<", NamedTextColor.RED))
                    .append(Component.text("defSpaceMine", NamedTextColor.RED)
                            .decorate(TextDecoration.OBFUSCATED))
                    .append(Component.text("> ", NamedTextColor.RED))
                    .append(message);
        }

        private void sendSpeakerLine(List<Player> recipients, Component message) {
            Component line = speakerLine(message);
            for (Player recipient : recipients) {
                recipient.sendMessage(line);
            }
            for (Player player : recipients) {
                if (player.getScoreboardTags().contains("playingPVE")
                        || player.getScoreboardTags().contains("spectatingPVE")) {
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
            sendSpeakerLine(players, Component.text("I understand.", NamedTextColor.RED));
            if (wantsToContinue) {
                String destinationName = getReturnWorldName();
                if (destinationName == null) {
                    for (Player player : players) {
                        player.sendMessage(Component.text(
                                "No return destination is configured for level " + level
                                        + ". Supported levels are 1-20.",
                                NamedTextColor.RED
                        ));
                    }
                    teamDialogueActive = false;
                    return;
                }

                World destination = Bukkit.getWorld(destinationName);
                if (destination == null) {
                    for (Player player : players) {
                        player.sendMessage(Component.text(
                                "The return world " + destinationName + " is not loaded.",
                                NamedTextColor.RED
                        ));
                    }
                    teamDialogueActive = false;
                    return;
                }

                Location spawn = destination.getSpawnLocation();
                for (Player player : players) {
                    if (player.isDead()) {
                        pendingRespawns.put(player.getUniqueId(), spawn);
                    } else {
                        player.teleport(spawn);
                    }
                }
                teamDialogueActive = false;
                return;
            }

            World lobbyWorld = Bukkit.getWorld("pvelobby0");
            if (lobbyWorld == null) {
                for (Player player : players) {
                    player.sendMessage(Component.text(
                            "The lobby world pvelobby0 is not loaded.",
                            NamedTextColor.RED
                    ));
                }
                teamDialogueActive = false;
                return;
            }

            Location spawn = lobbyWorld.getSpawnLocation();
            for (Player player : players) {
                if (player.isDead()) {
                    pendingRespawns.put(player.getUniqueId(), spawn);
                } else if (player.teleport(spawn)) {
                    player.removeScoreboardTag("playingPVE");
                }
            }
            teamDialogueActive = false;
        }

        private String getReturnWorldName() {
            if (level >= 1 && level <= 10) {
                return "pvelobby0_overworld";
            }
            if (level >= 11 && level <= 15) {
                return "pvelobby0_nether";
            }
            if (level >= 16 && level <= 20) {
                return "pvelobby0_end";
            }
            return null;
        }
    }

}
