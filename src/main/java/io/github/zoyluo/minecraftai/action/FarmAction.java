package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Farming as a player does it: every world change is a real vanilla item use or block break, so the
 * hoe wears, seeds obey the light and support rules, and bone meal is consumed by the item itself.
 * Each use is preceded by the exact-face (or exact-outline) ray proof inside reach; nothing here
 * writes blocks or edits the inventory.
 */
public final class FarmAction {
    private FarmAction() {
    }

    /** Tills the dirt-family block {@code ground} by using a hoe on its top face. */
    public static ActionResult till(AIPlayerEntity bot, BlockPos ground) {
        ServerLevel world = bot.level();
        if (!isTillable(world.getBlockState(ground)) || !world.getBlockState(ground.above()).isAir()) {
            return ActionResult.failed("not_tillable");
        }
        OptionalInt hoeSlot = findHoeSlot(bot);
        if (hoeSlot.isEmpty()) {
            return ActionResult.failed("missing_hoe");
        }
        InventoryAction.equipFromSlot(bot, hoeSlot.getAsInt());
        BlockState before = world.getBlockState(ground);
        ActionResult used = BuildAction.useItemOnFace(bot, ground, Direction.UP, InteractionHand.MAIN_HAND);
        if (used.isFailed()) {
            return ActionResult.failed("till_" + used.reason());
        }
        if (world.getBlockState(ground).equals(before)) {
            return ActionResult.failed("till_no_effect");
        }
        BotLog.action(bot, "till", "pos", ground, "result", world.getBlockState(ground).getBlock());
        return ActionResult.SUCCESS;
    }

    /** Plants {@code seed} on {@code farmland} by using it on the farmland's top face. */
    public static ActionResult plant(AIPlayerEntity bot, BlockPos farmland, Item seed, Block crop) {
        ServerLevel world = bot.level();
        BlockPos cropPos = farmland.above();
        if (!world.getBlockState(farmland).is(Blocks.FARMLAND) || !world.getBlockState(cropPos).isAir()) {
            return ActionResult.failed("not_empty_farmland");
        }
        // The seed's own placement rule (crops need light >= 8): checked up front so a refusal never
        // reaches a consumable seed item (a carrot or potato would otherwise start being eaten).
        if (!crop.defaultBlockState().canSurvive(world, cropPos)) {
            return ActionResult.failed("too_dark_or_unsupported");
        }
        OptionalInt slot = InventoryAction.findItem(bot, seed);
        if (slot.isEmpty()) {
            return ActionResult.failed("missing " + seed + " x1");
        }
        InventoryAction.equipFromSlot(bot, slot.getAsInt());
        ActionResult used = BuildAction.useItemOnFace(bot, farmland, Direction.UP, InteractionHand.MAIN_HAND);
        if (used.isFailed()) {
            return ActionResult.failed("plant_" + used.reason());
        }
        if (!world.getBlockState(cropPos).is(crop)) {
            return ActionResult.failed("plant_no_effect");
        }
        BotLog.action(bot, "plant", "pos", cropPos, "seed", seed, "crop", crop);
        return ActionResult.SUCCESS;
    }

    public static boolean isMature(ServerLevel world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.getBlock() instanceof CropBlock cropBlock && cropBlock.isMaxAge(state);
    }

    /**
     * Click-time proof for breaking a ripe crop: it is still ripe, inside the physical block
     * interaction reach, and its outline is actually visible. The break itself is a real mining
     * action (BlockMiner), so tools, swing and the vanilla break path all apply.
     */
    public static ActionResult harvestProof(AIPlayerEntity bot, BlockPos cropPos) {
        if (!isMature(bot.level(), cropPos)) {
            return ActionResult.failed("not_mature");
        }
        if (!bot.isWithinBlockInteractionRange(cropPos, 0.0D)) {
            return ActionResult.failed("out_of_reach");
        }
        if (!ObservableWorldQuery.canObserveFarmCell(bot, cropPos)) {
            return ActionResult.failed("crop_not_visible");
        }
        return ActionResult.SUCCESS;
    }

    /** Whether the crop at {@code cropPos} can still grow and accept bone meal (vanilla's own rule). */
    public static boolean isBonemealTarget(ServerLevel world, BlockPos cropPos) {
        BlockState state = world.getBlockState(cropPos);
        return state.getBlock() instanceof CropBlock
                && state.getBlock() instanceof BonemealableBlock bonemealable
                && bonemealable.isValidBonemealTarget(world, cropPos, state);
    }

    /** Applies one bone meal to the growing crop at {@code cropPos} with the item's own use. */
    public static ActionResult boneMeal(AIPlayerEntity bot, BlockPos cropPos) {
        ServerLevel world = bot.level();
        if (!isBonemealTarget(world, cropPos)) {
            return ActionResult.failed("not_a_growing_crop");
        }
        OptionalInt slot = InventoryAction.findItem(bot, Items.BONE_MEAL);
        if (slot.isEmpty()) {
            return ActionResult.failed("missing_bone_meal");
        }
        InventoryAction.equipFromSlot(bot, slot.getAsInt());
        BlockState before = world.getBlockState(cropPos);
        int countBefore = InventoryAction.countItem(bot, Items.BONE_MEAL);
        ActionResult used = BuildAction.useItemOnCell(bot, cropPos, InteractionHand.MAIN_HAND);
        if (used.isFailed()) {
            return ActionResult.failed("bone_meal_" + used.reason());
        }
        BotLog.action(bot, "bone_meal", "pos", cropPos,
                "grew", !world.getBlockState(cropPos).equals(before),
                "left", InventoryAction.countItem(bot, Items.BONE_MEAL),
                "used", countBefore - InventoryAction.countItem(bot, Items.BONE_MEAL));
        return ActionResult.SUCCESS;
    }

    public static boolean isWaterSource(ServerLevel world, BlockPos pos) {
        net.minecraft.world.level.material.FluidState fluid = world.getFluidState(pos);
        return fluid.is(net.minecraft.tags.FluidTags.WATER) && fluid.isSource();
    }

    public static CropSpec cropSpec(String cropName) {
        return switch (cropName) {
            case "wheat", "minecraft:wheat" -> new CropSpec(Items.WHEAT_SEEDS, Blocks.WHEAT, "wheat");
            case "carrot", "carrots", "minecraft:carrot", "minecraft:carrots" -> new CropSpec(Items.CARROT, Blocks.CARROTS, "carrot");
            case "potato", "potatoes", "minecraft:potato", "minecraft:potatoes" -> new CropSpec(Items.POTATO, Blocks.POTATOES, "potato");
            default -> throw new IllegalArgumentException("unknown_crop: " + cropName);
        };
    }

    public static boolean isTillable(BlockState state) {
        return state.is(Blocks.DIRT)
                || state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.ROOTED_DIRT);
    }

    private static OptionalInt findHoeSlot(AIPlayerEntity bot) {
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getNonEquipmentItems().size(); slot++) {
            ItemStack stack = inventory.getNonEquipmentItems().get(slot);
            if (stack.getItem() instanceof HoeItem) {
                return OptionalInt.of(slot);
            }
        }
        return OptionalInt.empty();
    }

    public record CropSpec(Item seed, Block crop, String name) {
    }
}
