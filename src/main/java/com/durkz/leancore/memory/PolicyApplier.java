package com.durkz.leancore.memory;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.diagnostics.DiagnosticLog;
import com.durkz.leancore.runtime.RuntimeGuard;
import com.durkz.leancore.runtime.RuntimeProfile;
import com.durkz.leancore.runtime.WorldDispatch;
import com.durkz.leancore.intelligence.FalseCutTracker;
import com.durkz.leancore.intelligence.HeuristicDemandModel;
import com.durkz.leancore.intelligence.HoldoutSet;
import com.durkz.leancore.intelligence.LoadingPressureGate;
import com.durkz.leancore.intelligence.PlayerBehavior;
import com.durkz.leancore.intelligence.RetentionDemand;
import com.durkz.leancore.intelligence.ViewRadiusCache;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PolicyApplier {

    private final LeanCoreConfig config;
    private final FalseCutTracker falseCutTracker;
    private final ViewRadiusCache viewRadiusCache;

    private String lastAppliedPolicyKey;
    private long lastApplyMs;
    private int lastLoggedHotRadius = Integer.MIN_VALUE;
    private double lastLoggedHotRadiusScale = Double.NaN;
    private String lastLoggedHotRadiusPolicy;

    // Connection-aware engine chunk-rate baseline per player (perSecond, perTick), captured before we
    // first touch it. Targets are a percentage of this, so the player's connection class is honored.
    private final Map<UUID, int[]> chunkBaselineByPlayer = new ConcurrentHashMap<>();
    private final ThreadLocal<PlayerBatchScratch> playerBatchScratch = ThreadLocal.withInitial(PlayerBatchScratch::new);

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
            // Only credit the batch (and advance the apply throttle below) when the world task
            // actually ran. A timed-out dispatch leaves view radii untouched, so claiming success
            // would wedge lastAppliedPolicyKey and suppress the retry next tick.
            boolean done = WorldDispatch.run(world, () -> {
                if (!RuntimeGuard.active()) {
                    return;
                }
                for (PlayerApply item : batch) {
                    applyOne(item.playerRef(), policy, item.demand(), profile);
                }
            });
            if (done) {
                scheduled += batch.size();
            }
        }

        if (scheduled > 0) {
            String previousKey = lastAppliedPolicyKey;
            lastApplyMs = nowMs;
            lastAppliedPolicyKey = applyKey;
            if (!applyKey.equals(previousKey)) {
                DiagnosticLog.info(String.format(Locale.ROOT,
                        "policy: %s viewScale=%.0f%% applied to %d player(s)%s",
                        policy.key(), policy.viewScale() * 100.0D, scheduled,
                        previousKey == null ? "" : " (was " + previousKey + ")"));
            }
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

        // Streaming grace: while this player is actively streaming chunks, don't shrink their view
        // radius (that would tell the client to drop chunks it is loading and re-request them).
        // CRITICAL always cuts. Anchor at the held radius so the live motion path won't pull it down.
        if (config.loadingPressureSignalEnabled && target < current
                && LoadingPressureGate.holdsRadiusReduction(
                        config, policy.tier(), loadingBacklog(playerRef), target, current)) {
            if (viewRadiusCache != null) {
                viewRadiusCache.noteBaseViewRadius(playerId, current);
            }
            DiagnosticLog.infoOnChange("streaming-radius-grace",
                    "view/hot radius cut held during active chunk streaming");
            return;
        }

        // Anchor radius (without motion boost). The live motion sampler boosts on top of this.
        if (viewRadiusCache != null) {
            viewRadiusCache.noteBaseViewRadius(playerId, target);
        }

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

    /**
     * Live cinematic view-radius boost. Runs on the world thread at the fast motion cadence,
     * independent of the governor policy throttle, so the radius tracks acceleration in near
     * real time. Upward only: it boosts above the governor anchor and reverts to it when the
     * player slows down. Must be called from the world thread for the given players.
     */
    public void applyMotionLive(Collection<PlayerRef> online, RuntimeProfile profile) {
        if (online == null || !RuntimeGuard.active() || viewRadiusCache == null) {
            return;
        }
        if (!config.motionModelEnabled || !config.motionViewRadiusBoostEnabled) {
            return;
        }
        Map<UUID, List<PlayerRef>> byWorld = new HashMap<>();
        for (PlayerRef playerRef : online) {
            if (playerRef == null || !playerRef.isValid()) {
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
            byWorld.computeIfAbsent(worldUuid, ignored -> new ArrayList<>()).add(playerRef);
        }

        // Live boost runs every motion tick; apply even 1-block changes (the periodic policy pass
        // keeps config.minViewRadiusDelta).
        int minDelta = 1;
        for (Map.Entry<UUID, List<PlayerRef>> entry : byWorld.entrySet()) {
            World world = Universe.get().getWorld(entry.getKey());
            if (world == null || !world.isAlive() || !RuntimeGuard.active()) {
                continue;
            }
            List<PlayerRef> batch = List.copyOf(entry.getValue());
            WorldDispatch.run(world, () -> {
                if (!RuntimeGuard.active()) {
                    return;
                }
                for (PlayerRef playerRef : batch) {
                    applyMotionLiveOne(playerRef, minDelta);
                }
            });
        }
    }

    private void applyMotionLiveOne(PlayerRef playerRef, int minDelta) {
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }
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
        int current = player.getClientViewRadius();
        int base = viewRadiusCache.baseViewRadius(playerId);
        if (base <= 0) {
            base = current;
            viewRadiusCache.noteBaseViewRadius(playerId, base);
        }
        // Boost off a clamped base so it can exceed a demand/tier-shrunk anchor, up to the max.
        base = clamp(base, config.minClientViewRadius, config.maxClientViewRadius);
        double motionScale = viewRadiusCache.motionViewScale(
                playerId, config.motionMinSpeedBlocksPerSecond, config.motionViewRadiusMaxBoost);
        int boosted = applyMotionBoost(base, motionScale, config.maxClientViewRadius);
        viewRadiusCache.noteMotionApplied(playerId, boosted, boosted - base);
        if (Math.abs(boosted - current) >= minDelta) {
            player.setClientViewRadius(boosted);
        }
    }

    /**
     * Hot/simulation radius actuator (v1.7.0 Frente C). Drives {@code setMaxHotLoadedRadius}
     * (ticking radius) from the active policy, cutting simulation cost without the view-radius
     * pop-in. Runs on each player's world thread; shrinks are skipped for holdout players so the
     * cohort comparison stays clean. No-op unless {@code hotRadiusGovernanceEnabled}.
     */
    public void applyHotRadius(GovernorPolicy policy, Collection<PlayerRef> online) {
        if (policy == null || online == null || !RuntimeGuard.active()
                || !config.hotRadiusGovernanceEnabled) {
            return;
        }
        int target = HotRadiusGovernance.targetHotRadius(config, policy.viewScale());
        boolean criticalCut = policy.tier() == MemoryTier.CRITICAL;
        logHotRadius(target, policy);
        PlayerBatchScratch scratch = playerBatchScratch.get();
        scratch.clear();
        for (PlayerRef playerRef : online) {
            if (playerRef == null || !playerRef.isValid()) {
                continue;
            }
            UUID worldUuid = playerRef.getWorldUuid();
            if (worldUuid == null) {
                continue;
            }
            scratch.playersFor(worldUuid).add(playerRef);
        }
        for (MutablePlayerBatch grouped : scratch.groupedWorlds) {
            World world = Universe.get().getWorld(grouped.worldUuid);
            if (world == null || !world.isAlive() || !RuntimeGuard.active()) {
                continue;
            }
            // A timed-out task may execute after this scratch buffer is reused, so it must retain
            // an immutable player list for its own world pass.
            List<PlayerRef> batch = List.copyOf(grouped.players);
            WorldDispatch.run(world, () -> {
                if (!RuntimeGuard.active()) {
                    return;
                }
                for (PlayerRef playerRef : batch) {
                    applyHotRadiusOne(playerRef, target, criticalCut);
                }
            });
        }
    }

    private void logHotRadius(int target, GovernorPolicy policy) {
        double scale = policy.viewScale();
        String policyKey = policy.key();
        if (target == lastLoggedHotRadius
                && Double.compare(scale, lastLoggedHotRadiusScale) == 0
                && Objects.equals(policyKey, lastLoggedHotRadiusPolicy)) {
            return;
        }
        lastLoggedHotRadius = target;
        lastLoggedHotRadiusScale = scale;
        lastLoggedHotRadiusPolicy = policyKey;
        DiagnosticLog.info(String.format(Locale.ROOT,
                "hot radius target=%d (max=%d min=%d viewScale=%.2f policy=%s)",
                target, config.maxHotLoadedChunksRadius, config.minHotLoadedChunksRadius, scale, policyKey));
    }

    private void applyHotRadiusOne(PlayerRef playerRef, int target, boolean criticalCut) {
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }
        ChunkTracker tracker = playerRef.getChunkTracker();
        if (tracker == null) {
            return;
        }
        int current = tracker.getMaxHotLoadedRadius();
        if (HoldoutSet.isHoldout(playerRef.getUuid()) && target < current) {
            return;
        }
        // Streaming grace: hold the hot-radius cut while this player streams (unless CRITICAL).
        if (config.loadingPressureSignalEnabled && !criticalCut && target < current
                && LoadingPressureGate.holdsUnload(config, Math.max(0, tracker.getLoadingSectionsCount()))) {
            DiagnosticLog.infoOnChange("streaming-radius-grace",
                    "view/hot radius cut held during active chunk streaming");
            return;
        }
        if (target == current) {
            return;
        }
        tracker.setMaxHotLoadedRadius(target);
    }

    private static int loadingBacklog(PlayerRef playerRef) {
        ChunkTracker tracker = playerRef.getChunkTracker();
        return tracker == null ? 0 : Math.max(0, tracker.getLoadingSectionsCount());
    }

    /**
     * Adaptive chunk-throughput actuator. Scales each player's chunk send-rate
     * ({@code setMaxSectionsPerSecond} / {@code setMaxSectionsPerTick}) by memory tier, as a percentage
     * of their connection-aware engine baseline (captured once, before we change it). Runs on each
     * player's world thread. No-op unless {@code chunkThroughputGovernanceEnabled}. Reductions are
     * skipped for holdout players so the cohort comparison stays clean.
     */
    public void applyChunkThroughput(MemoryTier tier, Collection<PlayerRef> online) {
        if (tier == null || online == null || !RuntimeGuard.active()
                || !config.chunkThroughputGovernanceEnabled) {
            return;
        }
        Set<UUID> onlineIds = new HashSet<>();
        Map<UUID, List<PlayerRef>> byWorld = new HashMap<>();
        for (PlayerRef playerRef : online) {
            if (playerRef == null || !playerRef.isValid()) {
                continue;
            }
            onlineIds.add(playerRef.getUuid());
            UUID worldUuid = playerRef.getWorldUuid();
            if (worldUuid == null) {
                continue;
            }
            World world = Universe.get().getWorld(worldUuid);
            if (world == null || !world.isAlive()) {
                continue;
            }
            byWorld.computeIfAbsent(worldUuid, ignored -> new ArrayList<>()).add(playerRef);
        }
        // Drop baselines for players who left, so the map cannot grow without bound.
        chunkBaselineByPlayer.keySet().retainAll(onlineIds);
        DiagnosticLog.infoOnChange("chunk-throughput", String.format(Locale.ROOT,
                "chunk throughput tier=%s pct=%d%% (comfort=%d tight=%d critical=%d)",
                tier, ChunkThroughputModel.percentForTier(config, tier),
                config.chunkThroughputComfortPct, config.chunkThroughputTightPct,
                config.chunkThroughputCriticalPct));
        for (Map.Entry<UUID, List<PlayerRef>> entry : byWorld.entrySet()) {
            World world = Universe.get().getWorld(entry.getKey());
            if (world == null || !world.isAlive() || !RuntimeGuard.active()) {
                continue;
            }
            List<PlayerRef> batch = List.copyOf(entry.getValue());
            WorldDispatch.run(world, () -> {
                if (!RuntimeGuard.active()) {
                    return;
                }
                for (PlayerRef playerRef : batch) {
                    applyChunkThroughputOne(playerRef, tier);
                }
            });
        }
    }

    private void applyChunkThroughputOne(PlayerRef playerRef, MemoryTier tier) {
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }
        ChunkTracker tracker = playerRef.getChunkTracker();
        if (tracker == null) {
            return;
        }
        UUID playerId = playerRef.getUuid();
        int[] baseline = chunkBaselineByPlayer.computeIfAbsent(playerId,
                ignored -> new int[]{tracker.getMaxSectionsPerSecond(), tracker.getMaxSectionsPerTick()});
        int backlog = Math.max(0, tracker.getLoadingSectionsCount());
        int targetSec = ChunkThroughputModel.targetPerSecond(config, tier, baseline[0], backlog);
        int targetTick = ChunkThroughputModel.targetPerTick(config, tier, baseline[1], backlog);
        boolean holdout = HoldoutSet.isHoldout(playerId);

        int currentSec = tracker.getMaxSectionsPerSecond();
        if (targetSec != currentSec && !(holdout && targetSec < currentSec)) {
            tracker.setMaxSectionsPerSecond(targetSec);
        }
        int currentTick = tracker.getMaxSectionsPerTick();
        if (targetTick != currentTick && !(holdout && targetTick < currentTick)) {
            tracker.setMaxSectionsPerTick(targetTick);
        }
    }

    /** Motion boost is upward only and capped at maxClientViewRadius. */
    static int applyMotionBoost(int target, double motionScale, int maxClientViewRadius) {
        if (motionScale <= 1.0D) {
            return target;
        }
        int boosted = (int) Math.round(target * motionScale);
        return Math.min(Math.max(target, boosted), maxClientViewRadius);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record PlayerApply(PlayerRef playerRef, RetentionDemand demand) {
    }

    /** The governor scheduler is single-threaded, so per-world grouping can keep its capacity. */
    private static final class PlayerBatchScratch {

        private final ArrayList<MutablePlayerBatch> groupedWorlds = new ArrayList<>();

        private void clear() {
            for (MutablePlayerBatch grouped : groupedWorlds) {
                grouped.players.clear();
            }
        }

        private ArrayList<PlayerRef> playersFor(UUID worldUuid) {
            for (MutablePlayerBatch grouped : groupedWorlds) {
                if (grouped.worldUuid.equals(worldUuid)) {
                    return grouped.players;
                }
            }
            MutablePlayerBatch grouped = new MutablePlayerBatch(worldUuid);
            groupedWorlds.add(grouped);
            return grouped.players;
        }
    }

    private static final class MutablePlayerBatch {

        private final UUID worldUuid;
        private final ArrayList<PlayerRef> players = new ArrayList<>();

        private MutablePlayerBatch(UUID worldUuid) {
            this.worldUuid = worldUuid;
        }
    }
}
