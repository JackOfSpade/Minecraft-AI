package io.github.zoyluo.minecraftai.baritone;

import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.PathCalculationResult;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Baritone plans real routes for real bots over real terrain: each test builds a small obstacle course on a sealed platform,
 * asks {@link BaritonePlanner} for a path on the shared worker pool (nothing is executed, the bot never moves), and checks
 * that the path exists or not as it should, has a sensible length, uses the movements the terrain calls for and was found
 * quickly. Every result is printed as one {@code BARITONE_PLAN} line for the record.
 *
 * <p>All coordinates are relative to the bot's start cell {@code feet}: +x is east (the goal side), +z south. The platform is
 * 15x15 with a bedrock ring around it, so the only ways to the goal are the ones the test builds.</p>
 */
public final class BaritonePlanningGameTests {
    private static final int RADIUS = 7;
    /** No search over a platform this small may take longer than this (Baritone's own failure timeout is 2 s). */
    private static final long MAX_SEARCH_MS = 1500L;

    private static final Set<String> WALKING = Set.of("MovementTraverse", "MovementDiagonal");

    @GameTest(maxTicks = 200)
    public void plansAroundTwoHighWallThroughItsGap(GameTestHelper context) {
        Scenario s = Scenario.begin(context, "BaritoneWallGT", 8, 40, 8);
        // A bedrock wall (unbreakable, so digging is not an option) two blocks high across x=+3, open for z=+3..+7.
        for (int dz = -RADIUS; dz <= 2; dz++) {
            fill(s.world, s.feet, 3, dz, Blocks.BEDROCK, 0, 1);
        }
        BlockPos goal = s.feet.offset(6, 0, 0);
        s.plan(new GoalBlock(goal), plan -> {
            require(context, plan.reachesGoal(), "wall detour: " + plan.type());
            List<BetterBlockPos> positions = plan.path().positions();
            require(context, positions.get(positions.size() - 1).equals(new BetterBlockPos(goal)), "ends at " + plan.path().getDest());
            require(context, positions.stream().noneMatch(p -> p.getX() == s.feet.getX() + 3 && p.getZ() <= s.feet.getZ() + 2),
                    "the path goes through the wall: " + positions);
            require(context, positions.stream().anyMatch(p -> p.getX() == s.feet.getX() + 3 && p.getZ() >= s.feet.getZ() + 3),
                    "the path never uses the gap: " + positions);
            require(context, plan.movements().size() >= 8 && plan.movements().size() <= 16,
                    "a detour of 8-16 moves was expected, got " + plan.movements().size() + " " + plan.movements());
            require(context, WALKING.containsAll(plan.movements()), "only walking moves were expected: " + plan.movements());
        });
    }

