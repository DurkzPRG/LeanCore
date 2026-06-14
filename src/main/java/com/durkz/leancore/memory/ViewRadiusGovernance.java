package com.durkz.leancore.memory;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.runtime.RuntimeProfile;

/**
 * View-radius policy application rules.
 * <p>
 * STANDARD/FULL: embedded solo skipped unless {@code dedicatedServerMode}.
 * LITE (1.5.0): solo embedded allowed when {@code liteViewRadiusEnabled} and login grace elapsed.
 */
public final class ViewRadiusGovernance {

    private ViewRadiusGovernance() {
    }

    /** STANDARD/FULL path (existing callers). */
    public static boolean shouldApply(LeanCoreConfig config, int onlinePlayers, boolean graceActive) {
        return shouldApply(config, null, onlinePlayers, graceActive, 0L, 0L);
    }

    public static boolean shouldApply(
            LeanCoreConfig config,
            RuntimeProfile profile,
            int onlinePlayers,
            boolean graceActive,
            long nowMs,
            long liteSessionStartedMs
    ) {
        if (profile == RuntimeProfile.LITE) {
            return shouldApplyLite(config, onlinePlayers, graceActive, nowMs, liteSessionStartedMs);
        }
        return shouldApplyStandard(config, onlinePlayers, graceActive);
    }

    static boolean shouldApplyStandard(LeanCoreConfig config, int onlinePlayers, boolean graceActive) {
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

    static boolean shouldApplyLite(
            LeanCoreConfig config,
            int onlinePlayers,
            boolean graceActive,
            long nowMs,
            long liteSessionStartedMs
    ) {
        if (config == null || !config.liteMemoryGovernorEnabled || !config.liteViewRadiusEnabled) {
            return false;
        }
        if (graceActive) {
            return false;
        }
        if (onlinePlayers > 1) {
            return false;
        }
        if (liteSessionStartedMs > 0L && config.liteViewRadiusLoginGraceSeconds > 0) {
            long graceMs = config.liteViewRadiusLoginGraceSeconds * 1000L;
            if (nowMs - liteSessionStartedMs < graceMs) {
                return false;
            }
        }
        return true;
    }
}
