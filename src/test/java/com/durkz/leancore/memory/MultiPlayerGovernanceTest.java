package com.durkz.leancore.memory;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.dormancy.ZoneDormancyMap;
import com.durkz.leancore.intelligence.PlayerBehavior;
import com.durkz.leancore.intelligence.RetentionDemand;
import com.durkz.leancore.runtime.RuntimeProfile;
import com.durkz.leancore.session.SessionMode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Server-profile decisions that depend on several players at once, checked without a server. */
class MultiPlayerGovernanceTest {

    private static final long MB = 1024L * 1024L;

    @Test
    void underTheSamePolicyBusyPlayersKeepMoreViewThanIdleOnes() {
        LeanCoreConfig config = new LeanCoreConfig();
        GovernorPolicy tight = GovernorPolicy.forTier(GovernorPreset.SERVER_DENSE, MemoryTier.TIGHT);
        RetentionDemand miner = new RetentionDemand(0.95D, 0.9D, 64, PlayerBehavior.MINER);
        RetentionDemand afk = new RetentionDemand(0.05D, 0.9D, 12, PlayerBehavior.UNKNOWN);

        int minerRadius = PolicyApplier.resolveTargetClientRadius(config, RuntimeProfile.FULL, 16, tight, miner);
        int afkRadius = PolicyApplier.resolveTargetClientRadius(config, RuntimeProfile.FULL, 16, tight, afk);

        assertTrue(minerRadius > afkRadius, minerRadius + " vs " + afkRadius);
        assertTrue(afkRadius >= config.minClientViewRadius);
    }

    @Test
    void criticalCutsEveryPlayerButNeverBelowTheServerFloor() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.minClientViewRadius = 6;
        GovernorPolicy critical = GovernorPolicy.forTier(GovernorPreset.SERVER_DENSE, MemoryTier.CRITICAL);
        GovernorPolicy comfort = GovernorPolicy.forTier(GovernorPreset.SERVER_DENSE, MemoryTier.COMFORT);
        for (int i = 0; i < 20; i++) {
            RetentionDemand demand = new RetentionDemand(i / 19.0D, 0.5D, 40, PlayerBehavior.UNKNOWN);
            int cut = PolicyApplier.resolveTargetClientRadius(config, RuntimeProfile.FULL, 12, critical, demand);
            int full = PolicyApplier.resolveTargetClientRadius(config, RuntimeProfile.FULL, 12, comfort, demand);
            assertTrue(cut <= full, "player " + i);
            assertTrue(cut >= 6, "player " + i);
        }
    }

    @Test
    void comfortGivesEveryPlayerTheirOwnRadiusBack() {
        LeanCoreConfig config = new LeanCoreConfig();
        for (GovernorPreset preset : GovernorPreset.values()) {
            GovernorPolicy comfort = GovernorPolicy.forTier(preset, MemoryTier.COMFORT);
            assertEquals(1.0D, comfort.viewScale(), 1e-9, preset.name());
            RetentionDemand idle = new RetentionDemand(0.0D, 0.9D, 12, PlayerBehavior.UNKNOWN);
            assertEquals(23, PolicyApplier.resolveTargetClientRadius(config, RuntimeProfile.FULL, 23, comfort, idle));
        }
    }

    @Test
    void cutsAreRelativeToTheRequestedRadiusSoTheyDoNotCompound() {
        LeanCoreConfig config = new LeanCoreConfig();
        GovernorPolicy tight = GovernorPolicy.forTier(GovernorPreset.SERVER_DENSE, MemoryTier.TIGHT);
        RetentionDemand demand = new RetentionDemand(0.5D, 0.5D, 40, PlayerBehavior.UNKNOWN);
        int first = PolicyApplier.resolveTargetClientRadius(config, RuntimeProfile.FULL, 23, tight, demand);
        // The next pass starts from the requested 23 again, not from `first`.
        int second = PolicyApplier.resolveTargetClientRadius(config, RuntimeProfile.FULL, 23, tight, demand);
        assertEquals(first, second);
        assertTrue(first > config.minClientViewRadius, "TIGHT on a 23-chunk request should not hit the floor: " + first);
    }

    @Test
    void rollbackOnlyUndoesAReliefNeverACut() {
        GovernorPolicy comfort = GovernorPolicy.forTier(GovernorPreset.SERVER_DENSE, MemoryTier.COMFORT);
        GovernorPolicy tight = GovernorPolicy.forTier(GovernorPreset.SERVER_DENSE, MemoryTier.TIGHT);
        // Went COMFORT -> TIGHT (a cut): heap still rising must not hand the view back.
        assertFalse(MemoryGovernor.relaxed(tight, comfort));
        // Went TIGHT -> COMFORT (a relief): heap rising right after is a reason to tighten again.
        assertTrue(MemoryGovernor.relaxed(comfort, tight));
        assertFalse(MemoryGovernor.relaxed(tight, tight));
    }

    @Test
    void retentionFootprintGrowsWithPlayersAndOverflowsTheServerBudget() {
        LeanCoreConfig config = new LeanCoreConfig();
        RetentionAllocator allocator = new RetentionAllocator(config);
        MemorySnapshot sample = new MemorySnapshot(1024L * MB, 4096L * MB, 0.25D, 30, 0.0D, MemoryTier.COMFORT);

        allocator.reconcile(GovernorPreset.SERVER_DENSE, SessionMode.SERVER, sample, demands(5), new ZoneDormancyMap(config));
        int fiveMb = allocator.lastFootprintMb();
        assertTrue(fiveMb <= allocator.lastBudgetMb(), fiveMb + " vs " + allocator.lastBudgetMb());

        allocator.reconcile(GovernorPreset.SERVER_DENSE, SessionMode.SERVER, sample, demands(30), new ZoneDormancyMap(config));
        assertEquals(fiveMb * 6, allocator.lastFootprintMb());
        assertTrue(allocator.lastFootprintMb() > allocator.lastBudgetMb());
    }

    private static Map<UUID, RetentionDemand> demands(int players) {
        Map<UUID, RetentionDemand> out = new HashMap<>();
        for (int i = 0; i < players; i++) {
            out.put(UUID.randomUUID(), new RetentionDemand(0.5D, 0.5D, 40, PlayerBehavior.UNKNOWN));
        }
        return out;
    }
}
