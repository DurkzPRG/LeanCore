package com.durkz.leancore.intelligence;

import java.util.UUID;

public interface ViewRadiusCache {

    void noteViewRadius(UUID playerId, int serverRadius, int clientRadius);

    /** Upward-only view-radius multiplier in [1.0, maxBoost] for fast movers. Default: no boost. */
    default double motionViewScale(UUID playerId, double minSpeedBlocksPerSec, double maxBoost) {
        return 1.0D;
    }

    /** Anchor radius (without motion boost) last decided by the governor. Default: no-op. */
    default void noteBaseViewRadius(UUID playerId, int baseRadius) {
    }

    /** Last governor anchor radius, or -1 if unknown. Default: unknown. */
    default int baseViewRadius(UUID playerId) {
        return -1;
    }

    /** Record the radius actually applied by the live motion boost and its delta over the anchor. */
    default void noteMotionApplied(UUID playerId, int appliedRadius, int boostBlocks) {
    }
}
