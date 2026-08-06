package com.ego.plugin.listeners;

import com.ego.plugin.EgoPlugin;
import com.ego.plugin.data.BotInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

public class ChatListener implements Listener {

    private final EgoPlugin plugin;

    public ChatListener(EgoPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player  = event.getPlayer();
        String message = event.getMessage().trim().toLowerCase();

        if (!message.equals("come here")) return;

        BotInstance bot = plugin.getDataManager().getBotByOwner(player.getUniqueId());
        if (bot == null) return;
        if (bot.getNpc() == null || !bot.getNpc().isSpawned()) return;

        // Teleport must happen on the main thread
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            bot.getNpc().getEntity().teleport(player.getLocation());
            player.sendMessage(plugin.msg("summoned"));
        });
    }
}
