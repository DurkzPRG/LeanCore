package com.durkz.leancore.intelligence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerMotionModelTest {

    @Test
    void straightLinePredictionLeadsCurrentPosition() {
        PlayerMotionModel motion = new PlayerMotionModel();
        long t = 0L;
        double x = 0.0D;
        motion.update(x, 0.0D, t);
        for (int i = 0; i < 12; i++) {
            t += 1_000L;
            x += 5.0D;
            motion.update(x, 0.0D, t);
        }

        assertTrue(motion.speedBlocksPerSec() > 4.0D && motion.speedBlocksPerSec() < 6.0D,
                "speed should converge near 5 b/s, was " + motion.speedBlocksPerSec());
        assertTrue(motion.confidence() > 0.7D, "steady motion should be confident, was " + motion.confidence());

        double[] predicted = motion.predictedXZ(3_000L);
        assertNotNull(predicted);
        assertTrue(predicted[0] > x, "prediction should lead the last x=" + x + ", was " + predicted[0]);
        assertTrue(predicted[0] < x + 20.0D, "prediction should stay bounded, was " + predicted[0]);
    }

    @Test
    void stationaryPlayerPredictsCurrentPosition() {
        PlayerMotionModel motion = new PlayerMotionModel();
        long t = 0L;
        motion.update(10.0D, 10.0D, t);
        for (int i = 0; i < 10; i++) {
            t += 1_000L;
            motion.update(10.0D, 10.0D, t);
        }

        assertTrue(motion.speedBlocksPerSec() < 0.5D, "stationary speed should be ~0");
        double[] predicted = motion.predictedXZ(3_000L);
        assertNotNull(predicted);
        assertTrue(Math.hypot(predicted[0] - 10.0D, predicted[1] - 10.0D) < 0.5D,
                "stationary prediction should match current position");
    }

    @Test
    void erraticMotionHasLowConfidence() {
        PlayerMotionModel motion = new PlayerMotionModel();
        long t = 0L;
        motion.update(0.0D, 0.0D, t);
        for (int i = 0; i < 12; i++) {
            t += 1_000L;
            motion.update(i % 2 == 0 ? 5.0D : 0.0D, 0.0D, t);
        }

        assertTrue(motion.confidence() < 0.3D, "erratic motion should be low confidence, was " + motion.confidence());
        double[] predicted = motion.predictedXZ(3_000L);
        assertNotNull(predicted);
        assertTrue(Math.abs(predicted[0] - (motion.predictedXZ(0L))[0]) < 2.0D,
                "low confidence should collapse prediction toward current");
    }

    @Test
    void teleportSpikeResetsEstimate() {
        PlayerMotionModel motion = new PlayerMotionModel();
        long t = 0L;
        double x = 0.0D;
        motion.update(x, 0.0D, t);
        for (int i = 0; i < 6; i++) {
            t += 1_000L;
            x += 5.0D;
            motion.update(x, 0.0D, t);
        }
        t += 1_000L;
        motion.update(100_000.0D, 0.0D, t);

        assertTrue(motion.speedBlocksPerSec() < 0.5D, "teleport should reset velocity to ~0");
    }

    @Test
    void viewScaleBoostsFastSteadyMoverOnly() {
        PlayerMotionModel motion = new PlayerMotionModel();
        long t = 0L;
        double x = 0.0D;
        motion.update(x, 0.0D, t);
        for (int i = 0; i < 14; i++) {
            t += 1_000L;
            x += 10.0D;
            motion.update(x, 0.0D, t);
        }

        double scale = motion.viewScale(3.0D, 1.2D);
        assertTrue(scale > 1.1D && scale <= 1.2D, "fast steady mover should approach max boost, was " + scale);

        PlayerMotionModel idle = new PlayerMotionModel();
        idle.update(0.0D, 0.0D, 0L);
        idle.update(0.0D, 0.0D, 1_000L);
        assertTrue(idle.viewScale(3.0D, 1.2D) == 1.0D, "idle player should get no boost");
    }

    @Test
    void boostNotSuppressedWhileAccelerating() {
        // Speeding up lowers confidence() via steadiness, but the boost rides a warmup floor instead.
        PlayerMotionModel accel = new PlayerMotionModel();
        long t = 0L;
        double x = 0.0D;
        double v = 3.0D;
        accel.update(x, 0.0D, t);
        for (int i = 0; i < 8; i++) {
            t += 1_000L;
            v += 1.0D;
            x += v;
            accel.update(x, 0.0D, t);
        }

        assertTrue(accel.viewScale(2.0D, 1.6D) > 1.0D,
                "an accelerating fast mover should still get a boost, was " + accel.viewScale(2.0D, 1.6D));
    }

    @Test
    void boostRampsInWithinAboutOneSecond() {
        // ~1s of samples is enough thanks to the warmup floor.
        PlayerMotionModel motion = new PlayerMotionModel();
        motion.update(0.0D, 0.0D, 0L);
        motion.update(8.0D, 0.0D, 500L);
        motion.update(16.0D, 0.0D, 1_000L);

        assertTrue(motion.viewScale(2.0D, 1.6D) > 1.0D,
                "fast mover should be boosted within ~1s, was " + motion.viewScale(2.0D, 1.6D));
    }

    @Test
    void accelerationTermLeadsPredictionForSpeedingUpPlayer() {
        PlayerMotionModel motion = new PlayerMotionModel();
        long t = 0L;
        double x = 0.0D;
        double v = 4.0D;
        motion.update(x, 0.0D, t);
        for (int i = 0; i < 10; i++) {
            t += 1_000L;
            v += 0.5D;
            x += v;
            motion.update(x, 0.0D, t);
        }

        double[] predicted = motion.predictedXZ(3_000L);
        assertNotNull(predicted);
        assertTrue(predicted[0] > x, "prediction should lead a speeding-up player, was " + predicted[0] + " vs x=" + x);
        assertTrue(predicted[0] < x + 120.0D, "prediction should stay physically bounded, was " + predicted[0]);
    }
}
