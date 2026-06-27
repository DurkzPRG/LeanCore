package com.durkz.leancore.dormancy;

/**
 * Content-aware zone scoring (v1.7.0 Frente B). Turns a regional scan of built content into a [0,1]
 * score that biases dormancy toward keeping content-rich zones HOT even when idle. Pure math, no
 * engine access; the raw counts come from {@code RegionalEntityProbe} on the world thread.
 * <p>
 * The dominant, robust signal is block-entity density (chests, benches, doors, any placed
 * infrastructure all register as block-entities). A handful already marks a built base, so the score
 * saturates fast. The persisted EMA lives on {@code ZoneReuseModel.ZoneReuseStat}.
 */
public final class ZoneContentModel {

    // A zone region is 4x4 chunks. ~8 block-entities is already a clear base; saturate around there.
    private static final double BLOCK_ENTITY_NORM = 8.0D;

    private ZoneContentModel() {
    }

    /** Content score in [0,1] from the block-entity count found across the region's chunks. */
    public static double scoreFromBlockEntities(int blockEntities) {
        if (blockEntities <= 0) {
            return 0.0D;
        }
        double score = Math.log(1.0D + blockEntities) / Math.log(1.0D + BLOCK_ENTITY_NORM);
        return Math.max(0.0D, Math.min(1.0D, score));
    }

    /**
     * Smallest block-entity count whose score is already clamped to 1.0. Once a regional scan reaches
     * this many block-entities the score cannot rise further, so the scan can stop early without
     * changing the result.
     */
    public static int saturationBlockEntities() {
        return (int) Math.ceil(BLOCK_ENTITY_NORM);
    }
}
