package com.defspacemine.snapshotpve;

import java.util.Arrays;
import java.util.logging.Level;

import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandException;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

final class PveWait implements CommandExecutor {
    private final Plugin plugin;

    PveWait(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("snapshotpve.wait")) {
            sender.sendMessage("You do not have permission to schedule commands.");
            return true;
        }
        boolean runCommand = args.length >= 3 && args[1].equalsIgnoreCase("run");
        if (args.length < 2 || (args.length != 2 && !runCommand)) {
            sender.sendMessage("Usage: /pvewait <delayTicks> <function|#function_tag> OR /pvewait <delayTicks> run <command...>");
            return true;
        }

        long delay;
        try {
            delay = Long.parseLong(args[0]);
            if (delay < 0 || delay > Integer.MAX_VALUE) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException exception) {
            sender.sendMessage("delayTicks must be a whole number between 0 and 2147483647.");
            return true;
        }
        String commandToRun;
        if (runCommand) {
            String commandText = String.join(" ", Arrays.copyOfRange(args, 2, args.length)).trim();
            if (commandText.startsWith("/")) {
                commandText = commandText.substring(1);
            }
            if (commandText.isBlank() || Character.isWhitespace(commandText.charAt(0))) {
                sender.sendMessage("Provide a command after run.");
                return true;
            }
            commandToRun = commandText;
        } else {
            String function = args[1];
            String key = function.startsWith("#") ? function.substring(1) : function;
            if (NamespacedKey.fromString(key) == null) {
                sender.sendMessage("Provide a valid Minecraft function name or #function tag.");
                return true;
            }
            commandToRun = "minecraft:function " + function;
        }

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            try {
                if (!plugin.getServer().dispatchCommand(sender, commandToRun)) {
                    sender.sendMessage("Could not run delayed command: " + commandToRun);
                    plugin.getLogger().warning("Could not dispatch " + commandToRun);
                }
            } catch (CommandException exception) {
                sender.sendMessage("Could not run delayed command: " + commandToRun);
                plugin.getLogger().log(Level.WARNING, "Could not dispatch " + commandToRun, exception);
            }
        }, delay);
        return true;
    }
}
