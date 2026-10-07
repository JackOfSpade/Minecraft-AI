package io.github.zoyluo.minecraftai.goal;

import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mining.OreScan;
import io.github.zoyluo.minecraftai.persist.MissionRuntimeRecord;
import io.github.zoyluo.minecraftai.persist.MissionSpec;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * An incremental public request (mine N MORE ore, harvest N MORE produce) is measured from what
 * the bot holds when the request STARTS, not from what it held when the request was accepted
 * behind another mission; an absolute goal queued the same way is never re-measured.
 */
public final class GoalIncrementalPromotionGameTests {
    private static final Set<Block> COAL = OreScan.oreFamily(Blocks.COAL_ORE);

    @GameTest(maxTicks = 20)
    public void theIncrementalMarkerSurvivesTheMissionSpecRoundTripWithRealRegistryEntries(
            GameTestHelper context) {
        Goal.MineOre ore = Goal.MineOre.additional(COAL, 2, 0);
        Goal.HarvestCrop crop = Goal.HarvestCrop.additional(
                Blocks.WHEAT, Items.WHEAT_SEEDS, Items.WHEAT, 2, 0);
        Goal.MineOre timedOre = Goal.MineOre.timedCollection(COAL, 3);
        Goal.HarvestCrop timedCrop = Goal.HarvestCrop.timedCollection(
                Blocks.WHEAT, Items.WHEAT_SEEDS, Items.WHEAT, 3);
        for (Goal goal : List.of(ore, crop, timedOre, timedCrop)) {
            MissionSpec spec = MissionSpec.fromGoal(goal);
            require(context, "true".equals(spec.params().get("incremental")),
                    "an incremental goal did not persist its marker: " + spec);
            require(context, goal.equals(spec.toGoal().orElse(null)),
                    "an incremental goal changed across a MissionSpec round trip: " + goal);
        }

        Goal absoluteOre = new Goal.MineOre(COAL, 2);
        Goal absoluteCrop = new Goal.HarvestCrop(Blocks.WHEAT, Items.WHEAT_SEEDS, Items.WHEAT, 2);
        for (Goal goal : List.of(absoluteOre, absoluteCrop)) {
            MissionSpec spec = MissionSpec.fromGoal(goal);
            require(context, !spec.params().containsKey("incremental"),
                    "an absolute goal grew a marker, so its wire format changed: " + spec);
            require(context, goal.equals(spec.toGoal().orElse(null)),
                    "an absolute goal changed across a MissionSpec round trip: " + goal);
        }

        // Written before the marker existed: a baseline and nothing else. It is an absolute goal.
        MissionSpec preMarkerCrop = new MissionSpec("harvest_crop", Map.of(
                "crop", "minecraft:wheat", "seed", "minecraft:wheat_seeds",
                "produce", "minecraft:wheat", "count", "2", "initial_produce_count", "5"), List.of());
        Goal.HarvestCrop readBack = (Goal.HarvestCrop) preMarkerCrop.toGoal().orElse(null);
        require(context, readBack != null && !readBack.incremental() && readBack.initialProduceCount() == 5,
                "a pre-marker crop record must read back absolute, with its baseline: " + readBack);
        require(context, new MissionSpec("harvest_crop", Map.of(
                        "crop", "minecraft:wheat", "seed", "minecraft:wheat_seeds",
                        "produce", "minecraft:wheat", "count", "2", "incremental", "yes"),
                List.of()).toGoal().isEmpty(), "a corrupt marker must isolate the record");
        context.succeed();
    }

    @GameTest(maxTicks = 120)
    public void aQueuedIncrementalOreRequestIsMeasuredFromTheInventoryItStartsWith(GameTestHelper context) {
        Goal.MineOre queued = Goal.MineOre.additional(COAL, 1, 0);
        promotedAfterMissionAhead(context, "IncOreGT", queued, bot -> {
                    InventoryAction.giveItem(bot, new ItemStack(Items.COAL, 3));
                },
                goal -> goal instanceof Goal.MineOre ore && ore.incremental() && ore.initialDropCount() == 3
                        && ore.count() == 1,
                "the queued ore request was not promoted with a baseline of the 3 coal the mission ahead left");
    }

