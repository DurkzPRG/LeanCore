package com.durkz.leancore.probe;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.durkz.leancore.dormancy.ZoneChunkUnloader;
import com.durkz.leancore.dormancy.ZoneDormancyMap;
import com.durkz.leancore.memory.MemoryTier;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ApiProbe {

    private ApiProbe() {
    }

    public static List<String> run(
            Store<EntityStore> store,
            Ref<EntityStore> ref,
            PlayerRef playerRef,
            World world,
            ZoneDormancyMap dormancyMap,
            ZoneChunkUnloader unloader,
            MemoryTier tier
    ) {
        List<String> out = new ArrayList<>(6);
        out.add("probe:");
        out.add(s1(store, ref));
        out.add(s2(playerRef));
        out.add(s3(store, ref, playerRef));
        out.add(s4(playerRef, world));
        out.add(s5(world, dormancyMap, unloader, tier));
        return out;
    }

    private static String s1(Store<EntityStore> store, Ref<EntityStore> ref) {
        if (ref == null) {
            return "S1 view-radius: fail (no entity ref)";
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return "S1 view-radius: fail (no Player component)";
        }
        return "S1 view-radius: ok server=" + player.getViewRadius()
                + " client=" + player.getClientViewRadius()
                + " write=setClientViewRadius";
    }

    private static String s2(PlayerRef p) {
        if (p == null || !p.isValid()) {
            return "S2 position: skip (no player)";
        }
        if (p.getTransform() == null || p.getTransform().getPosition() == null) {
            return "S2 position: fail";
        }
        var pos = p.getTransform().getPosition();
        return String.format(Locale.ROOT, "S2 position: ok %.0f %.0f %.0f world=%s",
                pos.x, pos.y, pos.z, p.getWorldUuid());
    }

    private static String s3(Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef p) {
        if (p == null || !p.isValid()) {
            return "S3 chunks: skip (no player)";
        }
        PlayerSpatialProbe.SpatialSample sample = PlayerSpatialProbe.readChunks(p);
        if (p.getChunkTracker() == null) {
            return "S3 chunks: fail (no ChunkTracker)";
        }
        int viewRadius = 16;
        Player player = ref != null ? store.getComponent(ref, Player.getComponentType()) : null;
        if (player != null) {
            viewRadius = Math.max(player.getViewRadius(), player.getClientViewRadius());
        }
        int budget = ChunkPressureModel.viewChunkBudget(viewRadius);
        double normalized = sample.normalizedPressure(viewRadius, -1);
        return String.format(Locale.ROOT,
                "S3 chunks: ok loaded=%d loading=%d raw=%.0f norm=%.1f view=%d budget=%d",
                sample.loadedChunks(),
                sample.loadingChunks(),
                sample.rawPressure(),
                normalized,
                viewRadius,
                budget);
    }

    private static String s4(PlayerRef p, World world) {
        if (p == null || !p.isValid()) {
            return "S4 entities: skip (no player)";
        }
        return RegionalEntityProbe.read(p, world).probeLine();
    }

    private static String s5(World world, ZoneDormancyMap dormancyMap, ZoneChunkUnloader unloader, MemoryTier tier) {
        if (world == null) {
            return "S5 unload: skip (no world)";
        }
        int candidates = dormancyMap.unloadCandidateZones(tier).size();
        return String.format(Locale.ROOT,
                "S5 unload: ok api=ChunkStore.remove(UNLOAD) candidates=%d lastUnloaded=%d storeLoaded=%d",
                candidates,
                unloader.lastUnloadedChunks(),
                world.getChunkStore().getLoadedChunksCount());
    }
}
