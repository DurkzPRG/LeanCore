package com.durkz.leancore.dormancy;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.intelligence.UnloadOutcomeTracker;
import com.durkz.leancore.memory.MemoryTier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
