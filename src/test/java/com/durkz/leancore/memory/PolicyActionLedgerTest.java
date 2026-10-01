package com.durkz.leancore.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyActionLedgerTest {

    @Test
    void completesComparableWindowAndCountsRevisits() {
        PolicyActionLedger ledger = new PolicyActionLedger();
        ledger.record("unload:4", sample(0.60D, 1), 2, 4, 3, 1_000L);
        ledger.observe(sample(0.50D, 1), 3, 6, 61_000L);

        String status = ledger.statusLine();
        assertTrue(status.contains("completed=1"));
        assertTrue(status.contains("postGcDelta=-10.0pp"));
        assertTrue(status.contains("revisits=1"));
        assertTrue(status.contains("engine=2"));
        assertTrue(status.contains("candidates=3"));
    }

    @Test
    void discardsWindowWhenPlayerCountChanges() {
        PolicyActionLedger ledger = new PolicyActionLedger();
        ledger.record("policy:TIGHT", sample(0.60D, 1), 0, 0, 0, 1_000L);
        ledger.observe(sample(0.55D, 2), 0, 0, 2_000L);

        assertTrue(ledger.statusLine().contains("discarded=1"));
    }

    private static MemorySnapshot sample(double ratio, int players) {
        long max = 1_000L;
        long used = Math.round(max * ratio);
        return new MemorySnapshot(used, max, ratio, players, 0.0D, MemoryTier.COMFORT,
                0L, 0L, used, 0L, 0.0D, 0.0D, Double.POSITIVE_INFINITY, "heap");
    }
}
