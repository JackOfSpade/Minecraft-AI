package io.github.zoyluo.minecraftai.mining;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class ToolTier {
    public static final int NONE = 0;
    public static final int WOOD = 1;
    public static final int STONE = 2;
    public static final int IRON = 3;
    public static final int DIAMOND = 4;
    public static final int NETHERITE = 5;

    private ToolTier() {
    }

    public static int requiredPickaxeTier(Set<Block> blocks) {
        int required = NONE;
        if (blocks == null) {
            return required;
        }
        for (Block block : blocks) {
            required = Math.max(required, requiredPickaxeTier(block));
        }
        return required;
    }

    public static int requiredPickaxeTier(Block block) {
        // Data-driven first (mod compatibility): vanilla's tool-tier tags are the authoritative source,
        // and by convention modded ores tag themselves with these tags (e.g. new ores from mods like
        // Twilight Forest are correctly tiered with no code changes needed). If no tag matches, fall back
        // to the hand-written table below (preserves legacy behavior).
        var state = block.defaultBlockState();
        if (state.is(net.minecraft.tags.BlockTags.NEEDS_DIAMOND_TOOL)) {
            return DIAMOND;
        }
        if (state.is(net.minecraft.tags.BlockTags.NEEDS_IRON_TOOL)) {
            return IRON;
        }
        if (state.is(net.minecraft.tags.BlockTags.NEEDS_STONE_TOOL)) {
            return STONE;
        }
        // Obsidian / Crying Obsidian / Ancient Debris: require a diamond pickaxe (otherwise breaking drops nothing).
        if (block == Blocks.OBSIDIAN || block == Blocks.CRYING_OBSIDIAN || block == Blocks.ANCIENT_DEBRIS) {
            return DIAMOND;
        }
        if (block == Blocks.GOLD_ORE || block == Blocks.DEEPSLATE_GOLD_ORE
                || block == Blocks.REDSTONE_ORE || block == Blocks.DEEPSLATE_REDSTONE_ORE
                || block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE
                || block == Blocks.EMERALD_ORE || block == Blocks.DEEPSLATE_EMERALD_ORE) {
            return IRON;
        }
        if (block == Blocks.IRON_ORE || block == Blocks.DEEPSLATE_IRON_ORE
                || block == Blocks.COPPER_ORE || block == Blocks.DEEPSLATE_COPPER_ORE
                || block == Blocks.LAPIS_ORE || block == Blocks.DEEPSLATE_LAPIS_ORE) {
            return STONE;
        }
        if (block == Blocks.COAL_ORE || block == Blocks.DEEPSLATE_COAL_ORE
                || block == Blocks.STONE || block == Blocks.DEEPSLATE
                || block == Blocks.COBBLESTONE || block == Blocks.COBBLED_DEEPSLATE) {
            return WOOD;
        }
        return OreScan.isOreBlock(block) ? STONE : NONE;
    }

    public static Item requiredPickaxeItem(Set<Block> blocks) {
        return pickaxeItem(requiredPickaxeTier(blocks));
    }

    public static Item requiredPickaxeItem(Block block) {
        return pickaxeItem(requiredPickaxeTier(block));
    }

    public static String requiredPickaxeItemId(Set<Block> blocks) {
        return BuiltInRegistries.ITEM.getKey(requiredPickaxeItem(blocks)).toString();
    }

    public static String requiredPickaxeItemId(Block block) {
        return BuiltInRegistries.ITEM.getKey(requiredPickaxeItem(block)).toString();
    }

    public static int bestPickaxeTier(AIPlayerEntity bot) {
        int best = pickaxeTier(bot.getMainHandItem());
        for (ItemStack stack : bot.getInventory().getNonEquipmentItems()) {
            best = Math.max(best, pickaxeTier(stack));
        }
        best = Math.max(best, pickaxeTier(bot.getItemBySlot(EquipmentSlot.OFFHAND)));
        return best;
    }

    public static boolean canHarvestWithInventory(AIPlayerEntity bot, BlockState state) {
        if (!state.requiresCorrectToolForDrops()) {
            return true;
        }
        for (ItemStack stack : bot.getInventory().getNonEquipmentItems()) {
            if (!stack.isEmpty() && stack.isCorrectToolForDrops(state) && !nearlyBroken(stack)) {
                return true;
            }
        }
        ItemStack offHandStack = bot.getItemBySlot(EquipmentSlot.OFFHAND);
        if (!offHandStack.isEmpty() && offHandStack.isCorrectToolForDrops(state) && !nearlyBroken(offHandStack)) {
            return true;
        }
        return false;
    }

    // Planning gate: a pickaxe with 1 durability left is treated as having none at all -- discovering mid-dig that the
    // pickaxe just broke is the worst possible moment (left empty-handed against deep-layer ore), so we trip
    // need_better_tool one use early and let GoalExecutor's existing replan chain source a replacement pickaxe on the spot.
    // The same boundary is used by GoalPlanner's inventory count and the OreDig channel tool choice. It is not a wear rule: the
    // general tool choice (ToolSelector.choose) still uses such a pick until it breaks, and the next worst takes over.
    private static boolean nearlyBroken(ItemStack stack) {
        return stack.isDamageableItem() && stack.getDamageValue() >= stack.getMaxDamage() - 1;
    }

    private static Item pickaxeItem(int tier) {
        if (tier >= NETHERITE) {
            return Items.NETHERITE_PICKAXE;
        }
        if (tier >= DIAMOND) {
            return Items.DIAMOND_PICKAXE; // Obsidian tier: missing this case makes need_better_tool falsely report iron, causing the plan to fetch the wrong pickaxe
        }
        if (tier >= IRON) {
            return Items.IRON_PICKAXE;
        }
        if (tier >= STONE) {
            return Items.STONE_PICKAXE;
        }
        if (tier >= WOOD) {
            return Items.WOODEN_PICKAXE;
        }
        return Items.AIR;
    }

    public static int pickaxeTier(ItemStack stack) {
        if (stack.isEmpty() || nearlyBroken(stack)) {
            return NONE;
        }
        Item item = stack.getItem();
        if (item == Items.NETHERITE_PICKAXE) {
            return NETHERITE;
        }
        if (item == Items.DIAMOND_PICKAXE) {
            return DIAMOND;
        }
        if (item == Items.IRON_PICKAXE) {
            return IRON;
        }
        // Copper harvests exactly what stone does: data/minecraft/tags/block/incorrect_for_copper_tool.json is identical to
        // incorrect_for_stone_tool.json in the 1.21.11 jar (needs_diamond_tool and needs_iron_tool), so a copper pickaxe is a stone-tier one.
        if (item == Items.STONE_PICKAXE || item == Items.COPPER_PICKAXE) {
            return STONE;
        }
        if (item == Items.WOODEN_PICKAXE || item == Items.GOLDEN_PICKAXE) {
            return WOOD;
        }
        return NONE;
    }
}
