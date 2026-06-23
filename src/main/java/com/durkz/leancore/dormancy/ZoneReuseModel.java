package com.durkz.leancore.dormancy;

import com.durkz.leancore.intelligence.FeatureNormalizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-zone reuse-distance + survival model (caching theory). Tracks how often and how recently each
 * zone is revisited and estimates a revisit score in [0,1] used to bias chunk unload and to scale
 * the dormancy thresholds. Small and interpretable: an EMA of inter-visit intervals plus an
 * exponential survival decay and an LFU-flavored frequency term. Persisted across sessions.
 */
public final class ZoneReuseModel {

    private static final double INTERVAL_BLEND = 0.4D;
    private static final double CONTENT_BLEND = 0.3D;
    private static final double VISIT_NORM = 20.0D;
    private static final double MIN_INTERVAL_MS = 1000.0D;
    private static final double NEUTRAL_SCORE = 0.5D;
    static final int MIN_VISITS_FOR_SCORE = 2;

    private final Map<ZoneKey, ZoneReuseStat> stats = new ConcurrentHashMap<>();

    /** Record a non-HOT -> HOT transition for the zone. */
    public void noteHot(ZoneKey key, long nowMs) {
        if (key == null) {
            return;
        }
        stats.compute(key, (k, prev) -> prev == null
                ? ZoneReuseStat.firstVisit(nowMs)
                : prev.revisit(nowMs));
    }

    /** Revisit likelihood in [0,1]. Neutral (0.5) until enough visits are observed. */
    public double revisitScore(ZoneKey key, long nowMs) {
        ZoneReuseStat s = key == null ? null : stats.get(key);
        if (s == null || s.visitCount < MIN_VISITS_FOR_SCORE) {
            return NEUTRAL_SCORE;
        }
        return s.revisitScore(nowMs);
    }

    /** Multiplier for dormant/frozen thresholds: high-frequency zones decay slower. 1.0 until learned. */
    public double thresholdScale(ZoneKey key, double min, double max) {
        ZoneReuseStat s = key == null ? null : stats.get(key);
        if (s == null || s.visitCount < MIN_VISITS_FOR_SCORE) {
            return 1.0D;
        }
        return s.thresholdScale(min, max);
    }

    public int visitCount(ZoneKey key) {
        ZoneReuseStat s = key == null ? null : stats.get(key);
        return s == null ? 0 : s.visitCount;
    }

    /**
     * Blends a fresh content observation (v1.7.0 Frente B) into the zone's persisted content EMA.
     * Creates a content-only entry for zones not yet visited; the reuse score still gates on
     * {@link #MIN_VISITS_FOR_SCORE}, so a content-only entry never affects revisit math.
     */
    public void noteContent(ZoneKey key, double contentScore, long nowMs) {
        if (key == null) {
            return;
        }
        final double observed = FeatureNormalizer.clamp01(contentScore);
        stats.compute(key, (k, prev) -> {
            ZoneReuseStat base = prev == null ? ZoneReuseStat.contentOnly(nowMs) : prev;
            return base.withContent(observed, nowMs);
        });
    }

    /** Persisted content score in [0,1] for the zone, or 0 (none/unknown) when untracked. */
    public double contentScore(ZoneKey key) {
        ZoneReuseStat s = key == null ? null : stats.get(key);
        return s == null ? 0.0D : s.contentScore;
    }

    public int size() {
        return stats.size();
    }

    /** Drop zones unseen past the TTL, then cap to maxEntries by evicting the least-recently-seen. */
    public int prune(long nowMs, long ttlMs, int maxEntries) {
        int removed = 0;
        if (ttlMs > 0L) {
            long cutoff = nowMs - ttlMs;
            var it = stats.entrySet().iterator();
            while (it.hasNext()) {
                if (it.next().getValue().lastSeenMs < cutoff) {
                    it.remove();
                    removed++;
                }
            }
        }
        if (maxEntries > 0 && stats.size() > maxEntries) {
            List<Map.Entry<ZoneKey, ZoneReuseStat>> all = new ArrayList<>(stats.entrySet());
            all.sort(Comparator.comparingLong(e -> e.getValue().lastSeenMs));
            int toRemove = stats.size() - maxEntries;
            for (int i = 0; i < toRemove && i < all.size(); i++) {
                stats.remove(all.get(i).getKey());
                removed++;
            }
        }
        return removed;
    }

