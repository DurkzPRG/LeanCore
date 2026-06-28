package com.durkz.leancore.probe;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkPrefetchPlannerTest {

    @Test
    void warmsChunksAheadAlongEastHeading() {
        // From origin heading +x with view radius 8: chunks 9, 10, 11 lie just past the edge.
        List<int[]> plan = ChunkPrefetchPlanner.plan(0, 0, 1000, 0, 8, 3);
        assertEquals(3, plan.size());
        assertArrayEqualsXZ(9, 0, plan.get(0));
        assertArrayEqualsXZ(10, 0, plan.get(1));
        assertArrayEqualsXZ(11, 0, plan.get(2));
    }

    @Test
    void followsDiagonalHeading() {
        List<int[]> plan = ChunkPrefetchPlanner.plan(0, 0, 100, 100, 4, 2);
        assertEquals(2, plan.size());
        assertArrayEqualsXZ(3, 3, plan.get(0));
        assertArrayEqualsXZ(4, 4, plan.get(1));
    }

    @Test
    void emptyWhenBarelyMoving() {
        // Under one chunk of movement: heading is noise, prefetch nothing.
        assertTrue(ChunkPrefetchPlanner.plan(0, 0, 10, 0, 8, 4).isEmpty());
        assertTrue(ChunkPrefetchPlanner.plan(50, 50, 50, 50, 8, 4).isEmpty());
    }

    @Test
    void emptyForNonPositiveBounds() {
        assertTrue(ChunkPrefetchPlanner.plan(0, 0, 1000, 0, 8, 0).isEmpty());
        assertTrue(ChunkPrefetchPlanner.plan(0, 0, 1000, 0, -1, 4).isEmpty());
    }

    private static void assertArrayEqualsXZ(int expectedX, int expectedZ, int[] actual) {
        assertEquals(expectedX, actual[0], "chunkX");
        assertEquals(expectedZ, actual[1], "chunkZ");
    }
}
