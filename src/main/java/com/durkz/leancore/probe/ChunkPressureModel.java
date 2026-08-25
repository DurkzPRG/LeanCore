package com.durkz.leancore.probe;

/**
 * Chunk-pressure math. The numerator from {@code ChunkTracker} is a section count
 * ({@code getLoadedSectionsCount}); the budget is the same unit: sections inside the engine's
 * view sphere, clipped to world height (10 sections on 0.6).
 */
public final class ChunkPressureModel {

    public static final double MAX_PRESSURE = 256.0D;
    private static final double LOADING_WEIGHT = 4.0D;
    private static final double SATURATION_WEIGHT = 64.0D;
    private static final double EXPLORE_DELTA_WEIGHT = 0.5D;
    private static final double MAX_EXPLORE_STRESS = 32.0D;

    /** Matches {@code ChunkUtil.HEIGHT_SECTIONS} on Hytale 0.6 (Y 0..9). */
    static final int HEIGHT_SECTIONS = 10;

    private static final int BUDGET_CACHE_SIZE = 65;
    private static final int[] SECTION_BUDGET_CACHE = new int[BUDGET_CACHE_SIZE];

    static {
        for (int radius = 0; radius < BUDGET_CACHE_SIZE; radius++) {
            SECTION_BUDGET_CACHE[radius] = computeViewSectionBudget(Math.max(1, radius));
        }
    }

    private ChunkPressureModel() {
    }

    /**
     * Sections the engine can keep in view at this radius (3D sphere, world-height clipped).
     * Same unit as {@code ChunkTracker.getLoadedSectionsCount()}.
     */
    public static int viewSectionBudget(int viewRadius) {
        int radius = Math.max(1, viewRadius);
        if (radius < BUDGET_CACHE_SIZE) {
            return SECTION_BUDGET_CACHE[radius];
        }
        return computeViewSectionBudget(radius);
    }

    /** Alias kept for existing callers; returns {@link #viewSectionBudget(int)}. */
    public static int viewChunkBudget(int viewRadius) {
        return viewSectionBudget(viewRadius);
    }

    /**
     * Discrete sphere {@code dx²+dy²+dz² <= r²}, Y clipped to {@link #HEIGHT_SECTIONS}.
     * Uses the player-Y that maximises the count so saturation is not overstated.
     */
    static int computeViewSectionBudget(int radius) {
        int rSq = radius * radius;
        int height = HEIGHT_SECTIONS;
        int max = 0;
        for (int centerY = 0; centerY < height; centerY++) {
            int count = 0;
            for (int dx = -radius; dx <= radius; dx++) {
                int dxSq = dx * dx;
                for (int dz = -radius; dz <= radius; dz++) {
                    int horizSq = dxSq + dz * dz;
                    if (horizSq > rSq) {
                        continue;
                    }
                    for (int y = 0; y < height; y++) {
                        int dy = y - centerY;
                        if (horizSq + dy * dy <= rSq) {
                            count++;
                        }
                    }
                }
            }
            if (count > max) {
                max = count;
            }
        }
        return Math.max(1, max);
    }

    public static double normalize(int loaded, int loading, int viewRadius, int previousLoaded) {
        int budget = Math.max(1, viewSectionBudget(viewRadius));
        double saturation = Math.min(1.0D, (double) loaded / budget);
        double loadStress = Math.max(0, loading) * LOADING_WEIGHT;
        double holdStress = saturation * SATURATION_WEIGHT;
        double exploreStress = 0.0D;
        if (previousLoaded >= 0) {
            int delta = Math.max(0, loaded - previousLoaded);
            exploreStress = Math.min(MAX_EXPLORE_STRESS, delta * EXPLORE_DELTA_WEIGHT);
        }
        return Math.min(MAX_PRESSURE, loadStress + holdStress + exploreStress);
    }
}
