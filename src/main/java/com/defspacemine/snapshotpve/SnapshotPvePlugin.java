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

// Main lifecycle and command wiring for the PVE plugin
public class SnapshotPvePlugin extends JavaPlugin {

    // Handy shared handle for helpers that need the plugin instance
    public static SnapshotPvePlugin instance;

    // Fail loudly when a helper runs before plugin startup
    public static SnapshotPvePlugin getInstance() {
        return Objects.requireNonNull(
            instance,
            "SnapshotPvePlugin is not enabled"
        );
    }

    // No extra work needed during Bukkit's load phase
    @Override
    public void onLoad() {}

    // Hook up commands, the villager listener, and the PVE lobby type
    @Override
    public void onEnable() {
        instance = this;
        // Checkpoint click-throughs and new prompts share one command
        Objects.requireNonNull(
            getCommand("pvecheckpoint"),
            "pvecheckpoint command is missing from the plugin descriptor"
        ).setExecutor((sender, command, label, args) -> {
            if (args.length == 2 && sender instanceof Player player) {
                return PveDialogue.handleChoice(player, args[0], args[1]);
            }
            return sendCheckpointPrompt(sender, args);
        });
        // Prologue line command, including optional appended text
        Objects.requireNonNull(
            getCommand("pveprologuedialogue"),
            "pvePrologueDialogue command is missing from the plugin descriptor"
        ).setExecutor((sender, command, label, args) -> {
            return sendDialogue(sender, args, false);
        });
        // DefSpaceMine-prefixed dialogue command
        Objects.requireNonNull(
            getCommand("pvedefdialogue"),
            "pveDefDialogue command is missing from the plugin descriptor"
        ).setExecutor((sender, command, label, args) -> {
            return sendDialogue(sender, args, true);
        });
        // Administrative level and PvP controls
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
        Objects.requireNonNull(
            getCommand("setpvepvp"),
            "setPvePvp command is missing from the plugin descriptor"
        ).setExecutor((sender, command, label, args) ->
            setPvePvp(sender, args)
        );
        // Track summoned entity dialogue and listen for spawns and right-clicks
        PvePrologue prologue = new PvePrologue();
        getServer().getPluginManager().registerEvents(prologue, this);
        Objects.requireNonNull(
            getCommand("pvesummon"),
            "pvesummon command is missing from the plugin descriptor"
        ).setExecutor((sender, command, label, args) ->
            prologue.summon(sender, args)
        );
        LobbyManager.registeredLobbies.add(new PveLobbyType());
    }

    // Toggle PvP across all loaded PVE dimensions
    private boolean setPvePvp(CommandSender sender, String[] args) {
        if (!sender.hasPermission("snapshotpve.setpvp")) {
            sender.sendMessage(
                Component.text(
                    "You do not have permission to change PVE PvP.",
                    NamedTextColor.RED
                )
            );
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage("Usage: /setpvepvp <true|false>");
            return true;
        }

        boolean enabled;
        // Parse strictly so typos don't accidentally flip the setting
        if (args[0].equalsIgnoreCase("true")) {
            enabled = true;
        } else if (args[0].equalsIgnoreCase("false")) {
            enabled = false;
        } else {
            sender.sendMessage("PvP must be true or false.");
            return true;
        }

        // Return the number of touched dimensions for useful feedback
        int updatedWorlds = PveLobbyType.PveLobbyInstance.setPvpForAllLobbies(
            enabled
        );
        if (updatedWorlds == 0) {
            sender.sendMessage(
                Component.text(
                    "No loaded PVE lobby dimension worlds were found.",
                    NamedTextColor.RED
                )
            );
            return true;
        }

        sender.sendMessage(
            Component.text(
                "PvP is now " +
                    (enabled ? "enabled" : "disabled") +
                    " in " +
                    updatedWorlds +
                    " loaded PVE dimension world(s).",
                NamedTextColor.GREEN
            )
        );
        return true;
    }

    // Change the saved level selection for an active lobby
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
        // Convert the numeric argument to one of the configured levels
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

        // Use the named world when supplied, otherwise the player's current world
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

