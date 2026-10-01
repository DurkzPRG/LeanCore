package com.durkz.leancore.memory;

import com.durkz.leancore.config.LeanCoreConfig;

/** Stateful rate and post-GC estimator fed by cheap JVM counters. */
final class MemoryPressureTracker {

    private static final double EMA_ALPHA = 0.25D;
    private static final int WARMUP_SAMPLES = 4;
    private static final int ESCALATION_SAMPLES = 2;
    private static final int RECOVERY_SAMPLES = 3;
    private static final int MIN_POST_GC_SAMPLES_FOR_PREDICTION = 3;
    private static final double PREDICT_TIGHT_SECONDS = 30.0D;
    private static final double PREDICT_WATCH_SECONDS = 60.0D;
    /**
     * Raw heap alone only means CRITICAL this close to the ceiling. G1 lets the raw heap swing to
     * 85-95% between young collections with a low live set; that is garbage, not pressure.
     */
    private static final double RAW_HEAP_HARD_CRITICAL = 0.97D;

    private long lastSampleNanos;
    private long lastAllocatedBytes;
    private long lastGcCount;
    private long lastGcTimeMs;
    private long postGcHeapUsed;
    private long lastPostGcSampleNanos;
    private double heapSlopePerSecond;
    private double gcPauseRatio;
    private int sampleCount;
    private int postGcSampleCount;
    private MemoryTier stableTier = MemoryTier.COMFORT;
    private MemoryTier candidateTier = MemoryTier.COMFORT;
    private int candidateSamples;
    private String stableReason = "warmup";

    Pressure observe(long nowNanos, long heapUsed, long heapMax, long oldGenUsed, long oldGenMax,
                     long allocatedBytes, long gcCount, long gcTimeMs, LeanCoreConfig config) {
        return observe(nowNanos, heapUsed, heapMax, oldGenUsed, oldGenMax, allocatedBytes, gcCount, gcTimeMs,
                -1L, config);
    }

