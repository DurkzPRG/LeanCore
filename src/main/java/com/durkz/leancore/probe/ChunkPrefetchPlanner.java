package com.durkz.leancore.probe;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure planner for predictive chunk prefetch. Given a player's current and predicted (x,z) in
 * blocks, it returns the chunk columns just beyond the current view edge along the heading, capped
 * at {@code maxChunks}. No engine state: callers turn each {@code {chunkX, chunkZ}} into an index
 * and decide whether to load it. Returns empty when the player is barely moving.
 */
public final class ChunkPrefetchPlanner {

    private static final double CHUNK_BLOCKS = 16.0D;

    private ChunkPrefetchPlanner() {
    }

    public static List<int[]> plan(
            double curX, double curZ, double predX, double predZ, int viewRadiusChunks, int maxChunks) {
        List<int[]> out = new ArrayList<>();
        if (maxChunks <= 0 || viewRadiusChunks < 0) {
            return out;
        }
        double dx = predX - curX;
        double dz = predZ - curZ;
        double dist = Math.hypot(dx, dz);
        // Need at least one chunk of intended movement, otherwise the heading is just noise.
        if (dist < CHUNK_BLOCKS) {
            return out;
        }
        double ux = dx / dist;
        double uz = dz / dist;
        int lastCx = Integer.MIN_VALUE;
        int lastCz = Integer.MIN_VALUE;
        for (int i = 1; i <= maxChunks; i++) {
            int ahead = viewRadiusChunks + i;
            double bx = curX + ux * ahead * CHUNK_BLOCKS;
            double bz = curZ + uz * ahead * CHUNK_BLOCKS;
            int cx = (int) Math.floor(bx / CHUNK_BLOCKS);
            int cz = (int) Math.floor(bz / CHUNK_BLOCKS);
            if (cx != lastCx || cz != lastCz) {
                out.add(new int[]{cx, cz});
                lastCx = cx;
                lastCz = cz;
            }
        }
        return out;
    }
}
