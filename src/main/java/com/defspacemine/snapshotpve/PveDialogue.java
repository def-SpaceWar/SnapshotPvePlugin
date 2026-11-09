package com.defspacemine.snapshotpve;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

public final class PveDialogue {

    public enum ChoiceScope {
        PLAYER,
        EVERYONE,
    }

    private static final Map<UUID, PendingChoice> PENDING_CHOICES =
        new HashMap<>();

    private record PendingChoice(
        String token,
        BiConsumer<Player, Boolean> callback,
        AtomicBoolean consumed,
        List<UUID> audience
    ) {}

    private PveDialogue() {}

    /** Whether the player is currently playing or spectating a PVE game. */
    public static boolean hasPveTag(Player player) {
        return (
            player.getScoreboardTags().contains("playingPVE") ||
            player.getScoreboardTags().contains("spectatingPVE")
        );
    }

    /**
     * Sends one colored, optionally decorated dialogue line after a delay.
     * The delay is measured in ticks (20 ticks is approximately 1 second).
     * Pass null for style to send plain, undecorated text.
     */
    public static BukkitTask sendDelayed(
        Player player,
        TextColor color,
        TextDecoration style,
        String dialogue,
        long delayTicks
    ) {
        return sendDelayed(player, color, style, dialogue, null, delayTicks);
    }

    public static BukkitTask sendDelayed(
        Player player,
        TextColor color,
        TextDecoration style,
        String dialogue,
        Sound sound,
        long delayTicks
    ) {
        return sendDelayed(
            player,
            color,
            style,
            dialogue,
            sound,
            1.0F,
            1.0F,
            delayTicks
        );
    }

    public static BukkitTask sendDelayed(
        Player player,
        TextColor color,
        TextDecoration style,
        String dialogue,
        Sound sound,
        float volume,
        float pitch,
        long delayTicks
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(color, "color");
        Objects.requireNonNull(dialogue, "dialogue");
        if (delayTicks < 0) {
            throw new IllegalArgumentException("delayTicks cannot be negative");
        }

        Component message = Component.text(dialogue, color);
        if (style != null) {
            message = message.decorate(style);
        }
        return sendComponentDelayed(
            player,
            message,
            sound,
            volume,
            pitch,
            delayTicks
        );
    }

    public static BukkitTask sendAppendedDelayed(
        Player player,
        TextColor color,
        String dialogue,
        TextColor appendedColor,
        TextDecoration appendedStyle,
        String appendedDialogue,
        Sound sound,
        float volume,
        float pitch,
        long delayTicks
    ) {
        Objects.requireNonNull(color, "color");
        Objects.requireNonNull(dialogue, "dialogue");
        Objects.requireNonNull(appendedColor, "appendedColor");
        Objects.requireNonNull(appendedDialogue, "appendedDialogue");

        Component message = Component.text(dialogue, color).append(
            Component.space()
        );
        Component appended = Component.text(appendedDialogue, appendedColor);
        if (appendedStyle != null) {
            appended = appended.decorate(appendedStyle);
        }
        return sendComponentDelayed(
            player,
            message.append(appended),
            sound,
            volume,
            pitch,
            delayTicks
        );
    }

    public static BukkitTask sendDefDelayed(
        Player player,
        TextColor color,
        String dialogue,
        Sound sound,
        long delayTicks
    ) {
        return sendDefDelayed(
            player,
            color,
            dialogue,
            sound,
            1.0F,
            1.0F,
            delayTicks
        );
    }

    public static BukkitTask sendDefDelayed(
        Player player,
        TextColor color,
        String dialogue,
        Sound sound,
        float volume,
        float pitch,
        long delayTicks
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(color, "color");
        Objects.requireNonNull(dialogue, "dialogue");
        Component message = Component.empty()
            .append(Component.text("<", NamedTextColor.RED))
            .append(
                Component.text("defSpaceMine", NamedTextColor.RED).decorate(
                    TextDecoration.OBFUSCATED
                )
            )
            .append(Component.text("> ", NamedTextColor.RED))
            .append(Component.text(dialogue, color));
        return sendComponentDelayed(
            player,
            message,
            sound,
            volume,
            pitch,
            delayTicks
        );
    }

    private static BukkitTask sendComponentDelayed(
        Player player,
        Component message,
        Sound sound,
        float volume,
        float pitch,
        long delayTicks
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(message, "message");
        if (delayTicks < 0) {
            throw new IllegalArgumentException("delayTicks cannot be negative");
        }

        return Bukkit.getScheduler().runTaskLater(
            SnapshotPvePlugin.getInstance(),
            () -> {
                if (player.isOnline()) {
                    player.sendMessage(message);
                    if (sound != null) {
                        player.playSound(
                            player.getLocation(),
                            sound,
                            volume,
                            pitch
                        );
                    }
                }
            },
            delayTicks
        );
    }

    public static Component choicePrompt(
        String prompt,
        Player player,
        Consumer<Boolean> callback
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(callback, "callback");
        return choicePrompt(prompt, List.of(player), (clicker, choice) ->
            callback.accept(choice)
        );
    }

