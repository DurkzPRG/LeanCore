package com.durkz.leancore.intelligence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeatureNormalizerTest {

    @Test
    void clamp01_bounds() {
        assertEquals(0.0D, FeatureNormalizer.clamp01(-1.0D));
        assertEquals(1.0D, FeatureNormalizer.clamp01(2.0D));
        assertEquals(0.5D, FeatureNormalizer.clamp01(0.5D));
    }

    @Test
    void dot_computesWeightedSum() {
        double[] w = {1.0D, 2.0D};
        double[] x = {0.5D, 0.25D};
        assertEquals(1.0D, FeatureNormalizer.dot(w, x), 0.0001D);
    }

    @Test
    void demandVector_hasExpectedSize() {
        PlayerFeatureState state = new PlayerFeatureState(java.util.UUID.randomUUID());
        state.onBlockBroken();
        double[] vector = FeatureSchema.demandVector(state, System.currentTimeMillis());
        assertEquals(FeatureSchema.DEMAND_DIM, vector.length);
        for (double v : vector) {
            assertTrue(v >= 0.0D && v <= 1.0D);
        }
    }
}
