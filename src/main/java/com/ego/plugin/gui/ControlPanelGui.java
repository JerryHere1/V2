package com.ego.plugin.gui;

import com.ego.plugin.EgoPlugin;
import com.ego.plugin.data.BotInstance;
import com.ego.plugin.data.BotMode;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.List;

/**
 * Chest inventory control panel and backpack viewer.
 *
 * Improvements over SuperBot v1:
 *  - Backpack fill bar in control panel (shows used / 27 slots)
 *  - "Unload Backpack" button that transfers items to the player
 *  - Cleaner slot layout
 */
public class ControlPanelGui {

    public static final String CONTROL_TITLE  = ChatColor.DARK_GRAY + "⚙ " + ChatColor.AQUA + "Ego Control Panel";
    public static final String BACKPACK_TITLE = ChatColor.DARK_GRAY + "🎒 " + ChatColor.AQUA + "Ego Backpack";

    public static void openControlPanel(Player player, BotInstance bot) {
        Inventory inv = Bukkit.createInventory(null, 36, CONTROL_TITLE);

        // Border glass
        ItemStack glass = makeItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 36; i++) inv.setItem(i, glass);

        // ── Mode buttons ─────────────────────────────────────────
        inv.setItem(10, modeButton(bot, BotMode.MINING, Material.DIAMOND_PICKAXE,
                ChatColor.AQUA + "⛏ Mining Mode",
                "Scans for ores and tunnels",
                "through stone to find them."));

        inv.setItem(11, modeButton(bot, BotMode.LUMBERJACK, Material.DIAMOND_AXE,
                ChatColor.GREEN + "🪓 Lumberjack Mode",
                "Chops down logs and fells",
                "the entire tree at once."));

        inv.setItem(12, modeButton(bot, BotMode.FARMING, Material.DIAMOND_HOE,
                ChatColor.YELLOW + "🌾 Farming Mode",
                "Harvests mature crops and",
                "instantly replants them."));

        inv.setItem(14, modeButton(bot, BotMode.COMBAT, Material.DIAMOND_SWORD,
                ChatColor.RED + "⚔ Combat Mode",
                "Actively hunts hostile",
                "mobs within range."));

        // ── Status display (centre) ───────────────────────────────
        int used  = bot.getBackpackUsed();
        String fillColor = bot.isBackpackFull() ? ChatColor.RED.toString()
                : used > 20 ? ChatColor.YELLOW.toString()
                : ChatColor.GREEN.toString();

        inv.setItem(13, makeItem(Material.NETHER_STAR,
                ChatColor.GOLD + "★ Bot Status",
                ChatColor.GRAY + "Mode: "     + ChatColor.WHITE + bot.getMode().name(),
                ChatColor.GRAY + "Backpack: " + fillColor + used + "/27 slots",
                ChatColor.GRAY + "Status: "   + ChatColor.WHITE + bot.getStatusLabel()));

        // ── Backpack viewer ───────────────────────────────────────
        inv.setItem(16, makeItem(Material.CHEST,
                ChatColor.GOLD + "🎒 View Backpack",
                ChatColor.GRAY + "Open the bot's virtual storage.",
                "",
                bot.isBackpackFull()
                        ? ChatColor.RED + "⚠ Backpack is FULL!"
                        : ChatColor.GREEN + "Click to open"));

        // ── Unload backpack ───────────────────────────────────────
        inv.setItem(25, makeItem(Material.HOPPER,
                ChatColor.GOLD + "📤 Unload Backpack",
                ChatColor.GRAY + "Transfer all bot items to your",
                ChatColor.GRAY + "inventory. Overflow drops at your feet.",
                "",
                ChatColor.YELLOW + "Click to unload"));

        // ── Stop / Idle ───────────────────────────────────────────
        inv.setItem(31, makeItem(Material.BARRIER,
                ChatColor.RED + "⏹ Set Idle",
                ChatColor.GRAY + "Pause all bot activity."));

        player.openInventory(inv);
    }

    public static void openBackpack(Player player, BotInstance bot) {
        Inventory inv = Bukkit.createInventory(null, 27, BACKPACK_TITLE);
        ItemStack[] backpack = bot.getBackpack();
        for (int i = 0; i < Math.min(backpack.length, 27); i++) {
            inv.setItem(i, backpack[i]);
        }
        player.openInventory(inv);
    }

    // ── Helpers ───────────────────────────────────────────────────

    private static ItemStack modeButton(BotInstance bot, BotMode mode, Material mat,
                                        String name, String... descLines) {
        boolean active = bot.getMode() == mode;
        String[] lore = Arrays.copyOf(descLines, descLines.length + 2);
        lore[descLines.length]     = "";
        lore[descLines.length + 1] = active
                ? ChatColor.GREEN + "▶ ACTIVE"
                : ChatColor.YELLOW + "Click to activate";
        return makeItem(mat, name, lore);
    }

    public static ItemStack makeItem(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta  = item.getItemMeta();
        meta.setDisplayName(name);
        if (lore.length > 0) {
            meta.setLore(Arrays.asList(lore));
        }
        item.setItemMeta(meta);
        return item;
    }
}
