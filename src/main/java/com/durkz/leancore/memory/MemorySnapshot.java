package com.durkz.leancore.memory;

public record MemorySnapshot(
        long heapUsedBytes,
        long heapMaxBytes,
        double heapUsedRatio,
        int onlinePlayers,
        double playerSpreadBlocks,
        MemoryTier tier
) {
}
