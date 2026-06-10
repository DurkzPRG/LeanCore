package com.durkz.leancore.memory;

import com.durkz.leancore.config.LeanCoreConfig;

/**
 * View-radius policy application rules. Embedded solo is skipped unless {@code dedicatedServerMode}.
 */
public final class ViewRadiusGovernance {

    private ViewRadiusGovernance() {
    }

    public static boolean shouldApply(LeanCoreConfig config, int onlinePlayers, boolean graceActive) {
        if (config == null || !config.viewRadiusGovernanceEnabled) {
            return false;
        }
        if (graceActive) {
            return false;
        }
        if (!config.dedicatedServerMode && onlinePlayers <= 1) {
            return false;
        }
        return true;
    }
}
