package com.durkz.leancore.dormancy;

import java.util.UUID;

/**
 * Supplies a predicted future (x,z) for a player, used by the dormancy map to bias unload away
 * from zones ahead of the player. Implementations return {@code null} when no estimate exists.
 */
public interface PredictedPositionSource {

    double[] predictedXZ(UUID playerId, long horizonMs);

    /**
     * Last on-world-sampled (x,z) for the player, or {@code null} when unknown. Lets schedulers
     * reuse the world-thread position sample instead of reading the transform off the world thread.
     */
    default double[] currentXZ(UUID playerId) {
        return null;
    }

    /** Effective client view radius in chunks, or non-positive when unknown (caller falls back). */
    default int viewRadiusChunks(UUID playerId) {
        return -1;
    }
}
