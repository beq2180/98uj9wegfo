# LocalAI Minecraft Companion v0.3.0 — Fabric 1.21

This version reworks the prototype into a **player-like AI companion** rather than a husk with commands bolted on.

## What changed

- Custom entity rendered with Minecraft's vanilla **player model** and default player skin.
- Normal player proportions, 20 HP, held-item rendering, pathfinding, mining, crafting, and close-range fighting.
- The companion defaults to the configurable name **Chatty** (`agentName` in config).
- **Normal chat integration**: after spawning the companion, just type in Minecraft chat. No `/localai ask` or `/localai agent task` needed for ordinary conversation/instructions.
- Casual messages are answered conversationally.
- Owner instructions in normal chat become persistent goals.
- Other players can talk to it, but cannot replace the owner's task.
- When a requested task is complete, the model can return `finish_goal` and resume ordinary survival play.
- Background behavior is now framed as "play survival alongside me": gather resources, improve tools, explore locally, and defend itself.

## Basic use

1. Start Ollama and make sure your configured model (recommended `qwen3:8b`) exists.
2. Put the built mod JAR and Fabric API in your Fabric 1.21 mods folder.
3. Join a world and run once:

```text
/localai agent spawn
```

Then simply use chat:

```text
hey Chatty
can you grab some wood?
come over here
make yourself a stone pickaxe
watch out for that zombie
what are you doing?
```

Commands such as `/localai agent status`, `/localai agent pause`, and `/localai diagnose` still exist for debugging.

## Config

The config is `config/localai.json`. Useful v0.3 settings include:

```json
{
  "model": "qwen3:8b",
  "agentName": "Chatty",
  "agentAutonomousByDefault": true,
  "agentRespondToAllChat": true,
  "agentAllowNonOwnerConversation": true,
  "agentVerboseStatus": false
}
```

Set `agentRespondToAllChat` to `false` if you only want it to react when its name appears in a message.

## Current prototype limits

The visible body is a custom player-shaped living entity, not a real authenticated Minecraft account. Its inventory/crafting layer is still the prototype virtual inventory from v0.2. Crafting is still focused on early-game recipes, and advanced player systems such as hunger, armor management, containers, block placement, full vanilla recipe lookup, sleeping, portals, and persistent save data are not complete yet.

Those are the next pieces to move from "player-like companion" to a much more complete autonomous survival player.

## Build

Run `./build.command` on macOS, or use the included GitHub Actions workflow. Java 21 is required.
