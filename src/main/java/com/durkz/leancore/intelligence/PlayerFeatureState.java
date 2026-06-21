package com.durkz.leancore.intelligence;

import com.durkz.leancore.probe.ChunkPressureModel;

import java.util.UUID;

public class PlayerFeatureState {

    static final double SIGNIFICANT_MOVE_BLOCKS = 2.0D;
    static final long HALF_LIFE_60S_MS = 60_000L;
    static final long HALF_LIFE_15M_MS = 15 * 60_000L;
    static final long HALF_LIFE_ACTIVITY_MS = 20_000L;
    static final long SPATIAL_SAMPLE_INTERVAL_MS = 5_000L;
    static final long EXPLORE_PULSE_INTERVAL_MS = 3_000L;
    private static final double CHUNK_BLEND_60 = blend(SPATIAL_SAMPLE_INTERVAL_MS, HALF_LIFE_60S_MS);
    private static final double CHUNK_BLEND_15M = blend(SPATIAL_SAMPLE_INTERVAL_MS, HALF_LIFE_15M_MS);
    private static final double MAX_SANE_CHUNK_EMA = 256.0D;
    private static final int DEFAULT_VIEW_RADIUS = 16;

    private final UUID playerId;

    private double emaMovement60;
    private double emaBreaks60;
    private double emaPlaces60;
    private double emaZones60;
    private double emaMovement15m;
    private double emaBreaks15m;
    private double emaPlaces15m;
    private double emaZones15m;
    private double emaChunks60;
    private double emaChunks15m;
    private double emaMine60;
    private double emaWood60;
    private double emaFarm60;
    private double emaBuild60;
    private double emaCraft60;
    private double emaCombat60;

    private final RecentActivityBuffer recentActivity = new RecentActivityBuffer();
    private final PlayerMotionModel motion = new PlayerMotionModel();

    private volatile int baseViewRadius = -1;
    private volatile int lastAppliedViewRadius = -1;
    private volatile int lastMotionBoostBlocks;

    private long lastExplorePulseMs;
    private long lastActivityMs;
    private long firstSeenMs;
    private long observedMs;
    private long lastTickMs;

    private double lastX;
    private double lastZ;
    private boolean positioned;

    private int cachedViewRadius = DEFAULT_VIEW_RADIUS;
    private int lastRawLoaded = -1;

    public PlayerFeatureState(UUID playerId) {
        this.playerId = playerId;
        long now = System.currentTimeMillis();
        this.lastActivityMs = now;
        this.firstSeenMs = now;
        this.lastTickMs = now;
    }

    public UUID playerId() {
        return playerId;
    }

    public synchronized void hydrate(
            double movement60,
            double breaks60,
            double places60,
            double zones60,
            double movement15m,
            double breaks15m,
            double places15m,
            double zones15m,
            double chunks60,
            double chunks15m,
            double mine60,
            double wood60,
            double farm60,
            double build60,
            double craft60,
            double combat60,
            long observedMs
    ) {
        this.emaMovement60 = movement60;
        this.emaBreaks60 = breaks60;
        this.emaPlaces60 = places60;
        this.emaZones60 = zones60;
        this.emaMovement15m = movement15m;
        this.emaBreaks15m = breaks15m;
        this.emaPlaces15m = places15m;
        this.emaZones15m = zones15m;
        this.emaChunks60 = sanitizeChunkEma(chunks60);
        this.emaChunks15m = sanitizeChunkEma(chunks15m);
        this.emaMine60 = mine60;
        this.emaWood60 = wood60;
        this.emaFarm60 = farm60;
        this.emaBuild60 = build60;
        this.emaCraft60 = craft60;
        this.emaCombat60 = combat60;
        this.observedMs = Math.max(0L, observedMs);
    }

    public void onBlockBroken(BlockActionContext context) {
        onActivity(context != null ? context.kind() : ActionKind.UNKNOWN, true, false);
    }

    public void onBlockPlaced(BlockActionContext context) {
        onActivity(context != null ? context.kind() : ActionKind.BUILD, false, true);
    }

    public void onCraft() {
        onActivity(ActionKind.CRAFT, false, false);
    }

    public void onBlockBroken() {
        onBlockBroken(BlockActionContext.unknown());
    }

    public void onBlockPlaced() {
        onBlockPlaced(BlockActionContext.unknown());
    }

