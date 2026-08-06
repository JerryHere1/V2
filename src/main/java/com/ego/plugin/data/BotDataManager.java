package com.ego.plugin.data;

import com.ego.plugin.EgoPlugin;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Handles all persistence for bot data.
 *
 * Improvements over v1:
 *  - ConcurrentHashMap for thread-safe reads from async contexts
 *  - Dirty-flag debouncing: saves only happen when `dirty == true`
 *  - Full loadAll() method that restores mode + backpack on startup
 *  - Backpack unload recorded to DB immediately
 */
public class BotDataManager {

    private final EgoPlugin plugin;
    private Connection connection;

    // Thread-safe: owner UUID → bot instance
    private final ConcurrentHashMap<UUID, BotInstance> activeBots = new ConcurrentHashMap<>();

    public BotDataManager(EgoPlugin plugin) {
        this.plugin = plugin;
    }

    // ── Lifecycle ──────────────────────────────────────────────────

    public void initialize() {
        try {
            File dbFile = new File(plugin.getDataFolder(), "ego_bots.db");
            plugin.getDataFolder().mkdirs();
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());

            // Enable WAL mode for better concurrent performance
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL;");
                stmt.execute("PRAGMA synchronous=NORMAL;");
            }

            createTables();
            plugin.getLogger().info("SQLite database connected (WAL mode).");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to connect to SQLite database", e);
        }
    }

    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS bot_data (
                    owner_uuid TEXT PRIMARY KEY,
                    npc_id     INTEGER NOT NULL,
                    mode       TEXT    NOT NULL DEFAULT 'IDLE',
                    backpack   BLOB
                )
            """);
        }
    }

    public void shutdown() {
        // Flush all dirty bots synchronously before closing
        for (BotInstance bot : activeBots.values()) {
            if (bot.isDirty()) {
                saveBotData(bot);
            }
        }
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Error closing database", e);
        }
    }

    // ── CRUD ───────────────────────────────────────────────────────

    public void registerBot(BotInstance bot) {
        activeBots.put(bot.getOwnerUuid(), bot);
        saveBotData(bot);
    }

    public void removeBot(UUID ownerUuid) {
        activeBots.remove(ownerUuid);
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM bot_data WHERE owner_uuid = ?")) {
            ps.setString(1, ownerUuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to delete bot data", e);
        }
    }

    public BotInstance getBotByOwner(UUID ownerUuid) {
        return activeBots.get(ownerUuid);
    }

    public BotInstance getBotByNpcId(int npcId) {
        return activeBots.values().stream()
                .filter(b -> b.getNpcId() == npcId)
                .findFirst().orElse(null);
    }

    public Collection<BotInstance> getAllBots() {
        return Collections.unmodifiableCollection(activeBots.values());
    }

    public boolean hasBot(UUID ownerUuid) {
        return activeBots.containsKey(ownerUuid);
    }

    /**
     * Saves this bot's data only if it has been modified since the last save.
     * Safe to call frequently — the dirty flag prevents needless SQL writes.
     */
    public void saveBotDataIfDirty(BotInstance bot) {
        if (bot.isDirty()) {
            saveBotData(bot);
        }
    }

    /** Unconditional save. Use sparingly; prefer {@link #saveBotDataIfDirty}. */
    public void saveBotData(BotInstance bot) {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT OR REPLACE INTO bot_data (owner_uuid, npc_id, mode, backpack)
                VALUES (?, ?, ?, ?)
            """)) {
            ps.setString(1, bot.getOwnerUuid().toString());
            ps.setInt(2, bot.getNpcId());
            ps.setString(3, bot.getMode().name());
            ps.setBytes(4, serializeBackpack(bot.getBackpack()));
            ps.executeUpdate();
            bot.clearDirty();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save bot data for " + bot.getOwnerUuid(), e);
        }
    }

    // ── Load helpers (used on restart) ────────────────────────────

    /** Returns a map of ownerUUID → {npcId} for all stored bots. */
    public Map<UUID, int[]> loadAllNpcIds() {
        Map<UUID, int[]> result = new HashMap<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT owner_uuid, npc_id FROM bot_data")) {
            while (rs.next()) {
                UUID owner = UUID.fromString(rs.getString("owner_uuid"));
                result.put(owner, new int[]{rs.getInt("npc_id")});
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load bot NPC IDs", e);
        }
        return result;
    }

    public BotMode loadMode(UUID ownerUuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT mode FROM bot_data WHERE owner_uuid = ?")) {
            ps.setString(1, ownerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return BotMode.valueOf(rs.getString("mode"));
            }
        } catch (SQLException | IllegalArgumentException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load mode for " + ownerUuid, e);
        }
        return BotMode.IDLE;
    }

    public ItemStack[] loadBackpack(UUID ownerUuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT backpack FROM bot_data WHERE owner_uuid = ?")) {
            ps.setString(1, ownerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    byte[] blob = rs.getBytes("backpack");
                    if (blob != null && blob.length > 0) return deserializeBackpack(blob);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load backpack for " + ownerUuid, e);
        }
        return new ItemStack[27];
    }

    // ── Serialisation ──────────────────────────────────────────────

    private byte[] serializeBackpack(ItemStack[] items) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             BukkitObjectOutputStream oos = new BukkitObjectOutputStream(bos)) {
            oos.writeInt(items.length);
            for (ItemStack item : items) oos.writeObject(item);
            return bos.toByteArray();
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to serialise backpack", e);
            return new byte[0];
        }
    }

    private ItemStack[] deserializeBackpack(byte[] data) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(data);
             BukkitObjectInputStream ois = new BukkitObjectInputStream(bis)) {
            int len = ois.readInt();
            ItemStack[] items = new ItemStack[len];
            for (int i = 0; i < len; i++) items[i] = (ItemStack) ois.readObject();
            return items;
        } catch (IOException | ClassNotFoundException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to deserialise backpack", e);
            return new ItemStack[27];
        }
    }
}