    /**
     * @param measuredPostGcHeap heap used right after the last GC as reported by the collector, or
     *        {@code <= 0} when unknown; the current heap reading is used as a fallback then.
     */
    Pressure observe(long nowNanos, long heapUsed, long heapMax, long oldGenUsed, long oldGenMax,
                     long allocatedBytes, long gcCount, long gcTimeMs, long measuredPostGcHeap,
                     LeanCoreConfig config) {
        double heapRatio = ratio(heapUsed, heapMax);
        double oldGenRatio = ratio(oldGenUsed, oldGenMax);
        long allocationRate = 0L;
        double observedGcPauseRatio = 0.0D;

        if (lastSampleNanos > 0L && nowNanos > lastSampleNanos) {
            double elapsedSec = (nowNanos - lastSampleNanos) / 1_000_000_000.0D;
            if (allocatedBytes >= lastAllocatedBytes && lastAllocatedBytes > 0L) {
                allocationRate = Math.max(0L, Math.round((allocatedBytes - lastAllocatedBytes) / elapsedSec));
            }
            if (gcTimeMs >= lastGcTimeMs) {
                observedGcPauseRatio = Math.min(1.0D,
                        (gcTimeMs - lastGcTimeMs) / (elapsedSec * 1000.0D));
                gcPauseRatio += EMA_ALPHA * (observedGcPauseRatio - gcPauseRatio);
            }
        }

        boolean firstPostGcSample = postGcHeapUsed <= 0L;
        if (firstPostGcSample || gcCount > lastGcCount) {
            long previousEstimate = postGcHeapUsed;
            long afterGc = measuredPostGcHeap > 0L ? measuredPostGcHeap : heapUsed;
            postGcHeapUsed = firstPostGcSample
                    ? afterGc
                    : Math.round(postGcHeapUsed + EMA_ALPHA * (afterGc - postGcHeapUsed));
            postGcSampleCount++;
            if (!firstPostGcSample && lastPostGcSampleNanos > 0L && nowNanos > lastPostGcSampleNanos) {
                double postGcElapsedSec = (nowNanos - lastPostGcSampleNanos) / 1_000_000_000.0D;
                double liveSlope = (postGcHeapUsed - previousEstimate)
                        / (double) Math.max(1L, heapMax) / postGcElapsedSec;
                heapSlopePerSecond = heapSlopePerSecond == 0.0D
                        ? liveSlope
                        : heapSlopePerSecond + EMA_ALPHA * (liveSlope - heapSlopePerSecond);
            }
            lastPostGcSampleNanos = nowNanos;
        }

        lastSampleNanos = nowNanos;
        lastAllocatedBytes = allocatedBytes;
        lastGcCount = gcCount;
        lastGcTimeMs = gcTimeMs;
        sampleCount++;

        double secondsToTight = Double.POSITIVE_INFINITY;
        double postGcRatio = ratio(postGcHeapUsed, heapMax);
        if (postGcSampleCount >= MIN_POST_GC_SAMPLES_FOR_PREDICTION
                && heapSlopePerSecond > 0.000_001D && postGcRatio < config.tightHeapRatio) {
            secondsToTight = (config.tightHeapRatio - postGcRatio) / heapSlopePerSecond;
        }

        double effectiveRatio = Math.max(oldGenRatio, postGcRatio);
        MemoryTier desired = MemoryTier.COMFORT;
        String reason = "heap";
        boolean hardCritical = heapRatio >= RAW_HEAP_HARD_CRITICAL
                || oldGenRatio >= config.criticalHeapRatio || postGcRatio >= config.criticalHeapRatio;
        if (hardCritical) {
            desired = MemoryTier.CRITICAL;
            reason = heapRatio >= RAW_HEAP_HARD_CRITICAL ? "heap-critical"
                    : oldGenRatio >= postGcRatio ? "old-gen-critical" : "post-gc-critical";
        } else if (oldGenRatio >= config.tightHeapRatio || postGcRatio >= config.tightHeapRatio) {
            desired = MemoryTier.TIGHT;
            reason = oldGenRatio > postGcRatio ? "old-gen-tight" : "post-gc-tight";
        } else if (secondsToTight <= PREDICT_TIGHT_SECONDS) {
            desired = MemoryTier.TIGHT;
            reason = "heap-growth";
        } else if (oldGenRatio >= config.watchHeapRatio || postGcRatio >= config.watchHeapRatio
                || secondsToTight <= PREDICT_WATCH_SECONDS
                || gcPauseRatio >= 0.10D
                && Math.max(oldGenRatio, postGcRatio) >= config.watchHeapRatio * 0.85D) {
            desired = MemoryTier.WATCH;
            reason = gcPauseRatio >= 0.10D ? "gc-duty"
                    : secondsToTight <= PREDICT_WATCH_SECONDS ? "heap-growth"
                    : oldGenRatio > postGcRatio ? "old-gen" : "post-gc";
        }

        MemoryTier predicted = confirm(desired, reason, hardCritical);
        return new Pressure(effectiveRatio, postGcHeapUsed, allocationRate, gcPauseRatio,
                heapSlopePerSecond, secondsToTight, predicted, stableReason);
    }

    private MemoryTier confirm(MemoryTier desired, String reason, boolean hardCritical) {
        if (hardCritical) {
            stableTier = MemoryTier.CRITICAL;
            stableReason = reason;
            candidateTier = desired;
            candidateSamples = 0;
            return stableTier;
        }
        if (sampleCount < WARMUP_SAMPLES) {
            stableTier = MemoryTier.COMFORT;
            stableReason = "warmup";
            candidateTier = MemoryTier.COMFORT;
            candidateSamples = 0;
            return stableTier;
        }
        if (desired == stableTier) {
            stableReason = reason;
            candidateTier = desired;
            candidateSamples = 0;
            return stableTier;
        }
        if (desired != candidateTier) {
            candidateTier = desired;
            candidateSamples = 1;
            return stableTier;
        }
        candidateSamples++;
        int required = desired.ordinal() > stableTier.ordinal()
                ? ESCALATION_SAMPLES : RECOVERY_SAMPLES;
        if (candidateSamples < required) {
            return stableTier;
        }
        stableTier = desired.ordinal() < stableTier.ordinal()
                ? MemoryTier.values()[stableTier.ordinal() - 1]
                : desired;
        stableReason = reason;
        candidateTier = stableTier;
        candidateSamples = 0;
        return stableTier;
    }

    private static double ratio(long used, long max) {
        return max <= 0L ? 0.0D : Math.max(0.0D, Math.min(1.0D, (double) used / max));
    }

    record Pressure(double effectiveRatio, long postGcHeapUsed, long allocationBytesPerSecond,
                    double gcPauseRatio, double heapSlopePerSecond, double secondsToTight,
                    MemoryTier predictedTier, String reason) {
    }
}
