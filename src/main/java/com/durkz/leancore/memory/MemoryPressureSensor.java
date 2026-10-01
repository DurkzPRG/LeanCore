package com.durkz.leancore.memory;

import com.durkz.leancore.dormancy.PredictedPositionSource;
import com.durkz.leancore.config.LeanCoreConfig;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;

public class MemoryPressureSensor {

    private final ServerContextTracker serverContext;
    private final SessionSavingsTracker sessionSavings;
    private final LeanCoreConfig config;
    private final MemoryPressureTracker pressureTracker = new MemoryPressureTracker();
    private final Map<String, Long> lastCollectorCounts = new HashMap<>();
    private Set<String> heapPoolNames;
    private long lastPostGcHeapUsed = -1L;
    private volatile PredictedPositionSource positions;

    public MemoryPressureSensor(ServerContextTracker serverContext) {
        this(serverContext, null, new LeanCoreConfig());
    }

    public MemoryPressureSensor(ServerContextTracker serverContext, SessionSavingsTracker sessionSavings) {
        this(serverContext, sessionSavings, new LeanCoreConfig());
    }

    public MemoryPressureSensor(ServerContextTracker serverContext, SessionSavingsTracker sessionSavings,
                                LeanCoreConfig config) {
        this.serverContext = serverContext;
        this.sessionSavings = sessionSavings;
        this.config = config;
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

    public synchronized MemorySnapshot sample(boolean trackQuantiles) {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        long max = rt.maxMemory();
        double ratio = max <= 0L ? 0.0D : (double) used / max;
        long[] oldGen = oldGenerationUsage();
        long[] gc = gcTotals();
        MemoryPressureTracker.Pressure pressure = pressureTracker.observe(
                System.nanoTime(), used, max, oldGen[0], oldGen[1], totalAllocatedBytes(),
                gc[0], gc[1], postGcHeapUsed(), config);

        long nowMs = System.currentTimeMillis();
        if (sessionSavings != null) {
            sessionSavings.noteHeapSample(used, max, nowMs);
        }
        if (trackQuantiles) {
            serverContext.observe(ratio, nowMs);
        }

        Collection<PlayerRef> players = Universe.get().getPlayers();
        MemoryTier tier = trackQuantiles
                ? serverContext.resolveTier(pressure.effectiveRatio(), ratio, pressure.predictedTier())
                : pressure.predictedTier();
        return new MemorySnapshot(used, max, ratio, players.size(), maxPairwiseSpread(players), tier,
                oldGen[0], oldGen[1], pressure.postGcHeapUsed(), pressure.allocationBytesPerSecond(),
                pressure.gcPauseRatio(), pressure.heapSlopePerSecond(), pressure.secondsToTight(), pressure.reason());
    }

    private static long[] oldGenerationUsage() {
        long used = 0L;
        long max = 0L;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != MemoryType.HEAP || pool.getUsage() == null) {
                continue;
            }
            String name = pool.getName().toLowerCase(java.util.Locale.ROOT);
            if (!name.contains("old") && !name.contains("tenured")) {
                continue;
            }
            used += Math.max(0L, pool.getUsage().getUsed());
            max += Math.max(0L, pool.getUsage().getMax());
        }
        return new long[]{used, max};
    }

    private static long[] gcTotals() {
        long count = 0L;
        long timeMs = 0L;
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            count += Math.max(0L, collector.getCollectionCount());
            timeMs += Math.max(0L, collector.getCollectionTime());
        }
        return new long[]{count, timeMs};
    }

    /** Heap used right after the most recent GC, from the collector's own GcInfo; -1 until one ran. */
    private long postGcHeapUsed() {
        if (heapPoolNames == null) {
            heapPoolNames = new HashSet<>();
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                if (pool.getType() == MemoryType.HEAP) {
                    heapPoolNames.add(pool.getName());
                }
            }
        }
        long latestEnd = -1L;
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = collector.getCollectionCount();
            Long previous = lastCollectorCounts.put(collector.getName(), count);
            if ((previous != null && previous == count)
                    || !(collector instanceof com.sun.management.GarbageCollectorMXBean withInfo)) {
                continue;
            }
            com.sun.management.GcInfo info = withInfo.getLastGcInfo();
            if (info == null || info.getEndTime() < latestEnd) {
                continue;
            }
            long heapAfter = 0L;
            for (Map.Entry<String, MemoryUsage> pool : info.getMemoryUsageAfterGc().entrySet()) {
                if (heapPoolNames.contains(pool.getKey())) {
                    heapAfter += Math.max(0L, pool.getValue().getUsed());
                }
            }
            latestEnd = info.getEndTime();
            lastPostGcHeapUsed = heapAfter;
        }
        return lastPostGcHeapUsed;
    }

    private static long totalAllocatedBytes() {
        if (!(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean allocationBean)
                || !allocationBean.isThreadAllocatedMemorySupported()
                || !allocationBean.isThreadAllocatedMemoryEnabled()) {
            return 0L;
        }
        return Math.max(0L, allocationBean.getTotalThreadAllocatedBytes());
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
