package com.durkz.leancore.intelligence;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.memory.MemoryTier;

/**
 * Loading-pressure gate. Uses the in-flight chunk backlog ({@code ChunkTracker} loading count, read
 * on the world thread) to keep the governor from fighting the engine chunk loader while a world or
 * player is actively streaming (join, teleport, sprint into ungenerated terrain): it holds unload
 * sweeps and holds view/hot-radius cuts during the burst, so chunks just sent are not dropped and
 * re-requested.
 */
public final class LoadingPressureGate {

    private LoadingPressureGate() {
    }

    /**
     * @param totalLoadingChunks summed in-flight chunk columns across a world's online players
     * @return true when unload should be held this pass because the world is still streaming
     */
    public static boolean holdsUnload(LeanCoreConfig config, int totalLoadingChunks) {
        if (config == null || !config.loadingPressureSignalEnabled) {
            return false;
        }
        return totalLoadingChunks > Math.max(0, config.unloadHoldWhenLoadingAbove);
    }

    /**
     * Streaming grace for a per-player radius cut. Holds a reduction (target below current) while the
     * player is actively streaming, so the client is not told to drop chunks it is loading. Real
     * pressure always wins: at {@link MemoryTier#CRITICAL} the cut is never held.
     *
     * @param loadingBacklog this player's in-flight chunk columns
     */
    public static boolean holdsRadiusReduction(
            LeanCoreConfig config, MemoryTier tier, int loadingBacklog, int target, int current) {
        if (tier == MemoryTier.CRITICAL || target >= current) {
            return false;
        }
        return holdsUnload(config, loadingBacklog);
    }
}
