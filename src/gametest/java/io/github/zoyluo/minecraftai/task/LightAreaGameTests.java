package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
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
    public void sealedDarkRoomLightsFloorWithoutClusteringTorches(GameTestHelper context) {
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
    public void smallDarkPocketGetsExactlyOneTorch(GameTestHelper context) {
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
    public void daylightCanopyDoesNotTriggerLightArea(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(20, 5, 60));
        var world = context.getLevel();
        // Open ground, no walls: only a 2-layer leaf canopy above the bot's head blocks direct
        // sky visibility. Leaves attenuate sky light by only ~1 per layer (unlike a solid block),
        // so at midday the cell under them still reads well above the mob-spawn light level --
        // this is exactly the "bright daytime under a leaf canopy" case FIX 2 must not fire on.
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                BlockPos cell = feet.offset(dx, 0, dz);
                world.setBlock(cell.below(), Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(cell.above(2), Blocks.OAK_LEAVES.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true), Block.UPDATE_ALL);
                world.setBlock(cell.above(3), Blocks.OAK_LEAVES.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true), Block.UPDATE_ALL);
            }
        }
        AIPlayerEntity bot = spawn(context, "LightCanopyGT", feet);
        giveTorches(bot, 4);

        boolean[] timeLockAcquired = {false};
        int[] settleTicks = {0};
        context.succeedIf(() -> {
            if (timeLockAcquired[0]) {
                io.github.zoyluo.minecraftai.gametest.GameTestTimeLock.release();
            }
        });
        context.failIfEver(() -> {
            if (!timeLockAcquired[0]) {
                if (!io.github.zoyluo.minecraftai.gametest.GameTestTimeLock.tryAcquire()) {
                    return;
                }
                timeLockAcquired[0] = true;
            }
            context.getLevel().setDayTime(6000L); // noon: ambient darkness 0, unambiguously day

            if (TaskManager.INSTANCE.getActive(bot).isPresent()) {
                context.fail(Component.nullToEmpty(
                        "daylight canopy incorrectly started a task: "
                                + TaskManager.INSTANCE.getActive(bot).orElseThrow().name()));
                return;
            }
            settleTicks[0]++;
            if (settleTicks[0] < 80) {
                return; // give updateSkyBrightness() and DangerWatcher's own scan cadence room to run repeatedly
            }
            require(context, !world.canSeeSky(feet), "fixture leaf canopy did not block direct sky visibility");
            int combined = world.getMaxLocalRawBrightness(feet, world.getSkyDarken());
            int threshold = MinecraftAiConfig.get().night().torchLightThreshold();
            require(context, combined >= threshold,
                    "fixture canopy is not actually bright enough at noon to exercise the guard: combined="
                            + combined + " threshold=" + threshold);
            require(context, TaskManager.INSTANCE.getActive(bot).isEmpty(),
                    "daylight canopy should never have started a task");
            AIPlayerManager.INSTANCE.despawn(world.getServer(), bot.getGameProfile().name());
            context.succeed();
        });
    }

    // ---- shared driving/assertion logic for the two placement scenarios ----

    private void driveAndAssert(GameTestHelper context, Room room, LightAreaTask task,
                                int maxTorches, int expectedTorches) {
        int threshold = MinecraftAiConfig.get().night().torchLightThreshold();
        List<BlockPos> order = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        int[] settleTicks = {0};
        context.failIfEver(() -> {
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty(
                        "light_area ended as " + task.state() + ":" + task.failureReason()
                                + " " + task.describe()));
                return;
            }
            for (BlockPos cell : room.floorCells()) {
                if (!seen.contains(cell) && context.getLevel().getBlockState(cell).is(Blocks.TORCH)) {
                    seen.add(cell);
                    order.add(cell.immutable());
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
            AIPlayerManager.INSTANCE.despawn(context.getLevel().getServer(), room.bot().getGameProfile().name());
            context.succeed();
        });
    }

    private static void assertNoOrthogonalAdjacency(GameTestHelper context, List<BlockPos> order) {
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
    private static void assertEveryTorchLitANewCell(GameTestHelper context, List<BlockPos> order,
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

    private static void assertFloorFullyLitOrBudgetExhausted(GameTestHelper context, Set<BlockPos> floorCells,
                                                              List<BlockPos> order, int maxTorches, int threshold) {
        if (order.size() >= maxTorches) {
            return; // budget exhausted is an accepted terminal condition
        }
        for (BlockPos cell : floorCells) {
            int light = context.getLevel().getBrightness(LightLayer.BLOCK, cell);
            require(context, light >= threshold,
                    "reachable floor cell " + cell + " is still dark (" + light
                            + ") after the task completed under budget (" + order.size() + "/" + maxTorches + ")");
        }
    }

    // ---- fixture construction ----

    private record Room(AIPlayerEntity bot, Set<BlockPos> floorCells) {
    }

    private static Room buildSealedRoom(GameTestHelper context, String name, BlockPos relativeFeet, int radius) {
        var world = context.getLevel();
        BlockPos feet = context.absolutePos(relativeFeet);
        int wall = radius + 1;
        for (int dx = -wall; dx <= wall; dx++) {
            for (int dz = -wall; dz <= wall; dz++) {
                boolean onWall = dx == -wall || dx == wall || dz == -wall || dz == wall;
                BlockPos floor = feet.offset(dx, -1, dz);
                world.setBlock(floor, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(floor.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                for (int dy = 0; dy <= 3; dy++) {
                    BlockPos cell = feet.offset(dx, dy, dz);
                    world.setBlock(cell, onWall || dy == 3
                            ? Blocks.STONE.defaultBlockState()
                            : Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        AIPlayerEntity bot = spawn(context, name, feet);
        Set<BlockPos> floorCells = new LinkedHashSet<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos cell = feet.offset(dx, 0, dz);
                if (!cell.equals(feet)) {
                    floorCells.add(cell);
                }
            }
        }
        return new Room(bot, floorCells);
    }

    private static AIPlayerEntity spawn(GameTestHelper context, String name, BlockPos feet) {
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        context.getLevel().getServer(), name, context.getLevel(),
                        Vec3.atBottomCenterOf(feet), 0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(context.getLevel(), feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                java.util.Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        bot.getFoodData().setSaturation(5.0F);
        return bot;
    }

    private static void giveTorches(AIPlayerEntity bot, int count) {
        for (int slot = 0; slot < bot.getInventory().getNonEquipmentItems().size(); slot++) {
            bot.getInventory().getNonEquipmentItems().set(slot, ItemStack.EMPTY);
        }
        bot.getInventory().setSelectedSlot(0);
        bot.getInventory().getNonEquipmentItems().set(0, new ItemStack(Items.TORCH, count));
        bot.getInventory().setChanged();
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }
}
