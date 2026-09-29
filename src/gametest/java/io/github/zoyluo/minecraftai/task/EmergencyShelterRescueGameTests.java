package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.List;
import java.util.Set;
import net.minecraft.text.Text;

import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.assertPhysicalExit;
import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.finish;
import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.isSealed;
import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.preparePlatform;
import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.require;
import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.runLocked;
import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.shelterShell;

/**
 * Live regressions for the no-food rescue lifecycle inside a sealed recovery shelter: the
 * cry-for-help/wait/rescue-delivery path below half health, the 50%-and-out-of-food exception
 * that gives up waiting to fight instead, and the ordinary "ran out of food right as it finished
 * healing" case that must not be mistaken for either.
 */
public final class EmergencyShelterRescueGameTests {
    @GameTest(environment = "minecraftai-gametest:emergency_shelter_rescue_game_tests_below_half_health_without_food_waits_and_cries_for_help_once", maxTicks = 16000)
    public void belowHalfHealthWithoutFoodWaitsAndCriesForHelpOnce(TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 4);
        List<BlockPos> shell = shelterShell(feet);
        AIPlayerEntity bot = spawn(context, "ShelterRescueWaitGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));
        // Deliberately no food at all: this fixture verifies the genuinely-stuck path.

        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_rescue_wait"));
        boolean[] lowHealthInjected = {false};
        boolean[] sawWaitingForRescue = {false};
        boolean[] sawCriedForHelp = {false};
        int[] firstCriedForHelpTick = {-1};

        boolean[] timeLockAcquired = {false};
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

            context.getWorld().setTimeOfDay(1000L);
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException(Text.of("rescue-wait shelter ended as "
                        + task.state() + ":" + task.failureReason() + " " + task.describe()));
                return;
            }
            boolean sealed = shell.stream().allMatch(pos -> isSealed(context, pos));
            if (!lowHealthInjected[0] && sealed) {
                bot.setHealth(6.0F); // 30% of 20 max: below the 50% give-up-and-fight threshold
                lowHealthInjected[0] = true;
                return;
            }
            if (lowHealthInjected[0]) {
                // The whole point of this fixture: a bot this hurt, with zero food, must never
                // silently open its own door. It must stay sealed for as long as this test runs.
                require(context, task.state() == TaskState.RUNNING
                                && task.describe().contains("phase=HOLD"),
                        "a bot below half health with no food must stay sealed, not exit: "
                                + task.describe());
                if (task.describe().contains("waiting_for_rescue=true")) {
                    sawWaitingForRescue[0] = true;
                }
                if (task.describe().contains("cried_for_help=true")) {
                    if (firstCriedForHelpTick[0] < 0) {
                        firstCriedForHelpTick[0] = task.elapsedTicks();
                    }
                    sawCriedForHelp[0] = true;
                } else {
                    require(context, firstCriedForHelpTick[0] < 0,
                            "cried_for_help must never clear once set within the same episode");
                }
                require(context, !task.describe().contains("rescue_resolved=true"),
                        "nothing has rescued this bot yet, so it must not claim to be resolved");
                if (firstCriedForHelpTick[0] >= 0
                        && task.elapsedTicks() - firstCriedForHelpTick[0] > 200) {
                    // Waited long enough with the cry-for-help state stable and no regression.
                    // A genuinely stuck bot is expected to sit here indefinitely (isWaiting()==true
                    // is exactly this contract) until the player rescues it -- end the fixture here
                    // rather than running out the full maxTicks budget.
                    require(context, sawWaitingForRescue[0] && sawCriedForHelp[0],
                            "never actually observed the wait/cry-for-help rescue state");
                    finish(context, bot, "ShelterRescueWaitGT");
                }
            }
        });
    }

    @GameTest(environment = "minecraftai-gametest:emergency_shelter_rescue_game_tests_food_delivered_while_waiting_resumes_healing_and_exits_safely", maxTicks = 16000)
    public void foodDeliveredWhileWaitingResumesHealingAndExitsSafely(TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 4);
        List<BlockPos> shell = shelterShell(feet);
        AIPlayerEntity bot = spawn(context, "ShelterRescuedGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));

        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_rescue_delivery"));
        boolean[] lowHealthInjected = {false};
        boolean[] waitedForRescue = {false};
        boolean[] foodDelivered = {false};
        boolean[] sawRescuedResumeHealing = {false};

        boolean[] timeLockAcquired = {false};
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

            context.getWorld().setTimeOfDay(1000L);
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException(Text.of("rescue-delivery shelter ended as "
                        + task.state() + ":" + task.failureReason() + " " + task.describe()));
                return;
            }
            boolean sealed = shell.stream().allMatch(pos -> isSealed(context, pos));
            if (!lowHealthInjected[0] && sealed) {
                bot.setHealth(4.0F); // 20% of 20 max
                lowHealthInjected[0] = true;
                return;
            }
            if (lowHealthInjected[0] && !waitedForRescue[0]) {
                if (task.describe().contains("waiting_for_rescue=true")
                        && task.describe().contains("cried_for_help=true")) {
                    waitedForRescue[0] = true;
                }
                return;
            }
            // Simulate the player's rescue: break a wall block, toss the food in, reseal it.
            // Vanilla item pickup (already exercised elsewhere in this project) is what would
            // actually deliver a tossed item in production; handing it straight to the inventory
            // here isolates this fixture to the shelter's own reactive detection of it, per the
            // spec's point 4 ("some periodic check ... must re-check do I have food now").
            if (waitedForRescue[0] && !foodDelivered[0]) {
                InventoryAction.giveItem(bot, new ItemStack(Items.COOKED_BEEF, 4));
                foodDelivered[0] = true;
                return;
            }
            if (foodDelivered[0] && !sawRescuedResumeHealing[0]
                    && task.describe().contains("waiting_for_rescue=false")
                    && task.describe().contains("cried_for_help=true")) {
                sawRescuedResumeHealing[0] = true;
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, waitedForRescue[0] && foodDelivered[0] && sawRescuedResumeHealing[0],
                    "fixture did not actually exercise wait -> rescue -> resume healing");
            require(context, bot.getHealth() >= bot.getMaxHealth(),
                    "shelter exited without finishing the resumed healing: hp=" + bot.getHealth());
            require(context, task.describe().contains("rescue_resolved=true"),
                    "an episode that cried for help must announce it no longer needs rescue");
            assertPhysicalExit(context, bot, feet);
            finish(context, bot, "ShelterRescuedGT");
        });
    }

    @GameTest(environment = "minecraftai-gametest:emergency_shelter_rescue_game_tests_half_health_without_food_gives_up_waiting_and_exits_to_fight", maxTicks = 16000)
    public void halfHealthWithoutFoodGivesUpWaitingAndExitsToFight(TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 4);
        List<BlockPos> shell = shelterShell(feet);
        AIPlayerEntity bot = spawn(context, "ShelterGiveUpFightGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));

        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_give_up_and_fight"));
        boolean[] healthInjected = {false};

        boolean[] timeLockAcquired = {false};
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

            context.getWorld().setTimeOfDay(1000L);
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException(Text.of("give-up-and-fight shelter ended as "
                        + task.state() + ":" + task.failureReason() + " " + task.describe()));
                return;
            }
            boolean sealed = shell.stream().allMatch(pos -> isSealed(context, pos));
            if (!healthInjected[0] && sealed) {
                bot.setHealth(12.0F); // 60% of 20 max: at/above the give-up-and-fight threshold
                healthInjected[0] = true;
                return;
            }
            if (healthInjected[0]) {
                require(context, !task.describe().contains("cried_for_help=true"),
                        "60% HP is enough to fight with; this must exit immediately, never wait: "
                                + task.describe());
                require(context, !task.describe().contains("waiting_for_rescue=true"),
                        "the 50% exception must skip the wait state entirely: " + task.describe());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, healthInjected[0], "give-up-and-fight fixture never reached HOLD");
            require(context, bot.getHealth() >= 12.0F,
                    "the 50% exception must not have waited for health to change: hp="
                            + bot.getHealth());
            assertPhysicalExit(context, bot, feet);
            finish(context, bot, "ShelterGiveUpFightGT");
        });
    }

    @GameTest(environment = "minecraftai-gametest:emergency_shelter_rescue_game_tests_full_health_out_of_food_exits_without_crying_for_help", maxTicks = 16000)
    public void fullHealthOutOfFoodExitsWithoutCryingForHelp(TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 4);
        List<BlockPos> shell = shelterShell(feet);
        AIPlayerEntity bot = spawn(context, "ShelterFullNoFoodGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));

        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_full_health_no_food"));
        boolean[] stateInjected = {false};

        boolean[] timeLockAcquired = {false};
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

            context.getWorld().setTimeOfDay(1000L);
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException(Text.of("full-health-no-food shelter ended as "
                        + task.state() + ":" + task.failureReason() + " " + task.describe()));
                return;
            }
            boolean sealed = shell.stream().allMatch(pos -> isSealed(context, pos));
            if (!stateInjected[0] && sealed) {
                // Point 6a: already at full health, hunger short of twenty, and nothing left to
                // eat -- the ordinary "food ran out right around when healing finished" case.
                bot.setHealth(bot.getMaxHealth());
                bot.getHungerManager().setFoodLevel(15);
                bot.getHungerManager().setSaturationLevel(0.0F);
                stateInjected[0] = true;
                return;
            }
            if (stateInjected[0]) {
                require(context, !task.describe().contains("cried_for_help=true"),
                        "6a is the normal case, not a rescue scenario: " + task.describe());
                require(context, !task.describe().contains("waiting_for_rescue=true"),
                        "already at full health, so healing was never actually stalled: "
                                + task.describe());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, stateInjected[0], "full-health-no-food fixture never reached HOLD");
            assertPhysicalExit(context, bot, feet);
            finish(context, bot, "ShelterFullNoFoodGT");
        });
    }

    private static AIPlayerEntity spawn(TestContext context, String name, BlockPos feet) {
        context.getWorld().setTimeOfDay(1000L);
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        context.getWorld().getServer(), name, context.getWorld(),
                        Vec3d.ofBottomCenter(feet), 0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(context.getWorld(), feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        bot.getHungerManager().setSaturationLevel(5.0F);
        return bot;
    }

}
