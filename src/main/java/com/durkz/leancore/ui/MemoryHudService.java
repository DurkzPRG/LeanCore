package com.durkz.leancore.ui;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.dormancy.ZoneDormancyMap;
import com.durkz.leancore.dormancy.ZoneKey;
import com.durkz.leancore.dormancy.ZoneState;
import com.durkz.leancore.intelligence.RetentionDemand;
import com.durkz.leancore.memory.GovernorStatus;
import com.durkz.leancore.memory.MemorySnapshot;
import com.durkz.leancore.permissions.LeanCorePermissions;
import com.durkz.leancore.runtime.MemoryRuntime;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MemoryHudService {

    private final LeanCoreConfig config;
    private final HudSessionStore sessions;
    private final Map<UUID, LeanCoreStatusHud> active = new ConcurrentHashMap<>();
    private long lastRefreshMs;

    public MemoryHudService(LeanCoreConfig config, HudSessionStore sessions) {
        this.config = config;
        this.sessions = sessions;
    }

    public HudSessionStore sessions() {
        return sessions;
    }

    public boolean enable(PlayerRef playerRef, Store<EntityStore> store, Ref<EntityStore> ref) {
        if (playerRef == null || !playerRef.isValid()) {
            return false;
        }
        if (!LeanCorePermissions.canViewHud(playerRef.getUuid(), config)) {
            return false;
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return false;
        }
        sessions.setEnabled(playerRef.getUuid(), true);
        LeanCoreStatusHud hud = active.computeIfAbsent(playerRef.getUuid(), id -> new LeanCoreStatusHud(playerRef));
        player.getHudManager().addCustomHud(playerRef, hud);
        return true;
    }

    public void disable(PlayerRef playerRef, Store<EntityStore> store, Ref<EntityStore> ref) {
        if (playerRef == null) {
            return;
        }
        sessions.setEnabled(playerRef.getUuid(), false);
        active.remove(playerRef.getUuid());
        if (store == null || ref == null) {
            return;
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        player.getHudManager().removeCustomHud(playerRef, LeanCoreStatusHud.KEY);
    }

    public void onDisconnect(UUID uuid) {
        if (uuid == null) {
            return;
        }
        active.remove(uuid);
    }

    public void shutdown() {
        for (PlayerRef ref : Universe.get().getPlayers()) {
            if (ref == null || !ref.isValid()) {
                continue;
            }
            var entityRef = ref.getReference();
            if (entityRef == null) {
                continue;
            }
            disable(ref, entityRef.getStore(), entityRef);
        }
        active.clear();
    }

    public void refresh(MemoryRuntime runtime) {
        if (!config.hudFeatureEnabled || !config.enabled) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        long intervalMs = Math.max(1, config.hudUpdateIntervalSeconds) * 1000L;
        if (lastRefreshMs > 0L && nowMs - lastRefreshMs < intervalMs) {
            return;
        }
        lastRefreshMs = nowMs;

        MemorySnapshot sample = runtime.lastSample();
        GovernorStatus gov = runtime.governorStatus();
        ZoneDormancyMap dormancy = runtime.dormancyMap();
        Map<UUID, RetentionDemand> demands = runtime.classifier().snapshotDemands(nowMs);

        for (PlayerRef ref : Universe.get().getPlayers()) {
            if (!ref.isValid() || !sessions.isEnabled(ref.getUuid())) {
                continue;
            }
            if (!LeanCorePermissions.canViewHud(ref.getUuid(), config)) {
                sessions.setEnabled(ref.getUuid(), false);
                active.remove(ref.getUuid());
                continue;
            }
            LeanCoreStatusHud hud = active.get(ref.getUuid());
            if (hud == null) {
                continue;
            }
            RetentionDemand demand = demands.getOrDefault(ref.getUuid(), runtime.learningStore().demandFor(ref.getUuid()));
            ZoneState localZone = localZoneState(ref, dormancy);
            hud.setLines(formatLine1(sample), formatLine2(gov, demand, localZone));
            hud.pushUpdate();
        }
    }

    private static ZoneState localZoneState(PlayerRef ref, ZoneDormancyMap dormancy) {
        Transform t = ref.getTransform();
        if (t == null || t.getPosition() == null) {
            return ZoneState.WARM;
        }
        ZoneKey key = ZoneKey.fromBlockCoords(ref.getWorldUuid(), t.getPosition().x, t.getPosition().z);
        return dormancy.stateOf(key);
    }

    private static String formatLine1(MemorySnapshot sample) {
        return String.format(Locale.ROOT, "heap %d/%d MB (%.0f%%) %s",
                sample.heapUsedBytes() / (1024 * 1024),
                sample.heapMaxBytes() / (1024 * 1024),
                sample.heapUsedRatio() * 100.0D,
                sample.tier());
    }

    private static String formatLine2(GovernorStatus gov, RetentionDemand demand, ZoneState zone) {
        if (!gov.enabled()) {
            return String.format(Locale.ROOT, "zone %s | governor off", zone);
        }
        return String.format(Locale.ROOT, "zone %s | footprint %d/%d MB | retention %d MB",
                zone,
                gov.totalFootprintMb(),
                gov.budgetMb(),
                demand.retentionMb());
    }
}
