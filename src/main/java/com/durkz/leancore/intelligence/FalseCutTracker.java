package com.durkz.leancore.intelligence;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts false cuts (high-demand view-radius shrinks / revisits after unload). {@code noteCut} is
 * called from the world thread (view-radius governance) and the scheduler thread (revisit-after-
 * unload), while {@code sessionCuts()} is read from the persist thread, so the counters are atomic:
 * a plain {@code int++} would lose updates and publish stale values across executors.
 */
public class FalseCutTracker {

    private final AtomicInteger windowCuts = new AtomicInteger();
    private final AtomicInteger sessionCuts = new AtomicInteger();

    public void beginWindow() {
        windowCuts.set(0);
    }

    public void noteCut(boolean highDemand) {
        if (!highDemand) {
            return;
        }
        windowCuts.incrementAndGet();
        sessionCuts.incrementAndGet();
    }

    public int windowCuts() {
        return windowCuts.get();
    }

    public int sessionCuts() {
        return sessionCuts.get();
    }
}
