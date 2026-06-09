package com.durkz.leancore.intelligence;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.inventory.ItemStack;

public record BlockActionContext(
        ActionKind kind,
        String toolGatherType,
        String itemId,
        String blockId,
        String blockGroup,
        boolean farmBlock
) {
    public static BlockActionContext unknown() {
        return new BlockActionContext(ActionKind.UNKNOWN, "", "", "", "", false);
    }

    public static BlockActionContext fromBreak(ItemStack item, BlockType block) {
        return BlockActionClassifier.classifyBreak(item, block);
    }

    public static BlockActionContext fromPlace(ItemStack item, BlockType block) {
        return BlockActionClassifier.classifyPlace(item, block);
    }
}
