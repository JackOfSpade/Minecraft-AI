package io.github.zoyluo.minecraftai.task;

import java.util.List;
import io.github.zoyluo.minecraftai.gametest.PerceptionFixtures;
import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.MaterialPalette;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mode.CapabilityRuntime;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.util.Set;

/**
 * Black-box safety contracts for the dedicated Creeper owner.
 *
 * <p>These tests intentionally use real survival inventory, entity observation, movement and
 * block placement. Task descriptions expose only the public diagnostic state needed to distinguish
 * ESCAPE, BUILD_WALL and HOLD_BARRIER; no test-only production hook is required.</p>
 */
public final class CreeperDefenseGameTests {
    @GameTest(environment = "minecraftai-gametest:creeper_defense_game_tests_late_fuse_assignment_starts_physical_defense_synchronously", maxTicks = 40 + PerceptionFixtures.MAX_WAIT_TICKS)
    public void lateFuseAssignmentStartsPhysicalDefenseSynchronously(GameTestHelper context) {
        AIPlayerEntity bot = spawnArenaBot(context, "CreeperLateFuseGT", 200);
        BlockPos origin = bot.blockPosition().immutable();
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_LOG, 8));
        assertStrictCapabilities(context, bot);

        HoldingTask mission = new HoldingTask("late_fuse_mission");
        TaskManager.INSTANCE.assign(bot, mission,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_late_fuse_mission"));
        Creeper creeper = spawnDisabledCreeper(context, origin.east(2));
        PerceptionFixtures.faceToward(bot, creeper);
        PerceptionFixtures.afterNoticedFresh(context, bot, List.of(creeper), since -> {
        creeper.ignite();
        creeper.setSwellDir(1);
        for (int tick = 0; tick < 20; tick++) {
            creeper.tick();
        }
        require(context, creeper.isAlive()
                        && creeper.isIgnited()
                        && creeper.getSwellDir() > 0
                        && creeper.getSwelling(1.0F) > 0.0F,
                "late-fuse fixture did not retain a live finite explosion clock");

        Vec3 before = bot.position();
        int materialBefore = MaterialPalette.countEmergencyShelterBlocks(bot);
        require(context, DangerWatcher.INSTANCE.scanBot(
                        context.getLevel().getServer(), bot),
                "late-fuse Creeper scan was not handled");

        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active instanceof CreeperDefenseTask
                        && TaskManager.INSTANCE.activeOrigin(bot)
                        .map(TaskOrigin::safety).orElse(false),
                "late-fuse scan did not assign the dedicated SAFETY owner");
        require(context, mission.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == mission
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "late-fuse scan did not preserve exactly one mission frame");

        boolean physicallyStepped = bot.position().distanceToSqr(before) >= 0.80D;
        boolean physicallyPlacedCore = MaterialPalette.countEmergencyShelterBlocks(bot)
                < materialBefore && hasPlacedOakLogNear(bot, origin);
        require(context, physicallyStepped || physicallyPlacedCore,
                "late-fuse onStart only scheduled a future path; no synchronous physical"
                        + " fast-step or core-wall action occurred: " + active.describe());
        finish(context, bot, "CreeperLateFuseGT", creeper);
        });
    }

    @GameTest(environment = "minecraftai-gametest:creeper_defense_game_tests_hidden_near_memory_is_not_overwritten_by_far_unarmed_creeper", maxTicks = 40 + PerceptionFixtures.MAX_WAIT_TICKS)
    public void hiddenNearMemoryIsNotOverwrittenByFarUnarmedCreeper(GameTestHelper context) {
        AIPlayerEntity bot = spawnArenaBot(context, "CreeperMemoryGT", 206);
        BlockPos origin = bot.blockPosition().immutable();
        assertStrictCapabilities(context, bot);

        HoldingTask mission = new HoldingTask("creeper_memory_mission");
        TaskManager.INSTANCE.assign(bot, mission,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_creeper_memory"));
        Creeper near = spawnDisabledCreeper(context, origin.east(3));
        PerceptionFixtures.faceToward(bot, near);
        PerceptionFixtures.afterNoticedFresh(context, bot, List.of(near), since -> {
        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);
        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active instanceof CreeperDefenseTask,
                "near Creeper did not assign the dedicated owner");
        String nearSource = compact(near.blockPosition());
        require(context, active.describe().contains("source=" + nearSource),
                "owner did not bind the initially observed near source: " + active.describe());

        bot.getActionPack().stopAll();
        bot.teleportTo(context.getLevel(), origin.getX() + 0.5D, origin.getY(),
                origin.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, true);
        bot.setDeltaMovement(Vec3.ZERO);
        buildOccludingWall(context, origin.east(), 1);
        require(context, !ObservableWorldQuery.canObserveEntity(bot, near),
                "near Creeper remained observable through the memory occluder");
        Creeper far = spawnDisabledCreeper(context, origin.west(8));
        require(context, ObservableWorldQuery.canObserveEntity(bot, far)
                        && !far.isIgnited() && far.getSwellDir() <= 0,
                "far replacement fixture was not a visible unarmed Creeper");

        // The far creeper must be NOTICED for this to prove anything. The remembered source's distance is part of the premise (a bot that
        // walked on would change which creeper is nearer), so the bot stays where the fixture put it and looks at the far creeper.
        Vec3 pinned = bot.position();
        PerceptionFixtures.everyTick(context, () -> {
            bot.teleportTo(context.getLevel(), pinned.x, pinned.y, pinned.z, Set.of(), bot.getYRot(), bot.getXRot(), true);
            bot.setDeltaMovement(Vec3.ZERO);
            PerceptionFixtures.faceToward(bot, far);
        });
        PerceptionFixtures.afterNoticed(context, bot, List.of(far), since2 -> {
        active.tick(bot);
        require(context, active.describe().contains("source=" + nearSource),
                "visible unarmed far Creeper overwrote the more dangerous hidden near memory:"
                        + " near=" + nearSource + " far=" + compact(far.blockPosition())
                        + " owner=" + active.describe());
        finish(context, bot, "CreeperMemoryGT", near, far);
        });
        });
    }

    @GameTest(environment = "minecraftai-gametest:creeper_defense_game_tests_older_occluded_risk_cannot_complete_while_second_risk_just_turned_hidden", maxTicks = 40 + PerceptionFixtures.MAX_WAIT_TICKS)
    public void olderOccludedRiskCannotCompleteWhileSecondRiskJustTurnedHidden(
            GameTestHelper context) {
        AIPlayerEntity bot = spawnArenaBot(context, "CreeperAllRiskGraceGT", 236);
        BlockPos origin = bot.blockPosition().immutable();
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 2));
        assertStrictCapabilities(context, bot);

        Creeper older = spawnDisabledCreeper(context, origin.east(3));
        PerceptionFixtures.faceToward(bot, older);
        PerceptionFixtures.afterNoticedFresh(context, bot, List.of(older), since -> {
        older.ignite();
        older.setSwellDir(1);
        for (int tick = 0; tick < 20; tick++) {
            older.tick();
        }
        CreeperDefenseTask owner = new CreeperDefenseTask(
                older.getUUID(), older.blockPosition());
        TaskManager.INSTANCE.assign(bot, owner,
                TaskOrigin.safety("gametest_all_risk_grace"));
        require(context, phase(owner, "HOLD_BARRIER"),
                "late older risk did not synchronously establish its owned core: "
                        + owner.describe());
        older.discard();

        // The second risk appears now, standing in the bot's view; the owner keeps running while the bot notices it (the reaction time of
        // the shared formula), and the manual ticks below then bring the owner to its 98th tick, the timeline this test always had.
        Creeper newer = spawnDisabledCreeper(context, origin.west(5));
        PerceptionFixtures.faceToward(bot, newer);
        PerceptionFixtures.afterNoticedWithin(context, bot, List.of(newer), 100.0D, since2 -> {
        while (owner.elapsedTicks() < 98) {
            owner.tick(bot);
        }
        require(context, owner.state() == TaskState.RUNNING,
                "older risk completed before its own observation grace");

        require(context, ObservableWorldQuery.canObserveEntity(bot, newer),
                "second-risk fixture was not factually observable");
        owner.tick(bot);
        newer.discard();
        owner.tick(bot);

        require(context, owner.elapsedTicks() == 100
                        && owner.state() == TaskState.RUNNING
                        && phase(owner, "HOLD_BARRIER"),
                "owner used the older wall/grace to release a second risk hidden for one tick: "
                        + owner.describe() + " state=" + owner.state());

        BlockPos allRiskClearance = origin.south(12);
        makeStandable(context, allRiskClearance);
        for (int tick = 0; tick < 100 && owner.state() == TaskState.RUNNING; tick++) {
            bot.getActionPack().stopAll();
            bot.teleportTo(context.getLevel(),
                    allRiskClearance.getX() + 0.5D,
                    allRiskClearance.getY(),
                    allRiskClearance.getZ() + 0.5D,
                    Set.of(), 0.0F, 0.0F, true);
            bot.setDeltaMovement(Vec3.ZERO);
            owner.tick(bot);
        }
        require(context, owner.state() == TaskState.COMPLETED,
                "all-risk grace fix retained ownership after every remembered source had"
                        + " independently reached grace and physical clearance: "
                        + owner.describe() + " state=" + owner.state());
        finish(context, bot, "CreeperAllRiskGraceGT");
        });
        });
    }

    @GameTest(environment = "minecraftai-gametest:creeper_defense_game_tests_lateral_oscillation_cannot_reset_away_progress", maxTicks = 60)
    public void lateralOscillationCannotResetAwayProgress(GameTestHelper context) {
        AIPlayerEntity bot = spawnArenaBot(context, "CreeperLateralStallGT", 212);
        BlockPos origin = bot.blockPosition().immutable();
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_LOG, 6));
        assertStrictCapabilities(context, bot);
        Creeper creeper = spawnDisabledCreeper(context, origin.east(6));
        CreeperDefenseTask owner = new CreeperDefenseTask(
                creeper.getUUID(), creeper.blockPosition());
        TaskManager.INSTANCE.assign(bot, owner,
                TaskOrigin.safety("gametest_lateral_stall"));

        for (int tick = 0; tick < 8 && phase(owner, "ESCAPE"); tick++) {
            double lateral = tick % 2 == 0 ? 0.10D : 0.90D;
            bot.teleportTo(context.getLevel(), origin.getX() + 0.5D, origin.getY(),
                    origin.getZ() + lateral, Set.of(), 0.0F, 0.0F, true);
            bot.setDeltaMovement(Vec3.ZERO);
            owner.tick(bot);
        }

        require(context, !phase(owner, "ESCAPE") && owner.elapsedTicks() <= 6,
                "north/south oscillation reset away-progress and suppressed the five-tick"
                        + " core-wall escalation: " + owner.describe());
        // The escalation now starts a walked step (a real walk of several ticks) before the wall;
        // the step and the wall are proven over real ticks, not synchronous owner ticks.
        BlockPos center = origin;
        context.failIfEver(() -> {
            if (isPhysicalBarrierCell(bot, center) && isPhysicalBarrierCell(bot, center.above())) {
                require(context, origin.getX() + 0.5D - bot.getX() >= 0.5D,
                        "the wall stands but the bot never backed away from it: x=" + bot.getX());
                finish(context, bot, "CreeperLateralStallGT", creeper);
            } else if (context.getTick() >= 40) {
                context.fail(Component.nullToEmpty(
                        "lateral-stall escalation did not physically complete its central two-high wall:"
                                + " bot=" + bot.blockPosition().toShortString()
                                + " owner=" + owner.describe()));
            }
        });
    }

    @GameTest(environment = "minecraftai-gametest:creeper_defense_game_tests_two_legal_blocks_complete_core_and_hold_without_side_material", maxTicks = 60 + PerceptionFixtures.MAX_WAIT_TICKS)
    public void twoLegalBlocksCompleteCoreAndHoldWithoutSideMaterial(GameTestHelper context) {
        AIPlayerEntity bot = spawnArenaBot(context, "CreeperTwoBlockCoreGT", 218);
        BlockPos origin = bot.blockPosition().immutable();
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 2));
        assertStrictCapabilities(context, bot);
        Creeper creeper = spawnDisabledCreeper(context, origin.east(3));
        PerceptionFixtures.faceToward(bot, creeper);
        PerceptionFixtures.afterNoticedFresh(context, bot, List.of(creeper), since -> {
        creeper.ignite();
        creeper.setSwellDir(1);
        // A fuse already past the late threshold leaves no time for a walked step, so the two-high
        // wall goes up where the bot stands, one cell toward the Creeper (the young-fuse walked
        // step has its own test).
        for (int tick = 0; tick < 15; tick++) {
            creeper.tick();
        }
        BlockPos wall = origin.east();
        CreeperDefenseTask owner = new CreeperDefenseTask(
                creeper.getUUID(), creeper.blockPosition());
        TaskManager.INSTANCE.assign(bot, owner,
                TaskOrigin.safety("gametest_two_block_core"));

        owner.tick(bot);
        owner.tick(bot);
        require(context, isPhysicalBarrierCell(bot, wall)
                        && isPhysicalBarrierCell(bot, wall.above()),
                "two legal blocks did not become the central two-high physical wall: "
                        + owner.describe());
        require(context, MaterialPalette.countEmergencyShelterBlocks(bot) == 0,
                "two-block fixture retained unexpected side-wall material");

        creeper.discard();
        owner.tick(bot);
        owner.tick(bot);
        require(context, owner.state() == TaskState.RUNNING
                        && phase(owner, "HOLD_BARRIER"),
                "missing optional side material discarded a proven central wall instead of"
                        + " retaining HOLD_BARRIER ownership: " + owner.describe()
                        + " state=" + owner.state() + ":" + owner.failureReason());
        require(context, isPhysicalBarrierCell(bot, wall)
                        && isPhysicalBarrierCell(bot, wall.above()),
                "central wall was not maintained during the hidden-pressure hold");
        finish(context, bot, "CreeperTwoBlockCoreGT");
        });
    }

    /**
     * A lit Creeper still early in its fuse: the bot backs one block away from it before it walls up,
     * and that step is a real walk by movement inputs, not a teleport. No tick may carry the bot a
     * block, the step must take several ticks, and the two-high wall must still stand in the cell
     * the bot left (proof that the step really happened) before the fuse ends.
     */
    @GameTest(environment = "minecraftai-gametest:creeper_defense_game_tests_walked_step_away_uses_inputs_instead_of_teleporting", maxTicks = 60)
    public void walkedStepAwayUsesInputsInsteadOfTeleporting(GameTestHelper context) {
        AIPlayerEntity bot = spawnArenaBot(context, "CreeperWalkStepGT", 242);
        BlockPos origin = bot.blockPosition().immutable();
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_LOG, 8));
        assertStrictCapabilities(context, bot);
        Creeper creeper = spawnDisabledCreeper(context, origin.east(3));
        creeper.ignite();
        Vec3[] previous = {bot.position()};
        CreeperDefenseTask owner = new CreeperDefenseTask(creeper.getUUID(), creeper.blockPosition());
        TaskManager.INSTANCE.assign(bot, owner, TaskOrigin.safety("gametest_walked_step_away"));
        int[] firstMovedTick = {-1};
        context.failIfEver(() -> {
            Vec3 now = bot.position();
            double step = Math.hypot(now.x - previous[0].x, now.z - previous[0].z);
            previous[0] = now;
            require(context, step < 0.6D,
                    "the creeper step moved the bot " + step + " blocks in one tick (a teleport, not a walk): "
                            + owner.describe());
            if (firstMovedTick[0] < 0 && step > 0.0D) {
                firstMovedTick[0] = (int) context.getTick();
            }
            boolean walled = isPhysicalBarrierCell(bot, origin)
                    && isPhysicalBarrierCell(bot, origin.above());
            if (walled) {
                require(context, origin.getX() + 0.5D - bot.getX() >= 0.5D,
                        "the wall stands but the bot never backed away from it: x=" + bot.getX());
                require(context, context.getTick() >= 3,
                        "the step and the wall were both finished within " + context.getTick() + " ticks");
                require(context, bot.isAlive() && bot.getHealth() >= 19.0F, "the bot was hurt behind its wall");
                finish(context, bot, "CreeperWalkStepGT", creeper);
            } else if (context.getTick() >= 26) {
                context.fail(Component.nullToEmpty("no wall behind a walked step: " + owner.describe()
                        + " bot=" + bot.blockPosition().toShortString() + " x=" + bot.getX()));
            }
        });
    }

    @GameTest(environment = "minecraftai-gametest:creeper_defense_game_tests_hidden_creeper_memory_yields_to_non_creeper_low_hp_shelter", maxTicks = 60 + PerceptionFixtures.MAX_WAIT_TICKS)
    public void hiddenCreeperMemoryYieldsToNonCreeperLowHpShelter(GameTestHelper context) {
        AIPlayerEntity bot = spawnArenaBot(context, "CreeperShelterHandoffGT", 224);
        BlockPos origin = bot.blockPosition().immutable();
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_LOG, 32));
        assertStrictCapabilities(context, bot);

        HoldingTask mission = new HoldingTask("creeper_shelter_handoff_mission");
        TaskManager.INSTANCE.assign(bot, mission,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_creeper_shelter_handoff"));
        // Five blocks is still factual Creeper pressure, but stays outside the synchronous
        // four-block core-wall boundary so this fixture tests scheduler handoff rather than
        // deliberately teleporting back into an owned placement cell.
        Creeper creeper = spawnDisabledCreeper(context, origin.east(5));
        PerceptionFixtures.faceToward(bot, creeper);
        PerceptionFixtures.afterNoticedFresh(context, bot, List.of(creeper), since -> {
        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);
        Task creeperOwner = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, creeperOwner instanceof CreeperDefenseTask
                        && mission.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == mission
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "fixture did not establish one Creeper SAFETY owner over one mission frame");

        bot.getActionPack().stopAll();
        bot.teleportTo(context.getLevel(), origin.getX() + 0.5D, origin.getY(),
                origin.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, true);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.fallDistance = 0.0F;
        bot.setOnGround(true);
        buildOccludingWall(context, origin.east(2), 1);
        require(context, !ObservableWorldQuery.canObserveEntity(bot, creeper),
                "Creeper was not hidden while its owner retained last-seen memory");

        Zombie zombie = spawnDisabledZombie(context, origin.west(3));
        // The zombie strikes the bot (a real blow: the striker is known at once, the bot busy with its creeper memory has no time to turn
        // and look); the bot is low on health afterwards.
        if (!bot.connection.hasClientLoaded()) {
            bot.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
        }
        require(context, bot.hurtServer(context.getLevel(), context.getLevel().damageSources().mobAttack(zombie), 0.5F),
                "the zombie's blow on the bot was not real");
        bot.setHealth(4.7F);
        bot.getFoodData().setFoodLevel(17);
        require(context, ObservableWorldQuery.canObserveEntity(bot, zombie)
                        && CombatCore.hasLineOfSight(bot, zombie),
                "non-Creeper LOW_HP fixture was not factual visible pressure");
        require(context, EmergencyShelterTask.hasMaterialsForCurrentPose(bot),
                "handoff fixture could not admit a physical emergency shelter");

        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);
        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active instanceof EmergencyShelterTask
                        && TaskManager.INSTANCE.activeOrigin(bot)
                        .map(TaskOrigin::safety).orElse(false),
                "hidden Creeper memory blocked a live non-Creeper LOW_HP shelter handoff: "
                        + (active == null ? "idle" : active.name() + "/" + active.describe()));
        require(context, mission.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == mission
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "SAFETY-to-SAFETY shelter handoff duplicated or replaced the mission frame");
        finish(context, bot, "CreeperShelterHandoffGT", creeper, zombie);
        });
    }

    @GameTest(environment = "minecraftai-gametest:creeper_defense_game_tests_non_safety_eat_is_paused_and_resumed_as_exact_instance", maxTicks = 180 + PerceptionFixtures.MAX_WAIT_TICKS)
    public void nonSafetyEatIsPausedAndResumedAsExactInstance(GameTestHelper context) {
        AIPlayerEntity bot = spawnArenaBot(context, "CreeperEatResumeGT", 230);
        BlockPos origin = bot.blockPosition().immutable();
        InventoryAction.giveItem(bot, new ItemStack(Items.COOKED_BEEF, 2));
        bot.getFoodData().setFoodLevel(17);
        assertStrictCapabilities(context, bot);

        EatTask eat = new EatTask();
        TaskManager.INSTANCE.assign(bot, eat,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_non_safety_eat"));
        Creeper creeper = spawnDisabledCreeper(context, origin.east(4));
        PerceptionFixtures.faceToward(bot, creeper);
        PerceptionFixtures.afterNoticedFresh(context, bot, List.of(creeper), since -> {
        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);

        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active instanceof CreeperDefenseTask,
                "Creeper did not replace the non-SAFETY EatTask with its dedicated owner");
        require(context, eat.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == eat
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "non-SAFETY EatTask was aborted or copied instead of pausing its exact instance:"
                        + " eat=" + eat.state() + " depth="
                        + TaskManager.INSTANCE.pausedDepth(bot));

        CreeperDefenseTask owner = (CreeperDefenseTask) active;
        creeper.discard();
        BlockPos clearance = origin.west(12);
        makeStandable(context, clearance);
        bot.getActionPack().stopAll();
        bot.teleportTo(context.getLevel(), clearance.getX() + 0.5D, clearance.getY(),
                clearance.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, true);
        bot.setDeltaMovement(Vec3.ZERO);
        for (int tick = 0; tick < 105 && owner.state() == TaskState.RUNNING; tick++) {
            owner.tick(bot);
        }
        require(context, owner.state() == TaskState.COMPLETED,
                "cleared Creeper owner did not settle after its bounded hidden-source grace: "
                        + owner.describe() + " state=" + owner.state());
        TaskManager.INSTANCE.tickAll(context.getLevel().getServer());
        require(context, TaskManager.INSTANCE.getActive(bot).isEmpty()
                        && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == eat
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "completed Creeper owner did not expose the exact paused EatTask");

        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        bot.hurtTime = 0;
        bot.getActionPack().stopAll();
        DangerWatcher.INSTANCE.scanBot(context.getLevel().getServer(), bot);
        require(context, TaskManager.INSTANCE.getActive(bot).orElse(null) == eat
                        && eat.state() == TaskState.RUNNING
                        && TaskManager.INSTANCE.pausedDepth(bot) == 0,
                "clear pressure did not resume the exact non-SAFETY EatTask instance: "
                        + " active=" + TaskManager.INSTANCE.getActive(bot)
                        .map(Task::name).orElse("idle")
                        + " eat=" + eat.state()
                        + " depth=" + TaskManager.INSTANCE.pausedDepth(bot));
        require(context, TaskManager.INSTANCE.activeOrigin(bot)
                        .map(originValue -> originValue.kind() == TaskOrigin.Kind.VERIFY)
                        .orElse(false),
                "resumed EatTask lost its original non-SAFETY origin");
        finish(context, bot, "CreeperEatResumeGT");
        });
    }

    private static AIPlayerEntity spawnArenaBot(GameTestHelper context,
                                                 String name,
                                                 int relativeY) {
        var world = context.getLevel();
        world.setDayTime(1000L);
        BlockPos feet = context.absolutePos(new BlockPos(4, relativeY, 4));
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                BlockPos cell = feet.offset(dx, 0, dz);
                world.setBlock(
                        cell.below(), Blocks.OBSIDIAN.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                world.setBlock(cell.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3.atBottomCenterOf(feet),
                        0.0F, 0.0F, GameType.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleportTo(world, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setDeltaMovement(Vec3.ZERO);
        bot.setHealth(bot.getMaxHealth());
        bot.getFoodData().setFoodLevel(20);
        bot.getFoodData().setSaturation(5.0F);
        return bot;
    }

    private static Creeper spawnDisabledCreeper(GameTestHelper context, BlockPos feet) {
        Creeper creeper = EntityType.CREEPER.create(
                context.getLevel(), EntitySpawnReason.COMMAND);
        if (creeper == null) {
            throw new IllegalStateException("failed to create Creeper fixture");
        }
        makeStandable(context, feet);
        creeper.setPersistenceRequired();
        creeper.setNoAi(true);
        creeper.snapTo(
                feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 90.0F, 0.0F);
        require(context, context.getLevel().addFreshEntity(creeper),
                "failed to spawn Creeper fixture");
        return creeper;
    }

    private static Zombie spawnDisabledZombie(GameTestHelper context, BlockPos feet) {
        Zombie zombie = EntityType.ZOMBIE.create(
                context.getLevel(), EntitySpawnReason.COMMAND);
        if (zombie == null) {
            throw new IllegalStateException("failed to create Zombie fixture");
        }
        makeStandable(context, feet);
        zombie.setPersistenceRequired();
        zombie.setNoAi(true);
        zombie.snapTo(
                feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 90.0F, 0.0F);
        require(context, context.getLevel().addFreshEntity(zombie),
                "failed to spawn Zombie fixture");
        return zombie;
    }

    private static void makeStandable(GameTestHelper context, BlockPos feet) {
        context.getLevel().setBlock(
                feet.below(), Blocks.OBSIDIAN.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(
                feet, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(
                feet.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        context.getLevel().setBlock(
                feet.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    private static void buildOccludingWall(GameTestHelper context,
                                            BlockPos center,
                                            int halfWidth) {
        for (int dz = -halfWidth; dz <= halfWidth; dz++) {
            for (int dy = 0; dy <= 2; dy++) {
                context.getLevel().setBlock(
                        center.offset(0, dy, dz),
                        Blocks.OBSIDIAN.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    private static boolean hasPlacedOakLogNear(AIPlayerEntity bot, BlockPos origin) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    if (bot.level().getBlockState(
                            origin.offset(dx, dy, dz)).is(Blocks.OAK_LOG)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isPhysicalBarrierCell(AIPlayerEntity bot, BlockPos pos) {
        var state = bot.level().getBlockState(pos);
        return !state.getCollisionShape(bot.level(), pos).isEmpty();
    }

    private static boolean phase(Task task, String expected) {
        return task.describe().contains("phase=" + expected);
    }

    private static String compact(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static void assertStrictCapabilities(GameTestHelper context, AIPlayerEntity bot) {
        require(context, MinecraftAiConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                "GameTest must run under strict_survival, got " + MinecraftAiConfig.get().profile());
        for (PrivilegedCapability capability : PrivilegedCapability.values()) {
            require(context, !CapabilityRuntime.decide(
                            bot, capability, "creeper_defense_gametest").allowed(),
                    "strict_survival unexpectedly allowed " + capability);
        }
    }

    private static void finish(GameTestHelper context,
                               AIPlayerEntity bot,
                               String name,
                               Entity... entities) {
        for (Entity entity : entities) {
            if (entity != null && !entity.isRemoved()) {
                entity.discard();
            }
        }
        DangerWatcher.INSTANCE.clear(bot);
        TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_complete");
        AIPlayerManager.INSTANCE.despawn(bot.level().getServer(), name);
        context.succeed();
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            context.fail(Component.nullToEmpty(message));
        }
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
            return "Holding exact resumable mission cursor";
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
