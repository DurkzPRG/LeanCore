package com.durkz.leancore.dormancy;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.memory.MemorySnapshot;
import com.durkz.leancore.memory.MemoryTier;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkRetentionTest {

    private static final long MB = 1024L * 1024L;

    @Test
    void holdsFreelyBelowNinetyPercentOfBudget() {
        assertTrue(ChunkRetention.decide(50, 100, 0L, 600_000L, 10_000.0D, 384));
    }

    @Test
    void nearBudgetOnlyKeepsChunksInsideTheRing() {
        assertTrue(ChunkRetention.decide(95, 100, 0L, 600_000L, 200.0D, 384));
        assertFalse(ChunkRetention.decide(95, 100, 0L, 600_000L, 900.0D, 384));
    }

    @Test
    void overBudgetOrTooOldOrNoBudgetReleases() {
        assertFalse(ChunkRetention.decide(101, 100, 0L, 600_000L, 10.0D, 384));
        assertFalse(ChunkRetention.decide(1, 100, 600_000L, 600_000L, 10.0D, 384));
        assertFalse(ChunkRetention.decide(1, 0, 0L, 600_000L, 10.0D, 384));
    }

    @Test
    void budgetIsHeadroomUnderPostGcCeilingPlusWhatIsHeld() {
        // 1000 MB max, 300 MB live after GC, ceiling 60% -> 300 MB free -> 300 chunks at 1 MB.
        MemorySnapshot sample = snapshot(MemoryTier.COMFORT, 1000L * MB, 300L * MB);
        assertEquals(300, ChunkRetention.budgetFor(sample, 0, 0.60D, 1.0D, 2048));
        assertEquals(340, ChunkRetention.budgetFor(sample, 40, 0.60D, 1.0D, 2048));
        assertEquals(100, ChunkRetention.budgetFor(sample, 0, 0.60D, 1.0D, 100));
    }

    @Test
    void anyPressureTierZeroesTheBudget() {
        assertEquals(0, ChunkRetention.budgetFor(snapshot(MemoryTier.WATCH, 1000L * MB, 100L * MB), 10, 0.60D, 1.0D, 2048));
        assertEquals(0, ChunkRetention.budgetFor(snapshot(MemoryTier.COMFORT, 1000L * MB, 700L * MB), 0, 0.60D, 1.0D, 2048));
    }

    @Test
    void disabledConfigZeroesTheBudget() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.chunkRetentionEnabled = false;
        ChunkRetention retention = new ChunkRetention(config);
        retention.updateBudget(snapshot(MemoryTier.COMFORT, 1000L * MB, 300L * MB));
        assertEquals(0, retention.budget());

        config.chunkRetentionEnabled = true;
        retention.updateBudget(snapshot(MemoryTier.COMFORT, 1000L * MB, 300L * MB));
        assertEquals(200, retention.budget());
    }

    @Test
    void distanceUsesThirtyTwoBlockChunks() {
        double[] player = {16.0D, 16.0D};
        assertEquals(0.0D, ChunkRetention.nearestDistance(player, 0, 0), 1e-9);
        assertEquals(320.0D, ChunkRetention.nearestDistance(player, 10, 0), 1e-9);
        assertEquals(Double.MAX_VALUE, ChunkRetention.nearestDistance(new double[0], 0, 0));
    }

    @Test
    void withSeveralPlayersTheRingFollowsTheNearestOne() {
        // Three players spread over the map; a chunk near the third must count as close.
        double[] players = {16.0D, 16.0D, 5000.0D, 16.0D, 16.0D, -9000.0D};
        assertEquals(0.0D, ChunkRetention.nearestDistance(players, 0, 0), 1e-9);
        assertEquals(0.0D, ChunkRetention.nearestDistance(players, 0, -282), 16.0D);
        double farFromAll = ChunkRetention.nearestDistance(players, 80, 80);
        assertTrue(farFromAll > 2000.0D);
        assertTrue(ChunkRetention.decide(95, 100, 0L, 600_000L,
                ChunkRetention.nearestDistance(players, 156, 0), 384));
        assertFalse(ChunkRetention.decide(95, 100, 0L, 600_000L, farFromAll, 384));
    }

    private static MemorySnapshot snapshot(MemoryTier tier, long max, long postGc) {
        return new MemorySnapshot(postGc, max, (double) postGc / max, 1, 0.0D, tier,
                0L, 0L, postGc, 0L, 0.0D, 0.0D, Double.POSITIVE_INFINITY, "heap");
    }
}
