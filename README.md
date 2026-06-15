# LeanCore

Server-side memory governor for Hytale. Cools idle map regions, trims load under heap pressure, and learns retention weights from how players actually play.

On a local/solo world it uses the **LITE** profile: adaptive view-radius, AFK chunk reclaim, and on-by-default learning — without the weight of STANDARD/FULL. When friends join the runtime scales to **STANDARD**; dedicated hosts use **FULL**.

[![CurseForge](https://img.shields.io/badge/CurseForge-LeanCore-orange)](https://www.curseforge.com/hytale/mods/leancore)
[![Documentation](https://img.shields.io/badge/Docs-DurkzPRG%20Mods-blue)](https://durkzprgmods.pages.dev/documentation/leancore)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

<p align="center">
  <img src="https://durkzprgmods.pages.dev/images/leancore-banner-800.png" alt="LeanCore server memory governor" width="672" />
</p>

## What it does

- Optional heap governor with COMFORT / WATCH / TIGHT / CRITICAL tiers and rollback
- Zone dormancy: WARM, DORMANT, FROZEN based on player proximity and idle time
- Per-player retention weights from activity and a learned demand model
- Activity Sense (1.2+): online classifier for mining, chopping, farming, building, crafting, combat
- Runtime profiles: `LITE` (solo), `STANDARD` (friends), `FULL` (dedicated)
- Staff tools: `/leancore` commands, heatmap, optional HUD

Does not touch client FPS or GPU. Primary metric is **server JVM heap**. Chunk load follows server view distance ([HytaleModding](https://hytalemodding.dev/en/docs/)); LeanCore acts through `setClientViewRadius` and `ChunkStore.remove(UNLOAD)`.

## Runtime profiles

| Players | Profile | Tick | Notes (1.5.0) |
|---------|---------|------|-----------------|
| 1 (solo local) | `LITE` | 30s (60s idle) | Lite governor, adaptive view, AFK unload, lite learning |
| 1 + `embeddedStandardProfile` | `STANDARD` | 15s | Dev dogfood of govern/learning without FULL |
| 2-8 (friends) | `STANDARD` | 15s | Classifier; govern/learning/HUD if enabled |
| 9+ (dense local) | `FULL` | 5s | Full runtime per config |
| Dedicated JVM | `FULL` | 5s | Full runtime per config |

Default: `localHostMode: "AUTO"`. Use `"PASSIVE"` to disable background ticks. Set `dedicatedServerMode: true` on a dedicated host.

Boot log: `LeanCore 1.5.0 setup (localHostMode=AUTO).` and `Runtime started profile=LITE` on solo.

### LITE profile (1.5.0)

Solo embedded gets a real memory governor without switching to STANDARD. STANDARD/FULL unchanged.

| Feature | Behavior |
|---------|----------|
| Adaptive view | 100% in COMFORT until chunk or heap pressure; gentle cuts in WATCH; aggressive only in TIGHT/CRITICAL |
| Dual signals | JVM heap tier + chunk pressure (`loaded / view budget`) |
| Unload AFK | FROZEN zones when idle; probe gate; no `governEnabled` required |
| Learning | `liteLearningEnabled=true` by default; demand shapes view cuts; no bandit in LITE |
| Tick budget | Heavy work on heap sample (~60s), world-thread dispatch |

STANDARD/FULL: view-radius and chunk unload still require `governEnabled` / `learningEnabled` / `unloadEnabled` as before.

## Install

1. Download **LeanCore-1.5.0.jar** from [CurseForge](https://www.curseforge.com/hytale/mods/leancore/files)
2. Put the JAR in your world's `mods/` folder (or `%AppData%\Hytale\UserData\Mods\` on Windows)
3. Config: `mods/durkz_LeanCore/LeanCore.json` (created on first boot with 1.5.0 defaults)
4. Run `/leancore probe` before enabling policy unload
5. Run `/leancore status` after about a minute

## Commands

Main command: `/leancore`

| Command | Who | Purpose |
|---------|-----|---------|
| `/leancore status` | Everyone | Profile, preset, heap tier, lite flags |
| `/leancore memory` | Everyone | Heap snapshot and tier |
| `/leancore savings` | Everyone | Session JVM heap, lite/standard governor state, zone/chunk actions |
| `/leancore zones` | Everyone | Dormancy counters |
| `/leancore learn` | Everyone | Learning store; LITE shows demand model only (no bandit) |
| `/leancore learn player` | Everyone | Your features, posterior, activity EMAs |
| `/leancore probe` | Everyone | API probe S1-S5 |
| `/leancore hud on\|off\|status` | HUD permission | Memory overlay |
| `/leancore heatmap [limit]` | Staff | Zone heatmap |
| `/leancore zone pin\|unpin\|pins` | Staff | Pin bases |

## Config (common keys)

File: `mods/durkz_LeanCore/LeanCore.json`

| Key | Default | Notes |
|-----|---------|-------|
| `localHostMode` | `AUTO` | `AUTO`, `PASSIVE`, or `FULL` |
| `embeddedStandardProfile` | `false` | Dev only; forces STANDARD on solo for dogfood |
| `governEnabled` | `false` | STANDARD/FULL governor |
| `learningEnabled` | `false` | STANDARD/FULL learning |
| `persistIntervalSeconds` | `300` | Learning flush interval (LITE uses `liteLearningEnabled`) |
| `learningMaxPersistedPlayers` | `512` | Prune oldest profiles on flush (`0` = unlimited) |
| `learningPlayerTtlDays` | `90` | Drop stale offline profiles (`0` = off) |
| `dedicatedServerMode` | `false` | Force FULL profile; allows view-radius on solo embedded |
| `dedicatedBootstrapEnabled` | `true` | One-time preset on first dedicated boot |
| `unloadEnabled` | `false` | STANDARD/FULL policy chunk unload (after `/leancore probe`) |
| `gcHintEnabled` | `false` | Experimental LITE idle GC nudge; metrics in `/leancore savings` |

### LITE keys (1.5.0)

| Key | Default | Notes |
|-----|---------|-------|
| `liteMemoryGovernorEnabled` | `true` | Solo governor (view + unload + demote) |
| `liteLearningEnabled` | `true` | Demand model + persistence; no bandit |
| `liteViewRadiusEnabled` | `true` | Adaptive view on embedded solo |
| `liteViewPressureThreshold` | `0.85` | COMFORT cap when chunk saturation high |
| `liteUnloadEnabled` | `true` | AFK reclaim; still needs probe |
| `liteUnloadIdleSeconds` | `180` | Idle before unload sweeps |

Learning snapshot: `mods/durkz_LeanCore/learning.state.gz` (schema v7, gzip binary). Legacy `learning.state` is migrated on first flush.

Permissions: `durkz.leancore.hud`, `durkz.leancore.admin`

Full reference: [documentation](https://durkzprgmods.pages.dev/documentation/leancore)

## Quick verify

1. Solo world: `/leancore status` shows `profile LITE`, `lite governor ON` in savings after ~60s
2. Mine ore, then `/leancore learn player` shows `MINER` and demand/viewScale
3. Friend joins → log shows `profile LITE -> STANDARD`

## Build

```bash
./gradlew build
```

Output: `build/libs/LeanCore-1.5.0.jar`

**Local deploy (DurkzPRG):** copy the built JAR to:

`%AppData%\Hytale\UserData\Mods\`

Example (Windows): `Copy-Item build\libs\LeanCore-1.5.0.jar $env:APPDATA\Hytale\UserData\Mods\`

## Links

- [Mod page](https://durkzprgmods.pages.dev/mods/leancore)
- [Docs](https://durkzprgmods.pages.dev/documentation/leancore)
- [GitHub](https://github.com/DurkzPRG/LeanCore)
- [CurseForge](https://www.curseforge.com/hytale/mods/leancore)

## License

MIT — see [LICENSE](LICENSE).
