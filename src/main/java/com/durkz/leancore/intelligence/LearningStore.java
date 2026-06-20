package com.durkz.leancore.intelligence;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.diagnostics.DiagnosticLog;
import com.durkz.leancore.dormancy.ZoneReuseModel;
import com.durkz.leancore.memory.MemoryTier;
import com.durkz.leancore.memory.ServerContextTracker;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class LearningStore {

    @FunctionalInterface
    public interface PersistListener {
        void onWarning(String message, Throwable cause);
    }

    static final int SCHEMA_VERSION = 8;
    private static final String STATE_FILE_LEGACY = "learning.state";
    private static final long HEAP_DIRTY_INTERVAL_MS = 60_000L;
    private static final int MIN_PERSISTED_ZONE_VISITS = 2;

    private final Path dataDir;
    private final LeanCoreConfig config;
    private final PersistListener persistListener;
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
    private final ZoneReuseModel zoneReuseModel = new ZoneReuseModel();

    private volatile double regionalPressure;
    private MemoryTier lastTier = MemoryTier.COMFORT;
    private int flushCount;
    private int persistFailCount;
    private int lastPrunedCount;
    private long lastStateFileBytes;
    private long lastFlushAtMs;
    private long lastHeapDirtyMs;
    private volatile long stateGeneration;
    private volatile long flushedGeneration;
    // flush() is reachable from the scheduler, disconnect and shutdown threads.
    private final ReentrantLock flushLock = new ReentrantLock();

    public LearningStore(Path dataDir, LeanCoreConfig config) {
        this(dataDir, config, null);
    }

    public LearningStore(Path dataDir, LeanCoreConfig config, PersistListener persistListener) {
        this.dataDir = dataDir;
        this.config = config;
        this.persistListener = persistListener;
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

    public ZoneReuseModel zoneReuseModel() {
        return zoneReuseModel;
    }

    public void setRegionalPressure(double pressure) {
        regionalPressure = Math.max(0.0D, Math.min(1.0D, pressure));
    }

    public double regionalPressure() {
        return regionalPressure;
    }

    public boolean persistenceEnabled() {
        return config.learningEnabled || config.liteLearningEnabled;
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
        long nowMs = System.currentTimeMillis();
        PersistedPlayer prior = players.getOrDefault(state.playerId(), PersistedPlayer.empty());
        players.put(state.playerId(), prior.withFeatures(state, nowMs));
        markDirty();
    }

    public void noteDemands(Map<UUID, RetentionDemand> demands) {
        long nowMs = System.currentTimeMillis();
        for (Map.Entry<UUID, RetentionDemand> e : demands.entrySet()) {
            PersistedPlayer prior = players.getOrDefault(e.getKey(), PersistedPlayer.empty());
            RetentionDemand demand = e.getValue();
            players.put(e.getKey(), prior.withDemand(demand, nowMs));
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
        if (!persistenceEnabled()) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        heapWindows.add(heapRatio, nowMs);
        if (nowMs - lastHeapDirtyMs >= HEAP_DIRTY_INTERVAL_MS) {
            lastHeapDirtyMs = nowMs;
            markDirty();
        }
    }

    public void flush() {
        flush(false, Set.of());
    }

    public void flush(boolean force, Set<UUID> retainOnline) {
        if (!persistenceEnabled()) {
            return;
        }
        if (!force && stateGeneration == flushedGeneration) {
            return;
        }
        flushLock.lock();
        try {
            if (!force && stateGeneration == flushedGeneration) {
                return;
            }
            long now = System.currentTimeMillis();
            lastPrunedCount = prunePlayers(now, retainOnline);
            pruneZones(now);
            LearningStateCodec.Snapshot snapshot = buildSnapshot(now);

            try {
                Files.createDirectories(dataDir);
                Path target = dataDir.resolve(LearningStateCodec.GZ_FILE);
                Path temp = dataDir.resolve(LearningStateCodec.GZ_FILE + "." + System.nanoTime() + ".tmp");
                try (OutputStream out = Files.newOutputStream(temp)) {
                    LearningStateCodec.writeTo(out, snapshot);
                }
                atomicMove(temp, target);
                Path legacy = dataDir.resolve(STATE_FILE_LEGACY);
                Files.deleteIfExists(legacy);
                lastStateFileBytes = Files.size(target);
                flushCount++;
                flushedGeneration = stateGeneration;
                lastFlushAtMs = now;
                DiagnosticLog.info(String.format(Locale.ROOT,
                        "persist: players=%d zones=%d pruned=%d size=%dB (v%d)",
                        players.size(), zoneReuseModel.size(), lastPrunedCount,
                        lastStateFileBytes, SCHEMA_VERSION));
            } catch (IOException ex) {
                persistFailCount++;
                warnPersist("learning flush failed", ex);
            }
        } finally {
            flushLock.unlock();
        }
    }

    private static void atomicMove(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private int prunePlayers(long nowMs, Set<UUID> retainOnline) {
        int before = players.size();
        long ttlMs = config.learningPlayerTtlDays > 0
                ? config.learningPlayerTtlDays * 86_400_000L
                : Long.MAX_VALUE;
        long cutoff = nowMs - ttlMs;

        players.entrySet().removeIf(e -> {
            UUID id = e.getKey();
            if (retainOnline != null && retainOnline.contains(id)) {
                return false;
            }
            return e.getValue().lastSeenMs > 0L && e.getValue().lastSeenMs < cutoff;
        });

        int max = config.learningMaxPersistedPlayers;
        if (max > 0 && players.size() > max) {
            List<Map.Entry<UUID, PersistedPlayer>> ranked = new ArrayList<>(players.entrySet());
            ranked.sort(Comparator
                    .comparingLong((Map.Entry<UUID, PersistedPlayer> e) -> e.getValue().lastSeenMs)
                    .thenComparingLong(e -> e.getValue().observedSec));
            for (Map.Entry<UUID, PersistedPlayer> entry : ranked) {
                if (players.size() <= max) {
                    break;
                }
                UUID id = entry.getKey();
                if (retainOnline != null && retainOnline.contains(id)) {
                    continue;
                }
                players.remove(id);
            }
        }
        return Math.max(0, before - players.size());
    }

    private void pruneZones(long nowMs) {
        long ttlMs = config.zoneReuseTtlDays > 0
                ? config.zoneReuseTtlDays * 86_400_000L
                : 0L;
        zoneReuseModel.prune(nowMs, ttlMs, config.zoneReuseMaxPersistedZones);
    }

    private LearningStateCodec.Snapshot buildSnapshot(long nowMs) {
        Map<String, LearningStateCodec.BanditArm> banditArms = new LinkedHashMap<>();
        for (Map.Entry<String, PolicyBandit.ArmState> e : policyBandit.arms().entrySet()) {
            LearningStateCodec.BanditArm arm = new LearningStateCodec.BanditArm();
            PolicyBandit.ArmState src = e.getValue();
            arm.pulls = src.pulls;
            arm.rewardSum = src.rewardSum;
            System.arraycopy(src.aDiag, 0, arm.aDiag, 0, PolicyBandit.CONTEXT_DIM);
            System.arraycopy(src.b, 0, arm.b, 0, PolicyBandit.CONTEXT_DIM);
            banditArms.put(e.getKey(), arm);
        }

        Map<UUID, LearningStateCodec.PlayerRecord> playerRecords = new LinkedHashMap<>();
        for (Map.Entry<UUID, PersistedPlayer> e : players.entrySet()) {
            playerRecords.put(e.getKey(), e.getValue().toRecord());
        }

        return new LearningStateCodec.Snapshot(
                nowMs,
                lastTier,
                regionalPressure,
                outcomeTracker.completed(),
                outcomeTracker.discarded(),
                falseCutTracker.sessionCuts(),
                demandModel.updates(),
                activityClassifier.updates(),
                unloadOutcomeTracker.policyUnloads(),
                unloadOutcomeTracker.engineUnloads(),
                heapWindows.avg60s(nowMs),
                heapWindows.avg15m(nowMs),
                heapWindows.avg24h(nowMs),
                serverContext.q50(),
                serverContext.q75(),
                serverContext.q90(),
                serverContext.q97(),
                serverContext.sampleCount(),
                demandModel.weights().clone(),
                activityClassifier.weights(),
                banditArms,
                policyBlacklist.snapshotActive(nowMs),
                playerRecords,
                zoneReuseModel.export(MIN_PERSISTED_ZONE_VISITS)
        );
    }

    private void applySnapshot(LearningStateCodec.Snapshot snapshot, long nowMs) {
        lastTier = snapshot.lastTier();
        regionalPressure = snapshot.regionalPressure();
        serverContext.hydrate(
                snapshot.serverQ50(),
                snapshot.serverQ75(),
                snapshot.serverQ90(),
                snapshot.serverQ97()
        );
        demandModel.hydrate(snapshot.demandWeights(), snapshot.demandUpdates());
        activityClassifier.hydrate(snapshot.activityWeights(), snapshot.activityUpdates());
        unloadOutcomeTracker.hydrate(snapshot.unloadPolicy(), snapshot.unloadEngine());
        policyBlacklist.hydrate(snapshot.blacklist(), nowMs);

        policyBandit.arms().clear();
        for (Map.Entry<String, LearningStateCodec.BanditArm> e : snapshot.banditArms().entrySet()) {
            PolicyBandit.ArmState arm = policyBandit.arms().computeIfAbsent(e.getKey(), ignored -> new PolicyBandit.ArmState());
            LearningStateCodec.BanditArm src = e.getValue();
            arm.pulls = src.pulls;
            arm.rewardSum = src.rewardSum;
            System.arraycopy(src.aDiag, 0, arm.aDiag, 0, PolicyBandit.CONTEXT_DIM);
            System.arraycopy(src.b, 0, arm.b, 0, PolicyBandit.CONTEXT_DIM);
        }

        players.clear();
        for (Map.Entry<UUID, LearningStateCodec.PlayerRecord> e : snapshot.players().entrySet()) {
            players.put(e.getKey(), PersistedPlayer.fromRecord(e.getValue()));
        }

        zoneReuseModel.clear();
        if (snapshot.zones() != null) {
            for (ZoneReuseModel.Record record : snapshot.zones()) {
                zoneReuseModel.importRecord(record);
            }
        }
    }

    public String statusLine() {
        long now = System.currentTimeMillis();
        return String.format(Locale.ROOT,
                "learning=v8 enabled=%s lite=%s flushes=%d flushErr=%d lastFlush=%s players=%d zones=%d state=%s pruned=%d tier=%s heap60s=%.0f%% eval=%d discard=%d falseCuts=%d blacklist=%d",
                config.learningEnabled,
                config.liteLearningEnabled,
                flushCount,
                persistFailCount,
                formatLastFlush(now),
                players.size(),
                zoneReuseModel.size(),
                formatStateSize(lastStateFileBytes),
                lastPrunedCount,
                lastTier,
                heapWindows.avg60s(now) * 100.0D,
                outcomeTracker.completed(),
                outcomeTracker.discarded(),
                falseCutTracker.sessionCuts(),
                policyBlacklist.activeCount(now));
    }

    private String formatLastFlush(long nowMs) {
        if (lastFlushAtMs <= 0L) {
            return "never";
        }
        long ageSec = Math.max(0L, (nowMs - lastFlushAtMs) / 1000L);
        if (ageSec < 120L) {
            return ageSec + "s ago";
        }
        if (ageSec < 7200L) {
            return (ageSec / 60L) + "m ago";
        }
        return (ageSec / 3600L) + "h ago";
    }

    private void warnPersist(String message, Throwable cause) {
        if (persistListener == null) {
            return;
        }
        persistListener.onWarning(message, cause);
    }

    private static String formatStateSize(long bytes) {
        if (bytes <= 0L) {
            return "n/a";
        }
        if (bytes < 1024L) {
            return bytes + "B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1fKB", bytes / 1024.0D);
        }
        return String.format(Locale.ROOT, "%.2fMB", bytes / (1024.0D * 1024.0D));
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
        long nowMs = System.currentTimeMillis();
        Path gzip = dataDir.resolve(LearningStateCodec.GZ_FILE);
        if (Files.isRegularFile(gzip)) {
            try (InputStream in = Files.newInputStream(gzip)) {
                applySnapshot(LearningStateCodec.readFrom(in), nowMs);
                lastStateFileBytes = Files.size(gzip);
                return;
            } catch (IOException ex) {
                warnPersist("learning state load failed; trying legacy", ex);
                quarantineCorruptState(gzip);
            } catch (RuntimeException ex) {
                warnPersist("learning state corrupt; trying legacy", ex);
                quarantineCorruptState(gzip);
            }
        }
        loadLegacyProperties(nowMs);
    }

    private void quarantineCorruptState(Path gzip) {
        if (gzip == null || !Files.isRegularFile(gzip)) {
            return;
        }
        try {
            Path quarantine = dataDir.resolve(
                    LearningStateCodec.GZ_FILE + ".corrupt." + System.currentTimeMillis());
            Files.move(gzip, quarantine, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    private void loadLegacyProperties(long nowMs) {
        Path target = dataDir.resolve(STATE_FILE_LEGACY);
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

        loadPlayers(props, nowMs);
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
        if (schema >= 4 && props.containsKey("activity.w.0.0")) {
            loadActivityModel(props);
        }
        if (schema >= 5) {
            loadBlacklist(props, nowMs);
        }
        try {
            lastStateFileBytes = Files.size(target);
        } catch (IOException ignored) {
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

    private void loadPlayers(Properties props, long nowMs) {
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
                    PersistedPlayer base = prior == null ? PersistedPlayer.empty(nowMs) : prior;
                    PersistedPlayer next = base.withField(field, props.getProperty(key));
                    if (next.lastSeenMs <= 0L) {
                        next = next.withLastSeen(nowMs);
                    }
                    return next;
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
            long observedSec,
            long lastSeenMs
    ) {
        static PersistedPlayer empty() {
            return empty(System.currentTimeMillis());
        }

        static PersistedPlayer empty(long nowMs) {
            return new PersistedPlayer(
                    0.5D, 0.0D, RetentionDemand.PRIOR_MB, PlayerBehavior.UNKNOWN,
                    0.0D, 0.0D, 0.0D, 0.0D,
                    0.0D, 0.0D, 0.0D, 0.0D,
                    0.0D, 0.0D,
                    0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D,
                    0L, nowMs
            );
        }

        static PersistedPlayer fromRecord(LearningStateCodec.PlayerRecord record) {
            return new PersistedPlayer(
                    record.demand(),
                    record.confidence(),
                    record.retentionMb(),
                    record.debugLabel(),
                    record.movement60(),
                    record.breaks60(),
                    record.places60(),
                    record.zones60(),
                    record.movement15m(),
                    record.breaks15m(),
                    record.places15m(),
                    record.zones15m(),
                    record.chunks60(),
                    record.chunks15m(),
                    record.mine60(),
                    record.wood60(),
                    record.farm60(),
                    record.build60(),
                    record.craft60(),
                    record.combat60(),
                    record.observedSec(),
                    record.lastSeenMs()
            );
        }

        LearningStateCodec.PlayerRecord toRecord() {
            return new LearningStateCodec.PlayerRecord(
                    demand, confidence, retentionMb, debugLabel,
                    movement60, breaks60, places60, zones60,
                    movement15m, breaks15m, places15m, zones15m,
                    chunks60, chunks15m,
                    mine60, wood60, farm60, build60, craft60, combat60,
                    observedSec, lastSeenMs
            );
        }

        PersistedPlayer withFeatures(PlayerFeatureState state, long nowMs) {
            return new PersistedPlayer(
                    demand, confidence, retentionMb, debugLabel,
                    state.emaMovement60(), state.emaBreaks60(), state.emaPlaces60(), state.emaZones60(),
                    state.emaMovement15m(), state.emaBreaks15m(), state.emaPlaces15m(), state.emaZones15m(),
                    state.emaChunks60(), state.emaChunks15m(),
                    state.emaMine60(), state.emaWood60(), state.emaFarm60(),
                    state.emaBuild60(), state.emaCraft60(), state.emaCombat60(),
                    state.observedSec(),
                    nowMs
            );
        }

        PersistedPlayer withDemand(RetentionDemand demand, long nowMs) {
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
                    observedSec,
                    nowMs
            );
        }

        PersistedPlayer withLastSeen(long nowMs) {
            return new PersistedPlayer(
                    demand, confidence, retentionMb, debugLabel,
                    movement60, breaks60, places60, zones60,
                    movement15m, breaks15m, places15m, zones15m,
                    chunks60, chunks15m,
                    mine60, wood60, farm60, build60, craft60, combat60,
                    observedSec, nowMs
            );
        }

        PersistedPlayer withField(String field, String raw) {
            if (raw == null) {
                return this;
            }
            return switch (field) {
                case "demand" -> new PersistedPlayer(readDouble(raw, demand), confidence, retentionMb, debugLabel,
                        movement60, breaks60, places60, zones60, movement15m, breaks15m, places15m, zones15m, chunks60, chunks15m, mine60, wood60, farm60, build60, craft60, combat60, observedSec, lastSeenMs);
                case "confidence" -> new PersistedPlayer(demand, readDouble(raw, confidence), retentionMb, debugLabel,
                        movement60, breaks60, places60, zones60, movement15m, breaks15m, places15m, zones15m, chunks60, chunks15m, mine60, wood60, farm60, build60, craft60, combat60, observedSec, lastSeenMs);
                case "retentionMb" -> new PersistedPlayer(demand, confidence, readInt(raw, retentionMb), debugLabel,
                        movement60, breaks60, places60, zones60, movement15m, breaks15m, places15m, zones15m, chunks60, chunks15m, mine60, wood60, farm60, build60, craft60, combat60, observedSec, lastSeenMs);
                case "label" -> new PersistedPlayer(demand, confidence, retentionMb, parseLabel(raw),
                        movement60, breaks60, places60, zones60, movement15m, breaks15m, places15m, zones15m, chunks60, chunks15m, mine60, wood60, farm60, build60, craft60, combat60, observedSec, lastSeenMs);
                case "movement60" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        readDouble(raw, movement60), breaks60, places60, zones60, movement15m, breaks15m, places15m, zones15m, chunks60, chunks15m, mine60, wood60, farm60, build60, craft60, combat60, observedSec, lastSeenMs);
                case "breaks60" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, readDouble(raw, breaks60), places60, zones60, movement15m, breaks15m, places15m, zones15m, chunks60, chunks15m, mine60, wood60, farm60, build60, craft60, combat60, observedSec, lastSeenMs);
                case "places60" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, breaks60, readDouble(raw, places60), zones60, movement15m, breaks15m, places15m, zones15m, chunks60, chunks15m, mine60, wood60, farm60, build60, craft60, combat60, observedSec, lastSeenMs);
                case "zones60" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, breaks60, places60, readDouble(raw, zones60), movement15m, breaks15m, places15m, zones15m, chunks60, chunks15m, mine60, wood60, farm60, build60, craft60, combat60, observedSec, lastSeenMs);
                case "movement15m" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, breaks60, places60, zones60, readDouble(raw, movement15m), breaks15m, places15m, zones15m, chunks60, chunks15m, mine60, wood60, farm60, build60, craft60, combat60, observedSec, lastSeenMs);
                case "breaks15m" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, breaks60, places60, zones60, movement15m, readDouble(raw, breaks15m), places15m, zones15m, chunks60, chunks15m, mine60, wood60, farm60, build60, craft60, combat60, observedSec, lastSeenMs);
                case "places15m" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, breaks60, places60, zones60, movement15m, breaks15m, readDouble(raw, places15m), zones15m, chunks60, chunks15m, mine60, wood60, farm60, build60, craft60, combat60, observedSec, lastSeenMs);
                case "zones15m" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, breaks60, places60, zones60, movement15m, breaks15m, places15m, readDouble(raw, zones15m), chunks60, chunks15m, mine60, wood60, farm60, build60, craft60, combat60, observedSec, lastSeenMs);
                case "chunks60" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, breaks60, places60, zones60, movement15m, breaks15m, places15m, zones15m,
                        readDouble(raw, chunks60), chunks15m, mine60, wood60, farm60, build60, craft60, combat60, observedSec, lastSeenMs);
                case "chunks15m" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, breaks60, places60, zones60, movement15m, breaks15m, places15m, zones15m,
                        chunks60, readDouble(raw, chunks15m), mine60, wood60, farm60, build60, craft60, combat60, observedSec, lastSeenMs);
                case "observedSec" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, breaks60, places60, zones60, movement15m, breaks15m, places15m, zones15m, chunks60, chunks15m, mine60, wood60, farm60, build60, craft60, combat60, readLong(raw, observedSec), lastSeenMs);
                case "lastSeenMs" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, breaks60, places60, zones60, movement15m, breaks15m, places15m, zones15m, chunks60, chunks15m, mine60, wood60, farm60, build60, craft60, combat60, observedSec, readLong(raw, lastSeenMs));
                case "mine60" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, breaks60, places60, zones60, movement15m, breaks15m, places15m, zones15m, chunks60, chunks15m,
                        readDouble(raw, mine60), wood60, farm60, build60, craft60, combat60, observedSec, lastSeenMs);
                case "wood60" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, breaks60, places60, zones60, movement15m, breaks15m, places15m, zones15m, chunks60, chunks15m,
                        mine60, readDouble(raw, wood60), farm60, build60, craft60, combat60, observedSec, lastSeenMs);
                case "farm60" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, breaks60, places60, zones60, movement15m, breaks15m, places15m, zones15m, chunks60, chunks15m,
                        mine60, wood60, readDouble(raw, farm60), build60, craft60, combat60, observedSec, lastSeenMs);
                case "build60" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, breaks60, places60, zones60, movement15m, breaks15m, places15m, zones15m, chunks60, chunks15m,
                        mine60, wood60, farm60, readDouble(raw, build60), craft60, combat60, observedSec, lastSeenMs);
                case "craft60" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, breaks60, places60, zones60, movement15m, breaks15m, places15m, zones15m, chunks60, chunks15m,
                        mine60, wood60, farm60, build60, readDouble(raw, craft60), combat60, observedSec, lastSeenMs);
                case "combat60" -> new PersistedPlayer(demand, confidence, retentionMb, debugLabel,
                        movement60, breaks60, places60, zones60, movement15m, breaks15m, places15m, zones15m, chunks60, chunks15m,
                        mine60, wood60, farm60, build60, craft60, readDouble(raw, combat60), observedSec, lastSeenMs);
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
