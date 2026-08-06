package com.ego.plugin.chatbot;

import com.ego.plugin.EgoPlugin;
import com.ego.plugin.data.BotInstance;
import com.google.gson.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Listens for @EgoBot mentions in chat and replies via the Claude API.
 *
 * Improvements over SuperBot v1:
 *  - Uses Gson (shaded) for robust JSON parsing instead of hand-rolled regex
 *  - Per-player conversation history (last N turns kept in memory)
 *  - Async-safe: no main-thread blocking
 *  - Rate-limit guard: one in-flight request per player at a time
 */
public class BotChatResponder implements Listener {

    private final EgoPlugin plugin;
    private final Pattern mentionPattern;

    // Per-player conversation history: UUID → list of {role, content} maps
    private final ConcurrentHashMap<UUID, List<Map<String, String>>> histories = new ConcurrentHashMap<>();
    // Players currently waiting for a response (prevents double-sending)
    private final Set<UUID> pendingPlayers = ConcurrentHashMap.newKeySet();

    private static final int MAX_HISTORY_TURNS = 6; // 3 user + 3 assistant messages

    public BotChatResponder(EgoPlugin plugin) {
        this.plugin = plugin;
        String rawName = plugin.getConfig().getString("bot-name", "&bEgoBot")
                .replaceAll("&[0-9a-fA-FklmnorKLMNOR]", "");
        mentionPattern = Pattern.compile("@" + Pattern.quote(rawName), Pattern.CASE_INSENSITIVE);
    }

    /** Call after /egoreload to pick up a new bot name from config. */
    public void reload() {
        // Pattern is final; a new BotChatResponder instance is created on reload
        histories.clear();
        pendingPlayers.clear();
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        if (!plugin.getConfig().getBoolean("chatbot.enabled", true)) return;

        String message = event.getMessage();
        Player sender  = event.getPlayer();

        if (!mentionPattern.matcher(message).find()) return;

        // Find any active bot
        BotInstance bot = plugin.getDataManager().getAllBots().stream().findFirst().orElse(null);
        if (bot == null) return;
        if (bot.getNpc() == null || !bot.getNpc().isSpawned()) return;

        // One request per player at a time
        if (!pendingPlayers.add(sender.getUniqueId())) return;

        String question = mentionPattern.matcher(message).replaceAll("").trim();
        if (question.isEmpty()) question = "How can I help?";

        String finalQuestion = question;

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String response = askAI(sender.getUniqueId(), finalQuestion);
                if (response == null) return;

                String reply = response;
                plugin.getServer().getScheduler().runTask(plugin, () ->
                    Bukkit.broadcastMessage(EgoPlugin.colorize("&b[EgoBot] &f") + reply)
                );
            } finally {
                pendingPlayers.remove(sender.getUniqueId());
            }
        });
    }

    private String askAI(UUID playerUuid, String question) {
        String apiKey = plugin.getConfig().getString("chatbot.api-key", "");
        if (apiKey.isBlank()) {
            plugin.getLogger().warning("Chatbot API key not set in config.yml!");
            return null;
        }

        String model       = plugin.getConfig().getString("chatbot.model", "claude-haiku-4-5-20251001");
        int    maxTokens   = plugin.getConfig().getInt("chatbot.max-tokens", 200);
        String systemPrompt = plugin.getConfig().getString("chatbot.system-prompt",
                "You are EgoBot, a helpful Minecraft assistant. Answer Minecraft questions concisely in 1-3 sentences.");

        // Build conversation history
        List<Map<String, String>> history = histories.computeIfAbsent(playerUuid, k -> new ArrayList<>());
        history.add(Map.of("role", "user", "content", question));

        // Trim to last MAX_HISTORY_TURNS messages
        if (history.size() > MAX_HISTORY_TURNS) {
            history.subList(0, history.size() - MAX_HISTORY_TURNS).clear();
        }

        try {
            URL url = new URL("https://api.anthropic.com/v1/messages");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("x-api-key", apiKey);
            conn.setRequestProperty("anthropic-version", "2023-06-01");
            conn.setDoOutput(true);
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(12000);

            // Build request JSON with Gson
            Gson gson = new Gson();
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.addProperty("max_tokens", maxTokens);
            body.addProperty("system", systemPrompt);

            JsonArray messages = new JsonArray();
            for (Map<String, String> turn : history) {
                JsonObject msg = new JsonObject();
                msg.addProperty("role", turn.get("role"));
                msg.addProperty("content", turn.get("content"));
                messages.add(msg);
            }
            body.add("messages", messages);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(gson.toJson(body).getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }

            // Parse with Gson — robust against field ordering and escape sequences
            JsonObject responseJson = JsonParser.parseString(sb.toString()).getAsJsonObject();

            if (status >= 400) {
                String errType = responseJson.has("error")
                        ? responseJson.getAsJsonObject("error").get("type").getAsString()
                        : "unknown";
                plugin.getLogger().warning("Chatbot API error " + status + ": " + errType);
                return null;
            }

            String text = responseJson
                    .getAsJsonArray("content")
                    .get(0).getAsJsonObject()
                    .get("text").getAsString();

            // Append assistant reply to history
            history.add(Map.of("role", "assistant", "content", text));

            return text;

        } catch (Exception e) {
            plugin.getLogger().warning("Chatbot API exception: " + e.getMessage());
            return null;
        }
    }

    /** Clears conversation history for a player (e.g. on disconnect). */
    public void clearHistory(UUID playerUuid) {
        histories.remove(playerUuid);
    }
}
