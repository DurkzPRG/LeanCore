package com.durkz.leancore.runtime;

import com.durkz.leancore.config.LeanCoreConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeProfileTest {

    @Test
    void liteSkipsHeavySubsystems() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.governEnabled = true;
        config.learningEnabled = true;
        config.hudFeatureEnabled = true;
        config.liteLearningEnabled = true;
        assertFalse(RuntimeProfile.LITE.runsGovernor(config));
        assertFalse(RuntimeProfile.LITE.runsLearning(config));
        assertTrue(RuntimeProfile.LITE.runsLiteLearning(config));
        assertEquals(30, RuntimeProfile.LITE.tickIntervalSeconds(config));
    }

    @Test
    void liteRunsHudWhenFeatureEnabled() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.hudFeatureEnabled = true;
        assertTrue(RuntimeProfile.LITE.runsHud(config));
        config.hudFeatureEnabled = false;
        assertFalse(RuntimeProfile.LITE.runsHud(config));
    }

    @Test
    void liteRunsLiteGovernorWhenEnabled() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.liteMemoryGovernorEnabled = true;
        assertTrue(RuntimeProfile.LITE.runsLiteGovernor(config));
        config.liteMemoryGovernorEnabled = false;
        assertFalse(RuntimeProfile.LITE.runsLiteGovernor(config));
        assertFalse(RuntimeProfile.STANDARD.runsLiteGovernor(config));
    }

    @Test
    void liteTracksPlayerMotion() {
        assertTrue(RuntimeProfile.LITE.tracksPlayerMotion());
        assertTrue(RuntimeProfile.STANDARD.tracksPlayerMotion());
    }

    @Test
    void liteRunsLiteLearningWhenEnabled() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.liteLearningEnabled = true;
        assertTrue(RuntimeProfile.LITE.runsLiteLearning(config));
        config.liteLearningEnabled = false;
        assertFalse(RuntimeProfile.LITE.runsLiteLearning(config));
        assertFalse(RuntimeProfile.STANDARD.runsLiteLearning(config));
    }

    @Test
    void standardHonorsConfigFlags() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.governEnabled = true;
        config.learningEnabled = false;
        assertTrue(RuntimeProfile.STANDARD.runsGovernor(config));
        assertFalse(RuntimeProfile.STANDARD.runsLearning(config));
        assertEquals(15, RuntimeProfile.STANDARD.tickIntervalSeconds(config));
    }
}
