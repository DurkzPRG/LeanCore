package com.durkz.leancore.intelligence;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class OnlineLinearDemandModel implements DemandModel {

    private static final double LEARNING_RATE = 0.08D;
    private static final double RIDGE = 0.02D;
    private static final double WEIGHT_DECAY = 0.9995D;
    private static final double MIN_BLEND = 0.35D;

    private final HeuristicDemandModel heuristic = new HeuristicDemandModel();
    private final double[] weights = new double[FeatureSchema.DEMAND_DIM];
    private int updates;

    public OnlineLinearDemandModel() {
        weights[0] = 0.15D;
        weights[4] = 0.20D;
        weights[5] = 0.25D;
        weights[7] = 0.18D;
        weights[8] = 0.12D;
        weights[9] = 0.10D;
        weights[10] = 0.14D;
    }

    @Override
    public String name() {
        return "LINEAR";
    }

    @Override
    public Map<UUID, RetentionDemand> estimate(
            Map<UUID, PlayerFeatureState> features,
            Map<UUID, PlayerBehavior> debugLabels,
            long nowMs
    ) {
        Map<UUID, RetentionDemand> baseline = heuristic.estimate(features, debugLabels, nowMs);
        if (updates < 8) {
            return baseline;
        }

        Map<UUID, RetentionDemand> out = new HashMap<>(baseline.size());
        for (Map.Entry<UUID, RetentionDemand> e : baseline.entrySet()) {
            UUID id = e.getKey();
            RetentionDemand prior = e.getValue();
            PlayerFeatureState state = features.get(id);
            double learned = predictDemand(state, nowMs);
            double blend = Math.max(MIN_BLEND, state != null ? state.confidence() : 0.0D);
            double demand = prior.demand() * (1.0D - blend) + learned * blend;
            demand = FeatureNormalizer.clamp01(demand);
            int retentionMb = resolveRetentionMb(demand, prior.confidence());
            out.put(id, new RetentionDemand(demand, prior.confidence(), retentionMb, prior.debugLabel()));
        }
        return Map.copyOf(out);
    }

    @Override
    public void onOutcome(UUID playerId, PlayerFeatureState state, double targetDemand, double reward, long nowMs) {
        if (state == null || reward <= 0.0D) {
            return;
        }
        double[] x = FeatureSchema.demandVector(state, nowMs);
        double prediction = predictDemand(state, nowMs);
        double error = targetDemand - prediction;
        for (int i = 0; i < FeatureSchema.DEMAND_DIM; i++) {
            weights[i] *= WEIGHT_DECAY;
            weights[i] += LEARNING_RATE * (error * x[i] - RIDGE * weights[i]);
        }
        updates++;
    }

    public double predictDemand(PlayerFeatureState state, long nowMs) {
        double raw = FeatureNormalizer.dot(weights, FeatureSchema.demandVector(state, nowMs));
        return FeatureNormalizer.clamp01(raw);
    }

    public int updates() {
        return updates;
    }

    public double[] weights() {
        return weights.clone();
    }

    public void hydrate(double[] savedWeights, int savedUpdates) {
        if (savedWeights != null) {
            int copy = Math.min(FeatureSchema.DEMAND_DIM, savedWeights.length);
            System.arraycopy(savedWeights, 0, weights, 0, copy);
        }
        updates = Math.max(0, savedUpdates);
    }

    public String statusLine() {
        return String.format(Locale.ROOT, "demandModel=LINEAR updates=%d w0=%.3f w4=%.3f w5=%.3f",
                updates, weights[0], weights[4], weights[5]);
    }

    private static int resolveRetentionMb(double demand, double confidence) {
        double raw = RetentionDemand.MIN_MB + demand * (RetentionDemand.MAX_MB - RetentionDemand.MIN_MB);
        double blended = raw * confidence + RetentionDemand.PRIOR_MB * (1.0D - confidence);
        return (int) Math.round(blended);
    }
}
