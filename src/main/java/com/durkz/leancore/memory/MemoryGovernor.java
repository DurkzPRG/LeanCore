package com.durkz.leancore.memory;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.dormancy.ZoneDormancyMap;
import com.durkz.leancore.intelligence.PlayerBehavior;
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

    private GovernorPolicy activePolicy;
    private GovernorPolicy previousPolicy;
    private long lastChangeMs;
    private double heapAtChange;
    private boolean rolledBack;
    private final Map<String, Long> blacklistedUntilMs = new ConcurrentHashMap<>();

    private volatile GovernorStatus lastStatus = GovernorStatus.idle();

    public MemoryGovernor(LeanCoreConfig config, RetentionAllocator allocator, PolicyApplier applier) {
        this.config = config;
        this.allocator = allocator;
        this.applier = applier;
    }

    public void tick(
            MemorySnapshot sample,
            SessionMode mode,
            Map<UUID, PlayerBehavior> behaviors,
            ZoneDormancyMap dormancyMap
    ) {
        if (!config.enabled || !config.governEnabled) {
            lastStatus = GovernorStatus.idle();
            return;
        }

        GovernorPreset preset = GovernorPreset.resolve(config.preset, mode);
        GovernorPolicy candidate = GovernorPolicy.forTier(preset, sample.tier());
        checkRollback(sample);

        allocator.reconcile(preset, mode, sample, behaviors, dormancyMap);
        if (candidate.demoteBatch() > 0) {
            dormancyMap.demoteFarthestDormant(candidate.demoteBatch());
        }

        GovernorPolicy toApply = choosePolicy(candidate);
        int scheduled = 0;
        if (toApply != null) {
            Collection<PlayerRef> online = Universe.get().getPlayers();
            scheduled = applier.apply(toApply, online, behaviors);
            commitPolicy(toApply, sample.heapUsedRatio());
        }

        long since = lastChangeMs <= 0L ? 0L : (System.currentTimeMillis() - lastChangeMs) / 1000L;
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
        activePolicy = previousPolicy;
        rolledBack = true;
        lastChangeMs = System.currentTimeMillis();
        heapAtChange = sample.heapUsedRatio();
    }

    private GovernorPolicy choosePolicy(GovernorPolicy candidate) {
        if (isBlacklisted(candidate)) {
            return activePolicy;
        }
        if (activePolicy == null) {
            return candidate;
        }
        if (candidate.tier().ordinal() > activePolicy.tier().ordinal()) {
            return candidate;
        }
        long elapsedSec = (System.currentTimeMillis() - lastChangeMs) / 1000L;
        if (!samePolicy(activePolicy, candidate) && elapsedSec >= config.policyChangeMinIntervalSec) {
            return candidate;
        }
        if (allocator.lastFootprintMb() > allocator.lastBudgetMb()) {
            return candidate;
        }
        return activePolicy;
    }

    private void commitPolicy(GovernorPolicy toApply, double heapRatio) {
        if (samePolicy(activePolicy, toApply)) {
            return;
        }
        previousPolicy = activePolicy;
        activePolicy = toApply;
        lastChangeMs = System.currentTimeMillis();
        heapAtChange = heapRatio;
        rolledBack = false;
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
