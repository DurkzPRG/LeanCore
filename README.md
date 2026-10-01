# LeanCore

Server-side memory governor for Hytale. It reads how much heap is really in use after each garbage collection and acts on that: trims view and simulation radius when memory is genuinely tight, keeps recently visited chunks loaded while there is spare heap so walking back does not reload them from disk, and slows chunk streaming under real pressure.

On a local/solo world it uses the **LITE** profile. When friends join the runtime scales to **STANDARD**; dedicated hosts use **FULL**.

[![CurseForge](https://img.shields.io/badge/CurseForge-LeanCore-orange)](https://www.curseforge.com/hytale/mods/leancore)
[![Documentation](https://img.shields.io/badge/Docs-DurkzPRG%20Mods-blue)](https://durkzprgmods.pages.dev/documentation/leancore)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

<p align="center">
  <img src="https://durkzprgmods.pages.dev/images/leancore-banner-800.png" alt="LeanCore server memory governor" width="672" />
</p>

## Status

Works and is in daily use on solo and small co-op worlds. Hytale is in early access, so the server API can change between versions. Results depend on your world and heap size.

## Why it exists

The engine already unloads any chunk no player can see, about 7.5 seconds after it leaves view. Two things it does not do:

- **It judges memory by the raw heap**, which includes garbage waiting for the next collection. Raw heap swings between 40% and 90% on a healthy server, so anything reacting to it cuts for nothing.
- **It throws chunks away even with plenty of free heap.** Walk back the way you came and every chunk is read from disk again.

LeanCore fills those two gaps.

## Measured

A/B on the Hytale 0.7 pre-release: solo, 3 GB heap, the same scripted flight (600 blocks out, 90s wait, back), the build before chunk retention vs 1.8.0.

| | before | 1.8.0 |
|---|---|---|
| Chunks reloaded from disk on the way back | 1203 | 653 (-46%) |
| World tick on the way back | 12.5 ms | 10.9 ms (-13%) |
| View radius at join | cut to 8 by a raw-heap false alarm | kept at 10 |
| Policy changes during the run | 13 | 5 |

The cost: more heap in use during the run (capped at 50% after GC) and about 0.16% more of the time spent in GC pauses. One run per build; your world will differ.

## How LeanCore decides

```
              every 5s on solo, every governor tick on servers
                                   │
        heap after GC  +  old gen after GC  +  growth trend
                                   │
        ┌──────────────┬───────────┴───────────┬──────────────┐
        ▼              ▼                       ▼              ▼
     COMFORT         WATCH                   TIGHT         CRITICAL
    full view     gentle trim           stronger trim      max trim
   hold spare    release held           release held     release held
     chunks         chunks             + brake loading   + brake harder
```

Default thresholds: 70% / 82% / 90% of max heap, measured after GC. Raw heap alone only counts as CRITICAL at 97%. A change needs two readings to confirm. Giving view back is undone if the heap climbs right after; a cut is never undone just because the heap kept rising.

**Chunk retention**

```
   a chunk leaves every player's view
                 │
                 ▼
   engine would unload it in ~7.5s
                 │
   COMFORT and heap after GC under 50%?  ── no ──►  engine unloads it
                 │ yes
                 ▼
   LeanCore holds it (engine keep-loaded reference; it stops ticking)
                 │
   player walks back           ──►  already loaded, no disk read
   pressure, or 10 min passed  ──►  released, engine unloads it as usual
```

LeanCore never removes chunks itself. Releasing only drops the reference.

## What it does

- Live-heap tiers: COMFORT / WATCH / TIGHT / CRITICAL from heap after GC, old gen after GC and the growth trend, with rollback when giving view back backfires
- View radius trims (LITE) and simulation (hot) radius trims under real pressure, never below your floor
- Chunk retention: spare heap keeps recently left chunks loaded
- Chunk rate brake: slows chunk streaming on TIGHT/CRITICAL (stays off while QuantumHy is loaded)
- Per-player demand from Activity Sense (mining, chopping, farming, building, combat, exploring) shapes how hard each player's view is trimmed
- Runtime profiles: `LITE` (solo), `STANDARD` (friends), `FULL` (dedicated)
- Always-on session diagnostics: startup/shutdown summaries and decision logs (`diagnosticLogEnabled`)
- Staff tools: `/leancore` commands, heatmap, zone pin, optional HUD and CRITICAL webhook

## With QuantumHy

LeanCore and [QuantumHy](https://www.curseforge.com/hytale/mods/quantumhy) work alone or together. With both installed, QuantumHy takes the client view radius and the chunk send rate (its anti-stutter smoothing), and LeanCore keeps memory: tiers, simulation radius and chunk retention. QuantumHy reads LeanCore's tier and tightens its render radius on TIGHT or CRITICAL. LeanCore's chunk rate brake stays off while QuantumHy is loaded.

## Runtime profiles

| Players | Profile | Tick | Notes |
|---------|---------|------|-------|
| 1 (solo local) | `LITE` | 30s (60s idle), pressure check every 5s | Lite governor, adaptive view, retention, lite learning |
| 1 + `embeddedStandardProfile` | `STANDARD` | 15s | Dev dogfood of govern/learning without FULL |
| 2-8 (friends) | `STANDARD` | 15s | Classifier; govern/learning/HUD if enabled |
| 9+ (dense local) | `FULL` | 5s | Full runtime per config |
| Dedicated JVM | `FULL` | 5s | Full runtime per config |

Default: `localHostMode: "AUTO"`. Use `"PASSIVE"` to disable background ticks. Set `dedicatedServerMode: true` on a dedicated host.

Boot log: `LeanCore 1.8.0 setup (localHostMode=AUTO).` and `Runtime started profile=LITE` on solo.

## Install

1. Download **LeanCore-1.8.0.jar** from [CurseForge](https://www.curseforge.com/hytale/mods/leancore/files)
2. Put the JAR in your world's `mods/` folder (or `%AppData%\Hytale\UserData\Mods\` on Windows)
3. Config: `mods/durkz_LeanCore/LeanCore.json` (created on first boot)
4. Run `/leancore status` after about a minute

## Commands

Main command: `/leancore`

| Command | Who | Purpose |
|---------|-----|---------|
| `/leancore status` | Everyone | Profile, tier, pressure line, retention line |
| `/leancore memory` | Everyone | Heap snapshot and tier |
| `/leancore savings` | Everyone | Session JVM heap and governor activity |
| `/leancore zones` | Everyone | Zone counters |
| `/leancore learn` | Everyone | Learning store |
| `/leancore learn player` | Everyone | Your features, posterior, activity EMAs |
| `/leancore probe` | Everyone | API probe S1-S5 (diagnostic) |
| `/leancore hud on\|off\|status` | HUD permission | Memory overlay |
| `/leancore heatmap [limit]` | Staff | Zone heatmap |
| `/leancore zone pin\|unpin\|pins` | Staff | Pin zones |

## Config (common keys)

File: `mods/durkz_LeanCore/LeanCore.json`

| Key | Default | Notes |
|-----|---------|-------|
| `localHostMode` | `AUTO` | `AUTO`, `PASSIVE`, or `FULL` |
| `governEnabled` | `false` | STANDARD/FULL governor |
| `learningEnabled` | `false` | STANDARD/FULL learning |
| `watchHeapRatio` / `tightHeapRatio` / `criticalHeapRatio` | `0.70` / `0.82` / `0.90` | Tier thresholds, heap after GC |
| `dedicatedServerMode` | `false` | Force FULL profile |
| `dedicatedViewRadiusGraceSeconds` | `600` | Dedicated hosts: no view-radius cuts for this long after start |
| `checkForUpdates` | `true` | Notify ops/admins once per session when a newer JAR is on the mod page |

### Retention and brake keys (1.8.0)

| Key | Default | Notes |
|-----|---------|-------|
| `chunkRetentionEnabled` | `true` | Keep recently left chunks loaded while there is spare heap |
| `chunkRetentionMaxPostGcRatio` | `0.50` | Hold chunks only while heap after GC stays under this (always below `watchHeapRatio`) |
| `chunkRetentionMbPerChunk` | `1.0` | Heap estimate per held chunk, used for the budget |
| `chunkRetentionMaxChunks` | `2048` | Hard cap on held chunks |
| `chunkRetentionMaxHoldSeconds` | `600` | Release a held chunk after this long |
| `chunkRetentionMaxDistanceBlocks` | `1024` | Chunks are held nearest-first; nothing farther than this from an online player |
| `chunkRateBrakeEnabled` | `true` | Brake chunk streaming on TIGHT/CRITICAL (off while QuantumHy is loaded) |
| `chunkRateBrakeTightPerSecond` | `512` | Sections/s cap per player on TIGHT |
| `chunkRateBrakeCriticalPerSecond` | `192` | Sections/s cap per player on CRITICAL |

### LITE keys

| Key | Default | Notes |
|-----|---------|-------|
| `liteMemoryGovernorEnabled` | `true` | Solo governor |
| `liteLearningEnabled` | `true` | Demand model + persistence |
| `liteViewRadiusEnabled` | `true` | Adaptive view on embedded solo |
| `liteMinClientViewRadius` | `8` | Never trim below this |
| `liteViewPressureThreshold` | `0.85` | COMFORT cap when section saturation is high |

`unloadEnabled`, `liteUnloadEnabled`, `liteUnloadIdleSeconds` and `unloadProbeGateEnabled` are kept so old configs load, but have no effect since 1.8.0: the engine already unloads every chunk no player tracks.

Learning snapshot: `mods/durkz_LeanCore/learning.state.gz` (schema v9, gzip binary).

Permissions: `durkz.leancore.hud`, `durkz.leancore.admin`

Full reference: [documentation](https://durkzprgmods.pages.dev/documentation/leancore)

## Quick verify

1. Solo world: `/leancore status` shows `profile LITE`, a `pressure=` line and a `retention` line
2. Walk away from spawn and stand still: the retention line shows `held` going up while the tier stays COMFORT
3. Friend joins: log shows `profile LITE -> STANDARD`

## Known limitations

- It does not fix CPU-bound tick lag. It targets memory and chunk churn.
- A small heap leaves little room for retention; most of the gain shows on hosts with spare memory.
- The learning model needs a session or two of real play before its demand signals are useful.
- It does not promise higher FPS; client frame rate depends on the renderer, not the server heap.
- `motionViewRadiusBoostEnabled` is off by default: rewriting view radius every tick churns chunk loading on the current engine.
- Future Hytale updates may change the APIs it relies on.

## Build

```bash
./gradlew build
```

Output: `build/libs/LeanCore-1.8.0.jar`

**Local deploy (DurkzPRG):** copy the built JAR to:

`%AppData%\Hytale\UserData\Mods\`

Example (Windows): `Copy-Item build\libs\LeanCore-1.8.0.jar $env:APPDATA\Hytale\UserData\Mods\`

## Links

- [Mod page](https://durkzprgmods.pages.dev/mods/leancore)
- [Docs](https://durkzprgmods.pages.dev/documentation/leancore)
- [GitHub](https://github.com/DurkzPRG/LeanCore)
- [CurseForge](https://www.curseforge.com/hytale/mods/leancore)

## License

MIT. See [LICENSE](LICENSE).
