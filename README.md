# LeanCore

**Server-side memory governance for Hytale hosts** spatial dormancy, per-player retention, adaptive heap tiers, and staff diagnostics. One mod for solo worlds, friends co-op, and dedicated servers. Not a client FPS tweak.

[![CurseForge](https://img.shields.io/badge/CurseForge-LeanCore-orange)](https://www.curseforge.com/hytale/mods/leancore)
[![Documentation](https://img.shields.io/badge/Docs-DurkzPRG%20Mods-blue)](https://durkzprgmods.pages.dev/documentation/leancore)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

<p align="center">
  <img src="https://durkzprgmods.pages.dev/images/leancore.jpg" alt="LeanCore — server memory governor" width="672" />
</p>

LeanCore reduces JVM heap pressure through spatial dormancy, per-player retention budgets, and an adaptive governor that learns from your server's own heap history not a one size fits all RAM slider.

Built for solo world owners, friends co-op, and dedicated servers that need **lower steady-state RAM** without guessing config values.

---

## What LeanCore is

- A **memory governor** that reads heap pressure and applies tiered policies
- **Spatial intelligence**: inactive map regions cool down (WARM → DORMANT → FROZEN)
- **Per-player demand**: explorers and builders are weighted by live activity, not fixed labels
- **Adaptive calibration**: tier thresholds learn from this server's heap quantiles over time
- **Transparency for staff**: diagnostics, heatmap, and an optional admin HUD

## What LeanCore is not

- Not a client FPS or TPS optimizer

---

## Main features

| Area | What it does |
|------|----------------|
| **Memory tiers** | COMFORT, WATCH, TIGHT, CRITICAL with hysteresis and rollback on bad policy outcomes |
| **Zone dormancy** | HOT near players; idle wilderness demotes over configurable timers |
| **Retention allocator** | Global memory budget with per-player footprints from demand scores |
| **Policy applier** | Throttled client view-radius adjustments under pressure |
| **Chunk unload** | Removes chunks in frozen/dormant zones when tiers require it |
| **Learning store** | Rolling heap windows (60s / 15m / 24h), policy bandit, false-cut tracking; persists across restarts |
| **Presets** | AUTO → SOLO_LEAN, FRIENDS_NIGHT, or SERVER_DENSE from online player count |
| **Admin HUD** | `/leancore hud on` — compact heap/tier overlay (**disabled by default**) |
| **Heatmap** | `/leancore heatmap [limit]` — zone state summary for staff |
| **Zone pin** | `/leancore zone pin\|unpin\|pins` — protect bases from demote/unload |
| **Webhook** | Optional `criticalWebhookUrl` posts generic JSON on CRITICAL tier (off by default) |
| **Asset pack** | Bundled server HUD `.ui` — no client mod required |

---

## Installation

1. Download **LeanCore-1.0.0.jar** from [CurseForge](https://www.curseforge.com/hytale/mods/leancore/files)
2. Place the JAR in your server's **`mods/`** folder
3. Start the server — config is created at **`mods/durkz_LeanCore/data/LeanCore.json`**
4. Grant staff HUD/admin access (see [Permissions](#permissions))
5. Run `/leancore memory` and `/leancore status` after a few minutes

**Singleplayer / local host:** default `hudViewerGroups` / `hudAdminGroups` (`OP`, `Admin`) work out of the box, or grant permission nodes with `/perm`.

---

## Commands

Main command: **`/leancore`**

| Command | Access | Description |
|---------|--------|-------------|
| `/leancore status` | Everyone | Governor, preset, and learning summary |
| `/leancore memory` | Everyone | Heap snapshot, tier, footprint, unload stats |
| `/leancore zones` | Everyone | Dormancy map counters |
| `/leancore learn` | Everyone | Learning diagnostics (bandit, quantiles, false cuts) |
| `/leancore learn player` | Everyone | Your per-player demand features |
| `/leancore probe` | Everyone | API capability probe |
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
| `governEnabled` | `true` | Apply governor policies |
| `learningEnabled` | `true` | In-process policy learning |
| `preset` | `AUTO` | `AUTO`, `SOLO_LEAN`, `FRIENDS_NIGHT`, `SERVER_DENSE` |
| `dedicatedServerMode` | `false` | Force SERVER preset behavior |
| `friendsMaxPlayers` | `8` | FRIENDS band upper bound |
| `serverDensePlayerThreshold` | `9` | SERVER preset from this count |
| `memoryBudgetMb` | `0` | Global retention cap (`0` = auto share of heap) |
| `watchHeapRatio` | `0.70` | Fixed tier threshold before quantiles warm up |
| `tightHeapRatio` | `0.82` | Fixed tier threshold |
| `criticalHeapRatio` | `0.90` | Fixed tier threshold |
| `dormantAfterMinutes` | `8` | WARM → DORMANT idle time |
| `frozenAfterMinutes` | `20` | → FROZEN idle time |
| `hudFeatureEnabled` | `true` | HUD feature available |
| `hudViewerGroups` | `OP`, `Admin` | Groups that may toggle HUD |
| `hudAdminGroups` | `OP`, `Admin` | Groups for heatmap and zone pin |
| `hudUpdateIntervalSeconds` | `3` | HUD refresh interval |
| `heatmapDefaultLimit` | `24` | Default heatmap rows |
| `zonePinMaxCount` | `16` | Max pinned zones |
| `criticalWebhookUrl` | `""` | Optional CRITICAL webhook (generic JSON) |
| `criticalWebhookCooldownSeconds` | `300` | Webhook cooldown |

**Other data files:**

- `mods/durkz_LeanCore/data/learning.state` — learning snapshot (schema v3)
- `mods/durkz_LeanCore/data/hud.state` — per-player HUD toggles

LeanCore uses **permission nodes** and/or **config group lists**. Either path grants access.

| Permission | Description |
|------------|-------------|
| `durkz.leancore.hud` | Toggle and view the opt-in memory HUD |
| `durkz.leancore.admin` | Heatmap, zone pin/unpin/pins, and admin HUD tools |

- **HUD viewers:** groups in `hudViewerGroups` and/or `durkz.leancore.hud`
- **Staff tools:** groups in `hudAdminGroups` and/or `durkz.leancore.admin`

### Quick setup with `/perm`

```text
/perm group add OP durkz.leancore.admin
```

HUD only (no heatmap / zone pin):

```text
/perm group add Moderator durkz.leancore.hud
```

Full permissions & configuration guide, `permissions.json` examples, and group setup: **[documentation](https://durkzprgmods.pages.dev/documentation/leancore)**

---

## Recommended usage

- Run on the **server** (dedicated host or world host). Works on solo share-code worlds where you host locally.
- Use `/leancore memory` at session start and after exploring to verify heap tier and governor status.
- Primary KPI is **server JVM heap**, not client FPS.

### Verifying it works

1. Boot log shows `LeanCore 1.0.0 setup.` with no errors
2. `/leancore memory` — tier, heap %, governor status
3. `/leancore learn` — quantiles and learning counters after several minutes
4. Compare heap with `governEnabled=false` vs `true` on the same route (A/B)

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

Output JAR: `build/libs/LeanCore-1.0.0.jar`

---

## License

MIT License. Copyright (c) 2026 DurkzPRG
