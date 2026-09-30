package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.entity.TeleportAudit;
import io.github.zoyluo.minecraftai.gametest.BotFixtureMoves;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.assertPhysicalExit;
import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.finish;
import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.isSealed;
import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.preparePlatform;
import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.require;
import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.runLocked;
import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.shelterShell;

/**
 * The emergency shelter moves by keys and physics, never by a teleport (R5): it settles at the middle of its cell by a walked step,
 * jumps for the roof support, sneaks over the edge of its support to place a foundation and walks out through the door. Each test
 * runs the whole shelter and asserts that the bot was never teleported ({@link TeleportAudit#corrections}) and never moved farther
 * in one tick than a walking or jumping body can.
 */
public final class ShelterNaturalMovementGameTests {
    /** Blocks a bot may move in one tick by its own legs (a sprint is 0.28, a jump adds 0.42 up): a teleport of a cell is more. */
    private static final double MAX_STEP = 0.6D;

    @GameTest(environment = "minecraftai-gametest:shelter_natural_movement_game_tests_shelter_builds_without_teleport", maxTicks = 3000)
    public void shelterBuildsWithoutTeleport(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 5);
        AIPlayerEntity bot = spawn(context, "ShelterNaturalBuildGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 24));
        bot.setHealth(14.0F);
        pressure(context, feet.offset(5, 0, 2));
        pressure(context, feet.offset(-5, 0, -3));
        // Off-centre and still sliding toward the east wall cell, as a bot that has just stopped walking.
        BotFixtureMoves.place(bot, new Vec3(feet.getX() + 0.5D + 0.3D, feet.getY(), feet.getZ() + 0.5D));
        bot.setOnGround(true);
        bot.setDeltaMovement(new Vec3(0.1D, 0.0D, 0.0D));
        TeleportAudit.reset(bot);

        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_natural_build"));
        watchWholeShelter(context, bot, task, feet, "ShelterNaturalBuildGT", false);
    }

