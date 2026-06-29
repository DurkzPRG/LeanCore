package com.durkz.leancore.intelligence;

import com.durkz.leancore.diagnostics.DiagnosticLog;
import com.durkz.leancore.memory.GovernorPolicy;
import com.durkz.leancore.memory.GovernorPreset;
import com.durkz.leancore.memory.MemorySnapshot;
import com.durkz.leancore.memory.MemoryTier;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PolicyBandit {

    public static final int CONTEXT_DIM = 7;

    // LinUCB exploration weight. Higher = explore high-variance arms more. 0.6 was a good middle
    // ground in dogfood: lower and it locks onto the first decent arm, higher and it keeps probing
    // even under sustained pressure.
    private static final double ALPHA = 0.6D;
    // Floor for the diagonal of A so a barely-pulled arm can't divide by ~0 and blow up the score.
    private static final double MIN_A = 0.05D;
    // Only start ignoring an arm after this many pulls, so we don't write it off on noise.
    private static final double DEPRIORITIZE_PULLS = 8.0D;
    // Mean reward below which a well-pulled arm is treated as harmful and skipped.
    private static final double DEPRIORITIZE_MEAN = -0.08D;
    // Optimistic score for an arm we've never pulled, so each arm gets tried at least once.
    private static final double UNPULLED_ARM_SCORE = 5.0D;

    // Context features are normalized to roughly [0,1] before going into the model. These scales set
    // what counts as "maxed out" for each raw signal.
    private static final double CONTEXT_PLAYER_SCALE = 32.0D;   // online players: saturates near a full server
    private static final double CONTEXT_SPREAD_SCALE = 1000.0D; // player spread in blocks (~31x31 chunks)
    private static final double CONTEXT_STALENESS_SCALE = 300.0D; // seconds since last policy change (5 min)
    private static final double CONTEXT_TIER_SCALE = 3.0D;      // MemoryTier ordinal range (COMFORT..CRITICAL)

    private final Map<String, ArmState> arms = new ConcurrentHashMap<>();

    public GovernorPolicy select(
            GovernorPreset preset,
            MemoryTier pressureTier,
            MemorySnapshot sample,
            double meanDemand,
            long secondsSinceChange,
            double heapBaseline,
            double regionalPressure,
            GovernorPolicy current,
            java.util.function.Predicate<String> blacklisted
    ) {
        double[] context = buildContext(sample, meanDemand, secondsSinceChange, pressureTier, heapBaseline, regionalPressure);
        GovernorPolicy best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (MemoryTier tier : MemoryTier.values()) {
            if (tier.ordinal() > pressureTier.ordinal()) {
                continue;
            }
            GovernorPolicy candidate = GovernorPolicy.forTier(preset, tier);
            String key = candidate.key();
            if (blacklisted.test(key) || isDeprioritized(key)) {
                continue;
            }
            double score = ucb(key, context);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        if (best == null) {
            GovernorPolicy fallback = current != null ? current : GovernorPolicy.forTier(preset, pressureTier);
            DiagnosticLog.infoOnChange("bandit",
                    "bandit: all arms blacklisted/deprioritized, fallback -> " + fallback.key());
            return fallback;
        }
        ArmState chosen = arms.get(best.key());
        int pulls = chosen == null ? 0 : chosen.pulls;
        DiagnosticLog.infoOnChange("bandit", String.format(Locale.ROOT,
                "bandit: chose %s (UCB=%.3f pulls=%d %s)",
                best.key(), bestScore, pulls, pulls <= 0 ? "explore" : "exploit"));
        return best;
    }

    public void update(String armKey, double[] context, double reward) {
        if (armKey == null || context == null || context.length != CONTEXT_DIM) {
            return;
        }
        ArmState arm = arms.computeIfAbsent(armKey, ignored -> new ArmState());
        arm.pulls++;
        arm.rewardSum += reward;
        for (int i = 0; i < CONTEXT_DIM; i++) {
            double x = context[i];
            arm.aDiag[i] += x * x;
            arm.b[i] += reward * x;
        }
    }

    public boolean isDeprioritized(String armKey) {
        ArmState arm = arms.get(armKey);
        if (arm == null || arm.pulls < DEPRIORITIZE_PULLS) {
            return false;
        }
        return (arm.rewardSum / arm.pulls) < DEPRIORITIZE_MEAN;
    }

    public int armCount() {
        return arms.size();
    }

    public String topArmLine() {
        String bestKey = null;
        double bestMean = Double.NEGATIVE_INFINITY;
        for (Map.Entry<String, ArmState> e : arms.entrySet()) {
            ArmState arm = e.getValue();
            if (arm.pulls <= 0) {
                continue;
            }
            double mean = arm.rewardSum / arm.pulls;
            if (mean > bestMean) {
                bestMean = mean;
                bestKey = e.getKey();
            }
        }
        if (bestKey == null) {
            return "bandit arms=0";
        }
        ArmState arm = arms.get(bestKey);
        return String.format(Locale.ROOT, "bandit top=%s mean=%.3f pulls=%d",
                bestKey, bestMean, arm.pulls);
    }

    public Map<String, ArmState> arms() {
        return arms;
    }

    public static double[] buildContext(
            MemorySnapshot sample,
            double meanDemand,
            long secondsSinceChange,
            MemoryTier pressureTier,
            double heapBaseline,
            double regionalPressure
    ) {
        return new double[]{
                sample.heapUsedRatio() - heapBaseline,
                Math.min(1.0D, sample.onlinePlayers() / CONTEXT_PLAYER_SCALE),
                Math.max(0.0D, Math.min(1.0D, meanDemand)),
                Math.min(1.0D, sample.playerSpreadBlocks() / CONTEXT_SPREAD_SCALE),
                Math.min(1.0D, secondsSinceChange / CONTEXT_STALENESS_SCALE),
                pressureTier.ordinal() / CONTEXT_TIER_SCALE,
                Math.max(0.0D, Math.min(1.0D, regionalPressure))
        };
    }

    private double ucb(String armKey, double[] context) {
        ArmState arm = arms.computeIfAbsent(armKey, ignored -> new ArmState());
        if (arm.pulls <= 0) {
            return UNPULLED_ARM_SCORE;
        }
        double exploit = 0.0D;
        double explore = 0.0D;
        for (int i = 0; i < CONTEXT_DIM; i++) {
            double a = Math.max(MIN_A, arm.aDiag[i]);
            double theta = arm.b[i] / a;
            exploit += theta * context[i];
            explore += (context[i] * context[i]) / a;
        }
        return exploit + ALPHA * Math.sqrt(explore);
    }

    public static final class ArmState {
        public final double[] aDiag = new double[CONTEXT_DIM];
        public final double[] b = new double[CONTEXT_DIM];
        public int pulls;
        public double rewardSum;

        public ArmState() {
            for (int i = 0; i < CONTEXT_DIM; i++) {
                aDiag[i] = 1.0D;
            }
        }
    }
}
