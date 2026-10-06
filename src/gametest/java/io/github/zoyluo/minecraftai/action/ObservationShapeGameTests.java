package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Shape-aware observation and shift-placement regressions. A chest (inset 1/16, 14/16 tall), farmland
 * (15/16), a bottom slab, a bed, a cake or a snow layer does not reach the cell faces the observation
 * rays used to aim at, so such a block in plain view was never observable. Placing against a chest,
 * door, lever, crafting table and the like must place the block, not use the support.
 */
public final class ObservationShapeGameTests {
    private static BlockState bed(Direction facing, BedPart part) {
        return Blocks.RED_BED.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                .setValue(BlockStateProperties.BED_PART, part);
    }

    /** Shaped blocks lying in plain view of the bot (its feet on the floor, target three blocks east). */
    @GameTest(environment = "minecraftai-gametest:observation_shape_game_tests_shaped_blocks_in_plain_view_are_observable", maxTicks = 60)
    public void shapedBlocksInPlainViewAreObservable(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(2, 4, 4));
        prepare(context, feet);
        AIPlayerEntity bot = spawn(context, "ObsShapeOpen", feet, Vec3.atBottomCenterOf(feet));
        BlockPos target = feet.east(3);
        List<String> failures = new ArrayList<>();
        BlockState[] states = {
                Blocks.STONE.defaultBlockState(),
                Blocks.CHEST.defaultBlockState(),
                Blocks.ENDER_CHEST.defaultBlockState(),
                Blocks.FARMLAND.defaultBlockState(),
                Blocks.DIRT_PATH.defaultBlockState(),
                Blocks.OAK_SLAB.defaultBlockState(),
                Blocks.CAKE.defaultBlockState(),
                Blocks.ENCHANTING_TABLE.defaultBlockState(),
                Blocks.SNOW.defaultBlockState(),
                bed(Direction.EAST, BedPart.FOOT),
        };
        for (BlockState state : states) {
            context.getLevel().setBlock(target, state, Block.UPDATE_ALL);
            if (state.is(Blocks.RED_BED)) {
                context.getLevel().setBlock(target.east(), bed(Direction.EAST, BedPart.HEAD), Block.UPDATE_ALL);
            }
            check(failures, bot, target, state);
            context.getLevel().setBlock(target, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            context.getLevel().setBlock(target.east(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), "ObsShapeOpen");
        finish(context, failures);
    }

    /** A one-layer snow block is exposed but its outline does not reach the unit-cell faces. */
    @GameTest(environment = "minecraftai-gametest:observation_shape_game_tests_exposed_snow_uses_the_cell_ray_mining_fallback", maxTicks = 60)
    public void exposedSnowUsesTheCellRayMiningFallback(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(2, 4, 4));
        prepare(context, feet);
        AIPlayerEntity bot = spawn(context, "ObsSnowFallback", feet, Vec3.atBottomCenterOf(feet));
        BlockPos target = feet.east(3);
        context.getLevel().setBlock(target, Blocks.SNOW.defaultBlockState(), Block.UPDATE_ALL);
        List<String> failures = new ArrayList<>();
        if (ObservableWorldQuery.canObserveBlockCellFace(bot, target)) {
            failures.add("snow unexpectedly reached a unit-cell face");
        }
        if (!ObservableWorldQuery.canObserveCell(bot, target)) {
            failures.add("canObserveCell(snow)");
        }
        if (!ObservableWorldQuery.canObserveBlock(bot, target)) {
            failures.add("canObserveBlock(snow)");
        }
        if (!MiningController.currentObservedTarget(bot, target)) {
            failures.add("currentObservedTarget(snow)");
        }
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), "ObsSnowFallback");
        finish(context, failures);
    }

    /** The same blocks with only their top face exposed (bot above on a pillar, walls on the four sides). */
    @GameTest(environment = "minecraftai-gametest:observation_shape_game_tests_shaped_blocks_exposed_only_on_top_are_observable", maxTicks = 60)
    public void shapedBlocksExposedOnlyOnTopAreObservable(GameTestHelper context) {
        BlockPos floor = context.absolutePos(new BlockPos(2, 4, 4));
        prepare(context, floor);
        for (int dy = -1; dy <= 1; dy++) {
            context.getLevel().setBlock(floor.offset(0, dy, 0), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        BlockPos feet = floor.above(2);
        // Stand on the east edge of the pillar: the ray to a low top face then clears the west wall by a margin.
        AIPlayerEntity bot = spawn(context, "ObsShapeTop", feet,
                new Vec3(feet.getX() + 0.85D, feet.getY(), feet.getZ() + 0.5D));
        BlockPos target = floor.east(2);
        for (Direction wall : Direction.Plane.HORIZONTAL) {
            context.getLevel().setBlock(target.relative(wall), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        List<String> failures = new ArrayList<>();
        BlockState[] states = {
                Blocks.STONE.defaultBlockState(),
                Blocks.CHEST.defaultBlockState(),
                Blocks.FARMLAND.defaultBlockState(),
                Blocks.DIRT_PATH.defaultBlockState(),
                Blocks.OAK_SLAB.defaultBlockState(),
                Blocks.CAKE.defaultBlockState(),
                Blocks.ENCHANTING_TABLE.defaultBlockState(),
                Blocks.SNOW.defaultBlockState(),
        };
        for (BlockState state : states) {
            context.getLevel().setBlock(target, state, Block.UPDATE_ALL);
            check(failures, bot, target, state);
            context.getLevel().setBlock(target, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        // A shaped block that really is hidden must stay hidden: a chest boxed in by stone.
        BlockPos hidden = target.east(2);
        context.getLevel().setBlock(hidden, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(target, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        for (Direction side : new Direction[]{Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            context.getLevel().setBlock(hidden.relative(side), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        if (ObservableWorldQuery.canObserveBlock(bot, hidden)
                || ObservableWorldQuery.canObserveBlockWithInsetFaces(bot, hidden)) {
            failures.add("chest behind a wall became observable");
        }
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), "ObsShapeTop");
        finish(context, failures);
    }

    private record Case(String name, BlockState state, Direction face, boolean door, boolean bed, boolean wall) {
    }

    /** Placing against a chest, door, lever, crafting table, ... places the block and uses nothing. */
    @GameTest(environment = "minecraftai-gametest:observation_shape_game_tests_placing_against_interactive_blocks_places_and_never_uses_them", maxTicks = 100)
    public void placingAgainstInteractiveBlocksPlacesAndNeverUsesThem(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(2, 4, 4));
        prepare(context, feet);
        AIPlayerEntity bot = spawn(context, "ObsShapePlace", feet, Vec3.atBottomCenterOf(feet));
        BlockPos support = feet.east(2);
        List<String> failures = new ArrayList<>();
        var world = context.getLevel();
        BlockState door = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        BlockState onWall = Blocks.STONE.defaultBlockState();
        Case[] cases = {
                new Case("chest", Blocks.CHEST.defaultBlockState(), Direction.UP, false, false, false),
                new Case("crafting_table", Blocks.CRAFTING_TABLE.defaultBlockState(), Direction.UP, false, false, false),
                new Case("furnace", Blocks.FURNACE.defaultBlockState(), Direction.UP, false, false, false),
                new Case("barrel", Blocks.BARREL.defaultBlockState(), Direction.UP, false, false, false),
                new Case("cake", Blocks.CAKE.defaultBlockState(), Direction.UP, false, false, false),
                new Case("bed", bed(Direction.EAST, BedPart.FOOT), Direction.UP, false, true, false),
                new Case("trapdoor", Blocks.OAK_TRAPDOOR.defaultBlockState(), Direction.UP, false, false, false),
                new Case("door", door, Direction.WEST, true, false, false),
                new Case("fence_gate", Blocks.OAK_FENCE_GATE.defaultBlockState()
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST), Direction.WEST, false, false, false),
                new Case("lever", Blocks.LEVER.defaultBlockState()
                        .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST), Direction.WEST, false, false, true),
                new Case("button", Blocks.STONE_BUTTON.defaultBlockState()
                        .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST), Direction.WEST, false, false, true),
        };
        for (Case c : cases) {
            if (c.wall()) {
                world.setBlock(support.east(), onWall, Block.UPDATE_ALL);
            }
            world.setBlock(support, c.state(), Block.UPDATE_ALL);
            if (c.door()) {
                world.setBlock(support.above(), c.state().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,
                        DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
            }
            if (c.bed()) {
                world.setBlock(support.east(), bed(Direction.EAST, BedPart.HEAD), Block.UPDATE_ALL);
            }
            BlockState before = world.getBlockState(support);
            BlockPos destination = support.relative(c.face());
            bot.getInventory().setSelectedSlot(0);
            bot.getInventory().getNonEquipmentItems().set(0, new ItemStack(Items.COBBLESTONE, 8));
            bot.getInventory().setChanged();
            bot.setShiftKeyDown(false);
            ActionResult result = BuildAction.placeBlock(bot, support, c.face(), InteractionHand.MAIN_HAND);
            if (!result.isSuccess()) {
                failures.add(c.name() + ": placement failed: " + result.reason());
            }
            if (!world.getBlockState(destination).is(Blocks.COBBLESTONE)) {
                failures.add(c.name() + ": destination is " + world.getBlockState(destination));
            }
            if (!world.getBlockState(support).equals(before)) {
                failures.add(c.name() + ": support changed " + before + " -> " + world.getBlockState(support));
            }
            if (bot.containerMenu != bot.inventoryMenu) {
                failures.add(c.name() + ": opened a menu on the bot");
                bot.closeContainer();
            }
            if (bot.isShiftKeyDown()) {
                failures.add(c.name() + ": shift left pressed after the placement");
            }
            if (bot.getMainHandItem().getCount() != 7) {
                failures.add(c.name() + ": cobblestone count " + bot.getMainHandItem().getCount());
            }
            for (int dx = 0; dx <= 3; dx++) {
                for (int dy = 0; dy <= 2; dy++) {
                    world.setBlock(feet.offset(1 + dx, dy, 0), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), "ObsShapePlace");
        finish(context, failures);
    }

    /** placeBlockAt with the crafting table / chest as the only support (the block below the cell). */
    @GameTest(environment = "minecraftai-gametest:observation_shape_game_tests_place_at_over_a_chest_and_a_crafting_table_places_the_block", maxTicks = 60)
    public void placeAtOverAChestAndACraftingTablePlacesTheBlock(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(2, 4, 4));
        prepare(context, feet);
        AIPlayerEntity bot = spawn(context, "ObsShapePlaceAt", feet, Vec3.atBottomCenterOf(feet));
        List<String> failures = new ArrayList<>();
        var world = context.getLevel();
        BlockPos support = feet.east(2);
        for (BlockState state : new BlockState[]{
                Blocks.CHEST.defaultBlockState(), Blocks.CRAFTING_TABLE.defaultBlockState()}) {
            world.setBlock(support, state, Block.UPDATE_ALL);
            bot.getInventory().setSelectedSlot(0);
            bot.getInventory().getNonEquipmentItems().set(0, new ItemStack(Items.COBBLESTONE, 8));
            bot.getInventory().setChanged();
            ActionResult result = BuildAction.placeBlockAt(bot, support.above());
            String name = state.getBlock().getDescriptionId();
            if (!result.isSuccess() || !world.getBlockState(support.above()).is(Blocks.COBBLESTONE)) {
                failures.add(name + ": " + result.reason());
            }
            if (bot.containerMenu != bot.inventoryMenu) {
                failures.add(name + ": opened a menu on the bot");
                bot.closeContainer();
            }
            world.setBlock(support.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), "ObsShapePlaceAt");
        finish(context, failures);
    }

    /**
     * A torch, a rail, a plant (poppy), a crop (wheat on farmland) and a cobweb have no collider, yet a player looking
     * at them sees them: the visibility predicates must say so (an OUTLINE ray to the block's own shape), while the
     * collider predicates that support and standability proofs use must not (seeing a torch is not standing on it).
     * A torch behind a wall stays hidden.
     */
    @GameTest(environment = "minecraftai-gametest:observation_shape_game_tests_a_torch_a_rail_and_a_plant_in_plain_view_are_observable", maxTicks = 60)
    public void aTorchARailAndAPlantInPlainViewAreObservable(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(2, 4, 4));
        prepare(context, feet);
        AIPlayerEntity bot = spawn(context, "ObsShapeDecor", feet, Vec3.atBottomCenterOf(feet));
        BlockPos target = feet.east(3);
        var world = context.getLevel();
        List<String> failures = new ArrayList<>();
        BlockState[] states = {
                Blocks.TORCH.defaultBlockState(),
                Blocks.RAIL.defaultBlockState(),
                Blocks.POPPY.defaultBlockState(),
                Blocks.WHEAT.defaultBlockState().setValue(net.minecraft.world.level.block.CropBlock.AGE, 3),
                Blocks.COBWEB.defaultBlockState(),
        };
        for (BlockState state : states) {
            String name = state.getBlock().getDescriptionId();
            if (state.is(Blocks.WHEAT)) {
                world.setBlock(target.below(), Blocks.FARMLAND.defaultBlockState(), Block.UPDATE_ALL);
            }
            world.setBlock(target, state, Block.UPDATE_ALL);
            if (!world.getBlockState(target).is(state.getBlock())) {
                failures.add(name + ": fixture block did not stay (" + world.getBlockState(target) + ")");
            }
            if (!ObservableWorldQuery.canObserveBlock(bot, target)) {
                failures.add("canObserveBlock(" + name + ") in plain view");
            }
            if (!ObservableWorldQuery.canObserveBlockWithInsetFaces(bot, target)) {
                failures.add("canObserveBlockWithInsetFaces(" + name + ") in plain view");
            }
            if (!ObservableWorldQuery.canObserveCell(bot, target)) {
                failures.add("canObserveCell(" + name + ") in plain view");
            }
            if (ObservableWorldQuery.canObserveCollider(bot, target)
                    || ObservableWorldQuery.canObserveColliderWithInsetFaces(bot, target)) {
                failures.add("a collider proof accepted " + name + ", which has no collision shape");
            }
            world.setBlock(target, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            world.setBlock(target.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        // Hidden stays hidden: a torch boxed in by stone, and the plain stone block still counts for both predicates.
        BlockPos hidden = target.east(2);
        world.setBlock(hidden, Blocks.TORCH.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(target, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(target.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(hidden.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(hidden.north(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(hidden.south(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(hidden.east(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        if (ObservableWorldQuery.canObserveBlock(bot, hidden)
                || ObservableWorldQuery.canObserveBlockWithInsetFaces(bot, hidden)) {
            failures.add("a torch behind a wall became observable");
        }
        if (!ObservableWorldQuery.canObserveCollider(bot, target)) {
            failures.add("canObserveCollider(stone) in plain view");
        }
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), "ObsShapeDecor");
        finish(context, failures);
    }

    /**
     * Both a chest (below the cell) and a plain stone block (beside it) can support the placement. The plain
     * support must win: an oak log's axis records the clicked face, so a Z axis means the stone's south face was
     * clicked and a Y axis means the chest's top was.
     */
    @GameTest(environment = "minecraftai-gametest:observation_shape_game_tests_place_at_prefers_a_plain_stone_support_over_a_chest", maxTicks = 60)
    public void placeAtPrefersAPlainStoneSupportOverAChest(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(2, 4, 4));
        prepare(context, feet);
        AIPlayerEntity bot = spawn(context, "ObsShapePlain", feet, Vec3.atBottomCenterOf(feet));
        List<String> failures = new ArrayList<>();
        var world = context.getLevel();
        BlockPos destination = feet.east(3);
        world.setBlock(destination.below(), Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(destination.north(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        bot.getInventory().setSelectedSlot(0);
        bot.getInventory().getNonEquipmentItems().set(0, new ItemStack(Items.OAK_LOG, 8));
        bot.getInventory().setChanged();
        ActionResult result = BuildAction.placeBlockAt(bot, destination);
        BlockState placed = world.getBlockState(destination);
        if (!result.isSuccess() || !placed.is(Blocks.OAK_LOG)) {
            failures.add("placement failed: " + result.reason() + " / " + placed);
        } else if (placed.getValue(BlockStateProperties.AXIS) != Direction.Axis.Z) {
            failures.add("the log axis is " + placed.getValue(BlockStateProperties.AXIS)
                    + ": the chest was clicked instead of the plain stone (Z expected)");
        }
        if (bot.containerMenu != bot.inventoryMenu) {
            failures.add("opened a menu on the bot");
            bot.closeContainer();
        }
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), "ObsShapePlain");
        finish(context, failures);
    }

    private static void check(List<String> failures, AIPlayerEntity bot, BlockPos target, BlockState state) {
        String name = state.getBlock().getDescriptionId();
        if (!ObservableWorldQuery.canObserveBlock(bot, target)) {
            failures.add("canObserveBlock(" + name + ")");
        }
        if (!ObservableWorldQuery.canObserveBlockWithInsetFaces(bot, target)) {
            failures.add("canObserveBlockWithInsetFaces(" + name + ")");
        }
        if (!MiningController.currentObservedTarget(bot, target)) {
            failures.add("currentObservedTarget(" + name + ")");
        }
    }

    private static void finish(GameTestHelper context, List<String> failures) {
        if (failures.isEmpty()) {
            context.succeed();
        } else {
            context.fail(Component.nullToEmpty(String.join("; ", failures)));
        }
    }

    /** Clears the fixture volume and lays a stone floor one below {@code feet}. */
    private static void prepare(GameTestHelper context, BlockPos feet) {
        for (int dx = -1; dx <= 8; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = 0; dy <= 5; dy++) {
                    context.getLevel().setBlock(feet.offset(dx, dy, dz),
                            Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
                context.getLevel().setBlock(feet.offset(dx, -1, dz),
                        Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    private static AIPlayerEntity spawn(GameTestHelper context, String name, BlockPos feet, Vec3 pose) {
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        context.getLevel().getServer(), name, context.getLevel(), pose,
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(context.getLevel(), pose.x, pose.y, pose.z, Set.of(), 0.0F, 0.0F, true);
        bot.setOnGround(true);
        // The chunk tracking view follows a teleport on the next tick, and every sight question below needs it at once.
        context.getLevel().getChunkSource().move(bot);
        if (!bot.blockPosition().equals(feet)) {
            context.fail(Component.nullToEmpty("fixture spawned in the wrong feet cell: "
                    + bot.blockPosition().toShortString()));
        }
        return bot;
    }
}
