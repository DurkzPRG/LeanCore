package com.durkz.leancore.probe;

import com.durkz.leancore.dormancy.ZoneKey;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.EntityChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import java.util.Locale;
import java.util.UUID;

public final class RegionalEntityProbe {

    private RegionalEntityProbe() {
    }

    public static RegionalEntitySample read(PlayerRef ref, World world) {
        if (ref == null || !ref.isValid() || world == null || !world.isAlive()) {
            return RegionalEntitySample.empty();
        }
        Transform transform = ref.getTransform();
        if (transform == null || transform.getPosition() == null) {
            return RegionalEntitySample.empty();
        }
        UUID worldUuid = ref.getWorldUuid();
        if (worldUuid == null) {
            return RegionalEntitySample.empty();
        }

        ZoneKey zone = ZoneKey.fromBlockCoords(
                worldUuid,
                transform.getPosition().x,
                transform.getPosition().z
        );
        ChunkStore chunkStore = world.getChunkStore();
        int regionChunks = ZoneKey.regionChunks();
        int baseChunkX = zone.regionX() * regionChunks;
        int baseChunkZ = zone.regionZ() * regionChunks;

        int entities = 0;
        int chunksSampled = 0;
        for (int dx = 0; dx < regionChunks; dx++) {
            for (int dz = 0; dz < regionChunks; dz++) {
                long index = ChunkUtil.indexChunk(baseChunkX + dx, baseChunkZ + dz);
                WorldChunk worldChunk = chunkStore.getChunkComponent(index, WorldChunk.getComponentType());
                if (worldChunk == null) {
                    continue;
                }
                chunksSampled++;
                EntityChunk entityChunk = worldChunk.getEntityChunk();
                if (entityChunk == null) {
                    continue;
                }
                var refs = entityChunk.getEntityReferences();
                if (refs != null) {
                    entities += refs.size();
                }
            }
        }

        int worldEntities = PlayerSpatialProbe.readWorldEntityCount(ref);
        return new RegionalEntitySample(zone, chunksSampled, entities, worldEntities);
    }

    public record RegionalEntitySample(
            ZoneKey zone,
            int chunksSampled,
            int regionalEntities,
            int worldEntities
    ) {
        public static RegionalEntitySample empty() {
            return new RegionalEntitySample(null, 0, 0, 0);
        }

        public String probeLine() {
            if (zone == null) {
                return "S4 entities: partial worldEntities=0 (no zone)";
            }
            return String.format(Locale.ROOT,
                    "S4 entities: ok regional=%d world=%d zone=%s chunks=%d",
                    regionalEntities,
                    worldEntities,
                    zone,
                    chunksSampled);
        }
    }
}
