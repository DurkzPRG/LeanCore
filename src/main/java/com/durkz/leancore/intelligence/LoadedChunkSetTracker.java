package com.durkz.leancore.intelligence;

import com.hypixel.hytale.server.core.universe.world.World;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-chunk unload truth (v1.7.0 Frente A). Snapshots {@code ChunkStore.getChunkIndexes()} per
 * world and diffs against the previous snapshot to count exactly how many loaded chunks dropped
 * since the last poll. More precise than the net {@code getLoadedChunksCount()} delta, which cancels
 * out chunks that load and unload in the same interval.
 * <p>
 * Snapshots use primitive {@code LongOpenHashSet} (fastutil) to avoid boxing every chunk index, and
 * {@link #retainWorlds} drops snapshots for worlds no longer tracked so instanced worlds cannot leak.
 * {@link #diffRemoved} reads the live chunk store, so it MUST run on the owning world thread (called
 * from inside a {@code WorldDispatch.run}).
 * <p>
 * Each world keeps two reusable sets and swaps them every poll (double-buffering), so a steady world
 * does not allocate a fresh snapshot per refresh. {@code clear()} keeps the backing array, so the
 * copy of the live index set reuses capacity instead of growing a new table each time.
 */
public final class LoadedChunkSetTracker {

    private final Map<UUID, WorldSnapshots> byWorld = new ConcurrentHashMap<>();

    /**
     * Number of chunk indices present at the previous poll but gone now (removed since last call),
     * and refreshes the stored snapshot. The first poll for a world primes the snapshot and returns
     * 0 (no prior baseline to diff against). Must be called on the world thread.
     */
    public int diffRemoved(UUID worldUuid, World world) {
        if (worldUuid == null || world == null) {
            return 0;
        }
        WorldSnapshots snapshots = byWorld.computeIfAbsent(worldUuid, ignored -> new WorldSnapshots());
        LongSet live = world.getChunkStore().getChunkIndexes();
        snapshots.current.clear();
        if (live != null) {
            snapshots.current.addAll(live);
        }
        int removed = 0;
        if (snapshots.primed) {
            for (LongIterator it = snapshots.previous.iterator(); it.hasNext(); ) {
                if (!snapshots.current.contains(it.nextLong())) {
                    removed++;
                }
            }
        }
        // Swap: this poll's snapshot becomes the baseline; the old baseline buffer is reused next time.
        LongOpenHashSet reuse = snapshots.previous;
        snapshots.previous = snapshots.current;
        snapshots.current = reuse;
        snapshots.primed = true;
        return removed;
    }

    /** Drops the snapshot for a world that is no longer alive, so a reload re-primes cleanly. */
    public void forget(UUID worldUuid) {
        if (worldUuid != null) {
            byWorld.remove(worldUuid);
        }
    }

    /** Keeps only snapshots for the given worlds, dropping the rest so instanced worlds cannot leak. */
    public void retainWorlds(Set<UUID> aliveWorlds) {
        if (aliveWorlds == null) {
            return;
        }
        byWorld.keySet().removeIf(uuid -> !aliveWorlds.contains(uuid));
    }

    /** Two reusable index sets per world, swapped each poll. Touched only on that world's thread. */
    private static final class WorldSnapshots {
        private LongOpenHashSet current = new LongOpenHashSet();
        private LongOpenHashSet previous = new LongOpenHashSet();
        private boolean primed;
    }
}
