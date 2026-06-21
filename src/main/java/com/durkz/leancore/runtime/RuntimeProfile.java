package com.durkz.leancore.runtime;

import com.durkz.leancore.config.LeanCoreConfig;

/**
 * Work budget tiers. Nitrado splits view-radius / chunk-GC / TPS into separate slow loops;
 * we split governor/learning/HUD by player count on embedded hosts instead of one 5s megatick.
 */
public enum RuntimeProfile {

    /** Solo on embedded host: dormancy + heap tier only. */
    LITE,
    /** Friends co-op on embedded host: adds classifier + optional govern/learning. */
    STANDARD,
    /** Dedicated host: full subsystem set per config. */
    FULL;

    public long tickIntervalSeconds(LeanCoreConfig config) {
        return switch (this) {
            case LITE -> Math.max(10, config.soloTickIntervalSeconds);
            case STANDARD -> Math.max(10, config.friendsTickIntervalSeconds);
            case FULL -> Math.max(1, config.runtimeTickIntervalSeconds);
        };
    }

    public boolean runsGovernor(LeanCoreConfig config) {
        return switch (this) {
            case LITE -> false;
            case STANDARD, FULL -> config.governEnabled;
        };
    }

    public boolean runsLiteGovernor(LeanCoreConfig config) {
        return this == LITE && config.liteMemoryGovernorEnabled;
    }

    public boolean runsLiteLearning(LeanCoreConfig config) {
        return this == LITE && config.liteLearningEnabled;
    }

    public boolean runsLearning(LeanCoreConfig config) {
        return switch (this) {
            case LITE -> false;
            case STANDARD, FULL -> config.learningEnabled;
        };
    }

    public boolean runsHud(LeanCoreConfig config) {
        return switch (this) {
            case LITE, STANDARD, FULL -> config.hudFeatureEnabled;
        };
    }

    public boolean tracksPlayerMotion() {
        return true;
    }
}
