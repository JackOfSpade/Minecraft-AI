package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * A bot stuck in the dark with nothing to light the cell with, and no emergency teleport (strict survival), digs a stair up
 * out of it: no torch is placed, nothing on the surface is lit, and where it cannot dig it says so and stops.
 *
 * <p>Fixture: a block of stone, a 5x5 room in it two cells high with five blocks of rock over it, and open sky over that.</p>
 */
public final class DigOutTaskGameTests {
    private static final long NOON = 6000L;
    private static final int HALF = 12;
    private static final int ROOF = 5;

    @GameTest(maxTicks = 2400)
    public void aBotWithNothingToLightItselfDigsAStairOutOfARoomUnderRock(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(20, 5, 900));
        stoneWithRoom(world, feet, Blocks.STONE);
        world.setDayTime(NOON);
        AIPlayerEntity bot = spawnAt(context, "DigOutRoomGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        require(context, MinecraftAiConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                "GameTest must run under strict_survival, got " + MinecraftAiConfig.get().profile());
        Map<BlockPos, BlockState> before = snapshot(world, feet);
        boolean[] digging = {false};
        int[] ticks = {0};
        context.failIfEver(() -> {
            require(context, bot.isAlive(), "the bot died");
            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            if (active instanceof DigOutTask) {
                digging[0] = true;
            }
            require(context, InventoryAction.countItem(bot, Items.TORCH) == 0 && noTorchPlaced(world, feet),
                    "a torch was placed or made: the bot had nothing to light with");
            if (digging[0] && active == null) {
                BlockPos at = bot.blockPosition();
                require(context, !DangerWatcher.isDarkTrapCell(world, at),
                        "the dig-out ended in a cell that is still a dark trap: " + at);
                require(context, at.getY() >= feet.getY() + ROOF - 2,
                        "the bot did not climb out of the rock: " + at + " from " + feet);
                require(context, DangerWatcher.INSTANCE.darkTrapDetections(bot) == 1,
                        "the same trap was judged " + DangerWatcher.INSTANCE.darkTrapDetections(bot) + " times");
                int opened = 0;
                for (Map.Entry<BlockPos, BlockState> entry : before.entrySet()) {
                    BlockState now = world.getBlockState(entry.getKey());
                    if (!now.equals(entry.getValue())) {
                        require(context, now.isAir(), "something was placed in the rock at " + entry.getKey());
                        opened++;
                    }
                }
                // A rise opens up to three cells, and the bot had to climb the rock over the room.
                require(context, opened >= ROOF - 1, "almost nothing was dug: " + opened);
                cleanUp(bot);
                context.succeed();
                return;
            }
            require(context, ++ticks[0] < 2300, "the bot never dug out of the room under the rock: active="
                    + (active == null ? "none" : active.name()) + " at " + bot.blockPosition() + " detections="
                    + DangerWatcher.INSTANCE.darkTrapDetections(bot));
        });
    }

    /** A rise over gravel is refused (it would fall into the stair); the bot goes up another way. */
    @GameTest(maxTicks = 2400)
    public void gravelInTheWayTurnsTheStairAsideAndLeavesTheGravelAlone(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(20, 5, 940));
        stoneWithRoom(world, feet, Blocks.STONE);
        world.setDayTime(NOON);
        // Over the first landing of a rise north from the north wall of the room.
        BlockPos gravel = feet.offset(0, 2, -3);
        world.setBlock(gravel, Blocks.GRAVEL.defaultBlockState(), Block.UPDATE_ALL);
        AIPlayerEntity bot = spawnAt(context, "DigOutGravelGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        boolean[] digging = {false};
        int[] ticks = {0};
        context.failIfEver(() -> {
            require(context, bot.isAlive(), "the bot died");
            require(context, world.getBlockState(gravel).is(Blocks.GRAVEL), "the gravel was dug or fell");
            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            if (active instanceof DigOutTask) {
                digging[0] = true;
            }
            if (digging[0] && active == null) {
                require(context, !DangerWatcher.isDarkTrapCell(world, bot.blockPosition()),
                        "the dig-out ended in the dark at " + bot.blockPosition());
                require(context, bot.blockPosition().getY() >= feet.getY() + ROOF - 2,
                        "the bot did not climb out of the rock: " + bot.blockPosition());
                cleanUp(bot);
                context.succeed();
                return;
            }
            require(context, ++ticks[0] < 2300, "the bot never got past the gravel: " + bot.blockPosition());
        });
    }

