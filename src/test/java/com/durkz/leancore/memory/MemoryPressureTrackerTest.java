package com.durkz.leancore.memory;

import com.durkz.leancore.config.LeanCoreConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryPressureTrackerTest {

    private static final long GB = 1024L * 1024L * 1024L;

    @Test
    void sustainedPostGcGrowthAnticipatesTightBeforeRawThreshold() {
        LeanCoreConfig config = new LeanCoreConfig();
        MemoryPressureTracker tracker = new MemoryPressureTracker();

        MemoryPressureTracker.Pressure pressure = null;
        double[] postGcSamples = {0.55D, 0.60D, 0.65D, 0.70D, 0.75D, 0.79D, 0.79D};
        for (int i = 0; i < postGcSamples.length; i++) {
            pressure = tracker.observe((i + 1L) * 5_000_000_000L,
                    (long) (GB * postGcSamples[i]), GB, 0L, 0L,
                    (i + 1L) * 100L, i + 1L, (i + 1L) * 10L, config);
        }

        assertEquals(MemoryTier.TIGHT, pressure.predictedTier());
        assertEquals("heap-growth", pressure.reason());
        assertTrue(pressure.secondsToTight() < 30.0D);
    }

    @Test
    void oldGenerationCanEscalatePressureIndependently() {
        LeanCoreConfig config = new LeanCoreConfig();
        MemoryPressureTracker tracker = new MemoryPressureTracker();

        MemoryPressureTracker.Pressure pressure = tracker.observe(
                1_000_000_000L, GB / 2, GB, (long) (GB * 0.91D), GB,
                100L, 0L, 0L, config);

        assertEquals(MemoryTier.CRITICAL, pressure.predictedTier());
        assertEquals("old-gen-critical", pressure.reason());
    }

    @Test
    void gcRefreshesPostGcLiveHeapEstimate() {
        LeanCoreConfig config = new LeanCoreConfig();
        MemoryPressureTracker tracker = new MemoryPressureTracker();
        tracker.observe(1_000_000_000L, (long) (GB * 0.75D), GB, 0L, 0L, 100L, 1L, 10L, config);

        MemoryPressureTracker.Pressure pressure = tracker.observe(
                2_000_000_000L, (long) (GB * 0.40D), GB, 0L, 0L, 200L, 2L, 20L, config);

        assertTrue(pressure.postGcHeapUsed() < (long) (GB * 0.75D));
        assertTrue(pressure.postGcHeapUsed() > (long) (GB * 0.40D));
    }

    @Test
    void bootstrapAndSingleHeapSpikeDoNotEscalateFastTier() {
        LeanCoreConfig config = new LeanCoreConfig();
        MemoryPressureTracker tracker = new MemoryPressureTracker();

        tracker.observe(1_000_000_000L, (long) (GB * 0.20D), GB,
                0L, 0L, 100L, 1L, 10L, config);
        MemoryPressureTracker.Pressure spike = tracker.observe(
                6_000_000_000L, (long) (GB * 0.86D), GB,
                0L, 0L, 200L, 1L, 10L, config);
        tracker.observe(11_000_000_000L, (long) (GB * 0.45D), GB,
                0L, 0L, 300L, 2L, 20L, config);
        MemoryPressureTracker.Pressure recovered = tracker.observe(
                16_000_000_000L, (long) (GB * 0.50D), GB,
                0L, 0L, 400L, 2L, 20L, config);

        assertEquals(MemoryTier.COMFORT, spike.predictedTier());
        assertEquals(MemoryTier.COMFORT, recovered.predictedTier());
    }

    @Test
    void sustainedOldGenerationPressureRequiresConfirmation() {
        LeanCoreConfig config = new LeanCoreConfig();
        MemoryPressureTracker tracker = new MemoryPressureTracker();
        for (int i = 1; i <= 4; i++) {
            tracker.observe(i * 5_000_000_000L, GB / 2, GB,
                    GB / 2, GB, i * 100L, i, i * 10L, config);
        }

        MemoryPressureTracker.Pressure first = tracker.observe(
                25_000_000_000L, GB / 2, GB, (long) (GB * 0.84D), GB,
                500L, 5L, 50L, config);
        MemoryPressureTracker.Pressure second = tracker.observe(
                30_000_000_000L, GB / 2, GB, (long) (GB * 0.84D), GB,
                600L, 6L, 60L, config);

        assertEquals(MemoryTier.COMFORT, first.predictedTier());
        assertEquals(MemoryTier.TIGHT, second.predictedTier());
        assertEquals("old-gen-tight", second.reason());
    }

    @Test
    void measuredPostGcHeapWinsOverHighRawHeap() {
        LeanCoreConfig config = new LeanCoreConfig();
        MemoryPressureTracker tracker = new MemoryPressureTracker();

        MemoryPressureTracker.Pressure pressure = null;
        for (int i = 1; i <= 8; i++) {
            pressure = tracker.observe(i * 5_000_000_000L, (long) (GB * 0.86D), GB,
                    0L, 0L, i * 100L, i, i * 10L, (long) (GB * 0.40D), config);
        }

        assertEquals(MemoryTier.COMFORT, pressure.predictedTier());
        assertEquals((long) (GB * 0.40D), pressure.postGcHeapUsed());
    }
}
