package com.durkz.leancore.memory;

import com.durkz.leancore.config.LeanCoreConfig;

/**
 * LITE solo view-scale from heap tier and section saturation (loaded sections / 3D view budget).
 * Demand {@link com.durkz.leancore.intelligence.RetentionDemand#viewScale()} is applied later in {@link PolicyApplier}.
 */
public final class LiteViewScaleResolver {

    private static final double MIN_SCALE = 0.50D;
    private static final double MAX_SCALE = 1.0D;

    private LiteViewScaleResolver() {
    }

    public static GovernorPolicy policyFor(LeanCoreConfig config, MemoryTier tier, double chunkSaturation) {
        GovernorPolicy base = GovernorPolicy.forTier(GovernorPreset.SOLO_LEAN, tier);
        double scale = resolvePolicyViewScale(config, tier, chunkSaturation);
        return new GovernorPolicy(GovernorPreset.SOLO_LEAN, tier, scale, base.demoteBatch());
    }

    public static double resolvePolicyViewScale(
            LeanCoreConfig config,
            MemoryTier tier,
            double chunkSaturation
    ) {
        if (config == null || tier == null) {
            return MAX_SCALE;
        }
        double tierScale = scaleForTier(config, tier);
        double pressureScale = scaleForChunkPressure(config, tier, chunkSaturation);
        return clamp(Math.min(tierScale, pressureScale));
    }

    static double scaleForTier(LeanCoreConfig config, MemoryTier tier) {
        return switch (tier) {
            case COMFORT -> MAX_SCALE;
            case WATCH -> config.liteViewWatchScale;
            case TIGHT -> config.liteViewTightScale;
            case CRITICAL -> config.liteViewCriticalScale;
        };
    }

    static double scaleForChunkPressure(LeanCoreConfig config, MemoryTier tier, double chunkSaturation) {
        if (tier == MemoryTier.COMFORT && chunkSaturation >= config.liteViewPressureThreshold) {
            return config.liteViewComfortCapScale;
        }
        return MAX_SCALE;
    }

    private static double clamp(double scale) {
        if (Double.isNaN(scale) || Double.isInfinite(scale)) {
            return MAX_SCALE;
        }
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
    }
}
