package com.durkz.leancore.runtime;

import com.durkz.leancore.config.LeanCoreConfig;

/**
 * Embedded Hytale shares one JVM with the client. PASSIVE disables everything; AUTO scales
 * LITE → STANDARD as friends join; FULL forces the heavy path (not recommended locally).
 */
public final class RuntimeActivationPolicy {

    public static final String MODE_AUTO = "AUTO";
    public static final String MODE_PASSIVE = "PASSIVE";
    public static final String MODE_FULL = "FULL";

    private RuntimeActivationPolicy() {
    }

    public static boolean isFullyPassive(LeanCoreConfig config) {
        if (config == null || !config.enabled) {
            return true;
        }
        if (config.dedicatedServerMode) {
            return false;
        }
        // The deprecated localHostPassiveMode flag is migrated to localHostMode=PASSIVE in
        // LeanCoreConfig.applyRuntimeDefaults() (always run on load), so checking it here is redundant.
        String mode = normalizeMode(config.localHostMode);
        return MODE_PASSIVE.equals(mode);
    }

    public static boolean backgroundRuntimeEnabled(LeanCoreConfig config) {
        return config != null && config.enabled && !isFullyPassive(config);
    }

    public static RuntimeProfile resolveProfile(LeanCoreConfig config, int playerCount) {
        if (!backgroundRuntimeEnabled(config)) {
            return null;
        }
        if (config.dedicatedServerMode) {
            return RuntimeProfile.FULL;
        }
        String mode = normalizeMode(config.localHostMode);
        if (MODE_FULL.equals(mode)) {
            return playerCount <= 1 ? RuntimeProfile.LITE : RuntimeProfile.STANDARD;
        }
        if (playerCount <= 1) {
            if (config.embeddedStandardProfile) {
                return RuntimeProfile.STANDARD;
            }
            return RuntimeProfile.LITE;
        }
        if (playerCount < config.serverDensePlayerThreshold) {
            return RuntimeProfile.STANDARD;
        }
        return RuntimeProfile.FULL;
    }

    private static String normalizeMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return MODE_AUTO;
        }
        return raw.trim().toUpperCase();
    }
}
