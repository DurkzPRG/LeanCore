package com.durkz.leancore.intelligence;

import com.durkz.leancore.runtime.WorldDispatch;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks engine-driven chunk unloads without registering ChunkUnloadEvent listeners.
 * Hytale's parallel ChunkUnloadingSystem cannot safely dispatch ECS events from worker threads.
 */
public final class EngineUnloadPoller {

    private final Map<UUID, Integer> lastLoadedByWorld = new ConcurrentHashMap<>();

    public void poll(Collection<PlayerRef> online, UnloadOutcomeTracker tracker) {
        if (tracker == null) {
            return;
        }
        Set<UUID> worldIds = new HashSet<>();
        if (online != null) {
            for (PlayerRef ref : online) {
                if (ref != null && ref.isValid() && ref.getWorldUuid() != null) {
                    worldIds.add(ref.getWorldUuid());
                }
            }
        }
        for (UUID worldId : worldIds) {
            World world = Universe.get().getWorld(worldId);
            if (world == null || !world.isAlive()) {
                lastLoadedByWorld.remove(worldId);
                continue;
            }
            // getLoadedChunksCount() touches the world's chunk store, so it must run on the world
            // thread. Skip this world's delta on a timed-out dispatch rather than read off-thread.
            int[] loadedHolder = {-1};
            boolean done = WorldDispatch.run(world, () -> loadedHolder[0] = world.getChunkStore().getLoadedChunksCount());
            if (!done || loadedHolder[0] < 0) {
                continue;
            }
            int loaded = loadedHolder[0];
            Integer previous = lastLoadedByWorld.put(worldId, loaded);
            if (previous != null && loaded < previous) {
                tracker.noteEngineUnloads(previous - loaded);
            }
        }
    }
}
