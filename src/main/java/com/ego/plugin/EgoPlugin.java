package com.ego.plugin;

import com.ego.plugin.ai.BotAIManager;
import com.ego.plugin.chatbot.BotChatResponder;
import com.ego.plugin.commands.RemoveBotCommand;
import com.ego.plugin.commands.ReloadCommand;
import com.ego.plugin.commands.SpawnBotCommand;
import com.ego.plugin.data.BotDataManager;
import com.ego.plugin.fakeplayers.FakePlayerListener;
import com.ego.plugin.fakeplayers.FakePlayerManager;
import com.ego.plugin.fakeplayers.FakeServerListPing;
import com.ego.plugin.listeners.ChatListener;
import com.ego.plugin.listeners.GuiListener;
import com.ego.plugin.listeners.NpcListener;
import org.bukkit.plugin.java.JavaPlugin;

public final class EgoPlugin extends JavaPlugin {

    private static EgoPlugin instance;
    private BotDataManager dataManager;
    private BotAIManager aiManager;
    private FakePlayerManager fakePlayerManager;
    private BotChatResponder chatResponder;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();

        if (getServer().getPluginManager().getPlugin("Citizens") == null) {
            getLogger().severe("Citizens plugin not found! EgoV1.0 requires Citizens. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Data layer
        dataManager = new BotDataManager(this);
        dataManager.initialize();

        // AI brain
        aiManager = new BotAIManager(this);
        aiManager.start();

        // Fake players
        fakePlayerManager = new FakePlayerManager(this);
        fakePlayerManager.start();

        // Commands
        getCommand("spawnbot").setExecutor(new SpawnBotCommand(this));
        getCommand("removebot").setExecutor(new RemoveBotCommand(this));
        getCommand("egoreload").setExecutor(new ReloadCommand(this));

        // Listeners
        chatResponder = new BotChatResponder(this);
        getServer().getPluginManager().registerEvents(new ChatListener(this), this);
        getServer().getPluginManager().registerEvents(new GuiListener(this), this);
        getServer().getPluginManager().registerEvents(new NpcListener(this), this);
        getServer().getPluginManager().registerEvents(new FakePlayerListener(this), this);
        getServer().getPluginManager().registerEvents(new FakeServerListPing(this), this);
        getServer().getPluginManager().registerEvents(chatResponder, this);

        getLogger().info("EgoV1.0 enabled! Running on Paper " + getServer().getVersion());
    }

    @Override
    public void onDisable() {
        if (aiManager != null) aiManager.stop();
        if (fakePlayerManager != null) fakePlayerManager.stop();
        if (dataManager != null) dataManager.shutdown();
        getLogger().info("EgoV1.0 disabled cleanly.");
    }

    // ── Accessors ──────────────────────────────────────────────────

    public static EgoPlugin getInstance() { return instance; }
    public BotDataManager getDataManager()         { return dataManager; }
    public BotAIManager getAiManager()             { return aiManager; }
    public FakePlayerManager getFakePlayerManager() { return fakePlayerManager; }
    public BotChatResponder getChatResponder()      { return chatResponder; }

    // ── Message helper ─────────────────────────────────────────────

    /** Returns a colorised, prefix-prepended message from config. */
    public String msg(String key, String... replacements) {
        String prefix = getConfig().getString("messages.prefix", "&8[&bEgo&8] ");
        String value  = getConfig().getString("messages." + key, key);
        String m      = colorize(prefix + value);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            m = m.replace(replacements[i], replacements[i + 1]);
        }
        return m;
    }

    public static String colorize(String s) {
        return s == null ? "" : s.replace("&", "\u00A7");
    }
}
