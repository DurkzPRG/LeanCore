package com.durkz.leancore.runtime;

import com.durkz.leancore.config.LeanCoreConfig;

/**
 * LITE profile optimizations for embedded solo worlds: motion-gated dormancy,
 * throttled heap samples, and adaptive tick when the player is idle.
 *
 * <p>Position-based decisions take the (x,z) the motion sampler captured on the world thread, so
 * this class never reads a {@code PlayerRef} transform off the world thread.
 */
public final class SoloRuntimePolicy {

    private SoloRuntimePolicy() {
    }

    public static boolean shouldRefreshDormancy(
            LeanCoreConfig config,
            double lastX,
            double lastZ,
            boolean positioned,
            long nowMs,
            long lastDormancyRefreshMs,
            double[] currentXZ
    ) {
        long minIntervalMs = Math.max(30_000L, config.soloDormancyMinIntervalSeconds * 1000L);
        if (lastDormancyRefreshMs <= 0L || nowMs - lastDormancyRefreshMs >= minIntervalMs) {
            return true;
        }

        if (currentXZ == null) {
            return false;
        }
        if (!positioned) {
            return true;
        }
        double dx = currentXZ[0] - lastX;
        double dz = currentXZ[1] - lastZ;
        return Math.hypot(dx, dz) >= Math.max(1.0D, config.soloDormancyMotionBlocks);
    }

    public static boolean shouldSampleHeap(LeanCoreConfig config, long nowMs, long lastHeapSampleMs) {
        if (lastHeapSampleMs <= 0L) {
            return true;
        }
        long intervalMs = Math.max(15_000L, config.soloHeapSampleIntervalSeconds * 1000L);
        return nowMs - lastHeapSampleMs >= intervalMs;
    }

    public static long nextTickDelaySeconds(LeanCoreConfig config, long playerIdleSec) {
        long base = Math.max(10, config.soloTickIntervalSeconds);
        if (!config.soloAdaptiveTickEnabled) {
            return base;
        }
        long idleThreshold = Math.max(60L, config.soloIdleThresholdSeconds);
        if (playerIdleSec >= idleThreshold) {
            return Math.max(base, config.soloIdleTickIntervalSeconds);
        }
        return base;
    }

    public static PlayerMotionSnapshot captureMotion(double[] currentXZ) {
        if (currentXZ == null) {
            return PlayerMotionSnapshot.empty();
        }
        return new PlayerMotionSnapshot(currentXZ[0], currentXZ[1], true);
    }

    public record PlayerMotionSnapshot(double x, double z, boolean positioned) {
        public static PlayerMotionSnapshot empty() {
            return new PlayerMotionSnapshot(0.0D, 0.0D, false);
        }
    }
}
