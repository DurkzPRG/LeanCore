package com.durkz.leancore.dormancy;

import com.durkz.leancore.LeanCorePlugin;
import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.diagnostics.DiagnosticLog;
import com.durkz.leancore.probe.UnloadProbeGate;
import com.durkz.leancore.runtime.RuntimeGuard;
import com.durkz.leancore.runtime.WorldDispatch;
import com.durkz.leancore.intelligence.LoadingPressureGate;
import com.durkz.leancore.intelligence.UnloadOutcomeTracker;
import com.durkz.leancore.memory.MemoryTier;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.component.ChunkUnloadingSystem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ZoneChunkUnloader {

    private final LeanCoreConfig config;
    private final UnloadOutcomeTracker unloadOutcomeTracker;

    private volatile int lastUnloadedChunks;
    private volatile int lastCandidateZones;
    private long lastSweepMs;
    private long lastProbeGateLogMs;

    public ZoneChunkUnloader(LeanCoreConfig config, UnloadOutcomeTracker unloadOutcomeTracker) {
        this.config = config;
        this.unloadOutcomeTracker = unloadOutcomeTracker;
    }

    public int sweep(ZoneDormancyMap dormancyMap, MemoryTier tier) {
        if (!RuntimeGuard.active() || !config.enabled || !config.governEnabled || !config.unloadEnabled) {
            return 0;
        }
        long nowMs = System.currentTimeMillis();
        if (UnloadProbeGate.blocksUnload(config)) {
            if (nowMs - lastProbeGateLogMs >= 60_000L) {
                lastProbeGateLogMs = nowMs;
                LeanCorePlugin plugin = LeanCorePlugin.getInstance();
                if (plugin != null) {
                    plugin.getLogger().atWarning().log(
                            "unload gated — run /leancore probe before unload sweeps"
                    );
                }
            }
            return 0;
        }
        long minIntervalMs = Math.max(1, config.unloadMinIntervalSeconds) * 1000L;
        if (lastSweepMs > 0L && nowMs - lastSweepMs < minIntervalMs) {
            return 0;
        }

        List<ZoneKey> candidates = dormancyMap.unloadCandidateZones(tier);
        if (candidates.isEmpty()) {
            lastCandidateZones = 0;
            return 0;
        }

        Map<UUID, List<ZoneKey>> byWorld = new HashMap<>();
        for (ZoneKey zone : candidates) {
            byWorld.computeIfAbsent(zone.worldUuid(), ignored -> new ArrayList<>()).add(zone);
        }

        int maxChunks = Math.max(1, config.unloadMaxChunksPerSweep);
        int unloaded = 0;

        for (Map.Entry<UUID, List<ZoneKey>> entry : byWorld.entrySet()) {
            if (!RuntimeGuard.active()) {
                break;
            }
            World world = Universe.get().getWorld(entry.getKey());
            if (world == null || !world.isAlive()) {
                continue;
            }
            List<ZoneKey> zones = List.copyOf(entry.getValue());
            List<ChunkTracker> trackers = collectTrackers(entry.getKey());
            int[] counter = new int[1];
            Set<ZoneKey> unloadedZones = new HashSet<>();
            // Read the counter only when the world task completed. On a timed-out dispatch the task
            // may still be mutating counter[0] on the world thread; trusting it here would be a data
            // race and could over-report unloads.
            boolean done = WorldDispatch.run(world,
                    () -> unloadOnWorld(config, world, zones, trackers, maxChunks, counter, unloadedZones));
            if (done) {
                unloaded += counter[0];
                noteUnloadedZones(dormancyMap, unloadedZones, nowMs);
            }
        }

        lastSweepMs = nowMs;
        lastCandidateZones = candidates.size();
        lastUnloadedChunks = unloaded;
        if (unloadOutcomeTracker != null) {
            unloadOutcomeTracker.beginSweepWindow();
            unloadOutcomeTracker.notePolicyUnload(unloaded);
        }
        if (unloaded > 0) {
            DiagnosticLog.info(String.format(java.util.Locale.ROOT,
                    "unload: %d chunks from %d candidate zones (tier=%s, reason=standard)",
                    unloaded, candidates.size(), tier));
        }
        return unloaded;
    }

    public int sweepLite(ZoneDormancyMap dormancyMap, MemoryTier tier, long playerIdleSec) {
        if (!config.enabled || !config.liteUnloadEnabled) {
            return 0;
        }
        long idleThreshold = Math.max(60, config.liteUnloadIdleSeconds);
        if (playerIdleSec < idleThreshold) {
            DiagnosticLog.infoOnChange("lite-unload-gate",
                    "lite unload blocked: player active (need idle " + idleThreshold + "s)");
            return 0;
        }
        long nowMs = System.currentTimeMillis();
        if (UnloadProbeGate.blocksLiteUnload(config)) {
            DiagnosticLog.infoOnChange("lite-unload-gate",
                    "lite unload blocked: probe gate (run /leancore probe)");
            return 0;
        }
        if (!RuntimeGuard.active()) {
            return 0;
        }
        long minIntervalMs = Math.max(1, config.unloadMinIntervalSeconds) * 1000L;
        if (lastSweepMs > 0L && nowMs - lastSweepMs < minIntervalMs) {
            return 0;
        }

        List<ZoneKey> candidates = dormancyMap.unloadCandidateZones(tier, MemoryTier.WATCH);
        if (candidates.isEmpty()) {
            lastCandidateZones = 0;
            DiagnosticLog.infoOnChange("lite-unload-gate", "lite unload open: 0 dormant candidates");
            return 0;
        }
        DiagnosticLog.infoOnChange("lite-unload-gate",
                "lite unload open: " + candidates.size() + " candidate zones (tier=" + tier + ")");

        Map<UUID, List<ZoneKey>> byWorld = new HashMap<>();
        for (ZoneKey zone : candidates) {
            byWorld.computeIfAbsent(zone.worldUuid(), ignored -> new ArrayList<>()).add(zone);
        }

        int maxChunks = Math.max(1, config.liteUnloadMaxChunksPerSweep);
        int unloaded = 0;

        for (Map.Entry<UUID, List<ZoneKey>> entry : byWorld.entrySet()) {
            if (!RuntimeGuard.active()) {
                break;
            }
            World world = Universe.get().getWorld(entry.getKey());
            if (world == null || !world.isAlive()) {
                continue;
            }
            List<ZoneKey> zones = List.copyOf(entry.getValue());
            List<ChunkTracker> trackers = collectTrackers(entry.getKey());
            int[] counter = new int[1];
            Set<ZoneKey> unloadedZones = new HashSet<>();
            boolean done = WorldDispatch.run(world,
                    () -> unloadOnWorld(config, world, zones, trackers, maxChunks, counter, unloadedZones));
            if (done) {
                unloaded += counter[0];
                noteUnloadedZones(dormancyMap, unloadedZones, nowMs);
            }
        }

        lastSweepMs = nowMs;
        lastCandidateZones = candidates.size();
        lastUnloadedChunks = unloaded;
        if (unloadOutcomeTracker != null) {
            unloadOutcomeTracker.beginSweepWindow();
            unloadOutcomeTracker.notePolicyUnload(unloaded);
        }
        if (unloaded > 0) {
            DiagnosticLog.info(String.format(java.util.Locale.ROOT,
                    "unload: %d chunks from %d candidate zones (tier=%s, reason=lite-idle %ds)",
                    unloaded, candidates.size(), tier, playerIdleSec));
        }
        return unloaded;
    }

    /** Feeds confirmed per-zone unloads to the dormancy map so revisit-after-unload can be scored. */
    private static void noteUnloadedZones(ZoneDormancyMap dormancyMap, Set<ZoneKey> unloadedZones, long nowMs) {
        if (dormancyMap == null || unloadedZones.isEmpty()) {
            return;
        }
        for (ZoneKey zone : unloadedZones) {
            dormancyMap.noteZoneUnloaded(zone, nowMs);
        }
    }

    public int lastUnloadedChunks() {
        return lastUnloadedChunks;
    }

    public int lastCandidateZones() {
        return lastCandidateZones;
    }

    private static List<ChunkTracker> collectTrackers(UUID worldUuid) {
        List<ChunkTracker> trackers = new ArrayList<>();
        for (PlayerRef ref : Universe.get().getPlayers()) {
            if (!ref.isValid()) {
                continue;
            }
            if (worldUuid != null && !worldUuid.equals(ref.getWorldUuid())) {
                continue;
            }
            ChunkTracker tracker = ref.getChunkTracker();
            if (tracker != null) {
                trackers.add(tracker);
            }
        }
        return trackers;
    }

    /** Sum of in-flight chunk columns across a world's online players. World-thread read. */
    private static int sumLoadingChunks(List<ChunkTracker> trackers) {
        if (trackers == null || trackers.isEmpty()) {
            return 0;
        }
        int loading = 0;
        for (ChunkTracker tracker : trackers) {
            if (tracker != null) {
                loading += Math.max(0, tracker.getLoadingSectionsCount());
            }
        }
        return loading;
    }

    private static void unloadOnWorld(
            LeanCoreConfig config,
            World world,
            List<ZoneKey> zones,
            List<ChunkTracker> trackers,
            int maxChunks,
            int[] unloadedCounter,
            Set<ZoneKey> unloadedZones
    ) {
        if (maxChunks <= 0) {
            return;
        }
        // Loading-pressure gate: while this world is still streaming chunks to its players, holding
        // the unload sweep avoids fighting the engine loader (which would re-load what we removed).
        // Runs on the world thread (caller dispatches), so the ChunkTracker reads are thread-safe.
        if (config != null && config.loadingPressureSignalEnabled) {
            int loadingBacklog = sumLoadingChunks(trackers);
            if (LoadingPressureGate.holdsUnload(config, loadingBacklog)) {
                DiagnosticLog.infoOnChange("unload-loading-gate",
                        "unload held: " + loadingBacklog + " chunks streaming (> "
                                + config.unloadHoldWhenLoadingAbove + ")");
                return;
            }
        }
        ChunkStore chunkStore = world.getChunkStore();
        int regionChunks = ZoneKey.regionChunks();

        for (ZoneKey zone : zones) {
            if (!RuntimeGuard.active()) {
                return;
            }
            if (!zone.worldUuid().equals(world.getWorldConfig().getUuid())) {
                continue;
            }
            int baseChunkX = zone.regionX() * regionChunks;
            int baseChunkZ = zone.regionZ() * regionChunks;
            for (int dx = 0; dx < regionChunks; dx++) {
                for (int dz = 0; dz < regionChunks; dz++) {
                    if (!RuntimeGuard.active() || unloadedCounter[0] >= maxChunks) {
                        return;
                    }
                    int chunkX = baseChunkX + dx;
                    int chunkZ = baseChunkZ + dz;
                    if (tryUnloadChunk(world, chunkStore, trackers, chunkX, chunkZ)) {
                        unloadedCounter[0]++;
                        unloadedZones.add(zone);
                    }
                }
            }
        }
    }

    private static boolean tryUnloadChunk(
            World world,
            ChunkStore chunkStore,
            List<ChunkTracker> trackers,
            int chunkX,
            int chunkZ
    ) {
        long index = ChunkUtil.indexChunk(chunkX, chunkZ);
        if (ChunkUnloadingSystem.getChunkVisibility(chunkStore, trackers, index)
                != ChunkTracker.ChunkVisibility.NONE) {
            return false;
        }
        Ref<ChunkStore> ref = chunkStore.getChunkReference(index);
        if (ref == null) {
            return false;
        }
        chunkStore.remove(ref, RemoveReason.UNLOAD);
        world.getNotificationHandler().updateChunk(index);
        return true;
    }
}
