package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.perception.SharedWorldSight;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The look-around scans and the water sights with a bot's eyes, which pass through foliage and water: a trunk behind the canopy
 * is a log sighting, a leaf with nothing behind it is still a landmark, a plant or a cobweb the ray crosses is found, and a pool
 * behind leaves is seen.
 */
public final class HorizonScanSeeThroughGameTests {
    private static final BlockState LEAF = Blocks.OAK_LEAVES.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private static void prepare(GameTestHelper context, BlockPos feet) {
        for (int dx = -1; dx <= 8; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = 0; dy <= 8; dy++) {
                    context.getLevel().setBlock(feet.offset(dx, dy, dz), AIR, Block.UPDATE_ALL);
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
        return bot;
    }

    private static void finish(GameTestHelper context, List<String> failures) {
        if (failures.isEmpty()) {
            context.succeed();
        } else {
            context.fail(Component.nullToEmpty(String.join("; ", failures)));
        }
    }

    @GameTest(environment = "minecraftai-gametest:horizon_scan_see_through_game_tests_the_tree_scan_sights_a_trunk_behind_leaves_and_keeps_the_leaf_landmark", maxTicks = 100)
    public void theTreeScanSightsATrunkBehindLeavesAndKeepsTheLeafLandmark(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(2, 4, 4));
        prepare(context, feet);
        AIPlayerEntity bot = spawn(context, "SeeThroughTree", feet);
        var world = context.getLevel();
        BlockPos leaf = feet.above(3);
        BlockPos log = feet.above(4);
        world.setBlock(leaf, LEAF, Block.UPDATE_ALL);
        world.setBlock(log, Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);
        List<String> failures = new ArrayList<>();
        SharedWorldSight.forget(bot.getUUID());
        TreeHorizonScan.Sighting trunk = new TreeHorizonScan(Set.of(Blocks.OAK_LOG)).step(bot);
        if (trunk == null || trunk.kind() != TreeHorizonScan.Kind.LOG || !log.equals(trunk.pos())) {
            failures.add("the trunk behind the leaf was not sighted as a log: " + trunk);
        }
        world.setBlock(log, AIR, Block.UPDATE_ALL);
        SharedWorldSight.forget(bot.getUUID());
        TreeHorizonScan.Sighting landmark = new TreeHorizonScan(Set.of(Blocks.OAK_LOG)).step(bot);
        if (landmark == null || landmark.kind() != TreeHorizonScan.Kind.LEAF || !leaf.equals(landmark.pos())) {
            failures.add("a leaf with nothing behind it was not kept as the landmark: " + landmark);
        }
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), "SeeThroughTree");
        finish(context, failures);
    }

    @GameTest(environment = "minecraftai-gametest:horizon_scan_see_through_game_tests_the_target_scan_finds_a_block_behind_leaves_and_a_cobweb_it_crosses", maxTicks = 100)
    public void theTargetScanFindsABlockBehindLeavesAndACobwebItCrosses(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(2, 4, 4));
        prepare(context, feet);
        AIPlayerEntity bot = spawn(context, "SeeThroughTarget", feet);
        var world = context.getLevel();
        BlockPos leaf = feet.above(3);
        BlockPos log = feet.above(4);
        world.setBlock(leaf, LEAF, Block.UPDATE_ALL);
        world.setBlock(log, Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);
        List<String> failures = new ArrayList<>();
        SharedWorldSight.forget(bot.getUUID());
        VisibleTargetHorizonScan.Sighting behind = new VisibleTargetHorizonScan(Set.of(Blocks.OAK_LOG)).step(bot);
        if (behind == null || !log.equals(behind.pos())) {
            failures.add("the log behind the leaf was not found: " + behind);
        }
        // A cobweb is see-through too, yet it is what a scan for cobwebs came to find: it is among the cells the ray crossed.
        world.setBlock(leaf, Blocks.COBWEB.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(log, AIR, Block.UPDATE_ALL);
        SharedWorldSight.forget(bot.getUUID());
        VisibleTargetHorizonScan.Sighting web = new VisibleTargetHorizonScan(Set.of(Blocks.COBWEB)).step(bot);
        if (web == null || !leaf.equals(web.pos())) {
            failures.add("the cobweb was not found: " + web);
        }
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), "SeeThroughTarget");
        finish(context, failures);
    }

    @GameTest(environment = "minecraftai-gametest:horizon_scan_see_through_game_tests_a_declined_sighting_stays_declined_whether_the_ray_crosses_or_strikes_it", maxTicks = 100)
    public void aDeclinedSightingStaysDeclinedWhetherTheRayCrossesOrStrikesIt(GameTestHelper context) {
        // What the caller could do nothing with from where the bot stands is not offered again from there, however the eyes meet
        // it: a log the ray strikes behind a leaf, the leaf it crosses on the way, a cobweb it crosses.
        BlockPos feet = context.absolutePos(new BlockPos(2, 4, 4));
        prepare(context, feet);
        AIPlayerEntity bot = spawn(context, "SeeThroughDeclined", feet);
        var world = context.getLevel();
        BlockPos leaf = feet.above(3);
        BlockPos log = feet.above(4);
        world.setBlock(leaf, LEAF, Block.UPDATE_ALL);
        world.setBlock(log, Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);
        List<String> failures = new ArrayList<>();
        SharedWorldSight.forget(bot.getUUID());
        TreeHorizonScan tree = new TreeHorizonScan(Set.of(Blocks.OAK_LOG));
        TreeHorizonScan.Sighting trunk = tree.step(bot);
        if (trunk == null || trunk.kind() != TreeHorizonScan.Kind.LOG || !log.equals(trunk.pos())) {
            failures.add("the trunk behind the leaf was not sighted first: " + trunk);
        }
        tree.decline(bot, log);
        TreeHorizonScan.Sighting landmark = tree.step(bot);
        if (landmark == null || landmark.kind() != TreeHorizonScan.Kind.LEAF || !leaf.equals(landmark.pos())) {
            failures.add("with the struck log declined the leaf the ray crosses was not the landmark: " + landmark);
        }
        tree.decline(bot, leaf);
        TreeHorizonScan.Sighting nothing = tree.step(bot);
        if (nothing != null) {
            failures.add("a declined log and a declined leaf were offered again from the same cell: " + nothing);
        }
        world.setBlock(leaf, Blocks.COBWEB.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(log, AIR, Block.UPDATE_ALL);
        SharedWorldSight.forget(bot.getUUID());
        VisibleTargetHorizonScan target = new VisibleTargetHorizonScan(Set.of(Blocks.COBWEB));
        VisibleTargetHorizonScan.Sighting web = target.step(bot);
        if (web == null || !leaf.equals(web.pos())) {
            failures.add("the cobweb was not found first: " + web);
        }
        target.decline(bot, leaf);
        VisibleTargetHorizonScan.Sighting again = target.step(bot);
        if (again != null) {
            failures.add("a declined cobweb the ray crosses was offered again from the same cell: " + again);
        }
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), "SeeThroughDeclined");
        finish(context, failures);
    }

    @GameTest(environment = "minecraftai-gametest:horizon_scan_see_through_game_tests_a_pool_behind_leaves_is_seen_and_a_pool_behind_stone_is_not", maxTicks = 100)
    public void aPoolBehindLeavesIsSeenAndAPoolBehindStoneIsNot(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(2, 4, 4));
        prepare(context, feet);
        AIPlayerEntity bot = spawn(context, "SeeThroughPool", feet);
        var world = context.getLevel();
        BlockPos pool = feet.east(4).below();
        world.setBlock(pool, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        List<String> failures = new ArrayList<>();
        if (!BoatSupport.canObserveWater(bot, pool) || !FireExtinguishTask.canSeeWaterSurface(bot, pool)) {
            failures.add("a pool in the open is not seen: boat " + BoatSupport.canObserveWater(bot, pool)
                    + " fire " + FireExtinguishTask.canSeeWaterSurface(bot, pool));
        }
        for (int dz = -3; dz <= 3; dz++) {
            for (int dy = 0; dy <= 2; dy++) {
                world.setBlock(feet.offset(2, dy, dz), LEAF, Block.UPDATE_ALL);
            }
        }
        if (!BoatSupport.canObserveWater(bot, pool) || !FireExtinguishTask.canSeeWaterSurface(bot, pool)) {
            failures.add("a pool behind leaves is not seen: boat " + BoatSupport.canObserveWater(bot, pool)
                    + " fire " + FireExtinguishTask.canSeeWaterSurface(bot, pool));
        }
        for (int dz = -3; dz <= 3; dz++) {
            for (int dy = 0; dy <= 2; dy++) {
                world.setBlock(feet.offset(2, dy, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        if (BoatSupport.canObserveWater(bot, pool) || FireExtinguishTask.canSeeWaterSurface(bot, pool)) {
            failures.add("a pool behind stone is seen: boat " + BoatSupport.canObserveWater(bot, pool)
                    + " fire " + FireExtinguishTask.canSeeWaterSurface(bot, pool));
        }
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), "SeeThroughPool");
        finish(context, failures);
    }
}
