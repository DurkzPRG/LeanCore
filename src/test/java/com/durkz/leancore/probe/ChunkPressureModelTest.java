package com.durkz.leancore.probe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkPressureModelTest {

    @Test
    void viewTwoIsDiscreteSphereOfRadiusTwo() {
        // Integer sphere r=2 is 33 cells; world height 10 fully contains it.
        assertEquals(33, ChunkPressureModel.viewSectionBudget(2));
        assertEquals(33, ChunkPressureModel.viewChunkBudget(2));
    }

    @Test
    void viewEightExceedsOldColumnGridAndFitsThreeHundredSections() {
        int oldColumnGrid = 17 * 17;
        int budget = ChunkPressureModel.viewSectionBudget(8);
        assertTrue(budget > oldColumnGrid, "3D section budget must beat the old (2r+1)^2 column grid");
        assertTrue(budget > 300, "300 loaded sections at view 8 must not pin saturation at 1.0");
        double saturation = 300.0D / budget;
        assertTrue(saturation < 1.0D);
        assertTrue(ChunkPressureModel.normalize(300, 0, 8, -1) < ChunkPressureModel.MAX_PRESSURE);
    }

    @Test
    void viewSixteenIsLargerThanViewEight() {
        assertTrue(ChunkPressureModel.viewSectionBudget(16) > ChunkPressureModel.viewSectionBudget(8));
    }

    @Test
    void aliasMatchesSectionBudget() {
        assertEquals(ChunkPressureModel.viewSectionBudget(8), ChunkPressureModel.viewChunkBudget(8));
        assertEquals(ChunkPressureModel.viewSectionBudget(16), ChunkPressureModel.viewChunkBudget(16));
    }

    @Test
    void loadedThreeHundredAtViewEightDoesNotSaturateHoldStress() {
        double at300 = ChunkPressureModel.normalize(300, 0, 8, -1);
        double atFull = ChunkPressureModel.normalize(ChunkPressureModel.viewSectionBudget(8), 0, 8, -1);
        assertTrue(at300 < atFull);
    }
}
