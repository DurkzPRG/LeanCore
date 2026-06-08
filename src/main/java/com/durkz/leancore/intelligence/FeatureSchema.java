package com.durkz.leancore.intelligence;

/**
 * ML input feature contract for demand and bandit models (v1).
 */
public final class FeatureSchema {

    public static final int VERSION = 1;
    public static final int DEMAND_DIM = 7;

    private FeatureSchema() {
    }

    public static double[] demandVector(PlayerFeatureState state, long nowMs) {
        if (state == null) {
            return zeroVector();
        }
        double activity = FeatureNormalizer.clamp01(state.activityIndex() / 350.0D);
        double idle = FeatureNormalizer.clamp01(state.idleSec(nowMs) / 600.0D);
        return new double[]{
                FeatureNormalizer.clamp01(state.emaMovement60() / 500.0D),
                FeatureNormalizer.clamp01(state.emaBreaks60() / 40.0D),
                FeatureNormalizer.clamp01(state.emaPlaces60() / 40.0D),
                FeatureNormalizer.clamp01(state.emaZones60() / 20.0D),
                FeatureNormalizer.clamp01(state.emaChunks60() / 64.0D),
                activity,
                idle
        };
    }

    public static double[] zeroVector() {
        return new double[DEMAND_DIM];
    }

    public static String versionLine() {
        return "featureSchema=v" + VERSION + " demandDim=" + DEMAND_DIM;
    }
}
