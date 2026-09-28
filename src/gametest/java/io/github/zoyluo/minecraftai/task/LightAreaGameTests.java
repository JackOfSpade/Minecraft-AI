package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.LightType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Real-server proofs for LightAreaTask's greedy, prediction-based torch placement (see
 * TorchPlacementPlanner and TorchPlacementPlannerTest for the pure-logic version of these same
 * invariants) and for DangerWatcher.maybeLightDarkArea's daylight-canopy guard.
 */
public final class LightAreaGameTests {
    @GameTest(environment = "minecraftai-gametest:light_area_game_tests_sealed_dark_room_lights_floor_without_clustering_torches", maxTicks = 500)
    public void sealedDarkRoomLightsFloorWithoutClusteringTorches(TestContext context) {
        int radius = 6;
        int maxTorches = 6;
        Room room = buildSealedRoom(context, "LightRoomGT", new BlockPos(20, 5, 20), radius);
        giveTorches(room.bot(), maxTorches);

        LightAreaTask task = new LightAreaTask(radius, maxTorches);
        TaskManager.INSTANCE.assign(room.bot(), task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_light_area_room"));

        driveAndAssert(context, room, task, maxTorches, /*expectedTorches*/ -1);
    }

    @GameTest(environment = "minecraftai-gametest:light_area_game_tests_small_dark_pocket_gets_exactly_one_torch", maxTicks = 200)
    public void smallDarkPocketGetsExactlyOneTorch(TestContext context) {
        int radius = 2; // LightAreaTask clamps radius to a minimum of 2; the walls sit tight
        // against that scan box so only the interior 3x3 floor is ever an observable air cell.
        int maxTorches = 3; // Deliberately more than needed: proves the task stops itself rather
        // than the fixture forcing a stop.
        Room room = buildSealedRoom(context, "LightPocketGT", new BlockPos(20, 5, 40), radius);
        giveTorches(room.bot(), maxTorches);

        LightAreaTask task = new LightAreaTask(radius, maxTorches);
        TaskManager.INSTANCE.assign(room.bot(), task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_light_area_pocket"));

        driveAndAssert(context, room, task, maxTorches, /*expectedTorches*/ 1);
    }

    @GameTest(environment = "minecraftai-gametest:light_area_game_tests_daylight_canopy_does_not_trigger_light_area", maxTicks = 100)
    public void daylightCanopyDoesNotTriggerLightArea(TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(20, 5, 60));
        var world = context.getWorld();
        // Open ground, no walls: only a 2-layer leaf canopy above the bot's head blocks direct
        // sky visibility. Leaves attenuate sky light by only ~1 per layer (unlike a solid block),
        // so at midday the cell under them still reads well above the mob-spawn light level --
        // this is exactly the "bright daytime under a leaf canopy" case FIX 2 must not fire on.
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                BlockPos cell = feet.add(dx, 0, dz);
                world.setBlockState(cell.down(), Blocks.GRASS_BLOCK.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(cell, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(cell.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(cell.up(2), Blocks.OAK_LEAVES.getDefaultState()
                        .with(net.minecraft.block.LeavesBlock.PERSISTENT, true), Block.NOTIFY_ALL);
                world.setBlockState(cell.up(3), Blocks.OAK_LEAVES.getDefaultState()
                        .with(net.minecraft.block.LeavesBlock.PERSISTENT, true), Block.NOTIFY_ALL);
            }
        }
        AIPlayerEntity bot = spawn(context, "LightCanopyGT", feet);
        giveTorches(bot, 4);

        boolean[] timeLockAcquired = {false};
        int[] settleTicks = {0};
        context.addFinalTask(() -> {
            if (timeLockAcquired[0]) {
                io.github.zoyluo.minecraftai.gametest.GameTestTimeLock.release();
            }
        });
        context.runAtEveryTick(() -> {
            if (!timeLockAcquired[0]) {
                if (!io.github.zoyluo.minecraftai.gametest.GameTestTimeLock.tryAcquire()) {
                    return;
                }
                timeLockAcquired[0] = true;
            }
            context.getWorld().setTimeOfDay(6000L); // noon: ambient darkness 0, unambiguously day

            if (TaskManager.INSTANCE.getActive(bot).isPresent()) {
                context.throwGameTestException(Text.of(
                        "daylight canopy incorrectly started a task: "
                                + TaskManager.INSTANCE.getActive(bot).orElseThrow().name()));
                return;
            }
            settleTicks[0]++;
            if (settleTicks[0] < 80) {
                return; // give calculateAmbientDarkness() and DangerWatcher's own scan cadence room to run repeatedly
            }
            require(context, !world.isSkyVisible(feet), "fixture leaf canopy did not block direct sky visibility");
            int combined = world.getLightLevel(feet, world.getAmbientDarkness());
            int threshold = MinecraftAiConfig.get().night().torchLightThreshold();
            require(context, combined >= threshold,
                    "fixture canopy is not actually bright enough at noon to exercise the guard: combined="
                            + combined + " threshold=" + threshold);
            require(context, TaskManager.INSTANCE.getActive(bot).isEmpty(),
                    "daylight canopy should never have started a task");
            AIPlayerManager.INSTANCE.despawn(world.getServer(), bot.getGameProfile().name());
            context.complete();
        });
    }

    // ---- shared driving/assertion logic for the two placement scenarios ----

    private void driveAndAssert(TestContext context, Room room, LightAreaTask task,
                                int maxTorches, int expectedTorches) {
        int threshold = MinecraftAiConfig.get().night().torchLightThreshold();
        List<BlockPos> order = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        int[] settleTicks = {0};
        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException(Text.of(
                        "light_area ended as " + task.state() + ":" + task.failureReason()
                                + " " + task.describe()));
                return;
            }
            for (BlockPos cell : room.floorCells()) {
                if (!seen.contains(cell) && context.getWorld().getBlockState(cell).isOf(Blocks.TORCH)) {
                    seen.add(cell);
                    order.add(cell.toImmutable());
                }
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            settleTicks[0]++;
            if (settleTicks[0] < 20) {
                return; // let the light engine fully settle before reading final block light
            }
            assertNoOrthogonalAdjacency(context, order);
            assertEveryTorchLitANewCell(context, order, room.floorCells(), threshold);
            assertFloorFullyLitOrBudgetExhausted(context, room.floorCells(), order, maxTorches, threshold);
            if (expectedTorches >= 0) {
                require(context, order.size() == expectedTorches,
                        "expected exactly " + expectedTorches + " torches, placed " + order.size()
                                + " at " + order);
            }
            AIPlayerManager.INSTANCE.despawn(context.getWorld().getServer(), room.bot().getGameProfile().name());
            context.complete();
        });
    }

