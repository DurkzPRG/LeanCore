package com.durkz.leancore.memory;

import com.durkz.leancore.config.LeanCoreConfig;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class MemoryPressureSensor {

    private final LeanCoreConfig config;
    private MemoryTier lastTier = MemoryTier.COMFORT;

    public MemoryPressureSensor(LeanCoreConfig config) {
        this.config = config;
    }

    public MemorySnapshot sample() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        long max = rt.maxMemory();
        double ratio = max <= 0L ? 0.0D : (double) used / max;

        Collection<PlayerRef> players = Universe.get().getPlayers();
        MemoryTier tier = resolveTier(ratio);
        return new MemorySnapshot(used, max, ratio, players.size(), maxPairwiseSpread(players), tier);
    }

    private MemoryTier resolveTier(double ratio) {
        MemoryTier next;
        if (ratio >= config.criticalHeapRatio) {
            next = MemoryTier.CRITICAL;
        } else if (ratio >= config.tightHeapRatio) {
            next = MemoryTier.TIGHT;
        } else if (ratio >= config.watchHeapRatio) {
            next = MemoryTier.WATCH;
        } else {
            next = MemoryTier.COMFORT;
        }

        // Step down one tier per tick so a single GC pause does not yo-yo the governor.
        if (next.ordinal() < lastTier.ordinal()) {
            next = MemoryTier.values()[lastTier.ordinal() - 1];
        }
        lastTier = next;
        return next;
    }

    // O(n^2) but n is tiny on the hosts we care about; spread drives per-player footprint caps in v0.2.
    private static double maxPairwiseSpread(Collection<PlayerRef> players) {
        if (players.size() < 2) {
            return 0.0D;
        }

        List<double[]> xz = new ArrayList<>(players.size());
        for (PlayerRef ref : players) {
            if (!ref.isValid()) {
                continue;
            }
            Transform t = ref.getTransform();
            if (t == null || t.getPosition() == null) {
                continue;
            }
            xz.add(new double[]{t.getPosition().x, t.getPosition().z});
        }
        if (xz.size() < 2) {
            return 0.0D;
        }

        double max = 0.0D;
        for (int i = 0; i < xz.size(); i++) {
            for (int j = i + 1; j < xz.size(); j++) {
                double dx = xz.get(i)[0] - xz.get(j)[0];
                double dz = xz.get(i)[1] - xz.get(j)[1];
                max = Math.max(max, Math.hypot(dx, dz));
            }
        }
        return max;
    }
}
