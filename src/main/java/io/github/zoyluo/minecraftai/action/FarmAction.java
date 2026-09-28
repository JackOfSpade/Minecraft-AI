package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.item.HoeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.OptionalInt;

public final class FarmAction {
    private FarmAction() {
    }

    public static ActionResult till(AIPlayerEntity bot, BlockPos ground) {
        ServerWorld world = bot.getEntityWorld();
        if (!isTillable(world.getBlockState(ground)) || !world.getBlockState(ground.up()).isAir()) {
            return ActionResult.failed("not_tillable");
        }
        OptionalInt hoeSlot = findHoeSlot(bot);
        if (hoeSlot.isEmpty()) {
            return ActionResult.failed("missing_hoe");
        }
        InventoryAction.equipFromSlot(bot, hoeSlot.getAsInt());
        world.setBlockState(ground, Blocks.FARMLAND.getDefaultState(), Block.NOTIFY_ALL);
        BotLog.action(bot, "till", "pos", ground);
        return ActionResult.SUCCESS;
    }

    public static ActionResult plant(AIPlayerEntity bot, BlockPos farmland, Item seed, Block crop) {
        ServerWorld world = bot.getEntityWorld();
        if (!world.getBlockState(farmland).isOf(Blocks.FARMLAND) || !world.getBlockState(farmland.up()).isAir()) {
            return ActionResult.failed("not_empty_farmland");
        }
        if (!InventoryAction.removeItems(bot, seed, 1)) {
            return ActionResult.failed("missing " + seed + " x1");
        }
        world.setBlockState(farmland.up(), crop.getDefaultState(), Block.NOTIFY_ALL);
        BotLog.action(bot, "plant", "pos", farmland.up(), "seed", seed, "crop", crop);
        return ActionResult.SUCCESS;
    }

    public static boolean isMature(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.getBlock() instanceof CropBlock cropBlock && cropBlock.isMature(state);
    }

    public static ActionResult harvest(AIPlayerEntity bot, BlockPos cropPos) {
        ServerWorld world = bot.getEntityWorld();
        if (!isMature(world, cropPos)) {
            return ActionResult.failed("not_mature");
        }
        world.breakBlock(cropPos, true, bot);
        BotLog.action(bot, "harvest", "pos", cropPos);
        return ActionResult.SUCCESS;
    }

    // Irrigation: place a water source at pos using a water bucket (simplified: directly setBlockState to a WATER source + inventory WATER_BUCKET→BUCKET).
    public static ActionResult placeWater(AIPlayerEntity bot, BlockPos pos) {
        ServerWorld world = bot.getEntityWorld();
        BlockState at = world.getBlockState(pos);
        if (!at.isAir() && !at.isOf(Blocks.WATER) && (!world.getFluidState(pos).isEmpty() || !at.isReplaceable())) {
            return ActionResult.failed("not_empty"); // target is occupied by a solid block or a non-water fluid (e.g. lava); water cannot be placed
        }
        if (!InventoryAction.removeItems(bot, Items.WATER_BUCKET, 1)) {
            return ActionResult.failed("missing_water_bucket");
        }
        world.setBlockState(pos, Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET, 1));
        BotLog.action(bot, "place_water", "pos", pos);
        return ActionResult.SUCCESS;
    }

    public static boolean isWaterSource(ServerWorld world, BlockPos pos) {
        net.minecraft.fluid.FluidState fluid = world.getFluidState(pos);
        return fluid.isIn(net.minecraft.registry.tag.FluidTags.WATER) && fluid.isStill();
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
        return state.isOf(Blocks.DIRT)
                || state.isOf(Blocks.GRASS_BLOCK)
                || state.isOf(Blocks.COARSE_DIRT)
                || state.isOf(Blocks.ROOTED_DIRT);
    }

    private static OptionalInt findHoeSlot(AIPlayerEntity bot) {
        var inventory = bot.getInventory();
        for (int slot = 0; slot < inventory.getMainStacks().size(); slot++) {
            ItemStack stack = inventory.getMainStacks().get(slot);
            if (stack.getItem() instanceof HoeItem) {
                return OptionalInt.of(slot);
            }
        }
        return OptionalInt.empty();
    }

    public record CropSpec(Item seed, Block crop, String name) {
    }
}
