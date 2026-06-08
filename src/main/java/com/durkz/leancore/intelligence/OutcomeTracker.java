package com.durkz.leancore.intelligence;

public class OutcomeTracker {

    private static final long MIN_STABLE_MS = 30_000L;
    private static final long EVAL_DELAY_MS = 90_000L;
    private static final long BOOT_GRACE_MS = 60_000L;
    private static final double REWARD_GOOD = 0.04D;
    private static final double REWARD_BAD = 0.06D;
    private static final double ROLLBACK_REWARD = -0.15D;
    private static final double FALSE_CUT_PENALTY = 0.05D;

    private final PolicyBandit bandit;
    private final FalseCutTracker falseCutTracker;
    private final long serverStartedMs = System.currentTimeMillis();

    private Pending pending;
    private int discarded;
    private int completed;
    private volatile double lastCompletedReward = Double.NaN;

    public OutcomeTracker(PolicyBandit bandit, FalseCutTracker falseCutTracker) {
        this.bandit = bandit;
        this.falseCutTracker = falseCutTracker;
    }

    public void onPolicyApplied(
            String armKey,
            double[] context,
            double heapRatio,
            int playerCount,
            long nowMs
    ) {
        closePending(heapRatio, playerCount, nowMs, false);
        falseCutTracker.beginWindow();
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

    public void flushPending(double heapRatio60s, int playerCount, long nowMs) {
        closePending(heapRatio60s, playerCount, nowMs, true);
    }

    public int discarded() {
        return discarded;
    }

    public int completed() {
        return completed;
    }

    public double pollCompletedReward() {
        double reward = lastCompletedReward;
        lastCompletedReward = Double.NaN;
        return reward;
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
            if (pending.playerCount == 0
                    && playerCount > 0
                    && nowMs - serverStartedMs < BOOT_GRACE_MS) {
                pending = new Pending(
                        pending.armKey,
                        pending.context,
                        pending.heapAtStart,
                        playerCount,
                        pending.startedMs
                );
                return;
            }
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
        } else if (delta >= REWARD_BAD) {
            reward = -delta;
        } else {
            reward = -Math.abs(delta) * 0.25D;
        }
        reward -= falseCutTracker.windowCuts() * FALSE_CUT_PENALTY;
        bandit.update(pending.armKey, pending.context, reward);
        completed++;
        lastCompletedReward = reward;
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
