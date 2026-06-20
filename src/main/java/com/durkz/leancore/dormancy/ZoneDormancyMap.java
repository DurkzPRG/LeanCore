package com.durkz.leancore.dormancy;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.diagnostics.DiagnosticLog;
import com.durkz.leancore.memory.MemoryTier;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class ZoneDormancyMap {

    private final LeanCoreConfig config;
    private final Map<ZoneKey, ZoneState> zones = new ConcurrentHashMap<>();
    private final Map<ZoneKey, Long> lastHotAtMs = new ConcurrentHashMap<>();
    private final Set<ZoneKey> pinned = ConcurrentHashMap.newKeySet();
    private volatile PredictedPositionSource positionSource;
    private volatile ZoneReuseModel reuseModel;

    public ZoneDormancyMap(LeanCoreConfig config) {
        this.config = config;
    }

    public void setPredictedPositionSource(PredictedPositionSource source) {
        this.positionSource = source;
    }

    public void setZoneReuseModel(ZoneReuseModel model) {
        this.reuseModel = model;
    }

    public void refreshFromPlayers() {
        refreshFromPlayerZones(collectHotPlayerZones(), System.currentTimeMillis());
    }

    void refreshFromPlayerZones(Collection<ZoneKey> hotZones, long now) {
        Set<ZoneKey> hotNow = new HashSet<>(hotZones);
        ZoneReuseModel reuse = this.reuseModel;
        boolean reuseEnabled = config.zoneReuseModelEnabled && reuse != null;
        List<String> changes = new ArrayList<>();

        for (ZoneKey key : hotNow) {
            ZoneState prev = zones.get(key);
            if (reuseEnabled && prev != ZoneState.HOT) {
                reuse.noteHot(key, now);
            }
            zones.put(key, ZoneState.HOT);
            lastHotAtMs.put(key, now);
            if (prev != ZoneState.HOT) {
                String why = reuseEnabled && reuse.visitCount(key) > 1 ? "revisit" : "player entered";
                changes.add((prev == null ? "NEW" : prev.name()) + "->HOT " + key + " " + why);
            }
        }

        for (Map.Entry<ZoneKey, Long> entry : lastHotAtMs.entrySet()) {
            ZoneKey key = entry.getKey();
            if (hotNow.contains(key) || pinned.contains(key)) {
                continue;
            }
            long idleMin = (now - entry.getValue()) / 60_000L;
            double scale = reuseEnabled
                    ? reuse.thresholdScale(key, config.zoneReuseThresholdScaleMin, config.zoneReuseThresholdScaleMax)
                    : 1.0D;
            long dormantMin = Math.round(config.dormantAfterMinutes * scale);
            long frozenMin = Math.round(config.frozenAfterMinutes * scale);
            ZoneState prev = zones.get(key);
            ZoneState next;
            if (idleMin >= frozenMin) {
                next = ZoneState.FROZEN;
            } else if (idleMin >= dormantMin) {
                next = ZoneState.DORMANT;
            } else {
                next = ZoneState.WARM;
            }
            zones.put(key, next);
            if (next != prev) {
                changes.add(transitionReason(key, prev, next, idleMin, scale,
                        dormantMin, frozenMin, reuseEnabled, reuse, now));
            }
        }

        for (ZoneKey key : pinned) {
            zones.put(key, ZoneState.HOT);
            lastHotAtMs.put(key, now);
        }

        pruneStaleZones(now);
        logTransitions(changes);
    }

    private String transitionReason(ZoneKey key, ZoneState prev, ZoneState next, long idleMin,
                                    double scale, long dormantMin, long frozenMin,
                                    boolean reuseEnabled, ZoneReuseModel reuse, long now) {
        StringBuilder sb = new StringBuilder();
        sb.append(prev == null ? "NEW" : prev.name()).append("->").append(next.name())
                .append(' ').append(key).append(" idle=").append(idleMin).append('m');
        if (next == ZoneState.DORMANT) {
            sb.append(" thr=").append(config.dormantAfterMinutes).append("m*")
                    .append(String.format(Locale.ROOT, "%.2f", scale)).append('=').append(dormantMin).append('m');
        } else if (next == ZoneState.FROZEN) {
            sb.append(" thr=").append(config.frozenAfterMinutes).append("m*")
                    .append(String.format(Locale.ROOT, "%.2f", scale)).append('=').append(frozenMin).append('m');
        }
        if (reuseEnabled && reuse != null && next != ZoneState.WARM) {
            sb.append(" reuse=").append(String.format(Locale.ROOT, "%.2f", reuse.revisitScore(key, now)));
        }
        return sb.toString();
    }

    private void logTransitions(List<String> changes) {
        if (changes == null || changes.isEmpty()) {
            return;
        }
        int cap = 8;
        StringBuilder sb = new StringBuilder("dormancy: ").append(changes.size()).append(" changes");
        for (int i = 0; i < changes.size() && i < cap; i++) {
            sb.append(" | ").append(changes.get(i));
        }
        if (changes.size() > cap) {
            sb.append(" (+").append(changes.size() - cap).append(" more)");
        }
        DiagnosticLog.info(sb.toString());
    }

    ZoneState idleStateForMinutes(long idleMin) {
        if (idleMin >= config.frozenAfterMinutes) {
            return ZoneState.FROZEN;
        }
        if (idleMin >= config.dormantAfterMinutes) {
            return ZoneState.DORMANT;
        }
        return ZoneState.WARM;
    }

    private void pruneStaleZones(long now) {
        long pruneAfterMs = Math.max(1, config.frozenAfterMinutes) * 2L * 60_000L;
        long cutoff = now - pruneAfterMs;
        lastHotAtMs.entrySet().removeIf(entry -> {
            ZoneKey key = entry.getKey();
            if (pinned.contains(key)) {
                return false;
            }
            if (entry.getValue() >= cutoff) {
                return false;
            }
            zones.remove(key);
            return true;
        });
    }

    private static Collection<ZoneKey> collectHotPlayerZones() {
        List<ZoneKey> hot = new ArrayList<>();
        for (PlayerRef ref : Universe.get().getPlayers()) {
            if (!ref.isValid()) {
                continue;
            }
            Transform t = ref.getTransform();
            if (t == null || t.getPosition() == null) {
                continue;
            }
            hot.add(ZoneKey.fromBlockCoords(ref.getWorldUuid(), t.getPosition().x, t.getPosition().z));
        }
        return hot;
    }

    public ZoneState stateOf(ZoneKey key) {
        return zones.getOrDefault(key, ZoneState.WARM);
    }

    public long idleMinutes(ZoneKey key) {
        Long lastHot = lastHotAtMs.get(key);
        if (lastHot == null) {
            return 0L;
        }
        return Math.max(0L, (System.currentTimeMillis() - lastHot) / 60_000L);
    }

    public boolean isPinned(ZoneKey key) {
        return key != null && pinned.contains(key);
    }

    public boolean pinZone(ZoneKey key) {
        if (key == null) {
            return false;
        }
        if (pinned.size() >= Math.max(1, config.zonePinMaxCount) && !pinned.contains(key)) {
            return false;
        }
        pinned.add(key);
        zones.put(key, ZoneState.HOT);
        lastHotAtMs.put(key, System.currentTimeMillis());
        return true;
    }

    public boolean unpinZone(ZoneKey key) {
        return key != null && pinned.remove(key);
    }

    public List<ZoneKey> pinnedZones() {
        return pinned.stream().sorted(ZoneDormancyMap::compareZoneKeys).collect(Collectors.toList());
    }

    public List<ZoneHeatmapEntry> heatmapEntries(int limit) {
        int cap = Math.max(1, limit);
        List<double[]> playerXZ = playerPositions();
        List<ZoneHeatmapEntry> rows = new ArrayList<>();
        for (Map.Entry<ZoneKey, ZoneState> entry : zones.entrySet()) {
            ZoneKey key = entry.getKey();
            rows.add(new ZoneHeatmapEntry(
                    key,
                    entry.getValue(),
                    idleMinutes(key),
                    pinned.contains(key),
                    (int) Math.round(minDistanceToPlayers(key, playerXZ)),
                    revisitScore(key)
            ));
        }
        rows.sort((a, b) -> {
            int byState = Integer.compare(b.state().ordinal(), a.state().ordinal());
            if (byState != 0) {
                return byState;
            }
            int byIdle = Long.compare(b.idleMinutes(), a.idleMinutes());
            if (byIdle != 0) {
                return byIdle;
            }
            return compareZoneKeys(a.key(), b.key());
        });
        return rows.stream().limit(cap).collect(Collectors.toList());
    }

    private static int compareZoneKeys(ZoneKey a, ZoneKey b) {
        int byX = Integer.compare(a.regionX(), b.regionX());
        return byX != 0 ? byX : Integer.compare(a.regionZ(), b.regionZ());
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

    public List<ZoneKey> unloadCandidateZones(MemoryTier tier) {
        return unloadCandidateZones(tier, MemoryTier.TIGHT);
    }

    /** LITE: DORMANT candidates from WATCH+; STANDARD uses TIGHT+. */
    public List<ZoneKey> unloadCandidateZones(MemoryTier tier, MemoryTier minDormantUnloadTier) {
        List<double[]> playerXZ = playerPositions();
        if (playerXZ.isEmpty()) {
            return List.of();
        }

        long now = System.currentTimeMillis();
        List<Map.Entry<ZoneKey, Double>> ranked = new ArrayList<>();
        for (Map.Entry<ZoneKey, ZoneState> entry : zones.entrySet()) {
            if (pinned.contains(entry.getKey())) {
                continue;
            }
            ZoneState state = entry.getValue();
            if (qualifiesForUnload(state, tier, minDormantUnloadTier)) {
                ranked.add(Map.entry(entry.getKey(), evictionPriority(entry.getKey(), playerXZ, now)));
            }
        }
        ranked.sort(Map.Entry.comparingByValue(Comparator.reverseOrder()));
        return ranked.stream().map(Map.Entry::getKey).collect(Collectors.toList());
    }

    public int demoteFarthestDormant(int maxZones) {
        if (maxZones <= 0) {
            return 0;
        }

        List<double[]> playerXZ = playerPositions();
        if (playerXZ.isEmpty()) {
            return 0;
        }

        long now = System.currentTimeMillis();
        List<Map.Entry<ZoneKey, Double>> dormant = zones.entrySet().stream()
                .filter(e -> e.getValue() == ZoneState.DORMANT && !pinned.contains(e.getKey()))
                .map(e -> Map.entry(e.getKey(), evictionPriority(e.getKey(), playerXZ, now)))
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

    /**
     * Eviction priority (higher = evict first): far zones unlikely to be revisited rank highest.
     * When the reuse model is off, this collapses to plain distance so behaviour is unchanged.
     */
    double evictionPriority(ZoneKey key, List<double[]> playerXZ, long nowMs) {
        double distance = minDistanceToPlayers(key, playerXZ);
        ZoneReuseModel reuse = this.reuseModel;
        if (!config.zoneReuseModelEnabled || reuse == null) {
            return distance;
        }
        double revisit = reuse.revisitScore(key, nowMs);
        return distance * (1.0D + config.zoneReuseRankWeight * (1.0D - revisit));
    }

    /** Revisit likelihood in [0,1] for the zone, or neutral (0.5) when the model is off. */
    public double revisitScore(ZoneKey key) {
        ZoneReuseModel reuse = this.reuseModel;
        if (!config.zoneReuseModelEnabled || reuse == null || key == null) {
            return 0.5D;
        }
        return reuse.revisitScore(key, System.currentTimeMillis());
    }

    public double thresholdScale(ZoneKey key) {
        ZoneReuseModel reuse = this.reuseModel;
        if (!config.zoneReuseModelEnabled || reuse == null || key == null) {
            return 1.0D;
        }
        return reuse.thresholdScale(key, config.zoneReuseThresholdScaleMin, config.zoneReuseThresholdScaleMax);
    }

    /** Compact reuse summary for the log/status. Iterates tracked zones only, no player lookups. */
    public String reuseSummaryLine(int topN) {
        ZoneReuseModel reuse = this.reuseModel;
        if (reuse == null) {
            return "reuse model off";
        }
        long now = System.currentTimeMillis();
        List<Map.Entry<ZoneKey, Double>> scored = new ArrayList<>();
        for (ZoneKey key : zones.keySet()) {
            scored.add(Map.entry(key, reuse.revisitScore(key, now)));
        }
        scored.sort(Map.Entry.<ZoneKey, Double>comparingByValue(Comparator.reverseOrder()));
        StringBuilder sb = new StringBuilder();
        sb.append("reuse tracked=").append(reuse.size()).append(" top:");
        int cap = Math.max(1, topN);
        int shown = 0;
        for (Map.Entry<ZoneKey, Double> e : scored) {
            if (shown >= cap) {
                break;
            }
            sb.append(String.format(Locale.ROOT, " %s=%.2f(x%.2f)",
                    e.getKey(), e.getValue(), thresholdScale(e.getKey())));
            shown++;
        }
        if (shown == 0) {
            sb.append(" none");
        }
        return sb.toString();
    }

    private List<double[]> playerPositions() {
        PredictedPositionSource source = this.positionSource;
        boolean usePredicted = config.motionModelEnabled && source != null;
        long horizonMs = Math.max(0, config.motionPredictionHorizonSeconds) * 1000L;
        List<double[]> out = new ArrayList<>();
        for (PlayerRef ref : Universe.get().getPlayers()) {
            if (!ref.isValid()) {
                continue;
            }
            Transform t = ref.getTransform();
            if (t == null || t.getPosition() == null) {
                continue;
            }
            if (usePredicted) {
                double[] predicted = source.predictedXZ(ref.getUuid(), horizonMs);
                if (predicted != null) {
                    out.add(predicted);
                    continue;
                }
            }
            out.add(new double[]{t.getPosition().x, t.getPosition().z});
        }
        return out;
    }

    static double minDistanceToPlayers(ZoneKey key, List<double[]> playerXZ) {
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

    static boolean qualifiesForUnload(ZoneState state, MemoryTier heapTier, MemoryTier minDormantUnloadTier) {
        if (state == ZoneState.FROZEN) {
            return true;
        }
        return state == ZoneState.DORMANT && heapTier.ordinal() >= minDormantUnloadTier.ordinal();
    }
}
