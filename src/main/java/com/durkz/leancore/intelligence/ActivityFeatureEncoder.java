package com.durkz.leancore.intelligence;

/**
 * Unified activity feature vector for online softmax classifier (event training + state inference).
 */
public final class ActivityFeatureEncoder {

    public static final int DIM = 10;
    private static final double NORM = 24.0D;

    private ActivityFeatureEncoder() {
    }

    public static double[] encodeEvent(ActionKind kind) {
        double[] x = zero();
        switch (kind) {
            case MINE -> x[0] = 1.0D;
            case CHOP -> x[1] = 1.0D;
            case FARM -> x[2] = 1.0D;
            case BUILD -> x[3] = 1.0D;
            case CRAFT -> x[4] = 1.0D;
            case COMBAT -> x[5] = 1.0D;
            case EXPLORE -> x[6] = 1.0D;
            default -> x[9] = 0.5D;
        }
        x[9] = 1.0D;
        return x;
    }

    public static double[] encodeState(PlayerFeatureState state, long nowMs) {
        if (state == null) {
            return zero();
        }
        double[] x = new double[DIM];
        x[0] = FeatureNormalizer.clamp01(state.emaMine60() / NORM);
        x[1] = FeatureNormalizer.clamp01(state.emaWood60() / NORM);
        x[2] = FeatureNormalizer.clamp01(state.emaFarm60() / NORM);
        x[3] = FeatureNormalizer.clamp01(state.emaBuild60() / NORM);
        x[4] = FeatureNormalizer.clamp01(state.emaCraft60() / NORM);
        x[5] = FeatureNormalizer.clamp01(state.emaCombat60() / NORM);
        x[6] = FeatureNormalizer.clamp01(state.emaMovement60() / 500.0D);
        x[7] = FeatureNormalizer.clamp01(state.emaZones60() / 20.0D);
        x[8] = FeatureNormalizer.clamp01(state.idleSec(nowMs) / 600.0D);
        x[9] = 1.0D;
        return x;
    }

    public static double[] zero() {
        double[] x = new double[DIM];
        x[9] = 1.0D;
        return x;
    }
}