    @GameTest(maxTicks = 120)
    public void aQueuedIncrementalCropRequestIsMeasuredFromTheInventoryItStartsWith(GameTestHelper context) {
        Goal.HarvestCrop queued = Goal.HarvestCrop.additional(
                Blocks.WHEAT, Items.WHEAT_SEEDS, Items.WHEAT, 1, 0);
        promotedAfterMissionAhead(context, "IncCropGT", queued, bot -> {
                    InventoryAction.giveItem(bot, new ItemStack(Items.WHEAT, 3));
                },
                goal -> goal instanceof Goal.HarvestCrop crop && crop.incremental()
                        && crop.initialProduceCount() == 3 && crop.count() == 1,
                "the queued crop request was not promoted with a baseline of the 3 wheat the mission ahead left");
    }

    @GameTest(maxTicks = 120)
    public void aQueuedAbsoluteOreGoalIsNeverTurnedIntoAnIncrementalOne(GameTestHelper context) {
        Goal.MineOre queued = new Goal.MineOre(COAL, 5);
        promotedAfterMissionAhead(context, "AbsOreGT", queued, bot -> {
                    InventoryAction.giveItem(bot, new ItemStack(Items.COAL, 3));
                },
                goal -> goal instanceof Goal.MineOre ore && !ore.incremental()
                        && ore.initialDropCount() == 0 && ore.count() == 5,
                "a queued absolute goal changed its baseline on promotion");
    }

    /** Queues {@code queued} behind a berry mission, finishes that mission with {@code afterwards}, and watches the promotion. */
    private static void promotedAfterMissionAhead(GameTestHelper context, String name, Goal queued,
                                                  java.util.function.Consumer<AIPlayerEntity> afterwards,
                                                  java.util.function.Predicate<Goal> expected,
                                                  String failure) {
        AIPlayerEntity bot = spawnBot(context, name);
        requireOrCleanup(context, bot, GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.SWEET_BERRIES, 1)),
                "setup: the first mission was not accepted");
        requireOrCleanup(context, bot, GoalExecutor.INSTANCE.submit(bot, queued)
                        && GoalExecutor.INSTANCE.queuedGoalCount(bot) == 1,
                "setup: the request was not queued behind it");
        context.runAtTickTime(5, () -> {
            InventoryAction.giveItem(bot, new ItemStack(Items.SWEET_BERRIES, 1));
            afterwards.accept(bot);
        });
        boolean[] done = {false};
        String[] lastSeen = {"nothing was observed"};
        context.onEachTick(() -> {
            if (done[0]) {
                return;
            }
            // The promoted mission can be over in the tick it starts (this world has no surface to plan a mine on), so
            // its goal is read from the active mission and from the last result alike.
            MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(bot);
            Goal active = runtime.active() == null ? null : runtime.active().spec().toGoal().orElse(null);
            Goal finished = GoalExecutor.INSTANCE.lastResult(bot).map(GoalResult::goal).orElse(null);
            lastSeen[0] = "active=" + active + " finished=" + finished;
            if (active != null && expected.test(active) || finished != null && expected.test(finished)) {
                done[0] = true;
                despawn(bot);
                context.succeed();
            }
        });
        context.runAtTickTime(110, () -> {
            if (!done[0]) {
                despawn(bot);
                context.fail(Component.nullToEmpty(failure + ": " + lastSeen[0]));
            }
        });
    }

    private static AIPlayerEntity spawnBot(GameTestHelper context, String name) {
        var world = context.getLevel();
        BlockPos cell = context.absolutePos(new BlockPos(1, 2, 1));
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                for (int dy = -1; dy <= 3; dy++) {
                    world.setBlock(cell.offset(dx, dy, dz), Blocks.STONE.defaultBlockState(),
                            Block.UPDATE_CLIENTS);
                }
            }
        }
        world.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        world.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(cell),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, cell.getX() + 0.5D, cell.getY(), cell.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        bot.getFoodData().setSaturation(5.0F);
        return bot;
    }

    private static void despawn(AIPlayerEntity bot) {
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), bot.getGameProfile().name());
    }

    private static void requireOrCleanup(GameTestHelper context, AIPlayerEntity bot,
                                         boolean condition, String message) {
        if (!condition) {
            despawn(bot);
            context.fail(Component.nullToEmpty(message));
        }
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }
}
