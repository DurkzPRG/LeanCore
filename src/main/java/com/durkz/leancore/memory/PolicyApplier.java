package com.durkz.leancore.memory;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.intelligence.PlayerBehavior;
import com.durkz.leancore.intelligence.RetentionDemand;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

public class PolicyApplier {

    private final LeanCoreConfig config;

    public PolicyApplier(LeanCoreConfig config) {
        this.config = config;
    }

    public int apply(GovernorPolicy policy, Collection<PlayerRef> online, Map<UUID, RetentionDemand> demands) {
        if (policy == null) {
            return 0;
        }
        int scheduled = 0;
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
            world.execute(() -> applyOne(playerRef, policy, demand));
            scheduled++;
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
        int target = targetRadius(player, policy, demand);
        if (player.getClientViewRadius() != target) {
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
}
