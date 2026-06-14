package com.durkz.leancore.intelligence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HoldoutCohortTrackerTest {

    @Test
    void tracksSeparateRollingAverages() {
        HoldoutCohortTracker tracker = new HoldoutCohortTracker();
        long now = System.currentTimeMillis();

        tracker.noteCohorts(2, 0, 0.70D, now);
        tracker.noteCohorts(2, 1, 0.80D, now + 1000L);

        assertTrue(tracker.treatmentHeap60s(now + 2000L) > 0.0D);
        assertTrue(tracker.holdoutHeap60s(now + 2000L) > tracker.treatmentHeap60s(now + 2000L));
        assertTrue(tracker.statusLine(now + 2000L).contains("delta="));
    }

    @Test
    void soloShowsInsufficientCohortMessage() {
        HoldoutCohortTracker tracker = new HoldoutCohortTracker();
        long now = System.currentTimeMillis();

        tracker.noteCohorts(1, 0, 0.65D, now);

        String line = tracker.statusLine(now + 1000L);
        assertTrue(line.contains("holdout cohort n/a"));
        assertTrue(line.contains("need 2+ online"));
        assertFalse(line.contains("delta="));
    }
}
