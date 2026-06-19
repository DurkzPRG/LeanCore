package com.durkz.leancore.memory;

import com.durkz.leancore.config.LeanCoreConfig;
import com.durkz.leancore.intelligence.PlayerBehavior;
import com.durkz.leancore.intelligence.PlayerBehavior;
import com.durkz.leancore.intelligence.RetentionDemand;
import com.durkz.leancore.runtime.RuntimeProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyApplierLiteTest {

    @Test
    void liteUsesHigherMinClientRadius() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.minClientViewRadius = 4;
        config.liteMinClientViewRadius = 8;
        config.maxClientViewRadius = 32;

        GovernorPolicy policy = new GovernorPolicy(GovernorPreset.SOLO_LEAN, MemoryTier.COMFORT, 0.50D, 0);
        RetentionDemand demand = new RetentionDemand(0.0D, 0.0D, RetentionDemand.PRIOR_MB, PlayerBehavior.UNKNOWN);

        int liteTarget = PolicyApplier.resolveTargetClientRadius(
                config, RuntimeProfile.LITE, 10, policy, demand);
        int standardTarget = PolicyApplier.resolveTargetClientRadius(
                config, null, 10, policy, demand);

        assertEquals(8, liteTarget);
        assertEquals(4, standardTarget);
    }

    @Test
    void liteMinRadiusFloorsAggressiveScale() {
        LeanCoreConfig config = new LeanCoreConfig();
        config.minClientViewRadius = 4;
        config.liteMinClientViewRadius = 12;
        config.maxClientViewRadius = 32;

        GovernorPolicy policy = new GovernorPolicy(
                GovernorPreset.SOLO_LEAN, MemoryTier.CRITICAL, 0.76D, 6);
        RetentionDemand demand = RetentionDemand.coldStart(PlayerBehavior.UNKNOWN);

        int target = PolicyApplier.resolveTargetClientRadius(
                config, RuntimeProfile.LITE, 16, policy, demand);

        assertEquals(12, target);
    }

    @Test
    void motionBoostIsUpwardOnlyAndCapped() {
        assertEquals(20, PolicyApplier.applyMotionBoost(20, 1.0D, 32), "no scale leaves target unchanged");
        assertEquals(24, PolicyApplier.applyMotionBoost(20, 1.2D, 32), "boost rounds up");
        assertEquals(22, PolicyApplier.applyMotionBoost(20, 1.2D, 22), "boost never exceeds maxClientViewRadius");
        assertEquals(20, PolicyApplier.applyMotionBoost(20, 0.8D, 32), "scale below 1 never cuts the target");
    }
}
