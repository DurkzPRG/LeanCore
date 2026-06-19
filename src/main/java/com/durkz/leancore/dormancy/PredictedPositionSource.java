package com.durkz.leancore.dormancy;

import java.util.UUID;

/**
 * Supplies a predicted future (x,z) for a player, used by the dormancy map to bias unload away
 * from zones ahead of the player. Implementations return {@code null} when no estimate exists.
 */
public interface PredictedPositionSource {

    double[] predictedXZ(UUID playerId, long horizonMs);
}
