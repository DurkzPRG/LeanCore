package com.durkz.leancore.probe;

import com.durkz.leancore.runtime.WorldDispatch;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Collection;
import java.util.function.Supplier;

/**
 * Max loaded-section saturation (loaded sections / 3D view budget) across online players.
 * Must run on the world thread (reads {@link Player} view radius).
 */
public final class ChunkSaturationSampler {

    private final Supplier<Collection<PlayerRef>> playersSupplier;

    public ChunkSaturationSampler(Supplier<Collection<PlayerRef>> playersSupplier) {
        this.playersSupplier = playersSupplier;
    }

    public double sample() {
        Collection<PlayerRef> players = playersSupplier.get();
        if (players == null || players.isEmpty()) {
            return 0.0D;
        }
        double maxSaturation = 0.0D;
        for (PlayerRef playerRef : players) {
            if (playerRef == null || !playerRef.isValid() || playerRef.getWorldUuid() == null) {
                continue;
            }
            World world = Universe.get().getWorld(playerRef.getWorldUuid());
            if (world == null || !world.isAlive()) {
                continue;
            }
            // Chunk-tracker and Player view-radius reads need world affinity; run them on the world
            // thread and skip this player on a timed-out dispatch.
            double[] saturation = {0.0D};
            boolean done = WorldDispatch.run(world, () -> {
                PlayerSpatialProbe.SpatialSample spatial = PlayerSpatialProbe.readChunks(playerRef);
                int viewRadius = readServerViewRadius(playerRef);
                int budget = Math.max(1, ChunkPressureModel.viewSectionBudget(viewRadius));
                saturation[0] = Math.min(1.0D, (double) spatial.loadedChunks() / budget);
            });
            if (done) {
                maxSaturation = Math.max(maxSaturation, saturation[0]);
            }
        }
        return maxSaturation;
    }

    private static int readServerViewRadius(PlayerRef playerRef) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null) {
            return 1;
        }
        Store<EntityStore> store = ref.getStore();
        if (store == null) {
            return 1;
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return 1;
        }
        return Math.max(1, player.getViewRadius());
    }
}
