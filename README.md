# LeanCore

LeanCore reduces JVM heap pressure through spatial dormancy, per-player retention budgets, and an adaptive governor that learns from your server's own heap history — not a one-size-fits-all RAM slider.

On **embedded local worlds**, it runs a **light profile** (dormancy + heap tier) so it does not compete with the game client. When **friends join**, it scales up. On **dedicated hosts**, you get the full runtime.

[![CurseForge](https://img.shields.io/badge/CurseForge-LeanCore-orange)](https://www.curseforge.com/hytale/mods/leancore)
[![Documentation](https://img.shields.io/badge/Docs-DurkzPRG%20Mods-blue)](https://durkzprgmods.pages.dev/documentation/leancore)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

<p align="center">
  <img src="https://durkzprgmods.pages.dev/images/leancore-banner-800.png" alt="LeanCore — server memory governor" width="672" />
</p>

---

## What LeanCore is

- A **memory governor** (optional) that reads heap pressure and applies tiered policies
- **Spatial dormancy**: inactive map regions cool down (WARM → DORMANT → FROZEN)
- **Per-player demand**: explorers and builders weighted by live activity and a learned demand model
- **Activity Sense (1.2)**: online ML classifier for mining, chopping, farming, building, crafting, and combat
- **Scaled runtime**: `LITE` solo → `STANDARD` friends → `FULL` dedicated
- **Staff diagnostics**: commands, heatmap, optional admin HUD

---

## Embedded host profiles (1.2.0)

| Players | Profile | Tick | What runs |
|---------|---------|------|-----------|
| 1 (solo local) | `LITE` | 30s (60s idle) | Motion-gated dormancy + throttled heap tier |
| 2–8 (friends) | `STANDARD` | 15s | + classifier; govern/learning/HUD if enabled in config |
| 9+ (dense local) | `FULL` | 5s | Full runtime per config |
| Dedicated JVM | `FULL` | 5s | Full runtime per config |

Config: `localHostMode: "AUTO"` (default). Use `"PASSIVE"` to disable background runtime entirely. Set `dedicatedServerMode: true` on a dedicated JVM host.

Boot log (solo): `LeanCore 1.2.0 setup (localHostMode=AUTO).` and `Runtime started profile=LITE`. Friend joins → `profile LITE -> STANDARD`.

View-radius governance never applies on embedded solo (1 player + not dedicated).

---

## Main features

| Area | What it does |
|------|----------------|
| **Runtime profiles** | `LITE` (30s tick, dormancy only), `STANDARD` (friends), `FULL` (dedicated) |
| **Memory tiers** | COMFORT, WATCH, TIGHT, CRITICAL with hysteresis and rollback |
| **Zone dormancy** | HOT near players; idle wilderness demotes over configurable timers |
| **Retention allocator** | Global memory budget with per-player footprints from demand scores |
| **Policy applier** | Throttled view-radius adjustments on **dedicated** hosts when enabled |
| **Chunk unload** | Removes chunks in frozen/dormant zones when tiers require it |
| **Learning store** | Rolling heap windows (60s / 15m / 24h), policy bandit, false-cut tracking; persists across restarts |
| **Demand model** | `OnlineLinearDemandModel`, feature schema **v2** (`demandDim=11`) |
| **Activity Sense (1.2)** | Online softmax classifier: MINER, LUMBERJACK, FARMER, BUILDER, FIGHTER, EXPLORER |
| **Block context** | Pickaxe on ore → mine; axe on wood → chop; combat from damage events only |
| **Regional probe** | S4 entity counts per zone in `/leancore probe` |
| **Unload counters** | Policy sweeps vs engine unloads in `/leancore learn` |
| **Holdout (10%)** | Bandit learns on treatment players; holdout skips view-radius cuts; cohort heap in `/leancore learn` |
| **Behavior posterior** | Soft playstyle scores from ML + activity EMAs (see `/leancore learn player`) |
| **S4 in bandit** | Regional entity pressure in policy context (throttled sample) |
| **Presets** | AUTO → SOLO_LEAN, FRIENDS_NIGHT, or SERVER_DENSE from online player count |
| **Admin HUD** | `/leancore hud on` — compact heap/tier overlay (**off by default**) |
| **Heatmap** | `/leancore heatmap [limit]` — zone state summary for staff |
| **Zone pin** | `/leancore zone pin\|unpin\|pins` — protect bases from demote/unload |
| **Webhook** | Optional `criticalWebhookUrl` posts generic JSON on CRITICAL tier (off by default) |
| **LeanCoreAPI** | Tier, zone pin, player snapshots for other mods |
| **Asset pack** | Bundled server HUD `.ui` — no client mod required |

---

## Installation

1. Download **LeanCore-1.2.0.jar** from [CurseForge](https://www.curseforge.com/hytale/mods/leancore/files)
2. Place the JAR in your world's **`mods/`** folder
3. Start — config is created at **`mods/durkz_LeanCore/data/LeanCore.json`**
4. Solo boot log should show `localHostMode=AUTO` and `Runtime started profile=LITE`
5. Run `/leancore status` after ~1 minute
6. Mine with a pickaxe, then `/leancore learn player` — expect `MINER` in posterior, not `FIGHTER`

**Singleplayer / local host:** default `localHostMode: "AUTO"` keeps overhead low. Grant HUD/admin via `hudViewerGroups` / `hudAdminGroups` or `/perm`.

---

## Commands

Main command: **`/leancore`**

| Command | Access | Description |
|---------|--------|-------------|
| `/leancore status` | Everyone | Profile, preset, heap tier, learning summary |
| `/leancore memory` | Everyone | Heap snapshot, tier, footprint, unload stats |
| `/leancore zones` | Everyone | Dormancy map counters |
| `/leancore learn` | Everyone | Learning diagnostics (bandit, quantiles, demand model, unload stats) |
| `/leancore learn player` | Everyone | Demand features, ML posterior, activity EMAs, holdout cohort |
| `/leancore probe` | Everyone | API capability probe (S1–S5), including regional S4 entity counts |
| `/leancore hud on\|off\|status` | HUD viewers | Opt-in memory HUD overlay |
| `/leancore heatmap [limit]` | Staff | Zone heatmap |
| `/leancore zone pin\|unpin\|pins` | Staff | Pin zones to prevent demote/unload |

```
/leancore status
/leancore memory
/leancore zones
/leancore learn
/leancore learn player
/leancore probe
/leancore hud on|off|status
/leancore heatmap [limit]
/leancore zone pin|unpin|pins
```

---

## Configuration & Permissions

**Runtime config:** `mods/durkz_LeanCore/data/LeanCore.json`

| Key | Default | Description |
|-----|---------|-------------|
| `enabled` | `true` | Master enable |
| `localHostMode` | `AUTO` | `AUTO`, `PASSIVE`, or `FULL` on embedded host |
| `runtimeInitialDelaySeconds` | `30` | Delay before first background tick |
| `soloTickIntervalSeconds` | `30` | LITE profile interval (1 player) |
| `soloIdleTickIntervalSeconds` | `60` | LITE tick when idle (adaptive) |
| `soloHeapSampleIntervalSeconds` | `60` | LITE heap/tier sample interval |
| `soloDormancyMotionBlocks` | `8` | Min movement before dormancy rebuild |
| `soloAdaptiveTickEnabled` | `true` | Stretch tick when solo player idle |
| `regionalPressureIntervalSeconds` | `60` | S4 bandit context sample interval |
| `friendsTickIntervalSeconds` | `15` | STANDARD profile interval (2+ players) |
| `governEnabled` | `false` | Apply governor policies (enable on dedicated after baseline) |
| `viewRadiusGovernanceEnabled` | `false` | Server view-radius cuts (dedicated only; never solo embedded) |
| `learningEnabled` | `false` | In-process policy learning |
| `hudFeatureEnabled` | `false` | HUD feature available |
| `chunkUnloadEventTracking` | `false` | Engine unload listener (off by default) |
| `preset` | `AUTO` | `AUTO`, `SOLO_LEAN`, `FRIENDS_NIGHT`, `SERVER_DENSE` |
| `dedicatedServerMode` | `false` | Force FULL profile + SERVER preset behavior |
| `friendsMaxPlayers` | `8` | FRIENDS band upper bound |
| `serverDensePlayerThreshold` | `9` | SERVER preset from this count |
| `memoryBudgetMb` | `0` | Global retention cap (`0` = auto share of heap) |
| `watchHeapRatio` | `0.70` | Fixed tier threshold before quantiles warm up |
| `tightHeapRatio` | `0.82` | Fixed tier threshold |
| `criticalHeapRatio` | `0.90` | Fixed tier threshold |
| `dormantAfterMinutes` | `8` | WARM → DORMANT idle time |
| `frozenAfterMinutes` | `20` | → FROZEN idle time |
| `hudViewerGroups` | `OP`, `Admin` | Groups that may toggle HUD |
| `hudAdminGroups` | `OP`, `Admin` | Groups for heatmap and zone pin |
| `hudUpdateIntervalSeconds` | `3` | HUD refresh interval |
| `heatmapDefaultLimit` | `24` | Default heatmap rows |
| `zonePinMaxCount` | `16` | Max pinned zones |
| `criticalWebhookUrl` | `""` | Optional CRITICAL webhook (generic JSON) |
| `criticalWebhookCooldownSeconds` | `300` | Webhook cooldown |

**Other data files:**

- `mods/durkz_LeanCore/data/learning.state` — learning snapshot (schema **v4**)
- `mods/durkz_LeanCore/data/hud.state` — per-player HUD toggles

LeanCore uses **permission nodes** and/or **config group lists**. Either path grants access.

| Permission | Description |
|------------|-------------|
| `durkz.leancore.hud` | Toggle and view the opt-in memory HUD |
| `durkz.leancore.admin` | Heatmap, zone pin/unpin/pins, and admin HUD tools |

### Quick setup with `/perm`

```text
/perm group add OP durkz.leancore.admin
```

HUD only (no heatmap / zone pin):

```text
/perm group add Moderator durkz.leancore.hud
```

Full guide: **[documentation](https://durkzprgmods.pages.dev/documentation/leancore)**

---

## Recommended usage

| Host type | Setup |
|-----------|--------|
| **Solo / local world** | `localHostMode: AUTO`, governor off — dormancy only, minimal overhead |
| **Friends co-op (local)** | `AUTO` — profile becomes `STANDARD` when 2+ players join |
| **Dedicated server** | `dedicatedServerMode: true`, then enable `governEnabled` after `/leancore memory` baseline |

Primary KPI is **server JVM heap**, not client FPS.

### Verifying it works

1. Boot log: `LeanCore 1.2.0 setup (localHostMode=AUTO).` and `profile=LITE` on solo
2. `/leancore status` — profile, tier, player count
3. Friend joins → log `profile LITE -> STANDARD`
4. Mine ore with pickaxe → `/leancore learn player` shows `posterior=MINER …` and `activityModel=SOFTMAX`
5. `/leancore learn` — `featureSchema=v2` after several minutes (if `learningEnabled`)
6. Dedicated: `dedicatedServerMode: true` → `profile=FULL`

---

## Links

| Resource | URL |
|----------|-----|
| **Mod page (site)** | https://durkzprgmods.pages.dev/mods/leancore |
| **Full documentation** | https://durkzprgmods.pages.dev/documentation/leancore |
| **CurseForge** | https://www.curseforge.com/hytale/mods/leancore |
| **Report issues** | https://durkzprgmods.pages.dev/issues |
| **DurkzPRG Mods** | https://durkzprgmods.pages.dev |

---

## Building from source

Requirements: JDK compatible with your Hytale mod toolchain, Gradle wrapper included.

```bash
./gradlew build
```

Output JAR: `build/libs/LeanCore-1.2.0.jar`

---

## License

MIT License. Copyright (c) 2026 DurkzPRG
