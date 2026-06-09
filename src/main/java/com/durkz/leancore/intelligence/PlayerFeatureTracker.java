package com.durkz.leancore.intelligence;

import com.durkz.leancore.probe.PlayerSpatialProbe;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerFeatureTracker implements ViewRadiusCache {

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

    public void onBlockBroken(PlayerRef ref, BlockActionContext context) {
        stateFor(ref).onBlockBroken(context);
    }

    public void onBlockPlaced(PlayerRef ref, BlockActionContext context) {
        stateFor(ref).onBlockPlaced(context);
    }

    public void onZoneDiscovered(PlayerRef ref) {
        stateFor(ref).onZoneDiscovered();
    }

    public void onCraft(PlayerRef ref) {
        stateFor(ref).onCraft();
    }

    public void onCombatHit(PlayerRef ref) {
        stateFor(ref).onCombatHit();
    }

    @Override
    public void noteViewRadius(UUID playerId, int serverRadius, int clientRadius) {
        PlayerFeatureState state = states.get(playerId);
        if (state != null) {
            state.noteViewRadius(serverRadius, clientRadius);
        }
    }

    public void samplePositions(Collection<PlayerRef> online, long nowMs) {
        for (PlayerFeatureState state : states.values()) {
            state.tick(nowMs);
        }
        boolean sampleSpatial = lastSpatialSampleMs <= 0L
                || nowMs - lastSpatialSampleMs >= PlayerFeatureState.SPATIAL_SAMPLE_INTERVAL_MS;
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
                PlayerSpatialProbe.SpatialSample sample = PlayerSpatialProbe.readChunks(ref);
                double pressure = sample.normalizedPressure(state.cachedViewRadius(), state.lastRawLoaded());
                state.noteRawLoaded(sample.loadedChunks());
                state.sampleSpatial(pressure);
            }
        }
    }

    public Map<UUID, PlayerFeatureState> snapshot() {
        return Map.copyOf(states);
    }
}