    @GameTest(maxTicks = 200)
    public void plansOneBlockStepWithSingleAscend(GameTestHelper context) {
        Scenario s = Scenario.begin(context, "BaritoneStepGT", 8, 40, 8);
        // A one-block-high plateau from x=+3 on: stone in the cell that used to be air, so the floor is one block higher.
        for (int dx = 3; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                s.world.setBlock(s.feet.offset(dx, 0, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                for (int dy = 1; dy <= 3; dy++) {
                    s.world.setBlock(s.feet.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        BlockPos goal = s.feet.offset(5, 1, 0);
        s.plan(new GoalBlock(goal), plan -> {
            require(context, plan.reachesGoal(), "step: " + plan.type());
            require(context, plan.path().getDest().equals(new BetterBlockPos(goal)), "ends at " + plan.path().getDest());
            require(context, plan.movements().stream().filter("MovementAscend"::equals).count() == 1,
                    "exactly one ascend was expected: " + plan.movements());
            require(context, plan.movements().size() == 5, "5 moves expected (4 walks and the step), got " + plan.movements());
        });
    }

    @GameTest(maxTicks = 200)
    public void plansAroundPitTooDeepToFallInto(GameTestHelper context) {
        Scenario s = Scenario.begin(context, "BaritonePitGT", 8, 40, 8);
        // A 2-wide pit four blocks deep (one more than the bots' safe fall of 3) across x=+3..+4 for z=-7..+3, floor at -5.
        for (int dx = 3; dx <= 4; dx++) {
            for (int dz = -RADIUS; dz <= 3; dz++) {
                carvePit(s.world, s.feet, dx, dz, 4);
            }
        }
        BlockPos goal = s.feet.offset(6, 0, 0);
        s.plan(new GoalBlock(goal), plan -> {
            require(context, plan.reachesGoal(), "pit detour: " + plan.type());
            List<BetterBlockPos> positions = plan.path().positions();
            require(context, positions.stream().allMatch(p -> p.getY() == s.feet.getY()),
                    "the path leaves the level of the platform (it went into the pit): " + positions);
            require(context, positions.stream().anyMatch(p -> p.getX() == s.feet.getX() + 3 && p.getZ() >= s.feet.getZ() + 4),
                    "the path does not cross at the solid part: " + positions);
            require(context, plan.movements().size() <= 18, "detour too long: " + plan.movements().size());
            require(context, WALKING.containsAll(plan.movements()), "only walking moves were expected: " + plan.movements());
        });
    }

    @GameTest(maxTicks = 200)
    public void doesNotEnterPitThatSpansTheWholeWidth(GameTestHelper context) {
        Scenario s = Scenario.begin(context, "BaritoneDeepPitGT", 8, 40, 8);
        for (int dx = 3; dx <= 4; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                carvePit(s.world, s.feet, dx, dz, 4);
            }
        }
        s.plan(new GoalBlock(s.feet.offset(6, 0, 0)), plan -> {
            require(context, !plan.reachesGoal(), "a path across a four-deep pit was reported (no blocks to bridge with): " + plan.movements());
            if (plan.path() != null) {
                require(context, plan.path().positions().stream()
                                .allMatch(p -> p.getY() == s.feet.getY() && p.getX() < s.feet.getX() + 3),
                        "the partial path enters the pit: " + plan.path().positions());
            }
        });
    }

    @GameTest(maxTicks = 200)
    public void plansThroughClosedWoodenDoor(GameTestHelper context) {
        Scenario s = Scenario.begin(context, "BaritoneDoorGT", 8, 40, 8);
        // A full-width bedrock wall at x=+3, three high, with one closed oak door in it at z=0.
        for (int dz = -RADIUS; dz <= RADIUS; dz++) {
            fill(s.world, s.feet, 3, dz, Blocks.BEDROCK, 0, 2);
        }
        placeClosedDoor(s.world, s.feet.offset(3, 0, 0), Blocks.OAK_DOOR.defaultBlockState());
        BlockPos goal = s.feet.offset(6, 0, 0);
        s.plan(new GoalNear(goal, 0), plan -> {
            require(context, plan.reachesGoal(), "door: " + plan.type());
            List<BetterBlockPos> positions = plan.path().positions();
            require(context, positions.contains(new BetterBlockPos(s.feet.offset(3, 0, 0))),
                    "the path does not go through the door cell: " + positions);
            require(context, plan.movements().equals(List.of("MovementTraverse", "MovementTraverse", "MovementTraverse",
                            "MovementTraverse", "MovementTraverse", "MovementTraverse")),
                    "six plain walks were expected (the door is opened by the walk), got " + plan.movements());
        });
    }

    @GameTest(maxTicks = 200)
    public void doesNotPlanThroughClosedIronDoor(GameTestHelper context) {
        Scenario s = Scenario.begin(context, "BaritoneIronDoorGT", 8, 40, 8);
        for (int dz = -RADIUS; dz <= RADIUS; dz++) {
            fill(s.world, s.feet, 3, dz, Blocks.BEDROCK, 0, 2);
        }
        placeClosedDoor(s.world, s.feet.offset(3, 0, 0), Blocks.IRON_DOOR.defaultBlockState());
        s.plan(new GoalBlock(s.feet.offset(6, 0, 0)), plan -> {
            require(context, !plan.reachesGoal(), "a path through a closed iron door was planned: " + plan.movements());
            if (plan.path() != null) {
                require(context, plan.path().positions().stream().allMatch(p -> p.getX() < s.feet.getX() + 3),
                        "the partial path goes through the iron door: " + plan.path().positions());
            }
        });
    }

    @GameTest(maxTicks = 200)
    public void plansThroughStripOfWater(GameTestHelper context) {
        Scenario s = Scenario.begin(context, "BaritoneWaterGT", 8, 40, 8);
        // Two-deep water across x=+2..+4 (the floor cell and the cell above it), bedrock sides are the platform's ring.
        for (int dx = 2; dx <= 4; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                s.world.setBlock(s.feet.offset(dx, -2, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                for (int dy = -1; dy <= 0; dy++) {
                    // Placed without neighbour updates or fluid ticks so the strip stays exactly as built while the search runs.
                    s.world.setBlock(s.feet.offset(dx, dy, dz), Blocks.WATER.defaultBlockState(),
                            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE);
                }
            }
        }
        BlockPos goal = s.feet.offset(6, 0, 0);
        s.plan(new GoalBlock(goal), plan -> {
            require(context, plan.reachesGoal(), "water: " + plan.type());
            List<BetterBlockPos> positions = plan.path().positions();
            require(context, positions.stream().anyMatch(p -> p.getX() >= s.feet.getX() + 2 && p.getX() <= s.feet.getX() + 4),
                    "the path never enters the water strip: " + positions);
            require(context, plan.movements().size() >= 6 && plan.movements().size() <= 10,
                    "a direct crossing of 6-10 moves was expected: " + plan.movements());
        });
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** One bot on a sealed platform, its Baritone instance, and the plumbing to run a plan and check it. */
    private static final class Scenario {
        final GameTestHelper context;
        final ServerLevel world;
        final BlockPos feet;
        final String name;
        final AIPlayerEntity bot;
        final IBaritone baritone;

        private Scenario(GameTestHelper context, String name, BlockPos feet, AIPlayerEntity bot, IBaritone baritone) {
            this.context = context;
            this.world = context.getLevel();
            this.feet = feet;
            this.name = name;
            this.bot = bot;
            this.baritone = baritone;
        }

        static Scenario begin(GameTestHelper context, String name, int x, int y, int z) {
            ServerLevel world = context.getLevel();
            BlockPos feet = context.absolutePos(new BlockPos(x, y, z));
            BaritoneServerGameTests.preparePlatform(world, feet, RADIUS);
            // Seal the platform: bedrock ring, four high, so a path can only be the one the test builds.
            for (int d = -RADIUS - 1; d <= RADIUS + 1; d++) {
                for (int dy = -1; dy <= 3; dy++) {
                    world.setBlock(feet.offset(d, dy, -RADIUS - 1), Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
                    world.setBlock(feet.offset(d, dy, RADIUS + 1), Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
                    world.setBlock(feet.offset(-RADIUS - 1, dy, d), Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
                    world.setBlock(feet.offset(RADIUS + 1, dy, d), Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
            AIPlayerEntity bot = BaritoneServerGameTests.spawn(context, name, feet);
            return new Scenario(context, name, feet, bot, BaritoneRegistry.INSTANCE.get(bot));
        }

        /** Plans on the worker pool, waits for the result, runs {@code check} on it, and always cleans up. */
        void plan(baritone.api.pathing.goals.Goal goal, Consumer<BaritonePlanner.Plan> check) {
            CompletableFuture<BaritonePlanner.Plan> future = BaritonePlanner.plan(baritone, goal);
            boolean[] handled = {false};
            context.onEachTick(() -> {
                if (handled[0] || !future.isDone()) {
                    return;
                }
                handled[0] = true;
                try {
                    BaritonePlanner.Plan plan = future.join();
                    System.out.println("BARITONE_PLAN scenario=" + name + " type=" + plan.type() + " moves=" + plan.movements().size()
                            + " nodes=" + plan.nodesConsidered() + " search_ms=" + plan.searchMillis() + " queue_ms=" + plan.queueMillis()
                            + " path=" + plan.movements());
                    require(context, plan.type() != PathCalculationResult.Type.EXCEPTION
                            && plan.type() != PathCalculationResult.Type.CANCELLATION, "search ended with " + plan.type());
                    require(context, plan.searchMillis() <= MAX_SEARCH_MS, "search took " + plan.searchMillis() + " ms");
                    check.accept(plan);
                } finally {
                    AIPlayerManager.INSTANCE.despawn(world.getServer(), name);
                }
                require(context, BaritoneRegistry.INSTANCE.find(bot.getUUID()) == null, "the bot's Baritone instance outlived the bot");
                context.succeed();
            });
        }
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        BaritoneServerGameTests.require(context, condition, message);
    }

    /** {@code block} in the column at ({@code dx}, {@code dz}) for heights {@code fromDy..toDy}. */
    private static void fill(ServerLevel world, BlockPos feet, int dx, int dz, Block block, int fromDy, int toDy) {
        for (int dy = fromDy; dy <= toDy; dy++) {
            world.setBlock(feet.offset(dx, dy, dz), block.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    /** Removes the floor of the column and everything below it down to {@code depth} blocks, and puts a floor under that. */
    private static void carvePit(ServerLevel world, BlockPos feet, int dx, int dz, int depth) {
        for (int dy = -1; dy >= -depth; dy--) {
            world.setBlock(feet.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        world.setBlock(feet.offset(dx, -depth - 1, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
    }

    /** A closed door (both halves) whose panel blocks travel along x. */
    private static void placeClosedDoor(ServerLevel world, BlockPos lower, BlockState door) {
        BlockState base = door.setValue(DoorBlock.FACING, Direction.EAST).setValue(DoorBlock.OPEN, false);
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        world.setBlock(lower, base.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), flags);
        world.setBlock(lower.above(), base.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), flags);
    }
}
