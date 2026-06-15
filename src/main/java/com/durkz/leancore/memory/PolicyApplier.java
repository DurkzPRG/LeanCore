package com.durkz.leancore.memory;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.runtime.RuntimeGuard;
import com.durkz.leancore.runtime.RuntimeProfile;
import com.durkz.leancore.runtime.WorldDispatch;
import com.durkz.leancore.intelligence.FalseCutTracker;
import com.durkz.leancore.intelligence.HeuristicDemandModel;
import com.durkz.leancore.intelligence.HoldoutSet;
import com.durkz.leancore.intelligence.PlayerBehavior;
import com.durkz.leancore.intelligence.RetentionDemand;
import com.durkz.leancore.intelligence.ViewRadiusCache;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class PolicyApplier {

    private final LeanCoreConfig config;
    private final FalseCutTracker falseCutTracker;
    private final ViewRadiusCache viewRadiusCache;

    private String lastAppliedPolicyKey;
    private long lastApplyMs;

    public PolicyApplier(LeanCoreConfig config, FalseCutTracker falseCutTracker, ViewRadiusCache viewRadiusCache) {
        this.config = config;
        this.falseCutTracker = falseCutTracker;
        this.viewRadiusCache = viewRadiusCache;
    }

    public int apply(
            GovernorPolicy policy,
            Collection<PlayerRef> online,
            Map<UUID, RetentionDemand> demands,
            boolean policyChanged
    ) {
        return apply(policy, online, demands, policyChanged, null);
    }

    public int apply(
            GovernorPolicy policy,
            Collection<PlayerRef> online,
            Map<UUID, RetentionDemand> demands,
            boolean policyChanged,
            RuntimeProfile profile
    ) {
        if (policy == null || !RuntimeGuard.active()) {
            return 0;
        }
        String applyKey = applyKey(policy, profile);
        if (!policyChanged
                && lastAppliedPolicyKey != null
                && applyKey.equals(lastAppliedPolicyKey)) {
            return 0;
        }

        long nowMs = System.currentTimeMillis();
        long minIntervalMs = Math.max(1, config.policyApplyMinIntervalSeconds) * 1000L;
        if (policyChanged && lastApplyMs > 0L && nowMs - lastApplyMs < minIntervalMs) {
            return 0;
        }

        Map<UUID, List<PlayerApply>> byWorld = new HashMap<>();
        for (PlayerRef playerRef : online) {
            if (!playerRef.isValid()) {
                continue;
            }
            UUID worldUuid = playerRef.getWorldUuid();
            if (worldUuid == null) {
                continue;
            }
            World world = Universe.get().getWorld(worldUuid);
            if (world == null || !world.isAlive()) {
                continue;
            }
            RetentionDemand demand = demands.getOrDefault(
                    playerRef.getUuid(),
                    RetentionDemand.coldStart(PlayerBehavior.UNKNOWN)
            );
            byWorld.computeIfAbsent(worldUuid, ignored -> new ArrayList<>())
                    .add(new PlayerApply(playerRef, demand));
        }

        int scheduled = 0;
        for (Map.Entry<UUID, List<PlayerApply>> entry : byWorld.entrySet()) {
            World world = Universe.get().getWorld(entry.getKey());
            if (world == null || !world.isAlive()) {
                continue;
            }
            List<PlayerApply> batch = List.copyOf(entry.getValue());
            if (!RuntimeGuard.active()) {
                continue;
            }
            WorldDispatch.run(world, () -> {
                if (!RuntimeGuard.active()) {
                    return;
                }
                for (PlayerApply item : batch) {
                    applyOne(item.playerRef(), policy, item.demand(), profile);
                }
            });
            scheduled += batch.size();
        }

        if (scheduled > 0) {
            lastApplyMs = nowMs;
            lastAppliedPolicyKey = applyKey;
        }
        return scheduled;
    }

    private static String applyKey(GovernorPolicy policy, RuntimeProfile profile) {
        if (profile == RuntimeProfile.LITE) {
            return "LITE:"
                    + policy.key()
                    + "@"
                    + String.format(Locale.ROOT, "%.4f", policy.viewScale());
        }
        return policy.key();
    }

    private void applyOne(
            PlayerRef playerRef,
            GovernorPolicy policy,
            RetentionDemand demand,
            RuntimeProfile profile
    ) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null) {
            return;
        }
        Store<EntityStore> store = ref.getStore();
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }

        UUID playerId = playerRef.getUuid();
        if (viewRadiusCache != null) {
            viewRadiusCache.noteViewRadius(playerId, player.getViewRadius(), player.getClientViewRadius());
        }

        int current = player.getClientViewRadius();
        int target = targetRadius(player, policy, demand, profile);

        if (HoldoutSet.isHoldout(playerId) && profile != RuntimeProfile.LITE && target < current) {
            return;
        }
        if (profile == RuntimeProfile.LITE && target < current && HeuristicDemandModel.isHighDemand(demand.demand())) {
            return;
        }
        if (target < current && HeuristicDemandModel.isHighDemand(demand.demand())) {
            falseCutTracker.noteCut(true);
        }

        int minDelta = Math.max(1, config.minViewRadiusDelta);
        if (Math.abs(target - current) < minDelta) {
            return;
        }
        player.setClientViewRadius(target);
    }

    private int targetRadius(Player player, GovernorPolicy policy, RetentionDemand demand, RuntimeProfile profile) {
        return resolveTargetClientRadius(
                config,
                profile,
                player.getViewRadius(),
                policy,
                demand
        );
    }

    static int resolveTargetClientRadius(
            LeanCoreConfig config,
            RuntimeProfile profile,
            int serverViewRadius,
            GovernorPolicy policy,
            RetentionDemand demand
    ) {
        int serverRadius = Math.max(1, serverViewRadius);
        int scaled = (int) Math.round(serverRadius * policy.viewScale() * demand.viewScale());
        int minClient = profile == RuntimeProfile.LITE
                ? Math.max(config.minClientViewRadius, config.liteMinClientViewRadius)
                : config.minClientViewRadius;
        return clamp(scaled, minClient, config.maxClientViewRadius);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record PlayerApply(PlayerRef playerRef, RetentionDemand demand) {
    }
}
