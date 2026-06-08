package com.durkz.leancore.memory;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.intelligence.FalseCutTracker;
import com.durkz.leancore.intelligence.HoldoutSet;
import com.durkz.leancore.intelligence.PlayerBehavior;
import com.durkz.leancore.intelligence.RetentionDemand;
import com.durkz.leancore.intelligence.RetentionDemandEstimator;
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
        if (policy == null) {
            return 0;
        }
        long nowMs = System.currentTimeMillis();
        long minIntervalMs = Math.max(1, config.policyApplyMinIntervalSeconds) * 1000L;
        if (!policyChanged
                && lastAppliedPolicyKey != null
                && policy.key().equals(lastAppliedPolicyKey)
                && nowMs - lastApplyMs < minIntervalMs) {
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
            world.execute(() -> {
                for (PlayerApply item : batch) {
                    applyOne(item.playerRef(), policy, item.demand());
                }
            });
            scheduled += batch.size();
        }

        if (scheduled > 0) {
            lastApplyMs = nowMs;
            lastAppliedPolicyKey = policy.key();
        }
        return scheduled;
    }

    private void applyOne(PlayerRef playerRef, GovernorPolicy policy, RetentionDemand demand) {
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
        int target = targetRadius(player, policy, demand);
        if (HoldoutSet.isHoldout(playerId) && target < current) {
            return;
        }
        if (target < current && RetentionDemandEstimator.isHighDemand(demand.demand())) {
            falseCutTracker.noteCut(true);
        }
        if (current != target) {
            player.setClientViewRadius(target);
        }
    }

    private int targetRadius(Player player, GovernorPolicy policy, RetentionDemand demand) {
        int serverRadius = Math.max(1, player.getViewRadius());
        int scaled = (int) Math.round(serverRadius * policy.viewScale() * demand.viewScale());
        return clamp(scaled, config.minClientViewRadius, config.maxClientViewRadius);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record PlayerApply(PlayerRef playerRef, RetentionDemand demand) {
    }
}
