package com.durkz.leancore.intelligence;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HoldoutSetTest {

    @Test
    void holdoutRateNearTenPercentForManyPlayers() {
        int holdout = 0;
        int total = 10_000;
        for (int i = 0; i < total; i++) {
            if (HoldoutSet.isHoldout(UUID.randomUUID())) {
                holdout++;
            }
        }
        double rate = holdout / (double) total;
        assertTrue(rate > 0.07D && rate < 0.13D, "holdout rate was " + rate);
    }

    @Test
    void holdoutIsStablePerPlayer() {
        UUID id = UUID.randomUUID();
        boolean first = HoldoutSet.isHoldout(id);
        for (int i = 0; i < 50; i++) {
            assertTrue(first == HoldoutSet.isHoldout(id));
        }
    }
}
