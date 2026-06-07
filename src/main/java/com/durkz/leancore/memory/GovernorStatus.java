package com.durkz.leancore.memory;

public record GovernorStatus(
        boolean enabled,
        GovernorPreset preset,
        GovernorPolicy policy,
        int appliedPlayers,
        int demotedZones,
        int reclaimedMbEstimate,
        int totalFootprintMb,
        int budgetMb,
        boolean rolledBack,
        long secondsSinceChange
) {
    public static GovernorStatus idle() {
        return new GovernorStatus(false, GovernorPreset.FRIENDS_NIGHT, null, 0, 0, 0, 0, 0, false, 0L);
    }
}
