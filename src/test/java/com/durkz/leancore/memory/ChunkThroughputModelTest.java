package com.durkz.leancore.memory;

import com.durkz.leancore.config.LeanCoreConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChunkThroughputModelTest {

    private static LeanCoreConfig defaults() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.chunkThroughputComfortPct = 135;
        config.chunkThroughputTightPct = 70;
        config.chunkThroughputCriticalPct = 40;
        config.chunkThroughputDrainBoostEnabled = true;
        config.chunkThroughputDrainBoostPct = 200;
        config.unloadHoldWhenLoadingAbove = 16;
        return config;
    }

    @Test
    void percentPerTierMatchesConfigAndWatchIsAlwaysHundred() {
        LeanCoreConfig config = defaults();
        assertEquals(135, ChunkThroughputModel.percentForTier(config, MemoryTier.COMFORT));
        assertEquals(100, ChunkThroughputModel.percentForTier(config, MemoryTier.WATCH));
        assertEquals(70, ChunkThroughputModel.percentForTier(config, MemoryTier.TIGHT));
        assertEquals(40, ChunkThroughputModel.percentForTier(config, MemoryTier.CRITICAL));
    }

    @Test
    void perSecondScalesRemoteBaseline() {
        LeanCoreConfig config = defaults();
        int remote = 36;
        assertEquals(49, ChunkThroughputModel.targetPerSecond(config, MemoryTier.COMFORT, remote));
        assertEquals(36, ChunkThroughputModel.targetPerSecond(config, MemoryTier.WATCH, remote));
        assertEquals(25, ChunkThroughputModel.targetPerSecond(config, MemoryTier.TIGHT, remote));
        assertEquals(14, ChunkThroughputModel.targetPerSecond(config, MemoryTier.CRITICAL, remote));
    }

    @Test
    void perSecondScalesLocalBaselineWithoutHittingFloor() {
        LeanCoreConfig config = defaults();
        int local = 256;
        assertEquals(346, ChunkThroughputModel.targetPerSecond(config, MemoryTier.COMFORT, local));
        assertEquals(102, ChunkThroughputModel.targetPerSecond(config, MemoryTier.CRITICAL, local));
    }

    @Test
    void perSecondNeverDropsBelowFloor() {
        LeanCoreConfig config = defaults();
        // 40% of 10 = 4, clamped up to the per-second floor.
        assertEquals(ChunkThroughputModel.MIN_CHUNKS_PER_SECOND,
                ChunkThroughputModel.targetPerSecond(config, MemoryTier.CRITICAL, 10));
    }

    @Test
    void perTickScalesAndNeverHitsZero() {
        LeanCoreConfig config = defaults();
        int baseline = 4;
        assertEquals(5, ChunkThroughputModel.targetPerTick(config, MemoryTier.COMFORT, baseline));
        assertEquals(4, ChunkThroughputModel.targetPerTick(config, MemoryTier.WATCH, baseline));
        assertEquals(3, ChunkThroughputModel.targetPerTick(config, MemoryTier.TIGHT, baseline));
        assertEquals(2, ChunkThroughputModel.targetPerTick(config, MemoryTier.CRITICAL, baseline));
        // 40% of 1 rounds to 0, clamped up to 1 so loading never stalls.
        assertEquals(1, ChunkThroughputModel.targetPerTick(config, MemoryTier.CRITICAL, 1));
    }

    @Test
    void drainBoostLiftsComfortWhenStreaming() {
        LeanCoreConfig config = defaults();
        // backlog above the streaming threshold, COMFORT -> drain-boost percent.
        assertEquals(200, ChunkThroughputModel.effectivePercent(config, MemoryTier.COMFORT, 17));
        assertEquals(72, ChunkThroughputModel.targetPerSecond(config, MemoryTier.COMFORT, 36, 17));
    }

    @Test
    void drainBoostInactiveWithLowBacklog() {
        LeanCoreConfig config = defaults();
        assertEquals(135, ChunkThroughputModel.effectivePercent(config, MemoryTier.COMFORT, 16));
        assertEquals(49, ChunkThroughputModel.targetPerSecond(config, MemoryTier.COMFORT, 36, 16));
    }

    @Test
    void drainBoostOnlyAppliesInComfort() {
        LeanCoreConfig config = defaults();
        assertEquals(100, ChunkThroughputModel.effectivePercent(config, MemoryTier.WATCH, 999));
        assertEquals(70, ChunkThroughputModel.effectivePercent(config, MemoryTier.TIGHT, 999));
        assertEquals(40, ChunkThroughputModel.effectivePercent(config, MemoryTier.CRITICAL, 999));
    }

    @Test
    void drainBoostOnLocalBaselineDoesNotExceedEngineCap() {
        LeanCoreConfig config = defaults();
        int local = 2560;
        assertEquals(ChunkThroughputModel.MAX_SECTIONS_PER_SECOND,
                ChunkThroughputModel.targetPerSecond(config, MemoryTier.COMFORT, local, 17));
        assertEquals(ChunkThroughputModel.MAX_SECTIONS_PER_TICK,
                ChunkThroughputModel.targetPerTick(config, MemoryTier.COMFORT, 40, 17));
    }

    @Test
    void remoteThreeSixtyScalesUnderCap() {
        LeanCoreConfig config = defaults();
        int remote = 360;
        assertEquals(486, ChunkThroughputModel.targetPerSecond(config, MemoryTier.COMFORT, remote));
        assertEquals(360, ChunkThroughputModel.targetPerSecond(config, MemoryTier.WATCH, remote));
        assertEquals(144, ChunkThroughputModel.targetPerSecond(config, MemoryTier.CRITICAL, remote));
    }

    @Test
    void drainBoostRespectsDisableFlag() {
        LeanCoreConfig config = defaults();
        config.chunkThroughputDrainBoostEnabled = false;
        assertEquals(135, ChunkThroughputModel.effectivePercent(config, MemoryTier.COMFORT, 999));
    }
}
