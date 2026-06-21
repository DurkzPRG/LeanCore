package com.durkz.leancore.memory;

import com.durkz.leancore.dormancy.PredictedPositionSource;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class MemoryPressureSensor {

    private final ServerContextTracker serverContext;
    private final SessionSavingsTracker sessionSavings;
    private volatile PredictedPositionSource positions;

    public MemoryPressureSensor(ServerContextTracker serverContext) {
        this(serverContext, null);
    }

    public MemoryPressureSensor(ServerContextTracker serverContext, SessionSavingsTracker sessionSavings) {
        this.serverContext = serverContext;
        this.sessionSavings = sessionSavings;
    }

    /**
     * Wires the on-world motion sample used for player spread. Without it (passive heap sensors)
     * spread is reported as zero rather than read off the world thread.
     */
    public void setPositionSource(PredictedPositionSource positions) {
        this.positions = positions;
    }

    public MemorySnapshot sample() {
        return sample(true);
    }

    public MemorySnapshot sample(boolean trackQuantiles) {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        long max = rt.maxMemory();
        double ratio = max <= 0L ? 0.0D : (double) used / max;

        long nowMs = System.currentTimeMillis();
        if (sessionSavings != null) {
            sessionSavings.noteHeapSample(used, max, nowMs);
        }
        if (trackQuantiles) {
            serverContext.observe(ratio, nowMs);
        }

        Collection<PlayerRef> players = Universe.get().getPlayers();
        MemoryTier tier = trackQuantiles
                ? serverContext.resolveTier(ratio)
                : serverContext.resolveTierFixed(ratio);
        return new MemorySnapshot(used, max, ratio, players.size(), maxPairwiseSpread(players), tier);
    }

    /**
     * Largest pairwise distance between online players, from the motion sampler's on-world (x,z)
     * snapshot (identity reads only here, no transform). Returns zero when no source is wired.
     */
    private double maxPairwiseSpread(Collection<PlayerRef> players) {
        PredictedPositionSource source = this.positions;
        if (source == null || players.size() < 2) {
            return 0.0D;
        }

        List<double[]> xz = new ArrayList<>(players.size());
        for (PlayerRef ref : players) {
            if (ref == null || !ref.isValid()) {
                continue;
            }
            double[] pos = source.currentXZ(ref.getUuid());
            if (pos != null) {
                xz.add(pos);
            }
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
