package com.ego.plugin.fakeplayers;

import com.ego.plugin.EgoPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class FakePlayerListener implements Listener {

    private final EgoPlugin plugin;

    public FakePlayerListener(EgoPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Send fake tab entries to the joining player after a short delay
        // to ensure their client is ready
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> plugin.getFakePlayerManager().sendFakeTabEntries(event.getPlayer()),
                10L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Clear chatbot history when player leaves
        if (plugin.getChatResponder() != null) {
            plugin.getChatResponder().clearHistory(event.getPlayer().getUniqueId());
        }
    }
}
