package com.durkz.leancore.intelligence;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BehaviorPosteriorTest {

    @Test
    void idlePlayerSkewsAfk() {
        UUID id = UUID.randomUUID();
        long base = System.currentTimeMillis();
        PlayerFeatureState state = new PlayerFeatureState(id);
        ActivityClassifierModel model = new ActivityClassifierModel();
        PlayerBehavior label = BehaviorPosterior.topLabel(state, model, base + 800_000L);
        assertEquals(PlayerBehavior.AFK, label);
    }

    @Test
    void miningEmasSkewMinerBeforeModelWarmup() {
        UUID id = UUID.randomUUID();
        PlayerFeatureState state = new PlayerFeatureState(id);
        for (int i = 0; i < 15; i++) {
            state.onBlockBroken(new BlockActionContext(
                    ActionKind.MINE,
                    "pickaxe",
                    "tool_pickaxe_copper",
                    "ore_copper",
                    "ore",
                    false
            ));
        }
        PlayerBehavior label = BehaviorPosterior.topLabelFromActivityEmas(state, System.currentTimeMillis());
        assertEquals(PlayerBehavior.MINER, label);
    }

    @Test
    void scoresSumToOneFromModel() {
        ActivityClassifierModel model = new ActivityClassifierModel();
        model.train(ActionKind.MINE);
        PlayerFeatureState state = new PlayerFeatureState(UUID.randomUUID());
        state.onBlockBroken(new BlockActionContext(
                ActionKind.MINE,
                "pickaxe",
                "tool_pickaxe_copper",
                "ore_copper",
                "ore",
                false
        ));
        double[] scores = model.posterior(state, System.currentTimeMillis());
        double sum = 0.0D;
        for (double score : scores) {
            sum += score;
        }
        assertTrue(sum > 0.99D && sum < 1.01D);
    }
}
