package io.github.zoyluo.minecraftai.mining;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;

import java.util.Set;

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
        var state = block.getDefaultState();
        if (state.isIn(net.minecraft.registry.tag.BlockTags.NEEDS_DIAMOND_TOOL)) {
            return DIAMOND;
        }
        if (state.isIn(net.minecraft.registry.tag.BlockTags.NEEDS_IRON_TOOL)) {
            return IRON;
        }
        if (state.isIn(net.minecraft.registry.tag.BlockTags.NEEDS_STONE_TOOL)) {
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
        return Registries.ITEM.getId(requiredPickaxeItem(blocks)).toString();
    }

    public static String requiredPickaxeItemId(Block block) {
        return Registries.ITEM.getId(requiredPickaxeItem(block)).toString();
    }

    public static int bestPickaxeTier(AIPlayerEntity bot) {
        int best = pickaxeTier(bot.getMainHandStack());
        for (ItemStack stack : bot.getInventory().getMainStacks()) {
            best = Math.max(best, pickaxeTier(stack));
        }
        best = Math.max(best, pickaxeTier(bot.getEquippedStack(EquipmentSlot.OFFHAND)));
        return best;
    }

    public static boolean canHarvestWithInventory(AIPlayerEntity bot, BlockState state) {
        if (!state.isToolRequired()) {
            return true;
        }
        for (ItemStack stack : bot.getInventory().getMainStacks()) {
            if (!stack.isEmpty() && stack.isSuitableFor(state) && !nearlyBroken(stack)) {
                return true;
            }
        }
        ItemStack offHandStack = bot.getEquippedStack(EquipmentSlot.OFFHAND);
        if (!offHandStack.isEmpty() && offHandStack.isSuitableFor(state) && !nearlyBroken(offHandStack)) {
            return true;
        }
        return false;
    }

    // Durability gate: a pickaxe with 1 durability left is treated as having none at all -- discovering
    // mid-dig that the pickaxe just broke is the worst possible moment (left empty-handed against deep-layer
    // ore), so we trip need_better_tool one point early and let GoalExecutor's existing replan chain source
    // a replacement pickaxe on the spot. This matches ToolSelector's "near-exhausted score of 0.001" at the
    // same threshold, keeping the two layers consistent.
    private static boolean nearlyBroken(ItemStack stack) {
        return stack.isDamageable() && stack.getDamage() >= stack.getMaxDamage() - 1;
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
        if (item == Items.STONE_PICKAXE) {
            return STONE;
        }
        if (item == Items.WOODEN_PICKAXE || item == Items.GOLDEN_PICKAXE) {
            return WOOD;
        }
        return NONE;
    }
}
