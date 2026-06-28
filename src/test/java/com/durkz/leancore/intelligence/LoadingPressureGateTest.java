package com.durkz.leancore.intelligence;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.memory.MemoryTier;
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

    @Test
    void radiusGraceHoldsReductionWhileStreaming() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.loadingPressureSignalEnabled = true;
        config.unloadHoldWhenLoadingAbove = 16;
        // target below current (a cut), heavy streaming, not critical -> held.
        assertTrue(LoadingPressureGate.holdsRadiusReduction(config, MemoryTier.TIGHT, 20, 6, 10));
    }

    @Test
    void radiusGraceNeverHoldsAtCritical() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.loadingPressureSignalEnabled = true;
        config.unloadHoldWhenLoadingAbove = 16;
        assertFalse(LoadingPressureGate.holdsRadiusReduction(config, MemoryTier.CRITICAL, 999, 6, 10));
    }

    @Test
    void radiusGraceOnlyAppliesToReductions() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.loadingPressureSignalEnabled = true;
        config.unloadHoldWhenLoadingAbove = 16;
        // target >= current is an increase/hold, never graced.
        assertFalse(LoadingPressureGate.holdsRadiusReduction(config, MemoryTier.WATCH, 999, 12, 10));
        assertFalse(LoadingPressureGate.holdsRadiusReduction(config, MemoryTier.WATCH, 999, 10, 10));
    }

    @Test
    void radiusGraceOpensWhenBacklogLow() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.loadingPressureSignalEnabled = true;
        config.unloadHoldWhenLoadingAbove = 16;
        assertFalse(LoadingPressureGate.holdsRadiusReduction(config, MemoryTier.TIGHT, 16, 6, 10));
    }

    @Test
    void radiusGraceOffWhenSignalDisabled() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.loadingPressureSignalEnabled = false;
        config.unloadHoldWhenLoadingAbove = 16;
        assertFalse(LoadingPressureGate.holdsRadiusReduction(config, MemoryTier.TIGHT, 999, 6, 10));
    }
}
