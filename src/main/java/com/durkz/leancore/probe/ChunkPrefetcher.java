package com.durkz.leancore.probe;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.dormancy.PredictedPositionSource;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.GetChunkFlags;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.List;
import java.util.UUID;

/**
 * Predictive chunk prefetch actuator. For each moving player it asks the engine to warm a few chunks
 * just beyond the view edge along the predicted heading, so revisited (already-generated) terrain is
 * resident on arrival. It loads with {@code NO_GENERATE} (disk only, no worldgen) and never sets the
 * chunk ticking, skips chunks already loaded or on load-failure backoff, and never retains the
 * returned reference, so prefetched chunks stay subject to normal engine retention and unload.
 *
 * <p>Must be called on the owning world thread (it reads the {@link ChunkStore}).
 */
public final class ChunkPrefetcher {

    // Disk only, no worldgen; defer any ticking; let worldgen skip if no longer needed.
    private static final int PREFETCH_FLAGS =
            GetChunkFlags.NO_GENERATE | GetChunkFlags.NO_SET_TICKING_SYNC | GetChunkFlags.POLL_STILL_NEEDED;

    // Fallback when the client view radius is unknown; deliberately small to stay conservative.
    private static final int DEFAULT_VIEW_RADIUS_CHUNKS = 8;

    private final LeanCoreConfig config;
    private final PredictedPositionSource predictions;

    public ChunkPrefetcher(LeanCoreConfig config, PredictedPositionSource predictions) {
        this.config = config;
        this.predictions = predictions;
    }

    public void prefetchOnWorld(World world, List<PlayerRef> players) {
        if (world == null || !world.isAlive() || players == null || players.isEmpty()) {
            return;
        }
        ChunkStore store = world.getChunkStore();
        if (store == null) {
            return;
        }
        int max = config.chunkPrefetchMaxPerTick;
        if (max <= 0) {
            return;
        }
        LongSet loaded = store.getChunkIndexes();
        long horizonMs = config.chunkPrefetchHorizonMs;
        for (PlayerRef ref : players) {
            if (ref == null || !ref.isValid()) {
                continue;
            }
            UUID id = ref.getUuid();
            if (id == null) {
                continue;
            }
            double[] cur = predictions.currentXZ(id);
            double[] pred = predictions.predictedXZ(id, horizonMs);
            if (cur == null || pred == null || cur.length < 2 || pred.length < 2) {
                continue;
            }
            int viewRadius = predictions.viewRadiusChunks(id);
            if (viewRadius <= 0) {
                viewRadius = DEFAULT_VIEW_RADIUS_CHUNKS;
            }
            for (int[] chunk : ChunkPrefetchPlanner.plan(cur[0], cur[1], pred[0], pred[1], viewRadius, max)) {
                long index = ChunkUtil.indexChunk(chunk[0], chunk[1]);
                if (loaded.contains(index)
                        || store.isChunkOnBackoff(index, ChunkTracker.MAX_FAILURE_BACKOFF_NANOS)) {
                    continue;
                }
                try {
                    store.getChunkReferenceAsync(index, PREFETCH_FLAGS);
                } catch (RuntimeException ignored) {
                    // A prefetch is best-effort; never let a warm-up failure disturb the tick.
                }
            }
        }
    }
}
