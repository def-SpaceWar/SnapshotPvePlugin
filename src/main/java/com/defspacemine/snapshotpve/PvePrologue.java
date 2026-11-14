package com.defspacemine.snapshotpve;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

// Weighted dialogue for any summoned mob
public final class PvePrologue implements Listener {

    // One weighted line and an entity's full dialogue deck
    private record DialogueLine(
        String text,
        double weight,
        NamedTextColor color,
        TextDecoration style,
        SoundEffect soundEffect
    ) {}

    private record SoundEffect(Sound sound, float volume, float pitch) {}

    private record AppendedLine(
        String text,
        NamedTextColor color,
        TextDecoration style
    ) {}

    private record SequenceLine(
        String text,
        NamedTextColor color,
        TextDecoration style,
        long delayTicks,
        AppendedLine appendedLine,
        SoundEffect soundEffect,
        String checkPrompt,
        List<PveDialogue.ChoiceOption> checkOptions
    ) {}

    private enum AudienceMode {
        PLAYER,
        PLAYING_PVE,
    }

    private record EntityDialogue(
        List<DialogueLine> lines,
        double totalWeight,
        List<SequenceLine> sequenceLines,
        AudienceMode audienceMode
    ) {}

    private static final class ActiveConversation {

        private final UUID entityId;
        private final AudienceMode audienceMode;
        private final Set<UUID> recipients;
        private BukkitTask task;
        private boolean finished;

        private ActiveConversation(
            UUID entityId,
            AudienceMode audienceMode,
            Set<UUID> recipients
        ) {
            this.entityId = entityId;
            this.audienceMode = audienceMode;
            this.recipients = recipients;
        }
    }

    // Dialogue stays tied to the summoned entity UUID
    private static final Map<UUID, EntityDialogue> ENTITY_DIALOGUE =
        new HashMap<>();
    private static final Map<UUID, ActiveConversation> ACTIVE_CONVERSATIONS =
        new HashMap<>();
    private static final Map<UUID, ActiveConversation> PLAYER_CONVERSATIONS =
        new HashMap<>();

    // Hidden incrementing counter so every summon gets a distinct marker tag
    private static final AtomicLong MARKER_COUNTER = new AtomicLong();

