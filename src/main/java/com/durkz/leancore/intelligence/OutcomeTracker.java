package com.durkz.leancore.intelligence;

public class OutcomeTracker {

    private static final long MIN_STABLE_MS = 30_000L;
    private static final long EVAL_DELAY_MS = 90_000L;
    private static final double REWARD_GOOD = 0.04D;
    private static final double REWARD_BAD = -0.06D;
    private static final double ROLLBACK_REWARD = -0.15D;

    private final PolicyBandit bandit;

    private Pending pending;
    private int discarded;
    private int completed;

    public OutcomeTracker(PolicyBandit bandit) {
        this.bandit = bandit;
    }

    public void onPolicyApplied(
            String armKey,
            double[] context,
            double heapRatio,
            int playerCount,
            long nowMs
    ) {
        closePending(heapRatio, playerCount, nowMs, false);
        pending = new Pending(armKey, context, heapRatio, playerCount, nowMs);
    }

    public void onRollback(String armKey, double[] context) {
        if (armKey != null && context != null) {
            bandit.update(armKey, context, ROLLBACK_REWARD);
        }
        pending = null;
    }

    public void tick(double heapRatio60s, int playerCount, long nowMs) {
        if (pending == null) {
            return;
        }
        long elapsed = nowMs - pending.startedMs;
        if (elapsed < EVAL_DELAY_MS) {
            return;
        }
        closePending(heapRatio60s, playerCount, nowMs, true);
    }

    public int discarded() {
        return discarded;
    }

    public int completed() {
        return completed;
    }

    private void closePending(double heapRatio, int playerCount, long nowMs, boolean timed) {
        if (pending == null) {
            return;
        }
        long elapsed = nowMs - pending.startedMs;
        if (!timed && elapsed < MIN_STABLE_MS) {
            pending = null;
            return;
        }
        if (pending.playerCount != playerCount) {
            discarded++;
            pending = null;
            return;
        }
        if (elapsed < MIN_STABLE_MS) {
            return;
        }
        double delta = heapRatio - pending.heapAtStart;
        double reward;
        if (delta <= -REWARD_GOOD) {
            reward = -delta;
        } else if (delta >= REWARD_BAD * -1.0D) {
            reward = delta;
        } else {
            reward = -Math.abs(delta) * 0.25D;
        }
        bandit.update(pending.armKey, pending.context, reward);
        completed++;
        pending = null;
    }

    private record Pending(
            String armKey,
            double[] context,
            double heapAtStart,
            int playerCount,
            long startedMs
    ) {
    }
}
