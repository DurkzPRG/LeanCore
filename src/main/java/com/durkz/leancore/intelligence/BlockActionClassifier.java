package com.durkz.leancore.intelligence;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemTool;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemToolSpec;
import com.hypixel.hytale.server.core.inventory.ItemStack;

import java.util.Locale;

/**
 * Heuristic teacher for {@link ActivityClassifierModel} bootstrap (cold-start guardrail).
 */
public final class BlockActionClassifier {

    private BlockActionClassifier() {
    }

    public static BlockActionContext classifyBreak(ItemStack item, BlockType block) {
        String itemId = safeItemId(item);
        String gather = resolveGatherType(item);
        String blockId = block != null ? safeLower(block.getId()) : "";
        String group = block != null ? safeLower(block.getGroup()) : "";
        boolean farm = isFarmBlock(block);

        if (farm || matchesGather(gather, "hoe") || containsAny(itemId, "hoe")) {
            return ctx(ActionKind.FARM, gather, itemId, blockId, group, true);
        }
        if (matchesGather(gather, "pickaxe", "pick", "hammer", "drill")
                || containsAny(itemId, "pickaxe", "pick_", "hammer")
                || isOreOrStone(blockId, group)) {
            return ctx(ActionKind.MINE, gather, itemId, blockId, group, farm);
        }
        if (matchesGather(gather, "axe", "hatchet")
                || containsAny(itemId, "axe", "hatchet")
                || isWood(blockId, group)) {
            return ctx(ActionKind.CHOP, gather, itemId, blockId, group, farm);
        }
        if (matchesGather(gather, "shovel", "spade")
                || containsAny(itemId, "shovel", "spade")
                || isSoil(blockId, group)) {
            return ctx(ActionKind.FARM, gather, itemId, blockId, group, true);
        }
        return ctx(ActionKind.UNKNOWN, gather, itemId, blockId, group, farm);
    }

    public static BlockActionContext classifyPlace(ItemStack item, BlockType block) {
        String itemId = safeItemId(item);
        String gather = resolveGatherType(item);
        String blockId = block != null ? safeLower(block.getId()) : "";
        String group = block != null ? safeLower(block.getGroup()) : "";
        boolean farm = isFarmBlock(block);
        ActionKind kind = farm ? ActionKind.FARM : ActionKind.BUILD;
        return ctx(kind, gather, itemId, blockId, group, farm);
    }

    static ActionKind inferFromContext(BlockActionContext context) {
        if (context == null) {
            return ActionKind.UNKNOWN;
        }
        if (context.kind() != ActionKind.UNKNOWN) {
            return context.kind();
        }
        String gather = safeLower(context.toolGatherType());
        String itemId = safeLower(context.itemId());
        String blockId = safeLower(context.blockId());
        String group = safeLower(context.blockGroup());
        if (context.farmBlock() || matchesGather(gather, "hoe") || containsAny(itemId, "hoe")) {
            return ActionKind.FARM;
        }
        if (matchesGather(gather, "pickaxe", "pick", "hammer")
                || containsAny(itemId, "pickaxe", "pick_", "hammer")
                || isOreOrStone(blockId, group)) {
            return ActionKind.MINE;
        }
        if (matchesGather(gather, "axe", "hatchet")
                || containsAny(itemId, "axe", "hatchet")
                || isWood(blockId, group)) {
            return ActionKind.CHOP;
        }
        if (matchesGather(gather, "shovel", "spade")
                || containsAny(itemId, "shovel", "spade")
                || isSoil(blockId, group)) {
            return ActionKind.FARM;
        }
        return ActionKind.UNKNOWN;
    }

    private static BlockActionContext ctx(
            ActionKind kind,
            String gather,
            String itemId,
            String blockId,
            String group,
            boolean farm
    ) {
        return new BlockActionContext(kind, gather, itemId, blockId, group, farm);
    }

    static String resolveGatherType(ItemStack item) {
        if (item == null || item.isEmpty()) {
            return "";
        }
        Item config = item.getItem();
        if (config == null) {
            return "";
        }
        ItemTool tool = config.getTool();
        if (tool == null) {
            return "";
        }
        ItemToolSpec[] specs = tool.getSpecs();
        if (specs == null || specs.length == 0) {
            return "";
        }
        for (ItemToolSpec spec : specs) {
            if (spec == null) {
                continue;
            }
            String gather = spec.getGatherType();
            if (gather != null && !gather.isBlank()) {
                return safeLower(gather);
            }
        }
        return "";
    }

    private static boolean isFarmBlock(BlockType block) {
        if (block == null) {
            return false;
        }
        if (block.getFarming() != null) {
            return true;
        }
        var gathering = block.getGathering();
        return gathering != null && gathering.isHarvestable();
    }

    private static boolean isOreOrStone(String blockId, String group) {
        return containsAny(blockId, "ore", "stone", "rock", "crystal", "mineral", "gem", "coal", "copper", "iron",
                "silver", "gold", "mithril", "cobalt", "adamant", "sandstone", "granite", "basalt", "gravel")
                || containsAny(group, "ore", "stone", "rock", "crystal", "mineral", "gem", "coal", "copper", "iron",
                "silver", "gold", "mithril", "cobalt", "adamant", "sandstone", "granite", "basalt", "gravel");
    }

    private static boolean isWood(String blockId, String group) {
        return containsAny(blockId, "wood", "log", "trunk", "bark", "leaf", "leaves", "plank", "lumber", "tree")
                || containsAny(group, "wood", "log", "trunk", "bark", "leaf", "leaves", "plank", "lumber", "tree");
    }

    private static boolean isSoil(String blockId, String group) {
        return containsAny(blockId, "soil", "dirt", "farmland", "crop", "seed", "grass", "hay", "mulch", "farm")
                || containsAny(group, "soil", "dirt", "farmland", "crop", "seed", "grass", "hay", "mulch", "farm");
    }

    private static String safeItemId(ItemStack item) {
        if (item == null || item.isEmpty()) {
            return "";
        }
        String id = item.getItemId();
        return id == null ? "" : safeLower(id);
    }

    private static String safeLower(String raw) {
        return raw == null ? "" : raw.toLowerCase(Locale.ROOT);
    }

    private static boolean matchesGather(String gather, String... tokens) {
        if (gather == null || gather.isBlank()) {
            return false;
        }
        for (String token : tokens) {
            if (gather.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String value, String... tokens) {
        if (value == null || value.isEmpty() || tokens == null) {
            return false;
        }
        String lower = safeLower(value);
        for (String token : tokens) {
            if (token != null && lower.contains(token)) {
                return true;
            }
        }
        return false;
    }
}
