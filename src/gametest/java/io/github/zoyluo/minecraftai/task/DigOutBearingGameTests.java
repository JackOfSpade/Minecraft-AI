package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import static io.github.zoyluo.minecraftai.task.DigOutTaskGameTests.NOON;
import static io.github.zoyluo.minecraftai.task.DigOutTaskGameTests.cleanUp;
import static io.github.zoyluo.minecraftai.task.DigOutTaskGameTests.require;
import static io.github.zoyluo.minecraftai.task.DigOutTaskGameTests.snapshot;
import static io.github.zoyluo.minecraftai.task.DigOutTaskGameTests.spawnAt;
import static io.github.zoyluo.minecraftai.task.DigOutTaskGameTests.stoneWithRoom;

/**
 * Which way a bot stuck in the dark goes first: toward a lit cell it can see, and the way it always went when it sees nothing brighter.
 * It does not know where the light is, so a lit chamber behind rock changes nothing.
 *
 * <p>Fixture: {@link DigOutTaskGameTests}'s room under rock, the bot in the middle facing south. With nothing in sight it walks to the
 * first wall of its search order, the north one. A torch eleven cells away in a corridor on the east side lights the corridor but
 * leaves the room dark (light 3 where the bot stands).</p>
 */
public final class DigOutBearingGameTests {
    private static final int TORCH_AT = 11;

    @GameTest(maxTicks = 900)
    public void aLitCorridorSeenOnOneSideIsWhereTheBotHeadsFirstAndItNeedsNoDigging(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(20, 5, 1560));
        stoneWithRoom(world, feet, Blocks.STONE);
        for (int dx = 3; dx <= TORCH_AT; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                world.setBlock(feet.offset(dx, dy, 0), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        world.setBlock(feet.offset(TORCH_AT, 0, 0), Blocks.TORCH.defaultBlockState(), Block.UPDATE_ALL);
        world.setDayTime(NOON);
        AIPlayerEntity bot = spawnAt(context, "DigOutLitGT", feet);
        Map<BlockPos, BlockState> before = snapshot(world, feet);
        DigOutTask task = new DigOutTask();
        // A bot that has just joined sees nothing until its chunk view is set up a tick or two later, and the light of the torch
        // reaches the corridor a tick after it is placed.
        context.runAfterDelay(10, () -> {
            require(context, DangerWatcher.isDarkTrapCell(world, feet),
                    "fixture: the bot's own cell must still be a dark trap, light " + world.getBrightness(LightLayer.BLOCK, feet));
            require(context, world.getBrightness(LightLayer.BLOCK, feet.offset(TORCH_AT, 0, 0)) >= 13,
                    "fixture: the torch has no light yet");
            TaskManager.INSTANCE.assign(bot, task, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_dig_out_lit"));
        });
        BlockPos[] firstMove = {null};
        int[] ticks = {0};
        context.failIfEver(() -> {
            require(context, bot.isAlive(), "the bot died");
            require(context, task.state() == TaskState.PENDING || task.state() == TaskState.RUNNING
                    || task.state() == TaskState.COMPLETED, "the task ended as " + task.state() + ": " + task.failureReason());
            if (firstMove[0] == null && !bot.blockPosition().equals(feet)) {
                firstMove[0] = bot.blockPosition();
                require(context, firstMove[0].equals(feet.east()),
                        "the bot did not head for the light it could see: its first move was to " + firstMove[0] + " from " + feet);
            }
            if (task.state() == TaskState.COMPLETED) {
                require(context, firstMove[0] != null, "the task completed without moving");
                require(context, !DangerWatcher.isDarkTrapCell(world, bot.blockPosition()),
                        "the dig-out ended in the dark at " + bot.blockPosition());
                require(context, bot.blockPosition().getX() > feet.getX(), "the bot ended at " + bot.blockPosition());
                require(context, task.risen() == 0, "the lit corridor needed no stair, but the bot rose " + task.risen());
                for (Map.Entry<BlockPos, BlockState> entry : before.entrySet()) {
                    require(context, world.getBlockState(entry.getKey()).equals(entry.getValue()),
                            "something was dug or placed at " + entry.getKey());
                }
                cleanUp(bot);
                context.succeed();
                return;
            }
            require(context, ++ticks[0] < 800, "the bot never reached the light: " + bot.blockPosition() + " state=" + task.state());
        });
    }

    @GameTest(maxTicks = 600)
    public void aLitChamberBehindRockIsNotKnownAndTheBotGoesTheWayItAlwaysDid(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(20, 5, 1600));
        stoneWithRoom(world, feet, Blocks.STONE);
        // Three cells of rock away from the room's east wall, a closed chamber with a torch in it.
        for (int dx = 6; dx <= 8; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    world.setBlock(feet.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        BlockPos torch = feet.offset(7, 0, 0);
        world.setBlock(torch, Blocks.TORCH.defaultBlockState(), Block.UPDATE_ALL);
        world.setDayTime(NOON);
        AIPlayerEntity bot = spawnAt(context, "DigOutHiddenLitGT", feet);
        DigOutTask task = new DigOutTask();
        context.runAfterDelay(10, () -> {
            require(context, world.getBrightness(LightLayer.BLOCK, torch) >= 13, "fixture: the chamber is not lit");
            require(context, DangerWatcher.isDarkTrapCell(world, feet), "fixture: the bot's own cell must be a dark trap");
            TaskManager.INSTANCE.assign(bot, task, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_dig_out_hidden_light"));
        });
        int[] ticks = {0};
        context.failIfEver(() -> {
            require(context, bot.isAlive(), "the bot died");
            if (!bot.blockPosition().equals(feet)) {
                require(context, bot.blockPosition().equals(feet.north()),
                        "with nothing brighter in sight the bot walks to the first wall of its search order, not to "
                                + bot.blockPosition());
                cleanUp(bot);
                context.succeed();
                return;
            }
            require(context, ++ticks[0] < 500, "the bot never moved: state=" + task.state());
        });
    }
}
