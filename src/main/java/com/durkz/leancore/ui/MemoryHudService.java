package com.durkz.leancore.ui;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.dormancy.ZoneDormancyMap;
import com.durkz.leancore.dormancy.ZoneKey;
import com.durkz.leancore.dormancy.ZoneState;
import com.durkz.leancore.intelligence.PlayerFeatureState;
import com.durkz.leancore.intelligence.RetentionDemand;
import com.durkz.leancore.memory.GovernorStatus;
import com.durkz.leancore.memory.MemorySnapshot;
import com.durkz.leancore.permissions.LeanCorePermissions;
import com.durkz.leancore.runtime.MemoryRuntime;
import com.durkz.leancore.runtime.WorldDispatch;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
        Map<UUID, PlayerFeatureState> features = runtime.classifier().features().snapshot();

        Map<UUID, List<PlayerRef>> byWorld = new HashMap<>();
        for (PlayerRef ref : Universe.get().getPlayers()) {
            if (!ref.isValid() || !sessions.isEnabled(ref.getUuid())) {
                continue;
            }
            if (!LeanCorePermissions.canViewHud(ref.getUuid(), config)) {
                sessions.setEnabled(ref.getUuid(), false);
                active.remove(ref.getUuid());
                continue;
            }
            if (active.get(ref.getUuid()) == null) {
                continue;
            }
            UUID worldUuid = ref.getWorldUuid();
            if (worldUuid == null) {
                continue;
            }
            World world = Universe.get().getWorld(worldUuid);
            if (world == null || !world.isAlive()) {
                continue;
            }
            byWorld.computeIfAbsent(worldUuid, ignored -> new ArrayList<>()).add(ref);
        }

        for (Map.Entry<UUID, List<PlayerRef>> entry : byWorld.entrySet()) {
            World world = Universe.get().getWorld(entry.getKey());
            if (world == null || !world.isAlive()) {
                continue;
            }
            List<PlayerRef> batch = List.copyOf(entry.getValue());
            WorldDispatch.run(world, () -> {
                for (PlayerRef ref : batch) {
                    renderHud(ref, sample, gov, dormancy, demands, features, runtime);
                }
            });
        }
    }

    private void renderHud(
            PlayerRef ref,
            MemorySnapshot sample,
            GovernorStatus gov,
            ZoneDormancyMap dormancy,
            Map<UUID, RetentionDemand> demands,
            Map<UUID, PlayerFeatureState> featureSnapshot,
            MemoryRuntime runtime
    ) {
        if (ref == null || !ref.isValid()) {
            return;
        }
        LeanCoreStatusHud hud = active.get(ref.getUuid());
        if (hud == null) {
            return;
        }
        RetentionDemand demand = demands.getOrDefault(ref.getUuid(), runtime.learningStore().demandFor(ref.getUuid()));
        ZoneState localZone = localZoneState(ref, dormancy);
        PlayerFeatureState features = featureSnapshot.get(ref.getUuid());
        String line2 = formatLine2(gov, demand, localZone) + formatReuseSuffix(ref, dormancy);
        hud.setLines(formatLine1(sample), line2, formatMotionLine(features));
        hud.pushUpdate();
    }

    private String formatMotionLine(PlayerFeatureState features) {
        if (!config.motionModelEnabled || features == null) {
            return "";
        }
        long horizonMs = Math.max(0, config.motionPredictionHorizonSeconds) * 1000L;
        double[] predicted = features.predictedXZ(horizonMs);
        String predStr = predicted == null
                ? "n/a"
                : String.format(Locale.ROOT, "%.0f,%.0f", predicted[0], predicted[1]);
        String view;
        if (!config.motionViewRadiusBoostEnabled) {
            view = "vr off";
        } else {
            int applied = features.lastAppliedViewRadius();
            if (applied <= 0) {
                applied = features.cachedViewRadius();
            }
            int boost = features.lastMotionBoostBlocks();
            view = boost > 0
                    ? String.format(Locale.ROOT, "vr %d (+%d)", applied, boost)
                    : String.format(Locale.ROOT, "vr %d (idle)", applied);
        }
        return String.format(Locale.ROOT, "mv %.1fb/s c%.2f pred %s | %s",
                features.speedBlocksPerSec(),
                features.motionConfidence(),
                predStr,
                view);
    }

    private String formatReuseSuffix(PlayerRef ref, ZoneDormancyMap dormancy) {
        if (!config.zoneReuseModelEnabled) {
            return "";
        }
        Transform t = ref.getTransform();
        if (t == null || t.getPosition() == null) {
            return "";
        }
        ZoneKey key = ZoneKey.fromBlockCoords(ref.getWorldUuid(), t.getPosition().x, t.getPosition().z);
        return String.format(Locale.ROOT, " | reuse %.2f x%.2f",
                dormancy.revisitScore(key), dormancy.thresholdScale(key));
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
        if (sample == null) {
            return "heap n/a (sampling)";
        }
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
