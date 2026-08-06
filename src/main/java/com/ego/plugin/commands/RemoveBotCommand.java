package com.ego.plugin.commands;

import com.ego.plugin.EgoPlugin;
import com.ego.plugin.data.BotInstance;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class RemoveBotCommand implements CommandExecutor {

    private final EgoPlugin plugin;

    public RemoveBotCommand(EgoPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        BotInstance bot = plugin.getDataManager().getBotByOwner(player.getUniqueId());
        if (bot == null) {
            player.sendMessage(plugin.msg("bot-not-found"));
            return true;
        }

        // Unload backpack items to owner before removing
        bot.unloadBackpackTo(player);

        // Remove hologram
        if (plugin.getAiManager().getHologramManager() != null) {
            plugin.getAiManager().getHologramManager().remove(bot);
        }

        // Despawn and delete NPC
        NPC npc = bot.getNpc();
        if (npc != null && npc.isSpawned()) {
            npc.despawn();
        }
        if (npc != null) {
            CitizensAPI.getNPCRegistry().deregister(npc);
        }

        plugin.getDataManager().removeBot(player.getUniqueId());
        player.sendMessage(plugin.msg("removed"));
        return true;
    }
}
