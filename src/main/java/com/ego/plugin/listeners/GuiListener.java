package com.ego.plugin.listeners;

import com.ego.plugin.EgoPlugin;
import com.ego.plugin.data.BotInstance;
import com.ego.plugin.data.BotMode;
import com.ego.plugin.gui.ControlPanelGui;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.ItemStack;

public class GuiListener implements Listener {

    private final EgoPlugin plugin;

    public GuiListener(EgoPlugin plugin) {
        this.plugin = plugin;
    }

    // ── Control panel clicks ──────────────────────────────────────

    @EventHandler
    public void onControlPanelClick(InventoryClickEvent event) {
        String title = ChatColor.stripColor(event.getView().getTitle());
        if (!title.contains("Ego Control Panel")) return;

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getCurrentItem() == null) return;

        BotInstance bot = plugin.getDataManager().getBotByOwner(player.getUniqueId());
        if (bot == null) return;

        Material clicked = event.getCurrentItem().getType();
        int slot = event.getSlot();

        switch (slot) {
            case 10 -> setMode(player, bot, BotMode.MINING);
            case 11 -> setMode(player, bot, BotMode.LUMBERJACK);
            case 12 -> setMode(player, bot, BotMode.FARMING);
            case 14 -> setMode(player, bot, BotMode.COMBAT);
            case 16 -> {
                player.closeInventory();
                ControlPanelGui.openBackpack(player, bot);
            }
            case 25 -> {
                // Unload backpack
                player.closeInventory();
                bot.unloadBackpackTo(player);
                plugin.getDataManager().saveBotData(bot);
                player.sendMessage(plugin.msg("backpack-unloaded"));
            }
            case 31 -> setMode(player, bot, BotMode.IDLE);
        }
    }

    private void setMode(Player player, BotInstance bot, BotMode mode) {
        bot.setMode(mode);
        plugin.getDataManager().saveBotData(bot);
        player.closeInventory();
        player.sendMessage(plugin.msg("mode-set", "{mode}", mode.name()));
        ControlPanelGui.openControlPanel(player, bot);
    }

    // ── Backpack view: sync changes back to bot ───────────────────

    @EventHandler
    public void onBackpackClose(InventoryCloseEvent event) {
        String title = ChatColor.stripColor(event.getView().getTitle());
        if (!title.contains("Ego Backpack")) return;
        if (!(event.getPlayer() instanceof Player player)) return;

        BotInstance bot = plugin.getDataManager().getBotByOwner(player.getUniqueId());
        if (bot == null) return;

        // Copy inventory contents back to the bot's backpack array
        ItemStack[] backpack = bot.getBackpack();
        for (int i = 0; i < backpack.length; i++) {
            ItemStack slot = event.getInventory().getItem(i);
            backpack[i] = (slot != null && slot.getType() != org.bukkit.Material.AIR) ? slot : null;
        }
        bot.setBackpackFull(bot.isActuallyFull());
        plugin.getDataManager().saveBotData(bot);
    }
}
