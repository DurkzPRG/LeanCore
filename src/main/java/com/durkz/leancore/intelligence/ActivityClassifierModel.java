package com.durkz.leancore.intelligence;

import java.util.Locale;

/**
 * Online multiclass softmax classifier for player activity labels.
 */
public class ActivityClassifierModel {

    private static final int CLASS_COUNT = PlayerBehavior.values().length;
    private static final double LEARNING_RATE = 0.12D;
    private static final double RIDGE = 0.03D;
    private static final int WARMUP_UPDATES = 8;

    private final double[][] weights = new double[CLASS_COUNT][ActivityFeatureEncoder.DIM];
    private int updates;

    public void train(ActionKind kind) {
        if (kind == null || kind == ActionKind.UNKNOWN) {
            return;
        }
        train(ActivityFeatureEncoder.encodeEvent(kind), kind.teacherIndex());
    }

    public synchronized void train(double[] features, int teacherIndex) {
        if (features == null || features.length != ActivityFeatureEncoder.DIM) {
            return;
        }
        if (teacherIndex < 0 || teacherIndex >= CLASS_COUNT) {
            return;
        }
        double[] probs = softmax(features);
        for (int c = 0; c < CLASS_COUNT; c++) {
            double target = c == teacherIndex ? 1.0D : 0.0D;
            double error = target - probs[c];
            for (int i = 0; i < ActivityFeatureEncoder.DIM; i++) {
                weights[c][i] += LEARNING_RATE * (error * features[i] - RIDGE * weights[c][i]);
            }
        }
        updates++;
    }

    public synchronized double[] posterior(PlayerFeatureState state, long nowMs) {
        double[] features = ActivityFeatureEncoder.encodeState(state, nowMs);
        return softmax(features);
    }

    public synchronized PlayerBehavior topLabel(PlayerFeatureState state, long nowMs) {
        if (state == null) {
            return PlayerBehavior.UNKNOWN;
        }
        long idleSec = state.idleSec(nowMs);
        if (idleSec >= 300L) {
            return PlayerBehavior.AFK;
        }
        PlayerBehavior recent = BehaviorPosterior.resolveRecent(state, nowMs);
        if (recent != PlayerBehavior.UNKNOWN) {
            return recent;
        }
        double[] probs = posterior(state, nowMs);
        int best = -1;
        double bestScore = -1.0D;
        for (int i = 0; i < probs.length; i++) {
            PlayerBehavior label = PlayerBehavior.values()[i];
            if (label == PlayerBehavior.UNKNOWN || label == PlayerBehavior.AFK || label == PlayerBehavior.SOCIAL) {
                continue;
            }
            if (probs[i] > bestScore) {
                bestScore = probs[i];
                best = i;
            }
        }
        if (best < 0 || (updates < WARMUP_UPDATES && bestScore < 0.18D)) {
            return BehaviorPosterior.topLabelFromActivityEmas(state, nowMs);
        }
        if (bestScore < 0.12D) {
            return PlayerBehavior.UNKNOWN;
        }
        return PlayerBehavior.values()[best];
    }

    public synchronized boolean isWarmedUp() {
        return updates >= WARMUP_UPDATES;
    }

    public synchronized int updates() {
        return updates;
    }

    public synchronized double[][] weights() {
        double[][] copy = new double[CLASS_COUNT][ActivityFeatureEncoder.DIM];
        for (int c = 0; c < CLASS_COUNT; c++) {
            System.arraycopy(weights[c], 0, copy[c], 0, ActivityFeatureEncoder.DIM);
        }
        return copy;
    }

    public synchronized void hydrate(double[][] savedWeights, int savedUpdates) {
        if (savedWeights != null) {
            int rows = Math.min(CLASS_COUNT, savedWeights.length);
            for (int c = 0; c < rows; c++) {
                if (savedWeights[c] == null) {
                    continue;
                }
                int cols = Math.min(ActivityFeatureEncoder.DIM, savedWeights[c].length);
                System.arraycopy(savedWeights[c], 0, weights[c], 0, cols);
            }
        }
        updates = Math.max(0, savedUpdates);
    }

    public synchronized String statusLine(PlayerFeatureState state, long nowMs) {
        PlayerBehavior top = topLabel(state, nowMs);
        double[] probs = posterior(state, nowMs);
        double topProb = top.ordinal() < probs.length ? probs[top.ordinal()] : 0.0D;
        return String.format(Locale.ROOT, "activityModel=SOFTMAX updates=%d top=%s %.0f%% warmed=%s",
                updates,
                top,
                topProb * 100.0D,
                isWarmedUp());
    }

    private double[] softmax(double[] features) {
        double[] logits = new double[CLASS_COUNT];
        double max = Double.NEGATIVE_INFINITY;
        for (int c = 0; c < CLASS_COUNT; c++) {
            logits[c] = dot(weights[c], features);
            max = Math.max(max, logits[c]);
        }
        double sum = 0.0D;
        double[] probs = new double[CLASS_COUNT];
        for (int c = 0; c < CLASS_COUNT; c++) {
            probs[c] = Math.exp(logits[c] - max);
            sum += probs[c];
        }
        if (sum <= 0.0D) {
            return probs;
        }
        for (int c = 0; c < CLASS_COUNT; c++) {
            probs[c] /= sum;
        }
        return probs;
    }

    private static double dot(double[] weights, double[] features) {
        double sum = 0.0D;
        for (int i = 0; i < ActivityFeatureEncoder.DIM; i++) {
            sum += weights[i] * features[i];
        }
        return sum;
    }
}
