package com.durkz.leancore.intelligence;

import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BehaviorClassifier {

    private static final long TRAIN_MIN_INTERVAL_MS = 80L;

    private final PlayerFeatureTracker features;
    private final LearningStore learningStore;
    private final Map<UUID, Long> lastTrainMs = new ConcurrentHashMap<>();

    public BehaviorClassifier(LearningStore learningStore) {
        this.learningStore = learningStore;
        this.features = new PlayerFeatureTracker(learningStore);
    }

    public PlayerFeatureTracker features() {
        return features;
    }

    public void profileFor(PlayerRef ref) {
        features.stateFor(ref);
    }

    public void forget(UUID playerId) {
        features.forget(playerId);
        lastTrainMs.remove(playerId);
    }

    public void onBlockBroken(PlayerRef ref, BlockActionContext context) {
        features.onBlockBroken(ref, context);
        maybeTrain(ref.getUuid(), context != null ? context.kind() : ActionKind.UNKNOWN);
    }

    public void onBlockPlaced(PlayerRef ref, BlockActionContext context) {
        features.onBlockPlaced(ref, context);
        maybeTrain(ref.getUuid(), context != null ? context.kind() : ActionKind.BUILD);
    }

    public void onZoneDiscovered(PlayerRef ref) {
        features.onZoneDiscovered(ref);
        maybeTrain(ref.getUuid(), ActionKind.EXPLORE);
    }

    public void onCraft(PlayerRef ref) {
        features.onCraft(ref);
        maybeTrain(ref.getUuid(), ActionKind.CRAFT);
    }

    public void onCombatHit(PlayerRef ref) {
        features.onCombatHit(ref);
        maybeTrain(ref.getUuid(), ActionKind.COMBAT);
    }

    private void maybeTrain(UUID playerId, ActionKind kind) {
        if (playerId == null || kind == null || kind == ActionKind.UNKNOWN) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        Long last = lastTrainMs.get(playerId);
        if (last != null && nowMs - last < TRAIN_MIN_INTERVAL_MS) {
            return;
        }
        lastTrainMs.put(playerId, nowMs);
        learningStore.activityClassifier().train(kind);
        learningStore.markDirty();
    }

    public void samplePositions(Collection<PlayerRef> online, long nowMs) {
        features.samplePositions(online, nowMs, true);
    }

    public void samplePositionsLite(Collection<PlayerRef> online, long nowMs) {
        features.samplePositions(online, nowMs, false);
    }

    public Map<UUID, PlayerBehavior> snapshotBehaviors(long nowMs) {
        return snapshotBehaviors(features.snapshot(), nowMs);
    }

    private Map<UUID, PlayerBehavior> snapshotBehaviors(Map<UUID, PlayerFeatureState> snapshot, long nowMs) {
        ActivityClassifierModel model = learningStore.activityClassifier();
        Map<UUID, PlayerBehavior> out = new HashMap<>(snapshot.size());
        for (Map.Entry<UUID, PlayerFeatureState> e : snapshot.entrySet()) {
            out.put(e.getKey(), BehaviorPosterior.topLabel(e.getValue(), model, nowMs));
        }
        return out;
    }

    public Map<UUID, RetentionDemand> snapshotDemands(long nowMs) {
        Map<UUID, PlayerFeatureState> snapshot = features.snapshot();
        return learningStore.demandModel().estimate(snapshot, snapshotBehaviors(snapshot, nowMs), nowMs);
    }

    public void syncToStore(LearningStore store) {
        for (PlayerFeatureState state : features.snapshot().values()) {
            store.savePlayerFeatures(state);
        }
    }
}
