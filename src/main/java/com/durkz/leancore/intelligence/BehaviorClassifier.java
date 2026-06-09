package com.durkz.leancore.intelligence;

import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BehaviorClassifier {

    private final PlayerFeatureTracker features;
    private final LearningStore learningStore;

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
    }

    public void onBlockBroken(PlayerRef ref, BlockActionContext context) {
        features.onBlockBroken(ref, context);
        if (context.kind() != ActionKind.UNKNOWN) {
            learningStore.activityClassifier().train(context.kind());
        }
    }

    public void onBlockPlaced(PlayerRef ref, BlockActionContext context) {
        features.onBlockPlaced(ref, context);
        learningStore.activityClassifier().train(context.kind());
    }

    public void onZoneDiscovered(PlayerRef ref) {
        features.onZoneDiscovered(ref);
        learningStore.activityClassifier().train(ActionKind.EXPLORE);
    }

    public void onCraft(PlayerRef ref) {
        features.onCraft(ref);
        learningStore.activityClassifier().train(ActionKind.CRAFT);
    }

    public void onCombatHit(PlayerRef ref) {
        features.onCombatHit(ref);
        learningStore.activityClassifier().train(ActionKind.COMBAT);
    }

    public void samplePositions(Collection<PlayerRef> online, long nowMs) {
        features.samplePositions(online, nowMs);
    }

    public Map<UUID, PlayerBehavior> snapshotBehaviors(long nowMs) {
        ActivityClassifierModel model = learningStore.activityClassifier();
        Map<UUID, PlayerFeatureState> snap = features.snapshot();
        Map<UUID, PlayerBehavior> out = new HashMap<>(snap.size());
        for (Map.Entry<UUID, PlayerFeatureState> e : snap.entrySet()) {
            out.put(e.getKey(), BehaviorPosterior.topLabel(e.getValue(), model, nowMs));
        }
        return out;
    }

    public Map<UUID, RetentionDemand> snapshotDemands(long nowMs) {
        return learningStore.demandModel().estimate(features.snapshot(), snapshotBehaviors(nowMs), nowMs);
    }

    public void syncToStore(LearningStore store) {
        for (PlayerFeatureState state : features.snapshot().values()) {
            store.savePlayerFeatures(state);
        }
    }
}
