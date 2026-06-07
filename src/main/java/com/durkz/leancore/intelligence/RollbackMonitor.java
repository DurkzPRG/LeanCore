package com.durkz.leancore.intelligence;

public class RollbackMonitor {

    private final LearningStore store;

    private String activePolicyKey;
    private double heapAtPolicy;
    private long policyStartedMs;

    public RollbackMonitor(LearningStore store) {
        this.store = store;
    }

    public void onPolicyApplied(String policyKey, double heapRatio, long nowMs) {
        evaluateActive(nowMs, heapRatio);
        activePolicyKey = policyKey;
        heapAtPolicy = heapRatio;
        policyStartedMs = nowMs;
    }

    public void onRollback(String policyKey) {
        store.penalizePolicy(policyKey);
        activePolicyKey = null;
    }

    public void tick(double heapRatio, long nowMs) {
        evaluateActive(nowMs, heapRatio);
    }

    private void evaluateActive(long nowMs, double heapRatio) {
        if (activePolicyKey == null || policyStartedMs <= 0L) {
            return;
        }
        long elapsedMs = nowMs - policyStartedMs;
        if (elapsedMs < 5_000L) {
            return;
        }
        if (elapsedMs > 60_000L) {
            activePolicyKey = null;
            return;
        }
        double delta = heapRatio - heapAtPolicy;
        if (delta <= -0.02D) {
            store.reinforcePolicy(activePolicyKey);
            activePolicyKey = null;
        } else if (delta >= 0.03D) {
            store.penalizePolicy(activePolicyKey);
            activePolicyKey = null;
        }
    }
}
