package com.durkz.leancore.dormancy;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.memory.MemorySnapshot;
import com.durkz.leancore.memory.MemoryTier;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.component.ChunkUnloadingSystem;
import it.unimi.dsi.fastutil.longs.LongIterator;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Spends spare heap on keeping recently left chunks loaded, so walking back does not reload them
 * from disk.
 * <p>
 * The engine drops every chunk no player tracks 7.5s after it leaves view, however much heap is
 * free. LeanCore takes an engine keep-loaded reference ({@link WorldChunk#addKeepLoaded()}) on such
 * chunks while the post-GC heap has room, bounded by a chunk budget derived from that room. The
 * engine keeps a keep-loaded chunk resident and stops ticking it on its own. Releasing just drops
 * the reference; the engine then unloads the chunk as usual. LeanCore never removes chunks.
 * <p>
 * {@code ChunkUnloadEvent} cannot be used for this: the engine dispatches it from its parallel
 * unload workers and any listener trips a world-thread assertion that stops the world (0.6, 0.7).
 * <p>
 * {@link #scanWorld} runs on that world's thread; {@link #updateBudget} on the runtime thread.
 */
public final class ChunkRetention {

    private static final double NEAR_FULL = 0.9D;
    private static final double BYTES_PER_MB = 1024.0D * 1024.0D;
    /**
     * New holds per world scan (every 2s). The post-GC reading lags a few GC cycles; taking the whole
     * budget at once would overshoot before the budget can shrink.
     */
    static final int MAX_NEW_HOLDS_PER_SCAN = 64;

    private final LeanCoreConfig config;
    /** Per world; each map is only touched on its own world thread. */
    private final ConcurrentHashMap<UUID, Map<Long, Held>> held = new ConcurrentHashMap<>();
    private final AtomicInteger heldCount = new AtomicInteger();
    private final LongAdder holds = new LongAdder();
    private final LongAdder returned = new LongAdder();
    private final LongAdder released = new LongAdder();

    private volatile int budget;

    public ChunkRetention(LeanCoreConfig config) {
        this.config = config;
    }

    /** Runtime thread. */
    public void updateBudget(MemorySnapshot sample) {
        budget = config.enabled && config.chunkRetentionEnabled
                ? budgetFor(sample, heldCount.get(), config.chunkRetentionMaxPostGcRatio,
                        config.chunkRetentionMbPerChunk, config.chunkRetentionMaxChunks)
                : 0;
    }

    /** Worlds that currently hold chunks, so they get scanned (and released) even with no players. */
    public Set<UUID> worldsHolding() {
        return Set.copyOf(held.keySet());
    }

    /**
     * World thread. Re-checks held chunks (back in view, too old, over budget) and takes new ones
     * that just left every player's view.
     *
     * @param players flat {@code x0,z0,x1,z1,...} block positions of this world's players, empty if none
     */
    public void scanWorld(World world, List<ChunkTracker> trackers, double[] players, long nowMs) {
        UUID worldUuid = world.getWorldConfig().getUuid();
        Map<Long, Held> mine = held.computeIfAbsent(worldUuid, ignored -> new HashMap<>());
        ChunkStore chunkStore = world.getChunkStore();
        long maxHoldMs = Math.max(0, config.chunkRetentionMaxHoldSeconds) * 1000L;
        int ring = config.chunkRetentionRingBlocks;
        int currentBudget = players.length == 0 ? 0 : budget;

        Iterator<Map.Entry<Long, Held>> it = mine.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Held> e = it.next();
            long index = e.getKey();
            Held h = e.getValue();
            WorldChunk current = chunkStore.getChunkComponent(index, WorldChunk.getComponentType());
            if (current != h.chunk) {
                // Removed by another path (world teardown); our reference went with it.
                it.remove();
                heldCount.decrementAndGet();
                continue;
            }
            if (ChunkUnloadingSystem.getChunkVisibility(chunkStore, trackers, index) != ChunkTracker.ChunkVisibility.NONE) {
                h.chunk.removeKeepLoaded();
                it.remove();
                heldCount.decrementAndGet();
                returned.increment();
                continue;
            }
            if (!decide(heldCount.get(), currentBudget, nowMs - h.sinceMs, maxHoldMs,
                    nearestDistance(players, ChunkUtil.xOfChunkIndex(index), ChunkUtil.zOfChunkIndex(index)), ring)) {
                h.chunk.removeKeepLoaded();
                it.remove();
                heldCount.decrementAndGet();
                released.increment();
            }
        }

        if (currentBudget > heldCount.get()) {
            int taken = 0;
            LongIterator indexes = chunkStore.getChunkIndexes().iterator();
            while (indexes.hasNext() && heldCount.get() < currentBudget && taken < MAX_NEW_HOLDS_PER_SCAN) {
                long index = indexes.nextLong();
                if (mine.containsKey(index)
                        || ChunkUnloadingSystem.getChunkVisibility(chunkStore, trackers, index) != ChunkTracker.ChunkVisibility.NONE) {
                    continue;
                }
                WorldChunk chunk = chunkStore.getChunkComponent(index, WorldChunk.getComponentType());
                if (chunk == null || chunk.shouldKeepLoaded()) {
                    continue;
                }
                if (!decide(heldCount.get() + 1, currentBudget, 0L, maxHoldMs,
                        nearestDistance(players, ChunkUtil.xOfChunkIndex(index), ChunkUtil.zOfChunkIndex(index)), ring)) {
                    continue;
                }
                chunk.addKeepLoaded();
                mine.put(index, new Held(chunk, nowMs));
                heldCount.incrementAndGet();
                holds.increment();
                taken++;
            }
        }
        if (mine.isEmpty()) {
            held.remove(worldUuid, mine);
        }
    }

    /** World thread. Drops every reference this world holds (shutdown, retention turned off). */
    public void releaseWorld(World world) {
        Map<Long, Held> mine = held.remove(world.getWorldConfig().getUuid());
        if (mine == null) {
            return;
        }
        ChunkStore chunkStore = world.getChunkStore();
        for (Map.Entry<Long, Held> e : mine.entrySet()) {
            if (chunkStore.getChunkComponent(e.getKey(), WorldChunk.getComponentType()) == e.getValue().chunk) {
                e.getValue().chunk.removeKeepLoaded();
            }
            heldCount.decrementAndGet();
        }
    }

    /**
     * Pure hold rule. {@code count} includes the chunk being decided.
     * Below 90% of the budget anything is held; near the budget only chunks within the ring around
     * a player (the likeliest to be walked back into); over the budget nothing.
     */
    static boolean decide(int count, int budget, long heldForMs, long maxHoldMs,
                          double distanceBlocks, int ringBlocks) {
        if (budget <= 0 || heldForMs >= maxHoldMs) {
            return false;
        }
        if (count <= budget * NEAR_FULL) {
            return true;
        }
        return count <= budget && distanceBlocks <= ringBlocks;
    }

    /**
     * Chunk budget for the current heap: what is held now plus what fits under the post-GC ceiling.
     * Zero unless the tier is COMFORT, so any pressure releases everything on the next scan.
     */
    static int budgetFor(MemorySnapshot sample, int heldNow, double maxPostGcRatio, double mbPerChunk, int maxChunks) {
        if (sample == null || sample.tier() != MemoryTier.COMFORT
                || sample.heapMaxBytes() <= 0L || sample.postGcHeapUsedBytes() <= 0L || mbPerChunk <= 0.0D) {
            return 0;
        }
        double freeBytes = maxPostGcRatio * sample.heapMaxBytes() - sample.postGcHeapUsedBytes();
        long extra = (long) Math.floor(freeBytes / (mbPerChunk * BYTES_PER_MB));
        return (int) Math.max(0L, Math.min(maxChunks, heldNow + extra));
    }

    public int heldCount() {
        return heldCount.get();
    }

    public int budget() {
        return budget;
    }

    public String statusLine() {
        return String.format(Locale.ROOT,
                "retention %s held=%d budget=%d holds=%d returned=%d released=%d",
                config.chunkRetentionEnabled ? "on" : "off",
                heldCount.get(), budget, holds.sum(), returned.sum(), released.sum());
    }

    static double nearestDistance(double[] players, int chunkX, int chunkZ) {
        double cx = chunkX * (double) ChunkUtil.SIZE + ChunkUtil.SIZE / 2.0D;
        double cz = chunkZ * (double) ChunkUtil.SIZE + ChunkUtil.SIZE / 2.0D;
        double best = Double.MAX_VALUE;
        for (int i = 0; i + 1 < players.length; i += 2) {
            double dx = players[i] - cx;
            double dz = players[i + 1] - cz;
            best = Math.min(best, dx * dx + dz * dz);
        }
        return best == Double.MAX_VALUE ? best : Math.sqrt(best);
    }

    /** Flattens (x,z) pairs for {@link #scanWorld}. */
    public static double[] flatten(List<double[]> positions) {
        double[] out = new double[positions.size() * 2];
        for (int i = 0; i < positions.size(); i++) {
            out[i * 2] = positions.get(i)[0];
            out[i * 2 + 1] = positions.get(i)[1];
        }
        return out;
    }

    private record Held(WorldChunk chunk, long sinceMs) {
    }
}
