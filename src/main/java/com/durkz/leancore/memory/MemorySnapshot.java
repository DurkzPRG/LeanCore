package com.durkz.leancore.memory;

public record MemorySnapshot(
        long heapUsedBytes,
        long heapMaxBytes,
        double heapUsedRatio,
        int onlinePlayers,
        double playerSpreadBlocks,
        MemoryTier tier,
        long oldGenUsedBytes,
        long oldGenMaxBytes,
        long postGcHeapUsedBytes,
        long allocationBytesPerSecond,
        double gcPauseRatio,
        double heapSlopePerSecond,
        double secondsToTight,
        String pressureReason
) {

    public MemorySnapshot(
            long heapUsedBytes,
            long heapMaxBytes,
            double heapUsedRatio,
            int onlinePlayers,
            double playerSpreadBlocks,
            MemoryTier tier
    ) {
        this(heapUsedBytes, heapMaxBytes, heapUsedRatio, onlinePlayers, playerSpreadBlocks, tier,
                0L, 0L, heapUsedBytes, 0L, 0.0D, 0.0D,
                Double.POSITIVE_INFINITY, "heap");
    }

    public double oldGenUsedRatio() {
        return oldGenMaxBytes <= 0L ? 0.0D : (double) oldGenUsedBytes / oldGenMaxBytes;
    }

    public double postGcHeapUsedRatio() {
        return heapMaxBytes <= 0L ? 0.0D : (double) postGcHeapUsedBytes / heapMaxBytes;
    }
}
