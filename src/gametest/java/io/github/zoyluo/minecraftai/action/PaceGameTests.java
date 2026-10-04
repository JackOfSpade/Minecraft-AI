package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.gametest.BotFixtureMoves;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.navigation.NavEngine;
import io.github.zoyluo.minecraftai.navigation.NavEngineSelector;
import io.github.zoyluo.minecraftai.task.CombatCore;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
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
            NavEngineSelector.setBotEngine(bot.getUUID(), NavEngine.BARITONE);
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


}
