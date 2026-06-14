package com.durkz.leancore.memory;

import com.durkz.leancore.config.LeanCoreConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LiteViewScaleResolverTest {

    private static LeanCoreConfig config() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.liteViewPressureThreshold = 0.85D;
        config.liteViewComfortCapScale = 0.97D;
        config.liteViewWatchScale = 0.94D;
        config.liteViewTightScale = 0.88D;
        config.liteViewCriticalScale = 0.76D;
        return config;
    }

    @Test
    void comfortLowPressureStaysAtFullScale() {
        assertEquals(1.0D, LiteViewScaleResolver.resolvePolicyViewScale(
                config(), MemoryTier.COMFORT, 0.50D), 0.001D);
    }

    @Test
    void comfortHighPressureAppliesCapScale() {
        LeanCoreConfig config = config();
        config.liteViewPressureThreshold = 0.85D;
        config.liteViewComfortCapScale = 0.97D;
        assertEquals(0.97D, LiteViewScaleResolver.resolvePolicyViewScale(
                config, MemoryTier.COMFORT, 0.90D), 0.001D);
    }

    @Test
    void watchTierUsesWatchScale() {
        LeanCoreConfig config = config();
        config.liteViewWatchScale = 0.94D;
        assertEquals(0.94D, LiteViewScaleResolver.resolvePolicyViewScale(
                config, MemoryTier.WATCH, 0.0D), 0.001D);
    }

    @Test
    void tightAndCriticalUseConfiguredScales() {
        LeanCoreConfig config = config();
        config.liteViewTightScale = 0.88D;
        config.liteViewCriticalScale = 0.76D;
        assertEquals(0.88D, LiteViewScaleResolver.resolvePolicyViewScale(
                config, MemoryTier.TIGHT, 0.0D), 0.001D);
        assertEquals(0.76D, LiteViewScaleResolver.resolvePolicyViewScale(
                config, MemoryTier.CRITICAL, 0.0D), 0.001D);
    }

    @Test
    void usesMinOfTierAndPressureInComfort() {
        LeanCoreConfig config = config();
        config.liteViewComfortCapScale = 0.97D;
        config.liteViewWatchScale = 0.94D;
        // If tier were WATCH with low saturation, watch wins; COMFORT high pressure uses cap
        assertEquals(0.97D, LiteViewScaleResolver.resolvePolicyViewScale(
                config, MemoryTier.COMFORT, 1.0D), 0.001D);
    }
}
