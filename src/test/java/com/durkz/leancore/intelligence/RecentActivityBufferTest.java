package com.durkz.leancore.intelligence;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecentActivityBufferTest {

    @Test
    void switchesRoleAfterFewChopEventsFollowingMining() {
        PlayerFeatureState state = new PlayerFeatureState(UUID.randomUUID());
        for (int i = 0; i < 4; i++) {
            state.onBlockBroken(new BlockActionContext(
                    ActionKind.MINE,
                    "pickaxe",
                    "tool_pickaxe_copper",
                    "ore_copper",
                    "ore",
                    false
            ));
        }
        assertEquals(PlayerBehavior.MINER, state.recentDominantBehavior());

        for (int i = 0; i < 6; i++) {
            state.onBlockBroken(new BlockActionContext(
                    ActionKind.CHOP,
                    "hatchet",
                    "tool_hatchet_copper",
                    "plant_oak_trunk",
                    "plant",
                    false
            ));
        }
        assertEquals(PlayerBehavior.LUMBERJACK, BehaviorPosterior.topLabel(state, null, System.currentTimeMillis()));
    }

    @Test
    void switchesFromFighterToExplorerAfterZoneDiscovery() {
        PlayerFeatureState state = new PlayerFeatureState(UUID.randomUUID());
        for (int i = 0; i < 10; i++) {
            state.onCombatHit();
        }
        assertEquals(PlayerBehavior.FIGHTER, state.recentDominantBehavior());

        for (int i = 0; i < 9; i++) {
            state.onZoneDiscovered();
        }
        assertEquals(PlayerBehavior.EXPLORER, BehaviorPosterior.topLabel(state, null, System.currentTimeMillis()));
    }
}
