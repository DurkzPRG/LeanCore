package com.durkz.leancore.intelligence;

import com.durkz.leancore.probe.PlayerSpatialProbe;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerFeatureTracker {

    private static final long SPATIAL_SAMPLE_INTERVAL_MS = 5_000L;

    private final LearningStore learningStore;
    private final Map<UUID, PlayerFeatureState> states = new ConcurrentHashMap<>();
    private long lastSpatialSampleMs;

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
        boolean sampleSpatial = lastSpatialSampleMs <= 0L
                || nowMs - lastSpatialSampleMs >= SPATIAL_SAMPLE_INTERVAL_MS;
        if (sampleSpatial) {
            lastSpatialSampleMs = nowMs;
        }
        for (PlayerRef ref : online) {
            if (!ref.isValid()) {
                continue;
            }
            Transform t = ref.getTransform();
            if (t == null || t.getPosition() == null) {
                continue;
            }
            PlayerFeatureState state = stateFor(ref);
            state.samplePosition(t.getPosition().x, t.getPosition().z);
            if (sampleSpatial) {
                state.sampleSpatial(PlayerSpatialProbe.readChunks(ref).chunkPressure());
            }
        }
    }

    public Map<UUID, PlayerFeatureState> snapshot() {
        return Map.copyOf(states);
    }
}
