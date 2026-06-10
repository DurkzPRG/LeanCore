package com.durkz.leancore.memory;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.runtime.RuntimeProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GcHintSchedulerTest {

    @Test
    void disabledByDefault() {
        LeanCoreConfig config = new LeanCoreConfig();
        GcHintScheduler scheduler = new GcHintScheduler(config);
        assertFalse(scheduler.maybeHint(60_000L, 600L, MemoryTier.COMFORT, RuntimeProfile.LITE));
        assertEquals(0, scheduler.hintCount());
    }

    @Test
    void requiresLiteComfortAndIdle() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.gcHintEnabled = true;
        config.soloIdleThresholdSeconds = 300;
        GcHintScheduler scheduler = new GcHintScheduler(config);

        assertFalse(scheduler.maybeHint(1_000L, 600L, MemoryTier.WATCH, RuntimeProfile.LITE));
        assertFalse(scheduler.maybeHint(1_000L, 600L, MemoryTier.COMFORT, RuntimeProfile.FULL));
        assertFalse(scheduler.maybeHint(1_000L, 120L, MemoryTier.COMFORT, RuntimeProfile.LITE));
        assertTrue(scheduler.maybeHint(1_000L, 600L, MemoryTier.COMFORT, RuntimeProfile.LITE));
        assertEquals(1, scheduler.hintCount());
    }

    @Test
    void respectsMinInterval() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.gcHintEnabled = true;
        config.gcHintMinIntervalSeconds = 600;
        config.soloIdleThresholdSeconds = 60;
        GcHintScheduler scheduler = new GcHintScheduler(config);

        assertTrue(scheduler.maybeHint(0L, 120L, MemoryTier.COMFORT, RuntimeProfile.LITE));
        assertFalse(scheduler.maybeHint(30_000L, 120L, MemoryTier.COMFORT, RuntimeProfile.LITE));
        assertTrue(scheduler.maybeHint(601_000L, 120L, MemoryTier.COMFORT, RuntimeProfile.LITE));
        assertEquals(2, scheduler.hintCount());
    }
}