    /**
     * Export persistable records for zones with at least {@code minVisits} visits, plus any zone
     * that already carries a learned content score (v1.7.0 Frente B): a base built during a single
     * visit would otherwise be dropped before its content survives a restart.
     */
    public List<Record> export(int minVisits) {
        List<Record> out = new ArrayList<>();
        for (Map.Entry<ZoneKey, ZoneReuseStat> e : stats.entrySet()) {
            ZoneReuseStat s = e.getValue();
            if (s.visitCount < minVisits && s.contentScore <= 0.0D) {
                continue;
            }
            ZoneKey k = e.getKey();
            out.add(new Record(k.worldUuid(), k.regionX(), k.regionZ(),
                    s.visitCount, s.lastHotAtMs, s.emaIntervalMs, s.lastSeenMs, s.contentScore));
        }
        return out;
    }

    public void importRecord(Record r) {
        if (r == null || r.worldUuid() == null) {
            return;
        }
        stats.put(new ZoneKey(r.worldUuid(), r.regionX(), r.regionZ()),
                ZoneReuseStat.restore(r.visitCount(), r.lastHotAtMs(), r.emaIntervalMs(),
                        r.lastSeenMs(), r.contentScore()));
    }

    public void clear() {
        stats.clear();
    }

    public record Record(UUID worldUuid, int regionX, int regionZ,
                          int visitCount, long lastHotAtMs, double emaIntervalMs, long lastSeenMs,
                          double contentScore) {
    }

    /**
     * Immutable per-zone stat. Writers mutate via {@code ConcurrentHashMap.compute} by returning a
     * new instance, so the map publishes a fully-constructed object: readers on the scheduler/persist
     * threads never observe a torn or half-updated field. Do not add mutable state here.
     */
    static final class ZoneReuseStat {
        final int visitCount;
        final long lastHotAtMs;
        final double emaIntervalMs;
        final long lastSeenMs;
        final double contentScore;

        private ZoneReuseStat(int visitCount, long lastHotAtMs, double emaIntervalMs,
                              long lastSeenMs, double contentScore) {
            this.visitCount = visitCount;
            this.lastHotAtMs = lastHotAtMs;
            this.emaIntervalMs = emaIntervalMs;
            this.lastSeenMs = lastSeenMs;
            this.contentScore = contentScore;
        }

        static ZoneReuseStat firstVisit(long now) {
            return new ZoneReuseStat(1, now, 0.0D, now, 0.0D);
        }

        static ZoneReuseStat contentOnly(long now) {
            return new ZoneReuseStat(0, 0L, 0.0D, now, 0.0D);
        }

        static ZoneReuseStat restore(int visitCount, long lastHotAtMs, double emaIntervalMs,
                                     long lastSeenMs, double contentScore) {
            return new ZoneReuseStat(Math.max(0, visitCount), lastHotAtMs,
                    Math.max(0.0D, emaIntervalMs), lastSeenMs,
                    FeatureNormalizer.clamp01(contentScore));
        }

        ZoneReuseStat withContent(double observed, long nowMs) {
            double next = contentScore <= 0.0D
                    ? observed
                    : contentScore * (1.0D - CONTENT_BLEND) + observed * CONTENT_BLEND;
            return new ZoneReuseStat(visitCount, lastHotAtMs, emaIntervalMs, nowMs, next);
        }

        ZoneReuseStat revisit(long now) {
            long interval = Math.max((long) MIN_INTERVAL_MS, now - lastHotAtMs);
            double nextEma = emaIntervalMs <= 0.0D
                    ? interval
                    : emaIntervalMs * (1.0D - INTERVAL_BLEND) + interval * INTERVAL_BLEND;
            return new ZoneReuseStat(visitCount + 1, now, nextEma, now, contentScore);
        }

        double revisitScore(long nowMs) {
            double mean = Math.max(MIN_INTERVAL_MS, emaIntervalMs);
            double t = Math.max(0.0D, nowMs - lastHotAtMs);
            double recency = Math.exp(-t / mean);
            double frequency = frequency();
            return FeatureNormalizer.clamp01(recency * (0.5D + 0.5D * frequency));
        }

        double thresholdScale(double min, double max) {
            double scale = 0.5D + 1.5D * frequency();
            return Math.max(min, Math.min(max, scale));
        }

        private double frequency() {
            return FeatureNormalizer.clamp01(Math.log(1.0D + visitCount) / Math.log(1.0D + VISIT_NORM));
        }
    }
}
