package com.defspacemine.snapshotpve;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Executes commands across PVE lobbies with conditional scoping and entity selection.
 * Syntax: /pveexecute [if|unless] [inLobby=true|false] [@selector[tag=...]] run <command...>
 */
public class PveExecute implements CommandExecutor {

    private static final Pattern TAG_FILTER_PATTERN = Pattern.compile("tag=([\\w]+)");

    @Override
    public boolean onCommand(
        CommandSender sender,
        Command command,
        String label,
        String[] args
    ) {
        if (args.length < 2) {
            sender.sendMessage("Usage: /pveexecute [if|unless] [inLobby=true|false] [@selector] run <command...>");
            return true;
        }

        int argIndex = 0;
        String condition = null;
        boolean inLobby = true;
        boolean hasInLobbyFilter = false;
        String selectorArg = null;

        // Parse condition (if/unless)
        if (argIndex < args.length && (args[argIndex].equalsIgnoreCase("if") || args[argIndex].equalsIgnoreCase("unless"))) {
            condition = args[argIndex].toLowerCase(Locale.ROOT);
            argIndex++;
        }

        // Parse inLobby filter
        if (argIndex < args.length && args[argIndex].regionMatches(true, 0, "inLobby=", 0, 8)) {
            String inLobbyValue = args[argIndex].substring(8).toLowerCase(Locale.ROOT);
            if (inLobbyValue.equals("true")) {
                inLobby = true;
                hasInLobbyFilter = true;
            } else if (inLobbyValue.equals("false")) {
                inLobby = false;
                hasInLobbyFilter = true;
            } else {
                sender.sendMessage("inLobby must be true or false.");
                return true;
            }
            argIndex++;
        }

        // Parse selector
        if (argIndex < args.length && args[argIndex].startsWith("@")) {
            selectorArg = args[argIndex];
            argIndex++;
        } else {
            sender.sendMessage("A selector (@a, @e, @r, @p, etc.) is required.");
            return true;
        }

        // Find "run" keyword
        int runIndex = -1;
        for (int i = argIndex; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("run")) {
                runIndex = i;
                break;
            }
        }

        if (runIndex < 0 || runIndex + 1 >= args.length) {
            sender.sendMessage("A run keyword and command are required.");
            return true;
        }

        String commandToRun = String.join(" ", Arrays.copyOfRange(args, runIndex + 1, args.length));

        // Execute across lobbies
        int executionCount = 0;
        for (Entity entity : getFilteredEntities(selectorArg, hasInLobbyFilter, inLobby)) {
            String fullCommand = String.format(
                "minecraft:execute as %s at @s run %s",
                entity.getUniqueId(),
                commandToRun
            );
            if (Bukkit.dispatchCommand(Bukkit.getConsoleSender(), fullCommand)) {
                executionCount++;
            }
        }

        sender.sendMessage(
            Component.text(
                "Executed command for " + executionCount + " entity(ies).",
                NamedTextColor.GREEN
            )
        );
        return true;
    }

    private List<Entity> getFilteredEntities(
        String selector,
        boolean hasInLobbyFilter,
        boolean inLobby
    ) {
        List<Entity> results = new ArrayList<>();

        // Parse selector and brackets
        int bracketStart = selector.indexOf('[');
        String baseSelector = bracketStart >= 0 ? selector.substring(0, bracketStart) : selector;
        Set<String> requiredTags = new HashSet<>();

        if (bracketStart >= 0 && selector.endsWith("]")) {
            String bracketed = selector.substring(bracketStart + 1, selector.length() - 1);
            Matcher tagMatcher = TAG_FILTER_PATTERN.matcher(bracketed);
            while (tagMatcher.find()) {
                requiredTags.add(tagMatcher.group(1));
            }
        }

        if (baseSelector.equals("@a")) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (shouldIncludeEntity(player, hasInLobbyFilter, inLobby, requiredTags)) {
                    results.add(player);
                }
            }
        } else if (baseSelector.equals("@e")) {
            for (Entity entity : getAllLoadedEntities()) {
                if (shouldIncludeEntity(entity, hasInLobbyFilter, inLobby, requiredTags)) {
                    results.add(entity);
                }
            }
        } else if (baseSelector.equals("@p")) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (shouldIncludeEntity(player, hasInLobbyFilter, inLobby, requiredTags)) {
                    results.add(player);
                    break;
                }
            }
        } else if (baseSelector.equals("@r")) {
            List<Entity> candidates = new ArrayList<>();
            for (Entity entity : getAllLoadedEntities()) {
                if (shouldIncludeEntity(entity, hasInLobbyFilter, inLobby, requiredTags)) {
                    candidates.add(entity);
                }
            }
            if (!candidates.isEmpty()) {
                results.add(candidates.get((int) (Math.random() * candidates.size())));
            }
        }

        return results;
    }

    private boolean shouldIncludeEntity(
        Entity entity,
        boolean hasInLobbyFilter,
        boolean inLobby,
        Set<String> requiredTags
    ) {
        // Check required tags
        if (!requiredTags.isEmpty()) {
            Set<String> entityTags = entity.getScoreboardTags();
            for (String requiredTag : requiredTags) {
                if (!entityTags.contains(requiredTag)) {
                    return false;
                }
            }
        }

        // Check lobby filter
        if (!hasInLobbyFilter) {
            return true;
        }

        PveLobbyType.PveLobbyInstance lobby = PveLobbyType.PveLobbyInstance.forWorld(
            entity.getWorld()
        );
        boolean entityInLobby = lobby != null;

        return entityInLobby == inLobby;
    }

    private List<Entity> getAllLoadedEntities() {
        List<Entity> allEntities = new ArrayList<>();
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            allEntities.addAll(world.getEntities());
        }
        return allEntities;
    }
}
