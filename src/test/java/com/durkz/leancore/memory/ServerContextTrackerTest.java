package com.durkz.leancore.memory;

import com.durkz.leancore.config.LeanCoreConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerContextTrackerTest {

    @Test
    void hardCriticalThresholdOverridesLearnedContext() {
        LeanCoreConfig config = new LeanCoreConfig();
        ServerContextTracker tracker = new ServerContextTracker(config);

        MemoryTier tier = tracker.resolveTier(0.50D, config.criticalHeapRatio, MemoryTier.COMFORT);

        assertEquals(MemoryTier.CRITICAL, tier);
    }
}
