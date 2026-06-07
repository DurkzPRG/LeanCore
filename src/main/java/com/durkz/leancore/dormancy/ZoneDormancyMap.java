package com.durkz.leancore.dormancy;

import com.durkz.leancore.config.LeanCoreConfig;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import java.util.ArrayList;
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

    public int demoteFarthestDormant(int maxZones) {
        if (maxZones <= 0) {
            return 0;
        }

        List<double[]> playerXZ = playerPositions();
        if (playerXZ.isEmpty()) {
            return 0;
        }

        List<Map.Entry<ZoneKey, Double>> dormant = zones.entrySet().stream()
                .filter(e -> e.getValue() == ZoneState.DORMANT)
                .map(e -> Map.entry(e.getKey(), minDistanceToPlayers(e.getKey(), playerXZ)))
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .limit(maxZones)
                .collect(Collectors.toList());

        int demoted = 0;
        for (Map.Entry<ZoneKey, Double> entry : dormant) {
            zones.put(entry.getKey(), ZoneState.FROZEN);
            demoted++;
        }
        return demoted;
    }

    private List<double[]> playerPositions() {
        List<double[]> out = new ArrayList<>();
        for (PlayerRef ref : Universe.get().getPlayers()) {
            if (!ref.isValid()) {
                continue;
            }
            Transform t = ref.getTransform();
            if (t == null || t.getPosition() == null) {
                continue;
            }
            out.add(new double[]{t.getPosition().x, t.getPosition().z});
        }
        return out;
    }

    private static double minDistanceToPlayers(ZoneKey key, List<double[]> playerXZ) {
        double cx = zoneCenterBlock(key.regionX());
        double cz = zoneCenterBlock(key.regionZ());
        double min = Double.MAX_VALUE;
        for (double[] p : playerXZ) {
            min = Math.min(min, Math.hypot(cx - p[0], cz - p[1]));
        }
        return min;
    }

    private static double zoneCenterBlock(int region) {
        return region * ZoneKey.regionChunks() * 16.0D + (ZoneKey.regionChunks() * 16.0D) / 2.0D;
    }
}
