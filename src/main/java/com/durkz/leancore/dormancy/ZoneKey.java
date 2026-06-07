package com.durkz.leancore.dormancy;

import java.util.UUID;

public record ZoneKey(UUID worldUuid, int regionX, int regionZ) {

    // 4x4 chunks per region — coarse enough for dormancy, fine enough for co-op bases.
    private static final int REGION_CHUNKS = 4;

    public static ZoneKey fromBlockCoords(UUID worldUuid, double blockX, double blockZ) {
        int chunkX = (int) Math.floor(blockX / 16.0D);
        int chunkZ = (int) Math.floor(blockZ / 16.0D);
        return new ZoneKey(
                worldUuid,
                Math.floorDiv(chunkX, REGION_CHUNKS),
                Math.floorDiv(chunkZ, REGION_CHUNKS)
        );
    }

    @Override
    public String toString() {
        return regionX + "," + regionZ;
    }
}
