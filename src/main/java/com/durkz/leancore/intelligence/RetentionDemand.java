package com.durkz.leancore.intelligence;

public record RetentionDemand(
        double demand,
        double confidence,
        int retentionMb,
        PlayerBehavior debugLabel
) {
    public static final int MIN_MB = 8;
    public static final int MAX_MB = 72;
    public static final int PRIOR_MB = 40;

    public static RetentionDemand coldStart(PlayerBehavior debugLabel) {
        return new RetentionDemand(0.5D, 0.0D, PRIOR_MB, debugLabel);
    }

    public double viewScale() {
        return 0.75D + demand * 0.30D;
    }
}