    // Validate the request, let vanilla handle SNBT, then attach the dialogue
    public boolean summon(CommandSender sender, String[] args) {
        if (!sender.hasPermission("snapshotpve.summon")) {
            sender.sendMessage(
                Component.text(
                    "You do not have permission to summon dialogue mobs.",
                    NamedTextColor.RED
                )
            );
            return true;
        }
        if (args.length < 5) {
            sendUsage(sender);
            return true;
        }

        Location senderLocation = getSenderLocation(sender);
        if (senderLocation == null || senderLocation.getWorld() == null) {
            sender.sendMessage(
                Component.text(
                    "Run this command as a player, entity, or command block so its world is known.",
                    NamedTextColor.RED
                )
            );
            return true;
        }

        EntityType type = parseEntityType(args[0]);
        if (type == null) {
            sender.sendMessage(
                Component.text(
                    "Unknown or unsupported mob type: " + args[0],
                    NamedTextColor.RED
                )
            );
            return true;
        }

        Location location;
        String nbt;
        EntityDialogue dialogue;
        try {
            location = new Location(
                senderLocation.getWorld(),
                parseCoordinate(args[1], "x", senderLocation.getX()),
                parseCoordinate(args[2], "y", senderLocation.getY()),
                parseCoordinate(args[3], "z", senderLocation.getZ())
            );
            String tail = String.join(
                " ",
                java.util.Arrays.copyOfRange(args, 4, args.length)
            ).trim();
            ParsedTail parsedTail = parseTail(tail);
            nbt = parsedTail.nbt();
            dialogue = parseDialogue(parsedTail.dialogueText());
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(
                Component.text(exception.getMessage(), NamedTextColor.RED)
            );
            sendUsage(sender);
            return true;
        }

        // Unique marker so only this exact mob is picked up afterwards
        final String marker = "pveprologue_" + MARKER_COUNTER.incrementAndGet();
        nbt = addScoreboardTag(nbt, marker);

        String summonCommand =
            "minecraft:execute in " +
            location.getWorld().getKey() +
            " run minecraft:summon " +
            type.getKey() +
            " " +
            formatCoordinate(location.getX()) +
            " " +
            formatCoordinate(location.getY()) +
            " " +
            formatCoordinate(location.getZ()) +
            (nbt.isEmpty() ? "" : " " + nbt);

        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), summonCommand);

        // Look the entity up on the next tick, once the world has registered it
        final EntityDialogue finalDialogue = dialogue;
        Bukkit.getScheduler().runTask(
            JavaPlugin.getProvidingPlugin(PvePrologue.class),
            () -> {
                UUID entityId = null;
                for (Entity candidate : location.getWorld().getEntities()) {
                    if (candidate.getScoreboardTags().contains(marker)) {
                        entityId = candidate.getUniqueId();
                        candidate.removeScoreboardTag(marker);
                        break;
                    }
                }
                if (entityId == null) {
                    sender.sendMessage(
                        Component.text(
                            "The summon command did not spawn the requested mob. Check the mob type and SNBT data, and review the server log for command errors.",
                            NamedTextColor.RED
                        )
                    );
                    return;
                }

                ENTITY_DIALOGUE.put(entityId, finalDialogue);
                int lineCount = finalDialogue.sequenceLines().isEmpty()
                    ? finalDialogue.lines().size()
                    : finalDialogue.sequenceLines().size();
                sender.sendMessage(
                    Component.text(
                        "Summoned " +
                            type.getKey() +
                            " at " +
                            formatCoordinate(location.getX()) +
                            ", " +
                            formatCoordinate(location.getY()) +
                            ", " +
                            formatCoordinate(location.getZ()) +
                            " with " +
                            lineCount +
                            (finalDialogue.sequenceLines().isEmpty()
                                ? " weighted dialogue line(s)."
                                : " sequential dialogue line(s)."),
                        NamedTextColor.GREEN
                    )
                );
            }
        );
        return true;
    }

    // PlayerInteractAtEntityEvent extends this event, so one handler covers both
    @EventHandler
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        UUID entityId = event.getRightClicked().getUniqueId();
        if (ACTIVE_CONVERSATIONS.containsKey(entityId)) {
            event.setCancelled(true);
            return;
        }
        if (
            PLAYER_CONVERSATIONS.containsKey(
                event.getPlayer().getUniqueId()
            ) &&
            ENTITY_DIALOGUE.containsKey(entityId)
        ) {
            event.setCancelled(true);
            return;
        }
        if (sendEntityDialogue(event.getPlayer(), event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    private boolean sendEntityDialogue(Player player, Entity entity) {
        EntityDialogue dialogue = ENTITY_DIALOGUE.get(entity.getUniqueId());
        if (dialogue == null) {
            return false;
        }

        if (!dialogue.sequenceLines().isEmpty()) {
            startConversation(player, entity, dialogue);
            return true;
        }

        double roll = ThreadLocalRandom.current().nextDouble(
            dialogue.totalWeight()
        );
        double cumulativeWeight = 0;
        DialogueLine selectedLine = dialogue
            .lines()
            .get(dialogue.lines().size() - 1);
        for (DialogueLine candidate : dialogue.lines()) {
            cumulativeWeight += candidate.weight();
            if (roll < cumulativeWeight) {
                selectedLine = candidate;
                break;
            }
        }
        Component message = Component.text(
            selectedLine.text(),
            selectedLine.color()
        );
        if (selectedLine.style() != null) {
            message = message.decorate(selectedLine.style());
        }
        player.sendMessage(message);
        playSound(player, selectedLine.soundEffect());
        return true;
    }

    private void startConversation(
        Player clicker,
        Entity entity,
        EntityDialogue dialogue
    ) {
        List<? extends Player> recipients = dialogue.audienceMode() ==
            AudienceMode.PLAYER
            ? List.of(clicker)
            : Bukkit.getOnlinePlayers()
                  .stream()
                  .filter(player ->
                      player.getScoreboardTags().contains("playingPVE")
                  )
                  .toList();
        if (recipients.isEmpty()) {
            clicker.sendMessage(
                Component.text(
                    "There are no online players tagged playingPVE.",
                    NamedTextColor.RED
                )
            );
            return;
        }
        if (
            recipients.stream().anyMatch(recipient ->
                PLAYER_CONVERSATIONS.containsKey(recipient.getUniqueId())
            )
        ) {
            clicker.sendMessage(
                Component.text(
                    "At least one dialogue recipient is already following another NPC.",
                    NamedTextColor.RED
                )
            );
            return;
        }

        Set<UUID> recipientIds = new HashSet<>();
        for (Player recipient : recipients) {
            recipientIds.add(recipient.getUniqueId());
        }
        ActiveConversation conversation = new ActiveConversation(
            entity.getUniqueId(),
            dialogue.audienceMode(),
            recipientIds
        );
        ACTIVE_CONVERSATIONS.put(entity.getUniqueId(), conversation);
        for (UUID recipientId : recipientIds) {
            PLAYER_CONVERSATIONS.put(recipientId, conversation);
        }
        scheduleSequenceLine(conversation, dialogue, 0);
    }

    private void scheduleSequenceLine(
        ActiveConversation conversation,
        EntityDialogue dialogue,
        int lineIndex
    ) {
        if (!isActive(conversation)) {
            return;
        }
        if (lineIndex >= dialogue.sequenceLines().size()) {
            finishConversation(conversation);
            return;
        }

        SequenceLine line = dialogue.sequenceLines().get(lineIndex);
        conversation.task = Bukkit.getScheduler().runTaskLater(
            JavaPlugin.getProvidingPlugin(PvePrologue.class),
            () -> {
                conversation.task = null;
                if (!isActive(conversation)) {
                    return;
                }
                List<Player> recipients = getCurrentRecipients(conversation);
                if (recipients.isEmpty()) {
                    finishConversation(conversation);
                    return;
                }

                Component message = Component.text(
                    line.text(),
                    line.color()
                );
                if (line.style() != null) {
                    message = message.decorate(line.style());
                }
                if (line.appendedLine() != null) {
                    AppendedLine appended = line.appendedLine();
                    Component appendedMessage = Component.text(
                        appended.text(),
                        appended.color()
                    );
                    if (appended.style() != null) {
                        appendedMessage = appendedMessage.decorate(
                            appended.style()
                        );
                    }
                    message = message
                        .append(Component.space())
                        .append(appendedMessage);
                }
                for (Player recipient : recipients) {
                    recipient.sendMessage(message);
                    playSound(recipient, line.soundEffect());
                }

                if (line.checkPrompt() == null) {
                    scheduleSequenceLine(
                        conversation,
                        dialogue,
                        lineIndex + 1
                    );
                    return;
                }

                Component prompt = PveDialogue.choicePromptWithOptions(
                    line.checkPrompt(),
                    recipients,
                    line.checkOptions(),
                    (clicker, choice) -> {
                        if (!isActive(conversation)) {
                            return;
                        }
                        runChoiceCommand(clicker, choice.command());
                        scheduleSequenceLine(
                            conversation,
                            dialogue,
                            lineIndex + 1
                        );
                    }
                );
                for (Player recipient : recipients) {
                    recipient.sendMessage(prompt);
                }
            },
            line.delayTicks()
        );
    }

    private List<Player> getCurrentRecipients(
        ActiveConversation conversation
    ) {
        List<Player> recipients = new java.util.ArrayList<>();
        for (UUID recipientId : List.copyOf(conversation.recipients)) {
            Player recipient = Bukkit.getPlayer(recipientId);
            if (
                recipient == null ||
                !recipient.isOnline() ||
                (conversation.audienceMode == AudienceMode.PLAYING_PVE &&
                    !recipient.getScoreboardTags().contains("playingPVE"))
            ) {
                removeRecipient(conversation, recipientId);
            } else {
                recipients.add(recipient);
            }
        }
        return recipients;
    }

    private void removeRecipient(
        ActiveConversation conversation,
        UUID recipientId
    ) {
        conversation.recipients.remove(recipientId);
        if (PLAYER_CONVERSATIONS.get(recipientId) == conversation) {
            PLAYER_CONVERSATIONS.remove(recipientId);
        }
        Player recipient = Bukkit.getPlayer(recipientId);
        if (recipient != null) {
            PveDialogue.clearPendingChoice(recipient);
        }
        if (conversation.recipients.isEmpty()) {
            finishConversation(conversation);
        }
    }

    private static boolean isActive(ActiveConversation conversation) {
        return (
            !conversation.finished &&
            ACTIVE_CONVERSATIONS.get(conversation.entityId) == conversation
        );
    }

    private void finishConversation(ActiveConversation conversation) {
        if (conversation.finished) {
            return;
        }
        conversation.finished = true;
        if (conversation.task != null) {
            conversation.task.cancel();
            conversation.task = null;
        }
        if (ACTIVE_CONVERSATIONS.get(conversation.entityId) == conversation) {
            ACTIVE_CONVERSATIONS.remove(conversation.entityId);
        }
        for (UUID recipientId : List.copyOf(conversation.recipients)) {
            if (PLAYER_CONVERSATIONS.get(recipientId) == conversation) {
                PLAYER_CONVERSATIONS.remove(recipientId);
            }
            Player recipient = Bukkit.getPlayer(recipientId);
            if (recipient != null) {
                PveDialogue.clearPendingChoice(recipient);
            }
        }
        conversation.recipients.clear();
    }

    private static void runChoiceCommand(Player player, String command) {
        if (command.equals("-")) {
            return;
        }
        String playerCommand = command.replace("%player%", player.getName());
        if (playerCommand.startsWith("/")) {
            playerCommand = playerCommand.substring(1);
        }
        Bukkit.dispatchCommand(player, playerCommand);
    }

    private static void playSound(Player player, SoundEffect soundEffect) {
        if (soundEffect != null && soundEffect.sound() != null) {
            player.playSound(
                player.getLocation(),
                soundEffect.sound(),
                soundEffect.volume(),
                soundEffect.pitch()
            );
        }
    }

    // Toss dialogue data when its entity dies
    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        UUID entityId = event.getEntity().getUniqueId();
        ENTITY_DIALOGUE.remove(entityId);
        ActiveConversation conversation = ACTIVE_CONVERSATIONS.get(entityId);
        if (conversation != null) {
            finishConversation(conversation);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        ActiveConversation conversation = PLAYER_CONVERSATIONS.get(playerId);
        if (conversation != null) {
            removeRecipient(conversation, playerId);
        }
        PveDialogue.clearPendingChoice(event.getPlayer());
    }

    // Forget all dialogue on plugin shutdown
    public static void clearEntityDialogue() {
        for (ActiveConversation conversation : List.copyOf(
            ACTIVE_CONVERSATIONS.values()
        )) {
            conversation.finished = true;
            if (conversation.task != null) {
                conversation.task.cancel();
            }
            for (UUID recipientId : conversation.recipients) {
                Player recipient = Bukkit.getPlayer(recipientId);
                if (recipient != null) {
                    PveDialogue.clearPendingChoice(recipient);
                }
            }
        }
        ACTIVE_CONVERSATIONS.clear();
        PLAYER_CONVERSATIONS.clear();
        ENTITY_DIALOGUE.clear();
    }

    private static EntityType parseEntityType(String input) {
        NamespacedKey key = NamespacedKey.fromString(
            input.toLowerCase(Locale.ROOT)
        );
        if (key == null) {
            return null;
        }
        EntityType type = Registry.ENTITY_TYPE.get(key);
        return type != null && type.isSpawnable() ? type : null;
    }

    private record ParsedTail(String nbt, String dialogueText) {}

    private static ParsedTail parseTail(String tail) {
        if (tail.isEmpty()) {
            throw new IllegalArgumentException(
                "Provide at least one dialogue line."
            );
        }
        if (tail.startsWith("{")) {
            int nbtEnd = findCompoundEnd(tail);
            if (nbtEnd < 0) {
                throw new IllegalArgumentException(
                    "SNBT data must be a complete compound surrounded by braces."
                );
            }
            String dialogueText = tail.substring(nbtEnd + 1).trim();
            if (dialogueText.isEmpty()) {
                throw new IllegalArgumentException(
                    "Provide dialogue lines after the SNBT data."
                );
            }
            return new ParsedTail(tail.substring(0, nbtEnd + 1), dialogueText);
        }
        if (tail.equals("-") || tail.startsWith("- ")) {
            String dialogueText = tail.substring(1).trim();
            if (dialogueText.isEmpty()) {
                throw new IllegalArgumentException(
                    "Provide dialogue lines after the - NBT placeholder."
                );
            }
            return new ParsedTail("", dialogueText);
        }
        return new ParsedTail("", tail);
    }

    // Insert a scoreboard tag into the SNBT, merging with any existing Tags list
    private static String addScoreboardTag(String nbt, String tag) {
        String quoted = "\"" + tag + "\"";
        if (nbt.isEmpty()) {
            return "{Tags:[" + quoted + "]}";
        }
        int listStart = findTopLevelTagsList(nbt);
        if (listStart >= 0) {
            int next = listStart + 1;
            while (
                next < nbt.length() && Character.isWhitespace(nbt.charAt(next))
            ) {
                next++;
            }
            boolean emptyList = next < nbt.length() && nbt.charAt(next) == ']';
            return (
                nbt.substring(0, listStart + 1) +
                quoted +
                (emptyList ? "" : ",") +
                nbt.substring(listStart + 1)
            );
        }
        String rest = nbt.substring(1);
        boolean emptyCompound = rest.stripLeading().startsWith("}");
        return "{Tags:[" + quoted + "]" + (emptyCompound ? "" : ",") + rest;
    }

    // Index of the '[' of the top-level Tags list, or -1 if there is none
    private static int findTopLevelTagsList(String nbt) {
        int length = nbt.length();
        int i = 1;
        while (i < length) {
            while (
                i < length &&
                (Character.isWhitespace(nbt.charAt(i)) || nbt.charAt(i) == ',')
            ) {
                i++;
            }
            if (i >= length || nbt.charAt(i) == '}') {
                return -1;
            }
            StringBuilder key = new StringBuilder();
            char first = nbt.charAt(i);
            if (first == '"' || first == '\'') {
                i++;
                while (i < length && nbt.charAt(i) != first) {
                    if (nbt.charAt(i) == '\\' && i + 1 < length) {
                        i++;
                    }
                    key.append(nbt.charAt(i));
                    i++;
                }
                i++;
            } else {
                while (
                    i < length &&
                    nbt.charAt(i) != ':' &&
                    !Character.isWhitespace(nbt.charAt(i))
                ) {
                    key.append(nbt.charAt(i));
                    i++;
                }
            }
            while (i < length && Character.isWhitespace(nbt.charAt(i))) {
                i++;
            }
            if (i >= length || nbt.charAt(i) != ':') {
                return -1;
            }
            i++;
            while (i < length && Character.isWhitespace(nbt.charAt(i))) {
                i++;
            }
            if (
                i < length &&
                key.toString().equals("Tags") &&
                nbt.charAt(i) == '['
            ) {
                return i;
            }
            i = skipValue(nbt, i);
        }
        return -1;
    }

    // Advance to the ',' or closing '}' that ends the value starting at index
    private static int skipValue(String text, int index) {
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int i = index; i < text.length(); i++) {
            char current = text.charAt(i);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (current == '"' || current == '\'') {
                quote = current;
            } else if (current == '{' || current == '[' || current == '(') {
                depth++;
            } else if (current == '}' || current == ']' || current == ')') {
                if (depth == 0) {
                    return i;
                }
                depth--;
            } else if (current == ',' && depth == 0) {
                return i;
            }
        }
        return text.length();
    }

    private static int findCompoundEnd(String text) {
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (current == '"' || current == '\'') {
                quote = current;
            } else if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static EntityDialogue parseDialogue(String input) {
        if (input.regionMatches(true, 0, "sequence=", 0, 9)) {
            int modeEnd = input.indexOf(' ');
            if (modeEnd < 0) {
                throw new IllegalArgumentException(
                    "Sequential dialogue needs a recipient mode and at least one line."
                );
            }
            String mode = input.substring(9, modeEnd).toUpperCase(Locale.ROOT);
            AudienceMode audienceMode = switch (mode) {
                case "PLAYER" -> AudienceMode.PLAYER;
                case "PLAYINGPVE" -> AudienceMode.PLAYING_PVE;
                default -> throw new IllegalArgumentException(
                    "Sequential mode must be PLAYER or PLAYINGPVE."
                );
            };
            List<SequenceLine> sequenceLines = parseSequenceLines(
                input.substring(modeEnd + 1).trim()
            );
            return new EntityDialogue(
                List.of(),
                0,
                sequenceLines,
                audienceMode
            );
        }

        List<DialogueLine> lines = parseDialogueLines(input);
        double totalWeight = lines
            .stream()
            .mapToDouble(DialogueLine::weight)
            .sum();
        return new EntityDialogue(lines, totalWeight, List.of(), null);
    }

    private static List<SequenceLine> parseSequenceLines(String input) {
        if (input.isEmpty()) {
            throw new IllegalArgumentException(
                "Provide at least one sequential dialogue line."
            );
        }
        String[] parts = input.split(",", -1);
        List<SequenceLine> lines = new java.util.ArrayList<>(parts.length);
        for (String part : parts) {
            String[] options = part.split("\\|", -1);
            String main = options[0].trim();
            int colorEnd = main.indexOf('%');
            int styleEnd = colorEnd < 0
                ? -1
                : main.indexOf('%', colorEnd + 1);
            int delayEnd = styleEnd < 0
                ? -1
                : main.indexOf('%', styleEnd + 1);
            if (
                colorEnd <= 0 ||
                styleEnd <= colorEnd + 1 ||
                delayEnd <= styleEnd + 1 ||
                delayEnd == main.length() - 1
            ) {
                throw new IllegalArgumentException(
                    "Each sequence line must look like <color>%<style>%<delayTicks>%<text>."
                );
            }

            NamedTextColor color = parseDialogueColor(
                main.substring(0, colorEnd)
            );
            TextDecoration style = parseDialogueStyle(
                main.substring(colorEnd + 1, styleEnd)
            );
            long delayTicks;
            try {
                delayTicks = Long.parseLong(
                    main.substring(styleEnd + 1, delayEnd)
                );
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(
                    "Sequence line delays must be whole numbers of ticks greater than or equal to 0.",
                    exception
                );
            }
            String text = main.substring(delayEnd + 1).trim();
            if (delayTicks < 0 || text.isEmpty()) {
                throw new IllegalArgumentException(
                    "Sequence line delays must be non-negative and text must not be empty."
                );
            }

            AppendedLine appendedLine = null;
            SoundEffect soundEffect = null;
            String checkPrompt = null;
            String yesCommand = null;
            String noCommand = null;
            List<PveDialogue.ChoiceOption> checkOptions =
                new java.util.ArrayList<>();
            for (int i = 1; i < options.length; i++) {
                String option = options[i].trim();
                if (option.regionMatches(true, 0, "append=", 0, 7)) {
                    if (appendedLine != null) {
                        throw new IllegalArgumentException(
                            "Each sequence line can have at most one append option."
                        );
                    }
                    appendedLine = parseAppendedLine(option.substring(7));
                } else if (option.regionMatches(true, 0, "sound=", 0, 6)) {
                    if (soundEffect != null) {
                        throw new IllegalArgumentException(
                            "Each sequence line can have at most one sound option."
                        );
                    }
                    soundEffect = parseSoundEffect(option.substring(6));
                } else if (option.regionMatches(true, 0, "check=", 0, 6)) {
                    if (checkPrompt != null) {
                        throw new IllegalArgumentException(
                            "Each sequence line can have at most one check option."
                        );
                    }
                    checkPrompt = option.substring(6).trim();
                    if (checkPrompt.isEmpty()) {
                        throw new IllegalArgumentException(
                            "A check prompt must not be empty."
                        );
                    }
                } else if (option.regionMatches(true, 0, "yes=", 0, 4)) {
                    if (yesCommand != null) {
                        throw new IllegalArgumentException(
                            "Each sequence line can have at most one yes command."
                        );
                    }
                    yesCommand = option.substring(4).trim();
                    if (yesCommand.isEmpty()) {
                        throw new IllegalArgumentException(
                            "The yes command must not be empty; use - for no action."
                        );
                    }
                } else if (option.regionMatches(true, 0, "no=", 0, 3)) {
                    if (noCommand != null) {
                        throw new IllegalArgumentException(
                            "Each sequence line can have at most one no command."
                        );
                    }
                    noCommand = option.substring(3).trim();
                    if (noCommand.isEmpty()) {
                        throw new IllegalArgumentException(
                            "The no command must not be empty; use - for no action."
                        );
                    }
                } else if (option.regionMatches(true, 0, "option=", 0, 7)) {
                    checkOptions.add(
                        parseSequenceChoiceOption(option.substring(7))
                    );
                } else {
                    throw new IllegalArgumentException(
                        "Sequence options must be append=, sound=, check=, yes=, no=, or option=."
                    );
                }
            }
            if (
                checkPrompt == null &&
                (yesCommand != null || noCommand != null || !checkOptions.isEmpty())
            ) {
                throw new IllegalArgumentException(
                    "Choice options and callbacks require a check prompt."
                );
            }
            if (checkPrompt != null) {
                if (!checkOptions.isEmpty()) {
                    if (yesCommand != null || noCommand != null) {
                        throw new IllegalArgumentException(
                            "Use either option= entries or yes=/no= callbacks for a check, not both."
                        );
                    }
                } else if (yesCommand == null && noCommand == null) {
                    checkOptions.addAll(
                        PveDialogue.defaultYesNoOptions("-", "-")
                    );
                } else if (yesCommand != null && noCommand != null) {
                    checkOptions.addAll(
                        PveDialogue.defaultYesNoOptions(
                            yesCommand,
                            noCommand
                        )
                    );
                } else {
                    throw new IllegalArgumentException(
                        "A check using legacy callbacks requires both yes=<command> and no=<command>."
                    );
                }
            }
            lines.add(
                new SequenceLine(
                    text,
                    color,
                    style,
                    delayTicks,
                    appendedLine,
                    soundEffect,
                    checkPrompt,
                    List.copyOf(checkOptions)
                )
            );
        }
        return lines;
    }

    private static PveDialogue.ChoiceOption parseSequenceChoiceOption(
        String option
    ) {
        int labelEnd = option.indexOf('%');
        int colorEnd = labelEnd < 0
            ? -1
            : option.indexOf('%', labelEnd + 1);
        int styleEnd = colorEnd < 0
            ? -1
            : option.indexOf('%', colorEnd + 1);
        if (
            labelEnd <= 0 ||
            colorEnd <= labelEnd + 1 ||
            styleEnd <= colorEnd + 1 ||
            styleEnd == option.length() - 1
        ) {
            throw new IllegalArgumentException(
                "Check choices must look like option=<label>%<color>%<style|NONE>%<command...>."
            );
        }
        String label = option.substring(0, labelEnd).trim();
        String command = option.substring(styleEnd + 1).trim();
        if (label.isEmpty() || command.isEmpty()) {
            throw new IllegalArgumentException(
                "Check choice labels and commands must not be empty; use - for no action."
            );
        }
        return new PveDialogue.ChoiceOption(
            label,
            parseDialogueColor(option.substring(labelEnd + 1, colorEnd)),
            parseDialogueStyle(option.substring(colorEnd + 1, styleEnd)),
            command
        );
    }

    private static SoundEffect parseSoundEffect(String soundSpec) {
        String[] parts = soundSpec.split("%", -1);
        if (parts.length != 1 && parts.length != 3) {
            throw new IllegalArgumentException(
                "Sound options must look like sound=<sound|NONE> or sound=<sound|NONE>%<volume>%<pitch>."
            );
        }
        Sound sound = SnapshotPvePlugin.parseSound(parts[0]);
        float volume = parts.length == 3
            ? SnapshotPvePlugin.parseSoundSetting(
                  parts[1],
                  "sound volume",
                  true
              )
            : 1.0F;
        float pitch = parts.length == 3
            ? SnapshotPvePlugin.parseSoundSetting(
                  parts[2],
                  "sound pitch",
                  false
              )
            : 1.0F;
        return new SoundEffect(sound, volume, pitch);
    }

    private static AppendedLine parseAppendedLine(String input) {
        int colorEnd = input.indexOf('%');
        int styleEnd = colorEnd < 0
            ? -1
            : input.indexOf('%', colorEnd + 1);
        if (
            colorEnd <= 0 ||
            styleEnd <= colorEnd + 1 ||
            styleEnd == input.length() - 1
        ) {
            throw new IllegalArgumentException(
                "Append options must look like append=<color>%<style>%<text>."
            );
        }
        String text = input.substring(styleEnd + 1).trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(
                "Appended dialogue text must not be empty."
            );
        }
        return new AppendedLine(
            text,
            parseDialogueColor(input.substring(0, colorEnd)),
            parseDialogueStyle(input.substring(colorEnd + 1, styleEnd))
        );
    }

    private static NamedTextColor parseDialogueColor(String colorName) {
        NamedTextColor color = colorName.equalsIgnoreCase("DEFAULT")
            ? NamedTextColor.WHITE
            : NamedTextColor.NAMES.value(colorName.toLowerCase(Locale.ROOT));
        if (color == null) {
            throw new IllegalArgumentException(
                "Use a named color such as WHITE, GOLD, or RED, or DEFAULT for WHITE."
            );
        }
        return color;
    }

    private static List<DialogueLine> parseDialogueLines(String input) {
        String[] parts = input.split(",", -1);
        List<DialogueLine> lines = new java.util.ArrayList<>(parts.length);
        for (String part : parts) {
            String[] options = part.split("\\|", -1);
            String definition = options[0].trim();
            int colorEnd = definition.indexOf('%');
            int styleEnd = colorEnd < 0
                ? -1
                : definition.indexOf('%', colorEnd + 1);
            int weightEnd = styleEnd < 0
                ? -1
                : definition.indexOf('%', styleEnd + 1);
            if (
                colorEnd <= 0 ||
                styleEnd <= colorEnd + 1 ||
                weightEnd <= styleEnd + 1 ||
                weightEnd == definition.length() - 1
            ) {
                throw new IllegalArgumentException(
                    "Each dialogue must look like <color>%<style>%<weight>%<text>, with an optional |sound=... setting."
                );
            }
            NamedTextColor color = parseDialogueColor(
                definition.substring(0, colorEnd)
            );

            TextDecoration style = parseDialogueStyle(
                definition.substring(colorEnd + 1, styleEnd)
            );
            double weight;
            try {
                weight = Double.parseDouble(
                    definition.substring(styleEnd + 1, weightEnd)
                );
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(
                    "Dialogue weights must be numbers greater than 0 and at most 100.",
                    exception
                );
            }
            String text = definition.substring(weightEnd + 1).trim();
            if (
                !Double.isFinite(weight) ||
                weight <= 0 ||
                weight > 100 ||
                text.isEmpty()
            ) {
                throw new IllegalArgumentException(
                    "Dialogue weights must be greater than 0 and at most 100, with non-empty text."
                );
            }
            SoundEffect soundEffect = null;
            for (int i = 1; i < options.length; i++) {
                String option = options[i].trim();
                if (!option.regionMatches(true, 0, "sound=", 0, 6)) {
                    throw new IllegalArgumentException(
                        "Weighted dialogue supports only the optional sound= setting."
                    );
                }
                if (soundEffect != null) {
                    throw new IllegalArgumentException(
                        "Each weighted dialogue line can have at most one sound option."
                    );
                }
                soundEffect = parseSoundEffect(option.substring(6));
            }
            lines.add(
                new DialogueLine(text, weight, color, style, soundEffect)
            );
        }
        return lines;
    }

    private static TextDecoration parseDialogueStyle(String styleName) {
        return switch (styleName.toLowerCase(Locale.ROOT)) {
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

    private static Location getSenderLocation(CommandSender sender) {
        if (sender instanceof Player player) {
            return player.getLocation();
        }
        if (sender instanceof BlockCommandSender blockSender) {
            return blockSender.getBlock().getLocation().add(0.5, 0.5, 0.5);
        }
        if (sender instanceof Entity entity) {
            return entity.getLocation();
        }
        return null;
    }

    private static double parseCoordinate(
        String value,
        String axis,
        double senderCoordinate
    ) {
        if (value.startsWith("~")) {
            String offsetText = value.substring(1);
            if (offsetText.isEmpty()) {
                return senderCoordinate;
            }
            try {
                double offset = Double.parseDouble(offsetText);
                double coordinate = senderCoordinate + offset;
                if (Double.isFinite(offset) && Double.isFinite(coordinate)) {
                    return coordinate;
                }
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(
                    "Coordinate " + axis + " must be a number or ~offset.",
                    exception
                );
            }
            throw new IllegalArgumentException(
                "Coordinate " + axis + " must be a finite number or ~offset."
            );
        }
        double coordinate;
        try {
            coordinate = Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                "Coordinate " + axis + " must be a finite number.",
                exception
            );
        }
        if (Double.isFinite(coordinate)) {
            return coordinate;
        }
        throw new IllegalArgumentException(
            "Coordinate " + axis + " must be a finite number."
        );
    }

    // Cleaner feedback for whole-number coordinates
    private static String formatCoordinate(double value) {
        return value == Math.rint(value) &&
            value >= Long.MIN_VALUE &&
            value < Long.MAX_VALUE
            ? Long.toString((long) value)
            : new java.math.BigDecimal(value)
                  .setScale(6, java.math.RoundingMode.HALF_UP)
                  .stripTrailingZeros()
                  .toPlainString();
    }

    // One usage string for all bad argument shapes
    private static void sendUsage(CommandSender sender) {
        sender.sendMessage(
            "Usage: /pvesummon <mob> <x> <y> <z> [<snbt>|-] " +
                "[<color>%<style>%<weight>%<text>[|sound=<sound|NONE>[%<volume>%<pitch>]],...|sequence=PLAYER <sequence-lines>|sequence=PLAYINGPVE <sequence-lines>]"
        );
    }
}
