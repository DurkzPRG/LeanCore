package com.durkz.leancore.memory;

import com.durkz.leancore.config.LeanCoreConfig;

/**
 * Adaptive chunk-throughput math. Turns a memory tier into a target chunk send-rate, expressed as a
 * percentage of the player's connection-aware engine baseline (local 256 / LAN 128 / remote 36 per
 * second, and 4 per tick). WATCH always returns the baseline (100%); COMFORT speeds loading up,
 * TIGHT and CRITICAL throttle it down. Pure math, no engine access; the baseline is captured per
 * player on the world thread by {@code PolicyApplier}.
 */
public final class ChunkThroughputModel {

    // Never throttle the per-second rate below this. setMaxSectionsPerSecond divides by the value, so it
    // must stay well clear of zero, and the streaming must not stall even under critical pressure.
    static final int MIN_CHUNKS_PER_SECOND = 8;

    private ChunkThroughputModel() {
    }

    /** Percentage of the engine baseline to apply at this tier (WATCH is always 100). */
    public static int percentForTier(LeanCoreConfig config, MemoryTier tier) {
        return switch (tier) {
            case COMFORT -> config.chunkThroughputComfortPct;
            case WATCH -> 100;
            case TIGHT -> config.chunkThroughputTightPct;
            case CRITICAL -> config.chunkThroughputCriticalPct;
        };
    }

    /**
     * Effective percentage including the COMFORT drain boost. When the player is actively streaming
     * (backlog above the streaming threshold) and the tier is COMFORT, the rate is lifted to the
     * drain-boost percentage to clear a join/teleport backlog faster. Other tiers are unaffected, so
     * the boost only ever spends heap headroom.
     */
    public static int effectivePercent(LeanCoreConfig config, MemoryTier tier, int loadingBacklog) {
        int base = percentForTier(config, tier);
        if (config.chunkThroughputDrainBoostEnabled
                && tier == MemoryTier.COMFORT
                && loadingBacklog > Math.max(0, config.unloadHoldWhenLoadingAbove)) {
            return Math.max(base, config.chunkThroughputDrainBoostPct);
        }
        return base;
    }

    /** Target max chunks/second for a player whose engine baseline is {@code baselinePerSecond}. */
    public static int targetPerSecond(LeanCoreConfig config, MemoryTier tier, int baselinePerSecond) {
        return targetPerSecond(config, tier, baselinePerSecond, 0);
    }

    public static int targetPerSecond(
            LeanCoreConfig config, MemoryTier tier, int baselinePerSecond, int loadingBacklog) {
        int scaled = (int) Math.round(
                baselinePerSecond * (effectivePercent(config, tier, loadingBacklog) / 100.0D));
        return Math.max(MIN_CHUNKS_PER_SECOND, scaled);
    }

    /** Target max chunks/tick for a player whose engine baseline is {@code baselinePerTick}. */
    public static int targetPerTick(LeanCoreConfig config, MemoryTier tier, int baselinePerTick) {
        return targetPerTick(config, tier, baselinePerTick, 0);
    }

    public static int targetPerTick(
            LeanCoreConfig config, MemoryTier tier, int baselinePerTick, int loadingBacklog) {
        int scaled = (int) Math.round(
                baselinePerTick * (effectivePercent(config, tier, loadingBacklog) / 100.0D));
        return Math.max(1, scaled);
    }
}
