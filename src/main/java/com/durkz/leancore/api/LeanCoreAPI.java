package com.durkz.leancore.api;

import com.durkz.leancore.LeanCorePlugin;
import com.durkz.leancore.dormancy.ZoneKey;
import com.durkz.leancore.intelligence.HoldoutSet;
import com.durkz.leancore.intelligence.PlayerBehavior;
import com.durkz.leancore.intelligence.RetentionDemand;
import com.durkz.leancore.memory.MemorySnapshot;
import com.durkz.leancore.memory.MemoryTier;
import com.durkz.leancore.runtime.MemoryRuntime;

import java.util.Optional;
import java.util.UUID;

public final class LeanCoreAPI {

    private LeanCoreAPI() {
    }

    public static boolean isLoaded() {
        return LeanCorePlugin.getInstance() != null;
    }

    public static boolean isBackgroundRuntimeActive() {
        LeanCorePlugin plugin = LeanCorePlugin.getInstance();
        return plugin != null
                && !plugin.isPassiveMode()
                && plugin.runtime() != null;
    }

    public static boolean isScaledEmbeddedRuntime() {
        LeanCorePlugin plugin = LeanCorePlugin.getInstance();
        if (plugin == null || plugin.isPassiveMode() || plugin.runtime() == null || plugin.config() == null) {
            return false;
        }
        return !plugin.config().dedicatedServerMode;
    }

    public static Optional<MemoryTier> currentTier() {
        MemoryRuntime runtime = runtime();
        if (runtime == null) {
            return Optional.empty();
        }
        return Optional.of(runtime.lastSample().tier());
    }

    public static Optional<MemorySnapshot> heapSnapshot() {
        MemoryRuntime runtime = runtime();
        if (runtime == null) {
            return Optional.empty();
        }
        return Optional.of(runtime.lastSample());
    }

    public static boolean pinZone(UUID worldUuid, double blockX, double blockZ) {
        MemoryRuntime runtime = runtime();
        if (runtime == null || worldUuid == null) {
            return false;
        }
        return runtime.dormancyMap().pinZone(ZoneKey.fromBlockCoords(worldUuid, blockX, blockZ));
    }

    public static boolean unpinZone(UUID worldUuid, double blockX, double blockZ) {
        MemoryRuntime runtime = runtime();
        if (runtime == null || worldUuid == null) {
            return false;
        }
        return runtime.dormancyMap().unpinZone(ZoneKey.fromBlockCoords(worldUuid, blockX, blockZ));
    }

    public static Optional<LeanCoreSnapshot> playerSnapshot(UUID playerId) {
        MemoryRuntime runtime = runtime();
        if (runtime == null || playerId == null) {
            return Optional.empty();
        }
        long nowMs = System.currentTimeMillis();
        MemorySnapshot sample = runtime.lastSample();
        RetentionDemand demand = runtime.classifier().snapshotDemands(nowMs).getOrDefault(
                playerId,
                runtime.learningStore().demandFor(playerId)
        );
        PlayerBehavior label = demand.debugLabel();
        return Optional.of(new LeanCoreSnapshot(
                sample.tier(),
                sample.heapUsedRatio(),
                sample.onlinePlayers(),
                playerId,
                demand.demand(),
                demand.confidence(),
                demand.retentionMb(),
                label,
                HoldoutSet.isHoldout(playerId)
        ));
    }

    private static MemoryRuntime runtime() {
        LeanCorePlugin plugin = LeanCorePlugin.getInstance();
        return plugin == null ? null : plugin.runtime();
    }
}
