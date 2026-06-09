package com.durkz.leancore.intelligence;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityClassifierModelTest {

    @Test
    void learnsMinerLabelAfterMiningEvents() {
        ActivityClassifierModel model = new ActivityClassifierModel();
        for (int i = 0; i < 12; i++) {
            model.train(ActionKind.MINE);
        }
        PlayerFeatureState state = new PlayerFeatureState(UUID.randomUUID());
        for (int i = 0; i < 20; i++) {
            state.onBlockBroken(new BlockActionContext(
                    ActionKind.MINE,
                    "pickaxe",
                    "tool_pickaxe_copper",
                    "ore_copper",
                    "ore",
                    false
            ));
        }
        assertEquals(PlayerBehavior.MINER, model.topLabel(state, System.currentTimeMillis()));
        assertTrue(model.updates() >= 12);
    }
}
