package com.durkz.leancore.memory;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.dormancy.ZoneChunkUnloader;
import com.durkz.leancore.dormancy.ZoneDormancyMap;
import com.durkz.leancore.intelligence.LearningStore;
import com.durkz.leancore.intelligence.OutcomeTracker;
import com.durkz.leancore.intelligence.PolicyBandit;
import com.durkz.leancore.intelligence.RetentionDemand;
import com.durkz.leancore.session.SessionMode;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MemoryGovernor {

    private final LeanCoreConfig config;
    private final RetentionAllocator allocator;
    private final PolicyApplier applier;
    private final ZoneChunkUnloader zoneChunkUnloader;
    private final LearningStore learningStore;
    private final PolicyBandit bandit;
    private final OutcomeTracker outcomeTracker;

    private GovernorPolicy activePolicy;
    private GovernorPolicy previousPolicy;
    private long lastChangeMs;
    private double heapAtChange;
    private boolean rolledBack;
    private double[] contextAtChange;
    private final Map<String, Long> blacklistedUntilMs = new ConcurrentHashMap<>();

    private volatile GovernorStatus lastStatus = GovernorStatus.idle();

    public MemoryGovernor(
            LeanCoreConfig config,
            RetentionAllocator allocator,
            PolicyApplier applier,
            ZoneChunkUnloader zoneChunkUnloader,
            LearningStore learningStore
    ) {
        this.config = config;
        this.allocator = allocator;
        this.applier = applier;
        this.zoneChunkUnloader = zoneChunkUnloader;
        this.learningStore = learningStore;
        this.bandit = learningStore.policyBandit();
        this.outcomeTracker = learningStore.outcomeTracker();
    }

    public void tick(
            MemorySnapshot sample,
            SessionMode mode,
            Map<UUID, RetentionDemand> demands,
            ZoneDormancyMap dormancyMap
    ) {
        if (!config.enabled || !config.governEnabled) {
            lastStatus = GovernorStatus.idle();
            return;
        }

        long nowMs = System.currentTimeMillis();
        outcomeTracker.tick(learningStore.heapAvg60s(), sample.onlinePlayers(), nowMs);

        GovernorPreset preset = GovernorPreset.resolve(config.preset, mode);
        GovernorPolicy pressurePolicy = GovernorPolicy.forTier(preset, sample.tier());
        checkRollback(sample);

        allocator.reconcile(preset, mode, sample, demands, dormancyMap);
        if (pressurePolicy.demoteBatch() > 0) {
            dormancyMap.demoteFarthestDormant(pressurePolicy.demoteBatch());
        }

        int unloadedChunks = zoneChunkUnloader.sweep(dormancyMap, sample.tier());

        GovernorPolicy toApply = choosePolicy(pressurePolicy, preset, sample, demands);
        int scheduled = 0;
        if (toApply != null) {
            Collection<PlayerRef> online = Universe.get().getPlayers();
            boolean policyChanged = !samePolicy(activePolicy, toApply);
            scheduled = applier.apply(toApply, online, demands, policyChanged);
            commitPolicy(toApply, sample, demands, nowMs);
        }

        long since = lastChangeMs <= 0L ? 0L : (nowMs - lastChangeMs) / 1000L;
        lastStatus = new GovernorStatus(
                true,
                preset,
                activePolicy,
                scheduled,
                allocator.lastDemotedZones(),
                allocator.lastReclaimedMb(),
                allocator.lastFootprintMb(),
                allocator.lastBudgetMb(),
                unloadedChunks,
                zoneChunkUnloader.lastCandidateZones(),
                rolledBack,
                since
        );
    }

    public GovernorStatus status() {
        return lastStatus;
    }

    private void checkRollback(MemorySnapshot sample) {
        if (activePolicy == null || lastChangeMs <= 0L || previousPolicy == null) {
            return;
        }
        long elapsedMs = System.currentTimeMillis() - lastChangeMs;
        if (elapsedMs > config.rollbackWindowSec * 1000L) {
            return;
        }
        if (sample.heapUsedRatio() <= heapAtChange + config.rollbackHeapDelta) {
            return;
        }

        GovernorPolicy failed = activePolicy;
        blacklistedUntilMs.put(failed.key(), System.currentTimeMillis() + 15 * 60_000L);
        outcomeTracker.onRollback(failed.key(), contextAtChange);
        activePolicy = previousPolicy;
        rolledBack = true;
        lastChangeMs = System.currentTimeMillis();
        heapAtChange = sample.heapUsedRatio();
    }

    private GovernorPolicy choosePolicy(
            GovernorPolicy pressurePolicy,
            GovernorPreset preset,
            MemorySnapshot sample,
            Map<UUID, RetentionDemand> demands
    ) {
        if (allocator.lastFootprintMb() > allocator.lastBudgetMb()) {
            return pressurePolicy;
        }

        long elapsedSec = lastChangeMs <= 0L
                ? config.policyChangeMinIntervalSec
                : (System.currentTimeMillis() - lastChangeMs) / 1000L;
        if (activePolicy != null && elapsedSec < config.policyChangeMinIntervalSec) {
            return activePolicy;
        }

        double meanDemand = meanDemand(demands);
        return bandit.select(
                preset,
                pressurePolicy.tier(),
                sample,
                meanDemand,
                elapsedSec,
                learningStore.serverContext().q50(),
                activePolicy,
                this::isBlacklisted
        );
    }

    private void commitPolicy(
            GovernorPolicy toApply,
            MemorySnapshot sample,
            Map<UUID, RetentionDemand> demands,
            long nowMs
    ) {
        if (samePolicy(activePolicy, toApply)) {
            return;
        }
        previousPolicy = activePolicy;
        activePolicy = toApply;
        lastChangeMs = nowMs;
        heapAtChange = sample.heapUsedRatio();
        rolledBack = false;

        long elapsedSec = previousPolicy == null ? config.policyChangeMinIntervalSec : 0L;
        contextAtChange = PolicyBandit.buildContext(
                sample,
                meanDemand(demands),
                elapsedSec,
                sample.tier(),
                learningStore.serverContext().q50()
        );
        outcomeTracker.onPolicyApplied(
                toApply.key(),
                contextAtChange,
                sample.heapUsedRatio(),
                sample.onlinePlayers(),
                nowMs
        );
    }

    private static double meanDemand(Map<UUID, RetentionDemand> demands) {
        if (demands.isEmpty()) {
            return 0.5D;
        }
        double sum = 0.0D;
        for (RetentionDemand demand : demands.values()) {
            sum += demand.demand();
        }
        return sum / demands.size();
    }

    private boolean isBlacklisted(String policyKey) {
        Long until = blacklistedUntilMs.get(policyKey);
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() >= until) {
            blacklistedUntilMs.remove(policyKey);
            return false;
        }
        return true;
    }

    private static boolean samePolicy(GovernorPolicy a, GovernorPolicy b) {
        return a != null && b != null && a.key().equals(b.key());
    }
}
