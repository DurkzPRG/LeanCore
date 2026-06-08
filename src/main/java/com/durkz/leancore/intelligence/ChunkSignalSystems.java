package com.durkz.leancore.intelligence;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.universe.world.events.ecs.ChunkUnloadEvent;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

public final class ChunkSignalSystems {

    private ChunkSignalSystems() {
    }

    public static void register(ComponentRegistryProxy<ChunkStore> registry, UnloadOutcomeTracker unloadTracker) {
        registry.registerSystem(new OnChunkUnload(unloadTracker));
    }

    private static final class OnChunkUnload extends EntityEventSystem<ChunkStore, ChunkUnloadEvent> {
        private final UnloadOutcomeTracker unloadTracker;

        OnChunkUnload(UnloadOutcomeTracker unloadTracker) {
            super(ChunkUnloadEvent.class);
            this.unloadTracker = unloadTracker;
        }

        @Override
        public Query<ChunkStore> getQuery() {
            return Query.any();
        }

        @Override
        public void handle(
                int index,
                ArchetypeChunk<ChunkStore> chunk,
                Store<ChunkStore> store,
                CommandBuffer<ChunkStore> buf,
                ChunkUnloadEvent event
        ) {
            unloadTracker.noteEngineUnload();
        }
    }
}
