package com.durkz.leancore.dormancy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZoneContentModelTest {

    @Test
    void zeroForEmptyOrNegative() {
        assertEquals(0.0D, ZoneContentModel.scoreFromBlockEntities(0), 1e-9);
        assertEquals(0.0D, ZoneContentModel.scoreFromBlockEntities(-5), 1e-9);
    }

    @Test
    void monotonicUpToSaturation() {
        double one = ZoneContentModel.scoreFromBlockEntities(1);
        double four = ZoneContentModel.scoreFromBlockEntities(4);
        double eight = ZoneContentModel.scoreFromBlockEntities(8);
        assertTrue(one > 0.0D && one < four, "more block entities scores higher");
        assertTrue(four < eight, "score keeps rising toward the saturation point");
        assertEquals(1.0D, eight, 1e-9, "a clear base (>= norm) saturates the score");
    }

    @Test
    void clampedAtOne() {
        assertEquals(1.0D, ZoneContentModel.scoreFromBlockEntities(64), 1e-9);
        assertEquals(1.0D, ZoneContentModel.scoreFromBlockEntities(10_000), 1e-9);
    }

    @Test
    void saturationCapIsTheExactClampPoint() {
        int cap = ZoneContentModel.saturationBlockEntities();
        assertEquals(1.0D, ZoneContentModel.scoreFromBlockEntities(cap), 1e-9,
                "the cap must already score 1.0 so the early-exit is lossless");
        assertTrue(ZoneContentModel.scoreFromBlockEntities(cap - 1) < 1.0D,
                "one below the cap must still be under 1.0, so the cap is the smallest such count");
    }
}
