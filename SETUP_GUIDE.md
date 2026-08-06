# EgoV1.0 — Setup Guide

## Requirements

| Requirement | Version |
|---|---|
| Paper / Purpur | 1.21.1+ |
| Java | 21+ |
| Citizens | 2.0.37+ |

---

## Installation

1. Drop **EgoV1.0.jar** into your `plugins/` folder.
2. Drop **Citizens.jar** into your `plugins/` folder (required dependency).
3. Start the server — `plugins/EgoV1.0/config.yml` is generated automatically.
4. *(Optional)* Add your Claude API key to enable the in-game chatbot (see below).

---

## Commands

| Command | Permission | Description |
|---|---|---|
| `/spawnbot` | `ego.spawn` | Spawn your Ego bot at your location |
| `/removebot` | `ego.spawn` | Remove your bot (returns all backpack items) |
| `/egoreload` | `ego.admin` | Reload config.yml without restarting |

---

## Bot Modes

Right-click your bot NPC to open the **Control Panel**.

| Mode | What it does |
|---|---|
| ⛏ Mining | Scans nearby for ores; tunnels through stone to find them |
| 🪓 Lumberjack | Fells entire trees (BFS upward from base log) |
| 🌾 Farming | Harvests mature crops and replants immediately |
| ⚔ Combat | Hunts hostile mobs within the combat scan radius |
| ⏹ Idle | Bot stands still; no actions taken |

The bot **always defends itself** against mobs regardless of mode (combat tick runs independently).

---

## Backpack

- The bot has a **27-slot virtual chest** (single chest).
- When full, the owner receives a chat notification *(throttled to once every 30 s)*.
- **Unload**: Click "📤 Unload Backpack" in the Control Panel, or use `/removebot`.  
  Items transfer to your inventory; overflow drops at your feet.
- Type **"come here"** in chat to teleport the bot to you.

---

## Chatbot (Claude AI)

Players can mention `@EgoBot` in chat to ask Minecraft questions.

**Setup:**
1. Get an API key from [console.anthropic.com](https://console.anthropic.com/).
2. Paste it into `config.yml` under `chatbot.api-key`.
3. Run `/egoreload`.

The bot maintains per-player conversation history (last 6 turns) for contextual follow-up questions. History clears when the player disconnects.

---

## Fake Players

EgoV1.0 can display inflated player counts and fake names to make your server look more active.

- **Server list**: Shows an inflated count + fake names in the hover tooltip before players join.
- **Tab list**: Injects fake player entries into the in-game tab list via NMS packets.

> **Note:** Tab-list injection uses NMS reflection. If your Paper version's internals change, it will fall back gracefully and log a warning — the rest of the plugin is unaffected.

To disable either feature, set `enabled: false` in the relevant section of `config.yml`.

---

## Configuration Highlights

```yaml
max-bots-per-player: 1        # Increase to allow multiple bots per player
spawn-permission: "ego.spawn" # Set to "" for no permission requirement
bot-name: "&bEgoBot"          # Supports & colour codes

ai:
  tick-interval: 40           # Main AI tick (ticks). 40 = 2 s
  combat-tick-interval: 10    # Combat tick (ticks). 10 = 0.5 s
  mining-scan-radius: 5       # Blocks to scan for ores
```

All ores, logs, and crops are fully configurable — add or remove entries freely.

---

## Building from Source

```bash
git clone <your-repo>
cd EgoV1.0
mvn package
# Output: target/EgoV1.0.jar
```