    private synchronized void onActivity(ActionKind kind, boolean broken, boolean placed) {
        long nowMs = System.currentTimeMillis();
        tick(nowMs);
        if (broken) {
            emaBreaks60 += 1.0D;
            emaBreaks15m += 1.0D;
        }
        if (placed) {
            emaPlaces60 += 1.0D;
            emaPlaces15m += 1.0D;
        }
        switch (kind) {
            case MINE -> {
                emaMine60 += 1.0D;
                emaWood60 *= 0.72D;
                emaBuild60 *= 0.85D;
                dampenCombat();
            }
            case CHOP -> {
                emaWood60 += 1.0D;
                emaMine60 *= 0.72D;
                emaBuild60 *= 0.85D;
                dampenCombat();
            }
            case FARM -> {
                emaFarm60 += 1.0D;
                emaMine60 *= 0.85D;
                emaWood60 *= 0.85D;
                dampenCombat();
            }
            case BUILD -> {
                emaBuild60 += 1.0D;
                emaMine60 *= 0.85D;
                emaWood60 *= 0.85D;
                dampenCombat();
            }
            case CRAFT -> {
                emaCraft60 += 1.0D;
                dampenCombat();
            }
            case EXPLORE -> {
                emaZones60 += 1.0D;
                emaZones15m += 1.0D;
                dampenCombat();
            }
            case COMBAT -> emaCombat60 += 1.0D;
            default -> {
            }
        }
        recentActivity.record(kind);
        lastActivityMs = nowMs;
    }

    public synchronized PlayerBehavior recentDominantBehavior() {
        PlayerBehavior recent = recentActivity.dominantBehavior(4, 0.55D);
        return recent == null ? PlayerBehavior.UNKNOWN : recent;
    }

    public void onZoneDiscovered() {
        onActivity(ActionKind.EXPLORE, false, false);
    }

    public void onCombatHit() {
        onActivity(ActionKind.COMBAT, false, false);
    }

    public void noteViewRadius(int serverRadius, int clientRadius) {
        int radius = Math.max(serverRadius, clientRadius);
        if (radius > 0) {
            cachedViewRadius = radius;
        }
    }

    public int cachedViewRadius() {
        return cachedViewRadius;
    }

    /** Largest known view radius in chunks (cached reading or last motion-applied). */
    public int effectiveViewRadius() {
        return Math.max(0, Math.max(cachedViewRadius, lastAppliedViewRadius));
    }

    public int lastRawLoaded() {
        return lastRawLoaded;
    }

    public void noteRawLoaded(int loaded) {
        lastRawLoaded = Math.max(0, loaded);
    }

    public synchronized void sampleSpatial(double chunkPressure) {
        if (chunkPressure <= 0.0D) {
            return;
        }
        double capped = Math.min(ChunkPressureModel.MAX_PRESSURE, chunkPressure);
        emaChunks60 = emaChunks60 * (1.0D - CHUNK_BLEND_60) + capped * CHUNK_BLEND_60;
        emaChunks15m = emaChunks15m * (1.0D - CHUNK_BLEND_15M) + capped * CHUNK_BLEND_15M;
    }

    public synchronized void samplePosition(double x, double z) {
        long nowMs = System.currentTimeMillis();
        tick(nowMs);
        motion.update(x, z, nowMs);
        if (positioned) {
            double dist = Math.hypot(x - lastX, z - lastZ);
            if (dist >= SIGNIFICANT_MOVE_BLOCKS) {
                emaMovement60 += dist;
                emaMovement15m += dist;
                emaCombat60 *= 0.90D;
                if (nowMs - lastExplorePulseMs >= EXPLORE_PULSE_INTERVAL_MS) {
                    recentActivity.record(ActionKind.EXPLORE);
                    lastExplorePulseMs = nowMs;
                }
                lastActivityMs = nowMs;
            }
        }
        lastX = x;
        lastZ = z;
        positioned = true;
    }

    public synchronized double[] predictedXZ(long horizonMs) {
        return motion.predictedXZ(horizonMs);
    }

    /** Last (x,z) captured by {@link #samplePosition}, or {@code null} before the first sample. */
    public synchronized double[] lastSampledXZ() {
        return positioned ? new double[]{lastX, lastZ} : null;
    }

    public synchronized double speedBlocksPerSec() {
        return motion.speedBlocksPerSec();
    }

    public synchronized double motionConfidence() {
        return motion.confidence();
    }

    public synchronized double motionViewScale(double minSpeedBlocksPerSec, double maxBoost) {
        return motion.viewScale(minSpeedBlocksPerSec, maxBoost);
    }

    public void setBaseViewRadius(int radius) {
        this.baseViewRadius = radius;
    }

    public int baseViewRadius() {
        return baseViewRadius;
    }

    public void noteMotionApplied(int appliedRadius, int boostBlocks) {
        this.lastAppliedViewRadius = appliedRadius;
        this.lastMotionBoostBlocks = boostBlocks;
    }

    public int lastAppliedViewRadius() {
        return lastAppliedViewRadius;
    }

