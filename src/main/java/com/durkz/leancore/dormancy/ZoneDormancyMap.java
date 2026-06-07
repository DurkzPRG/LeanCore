package com.durkz.leancore.dormancy;

import com.durkz.leancore.config.LeanCoreConfig;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class ZoneDormancyMap {

    private final LeanCoreConfig config;
    private final Map<ZoneKey, ZoneState> zones = new ConcurrentHashMap<>();
    private final Map<ZoneKey, Long> lastHotAtMs = new ConcurrentHashMap<>();

    public ZoneDormancyMap(LeanCoreConfig config) {
        this.config = config;
    }

    public void refreshFromPlayers() {
        long now = System.currentTimeMillis();
        Map<ZoneKey, ZoneState> next = new HashMap<>();

        for (PlayerRef ref : Universe.get().getPlayers()) {
            if (!ref.isValid()) {
                continue;
            }
            Transform t = ref.getTransform();
            if (t == null || t.getPosition() == null) {
                continue;
            }
            ZoneKey key = ZoneKey.fromBlockCoords(ref.getWorldUuid(), t.getPosition().x, t.getPosition().z);
            next.put(key, ZoneState.HOT);
            lastHotAtMs.put(key, now);
        }

        for (Map.Entry<ZoneKey, Long> entry : lastHotAtMs.entrySet()) {
            if (next.containsKey(entry.getKey())) {
                continue;
            }
            long idleMin = (now - entry.getValue()) / 60_000L;
            ZoneState state;
            if (idleMin >= config.frozenAfterMinutes) {
                state = ZoneState.FROZEN;
            } else if (idleMin >= config.dormantAfterMinutes) {
                state = ZoneState.DORMANT;
            } else {
                state = ZoneState.WARM;
            }
            next.put(entry.getKey(), state);
        }

        zones.clear();
        zones.putAll(next);
    }

    public int countByState(ZoneState state) {
        int n = 0;
        for (ZoneState value : zones.values()) {
            if (value == state) {
                n++;
            }
        }
        return n;
    }

    public List<String> topZones(int limit) {
        return zones.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> -e.getValue().ordinal()))
                .limit(limit)
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.toList());
    }

    public Collection<ZoneKey> hotZones() {
        return zones.entrySet().stream()
                .filter(e -> e.getValue() == ZoneState.HOT)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }
}
