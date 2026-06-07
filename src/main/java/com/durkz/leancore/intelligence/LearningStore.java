package com.durkz.leancore.intelligence;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.memory.MemoryTier;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class LearningStore {

    static final int SCHEMA_VERSION = 2;
    private static final String STATE_FILE = "learning.state";

    private static final double MIN_POLICY_SCORE = 0.1D;
    private static final double MAX_POLICY_SCORE = 2.0D;
    private static final double REINFORCE_DELTA = 0.1D;
    private static final double PENALIZE_DELTA = 0.25D;
    private static final double DEPRIORITIZE_SCORE = 0.35D;

    private final Path dataDir;
    private final LeanCoreConfig config;
    private final Map<String, Double> policyScores = new ConcurrentHashMap<>();
    private final Map<UUID, PersistedPlayer> players = new ConcurrentHashMap<>();
    private final RollingHeapTracker heapWindows = new RollingHeapTracker();

    private MemoryTier lastTier = MemoryTier.COMFORT;
    private int flushCount;
    private int reinforceCount;
    private int penalizeCount;

    public LearningStore(Path dataDir, LeanCoreConfig config) {
        this.dataDir = dataDir;
        this.config = config;
        load();
    }

    public void hydratePlayer(PlayerFeatureState state) {
        PersistedPlayer saved = players.get(state.playerId());
        if (saved == null) {
            return;
        }
        state.hydrate(
                saved.movement60,
                saved.breaks60,
                saved.places60,
                saved.zones60,
                saved.movement15m,
                saved.breaks15m,
                saved.places15m,
                saved.zones15m,
                saved.observedSec * 1000L
        );
    }

    public void savePlayerFeatures(PlayerFeatureState state) {
        PersistedPlayer prior = players.getOrDefault(state.playerId(), PersistedPlayer.empty());
        players.put(state.playerId(), prior.withFeatures(state));
    }

    public void noteDemands(Map<UUID, RetentionDemand> demands) {
        for (Map.Entry<UUID, RetentionDemand> e : demands.entrySet()) {
            PersistedPlayer prior = players.getOrDefault(e.getKey(), PersistedPlayer.empty());
            RetentionDemand demand = e.getValue();
            players.put(e.getKey(), prior.withDemand(demand));
        }
    }

    public RetentionDemand demandFor(UUID playerId) {
        PersistedPlayer saved = players.get(playerId);
        if (saved == null) {
            return RetentionDemand.coldStart(PlayerBehavior.UNKNOWN);
        }
        return new RetentionDemand(
                saved.demand,
                saved.confidence,
                saved.retentionMb,
                saved.debugLabel
        );
    }

    public PlayerFeatureState featureSnapshot(UUID playerId) {
        PersistedPlayer saved = players.get(playerId);
        if (saved == null) {
            return null;
        }
        PlayerFeatureState state = new PlayerFeatureState(playerId);
        state.hydrate(
                saved.movement60,
                saved.breaks60,
                saved.places60,
                saved.zones60,
                saved.movement15m,
                saved.breaks15m,
                saved.places15m,
                saved.zones15m,
                saved.observedSec * 1000L
        );
        return state;
    }

    public void noteTier(MemoryTier tier) {
        lastTier = tier;
    }

    public void noteHeap(double heapRatio) {
        if (!config.learningEnabled) {
            return;
        }
        heapWindows.add(heapRatio, System.currentTimeMillis());
    }

    public double policyScore(String policyKey) {
        return policyScores.getOrDefault(policyKey, 1.0D);
    }

    public boolean isPolicyDeprioritized(String policyKey) {
        return policyScore(policyKey) < DEPRIORITIZE_SCORE;
    }

    public void reinforcePolicy(String policyKey) {
        if (!config.learningEnabled || policyKey == null) {
            return;
        }
        policyScores.merge(policyKey, 1.0D, (old, ignored) -> clampScore(old + REINFORCE_DELTA));
        reinforceCount++;
    }

    public void penalizePolicy(String policyKey) {
        if (!config.learningEnabled || policyKey == null) {
            return;
        }
        policyScores.merge(policyKey, 1.0D, (old, ignored) -> clampScore(old - PENALIZE_DELTA));
        penalizeCount++;
    }

    public void flush() {
        if (!config.learningEnabled) {
            return;
        }
        long now = System.currentTimeMillis();
        Properties props = new Properties();
        props.setProperty("schema", Integer.toString(SCHEMA_VERSION));
        props.setProperty("savedAt", Instant.now().toString());
        props.setProperty("tier", lastTier.name());
        props.setProperty("playerCount", Integer.toString(players.size()));
        props.setProperty("heap.avg60s", formatRatio(heapWindows.avg60s(now)));
        props.setProperty("heap.avg15m", formatRatio(heapWindows.avg15m(now)));
        props.setProperty("heap.avg24h", formatRatio(heapWindows.avg24h(now)));
        props.setProperty("learn.reinforce", Integer.toString(reinforceCount));
        props.setProperty("learn.penalize", Integer.toString(penalizeCount));

        for (Map.Entry<String, Double> e : policyScores.entrySet()) {
            props.setProperty("policy." + e.getKey(), Double.toString(e.getValue()));
        }
        for (Map.Entry<UUID, PersistedPlayer> e : players.entrySet()) {
            writePlayer(props, e.getKey(), e.getValue());
        }

        try {
            Files.createDirectories(dataDir);
            Path target = dataDir.resolve(STATE_FILE);
            try (OutputStream out = Files.newOutputStream(target)) {
                props.store(out, "LeanCore learning v2");
            }
            flushCount++;
        } catch (IOException ignored) {
        }
    }

    public String statusLine() {
        long now = System.currentTimeMillis();
        return String.format(Locale.ROOT,
                "learning=v2 enabled=%s flushes=%d players=%d tier=%s heap60s=%.0f%% +%d -%d",
                config.learningEnabled,
                flushCount,
                players.size(),
                lastTier,
                heapWindows.avg60s(now) * 100.0D,
                reinforceCount,
                penalizeCount);
    }

    public String windowLine() {
        long now = System.currentTimeMillis();
        return String.format(Locale.ROOT, "windows 60s=%.0f%% 15m=%.0f%% 24h=%.0f%%",
                heapWindows.avg60s(now) * 100.0D,
                heapWindows.avg15m(now) * 100.0D,
                heapWindows.avg24h(now) * 100.0D);
    }

    private void load() {
        Path target = dataDir.resolve(STATE_FILE);
        if (!Files.isRegularFile(target)) {
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(target)) {
            props.load(in);
        } catch (IOException ignored) {
            return;
        }

        if (readInt(props, "schema", 0) != SCHEMA_VERSION) {
            return;
        }

        for (String key : props.stringPropertyNames()) {
            if (key.startsWith("policy.")) {
                policyScores.put(key.substring("policy.".length()), readDouble(props, key, 1.0D));
            }
        }
        reinforceCount = readInt(props, "learn.reinforce", 0);
        penalizeCount = readInt(props, "learn.penalize", 0);

        for (String key : props.stringPropertyNames()) {
            if (!key.startsWith("player.")) {
                continue;
            }
            String suffix = key.substring("player.".length());
            int dot = suffix.indexOf('.');
            if (dot <= 0) {
                continue;
            }
            try {
                UUID id = UUID.fromString(suffix.substring(0, dot));
                String field = suffix.substring(dot + 1);
                players.compute(id, (uuid, prior) -> {
                    PersistedPlayer base = prior == null ? PersistedPlayer.empty() : prior;
                    return base.withField(field, props.getProperty(key));
                });
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private static void writePlayer(Properties props, UUID id, PersistedPlayer player) {
        String prefix = "player." + id + ".";
        props.setProperty(prefix + "demand", Double.toString(player.demand));
        props.setProperty(prefix + "confidence", Double.toString(player.confidence));
        props.setProperty(prefix + "retentionMb", Integer.toString(player.retentionMb));
        props.setProperty(prefix + "label", player.debugLabel.name());
        props.setProperty(prefix + "movement60", Double.toString(player.movement60));
        props.setProperty(prefix + "breaks60", Double.toString(player.breaks60));
        props.setProperty(prefix + "places60", Double.toString(player.places60));
        props.setProperty(prefix + "zones60", Double.toString(player.zones60));
        props.setProperty(prefix + "movement15m", Double.toString(player.movement15m));
        props.setProperty(prefix + "breaks15m", Double.toString(player.breaks15m));
        props.setProperty(prefix + "places15m", Double.toString(player.places15m));
        props.setProperty(prefix + "zones15m", Double.toString(player.zones15m));
        props.setProperty(prefix + "observedSec", Long.toString(player.observedSec));
    }

    private static double readDouble(Properties props, String key, double fallback) {
        String raw = props.getProperty(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int readInt(Properties props, String key, int fallback) {
        String raw = props.getProperty(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String formatRatio(double ratio) {
        return String.format(Locale.ROOT, "%.4f", ratio);
    }

    private static double clampScore(double score) {
        return Math.max(MIN_POLICY_SCORE, Math.min(MAX_POLICY_SCORE, score));
    }

    private record PersistedPlayer(
            double demand,
            double confidence,
            int retentionMb,
            PlayerBehavior debugLabel,
            double movement60,
            double breaks60,
            double places60,
            double zones60,
            double movement15m,
            double breaks15m,
            double places15m,
            double zones15m,
            long observedSec
    ) {
        static PersistedPlayer empty() {
            return new PersistedPlayer(
                    0.5D, 0.0D, RetentionDemand.PRIOR_MB, PlayerBehavior.UNKNOWN,
                    0.0D, 0.0D, 0.0D, 0.0D,
                    0.0D, 0.0D, 0.0D, 0.0D,
                    0L
            );
        }

        PersistedPlayer withFeatures(PlayerFeatureState state) {
            return new PersistedPlayer(
                    demand, confidence, retentionMb, debugLabel,
                    state.emaMovement60(), state.emaBreaks60(), state.emaPlaces60(), state.emaZones60(),
                    state.emaMovement15m(), state.emaBreaks15m(), state.emaPlaces15m(), state.emaZones15m(),
                    state.observedSec()
            );
        }

        PersistedPlayer withDemand(RetentionDemand demand) {
            return new PersistedPlayer(
                    demand.demand(),
                    demand.confidence(),
                    demand.retentionMb(),
                    demand.debugLabel(),
                    movement60,
                    breaks60,
                    places60,
                    zones60,
                    movement15m,
                    breaks15m,
                    places15m,
                    zones15m,
                    observedSec
            );
        }

        PersistedPlayer withField(String field, String raw) {
            if (raw == null) {
                return this;
            }
            return switch (field) {
                case "demand" -> new PersistedPlayer(readDouble(raw, demand), confidence, retentionMb, debugLabel,
                        movement60, breaks60, places60, zones60, movement15m, breaks15m, places15m, zones15m, observedSec);
                case "confidence" -> new PersistedPlayer(demand, readDouble(raw, confidence), retentionMb, debugLabel,
                        movement60, breaks60, places60, zones60, movement15m, breaks15m, places15m, zones15m, observedSec);
                case "retentionMb" -> new PersistedPlayer(demand, confidence, readInt(raw, retentionMb), debugLabel,
                        movement60, breaks60, places60, zones60, movement15m, breaks15m, places15m, zones15m, observedSec);
                case "label" -> new PersistedPlayer(demand, confidence, retentionMb, parseLabel(raw),
                        movement60, breaks60, places60, zones60, movement15m, breaks15m, places15m, zones15m, observedSec);
                case "movement60" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        readDouble(raw, movement60), breaks60, places60, zones60, movement15m, breaks15m, places15m, zones15m, observedSec);
                case "breaks60" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, readDouble(raw, breaks60), places60, zones60, movement15m, breaks15m, places15m, zones15m, observedSec);
                case "places60" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, breaks60, readDouble(raw, places60), zones60, movement15m, breaks15m, places15m, zones15m, observedSec);
                case "zones60" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, breaks60, places60, readDouble(raw, zones60), movement15m, breaks15m, places15m, zones15m, observedSec);
                case "movement15m" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, breaks60, places60, zones60, readDouble(raw, movement15m), breaks15m, places15m, zones15m, observedSec);
                case "breaks15m" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, breaks60, places60, zones60, movement15m, readDouble(raw, breaks15m), places15m, zones15m, observedSec);
                case "places15m" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, breaks60, places60, zones60, movement15m, breaks15m, readDouble(raw, places15m), zones15m, observedSec);
                case "zones15m" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, breaks60, places60, zones60, movement15m, breaks15m, places15m, readDouble(raw, zones15m), observedSec);
                case "observedSec" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, breaks60, places60, zones60, movement15m, breaks15m, places15m, zones15m, readLong(raw, observedSec));
                default -> this;
            };
        }

        private static double readDouble(String raw, double fallback) {
            try {
                return Double.parseDouble(raw);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }

        private static int readInt(String raw, int fallback) {
            try {
                return Integer.parseInt(raw);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }

        private static long readLong(String raw, long fallback) {
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }

        private static PlayerBehavior parseLabel(String raw) {
            try {
                return PlayerBehavior.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return PlayerBehavior.UNKNOWN;
            }
        }
    }
}
