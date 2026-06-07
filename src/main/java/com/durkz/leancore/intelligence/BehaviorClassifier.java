package com.durkz.leancore.intelligence;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BehaviorClassifier {

    private final Map<UUID, PlayerMemoryProfile> profiles = new ConcurrentHashMap<>();

    public PlayerMemoryProfile profileFor(PlayerRef ref) {
        return profiles.computeIfAbsent(ref.getUuid(), PlayerMemoryProfile::new);
    }

    public void forget(UUID playerId) {
        profiles.remove(playerId);
    }

    public void onBlockBroken(PlayerRef ref) {
        profileFor(ref).blockBroken();
    }

    public void onBlockPlaced(PlayerRef ref) {
        profileFor(ref).blockPlaced();
    }

    public void onZoneDiscovered(PlayerRef ref) {
        profileFor(ref).zoneDiscovered();
    }

    public void samplePositions(Collection<PlayerRef> online) {
        for (PlayerRef ref : online) {
            if (!ref.isValid()) {
                continue;
            }
            Transform t = ref.getTransform();
            if (t == null || t.getPosition() == null) {
                continue;
            }
            profileFor(ref).samplePosition(t.getPosition().x, t.getPosition().z);
        }
    }

    public Map<UUID, PlayerBehavior> snapshotBehaviors() {
        long now = System.currentTimeMillis();
        Map<UUID, PlayerBehavior> out = new HashMap<>(profiles.size());
        for (Map.Entry<UUID, PlayerMemoryProfile> e : profiles.entrySet()) {
            out.put(e.getKey(), e.getValue().classify(now));
        }
        return out;
    }
}
