package com.durkz.leancore.runtime;

import com.durkz.leancore.LeanCorePlugin;
import com.durkz.leancore.alert.CriticalWebhookNotifier;
import com.durkz.leancore.config.DedicatedBootstrap;
import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.dormancy.ZoneChunkUnloader;
import com.durkz.leancore.dormancy.ZoneDormancyMap;
import com.durkz.leancore.intelligence.BehaviorClassifier;
import com.durkz.leancore.intelligence.EngineUnloadPoller;
import com.durkz.leancore.intelligence.LearningStore;
import com.durkz.leancore.memory.GcHintScheduler;
import com.durkz.leancore.memory.GovernorStatus;
import com.durkz.leancore.memory.MemoryGovernor;
import com.durkz.leancore.memory.MemoryPressureSensor;
import com.durkz.leancore.memory.MemorySnapshot;
import com.durkz.leancore.memory.SessionSavingsTracker;
import com.durkz.leancore.memory.PolicyApplier;
import com.durkz.leancore.memory.RetentionAllocator;
import com.durkz.leancore.session.SessionMode;
import com.durkz.leancore.session.SessionModeDetector;
import com.durkz.leancore.probe.RegionalPressureCache;
import com.durkz.leancore.ui.HudSessionStore;
import com.durkz.leancore.ui.MemoryHudService;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class MemoryRuntime {

    private final LeanCorePlugin plugin;
    private final LeanCoreConfig config;
    private final MemoryPressureSensor sensor;
    private final ZoneDormancyMap dormancyMap;
    private final ZoneChunkUnloader zoneChunkUnloader;
    private final BehaviorClassifier classifier;
    private final SessionModeDetector sessionDetector;
    private final LearningStore learningStore;
    private final MemoryGovernor governor;
    private final SessionSavingsTracker sessionSavings;
    private final MemoryHudService hudService;
    private final CriticalWebhookNotifier webhookNotifier;
    private final RegionalPressureCache regionalPressureCache = new RegionalPressureCache();
    private final EngineUnloadPoller engineUnloadPoller = new EngineUnloadPoller();
    private final GcHintScheduler gcHintScheduler;

    private volatile MemorySnapshot lastSample;
    private volatile SessionMode lastMode = SessionMode.SOLO;
    private volatile RuntimeProfile activeProfile = RuntimeProfile.LITE;

    private volatile boolean running;
    private volatile boolean shutdownDone;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> tickFuture;
    private ScheduledFuture<?> persistFuture;
    private final AtomicBoolean governorWorldTickPending = new AtomicBoolean(false);
    private long lastDormancyRefreshMs;
    private long lastLiteHeapSampleMs;
    private double lastLiteX;
    private double lastLiteZ;
    private boolean lastLitePositioned;

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
        ZoneDormancyMap dormancyMap = new ZoneDormancyMap(config);
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
                sessionSavings,
                hudService,
                webhookNotifier,
                new GcHintScheduler(config)
        );
    }

    public void start() {
        shutdown();
        shutdownDone = false;
        scheduler = newScheduler();
        running = true;
        int playerCount = Universe.get().getPlayers().size();
        activeProfile = RuntimeActivationPolicy.resolveProfile(config, playerCount);
        if (activeProfile == null) {
            activeProfile = RuntimeProfile.LITE;
        }
        long initialDelay = Math.max(0, config.runtimeInitialDelaySeconds);
        scheduleTick(initialDelay);
        schedulePersistIfNeeded();
        if (config.dedicatedServerMode && config.viewRadiusGovernanceEnabled) {
            governor.setViewRadiusGraceUntilMs(System.currentTimeMillis() + DedicatedBootstrap.VIEW_RADIUS_GRACE_MS);
        }
        plugin.getLogger().atInfo().log(
                "Runtime started profile=%s initialDelay=%ds tick=%ds",
                activeProfile,
                initialDelay,
                activeProfile.tickIntervalSeconds(config)
        );
    }

    public boolean isRunning() {
        return running && !shutdownDone;
    }

    public void shutdown() {
        if (shutdownDone) {
            return;
        }
        shutdownDone = true;
        running = false;
        governorWorldTickPending.set(false);
        if (tickFuture != null) {
            tickFuture.cancel(true);
            tickFuture = null;
        }
        if (persistFuture != null) {
            persistFuture.cancel(true);
            persistFuture = null;
        }
        stopScheduler();
        persistLearning();
        if (hudService != null) {
            hudService.shutdown();
            hudService.sessions().save();
        }
        if (webhookNotifier != null) {
            webhookNotifier.shutdown();
        }
        plugin.getLogger().atInfo().log("Runtime stopped (daemon scheduler shut down)");
    }

    private static ScheduledExecutorService newScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "LeanCore-runtime");
            thread.setDaemon(true);
            return thread;
        });
    }

    private void stopScheduler() {
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
        if (!running || scheduler == null || !config.learningEnabled || config.persistIntervalSeconds <= 0) {
            return;
        }
        persistFuture = scheduler.scheduleAtFixedRate(() -> {
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
            scheduleTick(delaySeconds);
        }
    }

    private void persistLearning() {
        classifier.syncToStore(learningStore);
        var sample = lastSample;
        if (sample != null) {
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
        World world = resolvePrimaryWorld(online);
        if (world == null) {
            tickGovernor(governorProfile, online, nowMs);
            return;
        }

        if (!governorWorldTickPending.compareAndSet(false, true)) {
            return;
        }
        try {
            world.execute(() -> {
                try {
                    if (running) {
                        tickGovernor(governorProfile, online, nowMs);
                    }
                } catch (Exception e) {
                    plugin.getLogger().atWarning().withCause(e).log("governor tick failed");
                } finally {
                    governorWorldTickPending.set(false);
                }
            });
        } catch (RuntimeException e) {
            governorWorldTickPending.set(false);
            plugin.getLogger().atFine().withCause(e).log("governor tick not queued — world shutting down");
        }
    }

    private void tickGovernor(RuntimeProfile profile, Collection<PlayerRef> online, long nowMs) {
        GovernorWorldContext.enter();
        try {
            tickGovernorOnWorld(profile, online, nowMs);
        } finally {
            GovernorWorldContext.exit();
        }
    }

    private void tickGovernorOnWorld(RuntimeProfile profile, Collection<PlayerRef> online, long nowMs) {
        long dormancyIntervalMs = dormancyIntervalMs(profile);
        if (lastDormancyRefreshMs <= 0L || nowMs - lastDormancyRefreshMs >= dormancyIntervalMs) {
            dormancyMap.refreshFromPlayers();
            lastDormancyRefreshMs = nowMs;
        }

        if (config.chunkUnloadEventTracking) {
            engineUnloadPoller.poll(online, learningStore.unloadOutcomeTracker());
        }

        regionalPressureCache.maybeSample(online, config.regionalPressureIntervalSeconds, nowMs);
        learningStore.setRegionalPressure(regionalPressureCache.pressure());

        MemorySnapshot sample = sensor.sample();
        lastSample = sample;
        lastMode = sessionDetector.detect(sample.onlinePlayers());
        learningStore.holdoutCohort().noteOnline(online, sample.heapUsedRatio(), nowMs);

        if (profile.tracksPlayerMotion()) {
            classifier.samplePositions(online, nowMs);
        }

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
    }

    private static World resolvePrimaryWorld(Collection<PlayerRef> online) {
        if (online == null) {
            return null;
        }
        for (PlayerRef ref : online) {
            if (ref == null || !ref.isValid() || ref.getWorldUuid() == null) {
                continue;
            }
            World world = Universe.get().getWorld(ref.getWorldUuid());
            if (world != null && world.isAlive()) {
                return world;
            }
        }
        return null;
    }

    private void tickLite(java.util.Collection<PlayerRef> online, long nowMs) {
        if (!online.isEmpty()) {
            classifier.samplePositionsLite(online, nowMs);
        }
        if (SoloRuntimePolicy.shouldRefreshDormancy(
                config,
                lastLiteX,
                lastLiteZ,
                lastLitePositioned,
                nowMs,
                lastDormancyRefreshMs
        )) {
            dormancyMap.refreshFromPlayers();
            lastDormancyRefreshMs = nowMs;
            SoloRuntimePolicy.PlayerMotionSnapshot motion = SoloRuntimePolicy.captureMotion();
            lastLiteX = motion.x();
            lastLiteZ = motion.z();
            lastLitePositioned = motion.positioned();
        }

        if (SoloRuntimePolicy.shouldSampleHeap(config, nowMs, lastLiteHeapSampleMs)) {
            MemorySnapshot sample = sensor.sample(false);
            lastSample = sample;
            lastLiteHeapSampleMs = nowMs;
            lastMode = sessionDetector.detect(sample.onlinePlayers());
            if (gcHintScheduler.maybeHint(nowMs, soloPlayerIdleSec(), sample.tier(), RuntimeProfile.LITE)) {
                plugin.getLogger().atFine().log("GC hint issued (LITE idle, tier COMFORT)");
            }
        }
    }

    public GcHintScheduler gcHintScheduler() {
        return gcHintScheduler;
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

    public MemoryHudService hudService() {
        return hudService;
    }
}
