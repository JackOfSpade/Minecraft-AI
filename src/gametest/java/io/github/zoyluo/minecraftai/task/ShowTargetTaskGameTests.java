package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Regression coverage for target-leg arrival at Baritone's exact {@code GoalNear(3)} boundary. */
public final class ShowTargetTaskGameTests {
    @GameTest(environment = "minecraftai-gametest:show_target_task_game_tests_exact_three_block_radius_completes_without_route",
            maxTicks = 60)
    public void showTargetCompletesAtExactThreeBlockHorizontalRadiusWithoutNoOpRoute(GameTestHelper context) {
        FollowFieldFixture fixture = new FollowFieldFixture(context, 8, 8);
        AIPlayerEntity bot = fixture.bot("ShowTargetRadiusGT", 0, 0, true);
        fixture.owner(bot, 1, 0);
        BlockPos target = fixture.cell(0, 3);
        ShowTargetTask task = new ShowTargetTask(target, "test target");
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_show_target_exact_radius"));

        context.failIfEver(() -> {
            fixture.require(task.state() != TaskState.FAILED,
                    "show target failed at GoalNear(3): " + task.failureReason());
            if (task.state() == TaskState.COMPLETED) {
                fixture.require(task.describe().contains("hops=0/24"),
                        "exactly three blocks away started a no-op target route: " + task.describe());
                fixture.finish();
                return;
            }
            fixture.require(context.getTick() < 30,
                    "show target did not point from GoalNear(3): " + task.describe());
        });
    }

    /** Ice is dry support, not water: a show route must be allowed to walk across it without breaking it. */
    @GameTest(environment = "minecraftai-gametest:show_target_task_game_tests_crosses_packed_ice_between_snow_and_chest",
            maxTicks = 240)
    public void showTargetCrossesPackedIceBetweenSnowAndChest(GameTestHelper context) {
        FollowFieldFixture fixture = new FollowFieldFixture(context, 5, 8);
        AIPlayerEntity bot = fixture.bot("ShowTargetIceGT", 0, -5, true);
        // Keep the owner close enough for the rendezvous without occupying the one-cell lane.
        fixture.owner(bot, 0, -7);
        for (int z = -8; z <= 8; z++) {
            fixture.arena.set(0, -1, z, Blocks.SNOW_BLOCK);
            fixture.arena.fill(-1, z, Blocks.BEDROCK, 0, 2);
            fixture.arena.fill(1, z, Blocks.BEDROCK, 0, 2);
        }
        BlockPos firstIce = fixture.arena.cell(0, -1, -2);
        BlockPos secondIce = fixture.arena.cell(0, -1, -1);
        fixture.level.setBlock(firstIce, Blocks.PACKED_ICE.defaultBlockState(), Block.UPDATE_ALL);
        fixture.level.setBlock(secondIce, Blocks.PACKED_ICE.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos target = fixture.arena.cell(0, 0, 4);
        fixture.level.setBlock(target, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        ShowTargetTask task = new ShowTargetTask(target, "ice chest");
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_show_target_ice_bridge"));
        boolean[] steppedOnIce = {false};

        context.failIfEver(() -> {
            BlockPos support = bot.blockPosition().below();
            steppedOnIce[0] |= support.equals(firstIce) || support.equals(secondIce);
            fixture.require(!bot.isInWater() && !bot.isInLava(),
                    "ice bridge was treated as a fluid route: " + bot.blockPosition());
            fixture.require(task.state() != TaskState.FAILED,
                    "show target failed on dry ice: " + task.failureReason());
            if (task.state() == TaskState.COMPLETED) {
                int completedRelativeZ = bot.blockPosition().getZ() - fixture.arena.origin.getZ();
                fixture.require(completedRelativeZ >= 1,
                        "show route completed before crossing the ice bridge: " + bot.blockPosition());
                fixture.require(steppedOnIce[0], "show route reached the chest without walking on the packed ice");
                fixture.require(fixture.level.getBlockState(firstIce).is(Blocks.PACKED_ICE)
                                && fixture.level.getBlockState(secondIce).is(Blocks.PACKED_ICE),
                        "show route broke an ice support");
                fixture.require(fixture.level.getBlockState(target).is(Blocks.CHEST),
                        "show route altered the chest it was showing");
                fixture.finish();
                return;
            }
            fixture.require(context.getTick() < 220,
                    "show target did not cross the ice bridge: " + task.describe());
        });
    }

    /** A chest below a pool is shown from its top-water projection, never by diving to its floor cell. */
    @GameTest(environment = "minecraftai-gametest:show_target_task_game_tests_shows_deep_water_target_from_surface_without_diving",
            maxTicks = 520)
    public void showTargetSwimsAtSurfaceAboveDeepChestWithoutDiving(GameTestHelper context) {
        FollowFieldFixture fixture = new FollowFieldFixture(context, 10, 6, 0, 8);
        AIPlayerEntity bot = fixture.bot("ShowTargetSurfaceGT", -7, 0, true);
        fixture.owner(bot, -8, 0);
        // The pool seals the whole cross-section of the arena, so reaching the chest's column requires a water
        // crossing. Its surface is feet-level y=0; the chest is six cells below it.
        for (int x = -1; x <= 10; x++) {
            for (int z = -6; z <= 6; z++) {
                for (int y = -5; y <= 0; y++) {
                    fixture.level.setBlock(fixture.arena.cell(x, y, z), Blocks.WATER.defaultBlockState(),
                            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE);
                }
            }
        }
        BlockPos target = fixture.arena.cell(6, -6, 0);
        BlockPos surface = fixture.arena.cell(6, 0, 0);
        fixture.level.setBlock(target, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        ShowTargetTask task = new ShowTargetTask(target, "deep chest");
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_show_target_surface_water"));
        boolean[] enteredWater = {false};
        boolean[] crossedNearTargetOnSurface = {false};

        context.failIfEver(() -> {
            enteredWater[0] |= bot.isInWater();
            crossedNearTargetOnSurface[0] |= bot.isInWater()
                    && bot.blockPosition().getY() == surface.getY()
                    && bot.blockPosition().getX() >= surface.getX() - ShowTargetTask.TARGET_RADIUS;
            fixture.require(!bot.isInLava(), "surface presentation entered lava: " + bot.blockPosition());
            fixture.require(bot.blockPosition().getY() >= surface.getY(),
                    "surface presentation dived below the top water layer: " + bot.blockPosition());
            fixture.require(task.state() != TaskState.FAILED,
                    "show target failed instead of using the water surface: " + task.failureReason());
            if (task.state() == TaskState.COMPLETED) {
                int dx = bot.blockPosition().getX() - surface.getX();
                int dz = bot.blockPosition().getZ() - surface.getZ();
                fixture.require(enteredWater[0], "surface presentation never entered the required water crossing");
                fixture.require(crossedNearTargetOnSurface[0],
                        "surface presentation did not cross water near the chest column");
                fixture.require(dx * dx + dz * dz <= ShowTargetTask.TARGET_RADIUS * ShowTargetTask.TARGET_RADIUS,
                        "surface presentation ended away from the chest column: " + bot.blockPosition());
                fixture.require(bot.blockPosition().getY() >= surface.getY(),
                        "surface presentation completed after diving: " + bot.blockPosition());
                fixture.require(fixture.level.getBlockState(target).is(Blocks.CHEST),
                        "surface presentation altered the underwater chest");
                fixture.finish();
                return;
            }
            fixture.require(context.getTick() < 500,
                    "show target did not reach the water surface: " + task.describe());
        });
    }
}
