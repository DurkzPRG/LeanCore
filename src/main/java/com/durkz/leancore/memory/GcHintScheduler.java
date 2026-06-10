package com.durkz.leancore.memory;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.runtime.RuntimeProfile;

/**
 * Optional JVM GC hint during solo LITE idle windows. Off by default — enable only after profiling.
 */
public final class GcHintScheduler {

    private final LeanCoreConfig config;

    private volatile long lastHintMs = -1L;
    private volatile int hintCount;

    public GcHintScheduler(LeanCoreConfig config) {
        this.config = config;
    }

    public boolean maybeHint(long nowMs, long soloIdleSec, MemoryTier tier, RuntimeProfile profile) {
        if (!config.gcHintEnabled || profile != RuntimeProfile.LITE || tier != MemoryTier.COMFORT) {
            return false;
        }
        if (soloIdleSec < config.soloIdleThresholdSeconds) {
            return false;
        }
        long minIntervalMs = Math.max(60, config.gcHintMinIntervalSeconds) * 1000L;
        if (lastHintMs >= 0L && nowMs - lastHintMs < minIntervalMs) {
            return false;
        }
        lastHintMs = nowMs;
        hintCount++;
        System.gc();
        return true;
    }

    public int hintCount() {
        return hintCount;
    }

    public long lastHintMs() {
        return lastHintMs;
    }
}
