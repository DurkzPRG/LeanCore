package com.durkz.leancore.intelligence;

import java.util.UUID;

public final class HoldoutSet {

    private static final int HOLDOUT_PERCENT = 10;

    private HoldoutSet() {
    }

    public static boolean isHoldout(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        int bucket = Math.floorMod(playerId.hashCode(), 100);
        return bucket < HOLDOUT_PERCENT;
    }
}
