package com.durkz.leancore.memory;

/**
 * Per-runtime-session JVM heap peaks and cumulative governor counters for {@code /leancore savings}.
 */
public final class SessionSavingsTracker {

    private static final long BOOT_STABILIZE_MS = 60_000L;

    private final long sessionStartedMs = System.currentTimeMillis();

    private long bootBaselineUsedBytes = -1L;
    private long bootBaselineMaxBytes = -1L;
    private long peakUsedBytes = -1L;
    private long peakMaxBytes = -1L;
    private long peakAtMs;
    private int heapSampleCount;

    private int cumulativeDemotedZones;
    private int cumulativeReclaimedMbEstimate;
    private int engineUnloadYields;
    private boolean governorEverActive;
    private long firstGovernorActiveMs;

    public void reset() {
        // Session boundary is runtime restart; tracker is recreated with MemoryRuntime.
    }

    public void noteHeapSample(long usedBytes, long maxBytes, long nowMs) {
        if (usedBytes < 0L || maxBytes <= 0L) {
            return;
        }
        heapSampleCount++;
        if (bootBaselineUsedBytes < 0L) {
            bootBaselineUsedBytes = usedBytes;
            bootBaselineMaxBytes = maxBytes;
        }
        if (peakUsedBytes < 0L || usedBytes > peakUsedBytes) {
            peakUsedBytes = usedBytes;
            peakMaxBytes = maxBytes;
            peakAtMs = nowMs;
        }
    }

    public void noteGovernorTick(int demotedZones, int reclaimedMbEstimate) {
        if (demotedZones > 0) {
            cumulativeDemotedZones += demotedZones;
        }
        if (reclaimedMbEstimate > 0) {
            cumulativeReclaimedMbEstimate += reclaimedMbEstimate;
        }
        if (!governorEverActive) {
            governorEverActive = true;
            firstGovernorActiveMs = System.currentTimeMillis();
        }
    }

    public long sessionStartedMs() {
        return sessionStartedMs;
    }

    public long bootBaselineUsedBytes() {
        return bootBaselineUsedBytes;
    }

    public long bootBaselineMaxBytes() {
        return bootBaselineMaxBytes;
    }

    public long peakUsedBytes() {
        return peakUsedBytes;
    }

    public long peakMaxBytes() {
        return peakMaxBytes;
    }

    public long peakAtMs() {
        return peakAtMs;
    }

    public int heapSampleCount() {
        return heapSampleCount;
    }

    public int cumulativeDemotedZones() {
        return cumulativeDemotedZones;
    }

    public int cumulativeReclaimedMbEstimate() {
        return cumulativeReclaimedMbEstimate;
    }

    public void noteEngineUnloadYield() {
        engineUnloadYields++;
    }

    public int engineUnloadYields() {
        return engineUnloadYields;
    }

    public boolean governorEverActive() {
        return governorEverActive;
    }

    public long firstGovernorActiveMs() {
        return firstGovernorActiveMs;
    }

    public boolean bootStabilizing(long nowMs) {
        return nowMs - sessionStartedMs < BOOT_STABILIZE_MS;
    }
}
