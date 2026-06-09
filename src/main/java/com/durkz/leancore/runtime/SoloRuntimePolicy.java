package com.durkz.leancore.runtime;

import com.durkz.leancore.config.LeanCoreConfig;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

/**
 * LITE profile optimizations for embedded solo worlds: motion-gated dormancy,
 * throttled heap samples, and adaptive tick when the player is idle.
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
            long lastDormancyRefreshMs
    ) {
        long minIntervalMs = Math.max(30_000L, config.soloDormancyMinIntervalSeconds * 1000L);
        if (lastDormancyRefreshMs <= 0L || nowMs - lastDormancyRefreshMs >= minIntervalMs) {
            return true;
        }

        PlayerRef player = firstOnlinePlayer();
        if (player == null) {
            return false;
        }
        Transform t = player.getTransform();
        if (t == null || t.getPosition() == null) {
            return false;
        }
        if (!positioned) {
            return true;
        }
        double dx = t.getPosition().x - lastX;
        double dz = t.getPosition().z - lastZ;
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

    public static PlayerMotionSnapshot captureMotion() {
        PlayerRef player = firstOnlinePlayer();
        if (player == null) {
            return PlayerMotionSnapshot.empty();
        }
        Transform t = player.getTransform();
        if (t == null || t.getPosition() == null) {
            return PlayerMotionSnapshot.empty();
        }
        return new PlayerMotionSnapshot(t.getPosition().x, t.getPosition().z, true);
    }

    private static PlayerRef firstOnlinePlayer() {
        for (PlayerRef ref : Universe.get().getPlayers()) {
            if (ref != null && ref.isValid()) {
                return ref;
            }
        }
        return null;
    }

    public record PlayerMotionSnapshot(double x, double z, boolean positioned) {
        public static PlayerMotionSnapshot empty() {
            return new PlayerMotionSnapshot(0.0D, 0.0D, false);
        }
    }
}
