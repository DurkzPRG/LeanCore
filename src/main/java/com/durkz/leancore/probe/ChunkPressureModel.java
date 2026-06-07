package com.durkz.leancore.probe;

public final class ChunkPressureModel {

    public static final double MAX_PRESSURE = 256.0D;
    private static final double LOADING_WEIGHT = 4.0D;
    private static final double SATURATION_WEIGHT = 64.0D;
    private static final double EXPLORE_DELTA_WEIGHT = 0.5D;
    private static final double MAX_EXPLORE_STRESS = 32.0D;

    private ChunkPressureModel() {
    }

    public static int viewChunkBudget(int viewRadius) {
        int radius = Math.max(1, viewRadius);
        long side = 2L * radius + 1L;
        long budget = side * side;
        return budget > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) budget;
    }

    public static double normalize(int loaded, int loading, int viewRadius, int previousLoaded) {
        int budget = Math.max(1, viewChunkBudget(viewRadius));
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
