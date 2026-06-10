package com.durkz.leancore.intelligence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActionKindTest {

    @Test
    void craftMapsToCrafterNotFarmer() {
        assertEquals(PlayerBehavior.CRAFTER, ActionKind.CRAFT.toBehavior());
        assertEquals(PlayerBehavior.CRAFTER.ordinal(), ActionKind.CRAFT.teacherIndex());
    }
}
