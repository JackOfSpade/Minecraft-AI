package io.github.zoyluo.minecraftai.gametest;

import io.github.zoyluo.minecraftai.goal.Goal;
import io.github.zoyluo.minecraftai.pathfinding.AStarPathfinder;
import io.github.zoyluo.minecraftai.persist.MissionSpec;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import java.util.List;
import java.util.Set;

/**
 * Minimal world-backed smoke tests for the isolated GameTest source set.
 *
 * <p>These tests deliberately avoid random state, external services and MinecraftAi persistence so a
 * failure always reflects the compiled mod/runtime rather than a reused world.</p>
 */
public final class MinecraftAiDeterministicGameTests {
    @GameTest(maxTicks = 20)
    public void blockMutationIsVisible(GameTestHelper context) {
        context.setBlock(1, 1, 1, Blocks.STONE);
        context.assertBlockPresent(Blocks.STONE, 1, 1, 1);
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void scheduledAssertionRunsAtExpectedTick(GameTestHelper context) {
        context.setBlock(2, 1, 2, Blocks.OAK_PLANKS);
        context.runAtTickTime(2, () -> {
            context.assertBlockPresent(Blocks.OAK_PLANKS, 2, 1, 2);
            context.succeed();
        });
    }

    @GameTest(maxTicks = 20)
    public void blockedEndpointPrefersNearestUpperStandOverDeeperCave(GameTestHelper context) {
        BlockPos start = context.absolutePos(new BlockPos(1, 3, 1));
        BlockPos requested = start.offset(3, 0, 0);
        var world = context.getLevel();

        for (int dx = 0; dx < 3; dx++) {
            world.setBlockAndUpdate(start.offset(dx, -1, 0), Blocks.STONE.defaultBlockState());
            world.setBlockAndUpdate(start.offset(dx, 0, 0), Blocks.AIR.defaultBlockState());
            world.setBlockAndUpdate(start.offset(dx, 1, 0), Blocks.AIR.defaultBlockState());
        }
        // The requested feet cell is a one-block obstacle. Both requested.up() and a cell three
        // blocks below are standable; endpoint resolution must honor actual distance.
        world.setBlockAndUpdate(requested, Blocks.STONE.defaultBlockState());
        world.setBlockAndUpdate(requested.above(), Blocks.AIR.defaultBlockState());
        world.setBlockAndUpdate(requested.above(2), Blocks.AIR.defaultBlockState());
        world.setBlockAndUpdate(requested.below(), Blocks.AIR.defaultBlockState());
        world.setBlockAndUpdate(requested.below(2), Blocks.AIR.defaultBlockState());
        world.setBlockAndUpdate(requested.below(3), Blocks.AIR.defaultBlockState());
        world.setBlockAndUpdate(requested.below(4), Blocks.STONE.defaultBlockState());

        var result = new AStarPathfinder(world, start, requested, 1_000, 50L, false, false).findPath();
        if (!result.success() || !requested.above().equals(result.resolvedGoal())) {
            context.fail(Component.nullToEmpty("blocked endpoint resolved away from nearest upper stand: "
                    + result.resolvedGoal() + " reason=" + result.reason()));
        }
        context.succeed();
    }

    @GameTest(maxTicks = 20)
    public void missionSpecsRoundTripWithBootstrappedRegistries(GameTestHelper context) {
        List<Goal> goals = List.of(
                new Goal.HaveItem(Items.IRON_INGOT, 3),
                new Goal.HavePickaxeTier(3),
                new Goal.MineOre(Set.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE), 4),
                new Goal.HarvestCrop(Blocks.WHEAT, Items.WHEAT_SEEDS, Items.WHEAT, 8),
                new Goal.Armor(),
                new Goal.Workstation(),
                new Goal.Stockpile(Items.COBBLESTONE, 64),
                new Goal.Food(5),
                new Goal.Build("small_hut"));

        for (Goal goal : goals) {
            Goal restored = MissionSpec.fromGoal(goal).toGoal().orElseThrow();
            if (!goal.equals(restored)) {
                context.fail(Component.nullToEmpty("MissionSpec round-trip mismatch for " + goal));
            }
        }
        context.succeed();
    }
}
