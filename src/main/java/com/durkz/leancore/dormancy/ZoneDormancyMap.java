package com.durkz.leancore.dormancy;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.diagnostics.DiagnosticLog;
import com.durkz.leancore.diagnostics.ZoneRankingJfrEvent;
import com.durkz.leancore.intelligence.FalseCutTracker;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class ZoneDormancyMap {

    private final LeanCoreConfig config;
    private final Map<ZoneKey, ZoneState> zones = new ConcurrentHashMap<>();
    private final Map<ZoneKey, Long> lastHotAtMs = new ConcurrentHashMap<>();
    private final Set<ZoneKey> pinned = ConcurrentHashMap.newKeySet();
    private volatile PredictedPositionSource positionSource;
    private volatile ZoneReuseModel reuseModel;
    private volatile FalseCutTracker falseCutTracker;
    private final Map<ZoneKey, Long> recentlyUnloadedAtMs = new ConcurrentHashMap<>();
    private final AtomicInteger revisitAfterUnloadCount = new AtomicInteger();
    private final ThreadLocal<ZoneRankScratch> rankScratch = ThreadLocal.withInitial(ZoneRankScratch::new);

    public ZoneDormancyMap(LeanCoreConfig config) {
        this.config = config;
    }

    public void setPredictedPositionSource(PredictedPositionSource source) {
        this.positionSource = source;
    }

    public void setZoneReuseModel(ZoneReuseModel model) {
        this.reuseModel = model;
    }

    public void setFalseCutTracker(FalseCutTracker tracker) {
        this.falseCutTracker = tracker;
    }

    /**
     * Records that a zone just had chunks unloaded, so a near-term return to HOT can be scored as a
     * false cut (v1.6.0 Frente 2 reward). Called by the unloader after a confirmed unload.
     */
    public void noteZoneUnloaded(ZoneKey key, long nowMs) {
        if (key == null) {
            return;
        }
        recentlyUnloadedAtMs.put(key, nowMs);
    }

    /** Zones that returned to HOT within the configured window after being unloaded (session total). */
    public int revisitAfterUnloadCount() {
        return revisitAfterUnloadCount.get();
    }

    /**
     * Runs the dormancy state machine from a pre-collected set of hot zones. The runtime gathers
     * hot zones per world (each on its own world thread) and calls this once on the scheduler
     * thread; the aging/transition logic touches no PlayerRef, so it is safe off the world thread.
     */
    public void refreshFromHotZones(Collection<ZoneKey> hotZones, long now) {
        refreshFromPlayerZones(hotZones, now);
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
                boolean afterUnload = noteRevisitAfterUnload(key, now);
                String why = afterUnload ? "revisit-after-unload"
                        : (reuseEnabled && reuse.visitCount(key) > 1 ? "revisit" : "player entered");
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
            scale = applyContentToThreshold(key, scale, reuse);
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

    /**
     * Scores a zone that just turned HOT as a false cut when we unloaded it inside the configured
     * window. Always increments the observability counter; only feeds the bandit reward when
     * {@code zoneFalseCutRewardEnabled} is set. Returns true when the revisit was within the window.
     */
    private boolean noteRevisitAfterUnload(ZoneKey key, long now) {
        Long unloadedAt = recentlyUnloadedAtMs.remove(key);
        if (unloadedAt == null) {
            return false;
        }
        long windowMs = Math.max(0, config.zoneRevisitAfterUnloadWindowSeconds) * 1000L;
        if (windowMs <= 0L || now - unloadedAt > windowMs) {
            return false;
        }
        revisitAfterUnloadCount.incrementAndGet();
        FalseCutTracker tracker = this.falseCutTracker;
        if (config.zoneFalseCutRewardEnabled && tracker != null) {
            tracker.noteCut(true);
        }
        return true;
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
        long unloadWindowMs = Math.max(0, config.zoneRevisitAfterUnloadWindowSeconds) * 1000L;
        if (unloadWindowMs <= 0L) {
            recentlyUnloadedAtMs.clear();
        } else {
            long unloadCutoff = now - unloadWindowMs;
            recentlyUnloadedAtMs.entrySet().removeIf(entry -> entry.getValue() < unloadCutoff);
        }
    }

    /**
     * Hot zones for the given players, tagged with each player's world. Reads transforms, so the
     * runtime calls this per world (inside a {@code WorldDispatch.run}) and aggregates the results
     * before running the (pure) dormancy state machine in {@link #refreshFromPlayerZones}.
     */
    public static List<ZoneKey> hotZonesForPlayers(Collection<PlayerRef> players) {
        List<ZoneKey> hot = new ArrayList<>();
        if (players == null) {
            return hot;
        }
        for (PlayerRef ref : players) {
            if (ref == null || !ref.isValid()) {
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
        List<PlayerPos> playerXZ = playerPositions();
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

    public List<ZoneKey> unloadCandidateZones(MemoryTier tier) {
        return unloadCandidateZones(tier, MemoryTier.TIGHT);
    }

    /** LITE: DORMANT candidates from WATCH+; STANDARD uses TIGHT+. */
    public List<ZoneKey> unloadCandidateZones(MemoryTier tier, MemoryTier minDormantUnloadTier) {
        List<PlayerPos> playerXZ = playerPositions();
        if (playerXZ.isEmpty()) {
            return List.of();
        }

        long now = System.currentTimeMillis();
        ZoneRankingJfrEvent event = ZoneRankingJfrEvent.begin("unload-candidates");
        ZoneRankScratch scratch = rankScratch.get();
        scratch.reset(zones.size());
        try {
            for (Map.Entry<ZoneKey, ZoneState> entry : zones.entrySet()) {
                ZoneKey key = entry.getKey();
                if (pinned.contains(key) || !qualifiesForUnload(entry.getValue(), tier, minDormantUnloadTier)
                        || isProtectedByRevisit(key, tier, now)) {
                    continue;
                }
                double distanceSq = nearestUnprotectedDistanceSq(key, playerXZ);
                if (Double.isNaN(distanceSq)) {
                    continue;
                }
                scratch.add(key, evictionPriority(key, distanceFromSquared(distanceSq), now));
            }
            scratch.sortDescendingStable();
            return scratch.copyKeys();
        } finally {
            ZoneRankingJfrEvent.commit(event, zones.size(), scratch.size());
            scratch.clear();
        }
    }

    public int demoteFarthestDormant(int maxZones) {
        if (maxZones <= 0) {
            return 0;
        }

        List<PlayerPos> playerXZ = playerPositions();
        if (playerXZ.isEmpty()) {
            return 0;
        }

        long now = System.currentTimeMillis();
        ZoneRankingJfrEvent event = ZoneRankingJfrEvent.begin("demote-dormant");
        ZoneRankScratch scratch = rankScratch.get();
        scratch.reset(zones.size());
        try {
            for (Map.Entry<ZoneKey, ZoneState> entry : zones.entrySet()) {
                ZoneKey key = entry.getKey();
                if (entry.getValue() != ZoneState.DORMANT || pinned.contains(key)) {
                    continue;
                }
                double distanceSq = nearestUnprotectedDistanceSq(key, playerXZ);
                if (!Double.isNaN(distanceSq)) {
                    scratch.add(key, evictionPriority(key, distanceFromSquared(distanceSq), now));
                }
            }
            scratch.sortDescendingStable();
            int demoted = Math.min(maxZones, scratch.size());
            for (int i = 0; i < demoted; i++) {
                zones.put(scratch.keyAt(i), ZoneState.FROZEN);
            }
            return demoted;
        } finally {
            ZoneRankingJfrEvent.commit(event, zones.size(), scratch.size());
            scratch.clear();
        }
    }

    /**
     * Anti-reload guard: keep a zone out of the unload candidate set while its revisit likelihood is
     * at or above the keep threshold, so a base/hub the player almost certainly returns to is not
     * unloaded and re-streamed. CRITICAL pressure overrides it. No-op without the reuse model.
     */
    private boolean isProtectedByRevisit(ZoneKey key, MemoryTier tier, long nowMs) {
        ZoneReuseModel reuse = this.reuseModel;
        if (!config.zoneReuseModelEnabled || reuse == null) {
            return false;
        }
        return revisitProtects(
                config.zoneRevisitKeepEnabled, tier, reuse.revisitScore(key, nowMs),
                config.zoneRevisitKeepThreshold);
    }

    /** Pure keep-guard decision: protect high-revisit zones from unload, except under CRITICAL. */
    static boolean revisitProtects(boolean enabled, MemoryTier tier, double revisitScore, double threshold) {
        if (!enabled || tier == MemoryTier.CRITICAL) {
            return false;
        }
        return revisitScore >= threshold;
    }

    /**
     * Eviction priority (higher = evict first): far zones unlikely to be revisited rank highest.
     * When the reuse model is off, this collapses to plain distance so behaviour is unchanged.
     */
    double evictionPriority(ZoneKey key, List<PlayerPos> playerXZ, long nowMs) {
        return evictionPriority(key, minDistanceToPlayers(key, playerXZ), nowMs);
    }

    private double evictionPriority(ZoneKey key, double distance, long nowMs) {
        ZoneReuseModel reuse = this.reuseModel;
        if (!config.zoneReuseModelEnabled || reuse == null) {
            return distance;
        }
        double revisit = reuse.revisitScore(key, nowMs);
        double priority = distance * (1.0D + config.zoneReuseRankWeight * (1.0D - revisit));
        // Content-rich zones (chests/benches/built base) should be evicted last: shrink their
        // priority by up to 90% with the built-content score. Off when the content model is off.
        double content = contentScore0(key, reuse);
        if (content > 0.0D) {
            priority *= 1.0D - Math.min(0.9D, config.zoneContentRankWeight * content);
        }
        return priority;
    }

    /**
     * Content score in [0,1] for the zone, or 0 when the content model is off. Read from the reuse
     * model's persisted per-zone EMA (v1.7.0 Frente B).
     */
    private double contentScore0(ZoneKey key, ZoneReuseModel reuse) {
        if (!config.zoneContentModelEnabled || reuse == null || key == null) {
            return 0.0D;
        }
        return reuse.contentScore(key);
    }

    /**
     * Stretches the dormancy threshold scale for content-rich zones so a built base stays HOT longer
     * before aging to DORMANT/FROZEN. Re-clamped to the configured reuse scale band.
     */
    private double applyContentToThreshold(ZoneKey key, double scale, ZoneReuseModel reuse) {
        double content = contentScore0(key, reuse);
        if (content <= 0.0D) {
            return scale;
        }
        double boosted = scale * (1.0D + config.zoneContentRankWeight * content);
        return Math.max(config.zoneReuseThresholdScaleMin,
                Math.min(config.zoneReuseThresholdScaleMax, boosted));
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
        boolean contentOn = config.zoneContentModelEnabled;
        for (Map.Entry<ZoneKey, Double> e : scored) {
            if (shown >= cap) {
                break;
            }
            sb.append(String.format(Locale.ROOT, " %s=%.2f(x%.2f)",
                    e.getKey(), e.getValue(), thresholdScale(e.getKey())));
            if (contentOn) {
                sb.append(String.format(Locale.ROOT, "c%.2f", reuse.contentScore(e.getKey())));
            }
            shown++;
        }
        if (shown == 0) {
            sb.append(" none");
        }
        int revisits = revisitAfterUnloadCount.get();
        if (revisits > 0) {
            sb.append(" revisitAfterUnload=").append(revisits);
        }
        return sb.toString();
    }

    /**
     * Player anchor points for distance/protection math. Positions come from the motion sampler
     * (captured on each world thread), not a live transform read, so this stays safe to call from
     * the scheduler thread. Without a position source there is nothing to protect, so it returns
     * empty and the caller produces no unload candidates.
     */
    private List<PlayerPos> playerPositions() {
        PredictedPositionSource source = this.positionSource;
        if (source == null) {
            return List.of();
        }
        boolean usePredicted = config.motionModelEnabled;
        long horizonMs = Math.max(0, config.motionPredictionHorizonSeconds) * 1000L;
        double fallbackViewBlocks = Math.max(1, config.maxClientViewRadius) * 16.0D;
        List<PlayerPos> out = new ArrayList<>();
        for (PlayerRef ref : Universe.get().getPlayers()) {
            if (!ref.isValid()) {
                continue;
            }
            java.util.UUID playerId = ref.getUuid();
            double[] xz = source.currentXZ(playerId);
            if (xz == null) {
                continue;
            }
            java.util.UUID worldUuid = ref.getWorldUuid();
            double viewBlocks = fallbackViewBlocks;
            int chunks = source.viewRadiusChunks(playerId);
            if (chunks > 0) {
                viewBlocks = chunks * 16.0D;
            }
            out.add(new PlayerPos(worldUuid, xz[0], xz[1], viewBlocks));
            // A predicted point protects the cone ahead too.
            if (usePredicted) {
                double[] predicted = source.predictedXZ(playerId, horizonMs);
                if (predicted != null) {
                    out.add(new PlayerPos(worldUuid, predicted[0], predicted[1], viewBlocks));
                }
            }
        }
        return out;
    }

    /**
     * Distance from the nearest same-world player to the zone's nearest chunk (point-to-AABB, not to
     * the center). Other worlds are ignored, so a world with nobody online ranks as MAX_VALUE.
     */
    static double minDistanceToPlayers(ZoneKey key, List<PlayerPos> playerXZ) {
        double minSq = Double.POSITIVE_INFINITY;
        for (PlayerPos p : playerXZ) {
            if (key.worldUuid() != null && !key.worldUuid().equals(p.world())) {
                continue;
            }
            minSq = Math.min(minSq, edgeDistanceSquared(key, p.x(), p.z()));
        }
        return distanceFromSquared(minSq);
    }

    /**
     * Zone is protected (never an unload candidate) when its nearest chunk is within any same-world
     * player's view distance plus one region of slack. Trades reclaim near players for zero thrash.
     */
    static boolean isProtectedByView(ZoneKey key, List<PlayerPos> playerXZ) {
        return Double.isNaN(nearestUnprotectedDistanceSq(key, playerXZ));
    }

    /** Squared distance from (px,pz) to the nearest point of the region's block-space AABB. */
    static double edgeDistanceSquared(ZoneKey key, double px, double pz) {
        double regionBlocks = ZoneKey.regionChunks() * 16.0D;
        double minX = key.regionX() * regionBlocks;
        double maxX = minX + regionBlocks;
        double minZ = key.regionZ() * regionBlocks;
        double maxZ = minZ + regionBlocks;
        double clampedX = Math.max(minX, Math.min(px, maxX));
        double clampedZ = Math.max(minZ, Math.min(pz, maxZ));
        double dx = px - clampedX;
        double dz = pz - clampedZ;
        return dx * dx + dz * dz;
    }

    /** Returns NaN when the zone is protected, otherwise its nearest same-world distance squared. */
    static double nearestUnprotectedDistanceSq(ZoneKey key, List<PlayerPos> playerXZ) {
        double minSq = Double.POSITIVE_INFINITY;
        double margin = ZoneKey.regionChunks() * 16.0D;
        for (PlayerPos p : playerXZ) {
            if (key.worldUuid() != null && !key.worldUuid().equals(p.world())) {
                continue;
            }
            double distanceSq = edgeDistanceSquared(key, p.x(), p.z());
            double protectedRadius = p.viewBlocks() + margin;
            if (distanceSq <= protectedRadius * protectedRadius) {
                return Double.NaN;
            }
            minSq = Math.min(minSq, distanceSq);
        }
        return minSq;
    }

    private static double distanceFromSquared(double distanceSq) {
        return Double.isInfinite(distanceSq) ? Double.MAX_VALUE : Math.sqrt(distanceSq);
    }

    record PlayerPos(java.util.UUID world, double x, double z, double viewBlocks) {
    }

    static boolean qualifiesForUnload(ZoneState state, MemoryTier heapTier, MemoryTier minDormantUnloadTier) {
        if (state == ZoneState.FROZEN) {
            return true;
        }
        return state == ZoneState.DORMANT && heapTier.ordinal() >= minDormantUnloadTier.ordinal();
    }

    /** Reusable stable sort buffer. Each runtime thread gets its own instance. */
    private static final class ZoneRankScratch {
        private ZoneKey[] keys = new ZoneKey[0];
        private double[] scores = new double[0];
        private ZoneKey[] keyWork = new ZoneKey[0];
        private double[] scoreWork = new double[0];
        private int size;

        void reset(int expectedSize) {
            ensureCapacity(expectedSize);
            size = 0;
        }

        void add(ZoneKey key, double score) {
            keys[size] = key;
            scores[size] = score;
            size++;
        }

        int size() {
            return size;
        }

        ZoneKey keyAt(int index) {
            return keys[index];
        }

        List<ZoneKey> copyKeys() {
            List<ZoneKey> result = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                result.add(keys[i]);
            }
            return result;
        }

        void sortDescendingStable() {
            for (int width = 1; width < size; width <<= 1) {
                for (int left = 0; left < size; left += width << 1) {
                    int middle = Math.min(left + width, size);
                    int right = Math.min(left + (width << 1), size);
                    merge(left, middle, right);
                }
                ZoneKey[] keySwap = keys;
                keys = keyWork;
                keyWork = keySwap;
                double[] scoreSwap = scores;
                scores = scoreWork;
                scoreWork = scoreSwap;
            }
        }

        void clear() {
            for (int i = 0; i < size; i++) {
                keys[i] = null;
                keyWork[i] = null;
            }
            size = 0;
        }

        private void merge(int left, int middle, int right) {
            int a = left;
            int b = middle;
            int target = left;
            while (a < middle && b < right) {
                if (scores[a] >= scores[b]) {
                    keyWork[target] = keys[a];
                    scoreWork[target++] = scores[a++];
                } else {
                    keyWork[target] = keys[b];
                    scoreWork[target++] = scores[b++];
                }
            }
            while (a < middle) {
                keyWork[target] = keys[a];
                scoreWork[target++] = scores[a++];
            }
            while (b < right) {
                keyWork[target] = keys[b];
                scoreWork[target++] = scores[b++];
            }
        }

        private void ensureCapacity(int expectedSize) {
            if (keys.length >= expectedSize) {
                return;
            }
            int capacity = Math.max(expectedSize, keys.length * 2 + 16);
            keys = new ZoneKey[capacity];
            scores = new double[capacity];
            keyWork = new ZoneKey[capacity];
            scoreWork = new double[capacity];
        }
    }
}
