package com.ego.plugin.commands;

import com.ego.plugin.EgoPlugin;
import com.ego.plugin.data.BotInstance;
import com.ego.plugin.data.BotMode;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

public class SpawnBotCommand implements CommandExecutor {

    private final EgoPlugin plugin;

    public SpawnBotCommand(EgoPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        String permission = plugin.getConfig().getString("spawn-permission", "ego.spawn");
        if (!permission.isEmpty() && !player.hasPermission(permission)) {
            player.sendMessage(plugin.msg("no-permission"));
            return true;
        }

        int maxBots = plugin.getConfig().getInt("max-bots-per-player", 1);
        if (plugin.getDataManager().hasBot(player.getUniqueId())) {
            player.sendMessage(plugin.msg("max-bots", "{max}", String.valueOf(maxBots)));
            return true;
        }

        String botName = plugin.getConfig().getString("bot-name", "&bEgoBot");
        NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER,
                EgoPlugin.colorize(botName));
        npc.spawn(player.getLocation());

        BotInstance bot = new BotInstance(player.getUniqueId(), npc.getId(), npc);
        bot.setMode(BotMode.IDLE);
        plugin.getDataManager().registerBot(bot);

        player.sendMessage(plugin.msg("spawned"));
        return true;
    }
}
