package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.baritone.ObservedNavigationFence;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.navigation.NavRoute;
import io.github.zoyluo.minecraftai.perception.SharedWorldSight;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;

/**
 * Sight is not reach, in a real server with the real tags and a real bot. A log behind two leaves, a fence, a pane, glass or
 * water is OBSERVED (the bot's eyes pass through them, so navigation admits it and the shared memory keeps the foliage as
 * foliage), but the strict gate every actuator re-proves with is the vanilla clip, which the first leaf stops: nothing is mined through it (the leaf is broken first),
 * opened or used through a leaf. What the eyes cannot pass (a wall, a door, a slab, lava) hides the block as it always did.
 */
public final class ObservationSeeThroughGameTests {
    /** The wall stands in front of the bot, covers every ray to the target, and is {@code thickness} blocks deep. */
    private static void wall(GameTestHelper context, BlockPos feet, int thickness, BlockState state) {
        for (int depth = 0; depth < thickness; depth++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = 0; dy <= 3; dy++) {
                    context.getLevel().setBlock(feet.offset(2 + depth, dy, dz), state, Block.UPDATE_ALL);
                }
            }
        }
    }

    private static void clearWall(GameTestHelper context, BlockPos feet, int thickness) {
        wall(context, feet, thickness, Blocks.AIR.defaultBlockState());
    }

    /** Door leaves in every column of the wall: lower and upper half, closed. */
    private static void doorWall(GameTestHelper context, BlockPos feet) {
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
        for (int dz = -3; dz <= 3; dz++) {
            context.getLevel().setBlock(feet.offset(2, 0, dz), lower, Block.UPDATE_ALL);
            context.getLevel().setBlock(feet.offset(2, 1, dz), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
        }
    }

    /** A top slab under a bottom slab: together they cover every height a ray to the target crosses the wall at. */
    private static void slabWall(GameTestHelper context, BlockPos feet) {
        for (int dz = -3; dz <= 3; dz++) {
            context.getLevel().setBlock(feet.offset(2, 0, dz),
                    Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP), Block.UPDATE_ALL);
            context.getLevel().setBlock(feet.offset(2, 1, dz),
                    Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM), Block.UPDATE_ALL);
        }
    }

    private static void clearDoorOrSlabWall(GameTestHelper context, BlockPos feet) {
        for (int dz = -3; dz <= 3; dz++) {
            for (int dy = 0; dy <= 3; dy++) {
                context.getLevel().setBlock(feet.offset(2, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    private static boolean seen(AIPlayerEntity bot, BlockPos target) {
        return ObservableWorldQuery.canObserveBlock(bot, target)
                && ObservableWorldQuery.canObserveBlockCellFace(bot, target)
                && ObservableWorldQuery.canObserveCell(bot, target);
    }

    private static boolean seenAtAll(AIPlayerEntity bot, BlockPos target) {
        return ObservableWorldQuery.canObserveBlock(bot, target)
                || ObservableWorldQuery.canObserveBlockCellFace(bot, target)
                || ObservableWorldQuery.canObserveCell(bot, target)
                || ObservableWorldQuery.canObserveBlockWithInsetFaces(bot, target);
    }

    private static boolean reachable(AIPlayerEntity bot, BlockPos target) {
        return ObservableWorldQuery.canObserveBlockStrict(bot, target)
                || ObservableWorldQuery.canObserveBlockCellFaceStrict(bot, target)
                || ObservableWorldQuery.canObserveCellStrict(bot, target)
                || ObservableWorldQuery.canObserveBlockWithInsetFacesStrict(bot, target)
                || MiningController.currentObservedTarget(bot, target);
    }

    private static void prepare(GameTestHelper context, BlockPos feet) {
        for (int dx = -1; dx <= 9; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = 0; dy <= 6; dy++) {
                    context.getLevel().setBlock(feet.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
                context.getLevel().setBlock(feet.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    private static AIPlayerEntity spawn(GameTestHelper context, String name, BlockPos feet) {
        Vec3 pose = Vec3.atBottomCenterOf(feet);
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        context.getLevel().getServer(), name, context.getLevel(), pose, 0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(context.getLevel(), pose.x, pose.y, pose.z, Set.of(), 0.0F, 0.0F, true);
        bot.setOnGround(true);
        // The chunk tracking view follows a teleport on the next tick, and every sight question needs it at once.
        context.getLevel().getChunkSource().move(bot);
        return bot;
    }

    private static void finish(GameTestHelper context, List<String> failures) {
        if (failures.isEmpty()) {
            context.succeed();
        } else {
            context.fail(Component.nullToEmpty(String.join("; ", failures)));
        }
    }

    /** {@code handPasses}: a vanilla pick ray goes through it (water), so the block behind is reachable as well as seen. */
    private record Wall(String name, BlockState state, int thickness, boolean handPasses) {
        Wall(String name, BlockState state, int thickness) {
            this(name, state, thickness, false);
        }
    }

    private static final BlockState LOG = Blocks.OAK_LOG.defaultBlockState();

    private static final Wall[] SEE_THROUGH = {
            new Wall("two leaves", Blocks.OAK_LEAVES.defaultBlockState(), 2),
            new Wall("a fence", Blocks.OAK_FENCE.defaultBlockState(), 1),
            new Wall("a closed fence gate", Blocks.OAK_FENCE_GATE.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST), 1),
            new Wall("glass", Blocks.GLASS.defaultBlockState(), 1),
            new Wall("glass panes", Blocks.GLASS_PANE.defaultBlockState(), 1),
            new Wall("iron bars", Blocks.IRON_BARS.defaultBlockState(), 1),
            new Wall("two blocks of water", Blocks.WATER.defaultBlockState(), 2, true),
    };

    @GameTest(environment = "minecraftai-gametest:observation_see_through_game_tests_a_block_behind_see_through_blocks_is_observed_but_not_reachable", maxTicks = 100)
    public void aBlockBehindSeeThroughBlocksIsObservedButNotReachable(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(2, 4, 4));
        prepare(context, feet);
        AIPlayerEntity bot = spawn(context, "SeeThroughReach", feet);
        BlockPos target = feet.east(5);
        context.getLevel().setBlock(target, LOG, Block.UPDATE_ALL);
        List<String> failures = new ArrayList<>();
        if (!seen(bot, target) || !MiningController.currentObservedTarget(bot, target)) {
            failures.add("a log in the open is neither observed nor reachable");
        }
        for (Wall wall : SEE_THROUGH) {
            wall(context, feet, wall.thickness(), wall.state());
            if (!seen(bot, target)) {
                failures.add("the log behind " + wall.name() + " is not observed: block " + ObservableWorldQuery.canObserveBlock(bot, target)
                        + " cell face " + ObservableWorldQuery.canObserveBlockCellFace(bot, target)
                        + " cell " + ObservableWorldQuery.canObserveCell(bot, target));
            }
            if (wall.handPasses() && !MiningController.currentObservedTarget(bot, target)) {
                failures.add("the log behind " + wall.name() + " is not reachable, though a pick ray passes it: mining "
                        + MiningController.currentObservedTarget(bot, target));
            }
            if (!wall.handPasses() && reachable(bot, target)) {
                failures.add("the log behind " + wall.name() + " passes a strict (reach) gate: block "
                        + ObservableWorldQuery.canObserveBlockStrict(bot, target)
                        + " cell face " + ObservableWorldQuery.canObserveBlockCellFaceStrict(bot, target)
                        + " cell " + ObservableWorldQuery.canObserveCellStrict(bot, target)
                        + " inset " + ObservableWorldQuery.canObserveBlockWithInsetFacesStrict(bot, target)
                        + " mining " + MiningController.currentObservedTarget(bot, target));
            }
            clearWall(context, feet, wall.thickness());
        }
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), "SeeThroughReach");
        finish(context, failures);
    }

    @GameTest(environment = "minecraftai-gametest:observation_see_through_game_tests_a_block_behind_a_wall_a_door_a_slab_or_lava_is_not_observed", maxTicks = 100)
    public void aBlockBehindAWallADoorASlabOrLavaIsNotObserved(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(2, 4, 4));
        prepare(context, feet);
        AIPlayerEntity bot = spawn(context, "SeeThroughBlind", feet);
        BlockPos target = feet.east(5);
        context.getLevel().setBlock(target, LOG, Block.UPDATE_ALL);
        List<String> failures = new ArrayList<>();
        Wall[] opaque = {
                new Wall("stone", Blocks.STONE.defaultBlockState(), 1),
                new Wall("a stone wall", Blocks.COBBLESTONE_WALL.defaultBlockState(), 1),
                new Wall("blue ice", Blocks.BLUE_ICE.defaultBlockState(), 1),
                new Wall("lava", Blocks.LAVA.defaultBlockState(), 2),
        };
        for (Wall wall : opaque) {
            wall(context, feet, wall.thickness(), wall.state());
            if (seenAtAll(bot, target)) {
                failures.add("the log behind " + wall.name() + " is observed");
            }
            if (wall.state().is(Blocks.LAVA)) {
                BlockPos lava = feet.east(2).above();
                if (!ObservableWorldQuery.canObserveCell(bot, lava)) {
                    failures.add("the lava itself is not observed");
                }
                // The memory keeps the hazard and nothing beyond it.
                boolean lavaKnown = SharedWorldSight.routeEvidence(bot, target).stream()
                        .anyMatch(o -> o.packedPos() == lava.asLong() && o.state().is(Blocks.LAVA));
                if (!lavaKnown) {
                    failures.add("the shared memory forgot the lava the ray reached");
                }
            }
            clearWall(context, feet, wall.thickness());
        }
        doorWall(context, feet);
        if (seenAtAll(bot, target)) {
            failures.add("the log behind a closed door is observed");
        }
        clearDoorOrSlabWall(context, feet);
        slabWall(context, feet);
        if (seenAtAll(bot, target)) {
            failures.add("the log behind slabs is observed");
        }
        clearDoorOrSlabWall(context, feet);
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), "SeeThroughBlind");
        finish(context, failures);
    }

    @GameTest(environment = "minecraftai-gametest:observation_see_through_game_tests_the_strict_mining_gate_refuses_a_break_through_an_intact_leaf", maxTicks = 100)
    public void theStrictMiningGateRefusesABreakThroughAnIntactLeaf(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(2, 4, 4));
        prepare(context, feet);
        AIPlayerEntity bot = spawn(context, "SeeThroughMine", feet);
        var world = context.getLevel();
        BlockPos leaf = feet.east(2).above();
        BlockPos log = feet.east(3).above();
        world.setBlock(leaf, Blocks.OAK_LEAVES.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(leaf.below(), Blocks.OAK_LEAVES.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(log, LOG, Block.UPDATE_ALL);
        List<String> failures = new ArrayList<>();
        if (!ObservableWorldQuery.canObserveBlock(bot, log)) {
            failures.add("the log behind the leaf is not seen");
        }
        if (MiningController.currentObservedTarget(bot, log)) {
            failures.add("the mining gate admits the log behind the leaf");
        }
        // The log is admitted (the miner breaks the leaf first, see MiningObstructionGameTests), but nothing is ever started
        // on the log itself while the leaf stands: a controller's first tick begins on the leaf and never touches the log.
        ActionResult admitted = bot.getActionPack().startMining(log, Direction.WEST);
        if (!admitted.isInProgress()) {
            failures.add("startMining behind a leaf was not admitted for clearing: " + admitted);
        }
        bot.getActionPack().stopMining();
        MiningController controller = new MiningController(log, Direction.WEST);
        ActionResult tick = controller.tick(bot.getActionPack());
        if (!tick.isInProgress() || controller.brokenBlockState() != null || !world.getBlockState(log).is(Blocks.OAK_LOG)
                || !world.getBlockState(leaf).is(Blocks.OAK_LEAVES)) {
            failures.add("a controller whose strict gate fails began on the log through the leaf: " + tick);
        }
        controller.abort(bot);
        if (!world.getBlockState(log).is(Blocks.OAK_LOG)) {
            failures.add("the log was broken through the leaf");
        }
        if (!MiningController.currentObservedTarget(bot, leaf)) {
            failures.add("the leaf itself, the block a hand meets first, is not minable");
        }
        // The leaf in front is what a hand reaches; once it is gone the log is reachable.
        world.setBlock(leaf, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(leaf.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        if (!MiningController.currentObservedTarget(bot, log)) {
            failures.add("the log is still refused after the leaves are gone");
        }
        ActionResult started = bot.getActionPack().startMining(log, Direction.WEST);
        if (!started.isInProgress()) {
            failures.add("mining the log with a clear line did not start: " + started);
        }
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), "SeeThroughMine");
        finish(context, failures);
    }

    @GameTest(environment = "minecraftai-gametest:observation_see_through_game_tests_navigation_admits_a_log_behind_foliage_and_remembers_the_foliage", maxTicks = 200)
    public void navigationAdmitsALogBehindFoliageAndRemembersTheFoliage(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(2, 4, 4));
        prepare(context, feet);
        AIPlayerEntity bot = spawn(context, "SeeThroughNav", feet);
        var world = context.getLevel();
        BlockPos target = feet.east(5);
        world.setBlock(target, LOG, Block.UPDATE_ALL);
        List<String> failures = new ArrayList<>();
        long generation = 1L;
        for (Wall wall : SEE_THROUGH) {
            wall(context, feet, wall.thickness(), wall.state());
            SharedWorldSight.forget(bot.getUUID());
            NavRoute route = new NavRoute(NavRoute.Shape.BLOCK, target, 0, NavRoute.Options.WALK_ONLY,
                    "see_through_fixture", world.getServer().getTickCount());
            ObservedNavigationFence.Capture capture = ObservedNavigationFence.admit(bot, route, null, generation++);
            if (!capture.accepted()) {
                failures.add("the log behind " + wall.name() + " was refused: " + capture.failure());
            } else {
                BlockState remembered = capture.fence().stateAt(target);
                if (remembered == null || !remembered.is(Blocks.OAK_LOG)) {
                    failures.add("the log behind " + wall.name() + " is remembered as " + remembered);
                }
                BlockPos eyeLevel = feet.east(2).above();
                BlockState crossed = capture.fence().stateAt(eyeLevel);
                if (crossed == null || !crossed.is(wall.state().getBlock())) {
                    failures.add("the fence remembers " + wall.name() + " at the eye-level cell as " + crossed);
                }
                for (int depth = 0; depth < wall.thickness(); depth++) {
                    for (int dy = 0; dy <= 1; dy++) {
                        BlockState known = capture.fence().stateAt(feet.offset(2 + depth, dy, 0));
                        if (known != null && known.isAir()) {
                            failures.add(wall.name() + " at depth " + depth + " height " + dy + " is remembered as air");
                        }
                    }
                }
            }
            // The shared memory the same ray feeds keeps the foliage as foliage too.
            List<SharedWorldSight.Observation> memory = SharedWorldSight.routeEvidence(bot, target);
            for (SharedWorldSight.Observation observation : memory) {
                BlockPos cell = observation.pos();
                boolean inWall = cell.getX() >= feet.getX() + 2 && cell.getX() < feet.getX() + 2 + wall.thickness()
                        && cell.getZ() == feet.getZ() && cell.getY() >= feet.getY() && cell.getY() <= feet.getY() + 1;
                if (inWall && observation.state().isAir()) {
                    failures.add("the shared memory holds " + wall.name() + " at " + cell.toShortString() + " as air");
                }
            }
            clearWall(context, feet, wall.thickness());
        }
        // The same log behind stone is as hidden as ever (and the memory of having seen it through the water is gone).
        wall(context, feet, 1, Blocks.STONE.defaultBlockState());
        SharedWorldSight.forget(bot.getUUID());
        ObservedNavigationFence.Capture hidden = ObservedNavigationFence.admit(bot,
                new NavRoute(NavRoute.Shape.BLOCK, target, 0, NavRoute.Options.WALK_ONLY, "hidden_fixture",
                        world.getServer().getTickCount()), null, generation);
        if (hidden.accepted() || !"navigation_goal_unobserved".equals(hidden.failure())) {
            failures.add("a log behind stone was not refused as unobserved: " + hidden.accepted() + " " + hidden.failure());
        }
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), "SeeThroughNav");
        finish(context, failures);
    }

    @GameTest(environment = "minecraftai-gametest:observation_see_through_game_tests_a_chest_and_a_cow_behind_glass_are_seen_but_never_used", maxTicks = 100)
    public void aChestAndACowBehindGlassAreSeenButNeverUsed(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(2, 4, 4));
        prepare(context, feet);
        AIPlayerEntity bot = spawn(context, "SeeThroughUse", feet);
        var world = context.getLevel();
        BlockPos chest = feet.east(4);
        world.setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        Cow cow = EntityType.COW.create(world, EntitySpawnReason.COMMAND);
        if (cow == null) {
            throw new IllegalStateException("no cow");
        }
        cow.setNoAi(true);
        cow.snapTo(feet.getX() + 3.5D, feet.getY(), feet.getZ() + 0.5D, 0.0F, 0.0F);
        world.addFreshEntity(cow);
        List<String> failures = new ArrayList<>();
        if (!ContainerAction.canSee(bot, chest) || ContainerAction.open(bot, chest, false).isEmpty()) {
            failures.add("a chest in the open cannot be opened");
        }
        wall(context, feet, 1, Blocks.GLASS.defaultBlockState());
        if (!ObservableWorldQuery.canObserveCell(bot, chest)) {
            failures.add("the chest behind glass is not seen");
        }
        if (ContainerAction.canSee(bot, chest) || ContainerAction.inReachAndSight(bot, chest)
                || ContainerAction.open(bot, chest, false).isPresent()) {
            failures.add("the chest behind glass was opened");
        }
        ActionResult through = InteractAction.useItemOnEntity(bot, cow, InteractionHand.MAIN_HAND);
        if (!through.isFailed() || !"no_line_of_sight".equals(through.reason())) {
            failures.add("a click on a cow behind glass was not refused: " + through);
        }
        clearWall(context, feet, 1);
        ActionResult open = InteractAction.useItemOnEntity(bot, cow, InteractionHand.MAIN_HAND);
        if ("no_line_of_sight".equals(open.reason())) {
            failures.add("a click on a cow in the open was refused for its line of sight");
        }
        cow.discard();
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), "SeeThroughUse");
        finish(context, failures);
    }
}
