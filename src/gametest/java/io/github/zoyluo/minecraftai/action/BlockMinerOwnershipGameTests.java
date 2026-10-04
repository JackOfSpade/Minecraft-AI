package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Generation leases prevent a stale single-block miner from claiming another controller's break. */
public final class BlockMinerOwnershipGameTests {
    @GameTest(maxTicks = 40)
    public void sameTargetPreemptionDoesNotCompleteTheStaleMiner(GameTestHelper context) {
        Fixture fixture = spawn(context, "BlockMinerPreemptGT");
        BlockPos target = fixture.feet().north();
        context.getLevel().setBlock(target, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);

        BlockMiner stale = new BlockMiner();
        stale.begin(fixture.bot(), target);
        require(context, stale.tick(fixture.bot()) == BlockMiner.Status.MINING,
                "first miner did not acquire its initial controller");
        long firstGeneration = fixture.bot().getActionPack().miningGeneration();
        ActionResult successor = fixture.bot().getActionPack().startMining(target, Direction.NORTH);
        require(context, successor.isInProgress()
                        && fixture.bot().getActionPack().miningGeneration() > firstGeneration,
                "same-target successor did not replace the first mining generation");

        // The successor may break the block before the stale owner receives its next task tick.
        // Visible air used to make that owner report DONE; it must now reject its lost lease.
        context.getLevel().setBlock(target, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        require(context, stale.tick(fixture.bot()) == BlockMiner.Status.FAILED
                        && BlockMiner.MINING_PREEMPTED.equals(stale.failureReason()),
                "stale generation accepted the successor's visible-air completion: " + stale.failureReason());
        cleanup(context, fixture);
    }

    @GameTest(maxTicks = 40)
    public void cancellingAnActiveControllerRevokesItsGeneration(GameTestHelper context) {
        Fixture fixture = spawn(context, "BlockMinerCancelGT");
        BlockPos target = fixture.feet().north();
        context.getLevel().setBlock(target, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);

        BlockMiner stale = new BlockMiner();
        stale.begin(fixture.bot(), target);
        require(context, stale.tick(fixture.bot()) == BlockMiner.Status.MINING,
                "miner did not acquire its controller before cancellation");
        long activeGeneration = fixture.bot().getActionPack().miningGeneration();
        fixture.bot().getActionPack().stopMining();
        require(context, fixture.bot().getActionPack().miningGeneration() > activeGeneration,
                "cancelling an active controller did not revoke its generation");

        context.getLevel().setBlock(target, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        require(context, stale.tick(fixture.bot()) == BlockMiner.Status.FAILED
                        && BlockMiner.MINING_PREEMPTED.equals(stale.failureReason()),
                "cancelled generation accepted a visible-air completion: " + stale.failureReason());
        cleanup(context, fixture);
    }

    @GameTest(maxTicks = 40)
    public void unstartedVisibleAirStillSettlesDone(GameTestHelper context) {
        Fixture fixture = spawn(context, "BlockMinerAirGT");
        BlockMiner miner = new BlockMiner();
        miner.begin(fixture.bot(), fixture.feet().north());

        require(context, miner.tick(fixture.bot()) == BlockMiner.Status.DONE,
                "an unstarted miner no longer settles an exposed empty target");
        cleanup(context, fixture);
    }

    @GameTest(maxTicks = 40)
    public void staleUnstartedAirCannotCancelASuccessor(GameTestHelper context) {
        Fixture fixture = spawn(context, "BlockMinerStaleAirGT");
        BlockMiner stale = new BlockMiner();
        stale.begin(fixture.bot(), fixture.feet().north());

        BlockPos successorTarget = fixture.feet().east();
        context.getLevel().setBlock(successorTarget, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
        ActionResult successor = fixture.bot().getActionPack().startMining(successorTarget, Direction.EAST);
        require(context, successor.isInProgress(), "successor did not acquire the mining controller");
        long successorGeneration = fixture.bot().getActionPack().miningGeneration();

        require(context, stale.tick(fixture.bot()) == BlockMiner.Status.DONE,
                "stale empty target did not settle locally");
        require(context, fixture.bot().getActionPack().miningGeneration() == successorGeneration
                        && !fixture.bot().getActionPack().isMiningIdle(),
                "stale empty target cancelled the successor controller");
        cleanup(context, fixture);
    }

    private static Fixture spawn(GameTestHelper context, String name) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 4, 4));
        context.getLevel().setBlock(feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        context.getLevel().getServer(), name, context.getLevel(), Vec3.atBottomCenterOf(feet),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(context.getLevel(), feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setOnGround(true);
        return new Fixture(bot, feet, name);
    }

    private static void cleanup(GameTestHelper context, Fixture fixture) {
        fixture.bot().getActionPack().stopAll();
        AIPlayerManager.INSTANCE.despawn(fixture.bot().level().getServer(), fixture.name());
        context.succeed();
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }

    private record Fixture(AIPlayerEntity bot, BlockPos feet, String name) {
    }
}