    public static BukkitTask sendCheckpointPrompt(
        Collection<? extends Player> players,
        TextColor color,
        TextDecoration style,
        String dialogue,
        Sound sound,
        float volume,
        float pitch,
        long delayTicks,
        String yesCommand,
        String noCommand,
        ChoiceScope choiceScope,
        boolean requirePveTag
    ) {
        Objects.requireNonNull(players, "players");
        Objects.requireNonNull(color, "color");
        Objects.requireNonNull(dialogue, "dialogue");
        Objects.requireNonNull(yesCommand, "yesCommand");
        Objects.requireNonNull(noCommand, "noCommand");
        Objects.requireNonNull(choiceScope, "choiceScope");
        if (delayTicks < 0) {
            throw new IllegalArgumentException("delayTicks cannot be negative");
        }

        return Bukkit.getScheduler().runTaskLater(
            SnapshotPvePlugin.getInstance(),
            () -> {
                List<? extends Player> recipients = players
                    .stream()
                    .filter(player ->
                        player.isOnline() &&
                        (!requirePveTag || hasPveTag(player))
                    )
                    .toList();
                if (choiceScope == ChoiceScope.PLAYER) {
                    for (Player player : recipients) {
                        String token = UUID.randomUUID().toString();
                        PENDING_CHOICES.put(
                            player.getUniqueId(),
                            new PendingChoice(
                                token,
                                (clicker, yes) ->
                                    runChoiceCommand(
                                        clicker,
                                        yes ? yesCommand : noCommand
                                    ),
                                new AtomicBoolean(),
                                List.of(player.getUniqueId())
                            )
                        );
                        player.sendMessage(
                            checkpointPrompt(dialogue, color, style, token)
                        );
                        if (sound != null) {
                            player.playSound(
                                player.getLocation(),
                                sound,
                                volume,
                                pitch
                            );
                        }
                    }
                    return;
                }

                String token = UUID.randomUUID().toString();
                PendingChoice pending = new PendingChoice(
                    token,
                    (player, yes) ->
                        runChoiceCommand(player, yes ? yesCommand : noCommand),
                    new AtomicBoolean(),
                    recipients.stream().map(Player::getUniqueId).toList()
                );
                Component message = checkpointPrompt(
                    dialogue,
                    color,
                    style,
                    token
                );
                for (Player player : recipients) {
                    PENDING_CHOICES.put(player.getUniqueId(), pending);
                    player.sendMessage(message);
                    if (sound != null) {
                        player.playSound(
                            player.getLocation(),
                            sound,
                            volume,
                            pitch
                        );
                    }
                }
            },
            delayTicks
        );
    }

    private static Component checkpointPrompt(
        String dialogue,
        TextColor color,
        TextDecoration style,
        String token
    ) {
        Component prompt = Component.text(dialogue, color);
        if (style != null) {
            prompt = prompt.decorate(style);
        }
        return prompt
            .append(Component.text(": "))
            .append(choiceButton("[Yes]", NamedTextColor.GREEN, token, "YES"))
            .append(Component.space())
            .append(choiceButton("[No]", NamedTextColor.RED, token, "NO"));
    }

    public static Component choicePrompt(
        String prompt,
        Collection<? extends Player> players,
        BiConsumer<Player, Boolean> callback
    ) {
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(players, "players");
        Objects.requireNonNull(callback, "callback");
        String token = UUID.randomUUID().toString();
        PendingChoice pending = new PendingChoice(
            token,
            callback,
            new AtomicBoolean(),
            players.stream().map(Player::getUniqueId).toList()
        );
        for (Player player : players) {
            PENDING_CHOICES.put(player.getUniqueId(), pending);
        }

        return Component.text(prompt + " ", NamedTextColor.RED)
            .append(choiceButton(" [YES]", NamedTextColor.GREEN, token, "YES"))
            .append(Component.space())
            .append(choiceButton("[NO]", NamedTextColor.RED, token, "NO"));
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

    public static boolean handleChoice(
        Player player,
        String token,
        String choice
    ) {
        PendingChoice pending = PENDING_CHOICES.get(player.getUniqueId());
        if (pending == null || !pending.token().equals(token)) {
            player.sendMessage(
                Component.text(
                    "That checkpoint choice has expired.",
                    NamedTextColor.RED
                )
            );
            return true;
        }
        if (!choice.equalsIgnoreCase("yes") && !choice.equalsIgnoreCase("no")) {
            player.sendMessage(
                Component.text("Choose yes or no.", NamedTextColor.RED)
            );
            return true;
        }

        if (pending.consumed().compareAndSet(false, true)) {
            boolean yes = choice.equalsIgnoreCase("yes");
            Component announcement = Component.text(
                player.getName() + " chose " + (yes ? "Yes" : "No"),
                NamedTextColor.GRAY
            );
            for (UUID recipientId : pending.audience()) {
                Player recipient = Bukkit.getPlayer(recipientId);
                if (recipient != null && recipient.isOnline()) {
                    recipient.sendMessage(announcement);
                }
            }
            pending.callback().accept(player, yes);
        }
        return true;
    }

    public static void clearPendingChoices() {
        PENDING_CHOICES.clear();
    }

    public static void clearPendingChoice(Player player) {
        PENDING_CHOICES.remove(player.getUniqueId());
    }

    private static Component choiceButton(
        String label,
        NamedTextColor color,
        String token,
        String choice
    ) {
        return Component.text(label, color)
            .clickEvent(
                ClickEvent.runCommand("/pvecheckpoint " + token + " " + choice)
            )
            .hoverEvent(
                HoverEvent.showText(
                    Component.text("Click to choose " + choice, color)
                )
            );
    }
}
