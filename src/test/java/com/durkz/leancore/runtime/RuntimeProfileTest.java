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
        assertFalse(RuntimeProfile.LITE.runsGovernor(config));
        assertFalse(RuntimeProfile.LITE.runsLearning(config));
        assertFalse(RuntimeProfile.LITE.runsHud(config));
        assertEquals(30, RuntimeProfile.LITE.tickIntervalSeconds(config));
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
    void standardHonorsConfigFlags() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.governEnabled = true;
        config.learningEnabled = false;
        assertTrue(RuntimeProfile.STANDARD.runsGovernor(config));
        assertFalse(RuntimeProfile.STANDARD.runsLearning(config));
        assertEquals(15, RuntimeProfile.STANDARD.tickIntervalSeconds(config));
    }
}
