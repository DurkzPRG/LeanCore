package com.durkz.leancore.memory;

import java.util.Locale;

/** Attributes delayed post-GC movement only when the surrounding session stayed comparable. */
public final class PolicyActionLedger {

    private static final long OUTCOME_WINDOW_MS = 60_000L;

    private Pending pending;
    private int completed;
    private int discarded;
    private double lastPostGcDelta;
    private int lastRevisitDelta;
    private int lastEngineUnloadDelta;
    private int lastCandidates;
    private String lastAction = "none";

    public void record(String action, MemorySnapshot sample, int revisits, int engineUnloads,
                       int candidates, long nowMs) {
        if (action == null || action.isBlank() || sample == null) {
            return;
        }
        if (pending != null) {
            return;
        }
        pending = new Pending(action, sample.postGcHeapUsedRatio(), sample.onlinePlayers(), revisits,
                engineUnloads, candidates, nowMs);
    }

    public void observe(MemorySnapshot sample, int revisits, int engineUnloads, long nowMs) {
        Pending current = pending;
        if (current == null || sample == null) {
            return;
        }
        if (sample.onlinePlayers() != current.onlinePlayers()) {
            discarded++;
            pending = null;
            return;
        }
        if (nowMs - current.startedMs() < OUTCOME_WINDOW_MS) {
            return;
        }
        completed++;
        lastAction = current.action();
        lastPostGcDelta = sample.postGcHeapUsedRatio() - current.postGcRatio();
        lastRevisitDelta = Math.max(0, revisits - current.revisits());
        lastEngineUnloadDelta = Math.max(0, engineUnloads - current.engineUnloads());
        lastCandidates = current.candidates();
        pending = null;
    }

    public String statusLine() {
        return String.format(Locale.ROOT,
                "ledger completed=%d discarded=%d pending=%s last=%s postGcDelta=%+.1fpp revisits=%d engine=%d candidates=%d",
                completed, discarded, pending != null, lastAction, lastPostGcDelta * 100.0D,
                lastRevisitDelta, lastEngineUnloadDelta, lastCandidates);
    }

    record Pending(String action, double postGcRatio, int onlinePlayers, int revisits,
                   int engineUnloads, int candidates, long startedMs) {
    }
}
