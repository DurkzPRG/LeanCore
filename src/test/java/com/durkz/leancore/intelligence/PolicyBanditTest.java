package com.durkz.leancore.intelligence;

import com.durkz.leancore.memory.MemorySnapshot;
import com.durkz.leancore.memory.MemoryTier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PolicyBanditTest {

    @Test
    void buildContextIncludesRegionalPressure() {
        MemorySnapshot sample = new MemorySnapshot(
                2_000_000_000L,
                4_000_000_000L,
                0.50D,
                4,
                120.0D,
                MemoryTier.WATCH
        );
        double[] context = PolicyBandit.buildContext(sample, 0.6D, 45L, MemoryTier.WATCH, 0.48D, 0.75D);
        assertEquals(PolicyBandit.CONTEXT_DIM, context.length);
        assertEquals(0.75D, context[6], 0.001D);
    }
}
