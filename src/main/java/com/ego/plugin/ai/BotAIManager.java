package com.ego.plugin.ai;

import com.ego.plugin.EgoPlugin;
import com.ego.plugin.data.BotInstance;
import com.ego.plugin.data.BotMode;
import com.ego.plugin.holograms.HologramManager;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Drives all NPC behaviour.
 *
 * Improvements over SuperBot v1:
 *  - Ore list is cached from config (not rebuilt every scan tick)
 *  - Backpack saves are debounced via dirty flag
 *  - Lumberjack fells the whole tree (BFS upward from log)
 *  - saveTick flushes dirty bots every 5 seconds
 *  - notifyOwner throttled to once per 30 s to avoid spam
 */
public class BotAIManager {

    private final EgoPlugin plugin;
    private HologramManager hologramManager;
    private BukkitTask aiTask;
    private BukkitTask combatTask;
    private BukkitTask saveTick;

    // Cached from config – rebuilt on reload
    private Set<String> oreTypes    = new HashSet<>();
    private List<String> logTypes   = new ArrayList<>();
    private Set<String> cropTypes   = new HashSet<>();

    // Throttle: owner UUID → last notification timestamp (ms)
    private final Map<UUID, Long> lastNotifyTime = new HashMap<>();
    private static final long NOTIFY_COOLDOWN_MS = 30_000L;

    public BotAIManager(EgoPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        hologramManager = new HologramManager(plugin);
        rebuildCaches();

        int aiInterval     = plugin.getConfig().getInt("ai.tick-interval", 40);
        int combatInterval = plugin.getConfig().getInt("ai.combat-tick-interval", 10);

        // Main AI tick
        aiTask = new BukkitRunnable() {
            @Override public void run() {
                for (BotInstance bot : plugin.getDataManager().getAllBots()) {
                    if (!isNpcSpawned(bot)) continue;
                    tickBot(bot);
                }
            }
        }.runTaskTimer(plugin, aiInterval, aiInterval);

        // Combat tick (faster)
        combatTask = new BukkitRunnable() {
            @Override public void run() {
                for (BotInstance bot : plugin.getDataManager().getAllBots()) {
                    if (!isNpcSpawned(bot)) continue;
                    tickCombat(bot);
                }
            }
        }.runTaskTimer(plugin, combatInterval, combatInterval);

        // Debounced save tick: flush dirty bots every 5 s
        saveTick = new BukkitRunnable() {
            @Override public void run() {
                for (BotInstance bot : plugin.getDataManager().getAllBots()) {
                    plugin.getDataManager().saveBotDataIfDirty(bot);
                }
            }
        }.runTaskTimerAsynchronously(plugin, 100L, 100L);
    }

    public void stop() {
        if (aiTask     != null) aiTask.cancel();
        if (combatTask != null) combatTask.cancel();
        if (saveTick   != null) saveTick.cancel();
        if (hologramManager != null) hologramManager.removeAll();
    }

    /** Called by ReloadCommand after config is reloaded. */
    public void rebuildCaches() {
        oreTypes.clear();
        if (plugin.getConfig().getConfigurationSection("mining.ores") != null) {
            oreTypes.addAll(plugin.getConfig().getConfigurationSection("mining.ores").getKeys(false));
        }

        logTypes.clear();
        logTypes.addAll(plugin.getConfig().getStringList("lumber.logs"));

        cropTypes.clear();
        if (plugin.getConfig().getConfigurationSection("farming.crops") != null) {
            cropTypes.addAll(plugin.getConfig().getConfigurationSection("farming.crops").getKeys(false));
        }
    }

    // ── Utility ───────────────────────────────────────────────────

    private boolean isNpcSpawned(BotInstance bot) {
        NPC npc = bot.getNpc();
        return npc != null && npc.isSpawned() && npc.getEntity() != null;
    }

    private Entity getBotEntity(BotInstance bot) { return bot.getNpc().getEntity(); }
    private Location getBotLocation(BotInstance bot) { return getBotEntity(bot).getLocation(); }

    // ── Priority 1: Combat ────────────────────────────────────────

