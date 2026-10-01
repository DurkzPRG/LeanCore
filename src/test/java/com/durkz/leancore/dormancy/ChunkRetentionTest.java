package com.durkz.leancore.dormancy;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.memory.MemorySnapshot;
import com.durkz.leancore.memory.MemoryTier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkRetentionTest {

    private static final long MB = 1024L * 1024L;

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
    void keepsWhileYoungAndWithinReach() {
        assertTrue(ChunkRetention.keeps(0L, 600_000L, 500.0D, 1024));
        assertFalse(ChunkRetention.keeps(600_000L, 600_000L, 500.0D, 1024));
        assertFalse(ChunkRetention.keeps(0L, 600_000L, 1500.0D, 1024));
    }

    @Test
    void candidatesNearAPlayerWinOverTheTrailOfSomeoneWhoLeft() {
        // Player at the origin; a trail far away competes with chunks next to the player.
        double[] players = {16.0D, 16.0D};
        List<ChunkRetention.Candidate> candidates = new ArrayList<>();
        for (int x = 25; x < 30; x++) {
            candidates.add(new ChunkRetention.Candidate(x, ChunkRetention.nearestDistance(players, x, 0)));
        }
        for (int x = 1; x < 4; x++) {
            candidates.add(new ChunkRetention.Candidate(x, ChunkRetention.nearestDistance(players, x, 0)));
        }
        List<ChunkRetention.Candidate> picked = ChunkRetention.nearestFirst(candidates, 3);
        assertEquals(3, picked.size());
        for (ChunkRetention.Candidate c : picked) {
            assertTrue(c.distance() < 200.0D, "picked a far chunk: " + c);
        }
        assertTrue(picked.get(0).distance() <= picked.get(2).distance());
    }

    @Test
    void withSeveralPlayersDistanceFollowsTheNearestOne() {
        double[] players = {16.0D, 16.0D, 5000.0D, 16.0D, 16.0D, -9000.0D};
        assertEquals(0.0D, ChunkRetention.nearestDistance(players, 0, 0), 1e-9);
        assertTrue(ChunkRetention.nearestDistance(players, 0, -282) < 16.0D);
        assertTrue(ChunkRetention.nearestDistance(players, 156, 0) < 16.0D);
        assertTrue(ChunkRetention.nearestDistance(players, 80, 80) > 2000.0D);
    }

    private static MemorySnapshot snapshot(MemoryTier tier, long max, long postGc) {
        return new MemorySnapshot(postGc, max, (double) postGc / max, 1, 0.0D, tier,
                0L, 0L, postGc, 0L, 0.0D, 0.0D, Double.POSITIVE_INFINITY, "heap");
    }
}
