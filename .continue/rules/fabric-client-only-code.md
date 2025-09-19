---
description: Ensure client-only references don't load on dedicated servers.
---

Place client-only code (MinecraftClient and rendering HUD, client events) under src/client/java and guard against null client player to avoid classloading issues.