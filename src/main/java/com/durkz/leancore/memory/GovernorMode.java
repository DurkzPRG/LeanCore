package com.durkz.leancore.memory;

/**
 * Which governor execution path applies. STANDARD/FULL use full {@link MemoryGovernor};
 * LITE uses throttled {@code tickLiteMode} (wired in 1.5.0).
 */
public enum GovernorMode {

    /** Friends co-op and dedicated: bandit, full tier policy, existing view rules. */
    STANDARD,
    /** Solo embedded: adaptive view, AFK unload, lite learning; no bandit. */
    LITE
}
