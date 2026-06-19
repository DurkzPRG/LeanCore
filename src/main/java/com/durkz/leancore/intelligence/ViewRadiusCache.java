package com.durkz.leancore.intelligence;

import java.util.UUID;

public interface ViewRadiusCache {

    void noteViewRadius(UUID playerId, int serverRadius, int clientRadius);

    /** Upward-only view-radius multiplier in [1.0, maxBoost] for fast movers. Default: no boost. */
    default double motionViewScale(UUID playerId, double minSpeedBlocksPerSec, double maxBoost) {
        return 1.0D;
    }
}