        // Unload the old map, then save the selected level
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

    // Place the selected level map into its lobby dimension
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

        // Console needs an explicit world; players can use their current one
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

    // List configured level numbers for command feedback
    private static String availableLevels() {
        return Arrays.stream(PveLobbyType.CurrentLevel.values())
            .map(level -> Integer.toString(level.levelNumber))
            .collect(Collectors.joining(", "));
    }

    /** PVE players in the sender's lobby, or online PVE players for console */
    private List<Player> getDialogueTargets(CommandSender sender) {
        World senderWorld = null;
        // Command blocks and minecarts carry a world; console does not
        switch (sender) {
            case BlockCommandSender block -> senderWorld = block
                .getBlock()
                .getWorld();
            case Entity entity -> senderWorld = entity.getWorld();
            default -> {
            }
        }
        PveLobbyType.PveLobbyInstance lobby =
            senderWorld == null
                ? null
                : PveLobbyType.PveLobbyInstance.forWorld(senderWorld);
        Collection<? extends Player> candidates =
            lobby == null ? Bukkit.getOnlinePlayers() : lobby.getPlayers();

        // Keep only active PVE participants from that audience
        List<Player> targets = new ArrayList<>();
        for (Player player : candidates) {
            if (PveDialogue.hasPveTag(player)) {
                targets.add(player);
            }
        }
        return targets;
    }

    // Parse and schedule one of the colored dialogue commands
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

        // Delay is in server ticks, and can't be negative
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

        // Restrict colors to Adventure's named colors
        NamedTextColor color = NamedTextColor.NAMES.value(
            args[1].toLowerCase(java.util.Locale.ROOT)
        );
        if (color == null) {
            sender.sendMessage(
                "Use a named color, such as RED, GOLD, or GREEN."
            );
            return true;
        }

        // Sound and volume/pitch are optional-ish but still validated up front
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

        int messageEnd = args.length;
        String targetSelector = null;
        // Pull the selector off the end so it doesn't become dialogue text
        if (
            !includeDefSpaceMine &&
            messageEnd > 5 &&
            args[messageEnd - 1].regionMatches(true, 0, "target=", 0, 7)
        ) {
            targetSelector = args[messageEnd - 1].substring(7);
            if (!targetSelector.startsWith("@")) {
                sendDialogueSyntaxError(
                    sender,
                    commandName,
                    "target= must be a Minecraft selector such as @a, @p, @r, or @e."
                );
                return true;
            }
            messageEnd--;
        }
        int appendIndex = includeDefSpaceMine
            ? -1
            : findMarker(args, 5, messageEnd, "append=");
        String dialogue = String.join(
            " ",
            java.util.Arrays.copyOfRange(
                args,
                5,
                appendIndex < 0 ? messageEnd : appendIndex
            )
        );
        NamedTextColor appendedColor = null;
        TextDecoration appendedStyle = null;
        String appendedDialogue = null;
        if (appendIndex >= 0) {
            // Appended text has its own color/style pair
            if (appendIndex + 3 >= messageEnd) {
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
                java.util.Arrays.copyOfRange(args, appendIndex + 3, messageEnd)
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
        // Default recipients follow the sender's PVE lobby
        Collection<? extends Player> targets =
            targetSelector == null
                ? getDialogueTargets(sender)
                : selectPlayerTargets(sender, targetSelector);
        if (targets == null) {
            return true;
        }
        for (Player player : targets) {
            // Route to the appropriate shared renderer
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

    // One concise error path with the right command name in the usage line
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
                    ? " [append=<color> <style|NONE> <text...>] [target=<selector>]"
                    : "")
        );
    }

