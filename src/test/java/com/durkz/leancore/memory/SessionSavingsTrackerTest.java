package com.durkz.leancore.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionSavingsTrackerTest {

    @Test
    void recordsBaselineAndPeak() {
        SessionSavingsTracker tracker = new SessionSavingsTracker();
        long start = tracker.sessionStartedMs();

        tracker.noteHeapSample(mb(1000), mb(4000), start + 1_000L);
        tracker.noteHeapSample(mb(1500), mb(4000), start + 2_000L);
        tracker.noteHeapSample(mb(1200), mb(4000), start + 3_000L);

        assertEquals(mb(1000), tracker.bootBaselineUsedBytes());
        assertEquals(mb(1500), tracker.peakUsedBytes());
        assertEquals(3, tracker.heapSampleCount());
    }

    @Test
    void accumulatesGovernorCounters() {
        SessionSavingsTracker tracker = new SessionSavingsTracker();
        tracker.noteGovernorTick(2, 12);
        tracker.noteGovernorTick(1, 6);

        assertEquals(3, tracker.cumulativeDemotedZones());
        assertEquals(18, tracker.cumulativeReclaimedMbEstimate());
        assertTrue(tracker.governorEverActive());
    }

    private static long mb(int megabytes) {
        return megabytes * 1024L * 1024L;
    }
}
