package com.durkz.leancore.intelligence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockActionClassifierTest {

    @Test
    void pickaxeItemIdClassifiesMine() {
        BlockActionContext ctx = new BlockActionContext(
                ActionKind.UNKNOWN,
                "pickaxe",
                "tool_pickaxe_copper",
                "",
                "",
                false
        );
        assertEquals(ActionKind.MINE, BlockActionClassifier.inferFromContext(ctx));
    }

    @Test
    void axeItemIdClassifiesChop() {
        BlockActionContext ctx = new BlockActionContext(
                ActionKind.UNKNOWN,
                "axe",
                "tool_axe_iron",
                "",
                "",
                false
        );
        assertEquals(ActionKind.CHOP, BlockActionClassifier.inferFromContext(ctx));
    }
}
