package com.durkz.leancore.intelligence;

import java.util.Map;
import java.util.UUID;

public interface DemandModel {

    String name();

    Map<UUID, RetentionDemand> estimate(
            Map<UUID, PlayerFeatureState> features,
            Map<UUID, PlayerBehavior> debugLabels,
            long nowMs
    );

    default void onOutcome(UUID playerId, PlayerFeatureState state, double targetDemand, double reward, long nowMs) {
    }
}
