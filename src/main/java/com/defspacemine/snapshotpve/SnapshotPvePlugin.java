package com.defspacemine.snapshotpve;

import com.defspacemine.snapshotpvp.lobby.LobbyManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class SnapshotPvePlugin extends JavaPlugin {

    public static SnapshotPvePlugin instance;

    public static SnapshotPvePlugin getInstance() {
        return Objects.requireNonNull(
            instance,
            "SnapshotPvePlugin is not enabled"
        );
    }

    @Override
    public void onLoad() {}

    @Override
    public void onEnable() {
        instance = this;
        Objects.requireNonNull(
            getCommand("pvecheckpoint"),
            "pvecheckpoint command is missing from the plugin descriptor"
        ).setExecutor((sender, command, label, args) -> {
            if (args.length == 2 && sender instanceof Player player) {
                return PveDialogue.handleChoice(player, args[0], args[1]);
            }
            return sendCheckpointPrompt(sender, args);
        });
        Objects.requireNonNull(
            getCommand("pveprologuedialogue"),
            "pvePrologueDialogue command is missing from the plugin descriptor"
        ).setExecutor((sender, command, label, args) -> {
            return sendDialogue(sender, args, false);
        });
        Objects.requireNonNull(
            getCommand("pvedefdialogue"),
            "pveDefDialogue command is missing from the plugin descriptor"
        ).setExecutor((sender, command, label, args) -> {
            return sendDialogue(sender, args, true);
        });
        Objects.requireNonNull(
            getCommand("setpvelevel"),
            "setPveLevel command is missing from the plugin descriptor"
        ).setExecutor((sender, command, label, args) ->
            setPveLevel(sender, args)
        );
        Objects.requireNonNull(
            getCommand("startpvelevel"),
            "startPveLevel command is missing from the plugin descriptor"
        ).setExecutor((sender, command, label, args) ->
            startPveLevel(sender, args)
        );
        LobbyManager.registeredLobbies.add(new PveLobbyType());
    }

    private boolean setPveLevel(CommandSender sender, String[] args) {
        if (!sender.hasPermission("snapshotpve.setlevel")) {
            sender.sendMessage(
                Component.text(
                    "You do not have permission to change the PVE level.",
                    NamedTextColor.RED
                )
            );
            return true;
        }
        if (args.length < 1 || args.length > 2) {
            sender.sendMessage("Usage: /setPveLevel <level> [pveWorld]");
            sender.sendMessage("Available levels: " + availableLevels());
            return true;
        }

        PveLobbyType.CurrentLevel level;
        try {
            level = PveLobbyType.CurrentLevel.fromNumber(
                Integer.parseInt(args[0])
            );
        } catch (NumberFormatException exception) {
            level = null;
        }
        if (level == null) {
            sender.sendMessage("Level must be one of: " + availableLevels());
            return true;
        }

        World targetWorld;
        if (args.length == 2) {
            targetWorld = Bukkit.getWorld(args[1]);
        } else if (sender instanceof Player player) {
            targetWorld = player.getWorld();
        } else {
            sender.sendMessage(
                "Specify a loaded PVE world when running this command from console."
            );
            return true;
        }
        if (targetWorld == null) {
            sender.sendMessage("That world is not loaded.");
            return true;
        }
        PveLobbyType.PveLobbyInstance lobby =
            PveLobbyType.PveLobbyInstance.forWorld(targetWorld);
        if (lobby == null) {
            sender.sendMessage(
                "That world is not associated with an active PVE lobby."
            );
            return true;
        }

        // removes the lobby's current level map, then remembers the new level
        lobby.switchLevel(level);
        sender.sendMessage(
            Component.text(
                "PVE level for " +
                    lobby.getWorld().getName() +
                    " is now " +
                    level.levelNumber +
                    ".",
                NamedTextColor.GREEN
            )
        );
        return true;
    }

    private boolean startPveLevel(CommandSender sender, String[] args) {
        if (!sender.hasPermission("snapshotpve.startlevel")) {
            sender.sendMessage(
                Component.text(
                    "You do not have permission to start the PVE level.",
                    NamedTextColor.RED
                )
            );
            return true;
        }
        if (args.length > 1) {
            sender.sendMessage("Usage: /startPveLevel [pveWorld]");
            return true;
        }

        World targetWorld;
        if (args.length == 1) {
            targetWorld = Bukkit.getWorld(args[0]);
        } else if (sender instanceof Player player) {
            targetWorld = player.getWorld();
        } else {
            sender.sendMessage(
                "Specify a loaded PVE world when running this command from console."
            );
            return true;
        }
        if (targetWorld == null) {
            sender.sendMessage("That world is not loaded.");
            return true;
        }
        PveLobbyType.PveLobbyInstance lobby =
            PveLobbyType.PveLobbyInstance.forWorld(targetWorld);
        if (lobby == null) {
            sender.sendMessage(
                "That world is not associated with an active PVE lobby."
            );
            return true;
        }

        if (lobby.startLevel()) {
            sender.sendMessage(
                Component.text(
                    "Started PVE level " +
                        lobby.currentLevel.levelNumber +
                        " in " +
                        lobby.getWorld().getName() +
                        ".",
                    NamedTextColor.GREEN
                )
            );
        } else {
            sender.sendMessage(
                Component.text(
                    "Could not start the PVE level. It may already be running, or a required world is not loaded.",
                    NamedTextColor.RED
                )
            );
        }
        return true;
    }

    private static String availableLevels() {
        return Arrays.stream(PveLobbyType.CurrentLevel.values())
            .map(level -> Integer.toString(level.levelNumber))
            .collect(Collectors.joining(", "));
    }

    /**
     * The PVE players a dialogue command should reach: the players of the lobby the sender
     * is in (command blocks, command minecarts and players), or every PVE player online when
     * the sender isn't inside a lobby (e.g. console).
     */
    private List<Player> getDialogueTargets(CommandSender sender) {
        World senderWorld = null;
        if (sender instanceof BlockCommandSender block) {
            senderWorld = block.getBlock().getWorld();
        } else if (sender instanceof Entity entity) {
            senderWorld = entity.getWorld();
        }
        PveLobbyType.PveLobbyInstance lobby =
            senderWorld == null
                ? null
                : PveLobbyType.PveLobbyInstance.forWorld(senderWorld);
        Collection<? extends Player> candidates =
            lobby == null ? Bukkit.getOnlinePlayers() : lobby.getPlayers();

        List<Player> targets = new ArrayList<>();
        for (Player player : candidates) {
            if (PveDialogue.hasPveTag(player)) {
                targets.add(player);
            }
        }
        return targets;
    }

    private boolean sendDialogue(
        CommandSender sender,
        String[] args,
        boolean includeDefSpaceMine
    ) {
        String commandName = includeDefSpaceMine
            ? "pveDefDialogue"
            : "pvePrologueDialogue";
        if (args.length < 6) {
            sendDialogueSyntaxError(
                sender,
                commandName,
                "Missing required arguments."
            );
            return true;
        }

        long delayTicks;
        try {
            delayTicks = Long.parseLong(args[0]);
            if (delayTicks < 0) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException exception) {
            sender.sendMessage("Delay must be a non-negative number of ticks.");
            return true;
        }

        NamedTextColor color = NamedTextColor.NAMES.value(
            args[1].toLowerCase(java.util.Locale.ROOT)
        );
        if (color == null) {
            sender.sendMessage(
                "Use a named color, such as RED, GOLD, or GREEN."
            );
            return true;
        }

        Sound sound;
        try {
            sound = parseSound(args[2]);
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(exception.getMessage());
            return true;
        }

        float volume;
        float pitch;
        try {
            volume = parseSoundSetting(args[3], "volume", true);
            pitch = parseSoundSetting(args[4], "pitch", false);
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(exception.getMessage());
            return true;
        }

        int appendIndex = includeDefSpaceMine
            ? -1
            : findMarker(args, 5, "append=");
        String dialogue = String.join(
            " ",
            java.util.Arrays.copyOfRange(
                args,
                5,
                appendIndex < 0 ? args.length : appendIndex
            )
        );
        NamedTextColor appendedColor = null;
        TextDecoration appendedStyle = null;
        String appendedDialogue = null;
        if (appendIndex >= 0) {
            if (appendIndex + 3 >= args.length) {
                sendDialogueSyntaxError(
                    sender,
                    commandName,
                    "append= requires a color, style, and text."
                );
                return true;
            }
            appendedColor = NamedTextColor.NAMES.value(
                args[appendIndex + 1].toLowerCase(java.util.Locale.ROOT)
            );
            if (appendedColor == null) {
                sendDialogueSyntaxError(
                    sender,
                    commandName,
                    "Invalid append color; use a named color such as RED, GOLD, or GREEN."
                );
                return true;
            }
            try {
                appendedStyle = parseStyle(args[appendIndex + 2]);
            } catch (IllegalArgumentException exception) {
                sendDialogueSyntaxError(
                    sender,
                    commandName,
                    "Invalid append style. " + exception.getMessage()
                );
                return true;
            }
            appendedDialogue = String.join(
                " ",
                java.util.Arrays.copyOfRange(args, appendIndex + 3, args.length)
            );
            if (appendedDialogue.isBlank()) {
                sendDialogueSyntaxError(
                    sender,
                    commandName,
                    "append= text cannot be empty."
                );
                return true;
            }
        }
        if (dialogue.isBlank()) {
            sendDialogueSyntaxError(
                sender,
                commandName,
                "Provide message text before append=."
            );
            return true;
        }
        for (Player player : getDialogueTargets(sender)) {
            if (includeDefSpaceMine) {
                PveDialogue.sendDefDelayed(
                    player,
                    color,
                    dialogue,
                    sound,
                    volume,
                    pitch,
                    delayTicks
                );
            } else if (appendedDialogue != null) {
                PveDialogue.sendAppendedDelayed(
                    player,
                    color,
                    dialogue,
                    appendedColor,
                    appendedStyle,
                    appendedDialogue,
                    sound,
                    volume,
                    pitch,
                    delayTicks
                );
            } else {
                PveDialogue.sendDelayed(
                    player,
                    color,
                    null,
                    dialogue,
                    sound,
                    volume,
                    pitch,
                    delayTicks
                );
            }
        }
        return true;
    }

    private void sendDialogueSyntaxError(
        CommandSender sender,
        String commandName,
        String error
    ) {
        sender.sendMessage("Syntax error: " + error);
        sender.sendMessage(
            "Usage: /" +
                commandName +
                " <delayTicks> <color> <sound|NONE> <volume> <pitch> <message...>" +
                (commandName.equals("pvePrologueDialogue")
                    ? " [append=<color> <style|NONE> <text...>]"
                    : "")
        );
    }

    private boolean sendCheckpointPrompt(CommandSender sender, String[] args) {
        if (args.length < 9 || !args[6].regionMatches(true, 0, "yes=", 0, 4)) {
            sender.sendMessage(
                "Usage: /pvecheckpoint <delayTicks> <color> <style|NONE> <sound|NONE> " +
                    "<volume> <pitch> " +
                    "yes=<command...> no=<command...> message=<dialogue...>"
            );
            return true;
        }

        int noIndex = findMarker(args, 7, "no=");
        int messageIndex =
            noIndex < 0 ? -1 : findMarker(args, noIndex + 1, "message=");
        if (noIndex < 0 || messageIndex < 0) {
            sender.sendMessage(
                "Include yes=, no=, and message= sections in that order."
            );
            return true;
        }

        long delayTicks;
        try {
            delayTicks = Long.parseLong(args[0]);
            if (delayTicks < 0) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException exception) {
            sender.sendMessage("Delay must be a non-negative number of ticks.");
            return true;
        }

        NamedTextColor color = NamedTextColor.NAMES.value(
            args[1].toLowerCase(java.util.Locale.ROOT)
        );
        if (color == null) {
            sender.sendMessage(
                "Use a named color, such as RED, GOLD, or GREEN."
            );
            return true;
        }

        TextDecoration style;
        try {
            style = parseStyle(args[2]);
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(exception.getMessage());
            return true;
        }

        Sound sound;
        try {
            sound = parseSound(args[3]);
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(exception.getMessage());
            return true;
        }

        float volume;
        float pitch;
        try {
            volume = parseSoundSetting(args[4], "volume", true);
            pitch = parseSoundSetting(args[5], "pitch", false);
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(exception.getMessage());
            return true;
        }

        String yesCommand = joinSection(args, 6, noIndex, "yes=");
        String noCommand = joinSection(args, noIndex, messageIndex, "no=");
        String dialogue = joinSection(
            args,
            messageIndex,
            args.length,
            "message="
        );
        if (yesCommand.isBlank() || noCommand.isBlank() || dialogue.isBlank()) {
            sender.sendMessage(
                "Provide a command or - for each choice, and non-empty dialogue."
            );
            return true;
        }

        PveDialogue.sendCheckpointPrompt(
            getDialogueTargets(sender),
            color,
            style,
            dialogue,
            sound,
            volume,
            pitch,
            delayTicks,
            yesCommand,
            noCommand
        );
        return true;
    }

    private int findMarker(String[] args, int start, String marker) {
        for (int i = start; i < args.length; i++) {
            if (args[i].regionMatches(true, 0, marker, 0, marker.length())) {
                return i;
            }
        }
        return -1;
    }

    private String joinSection(
        String[] args,
        int start,
        int end,
        String marker
    ) {
        String first = args[start].substring(marker.length());
        String remaining = String.join(
            " ",
            java.util.Arrays.copyOfRange(args, start + 1, end)
        );
        return remaining.isEmpty() ? first : first + " " + remaining;
    }

    private TextDecoration parseStyle(String styleName) {
        return switch (styleName.toLowerCase(java.util.Locale.ROOT)) {
            case "none" -> null;
            case "bold" -> TextDecoration.BOLD;
            case "italic" -> TextDecoration.ITALIC;
            case "underlined" -> TextDecoration.UNDERLINED;
            case "strikethrough" -> TextDecoration.STRIKETHROUGH;
            case "obfuscated" -> TextDecoration.OBFUSCATED;
            default -> throw new IllegalArgumentException(
                "Use a style: NONE, BOLD, ITALIC, UNDERLINED, STRIKETHROUGH, or OBFUSCATED."
            );
        };
    }

    private Sound parseSound(String soundName) {
        if (soundName.equalsIgnoreCase("NONE")) {
            return null;
        }
        String soundKeyText = soundName.toLowerCase(java.util.Locale.ROOT);
        if (!soundKeyText.contains(":")) {
            soundKeyText = soundKeyText.replace('_', '.');
        }
        NamespacedKey soundKey = NamespacedKey.fromString(soundKeyText);
        Sound sound = soundKey == null ? null : Registry.SOUNDS.get(soundKey);
        if (sound == null) {
            throw new IllegalArgumentException(
                "Use a Bukkit sound name, such as UI_BUTTON_CLICK, or NONE."
            );
        }
        return sound;
    }

    private float parseSoundSetting(
        String value,
        String settingName,
        boolean allowZero
    ) {
        try {
            float parsed = Float.parseFloat(value);
            if (
                !Float.isFinite(parsed) ||
                parsed < 0 ||
                (!allowZero && parsed == 0)
            ) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException exception) {
            String minimum = allowZero ? "non-negative" : "greater than zero";
            throw new IllegalArgumentException(
                settingName + " must be a finite number " + minimum + "."
            );
        }
    }

    @Override
    public void onDisable() {
        PveDialogue.clearPendingChoices();
        PveLobbyType.PveLobbyInstance.clearInstances();
        instance = null;
    }
}
