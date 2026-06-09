package com.durkz.leancore.config;

/**
 * One-time dedicated-host preset: enables governor, view-radius governance, and learning.
 * Chunk unload stays off until the admin opts in.
 */
public final class DedicatedBootstrap {

    public static final long VIEW_RADIUS_GRACE_MS = 600_000L;

    private DedicatedBootstrap() {
    }

    /**
     * @return true when bootstrap flags were applied and saved this call
     */
    public static boolean applyIfNeeded(LeanCoreConfig config) {
        if (config == null
                || !config.dedicatedServerMode
                || !config.dedicatedBootstrapEnabled
                || config.dedicatedBootstrapApplied) {
            return false;
        }
        config.governEnabled = true;
        config.viewRadiusGovernanceEnabled = true;
        config.learningEnabled = true;
        config.dedicatedBootstrapApplied = true;
        config.save();
        return true;
    }
}
