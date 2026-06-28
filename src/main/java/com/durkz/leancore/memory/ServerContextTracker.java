package com.durkz.leancore.memory;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.diagnostics.DiagnosticLog;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

public class ServerContextTracker {

    private static final int MIN_SAMPLES = 120;
    private static final int MAX_SAMPLES = 5_000;
    private static final long RECOMPUTE_INTERVAL_MS = 30_000L;

    private final LeanCoreConfig config;
    private final Deque<Double> samples = new ArrayDeque<>();

    private volatile double q50;
    private volatile double q75;
    private volatile double q90;
    private volatile double q97;
    private long lastRecomputeMs;
    private MemoryTier lastTier = MemoryTier.COMFORT;

    public ServerContextTracker(LeanCoreConfig config) {
        this.config = config;
        q50 = config.watchHeapRatio * 0.85D;
        q75 = config.watchHeapRatio;
        q90 = config.tightHeapRatio;
        q97 = config.criticalHeapRatio;
    }

    public void observe(double heapRatio, long nowMs) {
        samples.addLast(heapRatio);
        while (samples.size() > MAX_SAMPLES) {
            samples.removeFirst();
        }
        if (nowMs - lastRecomputeMs >= RECOMPUTE_INTERVAL_MS) {
            recomputeQuantiles();
            lastRecomputeMs = nowMs;
        }
    }

    public MemoryTier resolveTierFixed(double heapRatio) {
        return resolveFixed(heapRatio);
    }

    public MemoryTier resolveTier(double heapRatio) {
        if (samples.size() < MIN_SAMPLES) {
            return resolveFixed(heapRatio);
        }
        MemoryTier prev = lastTier;
        MemoryTier raw;
        if (heapRatio >= q97) {
            raw = MemoryTier.CRITICAL;
        } else if (heapRatio >= q90) {
            raw = MemoryTier.TIGHT;
        } else if (heapRatio >= q75) {
            raw = MemoryTier.WATCH;
        } else {
            raw = MemoryTier.COMFORT;
        }
        MemoryTier next = raw;
        boolean hysteresis = false;
        if (next.ordinal() < prev.ordinal()) {
            next = MemoryTier.values()[prev.ordinal() - 1];
            hysteresis = next != raw;
        }
        lastTier = next;
        logTierChange(prev, next, heapRatio, true, hysteresis);
        return next;
    }

    public double q50() {
        return q50;
    }

    public double q75() {
        return q75;
    }

    public double q90() {
        return q90;
    }

    public double q97() {
        return q97;
    }

    public int sampleCount() {
        return samples.size();
    }

    public void hydrate(double q50, double q75, double q90, double q97) {
        if (q50 > 0.0D) {
            this.q50 = q50;
        }
        if (q75 > 0.0D) {
            this.q75 = q75;
        }
        if (q90 > 0.0D) {
            this.q90 = q90;
        }
        if (q97 > 0.0D) {
            this.q97 = q97;
        }
    }

    private MemoryTier resolveFixed(double heapRatio) {
        MemoryTier prev = lastTier;
        MemoryTier raw;
        if (heapRatio >= config.criticalHeapRatio) {
            raw = MemoryTier.CRITICAL;
        } else if (heapRatio >= config.tightHeapRatio) {
            raw = MemoryTier.TIGHT;
        } else if (heapRatio >= config.watchHeapRatio) {
            raw = MemoryTier.WATCH;
        } else {
            raw = MemoryTier.COMFORT;
        }
        MemoryTier next = raw;
        boolean hysteresis = false;
        if (next.ordinal() < prev.ordinal()) {
            next = MemoryTier.values()[prev.ordinal() - 1];
            hysteresis = next != raw;
        }
        lastTier = next;
        logTierChange(prev, next, heapRatio, false, hysteresis);
        return next;
    }

    private void logTierChange(MemoryTier prev, MemoryTier next, double heapRatio,
                               boolean adaptive, boolean hysteresis) {
        if (next == prev) {
            return;
        }
        String bounds = adaptive
                ? String.format(Locale.ROOT, "q75=%.0f%% q90=%.0f%% q97=%.0f%%",
                        q75 * 100.0D, q90 * 100.0D, q97 * 100.0D)
                : String.format(Locale.ROOT, "watch=%.0f%% tight=%.0f%% crit=%.0f%%",
                        config.watchHeapRatio * 100.0D, config.tightHeapRatio * 100.0D,
                        config.criticalHeapRatio * 100.0D);
        DiagnosticLog.info(String.format(Locale.ROOT, "tier %s->%s heap=%.0f%% [%s]%s",
                prev, next, heapRatio * 100.0D, bounds,
                hysteresis ? " (hysteresis: 1-step downgrade)" : ""));
    }

    private void recomputeQuantiles() {
        if (samples.isEmpty()) {
            return;
        }
        List<Double> sorted = new ArrayList<>(samples);
        sorted.sort(Double::compare);
        q50 = percentile(sorted, 0.50D);
        q75 = percentile(sorted, 0.75D);
        q90 = percentile(sorted, 0.90D);
        q97 = percentile(sorted, 0.97D);
        q75 = Math.max(q75, q50 + 0.02D);
        q90 = Math.max(q90, q75 + 0.02D);
        q97 = Math.max(q97, q90 + 0.02D);
    }

    private static double percentile(List<Double> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0.0D;
        }
        if (sorted.size() == 1) {
            return sorted.getFirst();
        }
        double rank = p * (sorted.size() - 1);
        int low = (int) Math.floor(rank);
        int high = (int) Math.ceil(rank);
        if (low == high) {
            return sorted.get(low);
        }
        double weight = rank - low;
        return sorted.get(low) * (1.0D - weight) + sorted.get(high) * weight;
    }
}
