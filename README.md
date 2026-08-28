# LeanCore

Server-side memory governor for Hytale. Cools idle map regions, trims load under heap pressure, and learns retention weights from how players actually play.

On a local/solo world it uses the **LITE** profile: adaptive view-radius, AFK chunk reclaim, and on-by-default learning, without the weight of STANDARD/FULL. When friends join the runtime scales to **STANDARD**; dedicated hosts use **FULL**.

[![CurseForge](https://img.shields.io/badge/CurseForge-LeanCore-orange)](https://www.curseforge.com/hytale/mods/leancore)
[![Documentation](https://img.shields.io/badge/Docs-DurkzPRG%20Mods-blue)](https://durkzprgmods.pages.dev/documentation/leancore)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

<p align="center">
  <img src="https://durkzprgmods.pages.dev/images/leancore-banner-800.png" alt="LeanCore server memory governor" width="672" />
</p>

## Status

Works and is in daily use on solo and small co-op worlds. Treat it as functional but still maturing: Hytale is in early access, so the server API can change between versions, and the heap/chunk wins depend a lot on your world (entity density, how much you build, how many chunks stay loaded). The numbers in `/leancore savings` are measured per session; I have not run a controlled large-server benchmark yet, so the comparison below is illustrative, not a published result.

## With vs without LeanCore

Same solo session, server RAM over time. Without the mod, every area you visit stays resident and heap only climbs until you restart. With LeanCore, idle areas cool down and release, so RAM rises under load and then settles back.

The shape below is illustrative (it shows the intended behavior), not a benchmark. For real numbers on your setup, watch `/leancore savings` over a session.

```
Server RAM over a long session   (taller bars = more RAM in use)

Without LeanCore   ▁▂▃▄▅▆▇███████████   climbs to the limit, then you restart
With LeanCore      ▂▄▆▄▂▄▆▄▂▄▆▄▂▄▆▄▂▄   rises and falls, stays under control
```

## How LeanCore decides

Two signals drive every action: how tight memory is right now, and whether a zone is still wanted. The ladder picks how hard to act; the per-zone check decides what is safe to release.

**Pressure to action**

```
                           every tick
                                │
                 read heap %  +  section pressure
                                │
               rank against THIS server's history
                                │
        ┌───────────────┬───────┴───────┬───────────────┐
        ▼               ▼               ▼               ▼
     COMFORT          WATCH           TIGHT         CRITICAL
    full view      gentle trim   unload distant    max trim +
   do nothing     + demote idle   dormant zones    unload + GC
                                                    + webhook
```

**Keep or release a zone**

```
   each idle zone
        │
        ▼
   player in / near it, or pinned?     ──► HOT   keep, never touched
        │ no
        ▼
   inside current or predicted view?   ──► keep  would cause pop-in
        │ no
        ▼
   idle long enough to be dormant?     ──► WARM  keep, still cooling
        │ yes
        ▼
   rank: far away + unlikely to return + low built content
        │
        ▼
   release   capped per pass, only chunks nobody can see,
             and rolled back if it backfires
```



## What it does

- Optional heap governor with COMFORT / WATCH / TIGHT / CRITICAL tiers and rollback
- Zone dormancy: WARM, DORMANT, FROZEN based on player proximity and idle time
- Predictive zone retention (1.6+): a per-player motion model biases unload away from where players are heading and never evicts zones inside the current or predicted view
- Reuse-distance + survival model (1.6+): learns which zones get revisited and scales dormancy thresholds per zone
- Per-player retention weights from activity and a learned demand model
- Activity Sense (1.2+): online classifier for mining, chopping, farming, building, crafting, combat
- Runtime profiles: `LITE` (solo), `STANDARD` (friends), `FULL` (dedicated)
- Always-on session diagnostics: startup/shutdown summaries and decision logs explain what the mod did and why (`diagnosticLogEnabled`)
- Staff tools: `/leancore` commands, heatmap, optional HUD

## Runtime profiles

| Players | Profile | Tick | Notes (1.7.2) |
|---------|---------|------|-----------------|
| 1 (solo local) | `LITE` | 30s (60s idle) | Lite governor, adaptive view, AFK unload, lite learning, motion-aware retention |
| 1 + `embeddedStandardProfile` | `STANDARD` | 15s | Dev dogfood of govern/learning without FULL |
| 2-8 (friends) | `STANDARD` | 15s | Classifier; govern/learning/HUD if enabled |
| 9+ (dense local) | `FULL` | 5s | Full runtime per config |
| Dedicated JVM | `FULL` | 5s | Full runtime per config |

Default: `localHostMode: "AUTO"`. Use `"PASSIVE"` to disable background ticks. Set `dedicatedServerMode: true` on a dedicated host.

Boot log: `LeanCore 1.7.2 setup (localHostMode=AUTO).` and `Runtime started profile=LITE` on solo.

### LITE profile (1.7.2)

Solo embedded gets a real memory governor without switching to STANDARD. STANDARD/FULL unchanged.

| Feature | Behavior |
|---------|----------|
| Adaptive view | 100% in COMFORT until section or heap pressure; gentle cuts in WATCH; aggressive only in TIGHT/CRITICAL |
| Dual signals | JVM heap tier + section pressure (`loaded sections / 3D view budget`) |
| Unload AFK | FROZEN zones when idle; probe gate; no `governEnabled` required |
| Learning | `liteLearningEnabled=true` by default; demand shapes view cuts; no bandit in LITE |
| Tick budget | Heavy work on heap sample (~60s), world-thread dispatch |

STANDARD/FULL: view-radius and chunk unload still require `governEnabled` / `learningEnabled` / `unloadEnabled` as before.

## Install

1. Download **LeanCore-1.7.2.jar** from [CurseForge](https://www.curseforge.com/hytale/mods/leancore/files)
2. Put the JAR in your world's `mods/` folder (or `%AppData%\Hytale\UserData\Mods\` on Windows)
3. Config: `mods/durkz_LeanCore/LeanCore.json` (created on first boot with 1.7.2 defaults)
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
| `checkForUpdates` | `true` | Notify ops/admins once per session when a newer JAR is on the mod page |

### LITE keys (1.7.2)

| Key | Default | Notes |
|-----|---------|-------|
| `liteMemoryGovernorEnabled` | `true` | Solo governor (view + unload + demote) |
| `liteLearningEnabled` | `true` | Demand model + persistence; no bandit |
| `liteViewRadiusEnabled` | `true` | Adaptive view on embedded solo |
| `liteViewPressureThreshold` | `0.85` | COMFORT cap when section saturation high |
| `liteUnloadEnabled` | `true` | AFK reclaim; still needs probe |
| `liteUnloadIdleSeconds` | `180` | Idle before unload sweeps |
| `unloadHoldWhenLoadingAbove` | `80` | Hold unload/radius cuts while section backlog is above this |

### Motion & retention keys (1.7.2)

| Key | Default | Notes |
|-----|---------|-------|
| `motionModelEnabled` | `true` | Per-player velocity model; biases unload toward where players go |
| `zoneReuseModelEnabled` | `true` | Reuse-distance + survival model; scales dormancy per zone |
| `motionViewRadiusBoostEnabled` | `false` | Opt-in cinematic view boost for fast movers; off because rewriting view radius each tick churns chunk loading on the current engine |
| `diagnosticLogEnabled` | `true` | Always-on `[diag]` session/decision logs; set `false` to silence |

Learning snapshot: `mods/durkz_LeanCore/learning.state.gz` (schema v9, gzip binary). Legacy `learning.state` is migrated on first flush.

Permissions: `durkz.leancore.hud`, `durkz.leancore.admin`

Full reference: [documentation](https://durkzprgmods.pages.dev/documentation/leancore)

## Quick verify

1. Solo world: `/leancore status` shows `profile LITE`, `lite governor ON` in savings after ~60s
2. Mine ore, then `/leancore learn player` shows `MINER` and demand/viewScale
3. Friend joins → log shows `profile LITE -> STANDARD`

## Known limitations

- It does not replace manual server tuning. It helps with memory growth from idle regions; it does not fix CPU-bound tick lag.
- Gains depend on the world. A small, mostly-static world has little to reclaim, so you may see almost no difference.
- The learning model needs a session or two of real play before its retention and demand signals are useful. Fresh installs lean on the heuristics.
- It targets memory and section/chunk pressure. It does not promise higher FPS; client frame rate depends on the renderer, not the server heap.
- `motionViewRadiusBoostEnabled` is off by default: rewriting view radius every tick churns chunk loading on the current engine.
- Policy chunk unload (`unloadEnabled`) stays off until you run `/leancore probe`, because it depends on server internals that can shift between Hytale versions.
- Future Hytale updates may change the APIs the probe relies on.

## Build

```bash
./gradlew build
```

Output: `build/libs/LeanCore-1.7.2.jar`

**Local deploy (DurkzPRG):** copy the built JAR to:

`%AppData%\Hytale\UserData\Mods\`

Example (Windows): `Copy-Item build\libs\LeanCore-1.7.2.jar $env:APPDATA\Hytale\UserData\Mods\`

## Links

- [Mod page](https://durkzprgmods.pages.dev/mods/leancore)
- [Docs](https://durkzprgmods.pages.dev/documentation/leancore)
- [GitHub](https://github.com/DurkzPRG/LeanCore)
- [CurseForge](https://www.curseforge.com/hytale/mods/leancore)

## License

MIT. See [LICENSE](LICENSE).