    /** A room whose every wall is something a bot may not dig: the task ends where it says why, and nothing is dug. */
    @GameTest(maxTicks = 1500)
    public void withNothingItMayDigTheTaskEndsAndSaysSo(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(20, 5, 980));
        stoneWithRoom(world, feet, Blocks.STONE_BRICKS);
        world.setDayTime(NOON);
        AIPlayerEntity bot = spawnAt(context, "DigOutWalledGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        Map<BlockPos, BlockState> before = snapshot(world, feet);
        DigOutTask task = new DigOutTask();
        TaskManager.INSTANCE.assign(bot, task, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_dig_out_walled"));
        int[] ticks = {0};
        context.failIfEver(() -> {
            require(context, bot.isAlive(), "the bot died");
            if (task.state() == TaskState.FAILED) {
                require(context, task.failureReason().startsWith("dig_out_blocked:break_refused"),
                        "the task did not say it was refused a break: " + task.failureReason());
                for (Map.Entry<BlockPos, BlockState> entry : before.entrySet()) {
                    require(context, world.getBlockState(entry.getKey()).equals(entry.getValue()),
                            "something that may not be dug was changed at " + entry.getKey());
                }
                cleanUp(bot);
                context.succeed();
                return;
            }
            require(context, task.state() == TaskState.RUNNING, "the task ended as " + task.state());
            require(context, ++ticks[0] < 1400, "the task neither dug out nor gave up: " + bot.blockPosition());
        });
    }

    // ---- fixture ---------------------------------------------------------------------------------------------------

    /**
     * Rock (of {@code rock}) from {@code HALF} blocks either way and one under the room to {@code ROOF + 2} over its floor, a 5x5
     * room two cells high in it with {@code feet} at its centre, and clear air over the top.
     */
    private static void stoneWithRoom(ServerLevel world, BlockPos feet, Block rock) {
        for (int dx = -HALF; dx <= HALF; dx++) {
            for (int dz = -HALF; dz <= HALF; dz++) {
                for (int dy = -1; dy <= ROOF + 1; dy++) {
                    boolean room = Math.abs(dx) <= 2 && Math.abs(dz) <= 2 && (dy == 0 || dy == 1);
                    world.setBlock(feet.offset(dx, dy, dz),
                            room ? Blocks.AIR.defaultBlockState() : rock.defaultBlockState(), Block.UPDATE_ALL);
                }
                for (int dy = ROOF + 2; dy <= ROOF + 12; dy++) {
                    world.setBlock(feet.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
    }

    private static Map<BlockPos, BlockState> snapshot(ServerLevel world, BlockPos feet) {
        Map<BlockPos, BlockState> states = new HashMap<>();
        for (int dx = -HALF; dx <= HALF; dx++) {
            for (int dz = -HALF; dz <= HALF; dz++) {
                for (int dy = -1; dy <= ROOF + 1; dy++) {
                    BlockPos pos = feet.offset(dx, dy, dz).immutable();
                    states.put(pos, world.getBlockState(pos));
                }
            }
        }
        return states;
    }

    private static boolean noTorchPlaced(ServerLevel world, BlockPos feet) {
        for (int dx = -HALF; dx <= HALF; dx++) {
            for (int dz = -HALF; dz <= HALF; dz++) {
                for (int dy = -1; dy <= ROOF + 12; dy++) {
                    BlockState state = world.getBlockState(feet.offset(dx, dy, dz));
                    if (state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static AIPlayerEntity spawnAt(GameTestHelper context, String name, BlockPos feet) {
        ServerLevel world = context.getLevel();
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(feet),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        bot.getFoodData().setSaturation(5.0F);
        return bot;
    }

    private static void cleanUp(AIPlayerEntity bot) {
        TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_complete");
        DangerWatcher.INSTANCE.clear(bot);
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), bot.getGameProfile().name());
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
    }
}
