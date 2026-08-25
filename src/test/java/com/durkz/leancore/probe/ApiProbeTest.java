package com.durkz.leancore.probe;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiProbeTest {

    @Test
    void passedWhenEveryStepOk() {
        List<String> lines = List.of(
                "probe:",
                "S1 view-radius: ok server=16 client=16 write=setClientViewRadius",
                "S2 position: ok 0 64 0 world=abc",
                "S3 chunks: ok loadedSections=10 loadingSections=0 raw=0 norm=0.0 view=16 budget=1024",
                "S4 entities: ok nearby=0",
                "S5 unload: ok api=ChunkStore.remove(UNLOAD) candidates=0 lastUnloaded=0 storeLoaded=100"
        );
        assertTrue(ApiProbe.passed(lines));
    }

    @Test
    void failedWhenAnyStepFails() {
        List<String> lines = List.of(
                "probe:",
                "S1 view-radius: ok server=16 client=16 write=setClientViewRadius",
                "S2 position: fail"
        );
        assertFalse(ApiProbe.passed(lines));
    }

    @Test
    void failedWhenAnyStepSkipped() {
        List<String> lines = List.of(
                "probe:",
                "S1 view-radius: ok server=16 client=16 write=setClientViewRadius",
                "S2 position: skip (no player)"
        );
        assertFalse(ApiProbe.passed(lines));
    }

    @Test
    void failedWhenNoSteps() {
        assertFalse(ApiProbe.passed(List.of("probe:")));
        assertFalse(ApiProbe.passed(null));
    }
}
