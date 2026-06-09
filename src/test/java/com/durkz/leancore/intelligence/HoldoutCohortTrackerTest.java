package com.durkz.leancore.intelligence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HoldoutCohortTrackerTest {

    @Test
    void tracksSeparateRollingAverages() {
        HoldoutCohortTracker tracker = new HoldoutCohortTracker();
        long now = System.currentTimeMillis();

        tracker.noteCohorts(2, 0, 0.70D, now);
        tracker.noteCohorts(0, 1, 0.80D, now + 1000L);

        assertTrue(tracker.treatmentHeap60s(now + 2000L) > 0.0D);
        assertTrue(tracker.holdoutHeap60s(now + 2000L) > tracker.treatmentHeap60s(now + 2000L));
        assertTrue(tracker.statusLine(now + 2000L).contains("delta="));
    }
}
