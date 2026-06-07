package com.durkz.leancore.intelligence;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerFeatureTracker {

    private final LearningStore learningStore;
    private final Map<UUID, PlayerFeatureState> states = new ConcurrentHashMap<>();

    public PlayerFeatureTracker(LearningStore learningStore) {
        this.learningStore = learningStore;
    }

    public PlayerFeatureState stateFor(PlayerRef ref) {
        return states.computeIfAbsent(ref.getUuid(), id -> {
            PlayerFeatureState state = new PlayerFeatureState(id);
            learningStore.hydratePlayer(state);
            return state;
        });
    }

    public void forget(UUID playerId) {
        PlayerFeatureState state = states.remove(playerId);
        if (state != null) {
            learningStore.savePlayerFeatures(state);
        }
    }

    public void onBlockBroken(PlayerRef ref) {
        stateFor(ref).onBlockBroken();
    }

    public void onBlockPlaced(PlayerRef ref) {
        stateFor(ref).onBlockPlaced();
    }

    public void onZoneDiscovered(PlayerRef ref) {
        stateFor(ref).onZoneDiscovered();
    }

    public void samplePositions(Collection<PlayerRef> online, long nowMs) {
        for (PlayerFeatureState state : states.values()) {
            state.tick(nowMs);
        }
        for (PlayerRef ref : online) {
            if (!ref.isValid()) {
                continue;
            }
            Transform t = ref.getTransform();
            if (t == null || t.getPosition() == null) {
                continue;
            }
            stateFor(ref).samplePosition(t.getPosition().x, t.getPosition().z);
        }
    }

    public Map<UUID, PlayerFeatureState> snapshot() {
        return Map.copyOf(states);
    }
}
