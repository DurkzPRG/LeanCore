package com.durkz.leancore.dormancy;

import com.durkz.leancore.LeanCorePlugin;
import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.probe.UnloadProbeGate;
import com.durkz.leancore.runtime.RuntimeGuard;
import com.durkz.leancore.runtime.WorldDispatch;
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
import java.util.List;
import java.util.Map;
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
        List<ChunkTracker> trackers = collectTrackers();
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
            int[] counter = new int[1];
            WorldDispatch.run(world, () -> unloadOnWorld(world, zones, trackers, maxChunks, counter));
            unloaded += counter[0];
        }

        lastSweepMs = nowMs;
        lastCandidateZones = candidates.size();
        lastUnloadedChunks = unloaded;
        if (unloadOutcomeTracker != null) {
            unloadOutcomeTracker.beginSweepWindow();
            unloadOutcomeTracker.notePolicyUnload(unloaded);
        }
        return unloaded;
    }

    public int sweepLite(ZoneDormancyMap dormancyMap, MemoryTier tier, long playerIdleSec) {
        if (!config.enabled || !config.liteUnloadEnabled) {
            return 0;
        }
        if (playerIdleSec < Math.max(60, config.liteUnloadIdleSeconds)) {
            return 0;
        }
        long nowMs = System.currentTimeMillis();
        if (UnloadProbeGate.blocksLiteUnload(config)) {
            if (nowMs - lastProbeGateLogMs >= 60_000L) {
                lastProbeGateLogMs = nowMs;
                LeanCorePlugin plugin = LeanCorePlugin.getInstance();
                if (plugin != null) {
                    plugin.getLogger().atWarning().log(
                            "lite unload gated — run /leancore probe before unload sweeps"
                    );
                }
            }
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
            return 0;
        }

        Map<UUID, List<ZoneKey>> byWorld = new HashMap<>();
        for (ZoneKey zone : candidates) {
            byWorld.computeIfAbsent(zone.worldUuid(), ignored -> new ArrayList<>()).add(zone);
        }

        int maxChunks = Math.max(1, config.liteUnloadMaxChunksPerSweep);
        List<ChunkTracker> trackers = collectTrackers();
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
            int[] counter = new int[1];
            WorldDispatch.run(world, () -> unloadOnWorld(world, zones, trackers, maxChunks, counter));
            unloaded += counter[0];
        }

        lastSweepMs = nowMs;
        lastCandidateZones = candidates.size();
        lastUnloadedChunks = unloaded;
        if (unloadOutcomeTracker != null) {
            unloadOutcomeTracker.beginSweepWindow();
            unloadOutcomeTracker.notePolicyUnload(unloaded);
        }
        return unloaded;
    }

    public int lastUnloadedChunks() {
        return lastUnloadedChunks;
    }

    public int lastCandidateZones() {
        return lastCandidateZones;
    }

    private static List<ChunkTracker> collectTrackers() {
        List<ChunkTracker> trackers = new ArrayList<>();
        for (PlayerRef ref : Universe.get().getPlayers()) {
            if (!ref.isValid()) {
                continue;
            }
            ChunkTracker tracker = ref.getChunkTracker();
            if (tracker != null) {
                trackers.add(tracker);
            }
        }
        return trackers;
    }

    private static void unloadOnWorld(
            World world,
            List<ZoneKey> zones,
            List<ChunkTracker> trackers,
            int maxChunks,
            int[] unloadedCounter
    ) {
        if (maxChunks <= 0) {
            return;
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
        if (ChunkUnloadingSystem.getChunkVisibility(trackers, index)
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
