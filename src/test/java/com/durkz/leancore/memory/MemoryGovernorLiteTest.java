package com.durkz.leancore.memory;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.dormancy.ZoneChunkUnloader;
import com.durkz.leancore.dormancy.ZoneDormancyMap;
import com.durkz.leancore.intelligence.LearningStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MemoryGovernorLiteTest {

    @Test
    void tickLiteModeIdleWhenDisabled(@TempDir Path dataDir) {
        LeanCoreConfig config = new LeanCoreConfig();
        config.liteMemoryGovernorEnabled = false;
        MemoryGovernor governor = governor(config, dataDir);

        governor.tickLiteMode(
                sample(MemoryTier.COMFORT),
                Map.of(),
                new ZoneDormancyMap(config),
                0.0D,
                System.currentTimeMillis(),
                System.currentTimeMillis()
        );

        assertFalse(governor.status().enabled());
    }

    @Test
    void policyForWatchTierUsesLiteScale() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.liteViewWatchScale = 0.94D;

        GovernorPolicy policy = LiteViewScaleResolver.policyFor(config, MemoryTier.WATCH, 0.0D);

        assertEquals(GovernorPreset.SOLO_LEAN, policy.preset());
        assertEquals(MemoryTier.WATCH, policy.tier());
        assertEquals(0.94D, policy.viewScale(), 0.001D);
        assertEquals(1, policy.demoteBatch());
    }

    @Test
    void policyForComfortHighSaturationCapsViewScale() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.liteViewPressureThreshold = 0.85D;
        config.liteViewComfortCapScale = 0.97D;

        GovernorPolicy policy = LiteViewScaleResolver.policyFor(config, MemoryTier.COMFORT, 0.90D);

        assertEquals(0.97D, policy.viewScale(), 0.001D);
        assertEquals(0, policy.demoteBatch());
    }

    private static MemoryGovernor governor(LeanCoreConfig config, Path dataDir) {
        LearningStore learningStore = new LearningStore(dataDir, config);
        PolicyApplier applier = new PolicyApplier(config, learningStore.falseCutTracker(), null);
        ZoneChunkUnloader unloader = new ZoneChunkUnloader(config, learningStore.unloadOutcomeTracker());
        return new MemoryGovernor(
                config,
                new RetentionAllocator(config),
                applier,
                unloader,
                learningStore
        );
    }

    private static MemorySnapshot sample(MemoryTier tier) {
        return new MemorySnapshot(512L * 1024 * 1024, 1024L * 1024 * 1024, 0.50D, 1, 0.0D, tier);
    }
}
