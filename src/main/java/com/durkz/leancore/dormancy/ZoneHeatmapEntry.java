package com.durkz.leancore.dormancy;

public record ZoneHeatmapEntry(
        ZoneKey key,
        ZoneState state,
        long idleMinutes,
        boolean pinned,
        int distanceBlocks
) {
}
