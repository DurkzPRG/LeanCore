package com.durkz.leancore.memory;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.dormancy.ZoneDormancyMap;
import com.durkz.leancore.intelligence.LearningStore;
import com.durkz.leancore.intelligence.RetentionDemand;
import com.durkz.leancore.intelligence.RollbackMonitor;
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
    private final LearningStore learningStore;
    private final RollbackMonitor rollbackMonitor;

    private GovernorPolicy activePolicy;
    private GovernorPolicy previousPolicy;
    private long lastChangeMs;
    private double heapAtChange;
    private boolean rolledBack;
    private final Map<String, Long> blacklistedUntilMs = new ConcurrentHashMap<>();

    private volatile GovernorStatus lastStatus = GovernorStatus.idle();

    public MemoryGovernor(
            LeanCoreConfig config,
            RetentionAllocator allocator,
            PolicyApplier applier,
            LearningStore learningStore
    ) {
        this.config = config;
        this.allocator = allocator;
        this.applier = applier;
        this.learningStore = learningStore;
        this.rollbackMonitor = new RollbackMonitor(learningStore);
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
        rollbackMonitor.tick(sample.heapUsedRatio(), nowMs);

        GovernorPreset preset = GovernorPreset.resolve(config.preset, mode);
        GovernorPolicy candidate = GovernorPolicy.forTier(preset, sample.tier());
        checkRollback(sample);

        allocator.reconcile(preset, mode, sample, demands, dormancyMap);
        if (candidate.demoteBatch() > 0) {
            dormancyMap.demoteFarthestDormant(candidate.demoteBatch());
        }

        GovernorPolicy toApply = choosePolicy(candidate);
        int scheduled = 0;
        if (toApply != null) {
            Collection<PlayerRef> online = Universe.get().getPlayers();
            scheduled = applier.apply(toApply, online, demands);
            commitPolicy(toApply, sample.heapUsedRatio(), nowMs);
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
        rollbackMonitor.onRollback(failed.key());
        activePolicy = previousPolicy;
        rolledBack = true;
        lastChangeMs = System.currentTimeMillis();
        heapAtChange = sample.heapUsedRatio();
    }

    private GovernorPolicy choosePolicy(GovernorPolicy candidate) {
        if (isBlacklisted(candidate) || learningStore.isPolicyDeprioritized(candidate.key())) {
            return activePolicy;
        }
        if (activePolicy == null) {
            return candidate;
        }
        if (candidate.tier().ordinal() > activePolicy.tier().ordinal()) {
            return preferHigherScore(activePolicy, candidate);
        }
        long elapsedSec = (System.currentTimeMillis() - lastChangeMs) / 1000L;
        if (!samePolicy(activePolicy, candidate) && elapsedSec >= config.policyChangeMinIntervalSec) {
            return preferHigherScore(activePolicy, candidate);
        }
        if (allocator.lastFootprintMb() > allocator.lastBudgetMb()) {
            return preferHigherScore(activePolicy, candidate);
        }
        return activePolicy;
    }

    private GovernorPolicy preferHigherScore(GovernorPolicy current, GovernorPolicy candidate) {
        if (learningStore.policyScore(candidate.key()) >= learningStore.policyScore(current.key())) {
            return candidate;
        }
        return current;
    }

    private void commitPolicy(GovernorPolicy toApply, double heapRatio, long nowMs) {
        if (samePolicy(activePolicy, toApply)) {
            return;
        }
        previousPolicy = activePolicy;
        activePolicy = toApply;
        lastChangeMs = nowMs;
        heapAtChange = heapRatio;
        rolledBack = false;
        rollbackMonitor.onPolicyApplied(toApply.key(), heapRatio, nowMs);
    }

    private boolean isBlacklisted(GovernorPolicy policy) {
        Long until = blacklistedUntilMs.get(policy.key());
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() >= until) {
            blacklistedUntilMs.remove(policy.key());
            return false;
        }
        return true;
    }

    private static boolean samePolicy(GovernorPolicy a, GovernorPolicy b) {
        return a != null && b != null && a.key().equals(b.key());
    }
}
