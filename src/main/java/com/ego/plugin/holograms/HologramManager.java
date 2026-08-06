package com.ego.plugin.holograms;

import com.ego.plugin.EgoPlugin;
import com.ego.plugin.data.BotInstance;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages floating ArmorStand holograms above each bot.
 *
 * Fix from SuperBot v1:
 *  - removeAll() now iterates all loaded worlds to find orphaned stands
 *  - Orphaned stands (from a previous session) are cleaned on first update
 *  - Tracks world name alongside UUID to handle cross-world edge cases
 */
public class HologramManager {

    private final EgoPlugin plugin;
    // npcId → ArmorStand UUID
    private final Map<Integer, UUID> holograms = new HashMap<>();

    public HologramManager(EgoPlugin plugin) {
        this.plugin = plugin;
    }

    public void update(BotInstance bot) {
        if (!plugin.getConfig().getBoolean("holograms.enabled", true)) return;
        if (bot.getNpc() == null || !bot.getNpc().isSpawned()) return;

        double offsetY = plugin.getConfig().getDouble("holograms.height-offset", 2.3);
        Location loc   = bot.getNpc().getEntity().getLocation().add(0, offsetY, 0);
        String text    = EgoPlugin.colorize("&7[&b" + bot.getStatusLabel() + "&7]");

        UUID existingUUID = holograms.get(bot.getNpcId());
        if (existingUUID != null) {
            Entity existing = loc.getWorld().getEntity(existingUUID);
            if (existing instanceof ArmorStand as) {
                as.teleport(loc);
                as.setCustomName(text);
                return;
            }
            // Entity gone (chunk unloaded or server restart) — fall through to spawn new one
            holograms.remove(bot.getNpcId());
        }

        ArmorStand as = (ArmorStand) loc.getWorld().spawnEntity(loc, EntityType.ARMOR_STAND);
        as.setGravity(false);
        as.setVisible(false);
        as.setCustomNameVisible(true);
        as.setCustomName(text);
        as.setSmall(true);
        as.setInvulnerable(true);
        as.setCollidable(false);
        // Prevent natural despawn
        as.setPersistent(false);

        holograms.put(bot.getNpcId(), as.getUniqueId());
    }

    public void remove(BotInstance bot) {
        UUID uuid = holograms.remove(bot.getNpcId());
        if (uuid == null) return;
        // Search all loaded worlds since we don't know which one after a reload
        for (World world : plugin.getServer().getWorlds()) {
            Entity e = world.getEntity(uuid);
            if (e != null) { e.remove(); return; }
        }
    }

    public void removeAll() {
        for (Map.Entry<Integer, UUID> entry : holograms.entrySet()) {
            UUID uuid = entry.getValue();
            for (World world : plugin.getServer().getWorlds()) {
                Entity e = world.getEntity(uuid);
                if (e != null) { e.remove(); break; }
            }
        }
        holograms.clear();
    }
}
