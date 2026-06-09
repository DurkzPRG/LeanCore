package com.durkz.leancore.runtime;

import com.durkz.leancore.config.LeanCoreConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoloRuntimePolicyTest {

    @Test
    void defersHeapSampleUntilIntervalElapsed() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.soloHeapSampleIntervalSeconds = 60;
        long now = 100_000L;
        assertTrue(SoloRuntimePolicy.shouldSampleHeap(config, now, 0L));
        assertFalse(SoloRuntimePolicy.shouldSampleHeap(config, now + 10_000L, now));
        assertTrue(SoloRuntimePolicy.shouldSampleHeap(config, now + 60_000L, now));
    }

    @Test
    void stretchesTickWhenPlayerIdle() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.soloTickIntervalSeconds = 30;
        config.soloIdleTickIntervalSeconds = 60;
        config.soloAdaptiveTickEnabled = true;
        config.soloIdleThresholdSeconds = 120;
        assertEquals(30, SoloRuntimePolicy.nextTickDelaySeconds(config, 30L));
        assertEquals(60, SoloRuntimePolicy.nextTickDelaySeconds(config, 400L));
    }
}
