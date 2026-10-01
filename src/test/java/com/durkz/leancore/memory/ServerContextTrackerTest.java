package com.durkz.leancore.memory;

import com.durkz.leancore.config.LeanCoreConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerContextTrackerTest {

    @Test
    void liveHeapAtCriticalOverridesLearnedContext() {
        LeanCoreConfig config = new LeanCoreConfig();
        ServerContextTracker tracker = new ServerContextTracker(config);

        assertEquals(MemoryTier.CRITICAL, tracker.resolveTier(config.criticalHeapRatio, MemoryTier.COMFORT));
    }

    @Test
    void lowLiveHeapStaysComfortWhateverTheRawHeapDid() {
        LeanCoreConfig config = new LeanCoreConfig();
        ServerContextTracker tracker = new ServerContextTracker(config);

        assertEquals(MemoryTier.COMFORT, tracker.resolveTier(0.40D, MemoryTier.COMFORT));
        assertEquals(MemoryTier.TIGHT, tracker.resolveTier(0.40D, MemoryTier.TIGHT));
    }
}
