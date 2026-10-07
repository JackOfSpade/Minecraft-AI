package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import static io.github.zoyluo.minecraftai.task.DigOutTaskGameTests.NOON;
import static io.github.zoyluo.minecraftai.task.DigOutTaskGameTests.ROOF;
import static io.github.zoyluo.minecraftai.task.DigOutTaskGameTests.cleanUp;
import static io.github.zoyluo.minecraftai.task.DigOutTaskGameTests.require;
import static io.github.zoyluo.minecraftai.task.DigOutTaskGameTests.spawnAt;
import static io.github.zoyluo.minecraftai.task.DigOutTaskGameTests.stoneWithRoom;

/**
 * Opening a cell of the stair can show a lava or water cell that was hidden behind it, and the fluid comes in through the opening.
 * The bot closes that opening with a block it carries, the way a player does, never opens it again and goes up another way; with
 * nothing to close it with it backs down the stair to where it began and says so. It never goes into the lava.
 *
 * <p>Fixture: {@link DigOutTaskGameTests}'s room under rock. A bot dropped in the middle faces south and walks to the north wall,
 * where its first rise opens the cell over the room's ceiling at {@code (0, 2, -2)}, then the landing {@code (0, 1, -3)} and its head
 * cell {@code (0, 2, -3)} (offsets from the room's centre). The fluid is put in the rock where one of those openings reveals it.</p>
 */
public final class DigOutFlowGameTests {
    /** The cells scanned for fluid at the end: the room, the stair and the rock around them. */
    private static final int FROM_X = -3;
    private static final int TO_X = 3;
    private static final int TO_Y = 8;
    private static final int FROM_Z = -9;
    private static final int TO_Z = 2;

    @GameTest(maxTicks = 2400)
    public void aLavaFlowOpenedOverTheBotsHeadIsClosedWithABlockAndTheStairGoesUpAnotherWay(GameTestHelper context) {
        // The lava is over the first cell of the rise: opening it shows the lava straight above, and every rise from that cell goes through it.
        closedFlow(context, "DigOutLavaOverGT", 1020, new BlockPos(0, 3, -2), Blocks.LAVA, new BlockPos(0, 2, -2));
    }

    @GameTest(maxTicks = 2400)
    public void aLavaFlowOpenedBesideTheLandingIsClosedWithABlockAndTheStairGoesUpAnotherWay(GameTestHelper context) {
        // The lava is beside the head cell of the landing: only a rise through that cell shows it.
        closedFlow(context, "DigOutLavaBesideGT", 1060, new BlockPos(-1, 2, -3), Blocks.LAVA, new BlockPos(0, 2, -3));
    }

    @GameTest(maxTicks = 2400)
    public void aWaterFlowOpenedOverTheBotsHeadIsClosedWithABlockAndTheStairGoesUpAnotherWay(GameTestHelper context) {
        closedFlow(context, "DigOutWaterOverGT", 1100, new BlockPos(0, 3, -2), Blocks.WATER, new BlockPos(0, 2, -2));
    }

    /**
     * The bot digs with a stone pickaxe and carries a few blocks of dirt. The fluid source is the only fluid there is at the end, which
     * says that the opening was closed before the fluid's own tick (lava's is a second and a half away): the cell it enters by holds
     * the block that was put there.
     */
    private static void closedFlow(GameTestHelper context, String name, int z, BlockPos fluidAt, Block fluid, BlockPos entryAt) {
        ServerLevel world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(20, 5, z));
        stoneWithRoom(world, feet, Blocks.STONE);
        world.setBlock(feet.offset(fluidAt), fluid.defaultBlockState(), Block.UPDATE_ALL);
        world.setDayTime(NOON);
        AIPlayerEntity bot = spawnAt(context, name, feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 4));
        DigOutTask task = new DigOutTask();
        // A bot that has just joined sees nothing until its chunk view is set up a tick or two later.
        context.runAfterDelay(10, () -> TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_dig_out_flow")));
        int[] ticks = {0};
        context.failIfEver(() -> {
            require(context, bot.isAlive(), "the bot died");
            require(context, !bot.isInLava(), "the bot stood in lava at " + bot.blockPosition());
            require(context, task.state() == TaskState.PENDING || task.state() == TaskState.RUNNING
                    || task.state() == TaskState.COMPLETED, "the task ended as " + task.state() + ": " + task.failureReason());
            if (task.state() == TaskState.COMPLETED) {
                require(context, world.getBlockState(feet.offset(entryAt)).is(Blocks.DIRT),
                        "the cell the flow enters by was not closed with the block: " + world.getBlockState(feet.offset(entryAt)));
                int fluids = countBlocks(world, feet, fluid);
                require(context, fluids == 1, "the fluid got out of its cell: " + fluids + " cells of it");
                require(context, !DangerWatcher.isDarkTrapCell(world, bot.blockPosition()),
                        "the dig-out ended in the dark at " + bot.blockPosition());
                require(context, bot.blockPosition().getY() >= feet.getY() + ROOF - 2,
                        "the bot did not climb out of the rock: " + bot.blockPosition() + " from " + feet);
                require(context, task.risen() >= 1, "the bot never rose");
                cleanUp(bot);
                context.succeed();
                return;
            }
            require(context, ++ticks[0] < 2300, "the bot never dug out past the flow: " + bot.blockPosition()
                    + " state=" + task.state() + " risen=" + task.risen());
        });
    }

    /**
     * With nothing to close the flow with (it digs netherrack by hand, which drops nothing, and carries nothing) the bot goes back down
     * its stair, away from the opening, to the cell it began in, and ends with the typed reason. The lava is over the first cell of the
     * second rise, so there is a stair to back down.
     */
    @GameTest(maxTicks = 2000)
    public void withNothingToCloseALavaFlowWithTheBotBacksDownTheStairAndSaysSo(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(20, 5, 1140));
        stoneWithRoom(world, feet, Blocks.NETHERRACK);
        world.setBlock(feet.offset(0, 4, -3), Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);
        world.setDayTime(NOON);
        AIPlayerEntity bot = spawnAt(context, "DigOutNoBlockGT", feet);
        DigOutTask task = new DigOutTask();
        context.runAfterDelay(10, () -> TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_dig_out_no_block")));
        int[] ticks = {0};
        context.failIfEver(() -> {
            require(context, bot.isAlive(), "the bot died");
            require(context, !bot.isInLava(), "the bot stood in lava at " + bot.blockPosition());
            require(context, task.state() != TaskState.COMPLETED, "the task completed past a flow it could not close");
            if (task.state() == TaskState.FAILED) {
                require(context, (DigOutTask.FLOW_UNSEALED + ":no_block").equals(task.failureReason()),
                        "the task did not say the flow could not be closed: " + task.failureReason());
                require(context, task.risen() == 1, "the flow was met on the second rise, not after " + task.risen());
                require(context, bot.blockPosition().equals(feet),
                        "the bot did not back down the stair to where it began: " + bot.blockPosition() + " from " + feet);
                cleanUp(bot);
                context.succeed();
                return;
            }
            require(context, ++ticks[0] < 1900, "the bot neither closed the flow nor backed away from it: "
                    + bot.blockPosition() + " state=" + task.state() + " risen=" + task.risen());
        });
    }

    private static int countBlocks(ServerLevel world, BlockPos feet, Block block) {
        int count = 0;
        for (int dx = FROM_X; dx <= TO_X; dx++) {
            for (int dy = 0; dy <= TO_Y; dy++) {
                for (int dz = FROM_Z; dz <= TO_Z; dz++) {
                    if (world.getBlockState(feet.offset(dx, dy, dz)).is(block)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }
}
