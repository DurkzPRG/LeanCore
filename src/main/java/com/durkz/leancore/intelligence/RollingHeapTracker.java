package com.durkz.leancore.intelligence;

import java.util.ArrayDeque;
import java.util.Deque;

public final class RollingHeapTracker {

    private static final long WINDOW_60S_MS = 60_000L;
    private static final long WINDOW_15M_MS = 15 * 60_000L;
    private static final long WINDOW_24H_MS = 24 * 60 * 60_000L;
    private static final long MAX_RETAIN_MS = WINDOW_24H_MS;

    private final Deque<Sample> samples = new ArrayDeque<>();

    public synchronized void add(double heapRatio, long nowMs) {
        samples.addLast(new Sample(heapRatio, nowMs));
        prune(nowMs);
    }

    public synchronized double avg60s(long nowMs) {
        return avg(WINDOW_60S_MS, nowMs);
    }

    synchronized double avg15m(long nowMs) {
        return avg(WINDOW_15M_MS, nowMs);
    }

    synchronized double avg24h(long nowMs) {
        return avg(WINDOW_24H_MS, nowMs);
    }

    private double avg(long windowMs, long nowMs) {
        prune(nowMs);
        long cutoff = nowMs - windowMs;
        double sum = 0.0D;
        int count = 0;
        for (Sample sample : samples) {
            if (sample.atMs >= cutoff) {
                sum += sample.heapRatio;
                count++;
            }
        }
        return count == 0 ? 0.0D : sum / count;
    }

    private void prune(long nowMs) {
        long cutoff = nowMs - MAX_RETAIN_MS;
        while (!samples.isEmpty() && samples.peekFirst().atMs < cutoff) {
            samples.removeFirst();
        }
    }

    private record Sample(double heapRatio, long atMs) {
    }
}
