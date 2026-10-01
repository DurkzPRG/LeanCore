package com.durkz.leancore.dormancy;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.diagnostics.DiagnosticLog;
import com.durkz.leancore.intelligence.UnloadOutcomeTracker;
import com.durkz.leancore.memory.MemoryTier;

/**
 * Former zone chunk unloader. LeanCore no longer removes chunks itself.
 * <p>
 * The engine's {@code ChunkUnloadingSystem} removes every chunk no player tracks after 15 polls of
 * 0.5s (7.5s), on 0.6 and 0.7. A zone only becomes a LeanCore candidate after minutes of dormancy,
 * so the only chunks still loaded by then are the ones the engine holds on purpose: unsaved changes
 * or keep-loaded regions. Removing those skips the save the engine is waiting for. Memory is now
 * reclaimed through view/hot radius, and spare heap is spent on {@link ChunkRetention} instead.
 * <p>
 * The sweep entry points stay so callers, status lines and configs keep working; they never unload.
 */
public class ZoneChunkUnloader {

    private final LeanCoreConfig config;

    private volatile boolean lastSweepYieldedToEngine;
    private volatile int engineUnloadYields;

    /** Same threshold as engine {@code ChunkUnloadingSystem.DESPERATE_UNLOAD_RAM_USAGE_THRESHOLD}. */
    public static final double ENGINE_DESPERATE_HEAP_RATIO = 0.85D;

    /** The outcome tracker is no longer fed from here; kept so construction sites stay unchanged. */
    public ZoneChunkUnloader(LeanCoreConfig config, UnloadOutcomeTracker unloadOutcomeTracker) {
        this.config = config;
    }

    public static boolean engineOwnsUnload(double heapUsedRatio) {
        return heapUsedRatio >= ENGINE_DESPERATE_HEAP_RATIO;
    }

    public int sweep(ZoneDormancyMap dormancyMap, MemoryTier tier) {
        return sweep(dormancyMap, tier, 0.0D);
    }

    public int sweep(ZoneDormancyMap dormancyMap, MemoryTier tier, double heapUsedRatio) {
        return noUnload(heapUsedRatio, config.governEnabled && config.unloadEnabled);
    }

    public int sweepLite(ZoneDormancyMap dormancyMap, MemoryTier tier, long playerIdleSec) {
        return sweepLite(dormancyMap, tier, playerIdleSec, 0.0D);
    }

    public int sweepLite(ZoneDormancyMap dormancyMap, MemoryTier tier, long playerIdleSec, double heapUsedRatio) {
        return noUnload(heapUsedRatio, config.liteUnloadEnabled);
    }

    private int noUnload(double heapUsedRatio, boolean unloadConfigured) {
        lastSweepYieldedToEngine = false;
        if (engineOwnsUnload(heapUsedRatio)) {
            lastSweepYieldedToEngine = true;
            engineUnloadYields++;
            DiagnosticLog.infoOnChange("engine-unload-yield",
                    "engine desperate unload owns heap >= 85%");
        }
        if (config.enabled && unloadConfigured) {
            DiagnosticLog.infoOnChange("lite-unload-gate",
                    "chunk removal off: the engine unloads untracked chunks after 7.5s");
        }
        return 0;
    }

    public int lastUnloadedChunks() {
        return 0;
    }

    public int lastCandidateZones() {
        return 0;
    }

    public boolean lastSweepYieldedToEngine() {
        return lastSweepYieldedToEngine;
    }

    public int engineUnloadYields() {
        return engineUnloadYields;
    }
}
