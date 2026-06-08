package com.durkz.leancore.api;

import com.durkz.leancore.intelligence.PlayerBehavior;
import com.durkz.leancore.memory.MemoryTier;

import java.util.UUID;

public record LeanCoreSnapshot(
        MemoryTier tier,
        double heapUsedRatio,
        int onlinePlayers,
        UUID playerId,
        double demand,
        double confidence,
        int retentionMb,
        PlayerBehavior behaviorLabel,
        boolean holdout
) {
}
