package com.durkz.leancore.memory;

public record GovernorPolicy(
        GovernorPreset preset,
        MemoryTier tier,
        double viewScale,
        int demoteBatch
) {
    public static GovernorPolicy forTier(GovernorPreset preset, MemoryTier tier) {
        double tierScale = switch (tier) {
            case COMFORT -> 1.0D;
            case WATCH -> 0.92D;
            case TIGHT -> 0.82D;
            case CRITICAL -> 0.70D;
        };
        int demoteBatch = switch (tier) {
            case COMFORT -> 0;
            case WATCH -> 1;
            case TIGHT -> 3;
            case CRITICAL -> 6;
        };
        return new GovernorPolicy(preset, tier, preset.viewScale() * tierScale, demoteBatch);
    }

    public String key() {
        return preset.name() + ":" + tier.name();
    }
}
