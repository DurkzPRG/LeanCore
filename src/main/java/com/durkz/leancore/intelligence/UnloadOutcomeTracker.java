package com.durkz.leancore.intelligence;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public class UnloadOutcomeTracker {

    private final AtomicInteger policyUnloads = new AtomicInteger();
    private final AtomicInteger engineUnloads = new AtomicInteger();
    private final AtomicInteger windowPolicy = new AtomicInteger();
    private final AtomicInteger windowEngine = new AtomicInteger();

    public void beginSweepWindow() {
        windowPolicy.set(0);
        windowEngine.set(0);
    }

    public void notePolicyUnload(int count) {
        if (count <= 0) {
            return;
        }
        policyUnloads.addAndGet(count);
        windowPolicy.addAndGet(count);
    }

    public void noteEngineUnload() {
        noteEngineUnloads(1);
    }

    public void noteEngineUnloads(int count) {
        if (count <= 0) {
            return;
        }
        engineUnloads.addAndGet(count);
        windowEngine.addAndGet(count);
    }

    public int policyUnloads() {
        return policyUnloads.get();
    }

    public int engineUnloads() {
        return engineUnloads.get();
    }

    public int windowPolicy() {
        return windowPolicy.get();
    }

    public int windowEngine() {
        return windowEngine.get();
    }

    public void hydrate(int policy, int engine) {
        policyUnloads.set(Math.max(0, policy));
        engineUnloads.set(Math.max(0, engine));
    }

    public String statusLine() {
        return String.format(Locale.ROOT,
                "unload policy=%d engine=%d windowPolicy=%d windowEngine=%d",
                policyUnloads.get(),
                engineUnloads.get(),
                windowPolicy.get(),
                windowEngine.get());
    }
}
