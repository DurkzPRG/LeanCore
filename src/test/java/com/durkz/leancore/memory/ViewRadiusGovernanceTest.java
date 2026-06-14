package com.durkz.leancore.memory;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.runtime.RuntimeProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewRadiusGovernanceTest {

    @Test
    void soloEmbeddedSkipsWithoutDedicatedServerMode() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.viewRadiusGovernanceEnabled = true;
        config.dedicatedServerMode = false;
        assertFalse(ViewRadiusGovernance.shouldApply(config, 1, false));
    }

    @Test
    void soloAppliesWhenDedicatedServerMode() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.viewRadiusGovernanceEnabled = true;
        config.dedicatedServerMode = true;
        assertTrue(ViewRadiusGovernance.shouldApply(config, 1, false));
    }

    @Test
    void graceWindowBlocksApply() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.viewRadiusGovernanceEnabled = true;
        config.dedicatedServerMode = true;
        assertFalse(ViewRadiusGovernance.shouldApply(config, 1, true));
    }

    @Test
    void multiPlayerAppliesWhenEnabled() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.viewRadiusGovernanceEnabled = true;
        assertTrue(ViewRadiusGovernance.shouldApply(config, 3, false));
    }

    @Test
    void liteAllowsSoloEmbeddedWhenConfigured() {
        LeanCoreConfig config = new LeanCoreConfig();
        long started = 1_000_000L;
        long now = started + 700_000L;
        assertTrue(ViewRadiusGovernance.shouldApply(
                config, RuntimeProfile.LITE, 1, false, now, started));
    }

    @Test
    void liteBlocksDuringLoginGrace() {
        LeanCoreConfig config = new LeanCoreConfig();
        long started = 1_000_000L;
        long now = started + 60_000L;
        assertFalse(ViewRadiusGovernance.shouldApply(
                config, RuntimeProfile.LITE, 1, false, now, started));
    }

    @Test
    void liteBlocksWhenGovernorDisabled() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.liteMemoryGovernorEnabled = false;
        assertFalse(ViewRadiusGovernance.shouldApply(
                config, RuntimeProfile.LITE, 1, false, 9_000_000L, 1_000_000L));
    }

    @Test
    void liteBlocksMultiPlayer() {
        LeanCoreConfig config = new LeanCoreConfig();
        assertFalse(ViewRadiusGovernance.shouldApply(
                config, RuntimeProfile.LITE, 2, false, 9_000_000L, 1_000_000L));
    }

    @Test
    void standardPathIgnoresLiteFlags() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.liteMemoryGovernorEnabled = true;
        config.liteViewRadiusEnabled = true;
        config.viewRadiusGovernanceEnabled = false;
        assertFalse(ViewRadiusGovernance.shouldApply(
                config, RuntimeProfile.STANDARD, 1, false, 9_000_000L, 1_000_000L));
    }
}
