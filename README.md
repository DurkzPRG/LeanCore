# LeanCore

Server-side memory governor for Hytale. Cools idle map regions, optionally trims load under heap pressure, and learns retention weights from how players actually play.

On a local/solo world it stays light (dormancy + heap tier, 30s tick). When friends join it scales up. Dedicated hosts get the full runtime.

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

Does not touch client FPS or GPU. Primary metric is **server JVM heap**.

## Runtime profiles

| Players | Profile | Tick | Notes |
|---------|---------|------|-------|
| 1 (solo local) | `LITE` | 30s (60s idle) | Dormancy + throttled heap sample |
| 2-8 (friends) | `STANDARD` | 15s | + classifier; govern/learning/HUD if enabled |
| 9+ (dense local) | `FULL` | 5s | Full runtime per config |
| Dedicated JVM | `FULL` | 5s | Full runtime per config |

Default: `localHostMode: "AUTO"`. Use `"PASSIVE"` to disable background ticks. Set `dedicatedServerMode: true` on a dedicated host.

Solo boot log: `LeanCore 1.3.0 setup (localHostMode=AUTO).` and `Runtime started profile=LITE`.

View-radius cuts never apply on embedded solo (1 player, not dedicated).

## Install

1. Download **LeanCore-1.3.0.jar** from [CurseForge](https://www.curseforge.com/hytale/mods/leancore/files)
2. Put the JAR in your world's `mods/` folder
3. Config: `mods/durkz_LeanCore/data/LeanCore.json`
4. Run `/leancore status` after about a minute

## Commands

Main command: `/leancore`

| Command | Who | Purpose |
|---------|-----|---------|
| `/leancore status` | Everyone | Profile, preset, heap tier |
| `/leancore memory` | Everyone | Heap snapshot and tier |
| `/leancore savings` | Everyone | Session JVM heap peak/baseline, governor state, and cumulative zone/chunk actions (measured vs estimated) |
| `/leancore zones` | Everyone | Dormancy counters |
| `/leancore learn` | Everyone | Bandit, quantiles, demand model |
| `/leancore learn player` | Everyone | Your features, posterior, activity EMAs |
| `/leancore probe` | Everyone | API probe S1-S5 |
| `/leancore hud on\|off\|status` | HUD permission | Memory overlay |
| `/leancore heatmap [limit]` | Staff | Zone heatmap |
| `/leancore zone pin\|unpin\|pins` | Staff | Pin bases |

## Config (common keys)

File: `mods/durkz_LeanCore/data/LeanCore.json`

| Key | Default | Notes |
|-----|---------|-------|
| `localHostMode` | `AUTO` | `AUTO`, `PASSIVE`, or `FULL` |
| `governEnabled` | `false` | Enable on dedicated after baseline |
| `learningEnabled` | `false` | Policy learning + disk persistence |
| `persistIntervalSeconds` | `300` | Learning flush interval |
| `dedicatedServerMode` | `false` | Force FULL profile |
| `dedicatedBootstrapEnabled` | `true` | One-time preset on first dedicated boot: enables govern, view-radius, learning |

Learning snapshot: `mods/durkz_LeanCore/data/learning.state` (schema v5)

Permissions: `durkz.leancore.hud`, `durkz.leancore.admin`

Full reference: [documentation](https://durkzprgmods.pages.dev/documentation/leancore)

## Quick verify

1. Mine ore with a pickaxe, then `/leancore learn player` → `MINER`, not `FIGHTER`
2. Switch to hatchet, chop 6+ logs → posterior should move to `LUMBERJACK`
3. Friend joins → log shows `profile LITE -> STANDARD`

## Build

```bash
./gradlew build
```

Output: `build/libs/LeanCore-1.3.0.jar`

## Links

- [Mod page](https://durkzprgmods.pages.dev/mods/leancore)
- [Docs](https://durkzprgmods.pages.dev/documentation/leancore)
- [CurseForge](https://www.curseforge.com/hytale/mods/leancore)
- [Issues](https://durkzprgmods.pages.dev/issues)

## License

MIT. Copyright (c) 2026 DurkzPRG
