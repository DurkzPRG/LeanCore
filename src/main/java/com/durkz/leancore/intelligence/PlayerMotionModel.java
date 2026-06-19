package com.durkz.leancore.intelligence;

/**
 * Per-player constant-velocity estimate (steady-state first-order Kalman, i.e. a velocity EMA).
 * Used to predict near-future position so the unloader skips zones ahead of the player and to
 * size the client view radius for fast movers. Transient kinematics: never persisted.
 */
public final class PlayerMotionModel {

    private static final double VELOCITY_HALF_LIFE_MS = 2_000.0D;
    private static final double MIN_DT_MS = 1.0D;
    private static final long MAX_SANE_DT_MS = 5_000L;
    private static final double MAX_SANE_SPEED = 60.0D;
    private static final double WARMUP_MS = 4_000.0D;

    private double vx;
    private double vz;
    private double lastX;
    private double lastZ;
    private long lastSampleMs;
    private boolean positioned;
    private double observedMs;
    private double speedVarianceEma;

    public void update(double x, double z, long nowMs) {
        if (!positioned) {
            reset(x, z, nowMs);
            return;
        }
        long dtMs = nowMs - lastSampleMs;
        if (dtMs < MIN_DT_MS) {
            return;
        }
        if (dtMs > MAX_SANE_DT_MS) {
            reset(x, z, nowMs);
            return;
        }
        double dt = dtMs / 1000.0D;
        double instVx = (x - lastX) / dt;
        double instVz = (z - lastZ) / dt;
        double instSpeed = Math.hypot(instVx, instVz);
        if (instSpeed > MAX_SANE_SPEED) {
            reset(x, z, nowMs);
            return;
        }
        double blend = 1.0D - Math.exp(-Math.log(2.0D) * dtMs / VELOCITY_HALF_LIFE_MS);
        vx = vx * (1.0D - blend) + instVx * blend;
        vz = vz * (1.0D - blend) + instVz * blend;

        double speedDelta = instSpeed - Math.hypot(vx, vz);
        speedVarianceEma = speedVarianceEma * (1.0D - blend) + speedDelta * speedDelta * blend;

        lastX = x;
        lastZ = z;
        lastSampleMs = nowMs;
        observedMs = Math.min(observedMs + dtMs, 600_000.0D);
    }

    private void reset(double x, double z, long nowMs) {
        vx = 0.0D;
        vz = 0.0D;
        lastX = x;
        lastZ = z;
        lastSampleMs = nowMs;
        observedMs = 0.0D;
        speedVarianceEma = 0.0D;
        positioned = true;
    }

    public double speedBlocksPerSec() {
        return Math.hypot(vx, vz);
    }

    public boolean positioned() {
        return positioned;
    }

    /** 0..1: high when observed long enough and the velocity is steady (low relative variance). */
    public double confidence() {
        if (!positioned) {
            return 0.0D;
        }
        double timeFactor = Math.min(1.0D, observedMs / WARMUP_MS);
        double speed = speedBlocksPerSec();
        double rel = speed < 0.5D ? 0.0D : Math.sqrt(speedVarianceEma) / (speed + 0.5D);
        double steadiness = FeatureNormalizer.clamp01(1.0D - rel);
        return FeatureNormalizer.clamp01(timeFactor * steadiness);
    }

    /** Predicted (x,z) horizonMs ahead, damped by confidence. Null until the first sample lands. */
    public double[] predictedXZ(long horizonMs) {
        if (!positioned) {
            return null;
        }
        double horizon = Math.max(0L, horizonMs) / 1000.0D;
        double k = confidence();
        return new double[]{lastX + vx * horizon * k, lastZ + vz * horizon * k};
    }

    /**
     * View-radius multiplier in [1.0, maxBoost]. 1.0 below {@code minSpeedBlocksPerSec}; ramps up
     * with speed (saturating around twice the threshold) and is damped by confidence. Upward only.
     */
    public double viewScale(double minSpeedBlocksPerSec, double maxBoost) {
        double cap = Math.max(1.0D, maxBoost);
        if (cap <= 1.0D) {
            return 1.0D;
        }
        double minSpeed = Math.max(1.0E-6D, minSpeedBlocksPerSec);
        double speed = speedBlocksPerSec();
        if (speed <= minSpeed) {
            return 1.0D;
        }
        double ramp = FeatureNormalizer.clamp01((speed - minSpeed) / minSpeed);
        return 1.0D + (cap - 1.0D) * ramp * confidence();
    }
}
