package com.durkz.leancore.intelligence;

import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BehaviorClassifier {

    private final PlayerFeatureTracker features;
    private final RetentionDemandEstimator estimator = new RetentionDemandEstimator();
    private final Map<UUID, PlayerMemoryProfile> debugProfiles = new ConcurrentHashMap<>();

    public BehaviorClassifier(LearningStore learningStore) {
        this.features = new PlayerFeatureTracker(learningStore);
    }

    public PlayerFeatureTracker features() {
        return features;
    }

    public void profileFor(PlayerRef ref) {
        features.stateFor(ref);
        debugProfiles.computeIfAbsent(ref.getUuid(), PlayerMemoryProfile::new);
    }

    public void forget(UUID playerId) {
        features.forget(playerId);
        debugProfiles.remove(playerId);
    }

    public void onBlockBroken(PlayerRef ref) {
        features.onBlockBroken(ref);
        debugProfile(ref).blockBroken();
    }

    public void onBlockPlaced(PlayerRef ref) {
        features.onBlockPlaced(ref);
        debugProfile(ref).blockPlaced();
    }

    public void onZoneDiscovered(PlayerRef ref) {
        features.onZoneDiscovered(ref);
        debugProfile(ref).zoneDiscovered();
    }

    public void samplePositions(Collection<PlayerRef> online, long nowMs) {
        features.samplePositions(online, nowMs);
        for (PlayerRef ref : online) {
            if (!ref.isValid()) {
                continue;
            }
            var t = ref.getTransform();
            if (t == null || t.getPosition() == null) {
                continue;
            }
            debugProfile(ref).samplePosition(t.getPosition().x, t.getPosition().z);
        }
    }

    public Map<UUID, PlayerBehavior> snapshotBehaviors(long nowMs) {
        Map<UUID, PlayerBehavior> out = new HashMap<>(debugProfiles.size());
        for (Map.Entry<UUID, PlayerMemoryProfile> e : debugProfiles.entrySet()) {
            out.put(e.getKey(), e.getValue().classify(nowMs));
        }
        return out;
    }

    public Map<UUID, RetentionDemand> snapshotDemands(long nowMs) {
        return estimator.estimate(features.snapshot(), snapshotBehaviors(nowMs), nowMs);
    }

    public void syncToStore(LearningStore store) {
        for (PlayerFeatureState state : features.snapshot().values()) {
            store.savePlayerFeatures(state);
        }
    }

    private PlayerMemoryProfile debugProfile(PlayerRef ref) {
        return debugProfiles.computeIfAbsent(ref.getUuid(), PlayerMemoryProfile::new);
    }
}
