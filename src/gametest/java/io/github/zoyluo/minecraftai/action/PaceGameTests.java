package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.gametest.BotFixtureMoves;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.navigation.NavEngine;
import io.github.zoyluo.minecraftai.navigation.NavEngineSelector;
import io.github.zoyluo.minecraftai.task.CombatCore;
import java.lang.reflect.Field;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The pace policy on the legacy engine: a long path sprints and walks in as it nears the goal, the vanilla rules (food, walls,
 * items in use) hold, jumps and drops are never sprinted, leases and quiet zones decide the gait, and running costs food.
 *
 * <p>Every test has its own world layer (the courses are wider than a test structure and all tests of a batch run at once) and
 * scopes anything global (the pressure probe) to its own bot.</p>
 */
public final class PaceGameTests {
    private static final int BASE_Y = 230;
    private static final int LAYER_STEP = 10;

    private static void fail(GameTestHelper context, String message) {
        context.fail(Component.nullToEmpty(message));
        throw new IllegalStateException(message);
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            fail(context, message);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Fixture
    // ---------------------------------------------------------------------------------------------------------------

    /** A flat stone floor with air above and a light grid (no natural spawns), on its own layer. */
    private static final class Arena {
        final GameTestHelper context;
        final ServerLevel world;
        final BlockPos feet;
        final int fromX;
        final int toX;
        final int halfZ;

        private Arena(GameTestHelper context, BlockPos feet, int fromX, int toX, int halfZ) {
            this.context = context;
            this.world = context.getLevel();
            this.feet = feet;
            this.fromX = fromX;
            this.toX = toX;
            this.halfZ = halfZ;
        }

        static Arena build(GameTestHelper context, int layer, int fromX, int toX, int halfZ) {
            ServerLevel world = context.getLevel();
            BlockPos feet = context.absolutePos(new BlockPos(8, BASE_Y + LAYER_STEP * layer, 8));
            for (int dx = fromX; dx <= toX; dx++) {
                for (int dz = -halfZ; dz <= halfZ; dz++) {
                    for (int dy = -5; dy <= 6; dy++) {
                        Block block = dy == -1 ? Blocks.STONE : Blocks.AIR;
                        world.setBlock(feet.offset(dx, dy, dz), block.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
            for (int dx = fromX; dx <= toX; dx += 4) {
                for (int dz = -halfZ; dz <= halfZ; dz += 4) {
                    world.setBlock(feet.offset(dx, 4, dz), Blocks.LIGHT.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
            return new Arena(context, feet, fromX, toX, halfZ);
        }

        void set(int dx, int dy, int dz, Block block) {
            world.setBlock(feet.offset(dx, dy, dz), block.defaultBlockState(), Block.UPDATE_ALL);
        }

        /** {@code block} in the column at ({@code dx}, {@code dz}) for heights {@code fromDy..toDy}. */
        void fill(int dx, int dz, Block block, int fromDy, int toDy) {
            for (int dy = fromDy; dy <= toDy; dy++) {
                set(dx, dy, dz, block);
            }
        }

        /** A bot on the legacy engine at the origin, full health and food. */
        AIPlayerEntity spawn(String name) {
            AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                            world.getServer(), name, world, Vec3.atBottomCenterOf(feet), 0.0F, 0.0F, GameType.SURVIVAL)
                    .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
            NavEngineSelector.setBotEngine(bot.getUUID(), NavEngine.LEGACY);
            BotFixtureMoves.place(bot, feet);
            bot.setOnGround(true);
            bot.setHealth(bot.getMaxHealth());
            bot.getFoodData().setFoodLevel(20);
            bot.getFoodData().setSaturation(20.0F);
            return bot;
        }

        void finish(AIPlayerEntity bot) {
            bot.getActionPack().stopAll();
            bot.getActionPack().clearPace();
            NavEngineSelector.clearBotEngine(bot.getUUID());
            AIPlayerManager.INSTANCE.despawn(world.getServer(), bot.getGameProfile().name());
            context.succeed();
        }
    }

    /** Per-tick view of a bot on a trip. */
    private static final class Trip {
        final AIPlayerEntity bot;
        final Vec3 start;
        int ticks;
        int sprintTicks;
        int movingTicks;
        Vec3 last;
        double speed;

        Trip(AIPlayerEntity bot) {
            this.bot = bot;
            this.start = bot.position();
            this.last = bot.position();
        }

        /** Samples the bot at the end of a tick. */
        void sample() {
            ticks++;
            Vec3 now = bot.position();
            speed = Math.hypot(now.x - last.x, now.z - last.z);
            last = now;
            if (bot.isSprinting()) {
                sprintTicks++;
            }
            if (speed > 0.01D) {
                movingTicks++;
            }
        }

        double distanceTo(BlockPos goal) {
            return Math.hypot(goal.getX() + 0.5D - bot.getX(), goal.getZ() + 0.5D - bot.getZ());
        }
    }

    private static BlockPos playersFloorGoal(Arena arena, int dx) {
        return arena.feet.offset(dx, 0, 0);
    }

    /** The bot, with the pressure probe true for it alone, for the duration of a test. */
    private static void pressureFor(AIPlayerEntity bot) {
        String name = bot.getGameProfile().name();
        PacePolicy.setPressureProbe(candidate -> candidate.getGameProfile().name().equals(name));
    }

    private static void clearPressure() {
        PacePolicy.setPressureProbe(null);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Sprint, walk, vanilla rules
    // ---------------------------------------------------------------------------------------------------------------

    @GameTest(maxTicks = 420)
    public void legacyLongPathSprintsThenWalksNearGoal(GameTestHelper context) {
        Arena arena = Arena.build(context, 0, -2, 44, 4);
        AIPlayerEntity bot = arena.spawn("PaceLongGT");
        BlockPos goal = playersFloorGoal(arena, 30);
        ActionResult started = bot.getActionPack().startPathTo(goal);
        require(context, !started.isFailed(), "the path was refused: " + started.reason());
        Trip trip = new Trip(bot);
        int[] sprintFirstTwenty = {0};
        int[] sprintNear = {0};
        boolean[] done = {false};
        context.onEachTick(() -> {
            if (done[0]) {
                return;
            }
            trip.sample();
            boolean sprinting = bot.isSprinting();
            if (sprinting && bot.getX() - trip.start.x <= 20.0D) {
                sprintFirstTwenty[0]++;
            }
            if (sprinting && trip.distanceTo(goal) <= 4.0D) {
                sprintNear[0]++;
            }
            if (trip.ticks > 2 && bot.getActionPack().isPathExecutorIdle()) {
                done[0] = true;
                System.out.println("PACE legacy_long ticks=" + trip.ticks + " sprintTicks=" + trip.sprintTicks
                        + " sprintFirst20=" + sprintFirstTwenty[0] + " sprintNear=" + sprintNear[0]);
                require(context, trip.distanceTo(goal) <= 1.6D, "the bot did not reach the goal: " + bot.position());
                require(context, sprintFirstTwenty[0] >= 20, "sprinted for only " + sprintFirstTwenty[0] + " ticks in the first 20 blocks");
                require(context, sprintNear[0] == 0, "sprinted for " + sprintNear[0] + " ticks within 4 blocks of the goal");
                arena.finish(bot);
            } else if (trip.ticks > 400) {
                done[0] = true;
                fail(context, "the path never ended, bot at " + bot.position());
            }
        });
    }

    @GameTest(maxTicks = 700)
    public void noSprintAtFoodSixLegacy(GameTestHelper context) {
        Arena arena = Arena.build(context, 1, -2, 44, 4);
        AIPlayerEntity bot = arena.spawn("PaceHungryGT");
        bot.getFoodData().setFoodLevel(6);
        bot.getFoodData().setSaturation(0.0F);
        BlockPos out = playersFloorGoal(arena, 30);
        BlockPos back = playersFloorGoal(arena, 4);
        require(context, !bot.getActionPack().startPathTo(out).isFailed(), "the outbound path was refused");
        Trip trip = new Trip(bot);
        int[] phase = {0};
        int[] sprintAtSix = {0};
        boolean[] done = {false};
        context.onEachTick(() -> {
            if (done[0]) {
                return;
            }
            trip.sample();
            if (phase[0] == 0) {
                if (bot.getFoodData().getFoodLevel() != 6) {
                    // hunger stays put: no food restored, nothing eaten (a drop would only make the rule easier to keep)
                    bot.getFoodData().setFoodLevel(6);
                }
                if (bot.isSprinting()) {
                    sprintAtSix[0]++;
                }
                if (trip.ticks > 2 && bot.getActionPack().isPathExecutorIdle()) {
                    require(context, trip.distanceTo(out) <= 1.6D, "did not arrive at 6 food: " + bot.position());
                    require(context, sprintAtSix[0] == 0, "sprinted " + sprintAtSix[0] + " ticks at 6 food points");
                    // 7 food points may sprint: the rule is the threshold, not "never".
                    bot.getFoodData().setFoodLevel(7);
                    trip.sprintTicks = 0;
                    require(context, !bot.getActionPack().startPathTo(back).isFailed(), "the return path was refused");
                    phase[0] = 1;
                }
            } else {
                if (trip.ticks > 2 && bot.getActionPack().isPathExecutorIdle()) {
                    done[0] = true;
                    require(context, trip.sprintTicks >= 10, "the bot did not sprint at 7 food points (" + trip.sprintTicks + " ticks)");
                    arena.finish(bot);
                }
            }
            if (trip.ticks > 660) {
                done[0] = true;
                fail(context, "the trips never ended, phase " + phase[0] + " at " + bot.position());
            }
        });
    }

    @GameTest(maxTicks = 600)
    public void raisedShieldSlowsWalkLegacy(GameTestHelper context) {
        Arena arena = Arena.build(context, 2, -2, 24, 4);
        AIPlayerEntity bot = arena.spawn("PaceShieldGT");
        bot.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
        BlockPos goal = playersFloorGoal(arena, 12);
        require(context, !bot.getActionPack().startPathTo(goal).isFailed(), "the path was refused");
        Trip trip = new Trip(bot);
        int[] blockingTicks = {0};
        boolean[] done = {false};
        context.onEachTick(() -> {
            if (done[0]) {
                return;
            }
            if (!bot.isUsingItem()) {
                InteractAction.useItemInAir(bot, InteractionHand.OFF_HAND);
            }
            trip.sample();
            if (bot.isUsingItem()) {
                blockingTicks[0]++;
            }
            if (trip.ticks > 2 && bot.getActionPack().isPathExecutorIdle()) {
                done[0] = true;
                double speed = trip.start.distanceTo(bot.position()) / Math.max(1, trip.movingTicks) * 20.0D;
                System.out.println("PACE legacy_shield ticks=" + trip.ticks + " moving=" + trip.movingTicks + " blocking=" + blockingTicks[0]
                        + " blocksPerSecond=" + String.format("%.2f", speed));
                require(context, trip.distanceTo(goal) <= 1.6D, "did not arrive: " + bot.position());
                require(context, blockingTicks[0] >= trip.ticks - 3, "the shield was not up for the whole walk");
                require(context, speed <= 1.5D, "walked at " + speed + " blocks/s with the shield raised (vanilla: about 0.9)");
                arena.finish(bot);
            } else if (trip.ticks > 560) {
                done[0] = true;
                fail(context, "the walk never ended: " + bot.position() + " moving=" + trip.movingTicks);
            }
        });
    }

    /**
     * A raw-input driver (marks its ticks) that runs at a wall under a SPRINT lease: the vanilla rule ends the sprint on the hard
     * collision, whatever the lease says.
     */
    @GameTest(maxTicks = 200)
    public void sprintEndsOnWallCollisionLegacy(GameTestHelper context) {
        Arena arena = Arena.build(context, 3, -2, 30, 4);
        for (int dz = -4; dz <= 4; dz++) {
            arena.fill(14, dz, Blocks.STONE, 0, 3);
        }
        AIPlayerEntity bot = arena.spawn("PaceWallGT");
        BotFixtureMoves.place(bot, arena.feet.offset(2, 0, 0));
        io.github.zoyluo.minecraftai.action.LookAction.lookHorizontallyAt(bot, Vec3.atCenterOf(arena.feet.offset(20, 0, 0)));
        Trip trip = new Trip(bot);
        int[] sprintBeforeWall = {0};
        int[] collisionTicks = {0};
        int[] sprintOnCollision = {0};
        boolean[] done = {false};
        context.onEachTick(() -> {
            if (done[0]) {
                return;
            }
            ActionPack pack = bot.getActionPack();
            pack.setForward(1.0F);
            pack.markControllerInput();
            pack.requestPace(Gait.SPRINT, PaceOwner.TASK);
            trip.sample();
            boolean hard = bot.horizontalCollision && !bot.minorHorizontalCollision;
            if (hard) {
                collisionTicks[0]++;
                if (bot.isSprinting()) {
                    sprintOnCollision[0]++;
                }
            } else if (bot.isSprinting()) {
                sprintBeforeWall[0]++;
            }
            if (trip.ticks >= 120) {
                done[0] = true;
                require(context, sprintBeforeWall[0] >= 15, "the bot never sprinted on the way to the wall (" + sprintBeforeWall[0] + ")");
                require(context, collisionTicks[0] >= 5, "the bot never ran into the wall (" + collisionTicks[0] + " collision ticks), at " + bot.position());
                require(context, sprintOnCollision[0] == 0, "sprinting on " + sprintOnCollision[0] + " of " + collisionTicks[0] + " wall collision ticks");
                arena.finish(bot);
            }
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Nodes that are never sprinted
    // ---------------------------------------------------------------------------------------------------------------

    @GameTest(maxTicks = 500)
    public void noSprintOnJumpOrDropNodesUnderPressure(GameTestHelper context) {
        Arena arena = Arena.build(context, 4, -2, 30, 3);
        // A one block step up from x=8 to x=13, then a two block drop at x=14 and flat ground again.
        for (int dx = 8; dx <= 13; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                arena.set(dx, 0, dz, Blocks.STONE);
            }
        }
        for (int dx = 14; dx <= 30; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                arena.set(dx, -2, dz, Blocks.STONE);
                arena.set(dx, -1, dz, Blocks.AIR);
            }
        }
        AIPlayerEntity bot = arena.spawn("PaceNodesGT");
        pressureFor(bot);
        BlockPos goal = arena.feet.offset(24, -1, 0);
        float health = bot.getHealth();
        require(context, !bot.getActionPack().startPathTo(goal).isFailed(), "the path was refused");
        Trip trip = new Trip(bot);
        int[] airborneSprint = {0};
        int[] airborneTicks = {0};
        int[] groundSprint = {0};
        boolean[] done = {false};
        context.onEachTick(() -> {
            if (done[0]) {
                return;
            }
            trip.sample();
            boolean airborne = !bot.onGround();
            if (airborne) {
                airborneTicks[0]++;
                if (bot.isSprinting()) {
                    airborneSprint[0]++;
                }
            } else if (bot.isSprinting()) {
                groundSprint[0]++;
            }
            if (trip.ticks > 2 && bot.getActionPack().isPathExecutorIdle()) {
                done[0] = true;
                clearPressure();
                System.out.println("PACE nodes ticks=" + trip.ticks + " airborne=" + airborneTicks[0] + " airborneSprint=" + airborneSprint[0]
                        + " groundSprint=" + groundSprint[0]);
                require(context, trip.distanceTo(goal) <= 1.6D && bot.getY() <= arena.feet.getY() - 0.9D,
                        "the path did not end at the goal below the drop: " + bot.position() + " goal " + goal + " feet " + arena.feet);
                require(context, groundSprint[0] >= 10, "pressure did not make the bot sprint on the flat (" + groundSprint[0] + ")");
                require(context, airborneTicks[0] >= 4, "the bot was never airborne (" + airborneTicks[0] + "): no jump or drop happened");
                require(context, airborneSprint[0] == 0, "sprinted for " + airborneSprint[0] + " airborne ticks (jump or drop node)");
                require(context, bot.getHealth() >= health, "a drop cost health: " + health + " -> " + bot.getHealth());
                arena.finish(bot);
            } else if (trip.ticks > 470) {
                done[0] = true;
                clearPressure();
                fail(context, "the path never ended: " + bot.position());
            }
        });
    }

    /**
     * The walked combat steps keep their own rules: under pressure they do not sprint (the enforcer leaves raw keys alone), they
     * arrive inside the unchanged step budget, and with a raised shield a one cell step still arrives (no double slowdown).
     * A regression guard for the raw-key path (there is no {@code markControllerInput} caller in combat yet), not a pace decision test.
     */
    @GameTest(maxTicks = 200)
    public void combatStepUnderPressureDoesNotSprint(GameTestHelper context) {
        Arena arena = Arena.build(context, 5, -2, 12, 3);
        AIPlayerEntity bot = arena.spawn("PaceStepGT");
        BlockPos origin = arena.feet;
        int[] stage = {0};
        CombatCore.InputStep[] step = {null};
        int[] sprintTicks = {0};
        int[] plainTicks = {0};
        boolean[] done = {false};
        context.onEachTick(() -> {
            if (done[0]) {
                return;
            }
            if (bot.isSprinting()) {
                sprintTicks[0]++;
            }
            if (stage[0] == 1 && step[0] == null) {
                pressureFor(bot); // from the second step on
            }
            switch (stage[0]) {
                case 0, 1 -> {
                    BlockPos cell = stage[0] == 0 ? origin.east() : origin;
                    if (step[0] == null) {
                        step[0] = CombatCore.beginStepByInput(cell, false, false);
                    }
                    CombatCore.StepStatus status = CombatCore.stepByInput(bot, step[0]);
                    require(context, status != CombatCore.StepStatus.FAILED, "the plain step failed: " + step[0].failure());
                    if (status == CombatCore.StepStatus.ARRIVED) {
                        if (stage[0] == 0) {
                            plainTicks[0] = step[0].ticks();
                        } else {
                            // Same step, same budget: pressure changes nothing about a walked step.
                            // REGRESSION GUARD for the raw-key path only: no combat step calls markControllerInput() yet, so the
                            // enforcer leaves its keys alone and this relative assertion (pressure costs no extra ticks) is what
                            // keeps it that way. It does not prove a pace decision for combat steps; when a step becomes a
                            // controller-driven caller this test must be revisited (and the plain step re-measured).
                            require(context, step[0].ticks() <= plainTicks[0] + 1,
                                    "the step under pressure took " + step[0].ticks() + " ticks against " + plainTicks[0] + " without it");
                        }
                        step[0] = null;
                        stage[0]++;
                        if (stage[0] == 2) {
                            bot.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
                            InteractAction.useItemInAir(bot, InteractionHand.OFF_HAND);
                            require(context, bot.isUsingItem(), "the shield did not go up");
                        }
                    }
                }
                default -> {
                    if (!bot.isUsingItem()) {
                        InteractAction.useItemInAir(bot, InteractionHand.OFF_HAND);
                    }
                    if (step[0] == null) {
                        step[0] = CombatCore.beginStepByInput(origin.east(), false, false);
                    }
                    CombatCore.StepStatus status = CombatCore.stepByInput(bot, step[0]);
                    require(context, status != CombatCore.StepStatus.FAILED,
                            "the step with a raised shield failed: " + step[0].failure() + " after " + step[0].ticks() + " ticks");
                    if (status == CombatCore.StepStatus.ARRIVED) {
                        done[0] = true;
                        clearPressure();
                        System.out.println("PACE step plain=" + plainTicks[0] + " shield=" + step[0].ticks());
                        require(context, sprintTicks[0] == 0, "a combat step sprinted for " + sprintTicks[0] + " ticks (pressure on)");
                        arena.finish(bot);
                    }
                }
            }
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Leases
    // ---------------------------------------------------------------------------------------------------------------

    @GameTest(maxTicks = 600)
    public void sneakLeaseLiftsOnDropNode(GameTestHelper context) {
        Arena arena = Arena.build(context, 6, -2, 30, 3);
        for (int dx = 8; dx <= 30; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                arena.set(dx, -3, dz, Blocks.STONE);
                arena.set(dx, -1, dz, Blocks.AIR);
            }
        }
        AIPlayerEntity bot = arena.spawn("PaceSneakDropGT");
        BlockPos goal = arena.feet.offset(14, -2, 0);
        require(context, !bot.getActionPack().startPathTo(goal).isFailed(), "the path was refused");
        bot.getActionPack().requestRoutePace(Gait.SNEAK, PaceOwner.WARDEN);
        Trip trip = new Trip(bot);
        int[] sneakFlat = {0};
        int[] flatMoving = {0};
        int[] sneakAirborne = {0};
        boolean[] done = {false};
        context.onEachTick(() -> {
            if (done[0]) {
                return;
            }
            trip.sample();
            // Flat ticks well before the edge (the last block before the drop is the drop node's: the sneak is lifted there).
            if (bot.onGround() && bot.getY() > arena.feet.getY() - 0.5D && bot.getX() < arena.feet.getX() + 6.0D
                    && trip.speed > 0.01D && !bot.getActionPack().isPathExecutorIdle()) {
                flatMoving[0]++;
                if (bot.isShiftKeyDown()) {
                    sneakFlat[0]++;
                }
            }
            if (!bot.onGround() && bot.isShiftKeyDown()) {
                sneakAirborne[0]++;
            }
            if (trip.ticks > 2 && bot.getActionPack().isPathExecutorIdle()) {
                done[0] = true;
                System.out.println("PACE sneak_drop ticks=" + trip.ticks + " flatMoving=" + flatMoving[0] + " sneakFlat=" + sneakFlat[0]
                        + " sneakAirborne=" + sneakAirborne[0]);
                require(context, trip.distanceTo(goal) <= 1.6D && bot.getY() <= arena.feet.getY() - 1.9D,
                        "the sneaking bot did not get down the drop to the goal: " + bot.position() + " goal " + goal + " feet " + arena.feet);
                require(context, flatMoving[0] >= 20, "too few flat moving ticks to judge (" + flatMoving[0] + ")");
                require(context, sneakFlat[0] >= flatMoving[0] - 2, "sneaked on only " + sneakFlat[0] + " of " + flatMoving[0] + " flat moving ticks");
                require(context, sneakAirborne[0] == 0, "still sneaking while falling (" + sneakAirborne[0] + " ticks)");
                arena.finish(bot);
            } else if (trip.ticks > 560) {
                done[0] = true;
                fail(context, "the sneaking bot never got down: " + bot.position() + " moving=" + trip.movingTicks);
            }
        });
    }

    @GameTest(maxTicks = 700)
    public void routeLeaseSurvivesSubTargetStopMovement(GameTestHelper context) {
        Arena arena = Arena.build(context, 7, -2, 24, 5);
        // Two walls with alternating gaps: the path has several corners, and every corner is a sub-target (stopMovement).
        for (int dz = -5; dz <= 5; dz++) {
            if (dz != 4) {
                arena.fill(6, dz, Blocks.STONE, 0, 2);
            }
            if (dz != -4) {
                arena.fill(13, dz, Blocks.STONE, 0, 2);
            }
        }
        AIPlayerEntity bot = arena.spawn("PaceLeaseGT");
        BlockPos goal = arena.feet.offset(20, 0, 0);
        require(context, !bot.getActionPack().startPathTo(goal).isFailed(), "the path was refused");
        bot.getActionPack().requestRoutePace(Gait.SNEAK, PaceOwner.WARDEN);
        Trip trip = new Trip(bot);
        int[] flatMoving = {0};
        int[] notSneaking = {0};
        boolean[] done = {false};
        context.onEachTick(() -> {
            if (done[0]) {
                return;
            }
            trip.sample();
            // While the path runs: the tick it ends the controllers let go and the bot only slides to a stop.
            if (bot.onGround() && trip.speed > 0.01D && !bot.getActionPack().isPathExecutorIdle()) {
                flatMoving[0]++;
                if (!bot.isShiftKeyDown()) {
                    notSneaking[0]++;
                }
            }
            if (bot.getActionPack().leasedGait() != Gait.SNEAK && !bot.getActionPack().isPathExecutorIdle()) {
                done[0] = true;
                fail(context, "the route lease ended in the middle of the path, at " + bot.position());
            }
            if (trip.ticks > 2 && bot.getActionPack().isPathExecutorIdle()) {
                done[0] = true;
                System.out.println("PACE route_lease ticks=" + trip.ticks + " flatMoving=" + flatMoving[0] + " notSneaking=" + notSneaking[0]);
                require(context, trip.distanceTo(goal) <= 1.6D, "did not arrive: " + bot.position());
                require(context, flatMoving[0] >= 60, "too few moving ticks to judge (" + flatMoving[0] + ")");
                require(context, notSneaking[0] == 0, "shift was up on " + notSneaking[0] + " of " + flatMoving[0] + " moving ticks");
                require(context, bot.getActionPack().leasedGait() == null, "the route lease outlived the path");
                arena.finish(bot);
            } else if (trip.ticks > 660) {
                done[0] = true;
                fail(context, "the path never ended: " + bot.position() + " moving=" + trip.movingTicks);
            }
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Quiet zones
    // ---------------------------------------------------------------------------------------------------------------

    @GameTest(maxTicks = 420)
    public void darknessCapsSprint(GameTestHelper context) {
        Arena arena = Arena.build(context, 8, -2, 44, 4);
        AIPlayerEntity bot = arena.spawn("PaceDarkGT");
        bot.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 4000, 0, false, false));
        BlockPos goal = playersFloorGoal(arena, 30);
        require(context, !bot.getActionPack().startPathTo(goal).isFailed(), "the path was refused");
        Trip trip = new Trip(bot);
        boolean[] done = {false};
        context.onEachTick(() -> {
            if (done[0]) {
                return;
            }
            bot.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 4000, 0, false, false));
            trip.sample();
            if (trip.ticks > 2 && bot.getActionPack().isPathExecutorIdle()) {
                done[0] = true;
                require(context, trip.distanceTo(goal) <= 1.6D, "did not arrive: " + bot.position());
                require(context, trip.sprintTicks == 0, "sprinted for " + trip.sprintTicks + " ticks under the darkness effect");
                arena.finish(bot);
            } else if (trip.ticks > 400) {
                done[0] = true;
                fail(context, "the path never ended: " + bot.position());
            }
        });
    }

    @GameTest(maxTicks = 700)
    public void observedShriekerMakesBotSneak(GameTestHelper context) {
        Arena arena = Arena.build(context, 9, -2, 44, 5);
        BlockPos shrieker = arena.feet.offset(3, 0, 3);
        arena.set(3, 0, 3, Blocks.SCULK_SHRIEKER);
        AIPlayerEntity bot = arena.spawn("PaceShriekGT");
        bot.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 4000, 0, false, false));
        BlockPos goal = playersFloorGoal(arena, 36);
        require(context, !bot.getActionPack().startPathTo(goal).isFailed(), "the path was refused");
        Trip trip = new Trip(bot);
        int[] nearMoving = {0};
        int[] nearNotSneaking = {0};
        int[] farMoving = {0};
        int[] farSneaking = {0};
        boolean[] done = {false};
        context.onEachTick(() -> {
            if (done[0]) {
                return;
            }
            bot.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 4000, 0, false, false));
            trip.sample();
            double distance = Math.hypot(shrieker.getX() + 0.5D - bot.getX(), shrieker.getZ() + 0.5D - bot.getZ());
            if (bot.onGround() && trip.speed > 0.01D && trip.ticks > 3) {
                if (distance <= 6.0D) {
                    nearMoving[0]++;
                    if (!bot.isShiftKeyDown()) {
                        nearNotSneaking[0]++;
                    }
                } else if (distance >= 16.0D) {
                    farMoving[0]++;
                    if (bot.isShiftKeyDown()) {
                        farSneaking[0]++;
                    }
                }
            }
            if (trip.ticks > 2 && bot.getActionPack().isPathExecutorIdle()) {
                done[0] = true;
                System.out.println("PACE shrieker ticks=" + trip.ticks + " near=" + nearMoving[0] + "/" + nearNotSneaking[0]
                        + " far=" + farMoving[0] + "/" + farSneaking[0] + " sprintTicks=" + trip.sprintTicks);
                require(context, trip.distanceTo(goal) <= 1.6D, "did not arrive: " + bot.position());
                require(context, nearMoving[0] >= 20, "too few moving ticks near the shrieker to judge (" + nearMoving[0] + ")");
                require(context, nearNotSneaking[0] == 0, "not sneaking on " + nearNotSneaking[0] + " of " + nearMoving[0] + " ticks within 6 blocks of an observed shrieker");
                require(context, farMoving[0] >= 20 && farSneaking[0] == 0,
                        "still sneaking " + farSneaking[0] + " of " + farMoving[0] + " moving ticks 16+ blocks past the shrieker");
                require(context, trip.sprintTicks == 0, "sprinted in the dark (" + trip.sprintTicks + ")");
                arena.finish(bot);
            } else if (trip.ticks > 660) {
                done[0] = true;
                fail(context, "the path never ended: " + bot.position());
            }
        });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Movement hunger
    // ---------------------------------------------------------------------------------------------------------------

    /** The exhaustion accumulator of a {@link FoodData}; vanilla has no getter. */
    private static float exhaustion(FoodData food) {
        try {
            Field field = FoodData.class.getDeclaredField("exhaustionLevel");
            field.setAccessible(true);
            return field.getFloat(food);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("cannot read FoodData.exhaustionLevel", exception);
        }
    }

    @GameTest(maxTicks = 500)
    public void movementExhaustionChargesSprint(GameTestHelper context) {
        Arena arena = Arena.build(context, 10, -2, 50, 4);
        AIPlayerEntity bot = arena.spawn("PaceHungerGT");
        BlockPos goal = playersFloorGoal(arena, 44);
        FoodData food = bot.getFoodData();
        float exhaustionBefore = exhaustion(food);
        float saturationBefore = food.getSaturationLevel();
        int foodBefore = food.getFoodLevel();
        int sprintCmBefore = bot.getStats().getValue(Stats.CUSTOM.get(Stats.SPRINT_ONE_CM));
        require(context, !bot.getActionPack().startPathTo(goal).isFailed(), "the path was refused");
        Trip trip = new Trip(bot);
        boolean[] done = {false};
        context.onEachTick(() -> {
            if (done[0]) {
                return;
            }
            trip.sample();
            if (trip.ticks > 2 && bot.getActionPack().isPathExecutorIdle()) {
                done[0] = true;
                // Vanilla folds every 4.0 of exhaustion into one point of saturation (or food): add those back.
                float folded = (saturationBefore - food.getSaturationLevel()) + (foodBefore - food.getFoodLevel());
                float charged = exhaustion(food) - exhaustionBefore + 4.0F * folded;
                int sprintCm = bot.getStats().getValue(Stats.CUSTOM.get(Stats.SPRINT_ONE_CM)) - sprintCmBefore;
                System.out.println("PACE exhaustion charged=" + charged + " sprintCm=" + sprintCm + " sprintTicks=" + trip.sprintTicks);
                require(context, trip.distanceTo(goal) <= 1.6D, "did not arrive: " + bot.position());
                require(context, trip.sprintTicks >= 60, "too little sprinting to judge (" + trip.sprintTicks + " ticks)");
                require(context, charged >= 3.0F, "about 40 sprinted blocks charged only " + charged + " exhaustion (vanilla: 0.1 per block)");
                require(context, sprintCm >= 3000, "the sprint statistic was not awarded: " + sprintCm + " cm");
                arena.finish(bot);
            } else if (trip.ticks > 480) {
                done[0] = true;
                fail(context, "the path never ended: " + bot.position());
            }
        });
    }
}