    // Parse the marker-based shared Yes/No checkpoint prompt
    private boolean sendCheckpointPrompt(CommandSender sender, String[] args) {
        if (args.length < 9 || !args[6].regionMatches(true, 0, "yes=", 0, 4)) {
            sender.sendMessage(
                "Usage: /pvecheckpoint <delayTicks> <color> <style|NONE> <sound|NONE> " +
                    "<volume> <pitch> " +
                    "yes=<command...> no=<command...> message=<dialogue...> " +
                    "[target=<selector>] [scope=PLAYER|EVERYONE]"
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

        // Same tick delay and color checks as the single-line command
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
        int messageEnd = args.length;
        String targetSelector = null;
        PveDialogue.ChoiceScope choiceScope = PveDialogue.ChoiceScope.EVERYONE;
        boolean foundTarget = false;
        boolean foundScope = false;
        // Options live at the end so message text stays unrestricted
        while (messageEnd > messageIndex + 1) {
            String option = args[messageEnd - 1];
            if (option.regionMatches(true, 0, "target=", 0, 7)) {
                if (foundTarget) {
                    sender.sendMessage("Specify target= only once.");
                    return true;
                }
                targetSelector = option.substring(7);
                if (!targetSelector.startsWith("@")) {
                    sender.sendMessage(
                        "target= must be a Minecraft selector such as @a, @p, @r, or @e."
                    );
                    return true;
                }
                foundTarget = true;
                messageEnd--;
            } else if (option.regionMatches(true, 0, "scope=", 0, 6)) {
                if (foundScope) {
                    sender.sendMessage("Specify scope= only once.");
                    return true;
                }
                String scope = option.substring(6);
                if (scope.equalsIgnoreCase("PLAYER")) {
                    choiceScope = PveDialogue.ChoiceScope.PLAYER;
                } else if (scope.equalsIgnoreCase("EVERYONE")) {
                    choiceScope = PveDialogue.ChoiceScope.EVERYONE;
                } else {
                    sender.sendMessage("Use scope=PLAYER or scope=EVERYONE.");
                    return true;
                }
                foundScope = true;
                messageEnd--;
            } else {
                break;
            }
        }
        String dialogue = joinSection(
            args,
            messageIndex,
            messageEnd,
            "message="
        );
        if (yesCommand.isBlank() || noCommand.isBlank() || dialogue.isBlank()) {
            sender.sendMessage(
                "Provide a command or - for each choice, and non-empty dialogue."
            );
            return true;
        }

        // Use either the caller's selector or the default PVE audience
        Collection<? extends Player> targets;
        if (targetSelector == null) {
            targets = getDialogueTargets(sender);
        } else {
            targets = selectPlayerTargets(sender, targetSelector);
            if (targets == null) {
                return true;
            }
        }

        PveDialogue.sendCheckpointPrompt(
            targets,
            color,
            style,
            dialogue,
            sound,
            volume,
            pitch,
            delayTicks,
            yesCommand,
            noCommand,
            choiceScope,
            targetSelector == null
        );
        return true;
    }

    // Resolve a Minecraft selector and keep player entities only
    private Collection<? extends Player> selectPlayerTargets(
        CommandSender sender,
        String targetSelector
    ) {
        try {
            return Bukkit.selectEntities(sender, targetSelector)
                .stream()
                .filter(Player.class::isInstance)
                .map(Player.class::cast)
                .toList();
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(
                "Invalid target selector: " + exception.getMessage()
            );
            return null;
        }
    }

    // Search the full argument tail for a marker
    private int findMarker(String[] args, int start, String marker) {
        return findMarker(args, start, args.length, marker);
    }

    // Find a marker inside a bounded section
    private int findMarker(String[] args, int start, int end, String marker) {
        // Case-insensitive marker match, since command keywords aren't case-sensitive
        for (int i = start; i < end; i++) {
            if (args[i].regionMatches(true, 0, marker, 0, marker.length())) {
                return i;
            }
        }
        return -1;
    }

    // Join marker content with the remaining words in that section
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

    // Map user-facing style names to Adventure decorations
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

    // Accept Bukkit sound names or namespaced keys; NONE skips playback
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

    // Check volume and pitch before handing them to the sound API
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

    // Clear per-session prompts, villagers, and lobby maps on shutdown
    @Override
    public void onDisable() {
        PveDialogue.clearPendingChoices();
        PvePrologue.clearEntityDialogue();
        PveLobbyType.PveLobbyInstance.clearInstances();
        instance = null;
    }
}
