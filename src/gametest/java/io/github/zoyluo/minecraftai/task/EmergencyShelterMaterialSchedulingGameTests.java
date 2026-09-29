package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.MaterialPalette;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.isSealed;
import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.preparePlatform;
import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.require;
import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.runLocked;
import static io.github.zoyluo.minecraftai.task.ShelterGameTestFixtures.shelterShell;

/**
 * Live regressions for emergency-only wood material and safety-task replacement boundaries.
 */
public final class EmergencyShelterMaterialSchedulingGameTests {
    @GameTest(environment = "minecraftai-gametest:emergency_shelter_material_scheduling_game_tests_mixed_wood_fallback_builds_holds_and_physically_exits_with_dirt_first", maxTicks = 16000)
    public void mixedWoodFallbackBuildsHoldsAndPhysicallyExitsWithDirtFirst(
            GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 4);
        AIPlayerEntity bot = spawn(context, "ShelterMixedWoodGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_PLANKS, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.BIRCH_LOG, 8));

        require(context, EmergencyShelterTask.hasMaterialsForCurrentPose(bot),
                "mixed dirt/planks/logs inventory did not satisfy exact shelter admission");
        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                        "gametest_shelter_mixed_wood_fallback"));

        int[] holdStartedElapsed = {-1};
        runLocked(context, () -> {
            context.getLevel().setDayTime(1000L);
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.fail(Component.nullToEmpty("mixed-wood shelter ended as "
                        + task.state() + ":" + task.failureReason() + " " + task.describe()));
                return;
            }
            if (holdStartedElapsed[0] < 0 && task.describe().contains("phase=HOLD")) {
                holdStartedElapsed[0] = task.elapsedTicks();
                List<BlockPos> ownedEnvelope = shelterOwnedEnvelope(feet);
                require(context, countBlock(context, ownedEnvelope, Blocks.DIRT) == 2,
                        "lower-value dirt was not spent before emergency wood");
                require(context, countBlock(context, ownedEnvelope, Blocks.OAK_PLANKS) == 2,
                        "planks were not used before raw logs in the mixed fallback");
                require(context, countBlock(context, ownedEnvelope, Blocks.BIRCH_LOG) == 6,
                        "raw logs did not complete the mixed emergency enclosure");
                require(context, shelterShell(feet).stream()
                                .allMatch(pos -> isSealed(context, pos)),
                        "mixed emergency enclosure entered HOLD with a physical opening");
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, holdStartedElapsed[0] >= 0
                            && task.elapsedTicks() - holdStartedElapsed[0] >= 100,
                    "mixed emergency enclosure skipped its bounded HOLD transaction");
            assertOwnedNorthExit(context, bot, feet);
            finish(context, bot, "ShelterMixedWoodGT");
        });
    }

    @GameTest(environment = "minecraftai-gametest:emergency_shelter_material_scheduling_game_tests_emergency_wood_never_authorizes_permanent_mining_barricade", maxTicks = 30)
    public void emergencyWoodNeverAuthorizesPermanentMiningBarricade(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 3);
        AIPlayerEntity bot = spawn(context, "BarricadeWoodGuardGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_PLANKS, 8));
        InventoryAction.giveItem(bot, new ItemStack(Items.BIRCH_LOG, 8));
        BlockPos gateFeet = feet.east();

        require(context, MaterialPalette.countEmergencyShelterBlocks(bot) == 16
                        && EmergencyShelterTask.hasMaterialsForCurrentPose(bot),
                "wood-only inventory was not admitted for its temporary emergency owner");
        require(context, MaterialPalette.countShelterBlocks(bot) == 0
                        && !MiningBarricadeTask.hasMaterialsForOpenGate(bot),
                "emergency-only wood leaked into the permanent barricade palette");

        MiningBarricadeTask barricade = new MiningBarricadeTask(feet, gateFeet);
        barricade.start(bot);
        require(context, barricade.state() == TaskState.FAILED
                        && "missing barricade_blocks required=2 available=0"
                        .equals(barricade.failureReason()),
                "wood-only barricade admission did not fail closed: "
                        + barricade.state() + ":" + barricade.failureReason());
        require(context, context.getLevel().getBlockState(gateFeet).isAir()
                        && context.getLevel().getBlockState(gateFeet.above()).isAir(),
                "failed wood-only barricade admission mutated its gate");
        require(context, InventoryAction.countItem(bot, Items.OAK_PLANKS) == 8
                        && InventoryAction.countItem(bot, Items.BIRCH_LOG) == 8,
                "failed permanent barricade admission consumed emergency wood");
        finish(context, bot, "BarricadeWoodGuardGT");
    }

    @GameTest(environment = "minecraftai-gametest:emergency_shelter_material_scheduling_game_tests_one_block_cannot_dispatch_doomed_shelter_or_grow_pause_stack", maxTicks = 80)
    public void oneBlockCannotDispatchDoomedShelterOrGrowPauseStack(GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 180, 4));
        prepareEscapeCorridor(context, feet);
        AIPlayerEntity bot = spawn(context, "ShelterOneBlockGateGT", feet);
        bot.setHealth(4.7F);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT));
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        HoldingTask mission = new HoldingTask("one_block_mission");
        TaskManager.INSTANCE.assign(bot, mission,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                        "gametest_shelter_one_block_admission"));
        Husk hostile = spawnDisabledHusk(context, feet.east(3));

        require(context, EmergencyShelterTask.hasShelterBlock(bot)
                        && !EmergencyShelterTask.hasMaterialsForCurrentPose(bot),
                "one-block fixture did not distinguish item presence from full admission");
        require(context, ObservableWorldQuery.canObserveEntity(bot, hostile)
                        && CombatCore.hasLineOfSight(bot, hostile),
                "one-block fixture hostile was not factually observable");

        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);
        Task firstSafety = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, firstSafety != null
                        && !(firstSafety instanceof EmergencyShelterTask),
                "one block dispatched a doomed emergency shelter");
        require(context, mission.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == mission
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "fallback safety did not preserve exactly one mission frame");
        require(context, TaskManager.INSTANCE.activeOrigin(bot)
                        .map(TaskOrigin::safety).orElse(false),
                "fallback hostile response was not owned by SAFETY");

        for (int attempt = 0; attempt < 8; attempt++) {
            DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);
            require(context, !(TaskManager.INSTANCE.getActive(bot).orElse(null)
                            instanceof EmergencyShelterTask),
                    "repeated one-block scan dispatched a doomed shelter");
            require(context, TaskManager.INSTANCE.pausedDepth(bot) == 1
                            && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == mission,
                    "repeated one-block scan grew or replaced the mission pause frame");
        }
        require(context, InventoryAction.countItem(bot, Items.DIRT) == 1,
                "failed exact shelter admission consumed its only block");
        hostile.discard();
        finish(context, bot, "ShelterOneBlockGateGT");
    }

    @GameTest(environment = "minecraftai-gametest:emergency_shelter_material_scheduling_game_tests_emergency_shelter_supersedes_safety_evade_without_nesting_pause_frame", maxTicks = 80)
    public void emergencyShelterSupersedesSafetyEvadeWithoutNestingPauseFrame(
            GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 220, 4));
        prepareEscapeCorridor(context, feet);
        AIPlayerEntity bot = spawn(context, "ShelterSafetySwapGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));
        HoldingTask mission = new HoldingTask("safety_swap_mission");
        TaskManager.INSTANCE.assign(bot, mission,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                        "gametest_shelter_safety_supersede"));
        EnderMan hostile = spawnProvokedEnderman(context, feet.east(8), bot);

        require(context, DangerWatcher.isActiveHostileThreat(bot, hostile)
                        && ObservableWorldQuery.canObserveEntity(bot, hostile)
                        && CombatCore.hasLineOfSight(bot, hostile),
                "safety-swap Enderman was not a factual active threat");
        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);

        Task firstSafety = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, firstSafety instanceof EvadeTask,
                "melee-forbidden hostile did not establish the active Evade fixture: "
                        + (firstSafety == null ? "idle" : firstSafety.name()));
        require(context, TaskManager.INSTANCE.activeOrigin(bot)
                        .map(TaskOrigin::safety).orElse(false),
                "Evade fixture was not assigned with SAFETY origin");
        require(context, mission.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == mission
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "Evade fixture did not preserve exactly one mission frame");

        bot.setHealth(4.7F);
        require(context, EmergencyShelterTask.hasMaterialsForCurrentPose(bot),
                "adequate shelter inventory failed exact current-pose admission");
        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);

        Task replacement = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, replacement instanceof EmergencyShelterTask,
                "low-health emergency did not supersede active SAFETY Evade: "
                        + (replacement == null ? "idle" : replacement.name()));
        require(context, firstSafety.state() == TaskState.FAILED
                        && "aborted".equals(firstSafety.failureReason()),
                "superseded Evade did not publish the manager's abort terminal: "
                        + firstSafety.state() + ":" + firstSafety.failureReason());
        require(context, TaskManager.INSTANCE.activeOrigin(bot)
                        .map(TaskOrigin::safety).orElse(false),
                "replacement shelter lost SAFETY origin");
        require(context, mission.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == mission
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "SAFETY-to-SAFETY supersede nested another pause frame");

        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);
        require(context, TaskManager.INSTANCE.getActive(bot).orElse(null) == replacement
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "active shelter scan replaced ownership or grew the pause stack");
        hostile.discard();
        finish(context, bot, "ShelterSafetySwapGT");
    }

    @GameTest(environment = "minecraftai-gametest:emergency_shelter_material_scheduling_game_tests_generic_threat_supersedes_non_defense_safety_without_nesting_mission", maxTicks = 80)
    public void genericThreatSupersedesNonDefenseSafetyWithoutNestingMission(
            GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 240, 4));
        prepareEscapeCorridor(context, feet);
        AIPlayerEntity bot = spawn(context, "ThreatSafetySwapGT", feet);
        HoldingTask mission = new HoldingTask("threat_swap_mission");
        TaskManager.INSTANCE.assign(bot, mission,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                        "gametest_generic_threat_safety_supersede"));
        TaskManager.INSTANCE.pauseFor(bot, "establish_mission_frame");
        HoldingTask safetyHolder = new HoldingTask("critical_hunt_holder");
        TaskManager.INSTANCE.assign(bot, safetyHolder,
                TaskOrigin.safety("critical_hunt_for_food"));
        EnderMan hostile = spawnProvokedEnderman(context, feet.east(8), bot);

        require(context, mission.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == mission
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "non-defense SAFETY fixture did not start over exactly one mission frame");
        require(context, !DangerWatcher.hasActiveHostileDefenseOwner(bot),
                "critical hunt SAFETY holder was misclassified as hostile defense");
        require(context, DangerWatcher.isActiveHostileThreat(bot, hostile)
                        && ObservableWorldQuery.canObserveEntity(bot, hostile)
                        && CombatCore.hasLineOfSight(bot, hostile),
                "generic replacement Enderman was not a factual active threat");

        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);

        Task replacement = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, replacement instanceof EvadeTask,
                "generic hostile response did not replace non-defense SAFETY with Evade: "
                        + (replacement == null ? "idle" : replacement.name()));
        require(context, safetyHolder.state() == TaskState.FAILED
                        && "aborted".equals(safetyHolder.failureReason()),
                "replaced non-defense SAFETY holder did not publish an abort terminal: "
                        + safetyHolder.state() + ":" + safetyHolder.failureReason());
        require(context, DangerWatcher.hasActiveHostileDefenseOwner(bot),
                "replacement Evade was not recognized as a hostile-defense owner");
        require(context, mission.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == mission
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "generic SAFETY-to-SAFETY replacement nested the non-defense holder");
        hostile.discard();
        finish(context, bot, "ThreatSafetySwapGT");
    }

    @GameTest(environment = "minecraftai-gametest:emergency_shelter_material_scheduling_game_tests_trapped_fight_back_replaces_non_defense_safety_without_nesting_mission", maxTicks = 16000)
    public void trappedFightBackReplacesNonDefenseSafetyWithoutNestingMission(
            GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 260, 4));
        prepareIsolatedTrap(context, feet);
        AIPlayerEntity bot = spawn(context, "TrappedFightBackSwapGT", feet);
        bot.setHealth(18.0F);
        HoldingTask mission = new HoldingTask("trapped_fight_back_mission");
        TaskManager.INSTANCE.assign(bot, mission,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                        "gametest_trapped_fight_back_safety_supersede"));
        Husk hostile = spawnDisabledHusk(context, feet.east(3));

        require(context, EvadeTask.admitBestSurfaceEscapePath(
                        bot, hostile, hostile.blockPosition(), 12) == null
                        && bot.getActionPack().isPathExecutorIdle(),
                "trapped fight-back fixture unexpectedly exposed an escape route");
        require(context, DangerWatcher.INSTANCE.scanBot(
                        context.getLevel().getServer(), bot),
                "first trapped fight-back scan was not handled");
        Task firstEvade = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, firstEvade instanceof EvadeTask,
                "first trapped response was not Evade: "
                        + (firstEvade == null ? "idle" : firstEvade.name()));
        require(context, mission.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == mission
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "first trapped Evade did not preserve exactly one mission frame");

        HoldingTask[] safetyHolder = {null};
        long[] holderAssignedTick = {-1L};
        runLocked(context, () -> {
            context.getLevel().setDayTime(1000L);
            require(context, bot.isAlive() && bot.blockPosition().equals(feet),
                    "trapped fight-back fixture moved or died before replacement");
            require(context, mission.state() == TaskState.PAUSED
                            && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == mission,
                    "trapped fight-back fixture lost its mission cursor");

            if (safetyHolder[0] == null
                    && firstEvade.state() == TaskState.FAILED
                    && TaskManager.INSTANCE.getActive(bot).isEmpty()) {
                require(context, "no_valid_escape_route"
                                .equals(firstEvade.failureReason()),
                        "first trapped Evade ended for the wrong reason: "
                                + firstEvade.failureReason());
                safetyHolder[0] = new HoldingTask("critical_hunt_holder");
                TaskManager.INSTANCE.assign(bot, safetyHolder[0],
                        TaskOrigin.safety("critical_hunt_for_food"));
                holderAssignedTick[0] = context.getTick();
                require(context, !DangerWatcher.hasActiveHostileDefenseOwner(bot),
                        "non-defense SAFETY holder was classified as trapped protection");
            }
            if (safetyHolder[0] == null) {
                return;
            }

            bot.hurtTime = 5;
            DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);
            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            if (active instanceof CombatTask) {
                require(context, safetyHolder[0].state() == TaskState.FAILED
                                && "aborted".equals(safetyHolder[0].failureReason()),
                        "trapped_fight_back did not replace its non-defense SAFETY holder");
                require(context, DangerWatcher.hasActiveHostileDefenseOwner(bot),
                        "trapped_fight_back Combat was not recognized as hostile defense");
                require(context, mission.state() == TaskState.PAUSED
                                && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == mission
                                && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                        "trapped_fight_back nested SAFETY over the mission frame");
                hostile.discard();
                finish(context, bot, "TrappedFightBackSwapGT");
                return;
            }
            require(context, TaskManager.INSTANCE.pausedDepth(bot) == 1,
                    "waiting for trapped_fight_back grew the mission pause stack");
            if (context.getTick() - holderAssignedTick[0] > 120) {
                context.fail(Component.nullToEmpty(
                        "trapped_fight_back never replaced non-defense SAFETY: active="
                                + (active == null ? "idle" : active.name())));
            }
        });
    }

    @GameTest(environment = "minecraftai-gametest:emergency_shelter_material_scheduling_game_tests_critical_creeper_without_route_or_materials_retains_one_safety_owner", maxTicks = 16000)
    public void criticalCreeperWithoutRouteOrMaterialsRetainsOneSafetyOwner(
            GameTestHelper context) {
        BlockPos feet = context.absolutePos(new BlockPos(4, 280, 4));
        prepareIsolatedTrap(context, feet);
        AIPlayerEntity bot = spawn(context, "CreeperBackoffOwnerGT", feet);
        bot.setHealth(4.7F);
        bot.getFoodData().setFoodLevel(17);
        bot.getFoodData().setSaturation(0.0F);
        HoldingTask mission = new HoldingTask("creeper_backoff_mission");
        TaskManager.INSTANCE.assign(bot, mission,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                        "gametest_critical_creeper_backoff"));
        Creeper hostile = spawnDisabledCreeper(context, feet.east(3));

        require(context, !EmergencyShelterTask.hasShelterBlock(bot)
                        && !EmergencyShelterTask.hasMaterialsForCurrentPose(bot),
                "critical backoff fixture unexpectedly carried shelter material");
        require(context, DangerWatcher.isActiveHostileThreat(bot, hostile)
                        && ObservableWorldQuery.canObserveEntity(bot, hostile)
                        && CombatCore.hasLineOfSight(bot, hostile),
                "critical backoff Creeper was not a factual HIGH hostile");
        require(context, EvadeTask.admitBestSurfaceEscapePath(
                        bot, hostile, hostile.blockPosition(), 12) == null
                        && bot.getActionPack().isPathExecutorIdle(),
                "isolated trap unexpectedly exposed an effective escape landing");

        require(context, DangerWatcher.INSTANCE.scanBot(
                        context.getLevel().getServer(), bot),
                "first critical Creeper scan was not handled");
        Task first = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, first instanceof CreeperDefenseTask,
                "first critical Creeper response was not the dedicated owner: "
                        + (first == null ? "idle" : first.name()));
        require(context, mission.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == mission
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "first Creeper defense did not preserve exactly one mission frame");

        long started = context.getTick();
        runLocked(context, () -> {
            context.getLevel().setDayTime(1000L);
            bot.setHealth(4.7F);
            bot.getFoodData().setFoodLevel(17);
            require(context, bot.isAlive() && bot.blockPosition().equals(feet),
                    "critical backoff fixture moved or died before the fourth decision");
            require(context, mission.state() == TaskState.PAUSED
                            && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == mission
                            && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                    "critical backoff resumed, duplicated or replaced the mission frame");
            require(context, InventoryAction.countItem(bot, Items.DIRT) == 0
                            && !EmergencyShelterTask.hasShelterBlock(bot),
                    "critical backoff acquired or invented shelter material");

            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            require(context, active == first
                            && active instanceof CreeperDefenseTask
                            && active.state() == TaskState.RUNNING,
                    "route/material failure churned or released the Creeper owner: "
                            + (active == null ? "idle" : active.name() + "/" + active.state()));
            require(context, TaskManager.INSTANCE.activeOrigin(bot)
                            .map(TaskOrigin::safety).orElse(false),
                    "critical Creeper defense lost SAFETY ownership");

            if (context.getTick() - started >= 160) {
                hostile.discard();
                finish(context, bot, "CreeperBackoffOwnerGT");
            }
        });
    }

    private static List<BlockPos> shelterOwnedEnvelope(BlockPos feet) {
        List<BlockPos> result = new ArrayList<>(10);
        result.addAll(shelterShell(feet));
        result.add(feet.above(2).north());
        return List.copyOf(result);
    }

    private static long countBlock(GameTestHelper context,
                                   List<BlockPos> positions,
                                   Block block) {
        return positions.stream()
                .filter(pos -> context.getLevel().getBlockState(pos).is(block))
                .count();
    }

    private static void assertOwnedNorthExit(GameTestHelper context,
                                             AIPlayerEntity bot,
                                             BlockPos shelterFeet) {
        BlockPos exit = shelterFeet.north();
        require(context, bot.blockPosition().equals(exit),
                "shelter did not physically step through its planned north exit: "
                        + shelterFeet.toShortString() + " -> "
                        + bot.blockPosition().toShortString());
        require(context, Standability.isStandable(context.getLevel(), exit),
                "opened shelter exit was not a standable landing");
        require(context, context.getLevel().getBlockState(exit).isAir()
                        && context.getLevel().getBlockState(exit.above()).isAir(),
                "shelter completed without reopening its owned two-block north doorway");
    }

    private static void prepareEscapeCorridor(GameTestHelper context, BlockPos feet) {
        context.getLevel().setDayTime(18000L);
        for (int dx = -24; dx <= 12; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos cell = feet.offset(dx, 0, dz);
                context.getLevel().setBlock(
                        cell.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                context.getLevel().setBlock(
                        cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                context.getLevel().setBlock(
                        cell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                context.getLevel().setBlock(
                        cell.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    private static void prepareIsolatedTrap(GameTestHelper context, BlockPos feet) {
        context.getLevel().setDayTime(1000L);
        for (int dx = -18; dx <= 18; dx++) {
            for (int dz = -18; dz <= 18; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    context.getLevel().setBlock(
                            feet.offset(dx, dy, dz),
                            Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        context.getLevel().setBlock(
                feet.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(
                feet.east(3).below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
    }

    private static AIPlayerEntity spawn(GameTestHelper context, String name, BlockPos feet) {
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        context.getLevel().getServer(), name, context.getLevel(),
                        Vec3.atBottomCenterOf(feet), 0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(context.getLevel(),
                feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        bot.getFoodData().setSaturation(5.0F);
        return bot;
    }

    private static Husk spawnDisabledHusk(GameTestHelper context, BlockPos feet) {
        Husk hostile = EntityType.HUSK.create(
                context.getLevel(), EntitySpawnReason.COMMAND);
        if (hostile == null) {
            throw new IllegalStateException("failed to create one-block shelter hostile");
        }
        hostile.setPersistenceRequired();
        hostile.setNoAi(true);
        hostile.snapTo(
                feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                90.0F, 0.0F);
        require(context, context.getLevel().addFreshEntity(hostile),
                "failed to spawn one-block shelter hostile");
        return hostile;
    }

    private static EnderMan spawnProvokedEnderman(GameTestHelper context,
                                                         BlockPos feet,
                                                         AIPlayerEntity target) {
        EnderMan hostile = EntityType.ENDERMAN.create(
                context.getLevel(), EntitySpawnReason.COMMAND);
        if (hostile == null) {
            throw new IllegalStateException("failed to create safety-swap Enderman");
        }
        hostile.setPersistenceRequired();
        hostile.setNoAi(true);
        hostile.snapTo(
                feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                90.0F, 0.0F);
        hostile.setPersistentAngerEndTime(hostile.level().getGameTime() + 600L);
        hostile.setPersistentAngerTarget(EntityReference.of(target.getUUID()));
        hostile.setTarget(target);
        require(context, context.getLevel().addFreshEntity(hostile),
                "failed to spawn safety-swap Enderman");
        return hostile;
    }

    private static Creeper spawnDisabledCreeper(GameTestHelper context, BlockPos feet) {
        Creeper hostile = EntityType.CREEPER.create(
                context.getLevel(), EntitySpawnReason.COMMAND);
        if (hostile == null) {
            throw new IllegalStateException("failed to create critical backoff Creeper");
        }
        hostile.setPersistenceRequired();
        hostile.setNoAi(true);
        hostile.snapTo(
                feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                90.0F, 0.0F);
        require(context, context.getLevel().addFreshEntity(hostile),
                "failed to spawn critical backoff Creeper");
        return hostile;
    }

    private static void finish(GameTestHelper context, AIPlayerEntity bot, String name) {
        DangerWatcher.INSTANCE.clear(bot);
        TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_complete");
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
        context.succeed();
    }

    private static final class HoldingTask extends AbstractTask {
        private final String taskName;

        private HoldingTask(String taskName) {
            this.taskName = taskName;
        }

        @Override
        public String name() {
            return taskName;
        }

        @Override
        public String describe() {
            return "Holding a resumable mission cursor";
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
}