    public int lastMotionBoostBlocks() {
        return lastMotionBoostBlocks;
    }

    private void dampenCombat() {
        emaCombat60 *= 0.72D;
    }

    public synchronized void tick(long nowMs) {
        if (lastTickMs <= 0L) {
            lastTickMs = nowMs;
            return;
        }
        long elapsedMs = nowMs - lastTickMs;
        if (elapsedMs <= 0L) {
            return;
        }
        double decay60 = decay(elapsedMs, HALF_LIFE_60S_MS);
        double decay15m = decay(elapsedMs, HALF_LIFE_15M_MS);
        double decayActivity = decay(elapsedMs, HALF_LIFE_ACTIVITY_MS);
        emaMovement60 *= decay60;
        emaBreaks60 *= decay60;
        emaPlaces60 *= decay60;
        emaZones60 *= decay60;
        emaMovement15m *= decay15m;
        emaBreaks15m *= decay15m;
        emaPlaces15m *= decay15m;
        emaZones15m *= decay15m;
        emaMine60 *= decayActivity;
        emaWood60 *= decayActivity;
        emaFarm60 *= decayActivity;
        emaBuild60 *= decayActivity;
        emaCraft60 *= decayActivity;
        emaCombat60 *= decayActivity;
        observedMs += elapsedMs;
        lastTickMs = nowMs;
    }

    public synchronized double activityIndex() {
        double shortTerm = emaMovement60 * 0.04D
                + emaBreaks60 * 2.0D
                + emaPlaces60 * 2.5D
                + emaZones60 * 4.0D
                + emaChunks60 * 0.08D
                + emaMine60 * 1.5D
                + emaWood60 * 1.5D
                + emaFarm60 * 1.8D
                + emaBuild60 * 2.0D
                + emaCraft60 * 1.2D
                + emaCombat60 * 2.2D;
        double longTerm = emaMovement15m * 0.002D
                + emaBreaks15m * 0.15D
                + emaPlaces15m * 0.18D
                + emaZones15m * 0.25D
                + emaChunks15m * 0.01D;
        return shortTerm + longTerm;
    }

    public long idleSec(long nowMs) {
        return Math.max(0L, (nowMs - lastActivityMs) / 1000L);
    }

    public synchronized double confidence() {
        double timeFactor = Math.min(1.0D, observedMs / 600_000D);
        double stability = featureStability();
        return Math.min(1.0D, timeFactor * (0.45D + 0.55D * stability));
    }

    private double featureStability() {
        double shortTerm = emaMovement60 + emaBreaks60 + emaPlaces60 + emaZones60 + emaChunks60 * 0.1D;
        double longTerm = emaMovement15m + emaBreaks15m + emaPlaces15m + emaZones15m + emaChunks15m * 0.1D;
        if (longTerm < 0.5D) {
            return 0.25D;
        }
        double ratio = shortTerm / (longTerm + 0.01D);
        double drift = Math.abs(Math.log(ratio + 0.01D));
        return FeatureNormalizer.clamp01(1.0D - drift / 1.5D);
    }

    public double emaMovement60() {
        return emaMovement60;
    }

    public double emaBreaks60() {
        return emaBreaks60;
    }

    public double emaPlaces60() {
        return emaPlaces60;
    }

    public double emaZones60() {
        return emaZones60;
    }

    public double emaMovement15m() {
        return emaMovement15m;
    }

    public double emaBreaks15m() {
        return emaBreaks15m;
    }

    public double emaPlaces15m() {
        return emaPlaces15m;
    }

    public double emaZones15m() {
        return emaZones15m;
    }

    public double emaChunks60() {
        return emaChunks60;
    }

    public double emaChunks15m() {
        return emaChunks15m;
    }

    public long observedSec() {
        return observedMs / 1000L;
    }

    public double emaMine60() {
        return emaMine60;
    }

    public double emaWood60() {
        return emaWood60;
    }

    public double emaFarm60() {
        return emaFarm60;
    }

    public double emaBuild60() {
        return emaBuild60;
    }

    public double emaCraft60() {
        return emaCraft60;
    }

    public double emaCombat60() {
        return emaCombat60;
    }

    private static double decay(long elapsedMs, long halfLifeMs) {
        return Math.exp(-Math.log(2.0D) * elapsedMs / halfLifeMs);
    }

    private static double sanitizeChunkEma(double value) {
        if (value <= 0.0D || Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0D;
        }
        return Math.min(value, MAX_SANE_CHUNK_EMA);
    }

    private static double blend(long sampleIntervalMs, long halfLifeMs) {
        return 1.0D - Math.exp(-Math.log(2.0D) * sampleIntervalMs / halfLifeMs);
    }
}