    private void tickCombat(BotInstance bot) {
        double radius = plugin.getConfig().getDouble("ai.combat-scan-radius", 6);
        Location loc  = getBotLocation(bot);

        LivingEntity target = null;
        for (Entity e : getBotEntity(bot).getNearbyEntities(radius, radius, radius)) {
            if (e instanceof Monster mob) { target = mob; break; }
        }

        if (target == null) return;

        faceEntity(bot, target);
        equipItem(bot, Material.DIAMOND_SWORD);
        swingArm(bot);

        double dmg = plugin.getConfig().getDouble("ai.combat-damage", 4.0);
        target.damage(dmg, getBotEntity(bot));

        bot.setStatusLabel("⚔ Combat!");
        updateHologram(bot);
    }

    // ── Priority 2: Gathering ─────────────────────────────────────

    private void tickBot(BotInstance bot) {
        if (bot.getMode() == BotMode.IDLE || bot.getMode() == BotMode.COMBAT) return;

        if (bot.isBackpackFull()) {
            bot.setStatusLabel("📦 Backpack Full!");
            updateHologram(bot);
            notifyOwnerThrottled(bot, "backpack-full");
            return;
        }

        boolean worked = switch (bot.getMode()) {
            case MINING    -> tickMining(bot);
            case LUMBERJACK -> tickLumberjack(bot);
            case FARMING   -> tickFarming(bot);
            default        -> false;
        };

        if (!worked) tickRoam(bot);
    }

    // ── Mining ────────────────────────────────────────────────────

    private boolean tickMining(BotInstance bot) {
        int radius   = plugin.getConfig().getInt("ai.mining-scan-radius", 5);
        Location loc = getBotLocation(bot);

        Block ore = scanForOre(loc, radius);
        if (ore == null) {
            // Tunnel through solid blocks in front
            Block front = getFrontBlock(bot);
            if (front != null && front.getType().isSolid() && !oreTypes.contains(front.getType().name())) {
                mineBlock(bot, front, Material.STONE, new ItemStack(Material.COBBLESTONE, 1));
                bot.setStatusLabel("⛏ Tunneling...");
                updateHologram(bot);
                return true;
            }
            return false;
        }

        faceBlock(bot, ore);
        equipItem(bot, Material.DIAMOND_PICKAXE);
        swingArm(bot);

        String blockKey = ore.getType().name();
        String dropKey  = plugin.getConfig().getString("mining.ores." + blockKey + ".drop", "COBBLESTONE");
        int    dropAmt  = plugin.getConfig().getInt("mining.ores." + blockKey + ".amount", 1);
        Material dropMat = Material.matchMaterial(dropKey);
        if (dropMat == null) dropMat = Material.COBBLESTONE;

        mineBlock(bot, ore, Material.STONE, new ItemStack(dropMat, dropAmt));
        bot.setStatusLabel("⛏ Mining " + formatName(ore.getType()) + "...");
        updateHologram(bot);
        return true;
    }

    private Block scanForOre(Location center, int radius) {
        World world = center.getWorld();
        int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();
        // Prioritise closer blocks
        for (int dist = 0; dist <= radius; dist++) {
            for (int x = -dist; x <= dist; x++) {
                for (int y = -dist; y <= dist; y++) {
                    for (int z = -dist; z <= dist; z++) {
                        if (Math.abs(x) != dist && Math.abs(y) != dist && Math.abs(z) != dist) continue;
                        Block b = world.getBlockAt(cx + x, cy + y, cz + z);
                        if (oreTypes.contains(b.getType().name())) return b;
                    }
                }
            }
        }
        return null;
    }

    // ── Lumberjack ────────────────────────────────────────────────

    private boolean tickLumberjack(BotInstance bot) {
        int radius   = plugin.getConfig().getInt("ai.lumber-scan-radius", 5);
        Location loc = getBotLocation(bot);

        Block log = scanForBlock(loc, radius, logTypes);
        if (log == null) return false;

        // Fell the whole tree via BFS upward
        List<Block> tree = collectTree(log);

        faceBlock(bot, log);
        equipItem(bot, Material.DIAMOND_AXE);
        swingArm(bot);

        for (Block b : tree) {
            mineBlock(bot, b, Material.AIR, new ItemStack(b.getType(), 1));
            if (bot.isBackpackFull()) break;
        }

        bot.setStatusLabel("🪓 Chopping " + formatName(log.getType()) + "...");
        updateHologram(bot);
        return true;
    }

