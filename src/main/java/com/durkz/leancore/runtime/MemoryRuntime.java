package com.durkz.leancore.runtime;

import com.durkz.leancore.LeanCorePlugin;
import com.durkz.leancore.alert.CriticalWebhookNotifier;
import com.durkz.leancore.config.DedicatedBootstrap;
import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.diagnostics.DiagnosticLog;
import com.durkz.leancore.dormancy.ZoneChunkUnloader;
import com.durkz.leancore.dormancy.ZoneDormancyMap;
import com.durkz.leancore.dormancy.ZoneKey;
import com.durkz.leancore.dormancy.ZoneState;
import com.durkz.leancore.intelligence.BehaviorClassifier;
import com.durkz.leancore.intelligence.EngineUnloadPoller;
import com.durkz.leancore.intelligence.LearningStore;
import com.durkz.leancore.intelligence.LoadedChunkSetTracker;
import com.durkz.leancore.probe.RegionalEntityProbe;
import com.durkz.leancore.memory.GcHintScheduler;
import com.durkz.leancore.memory.GovernorStatus;
import com.durkz.leancore.memory.MemoryGovernor;
import com.durkz.leancore.memory.MemoryTier;
import com.durkz.leancore.memory.MemoryPressureSensor;
import com.durkz.leancore.memory.MemorySnapshot;
import com.durkz.leancore.memory.SessionSavingsTracker;
import com.durkz.leancore.memory.PolicyApplier;
import com.durkz.leancore.memory.RetentionAllocator;
import com.durkz.leancore.session.SessionMode;
import com.durkz.leancore.session.SessionModeDetector;
import com.durkz.leancore.probe.ChunkPrefetcher;
import com.durkz.leancore.probe.ChunkSaturationSampler;
import com.durkz.leancore.probe.RegionalPressureCache;
import com.durkz.leancore.ui.HudSessionStore;
import com.durkz.leancore.ui.MemoryHudService;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class MemoryRuntime {

    // Caps how long the sequential per-world fan-out may block the scheduler in one tick. Only trips
    // when worlds stall; once over, we stop dispatching the remaining worlds this tick.
    private static final long FAN_OUT_BUDGET_NANOS = 2_000L * 1_000_000L;

    private final LeanCorePlugin plugin;
    private final LeanCoreConfig config;
    private final MemoryPressureSensor sensor;
    private final ZoneDormancyMap dormancyMap;
    private final ZoneChunkUnloader zoneChunkUnloader;
    private final BehaviorClassifier classifier;
    private final SessionModeDetector sessionDetector;
    private final LearningStore learningStore;
    private final MemoryGovernor governor;
    private final PolicyApplier policyApplier;
    private final SessionSavingsTracker sessionSavings;
    private final MemoryHudService hudService;
    private final CriticalWebhookNotifier webhookNotifier;
    private final RegionalPressureCache regionalPressureCache = new RegionalPressureCache();
    private final EngineUnloadPoller engineUnloadPoller = new EngineUnloadPoller();
    private final LoadedChunkSetTracker loadedChunkSetTracker = new LoadedChunkSetTracker();
    private final GcHintScheduler gcHintScheduler;
    private ChunkSaturationSampler chunkSaturationSampler;
    private ChunkPrefetcher chunkPrefetcher;
    private final ThreadLocal<WorldBatchScratch> worldBatchScratch = ThreadLocal.withInitial(WorldBatchScratch::new);

    private volatile MemorySnapshot lastSample;
    private volatile SessionMode lastMode = SessionMode.SOLO;
    private volatile RuntimeProfile activeProfile = RuntimeProfile.LITE;

    private volatile boolean running;
    private final AtomicBoolean shutdownDone = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;
    private ScheduledExecutorService persistScheduler;
    private ScheduledFuture<?> tickFuture;
    private ScheduledFuture<?> persistFuture;
    private ScheduledFuture<?> motionFuture;
    private long lastDormancyRefreshMs;
    private long lastLiteHeapSampleMs;
    private double lastLiteX;
    private double lastLiteZ;
    private boolean lastLitePositioned;
    private long lastDeferredGovernorLogMs;
    private long liteSessionStartedMs;

    public MemoryRuntime(
            LeanCorePlugin plugin,
            LeanCoreConfig config,
            MemoryPressureSensor sensor,
            ZoneDormancyMap dormancyMap,
            ZoneChunkUnloader zoneChunkUnloader,
            BehaviorClassifier classifier,
            SessionModeDetector sessionDetector,
            LearningStore learningStore,
            MemoryGovernor governor,
            PolicyApplier policyApplier,
            SessionSavingsTracker sessionSavings,
            MemoryHudService hudService,
            CriticalWebhookNotifier webhookNotifier,
            GcHintScheduler gcHintScheduler
    ) {
        this.plugin = plugin;
        this.config = config;
        this.sensor = sensor;
        this.dormancyMap = dormancyMap;
        this.zoneChunkUnloader = zoneChunkUnloader;
        this.classifier = classifier;
        this.sessionDetector = sessionDetector;
        this.learningStore = learningStore;
        this.governor = governor;
        this.policyApplier = policyApplier;
        this.sessionSavings = sessionSavings;
        this.hudService = hudService;
        this.webhookNotifier = webhookNotifier;
        this.gcHintScheduler = gcHintScheduler;
    }

    public static MemoryRuntime create(
            LeanCorePlugin plugin,
            LeanCoreConfig config,
            BehaviorClassifier classifier,
            LearningStore learningStore
    ) {
        SessionSavingsTracker sessionSavings = new SessionSavingsTracker();
        MemoryPressureSensor sensor = new MemoryPressureSensor(learningStore.serverContext(), sessionSavings);
        sensor.setPositionSource(classifier.features());
        ZoneDormancyMap dormancyMap = new ZoneDormancyMap(config);
        dormancyMap.setPredictedPositionSource(classifier.features());
        dormancyMap.setZoneReuseModel(learningStore.zoneReuseModel());
        dormancyMap.setFalseCutTracker(learningStore.falseCutTracker());
        ZoneChunkUnloader zoneChunkUnloader = new ZoneChunkUnloader(config, learningStore.unloadOutcomeTracker());
        RetentionAllocator allocator = new RetentionAllocator(config);
        PolicyApplier applier = new PolicyApplier(config, learningStore.falseCutTracker(), classifier.features());
        MemoryGovernor governor = new MemoryGovernor(config, allocator, applier, zoneChunkUnloader, learningStore);
        HudSessionStore hudSessions = new HudSessionStore(plugin.getDataDirectory());
        MemoryHudService hudService = new MemoryHudService(config, hudSessions);
        CriticalWebhookNotifier webhookNotifier = new CriticalWebhookNotifier(config);
        return new MemoryRuntime(
                plugin,
                config,
                sensor,
                dormancyMap,
                zoneChunkUnloader,
                classifier,
                new SessionModeDetector(config),
                learningStore,
                governor,
                applier,
                sessionSavings,
                hudService,
                webhookNotifier,
                new GcHintScheduler(config)
        );
    }

    public void start() {
        shutdown();
        shutdownDone.set(false);
        scheduler = newScheduler("LeanCore-runtime");
        persistScheduler = newScheduler("LeanCore-persist");
        running = true;
        liteSessionStartedMs = System.currentTimeMillis();
        chunkSaturationSampler = new ChunkSaturationSampler(() -> Universe.get().getPlayers());
        chunkPrefetcher = new ChunkPrefetcher(config, classifier.features());
        int playerCount = Universe.get().getPlayers().size();
        activeProfile = RuntimeActivationPolicy.resolveProfile(config, playerCount);
        if (activeProfile == null) {
            activeProfile = RuntimeProfile.LITE;
        }
        long initialDelay = Math.max(0, config.runtimeInitialDelaySeconds);
        scheduleTick(initialDelay);
        schedulePersistIfNeeded();
        scheduleMotionTickIfNeeded();
        if (config.dedicatedServerMode && config.viewRadiusGovernanceEnabled) {
            governor.setViewRadiusGraceUntilMs(System.currentTimeMillis() + DedicatedBootstrap.VIEW_RADIUS_GRACE_MS);
        }
        plugin.getLogger().atInfo().log(
                "Runtime started profile=%s initialDelay=%ds tick=%ds",
                activeProfile,
                initialDelay,
                activeProfile.tickIntervalSeconds(config)
        );
        logStartupDiagnostics(initialDelay);
    }

    private void logStartupDiagnostics(long initialDelaySeconds) {
        List<String> lines = new ArrayList<>();
        lines.add("===== LeanCore startup (profile=" + activeProfile + ") =====");
        lines.add(String.format(Locale.ROOT,
                "schedule initialDelay=%ds tick=%ds motionSample=%ds persist=%ds",
                initialDelaySeconds,
                activeProfile.tickIntervalSeconds(config),
                Math.max(1, config.motionSampleIntervalSeconds),
                Math.max(1, config.persistIntervalSeconds)));
        lines.add(String.format(Locale.ROOT,
                "flags govern=%s learning=%s unload=%s lite[gov=%s view=%s learning=%s unload=%s]",
                config.governEnabled, config.learningEnabled, config.unloadEnabled,
                config.liteMemoryGovernorEnabled, config.liteViewRadiusEnabled,
                config.liteLearningEnabled, config.liteUnloadEnabled));
        lines.add(String.format(Locale.ROOT,
                "thresholds watch=%.2f tight=%.2f critical=%.2f dormantAfter=%dm frozenAfter=%dm",
                config.watchHeapRatio, config.tightHeapRatio, config.criticalHeapRatio,
                config.dormantAfterMinutes, config.frozenAfterMinutes));
        lines.add(String.format(Locale.ROOT,
                "motion=%s viewBoost=%s zoneReuse=%s",
                config.motionModelEnabled, config.motionViewRadiusBoostEnabled, config.zoneReuseModelEnabled));
        lines.add(String.format(Locale.ROOT,
                "v1.7.0 flags perChunkUnloadTruth=%s zoneContent=%s hotRadius=%s falseCutReward=%s",
                config.perChunkUnloadTruthEnabled, config.zoneContentModelEnabled,
                config.hotRadiusGovernanceEnabled, config.zoneFalseCutRewardEnabled));
        lines.add("loaded " + learningStore.statusLine());
        lines.add("==============================================");
        DiagnosticLog.info(lines);
    }

    private void logShutdownDiagnostics() {
        List<String> lines = new ArrayList<>();
        lines.add("===== LeanCore shutdown (profile=" + activeProfile + ") =====");
        MemorySnapshot s = lastSample();
        if (s != null) {
            lines.add(String.format(Locale.ROOT,
                    "heap %d/%d MB (%.0f%%) tier=%s online=%d",
                    s.heapUsedBytes() / (1024 * 1024),
                    s.heapMaxBytes() / (1024 * 1024),
                    s.heapUsedRatio() * 100.0D,
                    s.tier(),
                    s.onlinePlayers()));
        }
        GovernorStatus gov = governorStatus();
        if (gov.enabled()) {
            lines.add(String.format(Locale.ROOT,
                    "governor footprint %d/%d MB demoted=%d reclaimed~%d MB unloaded=%d chunks candidates=%d%s",
                    gov.totalFootprintMb(), gov.budgetMb(), gov.demotedZones(),
                    gov.reclaimedMbEstimate(), gov.unloadedChunks(), gov.unloadCandidateZones(),
                    gov.rolledBack() ? " ROLLBACK" : ""));
        } else {
            lines.add("governor disabled");
        }
        lines.add(learningStore.statusLine());
        lines.add(learningStore.windowLine());
        lines.add(learningStore.serverLine());
        lines.add(String.format(Locale.ROOT,
                "zones hot=%d warm=%d dormant=%d frozen=%d pinned=%d",
                dormancyMap.countByState(ZoneState.HOT),
                dormancyMap.countByState(ZoneState.WARM),
                dormancyMap.countByState(ZoneState.DORMANT),
                dormancyMap.countByState(ZoneState.FROZEN),
                dormancyMap.pinnedZones().size()));
        if (config.zoneReuseModelEnabled) {
            lines.add(dormancyMap.reuseSummaryLine(6));
        }
        lines.add("==============================================");
        DiagnosticLog.info(lines);
    }

    public boolean isRunning() {
        return running && !shutdownDone.get();
    }

    public void shutdown() {
        if (!shutdownDone.compareAndSet(false, true)) {
            return;
        }
        running = false;
        if (tickFuture != null) {
            tickFuture.cancel(true);
            tickFuture = null;
        }
        if (persistFuture != null) {
            persistFuture.cancel(true);
            persistFuture = null;
        }
        if (motionFuture != null) {
            motionFuture.cancel(true);
            motionFuture = null;
        }
        stopScheduler();
        persistLearning();
        logShutdownDiagnostics();
        if (hudService != null) {
            hudService.shutdown();
            hudService.sessions().save();
        }
        if (webhookNotifier != null) {
            webhookNotifier.shutdown();
        }
        plugin.getLogger().atInfo().log("Runtime stopped (daemon scheduler shut down)");
    }

    private static ScheduledExecutorService newScheduler(String threadName) {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, threadName);
            thread.setDaemon(true);
            return thread;
        });
    }

    private void stopScheduler() {
        stopExecutor(persistScheduler);
        persistScheduler = null;
        ScheduledExecutorService active = scheduler;
        scheduler = null;
        if (active == null) {
            return;
        }
        active.shutdownNow();
        try {
            active.awaitTermination(2L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void stopExecutor(ScheduledExecutorService executor) {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(2L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void scheduleTick(long delaySeconds) {
        if (!running || scheduler == null) {
            return;
        }
        tickFuture = scheduler.schedule(
                this::runTick,
                Math.max(0L, delaySeconds),
                TimeUnit.SECONDS
        );
    }

    private void schedulePersistIfNeeded() {
        if (!running || persistScheduler == null || !learningStore.persistenceEnabled() || config.persistIntervalSeconds <= 0) {
            return;
        }
        persistFuture = persistScheduler.scheduleAtFixedRate(() -> {
            if (!running) {
                return;
            }
            try {
                persistLearning();
            } catch (Exception e) {
                plugin.getLogger().atWarning().withCause(e).log("learning flush failed");
            }
        }, config.persistIntervalSeconds, config.persistIntervalSeconds, TimeUnit.SECONDS);
    }

    private void scheduleMotionTickIfNeeded() {
        if (!running || scheduler == null) {
            return;
        }
        if (!config.motionModelEnabled && !config.hudFeatureEnabled) {
            return;
        }
        long interval = Math.max(1, config.motionSampleIntervalSeconds);
        motionFuture = scheduler.scheduleAtFixedRate(
                this::runMotionTick, interval, interval, TimeUnit.SECONDS);
    }

    private void runMotionTick() {
        if (!running || !config.enabled) {
            return;
        }
        if (!config.motionModelEnabled && !config.hudFeatureEnabled) {
            return;
        }
        Collection<PlayerRef> online = Universe.get().getPlayers();
        if (online.isEmpty()) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        try {
            List<WorldBatch> batches = resolveAliveWorldBatches(online);
            if (batches.isEmpty()) {
                return;
            }
            if (config.motionModelEnabled) {
                samplePositionsPerWorld(batches, nowMs, false);
                if (config.motionViewRadiusBoostEnabled && policyApplier != null) {
                    policyApplier.applyMotionLive(online, activeProfile);
                }
            }
            if (RuntimeGuard.active() && activeProfile.runsHud(config) && hudService != null) {
                hudService.refresh(this);
            }
        } catch (Exception e) {
            plugin.getLogger().atWarning().withCause(e).log("motion tick failed");
        }
    }

    private void runTick() {
        if (!running) {
            return;
        }
        try {
            tick();
        } catch (Exception e) {
            plugin.getLogger().atWarning().withCause(e).log("tick failed");
        } finally {
            if (!running) {
                return;
            }
            long delaySeconds = activeProfile == RuntimeProfile.LITE
                    ? SoloRuntimePolicy.nextTickDelaySeconds(config, soloPlayerIdleSec())
                    : activeProfile.tickIntervalSeconds(config);
            logTickCadence(delaySeconds);
            scheduleTick(delaySeconds);
        }
    }

    private void logTickCadence(long delaySeconds) {
        String why;
        if (activeProfile == RuntimeProfile.LITE) {
            long idleThreshold = Math.max(60, config.soloIdleThresholdSeconds);
            if (!config.soloAdaptiveTickEnabled) {
                why = "LITE adaptive tick off";
            } else if (soloPlayerIdleSec() >= idleThreshold) {
                why = "LITE player idle >= " + idleThreshold + "s (slow)";
            } else {
                why = "LITE player active (< " + idleThreshold + "s)";
            }
        } else {
            why = "profile " + activeProfile;
        }
        DiagnosticLog.infoOnChange("tick-cadence", "tick cadence " + delaySeconds + "s why=" + why);
    }

    private void persistLearning() {
        classifier.syncToStore(learningStore);
        var sample = lastSample;
        if (sample != null && config.learningEnabled) {
            learningStore.outcomeTracker().flushPending(
                    learningStore.heapAvg60s(),
                    sample.onlinePlayers(),
                    System.currentTimeMillis()
            );
        }
        learningStore.flush(true, classifier.features().snapshot().keySet());
    }

    private void tick() {
        if (!config.enabled) {
            return;
        }

        int playerCount = Universe.get().getPlayers().size();
        RuntimeProfile profile = RuntimeActivationPolicy.resolveProfile(config, playerCount);
        if (profile == null) {
            profile = RuntimeProfile.LITE;
        }
        if (profile != activeProfile) {
            plugin.getLogger().atInfo().log(
                    "Runtime profile %s -> %s (%d online)",
                    activeProfile,
                    profile,
                    playerCount
            );
            activeProfile = profile;
        }

        long nowMs = System.currentTimeMillis();
        var online = Universe.get().getPlayers();

        if (profile == RuntimeProfile.LITE) {
            tickLite(online, nowMs);
            return;
        }

        if (!running) {
            return;
        }

        final RuntimeProfile governorProfile = profile;
        List<WorldBatch> batches = resolveAliveWorldBatches(online);
        if (batches.isEmpty()) {
            if (nowMs - lastDeferredGovernorLogMs >= 60_000L) {
                lastDeferredGovernorLogMs = nowMs;
                plugin.getLogger().atFine().log(
                        "Governor world-thread work deferred (no alive world)"
                );
            }
            tickGovernorDeferred(governorProfile, online, nowMs);
            return;
        }

        try {
            tickGovernorOnWorld(governorProfile, online, nowMs, batches);
        } catch (Exception e) {
            plugin.getLogger().atWarning().withCause(e).log("governor tick failed");
        }
    }

    private void tickGovernorDeferred(RuntimeProfile profile, Collection<PlayerRef> online, long nowMs) {
        MemorySnapshot sample = sensor.sample();
        lastSample = sample;
        lastMode = sessionDetector.detect(sample.onlinePlayers());
        learningStore.holdoutCohort().noteOnline(online, sample.heapUsedRatio(), nowMs);

        if (profile.tracksPlayerMotion()) {
            classifier.samplePositionsLite(online, nowMs);
        }

        var demands = classifier.snapshotDemands(nowMs);
        if (profile.runsLearning(config)) {
            learningStore.noteHeap(sample.heapUsedRatio());
            learningStore.noteTier(sample.tier());
            learningStore.noteDemands(demands);
        }
    }

    private void tickGovernorOnWorld(
            RuntimeProfile profile,
            Collection<PlayerRef> online,
            long nowMs,
            List<WorldBatch> batches
    ) {
        if (profile.tracksPlayerMotion()) {
            samplePositionsPerWorld(batches, nowMs, true);
        }

        long dormancyIntervalMs = dormancyIntervalMs(profile);
        if (lastDormancyRefreshMs <= 0L || nowMs - lastDormancyRefreshMs >= dormancyIntervalMs) {
            if (refreshDormancyPerWorld(batches, nowMs)) {
                lastDormancyRefreshMs = nowMs;
            }
        }

        // Front A: per-chunk unload truth runs inside the batched dormancy dispatch below. The
        // legacy net-count poller only runs when that path is off, so we never double-count.
        if (config.chunkUnloadEventTracking && !config.perChunkUnloadTruthEnabled) {
            engineUnloadPoller.poll(online, learningStore.unloadOutcomeTracker());
        }

        regionalPressureCache.maybeSample(online, config.regionalPressureIntervalSeconds, nowMs);
        learningStore.setRegionalPressure(regionalPressureCache.pressure());

        MemorySnapshot sample = sensor.sample();
        lastSample = sample;
        lastMode = sessionDetector.detect(sample.onlinePlayers());
        learningStore.holdoutCohort().noteOnline(online, sample.heapUsedRatio(), nowMs);

        var demands = classifier.snapshotDemands(nowMs);
        if (profile.runsLearning(config)) {
            learningStore.noteHeap(sample.heapUsedRatio());
            learningStore.noteTier(sample.tier());
            learningStore.noteDemands(demands);
        }

        if (profile.runsGovernor(config)) {
            governor.tick(sample, lastMode, demands, dormancyMap);
            GovernorStatus govStatus = governor.status();
            if (govStatus.enabled()) {
                sessionSavings.noteGovernorTick(govStatus.demotedZones(), govStatus.reclaimedMbEstimate());
            }
            noteEngineUnloadYieldIfNeeded();
            double reward = learningStore.outcomeTracker().pollCompletedReward();
            if (!Double.isNaN(reward)) {
                learningStore.reinforceDemandOnReward(
                        reward,
                        demands,
                        classifier.features().snapshot(),
                        nowMs
                );
            }
        }

        if (profile == RuntimeProfile.FULL && webhookNotifier != null) {
            webhookNotifier.onTier(sample.tier(), sample.heapUsedRatio());
        }

        if (RuntimeGuard.active() && profile.runsHud(config) && hudService != null) {
            hudService.refresh(this);
        }

        maybePrefetchChunks(batches, sample.tier());
    }

    /**
     * Predictive chunk prefetch (Frente C2): only ever runs with heap headroom (COMFORT), once enabled.
     * Each world warms a few chunks ahead of its moving players on its own thread, bounded by the
     * fan-out budget so a stalled world can never monopolise the tick.
     */
    private void maybePrefetchChunks(List<WorldBatch> batches, MemoryTier tier) {
        if (!config.chunkPrefetchEnabled || tier != MemoryTier.COMFORT || chunkPrefetcher == null) {
            return;
        }
        long deadlineNs = System.nanoTime() + FAN_OUT_BUDGET_NANOS;
        for (WorldBatch batch : batches) {
            if (System.nanoTime() > deadlineNs) {
                break;
            }
            WorldDispatch.run(batch.world(), () -> {
                GovernorWorldContext.enter(batch.worldUuid());
                try {
                    chunkPrefetcher.prefetchOnWorld(batch.world(), batch.players());
                } finally {
                    GovernorWorldContext.exit();
                }
            });
        }
    }

    /** Online players grouped by their alive world, so per-player work runs on the right thread. */
    private List<WorldBatch> resolveAliveWorldBatches(Collection<PlayerRef> online) {
        WorldBatchScratch scratch = worldBatchScratch.get();
        scratch.clear();
        if (online == null || online.isEmpty()) {
            return List.of();
        }
        for (PlayerRef ref : online) {
            if (ref == null || !ref.isValid() || ref.getWorldUuid() == null) {
                continue;
            }
            scratch.playersFor(ref.getWorldUuid()).add(ref);
        }
        for (MutableWorldBatch grouped : scratch.groupedWorlds) {
            World world = Universe.get().getWorld(grouped.worldUuid);
            if (world != null && world.isAlive()) {
                // A timed-out world task may still run after this scratch buffer is reused. Give the
                // task an immutable player list so it cannot observe a later runtime pass.
                scratch.batches.add(new WorldBatch(grouped.worldUuid, world, List.copyOf(grouped.players)));
            }
        }
        return scratch.batches;
    }

    /** Samples player motion on each world's own thread (transform reads need world affinity). */
    private void samplePositionsPerWorld(List<WorldBatch> batches, long nowMs, boolean fullProbe) {
        long deadlineNs = System.nanoTime() + FAN_OUT_BUDGET_NANOS;
        for (WorldBatch batch : batches) {
            if (System.nanoTime() > deadlineNs) {
                DiagnosticLog.infoOnChange("fanout-budget",
                        "fan-out budget exceeded; motion sample ran on a subset of worlds this tick");
                break;
            }
            WorldDispatch.run(batch.world(), () -> {
                GovernorWorldContext.enter(batch.worldUuid());
                try {
                    if (fullProbe) {
                        classifier.samplePositions(batch.players(), nowMs);
                    } else {
                        classifier.samplePositionsLite(batch.players(), nowMs);
                    }
                } finally {
                    GovernorWorldContext.exit();
                }
            });
        }
    }

    /**
     * Batched per-world reads (v1.7.0 Frente D): one {@code WorldDispatch.run} per world gathers hot
     * zones, diffs the loaded-chunk set for unload truth (Frente A), and scans built content for the
     * content model (Frente B). Each world fills a local buffer and only merges on a successful run,
     * so a timed-out task can never race the aggregate. If any world is missed we skip the dormancy
     * refresh rather than age its zones; the unload/content signals still merge per completed world.
     * Returns true only when every world ran, so the caller can avoid advancing the refresh clock on
     * a partial pass and retry next tick.
     */
    private boolean refreshDormancyPerWorld(List<WorldBatch> batches, long nowMs) {
        List<ZoneKey> hot = new ArrayList<>();
        boolean complete = true;
        int engineRemoved = 0;
        boolean unloadTruth = config.chunkUnloadEventTracking && config.perChunkUnloadTruthEnabled;
        boolean contentScan = config.zoneContentModelEnabled;
        if (unloadTruth) {
            Set<UUID> aliveWorlds = new HashSet<>();
            for (WorldBatch batch : batches) {
                aliveWorlds.add(batch.worldUuid());
            }
            loadedChunkSetTracker.retainWorlds(aliveWorlds);
        }
        long deadlineNs = System.nanoTime() + FAN_OUT_BUDGET_NANOS;
        for (WorldBatch batch : batches) {
            if (System.nanoTime() > deadlineNs) {
                DiagnosticLog.infoOnChange("fanout-budget",
                        "fan-out budget exceeded; dormancy refresh deferred (partial worlds)");
                complete = false;
                break;
            }
            List<ZoneKey> local = new ArrayList<>();
            int[] removed = {0};
            boolean done = WorldDispatch.run(batch.world(), () -> {
                GovernorWorldContext.enter(batch.worldUuid());
                try {
                    local.addAll(ZoneDormancyMap.hotZonesForPlayers(batch.players()));
                    if (unloadTruth) {
                        removed[0] = loadedChunkSetTracker.diffRemoved(batch.worldUuid(), batch.world());
                    }
                    if (contentScan) {
                        scanContentOnWorld(batch, nowMs);
                    }
                } finally {
                    GovernorWorldContext.exit();
                }
            });
            if (done) {
                hot.addAll(local);
                engineRemoved += removed[0];
            } else {
                complete = false;
            }
        }
        if (unloadTruth && engineRemoved > 0) {
            learningStore.unloadOutcomeTracker().noteEngineUnloads(engineRemoved);
        }
        if (complete) {
            dormancyMap.refreshFromHotZones(hot, nowMs);
        }
        return complete;
    }

    /**
     * Samples built-content density for each player's current zone and folds it into the persisted
     * per-zone content EMA. Runs on the world thread (called from inside the batched dispatch), so a
     * zone's content is learned while the player is present and reused once the zone cools.
     */
    private void scanContentOnWorld(WorldBatch batch, long nowMs) {
        var reuseModel = learningStore.zoneReuseModel();
        if (reuseModel == null) {
            return;
        }
        int saturationCap = com.durkz.leancore.dormancy.ZoneContentModel.saturationBlockEntities();
        for (PlayerRef ref : batch.players()) {
            // Content-only read: this path consumes only contentScore, so scan block-entities and
            // stop once the score saturates (cheaper than the full regional/world entity probe).
            RegionalEntityProbe.RegionalEntitySample sample =
                    RegionalEntityProbe.read(ref, batch.world(), saturationCap);
            if (sample.zone() != null) {
                reuseModel.noteContent(sample.zone(), sample.contentScore(), nowMs);
            }
        }
    }

    private record WorldBatch(UUID worldUuid, World world, List<PlayerRef> players) {
    }

    /** Runtime scheduler is single-threaded, so grouping buffers can be reused between passes. */
    private static final class WorldBatchScratch {

        private final ArrayList<MutableWorldBatch> groupedWorlds = new ArrayList<>();
        private final ArrayList<WorldBatch> batches = new ArrayList<>();

        private void clear() {
            for (MutableWorldBatch grouped : groupedWorlds) {
                grouped.players.clear();
            }
            batches.clear();
        }

        private ArrayList<PlayerRef> playersFor(UUID worldUuid) {
            for (MutableWorldBatch grouped : groupedWorlds) {
                if (grouped.worldUuid.equals(worldUuid)) {
                    return grouped.players;
                }
            }
            MutableWorldBatch grouped = new MutableWorldBatch(worldUuid);
            groupedWorlds.add(grouped);
            return grouped.players;
        }
    }

    private static final class MutableWorldBatch {

        private final UUID worldUuid;
        private final ArrayList<PlayerRef> players = new ArrayList<>();

        private MutableWorldBatch(UUID worldUuid) {
            this.worldUuid = worldUuid;
        }
    }

    private void tickLite(java.util.Collection<PlayerRef> online, long nowMs) {
        List<WorldBatch> batches = resolveAliveWorldBatches(online);
        if (batches.isEmpty()) {
            return;
        }
        try {
            tickLiteOnWorld(online, nowMs, batches);
        } catch (Exception e) {
            plugin.getLogger().atWarning().withCause(e).log("lite tick failed");
        }
    }

    private void tickLiteOnWorld(Collection<PlayerRef> online, long nowMs, List<WorldBatch> batches) {
        if (!online.isEmpty()) {
            samplePositionsPerWorld(batches, nowMs, false);
        }
        double[] soloXZ = soloCurrentXZ(online);
        if (SoloRuntimePolicy.shouldRefreshDormancy(
                config,
                lastLiteX,
                lastLiteZ,
                lastLitePositioned,
                nowMs,
                lastDormancyRefreshMs,
                soloXZ
        )) {
            if (refreshDormancyPerWorld(batches, nowMs)) {
                lastDormancyRefreshMs = nowMs;
                SoloRuntimePolicy.PlayerMotionSnapshot motion = SoloRuntimePolicy.captureMotion(soloXZ);
                lastLiteX = motion.x();
                lastLiteZ = motion.z();
                lastLitePositioned = motion.positioned();
            }
        }

        if (!SoloRuntimePolicy.shouldSampleHeap(config, nowMs, lastLiteHeapSampleMs)) {
            return;
        }

        MemorySnapshot sample = sensor.sample(false);
        lastSample = sample;
        lastLiteHeapSampleMs = nowMs;
        lastMode = sessionDetector.detect(sample.onlinePlayers());
        if (gcHintScheduler.maybeHint(nowMs, soloPlayerIdleSec(), sample.tier(), RuntimeProfile.LITE)) {
            plugin.getLogger().atFine().log("GC hint issued (LITE idle, tier COMFORT)");
        }
        if (!activeProfile.runsLiteGovernor(config)) {
            return;
        }

        var demands = classifier.snapshotDemands(nowMs);
        if (activeProfile.runsLiteLearning(config)) {
            learningStore.noteHeap(sample.heapUsedRatio());
            learningStore.noteTier(sample.tier());
            learningStore.noteDemands(demands);
            classifier.syncToStore(learningStore);
        }
        double chunkSaturation = sampleChunkSaturation();
        governor.tickLiteMode(
                sample,
                demands,
                dormancyMap,
                chunkSaturation,
                liteSessionStartedMs,
                soloPlayerIdleSec(),
                nowMs
        );
        GovernorStatus govStatus = governor.status();
        if (govStatus.enabled()) {
            sessionSavings.noteGovernorTick(govStatus.demotedZones(), govStatus.reclaimedMbEstimate());
        }
        noteEngineUnloadYieldIfNeeded();

        maybePrefetchChunks(batches, sample.tier());
    }

    private void noteEngineUnloadYieldIfNeeded() {
        if (zoneChunkUnloader != null && zoneChunkUnloader.lastSweepYieldedToEngine()) {
            sessionSavings.noteEngineUnloadYield();
        }
    }

    private double sampleChunkSaturation() {
        if (chunkSaturationSampler == null) {
            return 0.0D;
        }
        try {
            return chunkSaturationSampler.sample();
        } catch (Exception error) {
            plugin.getLogger().atFine().withCause(error).log("chunk saturation sample failed");
            return 0.0D;
        }
    }

    public GcHintScheduler gcHintScheduler() {
        return gcHintScheduler;
    }

    /** First online player's last on-world-sampled (x,z), or null. Identity reads only, no transform. */
    private double[] soloCurrentXZ(Collection<PlayerRef> online) {
        if (online == null) {
            return null;
        }
        var source = classifier.features();
        for (PlayerRef ref : online) {
            if (ref == null || !ref.isValid()) {
                continue;
            }
            double[] xz = source.currentXZ(ref.getUuid());
            if (xz != null) {
                return xz;
            }
        }
        return null;
    }

    private long soloPlayerIdleSec() {
        for (PlayerRef ref : Universe.get().getPlayers()) {
            if (ref == null || !ref.isValid()) {
                continue;
            }
            var features = classifier.features().snapshot().get(ref.getUuid());
            if (features != null) {
                return features.idleSec(System.currentTimeMillis());
            }
        }
        return 0L;
    }

    public RegionalPressureCache regionalPressureCache() {
        return regionalPressureCache;
    }

    private long dormancyIntervalMs(RuntimeProfile profile) {
        long base = Math.max(1, config.dormancyRefreshIntervalSeconds) * 1000L;
        return switch (profile) {
            case LITE -> Math.max(base, 30_000L);
            case STANDARD -> Math.max(base, 20_000L);
            case FULL -> base;
        };
    }

    public MemorySnapshot lastSample() {
        MemorySnapshot sample = lastSample;
        return sample != null ? sample : sensor.sample();
    }

    public MemorySnapshot freshSample() {
        MemorySnapshot sample = sensor.sample();
        lastSample = sample;
        return sample;
    }

    public SessionMode lastMode() {
        return lastMode;
    }

    public RuntimeProfile activeProfile() {
        return activeProfile;
    }

    public ZoneDormancyMap dormancyMap() {
        return dormancyMap;
    }

    public ZoneChunkUnloader zoneChunkUnloader() {
        return zoneChunkUnloader;
    }

    public LearningStore learningStore() {
        return learningStore;
    }

    public BehaviorClassifier classifier() {
        return classifier;
    }

    public SessionSavingsTracker sessionSavings() {
        return sessionSavings;
    }

    public GovernorStatus governorStatus() {
        return governor.status();
    }

    public long viewRadiusGraceUntilMs() {
        return governor.viewRadiusGraceUntilMs();
    }

    public long liteSessionStartedMs() {
        return liteSessionStartedMs;
    }

    public MemoryHudService hudService() {
        return hudService;
    }
}
