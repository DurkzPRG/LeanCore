package com.durkz.leancore.intelligence;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FalseCutTrackerTest {

    @Test
    void concurrentNoteCutLosesNoUpdates() throws InterruptedException {
        FalseCutTracker tracker = new FalseCutTracker();
        int threads = 8;
        int perThread = 10_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                pool.execute(() -> {
                    for (int i = 0; i < perThread; i++) {
                        tracker.noteCut(true);
                    }
                });
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "workers finished");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(threads * perThread, tracker.sessionCuts(), "no lost session increments");
        assertEquals(threads * perThread, tracker.windowCuts(), "no lost window increments");
    }

    @Test
    void ignoresLowDemandAndResetsWindowOnly() {
        FalseCutTracker tracker = new FalseCutTracker();
        tracker.noteCut(false);
        assertEquals(0, tracker.sessionCuts(), "low-demand cuts are ignored");

        tracker.noteCut(true);
        tracker.noteCut(true);
        assertEquals(2, tracker.windowCuts());
        assertEquals(2, tracker.sessionCuts());

        tracker.beginWindow();
        assertEquals(0, tracker.windowCuts(), "window resets");
        assertEquals(2, tracker.sessionCuts(), "session total survives a window reset");
    }
}
