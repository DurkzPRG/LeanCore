package com.durkz.leancore.dormancy;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.intelligence.UnloadOutcomeTracker;
import com.durkz.leancore.memory.MemoryTier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZoneChunkUnloaderLiteTest {

    @Test
    void sweepLiteSkipsWhenPlayerNotIdleLongEnough() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.governEnabled = false;
        config.liteUnloadEnabled = true;
        config.liteUnloadIdleSeconds = 180;
        config.probePassedAtMs = System.currentTimeMillis();

        ZoneChunkUnloader unloader = new ZoneChunkUnloader(config, new UnloadOutcomeTracker());
        int unloaded = unloader.sweepLite(new ZoneDormancyMap(config), MemoryTier.COMFORT, 60L);

        assertEquals(0, unloaded);
    }

    @Test
    void sweepLiteSkipsWhenLiteUnloadDisabled() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.governEnabled = false;
        config.liteUnloadEnabled = false;
        config.probePassedAtMs = System.currentTimeMillis();

        ZoneChunkUnloader unloader = new ZoneChunkUnloader(config, new UnloadOutcomeTracker());
        int unloaded = unloader.sweepLite(new ZoneDormancyMap(config), MemoryTier.WATCH, 300L);

        assertEquals(0, unloaded);
    }

    @Test
    void sweepLiteYieldsWhenEngineOwnsHeap() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.liteUnloadEnabled = true;
        config.liteUnloadIdleSeconds = 180;
        config.probePassedAtMs = System.currentTimeMillis();

        ZoneChunkUnloader unloader = new ZoneChunkUnloader(config, new UnloadOutcomeTracker());
        int unloaded = unloader.sweepLite(new ZoneDormancyMap(config), MemoryTier.CRITICAL, 300L, 0.90D);

        assertEquals(0, unloaded);
        assertTrue(unloader.lastSweepYieldedToEngine());
        assertEquals(1, unloader.engineUnloadYields());
    }

    @Test
    void engineOwnsUnloadAtDesperateThreshold() {
        assertTrue(ZoneChunkUnloader.engineOwnsUnload(0.85D));
        assertTrue(ZoneChunkUnloader.engineOwnsUnload(0.90D));
        assertFalse(ZoneChunkUnloader.engineOwnsUnload(0.82D));
        assertFalse(ZoneChunkUnloader.engineOwnsUnload(0.0D));
    }
}
