package com.durkz.leancore.intelligence;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.memory.MemoryTier;
import com.durkz.leancore.memory.ServerContextTracker;

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

    static final int SCHEMA_VERSION = 6;
    private static final String STATE_FILE = "learning.state";

    private final Path dataDir;
    private final LeanCoreConfig config;
    private final Map<UUID, PersistedPlayer> players = new ConcurrentHashMap<>();
    private final RollingHeapTracker heapWindows = new RollingHeapTracker();
    private final ServerContextTracker serverContext;
    private final PolicyBandit policyBandit;
    private final FalseCutTracker falseCutTracker;
    private final OutcomeTracker outcomeTracker;
    private final OnlineLinearDemandModel demandModel;
    private final ActivityClassifierModel activityClassifier;
    private final UnloadOutcomeTracker unloadOutcomeTracker;
    private final HoldoutCohortTracker holdoutCohort = new HoldoutCohortTracker();
    private final PolicyBlacklistTracker policyBlacklist = new PolicyBlacklistTracker();

    private volatile double regionalPressure;
    private MemoryTier lastTier = MemoryTier.COMFORT;
    private int flushCount;
    private volatile long stateGeneration;
    private volatile long flushedGeneration;

    public LearningStore(Path dataDir, LeanCoreConfig config) {
        this.dataDir = dataDir;
        this.config = config;
        this.serverContext = new ServerContextTracker(config);
        this.policyBandit = new PolicyBandit();
        this.falseCutTracker = new FalseCutTracker();
        this.outcomeTracker = new OutcomeTracker(policyBandit, falseCutTracker);
        this.demandModel = new OnlineLinearDemandModel();
        this.activityClassifier = new ActivityClassifierModel();
        this.unloadOutcomeTracker = new UnloadOutcomeTracker();
        load();
    }

    public ServerContextTracker serverContext() {
        return serverContext;
    }

    public PolicyBandit policyBandit() {
        return policyBandit;
    }

    public OutcomeTracker outcomeTracker() {
        return outcomeTracker;
    }

    public FalseCutTracker falseCutTracker() {
        return falseCutTracker;
    }

    public DemandModel demandModel() {
        return demandModel;
    }

    public OnlineLinearDemandModel linearDemandModel() {
        return demandModel;
    }

    public ActivityClassifierModel activityClassifier() {
        return activityClassifier;
    }

    public UnloadOutcomeTracker unloadOutcomeTracker() {
        return unloadOutcomeTracker;
    }

    public HoldoutCohortTracker holdoutCohort() {
        return holdoutCohort;
    }

    public PolicyBlacklistTracker policyBlacklist() {
        return policyBlacklist;
    }

    public void setRegionalPressure(double pressure) {
        regionalPressure = Math.max(0.0D, Math.min(1.0D, pressure));
    }

    public double regionalPressure() {
        return regionalPressure;
    }

    public void reinforceDemandOnReward(
            double reward,
            Map<UUID, RetentionDemand> demands,
            Map<UUID, PlayerFeatureState> features,
            long nowMs
    ) {
        if (!config.learningEnabled || reward <= 0.0D || demands == null || features == null) {
            return;
        }
        for (Map.Entry<UUID, RetentionDemand> entry : demands.entrySet()) {
            if (HoldoutSet.isHoldout(entry.getKey())) {
                continue;
            }
            PlayerFeatureState state = features.get(entry.getKey());
            if (state == null) {
                continue;
            }
            demandModel.onOutcome(entry.getKey(), state, entry.getValue().demand(), reward, nowMs);
        }
        markDirty();
    }

    public void hydratePlayer(PlayerFeatureState state) {
        PersistedPlayer saved = players.get(state.playerId());
        if (saved == null) {
            return;
        }
        applyPersistedFeatures(state, saved);
    }

    public void savePlayerFeatures(PlayerFeatureState state) {
        PersistedPlayer prior = players.getOrDefault(state.playerId(), PersistedPlayer.empty());
        players.put(state.playerId(), prior.withFeatures(state));
        markDirty();
    }

    public void noteDemands(Map<UUID, RetentionDemand> demands) {
        for (Map.Entry<UUID, RetentionDemand> e : demands.entrySet()) {
            PersistedPlayer prior = players.getOrDefault(e.getKey(), PersistedPlayer.empty());
            RetentionDemand demand = e.getValue();
            players.put(e.getKey(), prior.withDemand(demand));
        }
        markDirty();
    }

    public void markDirty() {
        stateGeneration++;
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
        applyPersistedFeatures(state, saved);
        return state;
    }

    private static void applyPersistedFeatures(PlayerFeatureState state, PersistedPlayer saved) {
        state.hydrate(
                saved.movement60,
                saved.breaks60,
                saved.places60,
                saved.zones60,
                saved.movement15m,
                saved.breaks15m,
                saved.places15m,
                saved.zones15m,
                saved.chunks60,
                saved.chunks15m,
                saved.mine60,
                saved.wood60,
                saved.farm60,
                saved.build60,
                saved.craft60,
                saved.combat60,
                saved.observedSec * 1000L
        );
    }

    public void noteTier(MemoryTier tier) {
        lastTier = tier;
    }

    public void noteHeap(double heapRatio) {
        if (!config.learningEnabled) {
            return;
        }
        heapWindows.add(heapRatio, System.currentTimeMillis());
        markDirty();
    }

    public void flush() {
        if (!config.learningEnabled) {
            return;
        }
        if (stateGeneration == flushedGeneration) {
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
        props.setProperty("learn.completed", Integer.toString(outcomeTracker.completed()));
        props.setProperty("learn.discarded", Integer.toString(outcomeTracker.discarded()));
        props.setProperty("learn.falseCuts", Integer.toString(falseCutTracker.sessionCuts()));
        props.setProperty("features.schema", Integer.toString(FeatureSchema.VERSION));
        props.setProperty("demand.updates", Integer.toString(demandModel.updates()));
        props.setProperty("unload.policy", Integer.toString(unloadOutcomeTracker.policyUnloads()));
        props.setProperty("unload.engine", Integer.toString(unloadOutcomeTracker.engineUnloads()));
        for (int i = 0; i < FeatureSchema.DEMAND_DIM; i++) {
            props.setProperty("demand.w." + i, Double.toString(demandModel.weights()[i]));
        }
        writeActivityModel(props);
        props.setProperty("server.heap.q50", formatRatio(serverContext.q50()));
        props.setProperty("server.heap.q75", formatRatio(serverContext.q75()));
        props.setProperty("server.heap.q90", formatRatio(serverContext.q90()));
        props.setProperty("server.heap.q97", formatRatio(serverContext.q97()));
        props.setProperty("server.heap.samples", Integer.toString(serverContext.sampleCount()));

        writeBandit(props);
        writeBlacklist(props, now);
        for (Map.Entry<UUID, PersistedPlayer> e : players.entrySet()) {
            writePlayer(props, e.getKey(), e.getValue());
        }

        try {
            Files.createDirectories(dataDir);
            Path target = dataDir.resolve(STATE_FILE);
            try (OutputStream out = Files.newOutputStream(target)) {
                props.store(out, "LeanCore learning v6");
            }
            flushCount++;
            flushedGeneration = stateGeneration;
        } catch (IOException ignored) {
        }
    }

    public String statusLine() {
        long now = System.currentTimeMillis();
        return String.format(Locale.ROOT,
                "learning=v6 enabled=%s flushes=%d players=%d tier=%s heap60s=%.0f%% eval=%d discard=%d falseCuts=%d blacklist=%d",
                config.learningEnabled,
                flushCount,
                players.size(),
                lastTier,
                heapWindows.avg60s(now) * 100.0D,
                outcomeTracker.completed(),
                outcomeTracker.discarded(),
                falseCutTracker.sessionCuts(),
                policyBlacklist.activeCount(now));
    }

    public String mlStatusLine() {
        return FeatureSchema.versionLine()
                + " | " + demandModel.statusLine()
                + " | activityUpdates=" + activityClassifier.updates()
                + " | banditCtx=v1 dim=" + PolicyBandit.CONTEXT_DIM;
    }

    public String holdoutStatusLine() {
        return holdoutCohort.statusLine(System.currentTimeMillis());
    }

    public String windowLine() {
        long now = System.currentTimeMillis();
        return String.format(Locale.ROOT, "windows 60s=%.0f%% 15m=%.0f%% 24h=%.0f%%",
                heapWindows.avg60s(now) * 100.0D,
                heapWindows.avg15m(now) * 100.0D,
                heapWindows.avg24h(now) * 100.0D);
    }

    public String serverLine() {
        return String.format(Locale.ROOT,
                "server q50=%.0f%% q75=%.0f%% q90=%.0f%% q97=%.0f%% samples=%d",
                serverContext.q50() * 100.0D,
                serverContext.q75() * 100.0D,
                serverContext.q90() * 100.0D,
                serverContext.q97() * 100.0D,
                serverContext.sampleCount());
    }

    public double heapAvg60s() {
        return heapWindows.avg60s(System.currentTimeMillis());
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

        int schema = readInt(props, "schema", 0);
        if (schema < 2) {
            return;
        }

        loadPlayers(props);
        if (schema >= 3) {
            serverContext.hydrate(
                    readDouble(props, "server.heap.q50", 0.0D),
                    readDouble(props, "server.heap.q75", 0.0D),
                    readDouble(props, "server.heap.q90", 0.0D),
                    readDouble(props, "server.heap.q97", 0.0D)
            );
            loadBandit(props);
            loadDemandModel(props);
            unloadOutcomeTracker.hydrate(
                    readInt(props, "unload.policy", 0),
                    readInt(props, "unload.engine", 0)
            );
        }
        if (schema >= 6) {
            loadActivityModel(props);
        }
        if (schema >= 5) {
            loadBlacklist(props, System.currentTimeMillis());
        }
    }

    private void writeActivityModel(Properties props) {
        props.setProperty("activity.updates", Integer.toString(activityClassifier.updates()));
        double[][] weights = activityClassifier.weights();
        for (int c = 0; c < weights.length; c++) {
            for (int i = 0; i < ActivityFeatureEncoder.DIM; i++) {
                props.setProperty("activity.w." + c + "." + i, Double.toString(weights[c][i]));
            }
        }
    }

    private void loadActivityModel(Properties props) {
        int classes = PlayerBehavior.values().length;
        double[][] weights = new double[classes][ActivityFeatureEncoder.DIM];
        for (int c = 0; c < classes; c++) {
            for (int i = 0; i < ActivityFeatureEncoder.DIM; i++) {
                weights[c][i] = readDouble(props, "activity.w." + c + "." + i, 0.0D);
            }
        }
        activityClassifier.hydrate(weights, readInt(props, "activity.updates", 0));
    }

    private void loadDemandModel(Properties props) {
        double[] weights = new double[FeatureSchema.DEMAND_DIM];
        for (int i = 0; i < FeatureSchema.DEMAND_DIM; i++) {
            weights[i] = readDouble(props, "demand.w." + i, weights[i]);
        }
        demandModel.hydrate(weights, readInt(props, "demand.updates", 0));
    }

    private void loadPlayers(Properties props) {
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

    private void loadBandit(Properties props) {
        for (String key : props.stringPropertyNames()) {
            if (!key.startsWith("bandit.")) {
                continue;
            }
            String suffix = key.substring("bandit.".length());
            int dot = suffix.lastIndexOf('.');
            if (dot <= 0) {
                continue;
            }
            String armKey = suffix.substring(0, dot);
            String field = suffix.substring(dot + 1);
            PolicyBandit.ArmState arm = policyBandit.arms().computeIfAbsent(armKey, ignored -> new PolicyBandit.ArmState());
            switch (field) {
                case "pulls" -> arm.pulls = readInt(props, key, 0);
                case "rewardSum" -> arm.rewardSum = readDouble(props, key, 0.0D);
                default -> {
                    if (field.startsWith("a.") && field.length() == 3) {
                        int idx = field.charAt(2) - '0';
                        if (idx >= 0 && idx < PolicyBandit.CONTEXT_DIM) {
                            arm.aDiag[idx] = readDouble(props, key, 1.0D);
                        }
                    } else if (field.startsWith("b.") && field.length() == 3) {
                        int idx = field.charAt(2) - '0';
                        if (idx >= 0 && idx < PolicyBandit.CONTEXT_DIM) {
                            arm.b[idx] = readDouble(props, key, 0.0D);
                        }
                    }
                }
            }
        }
    }

    private void writeBlacklist(Properties props, long nowMs) {
        for (Map.Entry<String, Long> entry : policyBlacklist.snapshotActive(nowMs).entrySet()) {
            props.setProperty("blacklist." + entry.getKey() + ".untilMs", Long.toString(entry.getValue()));
        }
    }

    private void loadBlacklist(Properties props, long nowMs) {
        Map<String, Long> entries = new java.util.HashMap<>();
        for (String key : props.stringPropertyNames()) {
            if (!key.startsWith("blacklist.")) {
                continue;
            }
            String suffix = key.substring("blacklist.".length());
            if (!suffix.endsWith(".untilMs")) {
                continue;
            }
            String policyKey = suffix.substring(0, suffix.length() - ".untilMs".length());
            long untilMs = readLong(props, key, 0L);
            if (untilMs > nowMs) {
                entries.put(policyKey, untilMs);
            }
        }
        policyBlacklist.hydrate(entries, nowMs);
    }

    private void writeBandit(Properties props) {
        for (Map.Entry<String, PolicyBandit.ArmState> e : policyBandit.arms().entrySet()) {
            String prefix = "bandit." + e.getKey() + ".";
            PolicyBandit.ArmState arm = e.getValue();
            props.setProperty(prefix + "pulls", Integer.toString(arm.pulls));
            props.setProperty(prefix + "rewardSum", Double.toString(arm.rewardSum));
            for (int i = 0; i < PolicyBandit.CONTEXT_DIM; i++) {
                props.setProperty(prefix + "a." + i, Double.toString(arm.aDiag[i]));
                props.setProperty(prefix + "b." + i, Double.toString(arm.b[i]));
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
        props.setProperty(prefix + "chunks60", Double.toString(player.chunks60));
        props.setProperty(prefix + "chunks15m", Double.toString(player.chunks15m));
        props.setProperty(prefix + "mine60", Double.toString(player.mine60));
        props.setProperty(prefix + "wood60", Double.toString(player.wood60));
        props.setProperty(prefix + "farm60", Double.toString(player.farm60));
        props.setProperty(prefix + "build60", Double.toString(player.build60));
        props.setProperty(prefix + "craft60", Double.toString(player.craft60));
        props.setProperty(prefix + "combat60", Double.toString(player.combat60));
        props.setProperty(prefix + "holdout", Boolean.toString(HoldoutSet.isHoldout(id)));
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

    private static long readLong(Properties props, String key, long fallback) {
        String raw = props.getProperty(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String formatRatio(double ratio) {
        return String.format(Locale.ROOT, "%.4f", ratio);
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
            double chunks60,
            double chunks15m,
            double mine60,
            double wood60,
            double farm60,
            double build60,
            double craft60,
            double combat60,
            long observedSec
    ) {
        static PersistedPlayer empty() {
            return new PersistedPlayer(
                    0.5D, 0.0D, RetentionDemand.PRIOR_MB, PlayerBehavior.UNKNOWN,
                    0.0D, 0.0D, 0.0D, 0.0D,
                    0.0D, 0.0D, 0.0D, 0.0D,
                    0.0D, 0.0D,
                    0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D,
                    0L
            );
        }

        PersistedPlayer withFeatures(PlayerFeatureState state) {
            return new PersistedPlayer(
                    demand, confidence, retentionMb, debugLabel,
                    state.emaMovement60(), state.emaBreaks60(), state.emaPlaces60(), state.emaZones60(),
                    state.emaMovement15m(), state.emaBreaks15m(), state.emaPlaces15m(), state.emaZones15m(),
                    state.emaChunks60(), state.emaChunks15m(),
                    state.emaMine60(), state.emaWood60(), state.emaFarm60(),
                    state.emaBuild60(), state.emaCraft60(), state.emaCombat60(),
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
                    chunks60,
                    chunks15m,
                    mine60,
                    wood60,
                    farm60,
                    build60,
                    craft60,
                    combat60,
                    observedSec
            );
        }

        PersistedPlayer withField(String field, String raw) {
            if (raw == null) {
                return this;
            }
            return switch (field) {
                case "demand" -> new PersistedPlayer(readDouble(raw, demand), confidence, retentionMb, debugLabel,
                        movement60, breaks60, places60, zones60, movement15m, breaks15m, places15m, zones15m, chunks60, chunks15m, mine60, wood60, farm60, build60, craft60, combat60, observedSec);
                case "confidence" -> new PersistedPlayer(demand, readDouble(raw, confidence), retentionMb, debugLabel,
                        movement60, breaks60, places60, zones60, movement15m, breaks15m, places15m, zones15m, chunks60, chunks15m, mine60, wood60, farm60, build60, craft60, combat60, observedSec);
                case "retentionMb" -> new PersistedPlayer(demand, confidence, readInt(raw, retentionMb), debugLabel,
                        movement60, breaks60, places60, zones60, movement15m, breaks15m, places15m, zones15m, chunks60, chunks15m, mine60, wood60, farm60, build60, craft60, combat60, observedSec);
                case "label" -> new PersistedPlayer(demand, confidence, retentionMb, parseLabel(raw),
                        movement60, breaks60, places60, zones60, movement15m, breaks15m, places15m, zones15m, chunks60, chunks15m, mine60, wood60, farm60, build60, craft60, combat60, observedSec);
                case "movement60" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        readDouble(raw, movement60), breaks60, places60, zones60, movement15m, breaks15m, places15m, zones15m, chunks60, chunks15m, mine60, wood60, farm60, build60, craft60, combat60, observedSec);
                case "breaks60" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, readDouble(raw, breaks60), places60, zones60, movement15m, breaks15m, places15m, zones15m, chunks60, chunks15m, mine60, wood60, farm60, build60, craft60, combat60, observedSec);
                case "places60" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, breaks60, readDouble(raw, places60), zones60, movement15m, breaks15m, places15m, zones15m, chunks60, chunks15m, mine60, wood60, farm60, build60, craft60, combat60, observedSec);
                case "zones60" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, breaks60, places60, readDouble(raw, zones60), movement15m, breaks15m, places15m, zones15m, chunks60, chunks15m, mine60, wood60, farm60, build60, craft60, combat60, observedSec);
                case "movement15m" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, breaks60, places60, zones60, readDouble(raw, movement15m), breaks15m, places15m, zones15m, chunks60, chunks15m, mine60, wood60, farm60, build60, craft60, combat60, observedSec);
                case "breaks15m" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, breaks60, places60, zones60, movement15m, readDouble(raw, breaks15m), places15m, zones15m, chunks60, chunks15m, mine60, wood60, farm60, build60, craft60, combat60, observedSec);
                case "places15m" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, breaks60, places60, zones60, movement15m, breaks15m, readDouble(raw, places15m), zones15m, chunks60, chunks15m, mine60, wood60, farm60, build60, craft60, combat60, observedSec);
                case "zones15m" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, breaks60, places60, zones60, movement15m, breaks15m, places15m, readDouble(raw, zones15m), chunks60, chunks15m, mine60, wood60, farm60, build60, craft60, combat60, observedSec);
                case "chunks60" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, breaks60, places60, zones60, movement15m, breaks15m, places15m, zones15m,
                        readDouble(raw, chunks60), chunks15m, mine60, wood60, farm60, build60, craft60, combat60, observedSec);
                case "chunks15m" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, breaks60, places60, zones60, movement15m, breaks15m, places15m, zones15m,
                        chunks60, readDouble(raw, chunks15m), mine60, wood60, farm60, build60, craft60, combat60, observedSec);
                case "observedSec" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, breaks60, places60, zones60, movement15m, breaks15m, places15m, zones15m, chunks60, chunks15m, mine60, wood60, farm60, build60, craft60, combat60, readLong(raw, observedSec));
                case "mine60" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, breaks60, places60, zones60, movement15m, breaks15m, places15m, zones15m, chunks60, chunks15m,
                        readDouble(raw, mine60), wood60, farm60, build60, craft60, combat60, observedSec);
                case "wood60" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, breaks60, places60, zones60, movement15m, breaks15m, places15m, zones15m, chunks60, chunks15m,
                        mine60, readDouble(raw, wood60), farm60, build60, craft60, combat60, observedSec);
                case "farm60" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, breaks60, places60, zones60, movement15m, breaks15m, places15m, zones15m, chunks60, chunks15m,
                        mine60, wood60, readDouble(raw, farm60), build60, craft60, combat60, observedSec);
                case "build60" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, breaks60, places60, zones60, movement15m, breaks15m, places15m, zones15m, chunks60, chunks15m,
                        mine60, wood60, farm60, readDouble(raw, build60), craft60, combat60, observedSec);
                case "craft60" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, breaks60, places60, zones60, movement15m, breaks15m, places15m, zones15m, chunks60, chunks15m,
                        mine60, wood60, farm60, build60, readDouble(raw, craft60), combat60, observedSec);
                case "combat60" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, breaks60, places60, zones60, movement15m, breaks15m, places15m, zones15m, chunks60, chunks15m,
                        mine60, wood60, farm60, build60, craft60, readDouble(raw, combat60), observedSec);
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
