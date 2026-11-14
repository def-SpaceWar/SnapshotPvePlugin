package com.defspacemine.snapshotpve;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;

// Weighted dialogue for any summoned mob
public final class PvePrologue implements Listener {

    // One weighted line and an entity's full dialogue deck
    private record DialogueLine(
        String text,
        double weight,
        NamedTextColor color,
        TextDecoration style
    ) {}

    private record EntityDialogue(
        List<DialogueLine> lines,
        double totalWeight
    ) {}

    // Dialogue stays tied to the summoned entity UUID
    private static final Map<UUID, EntityDialogue> ENTITY_DIALOGUE =
        new HashMap<>();

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
        List<DialogueLine> lines;
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
            lines = parseDialogueLines(parsedTail.dialogueText());
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(
                Component.text(exception.getMessage(), NamedTextColor.RED)
            );
            sendUsage(sender);
            return true;
        }

        // Unique marker so only this exact mob is picked up afterwards
        final String marker = "pveprologue_" + UUID.randomUUID();
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
        final List<DialogueLine> finalLines = List.copyOf(lines);
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

                double totalWeight = finalLines
                    .stream()
                    .mapToDouble(DialogueLine::weight)
                    .sum();
                ENTITY_DIALOGUE.put(
                    entityId,
                    new EntityDialogue(finalLines, totalWeight)
                );
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
                            finalLines.size() +
                            " weighted dialogue line(s).",
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
        if (sendEntityDialogue(event.getPlayer(), event.getRightClicked())) {
            // Stop the villager trade GUI from opening over the chat message
            event.setCancelled(true);
        }
    }

    private boolean sendEntityDialogue(Player player, Entity entity) {
        EntityDialogue dialogue = ENTITY_DIALOGUE.get(entity.getUniqueId());
        if (dialogue == null) {
            return false;
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
        return true;
    }

    // Toss dialogue data when its entity dies
    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        ENTITY_DIALOGUE.remove(event.getEntity().getUniqueId());
    }

    // Forget all dialogue on plugin shutdown
    public static void clearEntityDialogue() {
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

    private static List<DialogueLine> parseDialogueLines(String input) {
        String[] parts = input.split(",", -1);
        List<DialogueLine> lines = new java.util.ArrayList<>(parts.length);
        for (String part : parts) {
            int colorEnd = part.indexOf('%');
            int styleEnd = colorEnd < 0 ? -1 : part.indexOf('%', colorEnd + 1);
            int weightEnd = styleEnd < 0 ? -1 : part.indexOf('%', styleEnd + 1);
            if (
                colorEnd <= 0 ||
                styleEnd <= colorEnd + 1 ||
                weightEnd <= styleEnd + 1 ||
                weightEnd == part.length() - 1
            ) {
                throw new IllegalArgumentException(
                    "Each dialogue must look like <color>%<style>%<weight>%<text>, separated by commas."
                );
            }
            String colorName = part.substring(0, colorEnd);
            NamedTextColor color = colorName.equalsIgnoreCase("DEFAULT")
                ? NamedTextColor.WHITE
                : NamedTextColor.NAMES.value(
                      colorName.toLowerCase(Locale.ROOT)
                  );
            if (color == null) {
                throw new IllegalArgumentException(
                    "Use a named color such as WHITE, GOLD, or RED, or DEFAULT for WHITE."
                );
            }

            TextDecoration style = parseDialogueStyle(
                part.substring(colorEnd + 1, styleEnd)
            );
            double weight;
            try {
                weight = Double.parseDouble(
                    part.substring(styleEnd + 1, weightEnd)
                );
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(
                    "Dialogue weights must be numbers greater than 0 and at most 100.",
                    exception
                );
            }
            String text = part.substring(weightEnd + 1).trim();
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
            lines.add(new DialogueLine(text, weight, color, style));
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
            "Usage: /pvesummon <mob> <x> <y> <z> [<snbt>|-] <color>%<style>%<weight>%<text>,..."
        );
    }
}