    private static void assertNoOrthogonalAdjacency(TestContext context, List<BlockPos> order) {
        for (int i = 0; i < order.size(); i++) {
            for (int j = i + 1; j < order.size(); j++) {
                int manhattan = TorchPlacementPlanner.manhattanDistance(order.get(i), order.get(j));
                require(context, manhattan > 1,
                        "torches " + order.get(i) + " and " + order.get(j)
                                + " are orthogonally adjacent (or the same cell)");
            }
        }
    }

    // Mirrors LightAreaTask's own prediction model (a room that started fully dark, light 0
    // everywhere) to prove the REAL placement order the task chose satisfies "every torch it placed
    // lit at least one previously-dark cell" -- the exact property that the old nearest-first scan
    // violated by clustering every torch onto the bot's own feet.
    private static void assertEveryTorchLitANewCell(TestContext context, List<BlockPos> order,
                                                     Set<BlockPos> floorCells, int threshold) {
        List<BlockPos> placedSoFar = new ArrayList<>();
        for (BlockPos torch : order) {
            int newlyLit = 0;
            for (BlockPos cell : floorCells) {
                int before = TorchPlacementPlanner.predictedBlockLight(cell, 0, placedSoFar);
                if (before >= threshold) {
                    continue;
                }
                int after = TorchPlacementPlanner.predictedBlockLight(cell, 0, List.of(torch));
                if (Math.max(before, after) >= threshold) {
                    newlyLit++;
                }
            }
            require(context, newlyLit > 0, "torch at " + torch + " lit no previously-dark floor cell");
            placedSoFar.add(torch);
        }
    }

    private static void assertFloorFullyLitOrBudgetExhausted(TestContext context, Set<BlockPos> floorCells,
                                                              List<BlockPos> order, int maxTorches, int threshold) {
        if (order.size() >= maxTorches) {
            return; // budget exhausted is an accepted terminal condition
        }
        for (BlockPos cell : floorCells) {
            int light = context.getWorld().getLightLevel(LightType.BLOCK, cell);
            require(context, light >= threshold,
                    "reachable floor cell " + cell + " is still dark (" + light
                            + ") after the task completed under budget (" + order.size() + "/" + maxTorches + ")");
        }
    }

    // ---- fixture construction ----

    private record Room(AIPlayerEntity bot, Set<BlockPos> floorCells) {
    }

    private static Room buildSealedRoom(TestContext context, String name, BlockPos relativeFeet, int radius) {
        var world = context.getWorld();
        BlockPos feet = context.getAbsolutePos(relativeFeet);
        int wall = radius + 1;
        for (int dx = -wall; dx <= wall; dx++) {
            for (int dz = -wall; dz <= wall; dz++) {
                boolean onWall = dx == -wall || dx == wall || dz == -wall || dz == wall;
                BlockPos floor = feet.add(dx, -1, dz);
                world.setBlockState(floor, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(floor.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                for (int dy = 0; dy <= 3; dy++) {
                    BlockPos cell = feet.add(dx, dy, dz);
                    world.setBlockState(cell, onWall || dy == 3
                            ? Blocks.STONE.getDefaultState()
                            : Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        AIPlayerEntity bot = spawn(context, name, feet);
        Set<BlockPos> floorCells = new LinkedHashSet<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos cell = feet.add(dx, 0, dz);
                if (!cell.equals(feet)) {
                    floorCells.add(cell);
                }
            }
        }
        return new Room(bot, floorCells);
    }

    private static AIPlayerEntity spawn(TestContext context, String name, BlockPos feet) {
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        context.getWorld().getServer(), name, context.getWorld(),
                        Vec3d.ofBottomCenter(feet), 0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(context.getWorld(), feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                java.util.Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        bot.getHungerManager().setSaturationLevel(5.0F);
        return bot;
    }

    private static void giveTorches(AIPlayerEntity bot, int count) {
        for (int slot = 0; slot < bot.getInventory().getMainStacks().size(); slot++) {
            bot.getInventory().getMainStacks().set(slot, ItemStack.EMPTY);
        }
        bot.getInventory().setSelectedSlot(0);
        bot.getInventory().getMainStacks().set(0, new ItemStack(Items.TORCH, count));
        bot.getInventory().markDirty();
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(Text.of(message));
        }
    }
}
