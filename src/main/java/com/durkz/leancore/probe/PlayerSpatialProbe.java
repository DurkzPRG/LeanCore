package com.durkz.leancore.probe;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

public final class PlayerSpatialProbe {

    private PlayerSpatialProbe() {
    }

    public static SpatialSample read(PlayerRef ref) {
        SpatialSample chunks = readChunks(ref);
        return new SpatialSample(chunks.loadedChunks(), chunks.loadingChunks(), readWorldEntityCount(ref));
    }

    public static SpatialSample readChunks(PlayerRef ref) {
        if (ref == null || !ref.isValid()) {
            return SpatialSample.empty();
        }
        ChunkTracker tracker = ref.getChunkTracker();
        int loaded = 0;
        int loading = 0;
        if (tracker != null) {
            loaded = Math.max(0, tracker.getLoadedChunksCount());
            loading = Math.max(0, tracker.getLoadingChunksCount());
        }
        return new SpatialSample(loaded, loading, 0);
    }

    public static int readWorldEntityCount(PlayerRef ref) {
        if (ref == null || !ref.isValid()) {
            return 0;
        }
        Ref<EntityStore> entityRef = ref.getReference();
        if (entityRef == null) {
            return 0;
        }
        Store<EntityStore> store = entityRef.getStore();
        if (store == null) {
            return 0;
        }
        return Math.max(0, store.getEntityCount());
    }

    public record SpatialSample(int loadedChunks, int loadingChunks, int worldEntities) {
        public static SpatialSample empty() {
            return new SpatialSample(0, 0, 0);
        }

        public double chunkPressure() {
            return loadedChunks + loadingChunks * 0.5D;
        }
    }
}
