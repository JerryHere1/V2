package com.ego.plugin.fakeplayers;

import com.ego.plugin.EgoPlugin;
import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.UUID;

public class FakeServerListPing implements Listener {

    private final EgoPlugin plugin;

    public FakeServerListPing(EgoPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onServerListPing(PaperServerListPingEvent event) {
        // ── Inflate player count ───────────────────────────────────
        if (plugin.getConfig().getBoolean("fake-players.server-list.enabled", true)) {
            int real      = event.getNumPlayers();
            int extra     = plugin.getConfig().getInt("fake-players.server-list.extra-count", 25);
            int maxPlayers = plugin.getConfig().getInt("fake-players.server-list.max-players", 100);
            event.setNumPlayers(real + extra);
            event.setMaxPlayers(maxPlayers);
        }

        // ── Inject fake names in hover tooltip ────────────────────
        if (plugin.getConfig().getBoolean("fake-players.server-list.show-names-in-hover", true)) {
            for (String name : plugin.getFakePlayerManager().getFakeNames()) {
                if (event.getPlayerSample().size() >= 5) break;
                com.destroystokyo.paper.profile.PlayerProfile profile =
                        plugin.getServer().createProfile(
                                UUID.nameUUIDFromBytes(("FakePlayer:" + name).getBytes()), name);
                event.getPlayerSample().add(profile);
            }
        }
    }
}
