package com.durkz.leancore.api;

import com.durkz.leancore.LeanCorePlugin;
import com.durkz.leancore.dormancy.ZoneKey;
import com.durkz.leancore.intelligence.HoldoutSet;
import com.durkz.leancore.intelligence.PlayerBehavior;
import com.durkz.leancore.intelligence.PlayerFeatureState;
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
        LeanCorePlugin plugin = LeanCorePlugin.getInstance();
        return plugin != null && plugin.runtime() != null;
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
        PlayerFeatureState features = runtime.classifier().features().snapshot().get(playerId);
        PlayerBehavior label = demand.debugLabel();
        if (features == null) {
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
