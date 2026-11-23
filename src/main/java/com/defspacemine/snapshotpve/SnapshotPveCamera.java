package com.defspacemine.snapshotpve;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCamera;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

public final class SnapshotPveCamera implements CommandExecutor, Listener {
    static final String ACTIVE_TAG = "snapshotpve_camera_active";
    static final String PVE_CAM_TAG = "pveCam";

    private static final Pattern NAME_PATTERN = Pattern.compile(
        "[a-zA-Z0-9_-]{1,64}"
    );
    private static final long MAX_SEGMENT_TICKS = Integer.MAX_VALUE;
    private static final AtomicInteger NEXT_CAMERA_ENTITY_ID = new AtomicInteger(
        Integer.MAX_VALUE
    );
    private static final int CHUNK_MARGIN = 1;
    private static final long INITIAL_CHUNK_WARMUP_TICKS = 10;

    private final SnapshotPvePlugin plugin;
    private final File camerasFile;
    private final Map<String, CameraDefinition> cameras = new LinkedHashMap<>();
    private final Map<UUID, CameraSession> activeSessions = new LinkedHashMap<>();
    private final Map<UUID, PlayerState> pendingRespawnRestore = new LinkedHashMap<>();

    public SnapshotPveCamera(SnapshotPvePlugin plugin) {
        this.plugin = plugin;
        this.camerasFile = new File(
            plugin.getDataFolder(),
            "snapshotpvecamera.yml"
        );
        loadCameras();
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.removeScoreboardTag(ACTIVE_TAG);
            player.removeScoreboardTag(PVE_CAM_TAG);
        }
    }

    @Override
    public boolean onCommand(
        CommandSender sender,
        Command command,
        String label,
        String[] args
    ) {
        if (!sender.hasPermission("snapshotpve.camera")) {
            sender.sendMessage("You do not have permission to control PVE cameras.");
            return true;
        }
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "create" -> createCamera(sender, args);
                case "list" -> listCameras(sender, args);
                case "remove" -> removeCamera(sender, args);
                case "reset" -> resetCamera(sender, args);
                case "set" -> appendSegment(sender, args);
                case "setpoint" -> setPoint(sender, args);
                case "pointlist" -> listPoints(sender, args);
                case "pointreset" -> resetPoint(sender, args);
                case "pointdeleteall" -> deleteAllPoints(sender, args);
                case "interpolatealter" -> alterInterpolation(sender, args);
                case "changecamtype" -> changeCameraType(sender, args);
                case "play" -> playCamera(sender, args);
                case "stop" -> stopCamera(sender, args);
                default -> sendUsage(sender);
            }
        } catch (IllegalArgumentException exception) {
            sender.sendMessage("PVE camera error: " + exception.getMessage());
        }
        return true;
    }

    private void createCamera(CommandSender sender, String[] args) {
        if (args.length < 2 || args.length > 3) {
            sender.sendMessage(
                "Usage: /pvecam create <name> [polynomial|interpolation]"
            );
            return;
        }

        String name = normalizeName(args[1]);
        if (cameras.containsKey(name)) {
            sender.sendMessage("Camera '" + name + "' already exists.");
            return;
        }

        CameraType type = args.length == 3
            ? CameraType.parse(args[2])
            : CameraType.POLYNOMIAL;
        cameras.put(name, new CameraDefinition(type));
        if (!saveCameras(sender)) {
            cameras.remove(name);
            return;
        }
        sender.sendMessage(
            "Created " +
                type.commandName +
                " PVE camera '" +
                name +
                "'."
        );
    }

    private void listCameras(CommandSender sender, String[] args) {
        if (args.length != 1) {
            sender.sendMessage("Usage: /pvecam list");
            return;
        }
        if (cameras.isEmpty()) {
            sender.sendMessage("No PVE cameras exist.");
            return;
        }

        sender.sendMessage("PVE cameras (" + cameras.size() + "):");
        for (Map.Entry<String, CameraDefinition> entry : cameras.entrySet()) {
            CameraDefinition definition = entry.getValue();
            String pathSummary = definition.type == CameraType.POLYNOMIAL
                ? definition.polynomialSegments.size() + " polynomial segment(s)"
                : definition.interpolationPoints.size() + " interpolation point(s)";
            sender.sendMessage(
                "- " +
                    entry.getKey() +
                    " (" +
                    definition.type.commandName +
                    ", " +
                    pathSummary +
                    ")"
            );
        }
    }

    private void removeCamera(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage("Usage: /pvecam remove <name>");
            return;
        }

        String name = normalizeName(args[1]);
        if (!cameras.containsKey(name)) {
            sender.sendMessage("Unknown camera '" + name + "'.");
            return;
        }

        Map<String, CameraDefinition> previous = new LinkedHashMap<>(cameras);
        cameras.remove(name);
        if (!saveCameras(sender)) {
            cameras.clear();
            cameras.putAll(previous);
            return;
        }

        int stopped = stopCameraSessions(name);
        sender.sendMessage(
            "Removed PVE camera '" +
                name +
                "' and stopped " +
                stopped +
                " active session(s)."
        );
    }

    private void resetCamera(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage("Usage: /pvecam reset <name>");
            return;
        }

        String name = normalizeName(args[1]);
        CameraDefinition existing = cameras.get(name);
        if (existing == null) {
            sender.sendMessage("Unknown camera '" + name + "'.");
            return;
        }
        if (existing.hasNoPath()) {
            sender.sendMessage("Camera '" + name + "' already has no path.");
            return;
        }

        cameras.put(name, new CameraDefinition(existing.type));
        if (!saveCameras(sender)) {
            cameras.put(name, existing);
            return;
        }

        int stopped = stopCameraSessions(name);
        sender.sendMessage(
            "Reset the path for PVE camera '" +
                name +
                "' and stopped " +
                stopped +
                " active session(s)."
        );
    }

    private int stopCameraSessions(String cameraName) {
        int stopped = 0;
        for (CameraSession session : new ArrayList<>(activeSessions.values())) {
            if (session.cameraName.equals(cameraName)) {
                finishSession(session, true, true);
                stopped++;
            }
        }
        return stopped;
    }

    private void appendSegment(CommandSender sender, String[] args) {
        if (args.length != 8) {
            sender.sendMessage(
                "Usage: /pvecam set <name> <ticks> <x> <y> <z> <pitch> <yaw>"
            );
            return;
        }

        String name = normalizeName(args[1]);
        CameraDefinition definition = cameras.get(name);
        if (definition == null) {
            sender.sendMessage("Unknown camera '" + name + "'. Create it first.");
            return;
        }
        if (definition.type != CameraType.POLYNOMIAL) {
            throw new IllegalArgumentException(
                "Use /pvecam setpoint for interpolation cameras."
            );
        }

        long duration;
        try {
            duration = Long.parseLong(args[2]);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                "Segment duration must be a whole number of ticks."
            );
        }
        if (duration < 1 || duration > MAX_SEGMENT_TICKS) {
            throw new IllegalArgumentException(
                "Segment duration must be from 1 to " +
                    MAX_SEGMENT_TICKS +
                    " ticks."
            );
        }

        CameraSegment segment = new CameraSegment(
            duration,
            args[3],
            args[4],
            args[5],
            args[6],
            args[7]
        );
        validateContinuity(definition.polynomialSegments, segment);

        CameraDefinition updated = definition.copy();
        updated.polynomialSegments.add(segment);
        cameras.put(name, updated);
        if (!saveCameras(sender)) {
            cameras.put(name, definition);
            return;
        }
        sender.sendMessage(
            "Added a " + duration + "-tick segment to PVE camera '" + name + "'."
        );
    }

    private void setPoint(CommandSender sender, String[] args) {
        // Preserve the existing setpoint syntax for a camera named "edit".
        if (args.length >= 2 && args[1].equalsIgnoreCase("edit")
            && (args.length != 11 && (args.length != 6
                || !isInterpolationType(args[4])))) {
            editPoint(sender, args);
            return;
        }
        boolean useSenderPosition = args.length == 6;
        boolean hasExplicitPosition = args.length == 11;
        if (!useSenderPosition && !hasExplicitPosition) {
            sender.sendMessage(
                "Usage: /pvecam setpoint <name> <point#> <ticks-to-next> "
                    + "[<x> <y> <z> <pitch> <yaw>] "
                    + "<linear|sinusoidal|exponential> <easing 0-4>"
            );
            return;
        }

        String name = normalizeName(args[1]);
        CameraDefinition definition = requireInterpolationCamera(name);
        int pointNumber = parsePointNumber(args[2]);
        long ticksToNext = parsePointDuration(args[3]);
        int interpolationTypeIndex = useSenderPosition ? 4 : 9;
        CameraPoint location;
        if (useSenderPosition) {
            Location senderLocation = senderOrigin(sender);
            if (senderLocation == null) {
                throw new IllegalArgumentException(
                    "Omitting the position requires a player or command block."
                );
            }
            location = pointFromLocation(senderLocation);
        } else {
            location = parsePointLocation(args, 4);
        }
        CameraInterpolation.Type interpolationType = CameraInterpolation.parseType(
            args[interpolationTypeIndex]
        );
        int easingFactor = parseEasingFactor(
            args[interpolationTypeIndex + 1]
        );
        InterpolationPoint point = new InterpolationPoint(
            location,
            ticksToNext,
            interpolationType,
            easingFactor
        );

        List<InterpolationPoint> updatedPoints = new ArrayList<>(
            definition.interpolationPoints
        );
        if (pointNumber > updatedPoints.size() + 1) {
            throw new IllegalArgumentException(
                "Points must be numbered consecutively starting at 1."
            );
        }
        if (pointNumber == updatedPoints.size() + 1) {
            updatedPoints.add(point);
        } else {
            updatedPoints.set(pointNumber - 1, point);
        }
        validateInterpolationPath(updatedPoints, name);

        CameraDefinition updated = definition.copy();
        updated.interpolationPoints.clear();
        updated.interpolationPoints.addAll(updatedPoints);
        cameras.put(name, updated);
        if (!saveCameras(sender)) {
            cameras.put(name, definition);
            return;
        }
        sender.sendMessage(
            "Saved point " +
                pointNumber +
                " for interpolation camera '" +
                name +
                "'."
        );
    }

    private static boolean isInterpolationType(String value) {
        try {
            CameraInterpolation.parseType(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void editPoint(CommandSender sender, String[] args) {
        if (args.length != 6) {
            sender.sendMessage(
                "Usage: /pvecam setpoint edit <name> <point#> "
                    + "<ticks|x|y|z|pitch|yaw|type|easing> <value>"
            );
            return;
        }

        String name = normalizeName(args[2]);
        CameraDefinition definition = requireInterpolationCamera(name);
        int pointNumber = parsePointNumber(args[3]);
        CameraDefinition updated = definition.copy();
        editInterpolationPoint(
            updated.interpolationPoints, name, pointNumber, args[4], args[5]
        );
        cameras.put(name, updated);
        if (!saveCameras(sender)) {
            cameras.put(name, definition);
            return;
        }
        sender.sendMessage(
            "Updated point " + pointNumber + " for interpolation camera '" + name + "'."
        );
    }

    static void editInterpolationPoint(
        List<InterpolationPoint> points,
        String name,
        int pointNumber,
        String parameter,
        String value
    ) {
        if (pointNumber < 1 || pointNumber > points.size()) {
            throw new IllegalArgumentException(
                "Camera '" + name + "' has no point " + pointNumber + "."
            );
        }
        List<InterpolationPoint> updated = new ArrayList<>(points);
        updated.set(
            pointNumber - 1,
            points.get(pointNumber - 1).withEditedParameter(parameter, value)
        );
        validateInterpolationPath(updated, name);
        points.set(pointNumber - 1, updated.get(pointNumber - 1));
    }

    private void listPoints(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage("Usage: /pvecam pointlist <name>");
            return;
        }

        String name = normalizeName(args[1]);
        CameraDefinition definition = requireInterpolationCamera(name);
        if (definition.interpolationPoints.isEmpty()) {
            sender.sendMessage("Camera '" + name + "' has no interpolation points.");
            return;
        }

        sender.sendMessage(
            "Interpolation points for '" +
                name +
                "' (" +
                definition.interpolationPoints.size() +
                "):"
        );
        for (int i = 0; i < definition.interpolationPoints.size(); i++) {
            InterpolationPoint point = definition.interpolationPoints.get(i);
            String duration = i + 1 < definition.interpolationPoints.size()
                ? point.durationTicks + " ticks to next"
                : "final point; ticks-to-next unused";
            sender.sendMessage(
                (i + 1) +
                    ": x=" +
                    point.location.x +
                    " y=" +
                    point.location.y +
                    " z=" +
                    point.location.z +
                    " pitch=" +
                    point.location.pitch +
                    " yaw=" +
                    point.location.yaw +
                    " " +
                    duration +
                    " interpolation=" +
                    point.interpolationType.name().toLowerCase(Locale.ROOT) +
                    " easing=" +
                    point.easingFactor
            );
        }
    }

    private void resetPoint(CommandSender sender, String[] args) {
        if (args.length != 3) {
            sender.sendMessage("Usage: /pvecam pointreset <name> <point#>");
            return;
        }

        String name = normalizeName(args[1]);
        CameraDefinition definition = requireInterpolationCamera(name);
        int pointNumber = parsePointNumber(args[2]);
        if (pointNumber > definition.interpolationPoints.size()) {
            throw new IllegalArgumentException(
                "Camera '" + name + "' has no point " + pointNumber + "."
            );
        }

        CameraDefinition updated = definition.copy();
        updated.interpolationPoints.remove(pointNumber - 1);
        validateInterpolationPath(updated.interpolationPoints, name);
        cameras.put(name, updated);
        if (!saveCameras(sender)) {
            cameras.put(name, definition);
            return;
        }

        int stopped = stopCameraSessions(name);
        sender.sendMessage(
            "Reset point " +
                pointNumber +
                " for camera '" +
                name +
                "'; later points were renumbered and " +
                stopped +
                " active session(s) were stopped."
        );
    }

    private void deleteAllPoints(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage("Usage: /pvecam pointdeleteall <name>");
            return;
        }

        String name = normalizeName(args[1]);
        CameraDefinition definition = requireInterpolationCamera(name);
        if (definition.interpolationPoints.isEmpty()) {
            sender.sendMessage("Camera '" + name + "' has no points to delete.");
            return;
        }

        CameraDefinition updated = definition.copy();
        updated.interpolationPoints.clear();
        cameras.put(name, updated);
        if (!saveCameras(sender)) {
            cameras.put(name, definition);
            return;
        }

        int stopped = stopCameraSessions(name);
        sender.sendMessage(
            "Deleted all points from camera '" +
                name +
                "' and stopped " +
                stopped +
                " active session(s)."
        );
    }

    private void alterInterpolation(CommandSender sender, String[] args) {
        if (args.length != 4) {
            sender.sendMessage(
                "Usage: /pvecam interpolateAlter <name> <point#> "
                    + "<linear|sinusoidal|exponential>"
            );
            return;
        }

        String name = normalizeName(args[1]);
        CameraDefinition definition = requireInterpolationCamera(name);
        int pointNumber = parsePointNumber(args[2]);
        if (pointNumber > definition.interpolationPoints.size()) {
            throw new IllegalArgumentException(
                "Camera '" + name + "' has no point " + pointNumber + "."
            );
        }
        CameraInterpolation.Type newType = CameraInterpolation.parseType(args[3]);

        CameraDefinition updated = definition.copy();
        InterpolationPoint previous = updated.interpolationPoints.get(
            pointNumber - 1
        );
        updated.interpolationPoints.set(
            pointNumber - 1,
            previous.withInterpolationType(newType)
        );
        cameras.put(name, updated);
        if (!saveCameras(sender)) {
            cameras.put(name, definition);
            return;
        }
        sender.sendMessage(
            "Point " +
                pointNumber +
                " on camera '" +
                name +
                "' now uses " +
                newType.name().toLowerCase(Locale.ROOT) +
                " interpolation."
        );
    }

    private void changeCameraType(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage("Usage: /pvecam changeCamType <name>");
            return;
        }

        String name = normalizeName(args[1]);
        CameraDefinition existing = cameras.get(name);
        if (existing == null) {
            sender.sendMessage("Unknown camera '" + name + "'.");
            return;
        }
        CameraType newType = existing.type == CameraType.POLYNOMIAL
            ? CameraType.INTERPOLATION
            : CameraType.POLYNOMIAL;
        cameras.put(name, new CameraDefinition(newType));
        if (!saveCameras(sender)) {
            cameras.put(name, existing);
            return;
        }

        int stopped = stopCameraSessions(name);
        sender.sendMessage(
            "Changed camera '" +
                name +
                "' to " +
                newType.commandName +
                " and cleared its path; stopped " +
                stopped +
                " active session(s)."
        );
    }

    private CameraDefinition requireInterpolationCamera(String name) {
        CameraDefinition definition = cameras.get(name);
        if (definition == null) {
            throw new IllegalArgumentException(
                "Unknown camera '" + name + "'."
            );
        }
        if (definition.type != CameraType.INTERPOLATION) {
            throw new IllegalArgumentException(
                "Camera '" +
                    name +
                    "' is polynomial; use /pvecam changeCamType to switch its type."
            );
        }
        return definition;
    }

    private static int parsePointNumber(String input) {
        int pointNumber;
        try {
            pointNumber = Integer.parseInt(input);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                "Point number must be a whole number starting at 1."
            );
        }
        if (pointNumber < 1) {
            throw new IllegalArgumentException(
                "Point number must be a whole number starting at 1."
            );
        }
        return pointNumber;
    }

    private static long parsePointDuration(String input) {
        long duration;
        try {
            duration = Long.parseLong(input);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                "Ticks-to-next must be a whole number of ticks."
            );
        }
        if (duration < 0 || duration > MAX_SEGMENT_TICKS) {
            throw new IllegalArgumentException(
                "Ticks-to-next must be from 0 to " +
                    MAX_SEGMENT_TICKS +
                    " ticks."
            );
        }
        return duration;
    }

    private static int parseEasingFactor(String input) {
        int factor;
        try {
            factor = Integer.parseInt(input);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                "Easing factor must be a whole number from 0 to 4."
            );
        }
        if (factor < 0 || factor > 4) {
            throw new IllegalArgumentException(
                "Easing factor must be from 0 to 4."
            );
        }
        return factor;
    }

    private static CameraPoint parsePointLocation(String[] args, int offset) {
        double x = parseFiniteDouble(args[offset], "x");
        double y = parseFiniteDouble(args[offset + 1], "y");
        double z = parseFiniteDouble(args[offset + 2], "z");
        double pitch = parseFiniteDouble(args[offset + 3], "pitch");
        double yaw = parseFiniteDouble(args[offset + 4], "yaw");
        return validatedPointLocation(x, y, z, pitch, yaw);
    }

    private static CameraPoint validatedPointLocation(
        double x, double y, double z, double pitch, double yaw
    ) {
        if (Math.abs(x) > 29_999_984 || Math.abs(z) > 29_999_984) {
            throw new IllegalArgumentException(
                "Camera coordinates must stay within the world border."
            );
        }
        if (pitch < -90 || pitch > 90) {
            throw new IllegalArgumentException(
                "Camera pitch must be between -90 and 90 degrees."
            );
        }
        return new CameraPoint(x, y, z, (float) pitch, wrapYaw(yaw));
    }

    private static CameraPoint pointFromLocation(Location location) {
        return new CameraPoint(
            location.getX(),
            location.getY(),
            location.getZ(),
            location.getPitch(),
            wrapYaw(location.getYaw())
        );
    }

    private static double parseFiniteDouble(String input, String field) {
        double value;
        try {
            value = Double.parseDouble(input);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                field + " must be a finite number."
            );
        }
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(
                field + " must be a finite number."
            );
        }
        return value;
    }

    private static float wrapYaw(double yaw) {
        double wrappedYaw = yaw % 360.0;
        if (wrappedYaw >= 180) {
            wrappedYaw -= 360;
        } else if (wrappedYaw < -180) {
            wrappedYaw += 360;
        }
        return (float) wrappedYaw;
    }

    private void playCamera(CommandSender sender, String[] args) {
        if (args.length != 3) {
            sender.sendMessage(
                "Usage: /pvecam play <name> <player-selector>"
            );
            return;
        }

        String name = normalizeName(args[1]);
        CameraDefinition definition = cameras.get(name);
        if (definition == null) {
            sender.sendMessage("Unknown camera '" + name + "'.");
            return;
        }
        List<MotionSegment> segments = definition.playbackSegments();
        if (segments.isEmpty()) {
            sender.sendMessage("Camera '" + name + "' has no playable path.");
            return;
        }

        List<Player> targets = selectPlayers(sender, args[2]);
        if (targets.isEmpty()) {
            sender.sendMessage("No players matched that camera selector.");
            return;
        }

        int started = 0;
        for (Player target : targets) {
            if (startCamera(target, name, segments)) {
                started++;
            }
        }
        sender.sendMessage(
            "Started camera '" + name + "' for " + started + " player(s)."
        );
    }

    private void stopCamera(CommandSender sender, String[] args) {
        if (args.length > 2) {
            sender.sendMessage("Usage: /pvecam stop [player-selector]");
            return;
        }

        List<Player> targets;
        if (args.length == 2) {
            targets = selectPlayers(sender, args[1]);
        } else if (sender instanceof Player player) {
            targets = List.of(player);
        } else {
            targets = new ArrayList<>();
            for (CameraSession session : activeSessions.values()) {
                targets.add(session.player);
            }
        }

        int stopped = 0;
        for (Player target : targets) {
            CameraSession session = activeSessions.get(target.getUniqueId());
            if (session != null) {
                finishSession(session, true, true);
                stopped++;
            }
        }
        sender.sendMessage("Stopped " + stopped + " PVE camera session(s).");
    }

    private boolean startCamera(
        Player player,
        String cameraName,
        List<MotionSegment> segments
    ) {
        CameraSession previous = activeSessions.get(player.getUniqueId());
        if (previous != null) {
            finishSession(previous, true, true);
        }

        pendingRespawnRestore.remove(player.getUniqueId());
        CameraPoint firstPoint = segments.get(0).evaluate(0);
        PlayerState state = new PlayerState(player);
        CameraSession session = new CameraSession(
            player,
            cameraName,
            segments,
            state,
            NEXT_CAMERA_ENTITY_ID.getAndDecrement()
        );
        activeSessions.put(player.getUniqueId(), session);
        boolean started = false;
        try {
            state.addCameraTag(player);
            player.setGameMode(GameMode.SPECTATOR);
            if (!movePlayerForChunks(session, firstPoint)) {
                finishSession(session, true, false);
                return false;
            }

            sendSpawnCamera(session, firstPoint);
            PacketEvents.getAPI()
                .getPlayerManager()
                .sendPacket(player, new WrapperPlayServerCamera(session.entityId));
            session.cameraSelected = true;
            session.segmentTick = 1;
            session.task = new BukkitRunnable() {
                @Override
                public void run() {
                    advance(session);
                }
            }.runTaskTimer(
                plugin,
                INITIAL_CHUNK_WARMUP_TICKS,
                1L
            );
            started = true;
            return true;
        } finally {
            if (!started && activeSessions.get(player.getUniqueId()) == session) {
                finishSession(session, true, true);
            }
        }
    }

    private void advance(CameraSession session) {
        if (activeSessions.get(session.player.getUniqueId()) != session) {
            return;
        }
        if (!session.player.isOnline()) {
            finishSession(session, false, false);
            return;
        }
        if (!session.player.getWorld().equals(session.world)) {
            finishSession(session, true, true);
            return;
        }
        if (session.segmentIndex >= session.segments.size()) {
            finishSession(session, true, true);
            return;
        }

        MotionSegment segment = session.segments.get(session.segmentIndex);
        CameraPoint point;
        try {
            point = segment.evaluate(session.segmentTick);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().log(
                Level.WARNING,
                "Stopping invalid PVE camera '" +
                    session.cameraName +
                    "' for " +
                    session.player.getName(),
                exception
            );
            session.player.sendMessage(
                "PVE camera stopped because its path produced an invalid position."
            );
            finishSession(session, true, true);
            return;
        }

        if (!movePlayerForChunks(session, point)) {
            finishSession(session, true, true);
            return;
        }
        sendCameraPosition(session, point);

        if (session.segmentTick >= segment.durationTicks()) {
            session.segmentIndex++;
            session.segmentTick = 0;
            if (session.segmentIndex >= session.segments.size()) {
                finishSession(session, true, true);
            }
        } else {
            session.segmentTick++;
        }
    }

    private boolean movePlayerForChunks(
        CameraSession session,
        CameraPoint point
    ) {
        Player player = session.player;
        Location current = player.getLocation();
        int chunkDeltaX = Math.abs(
            current.getChunk().getX() - chunkCoordinate(point.x)
        );
        int chunkDeltaZ = Math.abs(
            current.getChunk().getZ() - chunkCoordinate(point.z)
        );
        if (Math.max(chunkDeltaX, chunkDeltaZ) <= CHUNK_MARGIN) {
            return true;
        }

        Location destination = point.toLocation(session.world);
        destination.setYaw(current.getYaw());
        destination.setPitch(current.getPitch());
        session.stagingPlayer = true;
        boolean teleported;
        try {
            teleported = player.teleport(
                destination,
                PlayerTeleportEvent.TeleportCause.PLUGIN
            );
        } finally {
            session.stagingPlayer = false;
        }
        if (!teleported) {
            plugin.getLogger().warning(
                "Could not move " +
                    player.getName() +
                    " near PVE camera '" +
                    session.cameraName +
                    "' to stream camera chunks."
            );
            player.sendMessage(
                "PVE camera stopped because the server could not stage your player for chunk loading."
            );
            return false;
        }
        return true;
    }

    private void sendSpawnCamera(CameraSession session, CameraPoint point) {
        PacketEvents.getAPI()
            .getPlayerManager()
            .sendPacket(
                session.player,
                new WrapperPlayServerSpawnEntity(
                    session.entityId,
                    Optional.of(session.entityUuid),
                    EntityTypes.MARKER,
                    new Vector3d(point.x, point.y, point.z),
                    point.pitch,
                    point.yaw,
                    point.yaw,
                    0,
                    Optional.of(new Vector3d(0, 0, 0))
                )
            );
        session.cameraSpawned = true;
    }

    private void sendCameraPosition(
        CameraSession session,
        CameraPoint point
    ) {
        PacketEvents.getAPI()
            .getPlayerManager()
            .sendPacket(
                session.player,
                new WrapperPlayServerEntityTeleport(
                    session.entityId,
                    new Vector3d(point.x, point.y, point.z),
                    point.yaw,
                    point.pitch,
                    true
                )
            );
    }

    private void finishSession(
        CameraSession session,
        boolean restorePlayer,
        boolean sendPackets
    ) {
        if (session.finished) {
            return;
        }
        session.finished = true;
        activeSessions.remove(session.player.getUniqueId(), session);
        if (session.task != null) {
            session.task.cancel();
        }

        Player player = session.player;
        if (!player.isOnline()) {
            return;
        }

        try {
            if (sendPackets && session.cameraSelected) {
                PacketEvents.getAPI()
                    .getPlayerManager()
                    .sendPacket(
                        player,
                        new WrapperPlayServerCamera(player.getEntityId())
                    );
            }
        } finally {
            try {
                if (sendPackets && session.cameraSpawned) {
                    PacketEvents.getAPI()
                        .getPlayerManager()
                        .sendPacket(
                            player,
                            new WrapperPlayServerDestroyEntities(session.entityId)
                        );
                }
            } finally {
                if (restorePlayer) {
                    session.playerState.restore(player);
                } else {
                    session.playerState.removeCameraTag(player);
                }
            }
        }
    }

    private void loadCameras() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            throw new IllegalStateException(
                "Could not create the plugin data folder for PVE cameras."
            );
        }
        if (!camerasFile.exists()) {
            try {
                if (!camerasFile.createNewFile()) {
                    throw new IOException("Could not create camera data file.");
                }
            } catch (IOException exception) {
                throw new IllegalStateException(
                    "Could not create " + camerasFile.getName() + ".",
                    exception
                );
            }
        }

        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.load(camerasFile);
        } catch (IOException | InvalidConfigurationException exception) {
            throw new IllegalStateException(
                "Could not load " + camerasFile.getName() + ".",
                exception
            );
        }

        ConfigurationSection cameraSection = configuration.getConfigurationSection(
            "cameras"
        );
        if (cameraSection == null) {
            return;
        }
        for (String rawName : cameraSection.getKeys(false)) {
            String name = normalizeName(rawName);
            ConfigurationSection section = cameraSection.getConfigurationSection(
                rawName
            );
            if (section == null) {
                throw new IllegalStateException(
                    "Invalid camera definition '" + name + "'."
                );
            }
            CameraType type;
            try {
                type = CameraType.parse(
                    section.getString("type", CameraType.POLYNOMIAL.commandName)
                );
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException(
                    "Invalid camera type for '" + name + "'.",
                    exception
                );
            }

            CameraDefinition definition = new CameraDefinition(type);
            if (type == CameraType.POLYNOMIAL) {
                List<?> storedSegments = section.getList("segments", List.of());
                for (Object storedSegment : storedSegments) {
                    if (!(storedSegment instanceof Map<?, ?> segmentMap)) {
                        throw new IllegalStateException(
                            "Invalid segment in camera '" + name + "'."
                        );
                    }
                    definition.polynomialSegments.add(
                        CameraSegment.fromMap(segmentMap, name)
                    );
                }
                validateSequence(definition.polynomialSegments, name);
            } else {
                List<?> storedPoints = section.getList("points", List.of());
                for (Object storedPoint : storedPoints) {
                    if (!(storedPoint instanceof Map<?, ?> pointMap)) {
                        throw new IllegalStateException(
                            "Invalid point in camera '" + name + "'."
                        );
                    }
                    definition.interpolationPoints.add(
                        InterpolationPoint.fromMap(pointMap, name)
                    );
                }
                validateInterpolationPath(
                    definition.interpolationPoints,
                    name
                );
            }
            cameras.put(name, definition);
        }
    }

    private boolean saveCameras(CommandSender sender) {
        YamlConfiguration configuration = new YamlConfiguration();
        ConfigurationSection cameraSection = configuration.createSection(
            "cameras"
        );
        for (Map.Entry<String, CameraDefinition> entry : cameras.entrySet()) {
            ConfigurationSection section = cameraSection.createSection(
                entry.getKey()
            );
            CameraDefinition definition = entry.getValue();
            section.set("type", definition.type.commandName);
            if (definition.type == CameraType.POLYNOMIAL) {
                List<Map<String, Object>> storedSegments = new ArrayList<>();
                for (CameraSegment segment : definition.polynomialSegments) {
                    storedSegments.add(segment.toMap());
                }
                section.set("segments", storedSegments);
            } else {
                List<Map<String, Object>> storedPoints = new ArrayList<>();
                for (InterpolationPoint point : definition.interpolationPoints) {
                    storedPoints.add(point.toMap());
                }
                section.set("points", storedPoints);
            }
        }

        try {
            configuration.save(camerasFile);
            return true;
        } catch (IOException exception) {
            plugin.getLogger().log(
                Level.SEVERE,
                "Could not save " + camerasFile.getName() + ".",
                exception
            );
            sender.sendMessage(
                "Could not save camera data; the previous definition was kept."
            );
            return false;
        }
    }

    private void validateContinuity(
        List<CameraSegment> existing,
        CameraSegment added
    ) {
        if (existing.isEmpty()) {
            return;
        }

        CameraPoint previousEnd = existing
            .get(existing.size() - 1)
            .evaluate(existing.get(existing.size() - 1).durationTicks);
        CameraPoint nextStart = added.evaluate(0);
        if (
            Math.abs(previousEnd.x - nextStart.x) > 0.01 ||
            Math.abs(previousEnd.y - nextStart.y) > 0.01 ||
            Math.abs(previousEnd.z - nextStart.z) > 0.01 ||
            Math.abs(previousEnd.pitch - nextStart.pitch) > 0.1 ||
            Math.abs(angleDifference(previousEnd.yaw, nextStart.yaw)) > 0.1
        ) {
            throw new IllegalArgumentException(
                "The new segment must start where the previous segment ends."
            );
        }
    }

    private void validateSequence(List<CameraSegment> segments, String name) {
        for (int i = 1; i < segments.size(); i++) {
            validateContinuity(segments.subList(0, i), segments.get(i));
        }
        if (name.isBlank()) {
            throw new IllegalStateException("Camera names cannot be blank.");
        }
    }

    private static void validateInterpolationPath(
        List<InterpolationPoint> points,
        String name
    ) {
        for (int i = 0; i < points.size() - 1; i++) {
            long duration = points.get(i).durationTicks;
            if (duration < 1 || duration > MAX_SEGMENT_TICKS) {
                throw new IllegalArgumentException(
                    "Point " +
                        (i + 1) +
                        " in camera '" +
                        name +
                        "' must have 1 to " +
                        MAX_SEGMENT_TICKS +
                        " ticks-to-next."
                );
            }
        }
    }

    private List<Player> selectPlayers(
        CommandSender sender,
        String selectorText
    ) {
        PlayerSelector selector = PlayerSelector.parse(selectorText);
        Location origin = senderOrigin(sender);
        if ((selector.base.equals("@p") || selector.distance != null) &&
            origin == null) {
            throw new IllegalArgumentException(
                "@p and distance= require a player or command-block execution position."
            );
        }

        PveLobbyType.PveLobbyInstance senderLobby = null;
        if (Boolean.TRUE.equals(selector.inPveLobby)) {
            if (origin == null || origin.getWorld() == null) {
                throw new IllegalArgumentException(
                    "inPVELobby=true requires a player or command block in a PVE lobby world."
                );
            }
            senderLobby = PveLobbyType.PveLobbyInstance.forWorld(origin.getWorld());
            if (senderLobby == null) {
                throw new IllegalArgumentException(
                    "inPVELobby=true requires execution inside the target PVE lobby."
                );
            }
        }

        List<Player> matches = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (matches(player, selector, origin, senderLobby)) {
                matches.add(player);
            }
        }

        if (selector.base.equals("@a")) {
            return matches;
        }
        if (selector.base.equals("@r")) {
            if (matches.isEmpty()) {
                return matches;
            }
            return List.of(
                matches.get(ThreadLocalRandom.current().nextInt(matches.size()))
            );
        }

        if (origin == null) {
            throw new IllegalArgumentException(
                "@p requires a player or command-block execution position."
            );
        }
        return matches
            .stream()
            .filter(player -> player.getWorld().equals(origin.getWorld()))
            .min((first, second) ->
                Double.compare(
                    first.getLocation().distanceSquared(origin),
                    second.getLocation().distanceSquared(origin)
                )
            )
            .map(List::of)
            .orElseGet(List::of);
    }

    private boolean matches(
        Player player,
        PlayerSelector selector,
        Location origin,
        PveLobbyType.PveLobbyInstance senderLobby
    ) {
        Set<String> tags = player.getScoreboardTags();
        for (TagFilter tag : selector.tags) {
            if (tags.contains(tag.value) == tag.negated) {
                return false;
            }
        }

        if (selector.dimension != null) {
            boolean dimensionMatches = matchesDimension(
                player.getWorld(),
                selector.dimension.value
            );
            if (
                selector.dimension.negated
                    ? dimensionMatches
                    : !dimensionMatches
            ) {
                return false;
            }
        }
        if (
            selector.gameMode != null &&
            (player.getGameMode() == selector.gameMode) ==
                selector.gameModeNegated
        ) {
            return false;
        }
        if (selector.distance != null) {
            if (!player.getWorld().equals(origin.getWorld())) {
                return false;
            }
            double distance = player.getLocation().distance(origin);
            if (!selector.distance.contains(distance)) {
                return false;
            }
        }
        if (selector.inPveLobby != null) {
            PveLobbyType.PveLobbyInstance playerLobby =
                PveLobbyType.PveLobbyInstance.forWorld(player.getWorld());
            boolean matchesLobby = selector.inPveLobby
                ? playerLobby != null && playerLobby == senderLobby
                : playerLobby == null;
            if (!matchesLobby) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesDimension(World world, String requested) {
        String worldName = world.getName().toLowerCase(Locale.ROOT);
        String key = world.getKey().toString().toLowerCase(Locale.ROOT);
        return
            requested.equals(worldName) ||
            requested.equals(key) ||
            (requested.equals("overworld") ||
                requested.equals("minecraft:overworld")) &&
                world.getEnvironment() == World.Environment.NORMAL ||
            (requested.equals("nether") ||
                requested.equals("the_nether") ||
                requested.equals("minecraft:the_nether")) &&
                world.getEnvironment() == World.Environment.NETHER ||
            (requested.equals("the_end") ||
                requested.equals("minecraft:the_end")) &&
                world.getEnvironment() == World.Environment.THE_END;
    }

    private Location senderOrigin(CommandSender sender) {
        if (sender instanceof Player player) {
            return player.getLocation();
        }
        if (sender instanceof BlockCommandSender blockSender) {
            return blockSender.getBlock().getLocation().add(0.5, 0.5, 0.5);
        }
        return null;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(
            "Usage: /pvecam create <name> [polynomial|interpolation] | list "
                + "| remove <name> | reset <name> | set <name> <ticks> "
                + "<x> <y> <z> <pitch> <yaw> | setpoint <name> <point#> "
                + "<ticks-to-next> <x> <y> <z> <pitch> <yaw> <type> <easing> "
                + "| setpoint edit <name> <point#> <ticks|x|y|z|pitch|yaw|type|easing> <value> "
                + "| pointlist <name> | pointreset <name> <point#> "
                + "| pointdeleteall <name> | interpolateAlter <name> <point#> <type> "
                + "| changeCamType <name> | play <name> <selector> | stop [selector]"
        );
    }

    private static String normalizeName(String value) {
        if (!NAME_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                "Camera names may contain only letters, numbers, underscores, and hyphens."
            );
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static int chunkCoordinate(double coordinate) {
        return ((int) Math.floor(coordinate)) >> 4;
    }

    private static double angleDifference(float first, float second) {
        double difference = (second - first) % 360.0;
        if (difference >= 180.0) {
            difference -= 360.0;
        } else if (difference < -180.0) {
            difference += 360.0;
        }
        return difference;
    }

    public void stopAll() {
        for (CameraSession session : new ArrayList<>(activeSessions.values())) {
            finishSession(session, true, true);
        }
        pendingRespawnRestore.clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent) {
            return;
        }
        CameraSession session = activeSessions.get(
            event.getPlayer().getUniqueId()
        );
        Location destination = event.getTo();
        if (
            session == null ||
            session.stagingPlayer ||
            destination == null
        ) {
            return;
        }
        Location origin = event.getFrom();
        if (
            !origin.getWorld().equals(destination.getWorld()) ||
            origin.getX() != destination.getX() ||
            origin.getY() != destination.getY() ||
            origin.getZ() != destination.getZ()
        ) {
            event.setTo(origin);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        CameraSession session = activeSessions.get(
            event.getPlayer().getUniqueId()
        );
        if (session == null || session.stagingPlayer) {
            return;
        }
        event.setCancelled(true);
        if (!session.teleportWarningSent) {
            event.getPlayer().sendMessage(
                "You cannot teleport while a PVE camera is active."
            );
            session.teleportWarningSent = true;
        }
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        CameraSession session = activeSessions.get(
            event.getPlayer().getUniqueId()
        );
        if (session != null) {
            finishSession(session, true, true);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        removeDisconnectedPlayer(event.getPlayer());
    }

    @EventHandler
    public void onPlayerKick(PlayerKickEvent event) {
        removeDisconnectedPlayer(event.getPlayer());
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        CameraSession session = activeSessions.get(player.getUniqueId());
        if (session != null) {
            pendingRespawnRestore.put(player.getUniqueId(), session.playerState);
            finishSession(session, false, true);
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        PlayerState state = pendingRespawnRestore.remove(
            event.getPlayer().getUniqueId()
        );
        if (state == null) {
            return;
        }
        event.setRespawnLocation(state.originalLocation);
        Bukkit.getScheduler().runTask(plugin, () -> state.restore(event.getPlayer()));
    }

    private void removeDisconnectedPlayer(Player player) {
        CameraSession session = activeSessions.get(player.getUniqueId());
        if (session != null) {
            finishSession(session, false, false);
        }
        pendingRespawnRestore.remove(player.getUniqueId());
        player.removeScoreboardTag(ACTIVE_TAG);
        player.removeScoreboardTag(PVE_CAM_TAG);
    }

    private enum CameraType {
        POLYNOMIAL("polynomial"),
        INTERPOLATION("interpolation");

        private final String commandName;

        CameraType(String commandName) {
            this.commandName = commandName;
        }

        static CameraType parse(String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "polynomial" -> POLYNOMIAL;
                case "interpolation" -> INTERPOLATION;
                default -> throw new IllegalArgumentException(
                    "Camera type must be polynomial or interpolation."
                );
            };
        }
    }

    private static final class CameraDefinition {
        private final CameraType type;
        private final List<CameraSegment> polynomialSegments;
        private final List<InterpolationPoint> interpolationPoints;

        CameraDefinition(CameraType type) {
            this(type, new ArrayList<>(), new ArrayList<>());
        }

        private CameraDefinition(
            CameraType type,
            List<CameraSegment> polynomialSegments,
            List<InterpolationPoint> interpolationPoints
        ) {
            this.type = type;
            this.polynomialSegments = polynomialSegments;
            this.interpolationPoints = interpolationPoints;
        }

        CameraDefinition copy() {
            return new CameraDefinition(
                type,
                new ArrayList<>(polynomialSegments),
                new ArrayList<>(interpolationPoints)
            );
        }

        boolean hasNoPath() {
            return type == CameraType.POLYNOMIAL
                ? polynomialSegments.isEmpty()
                : interpolationPoints.isEmpty();
        }

        List<MotionSegment> playbackSegments() {
            if (type == CameraType.POLYNOMIAL) {
                return new ArrayList<>(polynomialSegments);
            }

            List<MotionSegment> segments = new ArrayList<>();
            for (int i = 0; i + 1 < interpolationPoints.size(); i++) {
                segments.add(
                    new InterpolationSegment(
                        interpolationPoints.get(i),
                        interpolationPoints.get(i + 1)
                    )
                );
            }
            return segments;
        }
    }

    private interface MotionSegment {
        long durationTicks();

        CameraPoint evaluate(double ticks);
    }

    static final class InterpolationPoint {
        private final CameraPoint location;
        private final long durationTicks;
        private final CameraInterpolation.Type interpolationType;
        private final int easingFactor;

        InterpolationPoint(
            CameraPoint location,
            long durationTicks,
            CameraInterpolation.Type interpolationType,
            int easingFactor
        ) {
            if (durationTicks < 0 || durationTicks > MAX_SEGMENT_TICKS) {
                throw new IllegalArgumentException(
                    "Ticks-to-next must be from 0 to " +
                        MAX_SEGMENT_TICKS +
                        " ticks."
                );
            }
            if (
                !Double.isFinite(location.x) ||
                !Double.isFinite(location.y) ||
                !Double.isFinite(location.z) ||
                !Float.isFinite(location.pitch) ||
                !Float.isFinite(location.yaw) ||
                Math.abs(location.x) > 29_999_984 ||
                Math.abs(location.z) > 29_999_984
            ) {
                throw new IllegalArgumentException(
                    "Point coordinates and rotation must be finite and inside the world border."
                );
            }
            if (location.pitch < -90 || location.pitch > 90) {
                throw new IllegalArgumentException(
                    "Camera pitch must be between -90 and 90 degrees."
                );
            }
            if (easingFactor < 0 || easingFactor > 4) {
                throw new IllegalArgumentException(
                    "Easing factor must be from 0 to 4."
                );
            }
            this.location = location;
            this.durationTicks = durationTicks;
            this.interpolationType = interpolationType;
            this.easingFactor = easingFactor;
        }

        InterpolationPoint withInterpolationType(
            CameraInterpolation.Type newType
        ) {
            return new InterpolationPoint(
                location,
                durationTicks,
                newType,
                easingFactor
            );
        }

        InterpolationPoint withEditedParameter(String parameter, String value) {
            double x = location.x;
            double y = location.y;
            double z = location.z;
            double pitch = location.pitch;
            double yaw = location.yaw;
            long ticks = durationTicks;
            CameraInterpolation.Type type = interpolationType;
            int easing = easingFactor;
            switch (parameter.toLowerCase(Locale.ROOT)) {
                case "ticks" -> ticks = parsePointDuration(value);
                case "x" -> x = parseFiniteDouble(value, "x");
                case "y" -> y = parseFiniteDouble(value, "y");
                case "z" -> z = parseFiniteDouble(value, "z");
                case "pitch" -> pitch = parseFiniteDouble(value, "pitch");
                case "yaw" -> yaw = parseFiniteDouble(value, "yaw");
                case "type" -> type = CameraInterpolation.parseType(value);
                case "easing" -> easing = parseEasingFactor(value);
                default -> throw new IllegalArgumentException(
                    "Parameter must be ticks, x, y, z, pitch, yaw, type, or easing."
                );
            }
            return new InterpolationPoint(
                validatedPointLocation(x, y, z, pitch, yaw), ticks, type, easing
            );
        }

        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("x", location.x);
            values.put("y", location.y);
            values.put("z", location.z);
            values.put("pitch", location.pitch);
            values.put("yaw", location.yaw);
            values.put("ticksToNext", durationTicks);
            values.put(
                "interpolation",
                interpolationType.name().toLowerCase(Locale.ROOT)
            );
            values.put("easingFactor", easingFactor);
            return values;
        }

        static InterpolationPoint fromMap(
            Map<?, ?> values,
            String cameraName
        ) {
            Object ticksValue = values.get("ticksToNext");
            if (!(ticksValue instanceof Number ticks)) {
                throw new IllegalStateException(
                    "Missing ticks-to-next in camera '" + cameraName + "'."
                );
            }
            Object typeValue = values.get("interpolation");
            if (!(typeValue instanceof String interpolation)) {
                throw new IllegalStateException(
                    "Missing interpolation type in camera '" + cameraName + "'."
                );
            }
            Object easingValue = values.get("easingFactor");
            if (!(easingValue instanceof Number easing)) {
                throw new IllegalStateException(
                    "Missing easing factor in camera '" + cameraName + "'."
                );
            }
            double easingNumber = easing.doubleValue();
            int easingFactor = easing.intValue();
            if (easingNumber != easingFactor) {
                throw new IllegalStateException(
                    "Invalid easing factor in camera '" + cameraName + "'."
                );
            }

            return new InterpolationPoint(
                new CameraPoint(
                    requireCoordinate(values, "x", cameraName),
                    requireCoordinate(values, "y", cameraName),
                    requireCoordinate(values, "z", cameraName),
                    (float) requireCoordinate(values, "pitch", cameraName),
                    wrapYaw(requireCoordinate(values, "yaw", cameraName))
                ),
                ticks.longValue(),
                CameraInterpolation.parseType(interpolation),
                easingFactor
            );
        }

        private static double requireCoordinate(
            Map<?, ?> values,
            String key,
            String cameraName
        ) {
            Object value = values.get(key);
            if (!(value instanceof Number number)) {
                throw new IllegalStateException(
                    "Missing " + key + " coordinate in camera '" + cameraName + "'."
                );
            }
            double coordinate = number.doubleValue();
            if (!Double.isFinite(coordinate)) {
                throw new IllegalStateException(
                    "Invalid " + key + " coordinate in camera '" + cameraName + "'."
                );
            }
            return coordinate;
        }
    }

    private static final class InterpolationSegment implements MotionSegment {
        private final InterpolationPoint source;
        private final InterpolationPoint destination;

        InterpolationSegment(
            InterpolationPoint source,
            InterpolationPoint destination
        ) {
            this.source = source;
            this.destination = destination;
        }

        @Override
        public long durationTicks() {
            return source.durationTicks;
        }

        @Override
        public CameraPoint evaluate(double ticks) {
            double progress = Math.max(
                0,
                Math.min(1, ticks / source.durationTicks)
            );
            double easedProgress = CameraInterpolation.apply(
                source.interpolationType,
                source.easingFactor,
                progress
            );
            CameraPoint from = source.location;
            CameraPoint to = destination.location;
            return new CameraPoint(
                from.x + (to.x - from.x) * easedProgress,
                from.y + (to.y - from.y) * easedProgress,
                from.z + (to.z - from.z) * easedProgress,
                (float) (from.pitch + (to.pitch - from.pitch) * easedProgress),
                wrapYaw(
                    from.yaw +
                        angleDifference(from.yaw, to.yaw) * easedProgress
                )
            );
        }
    }

    private final class CameraSession {
        private final Player player;
        private final String cameraName;
        private final List<MotionSegment> segments;
        private final PlayerState playerState;
        private final int entityId;
        private final UUID entityUuid = UUID.randomUUID();
        private final World world;
        private int segmentIndex;
        private long segmentTick;
        private boolean stagingPlayer;
        private boolean cameraSpawned;
        private boolean cameraSelected;
        private boolean finished;
        private boolean teleportWarningSent;
        private BukkitTask task;

        CameraSession(
            Player player,
            String cameraName,
            List<MotionSegment> segments,
            PlayerState playerState,
            int entityId
        ) {
            this.player = player;
            this.cameraName = cameraName;
            this.segments = segments;
            this.playerState = playerState;
            this.entityId = entityId;
            this.world = player.getWorld();
        }
    }

    private static final class PlayerState {
        private final Location originalLocation;
        private final GameMode gameMode;
        private final Vector velocity;
        private final boolean allowFlight;
        private final boolean flying;
        private final float fallDistance;
        private boolean addedCameraTag;
        private boolean addedPveCamTag;

        PlayerState(Player player) {
            this.originalLocation = player.getLocation().clone();
            this.gameMode = player.getGameMode();
            this.velocity = player.getVelocity().clone();
            this.allowFlight = player.getAllowFlight();
            this.flying = player.isFlying();
            this.fallDistance = player.getFallDistance();
        }

        void addCameraTag(Player player) {
            addedCameraTag = player.addScoreboardTag(ACTIVE_TAG);
            addedPveCamTag = player.addScoreboardTag(PVE_CAM_TAG);
        }

        void removeCameraTag(Player player) {
            if (addedCameraTag) {
                player.removeScoreboardTag(ACTIVE_TAG);
                addedCameraTag = false;
            }
            if (addedPveCamTag) {
                player.removeScoreboardTag(PVE_CAM_TAG);
                addedPveCamTag = false;
            }
        }

        void restore(Player player) {
            try {
                if (
                    !player.getWorld().equals(originalLocation.getWorld()) ||
                    player.getLocation().distanceSquared(originalLocation) > 0.0001
                ) {
                    if (
                        !player.teleport(
                            originalLocation,
                            PlayerTeleportEvent.TeleportCause.PLUGIN
                        )
                    ) {
                        SnapshotPvePlugin.getInstance()
                            .getLogger()
                            .warning(
                                "Could not return " +
                                    player.getName() +
                                    " to their saved position after a PVE camera."
                            );
                        player.sendMessage(
                            "Could not return you to your saved position after the PVE camera."
                        );
                    }
                }
            } finally {
                try {
                    player.setGameMode(gameMode);
                    player.setAllowFlight(allowFlight);
                    if (flying && allowFlight) {
                        player.setFlying(true);
                    } else if (player.isFlying()) {
                        player.setFlying(false);
                    }
                    player.setFallDistance(fallDistance);
                    player.setVelocity(velocity);
                } finally {
                    removeCameraTag(player);
                }
            }
        }
    }

    private static final class CameraSegment implements MotionSegment {
        private final long durationTicks;
        private final String xEquation;
        private final String yEquation;
        private final String zEquation;
        private final String pitchEquation;
        private final String yawEquation;
        private final CameraPolynomial x;
        private final CameraPolynomial y;
        private final CameraPolynomial z;
        private final CameraPolynomial pitch;
        private final CameraPolynomial yaw;

        CameraSegment(
            long durationTicks,
            String xEquation,
            String yEquation,
            String zEquation,
            String pitchEquation,
            String yawEquation
        ) {
            if (
                durationTicks < 1 ||
                durationTicks > MAX_SEGMENT_TICKS
            ) {
                throw new IllegalArgumentException(
                    "Segment duration must be from 1 to " +
                        MAX_SEGMENT_TICKS +
                        " ticks."
                );
            }
            this.durationTicks = durationTicks;
            this.xEquation = xEquation;
            this.yEquation = yEquation;
            this.zEquation = zEquation;
            this.pitchEquation = pitchEquation;
            this.yawEquation = yawEquation;
            this.x = CameraPolynomial.parse(xEquation);
            this.y = CameraPolynomial.parse(yEquation);
            this.z = CameraPolynomial.parse(zEquation);
            this.pitch = CameraPolynomial.parse(pitchEquation);
            this.yaw = CameraPolynomial.parse(yawEquation);
            evaluate(0);
            evaluate(durationTicks);
        }

        static CameraSegment fromMap(Map<?, ?> values, String cameraName) {
            Object ticksValue = values.get("ticks");
            if (!(ticksValue instanceof Number ticks)) {
                throw new IllegalStateException(
                    "Missing tick duration in camera '" + cameraName + "'."
                );
            }
            return new CameraSegment(
                ticks.longValue(),
                requireEquation(values, "x", cameraName),
                requireEquation(values, "y", cameraName),
                requireEquation(values, "z", cameraName),
                requireEquation(values, "pitch", cameraName),
                requireEquation(values, "yaw", cameraName)
            );
        }

        private static String requireEquation(
            Map<?, ?> values,
            String key,
            String cameraName
        ) {
            Object value = values.get(key);
            if (!(value instanceof String equation)) {
                throw new IllegalStateException(
                    "Missing " + key + " equation in camera '" + cameraName + "'."
                );
            }
            return equation;
        }

        @Override
        public CameraPoint evaluate(double ticks) {
            double xValue = x.evaluate(ticks);
            double yValue = y.evaluate(ticks);
            double zValue = z.evaluate(ticks);
            double pitchValue = pitch.evaluate(ticks);
            double yawValue = yaw.evaluate(ticks);
            if (
                Math.abs(xValue) > 29_999_984 ||
                Math.abs(zValue) > 29_999_984
            ) {
                throw new IllegalArgumentException(
                    "Camera coordinates must stay within the world border."
                );
            }

            if (pitchValue < -90 || pitchValue > 90) {
                throw new IllegalArgumentException(
                    "Camera pitch must stay between -90 and 90 degrees."
                );
            }
            float wrappedYaw = (float) (yawValue % 360.0);
            if (wrappedYaw >= 180) {
                wrappedYaw -= 360;
            } else if (wrappedYaw < -180) {
                wrappedYaw += 360;
            }
            return new CameraPoint(
                xValue,
                yValue,
                zValue,
                (float) pitchValue,
                wrappedYaw
            );
        }

        @Override
        public long durationTicks() {
            return durationTicks;
        }

        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("ticks", durationTicks);
            values.put("x", xEquation);
            values.put("y", yEquation);
            values.put("z", zEquation);
            values.put("pitch", pitchEquation);
            values.put("yaw", yawEquation);
            return values;
        }
    }

    static final class CameraPoint {
        private final double x;
        private final double y;
        private final double z;
        private final float pitch;
        private final float yaw;

        CameraPoint(double x, double y, double z, float pitch, float yaw) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.pitch = pitch;
            this.yaw = yaw;
        }

        Location toLocation(World world) {
            return new Location(world, x, y, z, yaw, pitch);
        }
    }

    private static final class PlayerSelector {
        private final String base;
        private final List<TagFilter> tags = new ArrayList<>();
        private DimensionFilter dimension;
        private GameMode gameMode;
        private boolean gameModeNegated;
        private DistanceRange distance;
        private Boolean inPveLobby;

        private PlayerSelector(String base) {
            this.base = base;
        }

        static PlayerSelector parse(String input) {
            int bracketStart = input.indexOf('[');
            String base = (
                bracketStart >= 0
                    ? input.substring(0, bracketStart)
                    : input
            ).toLowerCase(Locale.ROOT);
            if (!base.equals("@a") && !base.equals("@p") && !base.equals("@r")) {
                throw new IllegalArgumentException(
                    "Player selectors must be @a, @p, or @r."
                );
            }

            PlayerSelector selector = new PlayerSelector(base);
            if (bracketStart < 0) {
                if (input.indexOf(']') >= 0) {
                    throw invalidSelector();
                }
                return selector;
            }
            if (!input.endsWith("]") || input.indexOf('[', bracketStart + 1) >= 0) {
                throw invalidSelector();
            }

            String options = input.substring(bracketStart + 1, input.length() - 1);
            if (options.isBlank()) {
                throw invalidSelector();
            }
            for (String option : options.split(",", -1)) {
                int equals = option.indexOf('=');
                if (equals <= 0 || equals == option.length() - 1) {
                    throw invalidSelector();
                }
                String key = option.substring(0, equals).toLowerCase(Locale.ROOT);
                String value = option.substring(equals + 1);
                selector.parseOption(key, value);
            }
            return selector;
        }

        private void parseOption(String key, String rawValue) {
            switch (key) {
                case "tag" -> {
                    boolean negated = rawValue.startsWith("!");
                    String value = negated ? rawValue.substring(1) : rawValue;
                    if (value.isBlank()) {
                        throw invalidSelector();
                    }
                    tags.add(new TagFilter(value, negated));
                }
                case "distance" -> {
                    if (distance != null) {
                        throw invalidSelector();
                    }
                    distance = DistanceRange.parse(rawValue);
                }
                case "dimension" -> {
                    if (dimension != null) {
                        throw invalidSelector();
                    }
                    boolean negated = rawValue.startsWith("!");
                    String value = negated ? rawValue.substring(1) : rawValue;
                    if (value.isBlank()) {
                        throw invalidSelector();
                    }
                    dimension = new DimensionFilter(
                        value.toLowerCase(Locale.ROOT),
                        negated
                    );
                }
                case "gamemode" -> {
                    if (gameMode != null) {
                        throw invalidSelector();
                    }
                    boolean negated = rawValue.startsWith("!");
                    String value = negated ? rawValue.substring(1) : rawValue;
                    try {
                        gameMode = GameMode.valueOf(
                            value.toUpperCase(Locale.ROOT)
                        );
                    } catch (IllegalArgumentException exception) {
                        throw new IllegalArgumentException(
                            "gamemode must be survival, creative, adventure, or spectator."
                        );
                    }
                    gameModeNegated = negated;
                }
                case "inpvelobby" -> {
                    if (inPveLobby != null) {
                        throw invalidSelector();
                    }
                    if (rawValue.equalsIgnoreCase("true")) {
                        inPveLobby = true;
                    } else if (rawValue.equalsIgnoreCase("false")) {
                        inPveLobby = false;
                    } else {
                        throw new IllegalArgumentException(
                            "inPVELobby must be true or false."
                        );
                    }
                }
                default -> throw new IllegalArgumentException(
                    "Unsupported selector option '" + key + "'."
                );
            }
        }

        private static IllegalArgumentException invalidSelector() {
            return new IllegalArgumentException(
                "Use @a, @p, or @r with tag=, distance=, dimension=, gamemode=, "
                    + "or inPVELobby= filters."
            );
        }
    }

    private static final class TagFilter {
        private final String value;
        private final boolean negated;

        TagFilter(String value, boolean negated) {
            this.value = value;
            this.negated = negated;
        }
    }

    private static final class DimensionFilter {
        private final String value;
        private final boolean negated;

        DimensionFilter(String value, boolean negated) {
            this.value = value;
            this.negated = negated;
        }
    }

    private static final class DistanceRange {
        private final double minimum;
        private final double maximum;

        private DistanceRange(double minimum, double maximum) {
            this.minimum = minimum;
            this.maximum = maximum;
        }

        static DistanceRange parse(String value) {
            int rangeSeparator = value.indexOf("..");
            try {
                if (rangeSeparator < 0) {
                    double exact = parseDistance(value);
                    return new DistanceRange(exact, exact);
                }
                if (value.indexOf("..", rangeSeparator + 2) >= 0) {
                    throw new NumberFormatException();
                }
                String start = value.substring(0, rangeSeparator);
                String end = value.substring(rangeSeparator + 2);
                double minimum = start.isEmpty() ? 0 : parseDistance(start);
                double maximum = end.isEmpty()
                    ? Double.POSITIVE_INFINITY
                    : parseDistance(end);
                if (minimum > maximum) {
                    throw new NumberFormatException();
                }
                return new DistanceRange(minimum, maximum);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(
                    "distance must be a non-negative number or range such as ..32 or 8..32."
                );
            }
        }

        private static double parseDistance(String value) {
            double distance = Double.parseDouble(value);
            if (!Double.isFinite(distance) || distance < 0) {
                throw new NumberFormatException();
            }
            return distance;
        }

        boolean contains(double distance) {
            return distance >= minimum && distance <= maximum;
        }
    }
}
