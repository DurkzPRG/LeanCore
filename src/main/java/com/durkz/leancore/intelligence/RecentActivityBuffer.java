package com.durkz.leancore.intelligence;

/**
 * Short rolling window of classified actions for fast role switches (seconds, not minutes).
 */
public final class RecentActivityBuffer {

    private static final int CAPACITY = 16;

    private final ActionKind[] ring = new ActionKind[CAPACITY];
    private int head;
    private int size;

    public void record(ActionKind kind) {
        if (kind == null || kind == ActionKind.UNKNOWN) {
            return;
        }
        ring[head] = kind;
        head = (head + 1) % CAPACITY;
        if (size < CAPACITY) {
            size++;
        }
    }

    public PlayerBehavior dominantBehavior(int minSamples, double minShare) {
        if (size < minSamples || minShare <= 0.0D) {
            return null;
        }
        int[] counts = new int[ActionKind.values().length];
        for (int i = 0; i < size; i++) {
            int idx = (head - 1 - i + CAPACITY) % CAPACITY;
            ActionKind kind = ring[idx];
            if (kind != null) {
                counts[kind.ordinal()]++;
            }
        }
        int bestKind = -1;
        int bestCount = 0;
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] > bestCount) {
                bestCount = counts[i];
                bestKind = i;
            }
        }
        if (bestKind < 0 || bestCount < minSamples) {
            return null;
        }
        double share = bestCount / (double) size;
        if (share < minShare) {
            return null;
        }
        return ActionKind.values()[bestKind].toBehavior();
    }

    public int size() {
        return size;
    }
}
