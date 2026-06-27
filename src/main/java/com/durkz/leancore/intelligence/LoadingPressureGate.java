package com.durkz.leancore.intelligence;

import com.durkz.leancore.config.LeanCoreConfig;

/**
 * Loading-pressure gate. Holds unload sweeps for a world while it is actively streaming chunks to
 * its players (join, teleport, sprint into ungenerated terrain), so the governor does not fight the
 * engine chunk loader and force chunks it just sent to reload. The backlog is the sum of in-flight
 * columns across that world's {@code ChunkTracker}s, read on the world thread.
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
}
