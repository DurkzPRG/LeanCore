package com.durkz.leancore.intelligence;

import java.util.UUID;

public final class HoldoutSet {

    /** ~10% holdout using UUID v4 random bits (stable per player, uniform at small N). */
    private static final int HOLDOUT_BUCKET_THRESHOLD = 26;

    private HoldoutSet() {
    }

    public static boolean isHoldout(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        int bucket = (int) ((playerId.getLeastSignificantBits() >>> 8) & 0xFF);
        return bucket < HOLDOUT_BUCKET_THRESHOLD;
    }
}
