package com.ego.plugin.listeners;

import com.ego.plugin.EgoPlugin;
import com.ego.plugin.data.BotInstance;
import com.ego.plugin.gui.ControlPanelGui;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class NpcListener implements Listener {

    private final EgoPlugin plugin;

    public NpcListener(EgoPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onNpcRightClick(NPCRightClickEvent event) {
        Player player = event.getClicker();
        int npcId     = event.getNPC().getId();

        BotInstance bot = plugin.getDataManager().getBotByNpcId(npcId);
        if (bot == null) return;

        // Only the owner can open the panel
        if (!bot.getOwnerUuid().equals(player.getUniqueId())) {
            player.sendMessage(plugin.msg("not-your-bot"));
            return;
        }

        ControlPanelGui.openControlPanel(player, bot);
    }
}
