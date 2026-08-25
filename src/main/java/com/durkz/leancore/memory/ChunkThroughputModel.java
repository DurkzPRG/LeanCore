package com.durkz.leancore.memory;

import com.durkz.leancore.config.LeanCoreConfig;

/**
 * Adaptive chunk-throughput math. Turns a memory tier into a target chunk send-rate, expressed as a
 * percentage of the player's connection-aware engine baseline (local 2560 / LAN 1280 / remote 360 per
 * second, and 40 per tick). WATCH always returns the baseline (100%); COMFORT speeds loading up,
 * TIGHT and CRITICAL throttle it down. Pure math, no engine access; the baseline is captured per
 * player on the world thread by {@code PolicyApplier}.
 */
public final class ChunkThroughputModel {

    // Never throttle the per-second rate below this. setMaxSectionsPerSecond divides by the value, so it
    // must stay well clear of zero, and the streaming must not stall even under critical pressure.
    static final int MIN_CHUNKS_PER_SECOND = 8;

    // Engine local cap (ChunkTracker.MAX_SECTIONS_PER_SECOND_LOCAL). Drain boost must not exceed this.
    static final int MAX_SECTIONS_PER_SECOND = 2560;
    static final int MAX_SECTIONS_PER_TICK = 40;

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
        return Math.min(MAX_SECTIONS_PER_SECOND, Math.max(MIN_CHUNKS_PER_SECOND, scaled));
    }

    /** Target max chunks/tick for a player whose engine baseline is {@code baselinePerTick}. */
    public static int targetPerTick(LeanCoreConfig config, MemoryTier tier, int baselinePerTick) {
        return targetPerTick(config, tier, baselinePerTick, 0);
    }

    public static int targetPerTick(
            LeanCoreConfig config, MemoryTier tier, int baselinePerTick, int loadingBacklog) {
        int scaled = (int) Math.round(
                baselinePerTick * (effectivePercent(config, tier, loadingBacklog) / 100.0D));
        return Math.min(MAX_SECTIONS_PER_TICK, Math.max(1, scaled));
    }
}
