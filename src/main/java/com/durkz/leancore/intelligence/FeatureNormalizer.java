package com.durkz.leancore.intelligence;

public final class FeatureNormalizer {

    private FeatureNormalizer() {
    }

    public static double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    public static double[] normalize(double[] raw) {
        if (raw == null || raw.length == 0) {
            return FeatureSchema.zeroVector();
        }
        double[] out = new double[raw.length];
        for (int i = 0; i < raw.length; i++) {
            out[i] = clamp01(raw[i]);
        }
        return out;
    }

    public static double dot(double[] weights, double[] features) {
        if (weights == null || features == null) {
            return 0.0D;
        }
        int n = Math.min(weights.length, features.length);
        double sum = 0.0D;
        for (int i = 0; i < n; i++) {
            sum += weights[i] * features[i];
        }
        return sum;
    }
}