    @GameTest(environment = "minecraftai-gametest:shelter_natural_movement_game_tests_shelter_on_slope_uses_sneak_shift", maxTicks = 3000)
    public void shelterOnSlopeUsesSneakShift(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 5);
        // A ledge: the east and west neighbours have no floor (a one-block pit under them), so each needs a foundation block that
        // the bot places from the edge of its own support. North stays a planned landing (the way out).
        for (BlockPos pit : List.of(feet.east(), feet.west())) {
            context.getLevel().setBlock(pit.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            context.getLevel().setBlock(pit.below(2), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        }
        AIPlayerEntity bot = spawn(context, "ShelterNaturalSlopeGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 24));
        TeleportAudit.reset(bot);

        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_natural_slope"));
        watchWholeShelter(context, bot, task, feet, "ShelterNaturalSlopeGT", true);
    }

    @GameTest(environment = "minecraftai-gametest:shelter_natural_movement_game_tests_shelter_egress_walks_out", maxTicks = 3000)
    public void shelterEgressWalksOut(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 5);
        AIPlayerEntity bot = spawn(context, "ShelterNaturalEgressGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 24));
        TeleportAudit.reset(bot);

        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_natural_egress"));
        boolean[] doorOpened = {false};
        int[] doorOpenTick = {-1};
        int[] ticks = {0};
        Vec3[] last = {bot.position()};
        double[] farthest = {0.0D};
        boolean[] sealedOnce = {false};
        runLocked(context, () -> {
            context.getLevel().setDayTime(1000L);
            ticks[0]++;
            failOnTeleport(context, bot, task, last, farthest);
            List<BlockPos> shell = shelterShell(feet);
            boolean sealed = shell.stream().allMatch(pos -> isSealed(context, pos));
            if (sealed) {
                sealedOnce[0] = true;
            }
            if (sealedOnce[0] && !sealed && !doorOpened[0]) {
                doorOpened[0] = true;
                doorOpenTick[0] = ticks[0];
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, sealedOnce[0], "the shelter never sealed");
            require(context, doorOpened[0], "the door was never opened");
            require(context, ticks[0] - doorOpenTick[0] >= 3, "the way out took " + (ticks[0] - doorOpenTick[0])
                    + " ticks: a walk through the door takes longer than that");
            assertPhysicalExit(context, bot, feet);
            require(context, TeleportAudit.corrections(bot) == 0, "the way out teleported the bot (" + TeleportAudit.lastCaller(bot) + ")");
            finish(context, bot, "ShelterNaturalEgressGT");
        });
    }

    private static void watchWholeShelter(GameTestHelper context,
                                          AIPlayerEntity bot,
                                          EmergencyShelterTask task,
                                          BlockPos feet,
                                          String name,
                                          boolean foundations) {
        List<BlockPos> shell = shelterShell(feet);
        Vec3[] last = {bot.position()};
        double[] farthest = {0.0D};
        boolean[] sealedOnce = {false};
        double[] lowest = {bot.getY()};
        runLocked(context, () -> {
            context.getLevel().setDayTime(1000L);
            failOnTeleport(context, bot, task, last, farthest);
            lowest[0] = Math.min(lowest[0], bot.getY());
            require(context, bot.getY() >= feet.getY() - 0.01D,
                    "the bot fell off its support: y=" + bot.getY() + " at " + bot.position());
            if (shell.stream().allMatch(pos -> isSealed(context, pos))) {
                if (!sealedOnce[0]) {
                    require(context, bot.blockPosition().equals(feet), "the shelter sealed with the bot outside: " + bot.blockPosition());
                }
                sealedOnce[0] = true;
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, sealedOnce[0], "the shelter completed without ever being sealed");
            if (foundations) {
                require(context, isSealed(context, feet.east().below()) && isSealed(context, feet.west().below()),
                        "the foundations under the east and west walls were not placed");
            }
            assertPhysicalExit(context, bot, feet);
            require(context, TeleportAudit.corrections(bot) == 0,
                    "the shelter teleported the bot " + TeleportAudit.corrections(bot) + " times (" + TeleportAudit.lastCaller(bot) + ")");
            finish(context, bot, name);
        });
    }

    /** Fails the test when the shelter ended badly, the bot was teleported, or it moved farther in a tick than legs can carry it. */
    private static void failOnTeleport(GameTestHelper context,
                                       AIPlayerEntity bot,
                                       EmergencyShelterTask task,
                                       Vec3[] last,
                                       double[] farthest) {
        if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
            context.fail(net.minecraft.network.chat.Component.nullToEmpty(
                    "the shelter ended as " + task.state() + ":" + task.failureReason() + " " + task.describe()));
            return;
        }
        require(context, TeleportAudit.corrections(bot) == 0,
                "the shelter teleported the bot (" + TeleportAudit.lastCaller(bot) + ") " + task.describe());
        double step = bot.position().distanceTo(last[0]);
        farthest[0] = Math.max(farthest[0], step);
        require(context, step <= MAX_STEP, "the bot moved " + step + " blocks in one tick: " + last[0] + " -> " + bot.position());
        last[0] = bot.position();
    }

    private static void pressure(GameTestHelper context, BlockPos feet) {
        Zombie zombie = EntityType.ZOMBIE.create(context.getLevel(), EntitySpawnReason.COMMAND);
        if (zombie == null) {
            throw new IllegalStateException("failed to create the pressure zombie");
        }
        zombie.setPersistenceRequired();
        zombie.setNoAi(true);
        zombie.snapTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 0.0F, 0.0F);
        context.getLevel().addFreshEntity(zombie);
    }

    private static AIPlayerEntity spawn(GameTestHelper context, String name, BlockPos feet) {
        context.getLevel().setDayTime(1000L);
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        context.getLevel().getServer(), name, context.getLevel(),
                        Vec3.atBottomCenterOf(feet), 0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        BotFixtureMoves.place(bot, feet);
        bot.setOnGround(true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        bot.getFoodData().setSaturation(5.0F);
        return bot;
    }
}
