package com.durkz.leancore.dormancy;

import com.durkz.leancore.config.LeanCoreConfig;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ZoneChunkUnloader {

    private final LeanCoreConfig config;

    private volatile int lastUnloadedChunks;
    private volatile int lastCandidateZones;
    private long lastSweepMs;

    public ZoneChunkUnloader(LeanCoreConfig config) {
        this.config = config;
    }

    public int sweep(ZoneDormancyMap dormancyMap, MemoryTier tier) {
        if (!config.enabled || !config.governEnabled || !config.unloadEnabled) {
            return 0;
        }
        long nowMs = System.currentTimeMillis();
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
        List<CompletableFuture<Integer>> pending = new ArrayList<>();

        for (Map.Entry<UUID, List<ZoneKey>> entry : byWorld.entrySet()) {
            World world = Universe.get().getWorld(entry.getKey());
            if (world == null || !world.isAlive()) {
                continue;
            }
            List<ZoneKey> zones = List.copyOf(entry.getValue());
            CompletableFuture<Integer> result = new CompletableFuture<>();
            world.execute(() -> {
                int[] counter = new int[1];
                unloadOnWorld(world, zones, trackers, maxChunks, counter);
                result.complete(counter[0]);
            });
            pending.add(result);
        }

        int unloaded = 0;
        for (CompletableFuture<Integer> result : pending) {
            try {
                unloaded += result.get(2L, TimeUnit.SECONDS);
            } catch (TimeoutException ignored) {
                result.cancel(true);
            } catch (Exception ignored) {
            }
        }

        lastSweepMs = nowMs;
        lastCandidateZones = candidates.size();
        lastUnloadedChunks = unloaded;
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
            if (!zone.worldUuid().equals(world.getWorldConfig().getUuid())) {
                continue;
            }
            int baseChunkX = zone.regionX() * regionChunks;
            int baseChunkZ = zone.regionZ() * regionChunks;
            for (int dx = 0; dx < regionChunks; dx++) {
                for (int dz = 0; dz < regionChunks; dz++) {
                    if (unloadedCounter[0] >= maxChunks) {
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
