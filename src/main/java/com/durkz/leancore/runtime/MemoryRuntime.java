package com.durkz.leancore.runtime;

import com.durkz.leancore.LeanCorePlugin;
import com.durkz.leancore.alert.CriticalWebhookNotifier;
import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.dormancy.ZoneChunkUnloader;
import com.durkz.leancore.dormancy.ZoneDormancyMap;
import com.durkz.leancore.intelligence.BehaviorClassifier;
import com.durkz.leancore.intelligence.LearningStore;
import com.durkz.leancore.memory.GovernorStatus;
import com.durkz.leancore.memory.MemoryGovernor;
import com.durkz.leancore.memory.MemoryPressureSensor;
import com.durkz.leancore.memory.MemorySnapshot;
import com.durkz.leancore.memory.PolicyApplier;
import com.durkz.leancore.memory.RetentionAllocator;
import com.durkz.leancore.session.SessionMode;
import com.durkz.leancore.session.SessionModeDetector;
import com.durkz.leancore.ui.HudSessionStore;
import com.durkz.leancore.ui.MemoryHudService;
import com.hypixel.hytale.server.core.universe.Universe;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
    private final MemoryHudService hudService;
    private final CriticalWebhookNotifier webhookNotifier;

    private volatile MemorySnapshot lastSample;
    private volatile SessionMode lastMode = SessionMode.SOLO;
    private ScheduledExecutorService ticker;
    private long lastDormancyRefreshMs;

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
            MemoryHudService hudService,
            CriticalWebhookNotifier webhookNotifier
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
        this.hudService = hudService;
        this.webhookNotifier = webhookNotifier;
    }

    public static MemoryRuntime create(
            LeanCorePlugin plugin,
            LeanCoreConfig config,
            BehaviorClassifier classifier,
            LearningStore learningStore
    ) {
        MemoryPressureSensor sensor = new MemoryPressureSensor(learningStore.serverContext());
        ZoneDormancyMap dormancyMap = new ZoneDormancyMap(config);
        ZoneChunkUnloader zoneChunkUnloader = new ZoneChunkUnloader(config);
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
                hudService,
                webhookNotifier
        );
    }

    public void start() {
        shutdown();
        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "LeanCore-tick");
            t.setDaemon(true);
            return t;
        });
        long tickIntervalSec = Math.max(1, config.runtimeTickIntervalSeconds);
        ticker.scheduleAtFixedRate(() -> {
            try {
                tick();
            } catch (Exception e) {
                plugin.getLogger().atWarning().withCause(e).log("tick failed");
            }
        }, 0L, tickIntervalSec, TimeUnit.SECONDS);

        if (config.persistIntervalSeconds > 0) {
            ticker.scheduleAtFixedRate(() -> {
                try {
                    persistLearning();
                } catch (Exception e) {
                    plugin.getLogger().atWarning().withCause(e).log("learning flush failed");
                }
            }, config.persistIntervalSeconds, config.persistIntervalSeconds, TimeUnit.SECONDS);
        }
    }

    public void shutdown() {
        if (ticker == null) {
            return;
        }
        persistLearning();
        if (hudService != null) {
            hudService.sessions().save();
        }
        if (webhookNotifier != null) {
            webhookNotifier.shutdown();
        }
        ticker.shutdownNow();
        ticker = null;
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
        learningStore.flush();
    }

    private void tick() {
        if (!config.enabled) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        var online = Universe.get().getPlayers();
        classifier.samplePositions(online, nowMs);
        long dormancyIntervalMs = Math.max(1, config.dormancyRefreshIntervalSeconds) * 1000L;
        if (lastDormancyRefreshMs <= 0L || nowMs - lastDormancyRefreshMs >= dormancyIntervalMs) {
            dormancyMap.refreshFromPlayers();
            lastDormancyRefreshMs = nowMs;
        }

        MemorySnapshot sample = sensor.sample();
        lastSample = sample;
        lastMode = sessionDetector.detect(sample.onlinePlayers());

        var demands = classifier.snapshotDemands(nowMs);
        learningStore.noteHeap(sample.heapUsedRatio());
        learningStore.noteTier(sample.tier());
        learningStore.noteDemands(demands);
        governor.tick(sample, lastMode, demands, dormancyMap);
        if (webhookNotifier != null) {
            webhookNotifier.onTier(sample.tier(), sample.heapUsedRatio());
        }
        if (hudService != null) {
            hudService.refresh(this);
        }
    }

    public MemorySnapshot lastSample() {
        MemorySnapshot sample = lastSample;
        return sample != null ? sample : sensor.sample();
    }

    public SessionMode lastMode() {
        return lastMode;
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

    public GovernorStatus governorStatus() {
        return governor.status();
    }

    public MemoryHudService hudService() {
        return hudService;
    }
}
