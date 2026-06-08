package com.durkz.leancore.intelligence;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnlineLinearDemandModelTest {

    @Test
    void fallsBackToHeuristicUntilWarmup() {
        OnlineLinearDemandModel model = new OnlineLinearDemandModel();
        UUID id = UUID.randomUUID();
        PlayerFeatureState state = new PlayerFeatureState(id);
        state.onBlockBroken();
        state.onBlockPlaced();
        long now = System.currentTimeMillis();

        Map<UUID, RetentionDemand> out = model.estimate(
                Map.of(id, state),
                Map.of(id, PlayerBehavior.BUILDER),
                now
        );

        assertEquals(1, out.size());
        assertTrue(out.get(id).demand() >= 0.0D && out.get(id).demand() <= 1.0D);
        assertEquals(0, model.updates());
    }

    @Test
    void learnsAfterPositiveOutcome() {
        OnlineLinearDemandModel model = new OnlineLinearDemandModel();
        UUID id = UUID.randomUUID();
        PlayerFeatureState state = new PlayerFeatureState(id);
        for (int i = 0; i < 10; i++) {
            model.onOutcome(id, state, 0.8D, 0.1D, System.currentTimeMillis());
        }
        assertTrue(model.updates() >= 10);
    }
}
