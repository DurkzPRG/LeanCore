package com.durkz.leancore.intelligence;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyBlacklistTrackerTest {

    @Test
    void keepsActiveBlacklistEntries() {
        PolicyBlacklistTracker tracker = new PolicyBlacklistTracker();
        long now = System.currentTimeMillis();
        tracker.blacklist("AUTO:TIGHT", now + 60_000L);

        assertTrue(tracker.isBlacklisted("AUTO:TIGHT"));
        assertEquals(1, tracker.activeCount(now));
    }

    @Test
    void expiresBlacklistEntriesLazily() {
        PolicyBlacklistTracker tracker = new PolicyBlacklistTracker();
        long now = System.currentTimeMillis();
        tracker.blacklist("AUTO:TIGHT", now - 1L);

        assertFalse(tracker.isBlacklisted("AUTO:TIGHT"));
        assertEquals(0, tracker.activeCount(now));
    }

    @Test
    void hydrateSkipsExpiredEntries() {
        PolicyBlacklistTracker tracker = new PolicyBlacklistTracker();
        long now = System.currentTimeMillis();
        tracker.hydrate(Map.of(
                "AUTO:TIGHT", now + 60_000L,
                "AUTO:CRITICAL", now - 1L
        ), now);

        assertTrue(tracker.isBlacklisted("AUTO:TIGHT"));
        assertFalse(tracker.isBlacklisted("AUTO:CRITICAL"));
        assertEquals(1, tracker.activeCount(now));
    }
}
