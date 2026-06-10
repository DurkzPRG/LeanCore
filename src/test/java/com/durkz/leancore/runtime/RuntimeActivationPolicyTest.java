package com.durkz.leancore.runtime;

import com.durkz.leancore.config.LeanCoreConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeActivationPolicyTest {

    @Test
    void autoScalesLiteToStandardWithFriends() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.localHostMode = "AUTO";
        config.localHostPassiveMode = false;
        assertEquals(RuntimeProfile.LITE, RuntimeActivationPolicy.resolveProfile(config, 1));
        assertEquals(RuntimeProfile.STANDARD, RuntimeActivationPolicy.resolveProfile(config, 2));
        assertEquals(RuntimeProfile.STANDARD, RuntimeActivationPolicy.resolveProfile(config, 8));
    }

    @Test
    void embeddedStandardProfileUpgradesSoloFromLite() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.embeddedStandardProfile = true;
        assertEquals(RuntimeProfile.STANDARD, RuntimeActivationPolicy.resolveProfile(config, 1));
    }

    @Test
    void dedicatedHostAlwaysFull() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.dedicatedServerMode = true;
        assertEquals(RuntimeProfile.FULL, RuntimeActivationPolicy.resolveProfile(config, 1));
        assertEquals(RuntimeProfile.FULL, RuntimeActivationPolicy.resolveProfile(config, 12));
    }

    @Test
    void passiveDisablesBackgroundRuntime() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.localHostMode = "PASSIVE";
        assertTrue(RuntimeActivationPolicy.isFullyPassive(config));
        assertFalse(RuntimeActivationPolicy.backgroundRuntimeEnabled(config));
        assertNull(RuntimeActivationPolicy.resolveProfile(config, 4));
    }

    @Test
    void legacyLocalHostPassiveModeFlag() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.localHostPassiveMode = true;
        assertTrue(RuntimeActivationPolicy.isFullyPassive(config));
    }

    @Test
    void disabledModIsPassive() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.enabled = false;
        assertTrue(RuntimeActivationPolicy.isFullyPassive(config));
        assertNull(RuntimeActivationPolicy.resolveProfile(config, 1));
    }
}
