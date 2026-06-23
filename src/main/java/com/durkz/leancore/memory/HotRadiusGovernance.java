package com.durkz.leancore.memory;

import com.durkz.leancore.config.LeanCoreConfig;

/**
 * Hot/simulation radius policy application rules (v1.7.0 Frente C). Mirrors
 * {@link ViewRadiusGovernance} but for {@code ChunkTracker.setMaxHotLoadedChunksRadius} (the ticking
 * radius), which only affects server-side simulation cost and has no client view pop-in. Off by
 * default; respects the same grace window as the view-radius governor.
 */
public final class HotRadiusGovernance {

    private HotRadiusGovernance() {
    }

    public static boolean shouldApply(LeanCoreConfig config, boolean graceActive) {
        if (config == null || !config.hotRadiusGovernanceEnabled) {
            return false;
        }
        return !graceActive;
    }

    /** Target hot radius for a policy: scales the configured max by the policy view scale, clamped. */
    public static int targetHotRadius(LeanCoreConfig config, double viewScale) {
        int max = Math.max(1, config.maxHotLoadedChunksRadius);
        int min = Math.max(1, Math.min(config.minHotLoadedChunksRadius, max));
        int scaled = (int) Math.round(max * viewScale);
        return Math.max(min, Math.min(max, scaled));
    }
}
