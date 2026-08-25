package com.durkz.leancore.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeanCoreConfigLiteTest {

    @Test
    void liteDefaultsAfterSanitize() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.normalizeDefaults();

        assertTrue(config.liteMemoryGovernorEnabled);
        assertTrue(config.liteLearningEnabled);
        assertTrue(config.liteViewRadiusEnabled);
        assertTrue(config.liteUnloadEnabled);
        assertEquals(0.85D, config.liteViewPressureThreshold, 0.001D);
        assertEquals(0.97D, config.liteViewComfortCapScale, 0.001D);
        assertEquals(8, config.liteUnloadMaxChunksPerSweep);
        assertEquals(180, config.liteUnloadIdleSeconds);
        assertFalse(config.chunkThroughputGovernanceEnabled);
        assertFalse(config.motionViewRadiusBoostEnabled);
        assertFalse(config.chunkPrefetchEnabled);
    }

    @Test
    void clampsInvalidLiteScales() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.liteViewComfortCapScale = 2.0D;
        config.liteViewWatchScale = -1.0D;
        config.liteViewPressureThreshold = 0.0D;
        config.normalizeDefaults();

        assertEquals(1.0D, config.liteViewComfortCapScale, 0.001D);
        assertEquals(0.50D, config.liteViewWatchScale, 0.001D);
        assertEquals(0.50D, config.liteViewPressureThreshold, 0.001D);
    }
}