    /**
     * BFS from the base log block upwards, collecting all connected log blocks.
     * Limits to 64 blocks to prevent runaway on huge custom trees.
     */
    private List<Block> collectTree(Block base) {
        List<Block> result = new ArrayList<>();
        Set<Location> visited = new HashSet<>();
        Queue<Block> queue = new LinkedList<>();
        queue.add(base);

        while (!queue.isEmpty() && result.size() < 64) {
            Block current = queue.poll();
            if (visited.contains(current.getLocation())) continue;
            visited.add(current.getLocation());

            if (!logTypes.contains(current.getType().name())) continue;
            result.add(current);

            // Check above, and diagonally above
            int[] offsets = {-1, 0, 1};
            for (int dx : offsets) {
                for (int dz : offsets) {
                    Block neighbour = current.getRelative(dx, 1, dz);
                    if (!visited.contains(neighbour.getLocation())) {
                        queue.add(neighbour);
                    }
                }
            }
        }
        return result;
    }

    // ── Farming ───────────────────────────────────────────────────

    private boolean tickFarming(BotInstance bot) {
        int radius   = plugin.getConfig().getInt("ai.farming-scan-radius", 4);
        Location loc = getBotLocation(bot);
        World world  = loc.getWorld();

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                Block b = world.getBlockAt(loc.getBlockX() + x, loc.getBlockY(), loc.getBlockZ() + z);
                String key = b.getType().name();
                if (!cropTypes.contains(key)) continue;
                if (!(b.getBlockData() instanceof Ageable ageable)) continue;

                int matureAge = plugin.getConfig().getInt("farming.crops." + key + ".mature-age", 7);
                if (ageable.getAge() < matureAge) continue;

                faceBlock(bot, b);
                equipItem(bot, Material.DIAMOND_HOE);
                swingArm(bot);

                String dropKey  = plugin.getConfig().getString("farming.crops." + key + ".drop", key);
                int    dropAmt  = plugin.getConfig().getInt("farming.crops." + key + ".drop-amount", 1);
                String seedKey  = plugin.getConfig().getString("farming.crops." + key + ".seed", key);
                int    seedAmt  = plugin.getConfig().getInt("farming.crops." + key + ".seed-amount", 1);

                Material dropMat = Material.matchMaterial(dropKey);
                Material seedMat = Material.matchMaterial(seedKey);

                if (dropMat != null) addToBackpackSafe(bot, new ItemStack(dropMat, dropAmt));
                if (seedMat != null) addToBackpackSafe(bot, new ItemStack(seedMat, seedAmt));

                // Replant: reset age to 0
                Ageable replant = (Ageable) b.getBlockData().clone();
                replant.setAge(0);
                b.setBlockData(replant);

                bot.setStatusLabel("🌾 Farming " + formatName(b.getType()) + "...");
                updateHologram(bot);
                return true;
            }
        }
        return false;
    }

    // ── Roaming & Hazard Detection ────────────────────────────────

    private void tickRoam(BotInstance bot) {
        String modeLabel = switch (bot.getMode()) {
            case MINING     -> "⛏ Searching...";
            case LUMBERJACK -> "🌲 Searching...";
            case FARMING    -> "🌾 Searching...";
            default         -> "🚶 Roaming...";
        };
        bot.setStatusLabel(modeLabel);
        updateHologram(bot);

        Entity entity = getBotEntity(bot);
        Location loc  = entity.getLocation();
        World world   = loc.getWorld();
        Vector dir    = loc.getDirection().setY(0).normalize();

        Location frontLoc     = loc.clone().add(dir);
        Block frontBlock      = world.getBlockAt(frontLoc);
        Block belowFrontBlock = world.getBlockAt(frontLoc.clone().subtract(0, 1, 0));

        boolean hazard = isHazardBlock(frontBlock.getType())
                || isHazardBlock(belowFrontBlock.getType())
                || belowFrontBlock.getType() == Material.AIR;

        if (hazard) {
            // Rotate ~90° and try again next tick
            float newYaw = loc.getYaw() + 90f + (float)(Math.random() * 45 - 22.5);
            loc.setYaw(newYaw);
            entity.teleport(loc);
            return;
        }

        double speed = plugin.getConfig().getDouble("ai.roam-speed", 0.25);
        entity.setVelocity(dir.clone().multiply(speed));
    }

    private boolean isHazardBlock(Material mat) {
        return mat == Material.LAVA || mat == Material.WATER
                || mat == Material.FIRE || mat == Material.MAGMA_BLOCK;
    }

    // ── Shared Helpers ────────────────────────────────────────────

    private Block scanForBlock(Location center, int radius, List<String> types) {
        World world = center.getWorld();
        int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Block b = world.getBlockAt(cx + x, cy + y, cz + z);
                    if (types.contains(b.getType().name())) return b;
                }
            }
        }
        return null;
    }

    private void mineBlock(BotInstance bot, Block block, Material replace, ItemStack drop) {
        block.getWorld().playEffect(block.getLocation(), Effect.STEP_SOUND, block.getType());
        block.setType(replace);
        addToBackpackSafe(bot, drop);
        navigateTo(bot, block.getLocation());
    }

    private void addToBackpackSafe(BotInstance bot, ItemStack item) {
        boolean added = bot.addToBackpack(item);
        if (!added) {
            notifyOwnerThrottled(bot, "backpack-full");
        }
        // Note: actual DB save is deferred to saveTick (every 5 s)
    }

    private void faceBlock(BotInstance bot, Block block) {
        if (!bot.getNpc().isSpawned()) return;
        Location botLoc   = getBotLocation(bot);
        Location blockLoc = block.getLocation().add(0.5, 0.5, 0.5);
        Vector dir        = blockLoc.toVector().subtract(botLoc.toVector()).normalize();
        bot.getNpc().getEntity().teleport(botLoc.setDirection(dir));
    }

    private void faceEntity(BotInstance bot, Entity target) {
        if (!bot.getNpc().isSpawned()) return;
        Location botLoc    = getBotLocation(bot);
        Location targetLoc = target.getLocation();
        Vector dir         = targetLoc.toVector().subtract(botLoc.toVector()).normalize();
        bot.getNpc().getEntity().teleport(botLoc.setDirection(dir));
    }

    private void equipItem(BotInstance bot, Material material) {
        Entity e = getBotEntity(bot);
        if (e instanceof LivingEntity le) {
            le.getEquipment().setItemInMainHand(new ItemStack(material));
        }
    }

    private void swingArm(BotInstance bot) {
        Entity e = getBotEntity(bot);
        e.getWorld().playEffect(e.getLocation(), Effect.CLICK1, 0);
        e.getWorld().playEffect(e.getLocation(), Effect.CLICK2, 0);
    }

    private Block getFrontBlock(BotInstance bot) {
        Entity e  = getBotEntity(bot);
        Location l = e.getLocation();
        return l.getWorld().getBlockAt(l.clone().add(l.getDirection().setY(0).normalize()));
    }

    private void navigateTo(BotInstance bot, Location target) {
        NPC npc = bot.getNpc();
        if (!npc.isSpawned()) return;
        try { npc.getNavigator().setTarget(target); } catch (Exception ignored) {}
    }

    private void updateHologram(BotInstance bot) {
        if (hologramManager != null) hologramManager.update(bot);
    }

    /** Sends a message to the bot owner at most once per {@link #NOTIFY_COOLDOWN_MS}. */
    private void notifyOwnerThrottled(BotInstance bot, String msgKey) {
        long now = System.currentTimeMillis();
        Long last = lastNotifyTime.get(bot.getOwnerUuid());
        if (last != null && now - last < NOTIFY_COOLDOWN_MS) return;
        lastNotifyTime.put(bot.getOwnerUuid(), now);

        org.bukkit.entity.Player owner = Bukkit.getPlayer(bot.getOwnerUuid());
        if (owner != null && owner.isOnline()) {
            owner.sendMessage(plugin.msg(msgKey));
        }
    }

    private String formatName(Material material) {
        return material.name().replace("_", " ").toLowerCase();
    }

    // ── Public API ────────────────────────────────────────────────

    public HologramManager getHologramManager() { return hologramManager; }
}
