package com.durkz.leancore.intelligence;

import com.durkz.leancore.config.LeanCoreConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadingPressureGateTest {

    @Test
    void holdsWhenBacklogAboveThreshold() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.loadingPressureSignalEnabled = true;
        config.unloadHoldWhenLoadingAbove = 16;
        assertTrue(LoadingPressureGate.holdsUnload(config, 17));
    }

    @Test
    void openWhenBacklogAtOrBelowThreshold() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.loadingPressureSignalEnabled = true;
        config.unloadHoldWhenLoadingAbove = 16;
        assertFalse(LoadingPressureGate.holdsUnload(config, 16));
        assertFalse(LoadingPressureGate.holdsUnload(config, 0));
    }

    @Test
    void openWhenSignalDisabledEvenWithHugeBacklog() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.loadingPressureSignalEnabled = false;
        config.unloadHoldWhenLoadingAbove = 16;
        assertFalse(LoadingPressureGate.holdsUnload(config, 9_999));
    }

    @Test
    void negativeThresholdIsClampedToZero() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.loadingPressureSignalEnabled = true;
        config.unloadHoldWhenLoadingAbove = -5;
        assertFalse(LoadingPressureGate.holdsUnload(config, 0));
        assertTrue(LoadingPressureGate.holdsUnload(config, 1));
    }

    @Test
    void openOnNullConfig() {
        assertFalse(LoadingPressureGate.holdsUnload(null, 100));
    }
}
