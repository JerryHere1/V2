package com.ego.plugin.commands;

import com.ego.plugin.EgoPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class ReloadCommand implements CommandExecutor {

    private final EgoPlugin plugin;

    public ReloadCommand(EgoPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("ego.admin")) {
            sender.sendMessage(plugin.msg("no-permission"));
            return true;
        }

        plugin.reloadConfig();

        // Rebuild AI caches from new config
        if (plugin.getAiManager() != null) {
            plugin.getAiManager().rebuildCaches();
        }

        // Reload fake player list
        if (plugin.getFakePlayerManager() != null) {
            plugin.getFakePlayerManager().reload();
        }

        sender.sendMessage(plugin.msg("reloaded"));
        return true;
    }
}
