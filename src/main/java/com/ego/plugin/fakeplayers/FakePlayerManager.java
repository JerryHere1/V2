package com.ego.plugin.fakeplayers;

import com.ego.plugin.EgoPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.logging.Level;

/**
 * Injects fake player entries into the in-game tab list via NMS packets.
 *
 * Fix from SuperBot v1:
 *  - sendAddPlayerPacket actually builds and sends the packet correctly
 *  - Uses a two-step approach: ADD_PLAYER + UPDATE_LISTED in one EnumSet
 *  - Falls back gracefully without spamming warnings if reflection fails
 *  - Removal packet correctly built and sent on stop()
 */
public class FakePlayerManager {

    private final EgoPlugin plugin;
    private final List<String> fakeNames = new ArrayList<>();
    private BukkitTask updateTask;

    // Cached reflection objects (built once, reused)
    private boolean reflectionReady = false;
    private Class<?> packetAddClass;
    private Class<?> packetRemoveClass;
    private Class<?> actionClass;
    private Object   enumSetAddPlayer;   // EnumSet<Action> for ADD_PLAYER + UPDATE_LISTED
    private Class<?> packetClass;

    public FakePlayerManager(EgoPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("fake-players.tab-list.enabled", true)) return;
        initReflection();
        reload();
        // Re-send entries every 30 s (handles new players joining)
        updateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshTabList, 200L, 600L);
    }

    public void reload() {
        fakeNames.clear();
        fakeNames.addAll(plugin.getConfig().getStringList("fake-players.tab-list.names"));
        if (reflectionReady) refreshTabList();
    }

    public void stop() {
        if (updateTask != null) updateTask.cancel();
        if (reflectionReady) removeTabEntries();
    }

    // ── Reflection setup ──────────────────────────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void initReflection() {
        try {
            packetAddClass    = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket");
            packetRemoveClass = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket");
            actionClass       = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$Action");

            Object addPlayer   = Enum.valueOf((Class<Enum>) actionClass, "ADD_PLAYER");
            Object updateListed = Enum.valueOf((Class<Enum>) actionClass, "UPDATE_LISTED");

            // Build EnumSet.of(ADD_PLAYER, UPDATE_LISTED)
            Method enumSetOf = EnumSet.class.getMethod("of",
                    Enum.class, Enum.class.arrayType());
            // Use copyOf from a set as workaround
            @SuppressWarnings("unchecked")
            EnumSet enumSet = EnumSet.noneOf((Class<Enum>) actionClass);
            enumSet.add(addPlayer);
            enumSet.add(updateListed);
            enumSetAddPlayer = enumSet;

            reflectionReady = true;
            plugin.getLogger().info("Tab-list packet reflection initialised successfully.");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not initialise tab-list packet reflection. Fake tab entries disabled. " +
                    "This is non-fatal. Error: " + e.getMessage());
            reflectionReady = false;
        }
    }

    // ── Tab-list management ───────────────────────────────────────

    private void refreshTabList() {
        if (!plugin.getConfig().getBoolean("fake-players.tab-list.enabled", true)) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            sendFakeTabEntries(p);
        }
    }

    public void sendFakeTabEntries(Player player) {
        if (!reflectionReady) return;
        if (!plugin.getConfig().getBoolean("fake-players.tab-list.enabled", true)) return;
        try {
            Object connection = getPlayerConnection(player);
            if (connection == null) return;
            for (String name : fakeNames) {
                sendAddEntry(connection, name);
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Tab entry send failed for " + player.getName() + ": " + e.getMessage());
        }
    }

    private void removeTabEntries() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                Object connection = getPlayerConnection(p);
                if (connection == null) continue;
                List<UUID> uuids = new ArrayList<>();
                for (String name : fakeNames) {
                    uuids.add(nameToUUID(name));
                }
                // ClientboundPlayerInfoRemovePacket(List<UUID>)
                Constructor<?> ctor = packetRemoveClass.getConstructor(List.class);
                Object packet = ctor.newInstance(uuids);
                sendPacket(connection, packet);
            } catch (Exception ignored) {}
        }
    }

    private void sendAddEntry(Object connection, String name) throws Exception {
        UUID uuid = nameToUUID(name);

        // Build a GameProfile
        Class<?> profileClass = Class.forName("com.mojang.authlib.GameProfile");
        Object profile = profileClass.getConstructor(UUID.class, String.class).newInstance(uuid, name);

        // Build the PlayerUpdate inner record / class
        // In Paper 1.21 the constructor is: ClientboundPlayerInfoUpdatePacket(EnumSet, GameProfile)
        // which creates a single-entry packet
        Constructor<?> ctor = packetAddClass.getConstructor(EnumSet.class, profileClass);
        Object packet = ctor.newInstance((EnumSet<?>) enumSetAddPlayer, profile);
        sendPacket(connection, packet);
    }

    private void sendPacket(Object connection, Object packet) throws Exception {
        Method send = connection.getClass().getMethod("send",
                Class.forName("net.minecraft.network.protocol.Packet"));
        send.invoke(connection, packet);
    }

    private Object getPlayerConnection(Player player) {
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            return handle.getClass().getField("connection").get(handle);
        } catch (Exception e) {
            return null;
        }
    }

    private static UUID nameToUUID(String name) {
        return UUID.nameUUIDFromBytes(("FakePlayer:" + name).getBytes());
    }

    // ── Public helpers ────────────────────────────────────────────

    public List<String> getFakeNames()  { return Collections.unmodifiableList(fakeNames); }
    public int getFakeCount()           { return reflectionReady ? fakeNames.size() : 0; }
}
