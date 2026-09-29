package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.EquipAction;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.ToolSelector;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.runtime.IntentController;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.github.zoyluo.minecraftai.task.SensingArena.botLog;
import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.require;

/**
 * Real-server proofs of the survival-reflex fixes from the 2026-09-29 session review: wounded-and-stalled
 * regeneration eating, the dark-trap reflex no longer firing under a tree canopy in daylight, a chat request
 * not cancelling a running SAFETY task, defensive combat not being assigned against an out-of-leash target,
 * the tool selector ignoring air/fluid, armor-equip and damage log coalescing, and diag_health_drop naming its
 * cause.
 */
public final class SurvivalReflexGameTests {
    private static final long NOON = 6000L;

    // ---- 1: wounded bot with food < 18 eats to full and regenerates ----

    @GameTest(environment = "minecraftai-gametest:survival_reflex_game_tests_wounded_bot_below_regen_food_eats_and_regenerates", maxTicks = 700)
    public void woundedBotBelowRegenFoodEatsAndRegenerates(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "WoundedEaterGT", 20, 5, 30, 4);
        bot.setHealth(12.0F);
        bot.getFoodData().setFoodLevel(17);
        bot.getFoodData().setSaturation(0.0F);
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 3));
        boolean[] sawEat = {false};
        context.failIfEver(() -> {
            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            if (active instanceof EatTask) {
                sawEat[0] = true;
                require(context, active.state() != TaskState.FAILED, "EatTask failed: " + active.failureReason());
            }
            if (sawEat[0] && bot.getFoodData().getFoodLevel() >= DangerWatcher.REGEN_FOOD_LEVEL
                    && bot.getHealth() > 12.0F) {
                require(context, InventoryAction.countItem(bot, Items.BREAD) < 3, "food rose without eating bread");
                despawnAndComplete(context, bot);
            }
        });
    }

    @GameTest(environment = "minecraftai-gametest:survival_reflex_game_tests_wounded_bot_does_not_eat_while_a_safety_task_runs", maxTicks = 120)
    public void woundedBotDoesNotEatWhileASafetyTaskRuns(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "WoundedSafetyGT", 20, 5, 70, 4);
        bot.setHealth(12.0F);
        bot.getFoodData().setFoodLevel(17);
        bot.getFoodData().setSaturation(0.0F);
        InventoryAction.giveItem(bot, new ItemStack(Items.BREAD, 3));
        HoldingTask safety = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, safety, TaskOrigin.safety("gametest_safety_hold"));
        int[] ticks = {0};
        context.failIfEver(() -> {
            require(context, TaskManager.INSTANCE.getActive(bot).orElse(null) == safety,
                    "a regeneration bite replaced the running SAFETY task");
            require(context, bot.getFoodData().getFoodLevel() == 17, "the bot ate during a SAFETY task");
            if (++ticks[0] >= 80) {
                despawnAndComplete(context, bot);
            }
        });
    }

    @GameTest(environment = "minecraftai-gametest:survival_reflex_game_tests_healthy_or_regenerating_bot_does_not_eat", maxTicks = 120)
    public void healthyOrRegeneratingBotDoesNotEat(GameTestHelper context) {
        AIPlayerEntity fullHealth = spawnOnPlatform(context, "FullHpFood17GT", 20, 5, 110, 4);
        fullHealth.setHealth(fullHealth.getMaxHealth());
        fullHealth.getFoodData().setFoodLevel(17);
        InventoryAction.giveItem(fullHealth, new ItemStack(Items.BREAD, 3));
        AIPlayerEntity regenerating = spawnOnPlatform(context, "Hp12Food18GT", 40, 5, 110, 4);
        regenerating.setHealth(12.0F);
        regenerating.getFoodData().setFoodLevel(18);
        InventoryAction.giveItem(regenerating, new ItemStack(Items.BREAD, 3));
        int[] ticks = {0};
        context.failIfEver(() -> {
            require(context, !(TaskManager.INSTANCE.getActive(fullHealth).orElse(null) instanceof EatTask),
                    "a full-health bot ate at food 17");
            require(context, !(TaskManager.INSTANCE.getActive(regenerating).orElse(null) instanceof EatTask),
                    "a wounded bot that already regenerates (food 18) ate");
            if (++ticks[0] >= 80) {
                despawnAndComplete(context, fullHealth, regenerating);
            }
        });
    }

    /**
     * Negative control for the regen-stall rule: a wounded bot carrying ONLY harmful food (the last-resort
     * items rotten flesh, spider eye, pufferfish) must not be sent to eat it "to heal". The positive control
     * (bread) is {@link #woundedBotBelowRegenFoodEatsAndRegenerates}.
     */
    @GameTest(environment = "minecraftai-gametest:survival_reflex_game_tests_wounded_bot_with_only_harmful_food_does_not_eat", maxTicks = 300)
    public void woundedBotWithOnlyHarmfulFoodDoesNotEat(GameTestHelper context) {
        net.minecraft.world.item.Item[] harmful = {Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.PUFFERFISH};
        AIPlayerEntity[] bots = new AIPlayerEntity[harmful.length];
        for (int index = 0; index < harmful.length; index++) {
            bots[index] = spawnOnPlatform(context, "HarmfulOnly" + index + "GT", 20 + index * 20, 5, 480, 4);
            bots[index].setHealth(12.0F);
            bots[index].getFoodData().setFoodLevel(17);
            bots[index].getFoodData().setSaturation(0.0F);
            InventoryAction.giveItem(bots[index], new ItemStack(harmful[index], 4));
        }
        int[] ticks = {0};
        context.failIfEver(() -> {
            for (int index = 0; index < harmful.length; index++) {
                AIPlayerEntity bot = bots[index];
                require(context, !(TaskManager.INSTANCE.getActive(bot).orElse(null) instanceof EatTask),
                        "a wounded bot with only " + harmful[index] + " started eating it");
                require(context, bot.getFoodData().getFoodLevel() == 17 && InventoryAction.countItem(bot, harmful[index]) == 4,
                        "a wounded bot with only " + harmful[index] + " ate it");
                require(context, !DangerWatcher.isRegenStall(bot), "harmful food counted as regen-stall food: " + harmful[index]);
            }
            if (++ticks[0] >= 200) {
                despawnAndComplete(context, bots);
            }
        });
    }

    /**
     * The eat-to-full loop started for a regen stall never falls back to harmful food (it stops instead), and
     * the stall predicate is side-effect free: an offhand stack is neither promoted nor logged by the check.
     */
    @GameTest(environment = "minecraftai-gametest:survival_reflex_game_tests_regen_stall_eat_task_never_falls_back_to_harmful_food", maxTicks = 200)
    public void regenStallEatTaskNeverFallsBackToHarmfulFood(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "SafeOnlyEatGT", 20, 5, 510, 4);
        bot.setHealth(12.0F);
        bot.getFoodData().setFoodLevel(17);
        bot.getFoodData().setSaturation(0.0F);
        InventoryAction.giveItem(bot, new ItemStack(Items.ROTTEN_FLESH, 2));
        bot.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.BREAD, 2));
        // Purity: scanning while wounded leaves the offhand bread where it is.
        for (int scan = 0; scan < 5; scan++) {
            require(context, DangerWatcher.isRegenStall(bot), "offhand bread does not count as safe food");
        }
        require(context, bot.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.BREAD)
                        && bot.getItemBySlot(EquipmentSlot.OFFHAND).getCount() == 2,
                "isRegenStall promoted/moved the offhand stack");
        require(context, InventoryAction.hasSafeFood(bot), "hasSafeFood misses the offhand bread");
        // Take the safe food away: only rotten flesh is left, a safe-only pass must stop without eating it.
        bot.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        require(context, !InventoryAction.hasSafeFood(bot) && InventoryAction.findSafeFoodSlot(bot) < 0,
                "harmful food reported as safe");
        require(context, !DangerWatcher.isRegenStall(bot), "rotten flesh alone is a regen stall");
        EatTask task = EatTask.safeFoodOnly();
        TaskManager.INSTANCE.assign(bot, task, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_safe_only_eat"));
        int[] ticks = {0};
        context.failIfEver(() -> {
            require(context, InventoryAction.countItem(bot, Items.ROTTEN_FLESH) == 2 && bot.getFoodData().getFoodLevel() == 17,
                    "the safe-only eat pass ate rotten flesh");
            if (task.state() == TaskState.FAILED) {
                require(context, "no_food".equals(task.failureReason()), "unexpected failure: " + task.failureReason());
                despawnAndComplete(context, bot);
                return;
            }
            require(context, ++ticks[0] < 120 && task.state() != TaskState.COMPLETED,
                    "the safe-only eat pass neither failed nor stayed harmless: " + task.state());
        });
    }

    // ---- 2: dark-trap reflex ----

    @GameTest(environment = "minecraftai-gametest:survival_reflex_game_tests_bot_under_tree_canopy_at_noon_is_not_dark_trapped", maxTicks = 1500)
    public void botUnderTreeCanopyAtNoonIsNotDarkTrapped(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(20, 5, 150));
        buildFloor(world, feet, 9);
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = 3; dy <= 4; dy++) {
                    world.setBlock(feet.offset(dx, dy, dz),
                            Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true), Block.UPDATE_ALL);
                }
            }
        }
        for (int dy = 0; dy <= 4; dy++) {
            world.setBlock(feet.offset(2, dy, 0), Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_ALL);
        }
        AIPlayerEntity bot = spawnAt(context, "CanopyNoonGT", feet);
        int[] ticks = {0};
        TimeLockedRun.run(context, 1200, () -> {
            world.setDayTime(NOON);
            if (ticks[0] == 40) {
                // The fixture reproduces the reported case: no sky view and no block light (the old test).
                require(context, !world.canSeeSky(feet)
                                && world.getBrightness(LightLayer.BLOCK, feet) < DangerWatcher.DARK_TRAP_LIGHT,
                        "fixture is not the reported case (the bot can see the sky or has block light)");
            }
            require(context, DangerWatcher.INSTANCE.darkTrapDetections(bot) == 0,
                    "the bot under a tree canopy at noon was judged trapped in the dark");
            return ++ticks[0] >= 320; // more than 160 ticks standing still after the light settled, the old trigger
        }, () -> cleanUp(bot));
    }

    @GameTest(environment = "minecraftai-gametest:survival_reflex_game_tests_bot_in_sealed_dark_stone_pocket_is_dark_trapped", maxTicks = 1500)
    public void botInSealedDarkStonePocketIsDarkTrapped(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(20, 5, 190));
        int wall = 3;
        for (int dx = -wall; dx <= wall; dx++) {
            for (int dz = -wall; dz <= wall; dz++) {
                boolean onWall = Math.abs(dx) == wall || Math.abs(dz) == wall;
                world.setBlock(feet.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                for (int dy = 0; dy <= 3; dy++) {
                    world.setBlock(feet.offset(dx, dy, dz), onWall || dy == 3
                            ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        AIPlayerEntity bot = spawnAt(context, "DarkPocketGT", feet);
        int[] ticks = {0};
        TimeLockedRun.run(context, 1200, () -> {
            world.setDayTime(NOON);
            require(context, !SurfaceCheck.isOnSurface(world, feet), "fixture pocket is not under a roof");
            if (DangerWatcher.INSTANCE.darkTrapDetections(bot) >= 1) {
                return true;
            }
            require(context, ++ticks[0] < 500, "the bot in a sealed dark stone pocket was never judged trapped");
            return false;
        }, () -> cleanUp(bot));
    }

    // ---- 3: a chat request does not cancel a running SAFETY task ----

    @GameTest(environment = "minecraftai-gametest:survival_reflex_game_tests_new_chat_request_keeps_a_running_safety_task", maxTicks = 60)
    public void newChatRequestKeepsARunningSafetyTask(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "ChatSafetyGT", 20, 5, 230, 4);
        // 1. A safety task with mission work paused beneath it: the request replaces the mission, not the fight.
        HoldingTask mission = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, mission, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_mission"));
        TaskManager.INSTANCE.pauseFor(bot, "gametest_threat");
        HoldingTask fight = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, fight, TaskOrigin.safety("gametest_fight"));
        IntentController.INSTANCE.cancelAllKeepingActiveSafety(
                bot, IntentController.ControlOrigin.SYSTEM, "new_player_request");
        require(context, TaskManager.INSTANCE.getActive(bot).orElse(null) == fight,
                "the chat request cancelled the running SAFETY task");
        require(context, fight.state() == TaskState.RUNNING, "the SAFETY task is no longer RUNNING: " + fight.state());
        require(context, !TaskManager.INSTANCE.hasPaused(bot), "the paused mission beneath the fight survived the request");
        require(context, mission.state() != TaskState.RUNNING && mission.state() != TaskState.PAUSED,
                "the paused mission was not cancelled: " + mission.state());
        // 2. An explicit stop still preempts the SAFETY task.
        IntentController.INSTANCE.cancelAll(bot, IntentController.ControlOrigin.PLAYER_COMMAND, "stop");
        require(context, TaskManager.INSTANCE.getActive(bot).isEmpty(), "an explicit stop did not preempt the SAFETY task");
        // 3. An ordinary (non-safety) task is still replaced by a new request.
        HoldingTask ordinary = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, ordinary, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_ordinary"));
        IntentController.INSTANCE.cancelAllKeepingActiveSafety(
                bot, IntentController.ControlOrigin.SYSTEM, "new_player_request");
        require(context, TaskManager.INSTANCE.getActive(bot).isEmpty(), "a new chat request did not cancel ordinary work");
        despawnAndComplete(context, bot);
    }

    /**
     * The model's task tools while a SAFETY task runs: blocked (the fight is not cancelled), and the request
     * is remembered so the brain re-wakes when the threat ends instead of losing the player's request.
     */
    @GameTest(environment = "minecraftai-gametest:survival_reflex_game_tests_llm_tool_during_a_safety_task_is_blocked_and_deferred", maxTicks = 60)
    public void llmToolDuringASafetyTaskIsBlockedAndDeferred(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "ToolBlockGT", 20, 5, 540, 4);
        HoldingTask fight = new HoldingTask();
        TaskManager.INSTANCE.assign(bot, fight, TaskOrigin.safety("gametest_fight"));
        var dispatcher = new io.github.zoyluo.minecraftai.brain.ActionDispatcher(new io.github.zoyluo.minecraftai.brain.ToolRegistry());
        try {
            var results = dispatcher.dispatch(bot, List.of(
                    new io.github.zoyluo.minecraftai.brain.ChatToolCall("call_1", "eat", "{}")));
            require(context, results.size() == 1, "expected one tool result, got " + results.size());
            String content = results.get(0).content();
            require(context, content.contains("blocked") && content.contains("safety_task_active")
                            && !content.contains("assigned"),
                    "the tool was not blocked by the SAFETY task: " + content);
            require(context, TaskManager.INSTANCE.getActive(bot).orElse(null) == fight && fight.state() == TaskState.RUNNING,
                    "the blocked tool disturbed the running SAFETY task");
            require(context, io.github.zoyluo.minecraftai.brain.BrainCoordinator.INSTANCE.isRequestDeferredForTest(bot),
                    "the blocked request was not remembered for the end of the SAFETY task");
        } finally {
            io.github.zoyluo.minecraftai.brain.BrainCoordinator.INSTANCE.reset(bot); // no LLM wake from a test
        }
        // An ordinary (non-safety) active task does not block the tool: the request is not deferred.
        TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_reset");
        var ordinary = dispatcher.dispatch(bot, List.of(
                new io.github.zoyluo.minecraftai.brain.ChatToolCall("call_2", "eat", "{}")));
        require(context, ordinary.get(0).content().contains("assigned"),
                "the tool was blocked without a SAFETY task: " + ordinary.get(0).content());
        require(context, !io.github.zoyluo.minecraftai.brain.BrainCoordinator.INSTANCE.isRequestDeferredForTest(bot),
                "a request was deferred without a SAFETY task");
        despawnAndComplete(context, bot);
    }

    // ---- 4: defensive combat is not assigned against a target already outside the leash ----

    @GameTest(environment = "minecraftai-gametest:survival_reflex_game_tests_out_of_leash_hostile_does_not_start_and_drop_combat_repeatedly", maxTicks = 600)
    public void outOfLeashHostileDoesNotStartAndDropCombatRepeatedly(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(20, 5, 270));
        buildFloor(world, feet, 14);
        AIPlayerEntity bot = spawnAt(context, "LeashWatcherGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_SWORD));
        EquipAction.equipBestWeapon(bot);
        Husk husk = EntityType.HUSK.create(world, EntitySpawnReason.COMMAND);
        if (husk == null) {
            cleanUp(bot);
            context.fail(Component.nullToEmpty("failed to create the husk fixture"));
            return;
        }
        BlockPos huskFeet = feet.east(9); // inside the ten-block pressure range, outside the eight-block leash
        husk.setPersistenceRequired();
        husk.setNoAi(true);
        husk.snapTo(huskFeet.getX() + 0.5D, huskFeet.getY(), huskFeet.getZ() + 0.5D, 90.0F, 0.0F);
        world.addFreshEntity(husk);
        Set<Task> combats = new HashSet<>();
        int[] ticks = {0};
        TimeLockedRun.run(context, 500, () -> {
            world.setDayTime(18000L);
            if (ticks[0] == 40) {
                require(context, DangerWatcher.hasObservableHostilePressure(bot),
                        "fixture: the husk is not observable hostile pressure");
                require(context, !CombatTask.isWithinDefensiveLeash(bot.blockPosition(), husk.blockPosition()),
                        "fixture: the husk is inside the leash");
            }
            TaskManager.INSTANCE.getActive(bot).ifPresent(task -> {
                if (task instanceof CombatTask) {
                    combats.add(task);
                }
            });
            if (++ticks[0] < 200) {
                return false;
            }
            husk.discard();
            require(context, combats.isEmpty(),
                    "defensive combat was assigned " + combats.size() + " time(s) against a target outside the leash");
            List<String> lines = botLog(bot.getGameProfile().name());
            require(context, lines != null, "the per-bot log is unavailable, so the disengage count cannot be checked");
            long disengaged = lines.stream().filter(line -> line.contains("event=defensive_combat_disengaged")).count();
            require(context, disengaged == 0, "defensive combat disengaged " + disengaged + " time(s) with target_left_leash");
            return true;
        }, () -> cleanUp(bot));
    }

    /** Positive control for the leash rule: a hostile INSIDE the leash still gets defensive combat. */
    @GameTest(environment = "minecraftai-gametest:survival_reflex_game_tests_hostile_inside_the_leash_still_gets_defensive_combat", maxTicks = 700)
    public void hostileInsideTheLeashStillGetsDefensiveCombat(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockPos feet = context.absolutePos(new BlockPos(20, 5, 570));
        buildFloor(world, feet, 14);
        AIPlayerEntity bot = spawnAt(context, "LeashInsideGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_SWORD));
        EquipAction.equipBestWeapon(bot);
        Husk husk = EntityType.HUSK.create(world, EntitySpawnReason.COMMAND);
        if (husk == null) {
            cleanUp(bot);
            context.fail(Component.nullToEmpty("failed to create the husk fixture"));
            return;
        }
        BlockPos huskFeet = feet.east(4);
        husk.setPersistenceRequired();
        husk.setNoAi(true);
        husk.snapTo(huskFeet.getX() + 0.5D, huskFeet.getY(), huskFeet.getZ() + 0.5D, 90.0F, 0.0F);
        world.addFreshEntity(husk);
        int[] ticks = {0};
        TimeLockedRun.run(context, 600, () -> {
            world.setDayTime(18000L);
            if (ticks[0] == 40) {
                require(context, CombatTask.isWithinDefensiveLeash(bot.blockPosition(), husk.blockPosition()),
                        "fixture: the husk is outside the leash");
            }
            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            if (active instanceof CombatTask) {
                husk.discard();
                return true;
            }
            require(context, ++ticks[0] < 400, "a hostile inside the leash never got defensive combat");
            return false;
        }, () -> cleanUp(bot));
    }

    // ---- 5: the tool selector ignores air and fluid ----

    @GameTest(environment = "minecraftai-gametest:survival_reflex_game_tests_equip_best_tool_ignores_air_and_fluid_targets", maxTicks = 60)
    public void equipBestToolIgnoresAirAndFluidTargets(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "ToolAirGT", 20, 5, 320, 4);
        var stacks = bot.getInventory().getNonEquipmentItems();
        for (int slot = 0; slot < stacks.size(); slot++) {
            stacks.set(slot, ItemStack.EMPTY);
        }
        stacks.set(0, new ItemStack(Items.IRON_PICKAXE));
        stacks.set(1, new ItemStack(Items.OAK_PLANKS, 32));
        bot.getInventory().setSelectedSlot(0);
        for (var state : List.of(Blocks.AIR.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState(),
                Blocks.WATER.defaultBlockState(), Blocks.LAVA.defaultBlockState())) {
            ToolSelector.Selection selection = ToolSelector.equipBestTool(bot, state);
            require(context, !selection.changed(), "equipBestTool changed the selection for " + state.getBlock());
            require(context, bot.getInventory().getSelectedSlot() == 0,
                    "equipBestTool moved the hotbar to slot " + bot.getInventory().getSelectedSlot() + " for " + state.getBlock());
        }
        despawnAndComplete(context, bot);
    }

    // ---- 6: armor equip logging is coalesced ----

    @GameTest(environment = "minecraftai-gametest:survival_reflex_game_tests_repeated_armor_equip_is_logged_once", maxTicks = 300)
    public void repeatedArmorEquipIsLoggedOnce(GameTestHelper context) {
        AIPlayerEntity bot = spawnOnPlatform(context, "ArmorLogGT", 20, 5, 360, 4);
        for (int round = 0; round < 3; round++) {
            bot.getInventory().getNonEquipmentItems().set(5, new ItemStack(Items.IRON_CHESTPLATE));
            bot.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
            require(context, EquipAction.equipBestArmor(bot) == 1, "round " + round + ": the chestplate was not equipped");
        }
        int[] ticks = {0};
        context.failIfEver(() -> {
            if (++ticks[0] < 40) {
                return;
            }
            List<String> lines = botLog(bot.getGameProfile().name());
            if (lines == null) {
                require(context, ticks[0] < 200, "the per-bot log is unavailable"); // never pass vacuously
                return;
            }
            long logged = lines.stream().filter(line -> line.contains("event=equip_armor")).count();
            if (logged < 1 && ticks[0] < 200) {
                return; // the per-bot log is written asynchronously
            }
            require(context, logged == 1, "the same chestplate equip was logged " + logged + " times, expected 1");
            despawnAndComplete(context, bot);
        });
    }

    // ---- 7 + 8: damage source in diag_health_drop, damage log coalescing ----

    @GameTest(environment = "minecraftai-gametest:survival_reflex_game_tests_diag_health_drop_names_the_damage_source", maxTicks = 450)
    public void diagHealthDropNamesTheDamageSource(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        AIPlayerEntity bot = spawnOnPlatform(context, "DiagCauseGT", 20, 5, 400, 4);
        int[] ticks = {0};
        context.failIfEver(() -> {
            ticks[0]++;
            if (ticks[0] == 80) { // a fresh bot ignores damage until its (fake) client has loaded, about 60 ticks
                bot.hurtServer(world, world.damageSources().magic(), 2.0F);
            }
            if (ticks[0] < 100) {
                return;
            }
            List<String> lines = botLog(bot.getGameProfile().name());
            if (lines == null) {
                require(context, ticks[0] < 300, "the per-bot log is unavailable"); // never pass vacuously
                return;
            }
            String drop = lines.stream().filter(line -> line.contains("event=diag_health_drop")).findFirst().orElse(null);
            if (drop == null) {
                require(context, ticks[0] < 300, "no diag_health_drop line was logged");
                return;
            }
            require(context, drop.contains("magic"), "diag_health_drop does not name the damage source: " + drop);
            despawnAndComplete(context, bot);
        });
    }

    @GameTest(environment = "minecraftai-gametest:survival_reflex_game_tests_repeated_same_source_damage_is_logged_as_one_line_with_a_count", maxTicks = 450)
    public void repeatedSameSourceDamageIsLoggedAsOneLineWithACount(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        AIPlayerEntity bot = spawnOnPlatform(context, "DamageBurstGT", 20, 5, 440, 4);
        bot.setHealth(bot.getMaxHealth());
        int[] ticks = {0};
        context.failIfEver(() -> {
            ticks[0]++;
            if (ticks[0] >= 80 && ticks[0] < 86) { // after the ~60-tick post-spawn invulnerability
                bot.hurtServer(world, world.damageSources().magic(), 1.0F);
            }
            if (ticks[0] < 170) {
                return; // let the two-second window pass so the summary is flushed by the idle tick
            }
            List<String> lines = botLog(bot.getGameProfile().name());
            if (lines == null) {
                require(context, ticks[0] < 350, "the per-bot log is unavailable"); // never pass vacuously
                return;
            }
            long single = lines.stream().filter(line -> line.contains("event=damage_taken ")).count();
            long summaries = lines.stream().filter(line -> line.contains("event=damage_taken_repeated")).count();
            if (summaries < 1 && ticks[0] < 350) {
                return; // asynchronous per-bot log writer
            }
            require(context, single == 1, "expected one damage_taken line for six same-source hits, saw " + single);
            require(context, summaries == 1, "expected one damage_taken_repeated summary, saw " + summaries);
            String summary = lines.stream().filter(line -> line.contains("event=damage_taken_repeated")).findFirst().orElse("");
            require(context, summary.contains("repeats='5'"), "the summary does not count the five repeats: " + summary);
            despawnAndComplete(context, bot);
        });
    }

    /**
     * A fatal hit of the same kind as the run before it (fire/lava/magic) is reported with the death: die()
     * runs inside hurtServer, before that hit is recorded, so the run is flushed after recording when dead.
     */
    @GameTest(environment = "minecraftai-gametest:survival_reflex_game_tests_fatal_same_source_hit_is_logged_with_the_death", maxTicks = 500)
    public void fatalSameSourceHitIsLoggedWithTheDeath(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        AIPlayerEntity bot = spawnOnPlatform(context, "FatalCoalesceGT", 20, 5, 600, 4);
        int[] ticks = {0};
        context.failIfEver(() -> {
            ticks[0]++;
            if (ticks[0] == 80) { // after the ~60-tick post-spawn invulnerability
                bot.hurtServer(world, world.damageSources().magic(), 1.0F);
            }
            if (ticks[0] == 100) {
                bot.hurtServer(world, world.damageSources().magic(), 1.0F);
            }
            if (ticks[0] == 110) {
                bot.setHealth(1.0F);
                bot.invulnerableTime = 0;
                bot.hurtServer(world, world.damageSources().magic(), 50.0F);
            }
            if (ticks[0] == 112) {
                bot.hurtServer(world, world.damageSources().magic(), 1.0F); // a same-kind hit on the dead bot
            }
            if (ticks[0] < 115) {
                return;
            }
            List<String> lines = botLog(bot.getGameProfile().name());
            if (lines == null) {
                require(context, ticks[0] < 400, "the per-bot log is unavailable");
                return;
            }
            boolean died = lines.stream().anyMatch(line -> line.contains("event=bot_death"));
            boolean fatalLogged = lines.stream().anyMatch(line -> line.contains("event=damage_taken ") && line.contains("->0.0"));
            long singles = lines.stream().filter(line -> line.contains("event=damage_taken ")).count();
            // first hit, fatal hit and the hit on the dead bot are three single lines while the bot still exists:
            // a same-kind hit after death used to be folded into a run that only the removal flush reported.
            if (!died || !fatalLogged || singles < 3) {
                require(context, ticks[0] < 400, "bot_death logged=" + died + ", death-causing damage line logged="
                        + fatalLogged + ", single damage lines=" + singles + " (expected 3, before the bot was removed)");
                return;
            }
            despawnAndComplete(context, bot);
        });
    }

    // ---- fixtures ----

    private static final class HoldingTask extends AbstractTask {
        @Override
        public String name() {
            return "holding_work";
        }

        @Override
        public String describe() {
            return "Holding";
        }

        @Override
        public double progress() {
            return 0.5D;
        }

        @Override
        protected void onStart(AIPlayerEntity bot) {
        }

        @Override
        protected void onTick(AIPlayerEntity bot) {
        }
    }

    private static void buildFloor(ServerLevel world, BlockPos feet, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos cell = feet.offset(dx, 0, dz);
                world.setBlock(cell.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                for (int dy = 0; dy <= 6; dy++) {
                    world.setBlock(cell.above(dy), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
    }

    private static AIPlayerEntity spawnOnPlatform(GameTestHelper context, String name, int x, int y, int z, int radius) {
        BlockPos feet = context.absolutePos(new BlockPos(x, y, z));
        buildFloor(context.getLevel(), feet, radius);
        context.getLevel().setDayTime(1000L);
        return spawnAt(context, name, feet);
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

    private static void despawnAndComplete(GameTestHelper context, AIPlayerEntity... bots) {
        for (AIPlayerEntity bot : bots) {
            cleanUp(bot);
        }
        context.succeed();
    }
}
