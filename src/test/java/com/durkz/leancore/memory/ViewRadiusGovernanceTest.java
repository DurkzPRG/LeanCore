package com.durkz.leancore.memory;

import com.durkz.leancore.config.LeanCoreConfig;
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
}
