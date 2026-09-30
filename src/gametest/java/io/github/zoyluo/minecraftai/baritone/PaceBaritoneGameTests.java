package io.github.zoyluo.minecraftai.baritone;

import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.Gait;
import io.github.zoyluo.minecraftai.action.InteractAction;
import io.github.zoyluo.minecraftai.action.PaceOwner;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.navigation.NavEngine;
import io.github.zoyluo.minecraftai.navigation.NavEngineSelector;
import io.github.zoyluo.minecraftai.navigation.NavOutcome;
import io.github.zoyluo.minecraftai.task.EvadeTask;
import io.github.zoyluo.minecraftai.task.TaskState;
import io.github.zoyluo.minecraftai.task.Threat;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The pace policy on the Baritone engine ({@code nav.engine=baritone} for the bot under test only): a long route sprints and walks in
 * as it nears the goal, a raised shield slows the walk, the evade task keeps its own route (setting the sprint flag must not cancel
 * it), and Baritone's own needs stay in force under a walk or sneak pace: a parkour jump is still made, and a vine column is still
 * climbed down.
 *
 * <p>Every course is sealed by a bedrock ring and has its own world layer (all tests of a batch run at once).</p>
 */
public final class PaceBaritoneGameTests {
    private static final int BASE_Y = 130;
    private static final int LAYER_STEP = 14;

    private static void fail(GameTestHelper context, String message) {
        context.fail(Component.nullToEmpty(message));
        throw new IllegalStateException(message);
    }

    private static void require(GameTestHelper context, boolean condition, String message) {
        if (!condition) {
            fail(context, message);
        }
    }

    /** A bedrock-sealed flat course on its own layer; the test builds its obstacles, then spawns the bot. */
    private static final class Course {
        final GameTestHelper context;
        final ServerLevel world;
        final BlockPos feet;
        final String name;
        AIPlayerEntity bot;

        private Course(GameTestHelper context, String name, BlockPos feet) {
            this.context = context;
            this.world = context.getLevel();
            this.name = name;
            this.feet = feet;
        }

        static Course build(GameTestHelper context, String name, int layer, int fromX, int toX, int halfZ, int height) {
            ServerLevel world = context.getLevel();
            BlockPos feet = context.absolutePos(new BlockPos(8, BASE_Y + LAYER_STEP * layer, 8));
            for (int dx = fromX - 1; dx <= toX + 1; dx++) {
                for (int dz = -halfZ - 1; dz <= halfZ + 1; dz++) {
                    boolean ring = dx == fromX - 1 || dx == toX + 1 || dz == -halfZ - 1 || dz == halfZ + 1;
                    for (int dy = -5; dy <= height; dy++) {
                        BlockState state;
                        if (ring && dy >= -1) {
                            state = Blocks.BEDROCK.defaultBlockState();
                        } else if (!ring && dy == -1) {
                            state = Blocks.STONE.defaultBlockState();
                        } else {
                            state = Blocks.AIR.defaultBlockState();
                        }
                        world.setBlock(feet.offset(dx, dy, dz), state, Block.UPDATE_CLIENTS);
                    }
                }
            }
            // No natural spawns near the bot under test (a hostile in view would make the bot's own reflexes act).
            for (int dx = fromX; dx <= toX; dx += 4) {
                for (int dz = -halfZ; dz <= halfZ; dz += 4) {
                    world.setBlock(feet.offset(dx, height - 1, dz), Blocks.LIGHT.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
            return new Course(context, name, feet);
        }

        void set(int dx, int dy, int dz, Block block) {
            world.setBlock(feet.offset(dx, dy, dz), block.defaultBlockState(), Block.UPDATE_ALL);
        }

        void fill(int dx, int dz, Block block, int fromDy, int toDy) {
            for (int dy = fromDy; dy <= toDy; dy++) {
                set(dx, dy, dz, block);
            }
        }

        /** Spawns the bot {@code startDx} east and {@code startDy} above {@code feet}, on the Baritone engine. */
        AIPlayerEntity spawn(int startDx, int startDy) {
            bot = BaritoneServerGameTests.spawn(context, name, feet.offset(startDx, startDy, 0));
            bot.setHealth(bot.getMaxHealth());
            bot.getFoodData().setFoodLevel(20);
            bot.getFoodData().setSaturation(20.0F);
            NavEngineSelector.setBotEngine(bot.getUUID(), NavEngine.BARITONE);
            return bot;
        }

        void finish() {
            bot.getActionPack().stopAll();
            bot.getActionPack().clearPace();
            NavEngineSelector.clearBotEngine(bot.getUUID());
            AIPlayerManager.INSTANCE.despawn(world.getServer(), name);
            context.succeed();
        }

        double distanceTo(BlockPos goal) {
            return Math.hypot(goal.getX() + 0.5D - bot.getX(), goal.getZ() + 0.5D - bot.getZ());
        }
    }

    /** Sampled state of a route. */
    private static final class Trip {
        final AIPlayerEntity bot;
        final Vec3 start;
        Vec3 last;
        int ticks;
        int sprintTicks;
        int movingTicks;
        double speed;
        double minY;

        Trip(AIPlayerEntity bot) {
            this.bot = bot;
            this.start = bot.position();
            this.last = bot.position();
            this.minY = bot.getY();
        }

        void sample() {
            ticks++;
            Vec3 now = bot.position();
            speed = Math.hypot(now.x - last.x, now.z - last.z);
            last = now;
            minY = Math.min(minY, now.y);
            if (bot.isSprinting()) {
                sprintTicks++;
            }
            if (speed > 0.01D) {
                movingTicks++;
            }
        }
    }

    private static boolean routeEnded(ActionPack pack) {
        return !pack.hasBaritoneRoute();
    }

    private static void requireRouteSucceeded(GameTestHelper context, ActionPack pack) {
        NavOutcome outcome = pack.lastRouteOutcome();
        require(context, outcome != null && outcome.success(), "the route did not succeed: " + outcome);
    }

    // ---------------------------------------------------------------------------------------------------------------

    @GameTest(maxTicks = 420)
    public void baritoneLongRouteSprintsThenWalksNearGoal(GameTestHelper context) {
        Course course = Course.build(context, "PaceBLongGT", 0, -2, 44, 4, 5);
        AIPlayerEntity bot = course.spawn(0, 0);
        BlockPos goal = course.feet.offset(30, 0, 0);
        ActionResult started = bot.getActionPack().startSurfacePathTo(goal);
        require(context, !started.isFailed(), "the route was refused: " + started.reason());
        require(context, bot.getActionPack().hasBaritoneRoute(), "the route is not Baritone's");
        Trip trip = new Trip(bot);
        int[] sprintFirstTwenty = {0};
        int[] sprintNear = {0};
        boolean[] done = {false};
        context.onEachTick(() -> {
            if (done[0]) {
                return;
            }
            trip.sample();
            if (bot.isSprinting() && bot.getX() - trip.start.x <= 20.0D) {
                sprintFirstTwenty[0]++;
            }
            if (bot.isSprinting() && course.distanceTo(goal) <= 4.0D) {
                sprintNear[0]++;
            }
            if (trip.ticks > 2 && routeEnded(bot.getActionPack())) {
                done[0] = true;
                System.out.println("PACE baritone_long ticks=" + trip.ticks + " sprintTicks=" + trip.sprintTicks
                        + " sprintFirst20=" + sprintFirstTwenty[0] + " sprintNear=" + sprintNear[0]);
                requireRouteSucceeded(context, bot.getActionPack());
                require(context, course.distanceTo(goal) <= 1.6D, "not at the goal: " + bot.position());
                require(context, sprintFirstTwenty[0] >= 20, "sprinted for only " + sprintFirstTwenty[0] + " ticks in the first 20 blocks");
                require(context, sprintNear[0] == 0, "sprinted for " + sprintNear[0] + " ticks within 4 blocks of the goal");
                course.finish();
            } else if (trip.ticks > 400) {
                done[0] = true;
                fail(context, "the route never ended: " + bot.position());
            }
        });
    }

    @GameTest(maxTicks = 700)
    public void raisedShieldSlowsWalkBaritone(GameTestHelper context) {
        Course course = Course.build(context, "PaceBShieldGT", 1, -2, 24, 4, 5);
        AIPlayerEntity bot = course.spawn(0, 0);
        bot.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
        BlockPos goal = course.feet.offset(12, 0, 0);
        ActionResult started = bot.getActionPack().startSurfacePathTo(goal);
        require(context, !started.isFailed() && bot.getActionPack().hasBaritoneRoute(), "the route was not started: " + started.reason());
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
            if (trip.ticks > 2 && routeEnded(bot.getActionPack())) {
                done[0] = true;
                double speed = trip.start.distanceTo(bot.position()) / Math.max(1, trip.movingTicks) * 20.0D;
                System.out.println("PACE baritone_shield ticks=" + trip.ticks + " moving=" + trip.movingTicks + " blocking=" + blockingTicks[0]
                        + " blocksPerSecond=" + String.format("%.2f", speed) + " outcome=" + bot.getActionPack().lastRouteOutcome());
                requireRouteSucceeded(context, bot.getActionPack());
                require(context, course.distanceTo(goal) <= 1.6D, "not at the goal: " + bot.position());
                require(context, blockingTicks[0] >= trip.ticks - 3, "the shield was not up for the whole walk");
                require(context, speed <= 1.5D, "walked at " + speed + " blocks/s with the shield raised (vanilla: about 0.9)");
                course.finish();
            } else if (trip.ticks > 660) {
                done[0] = true;
                fail(context, "the route never ended: " + bot.position() + " outcome=" + bot.getActionPack().lastRouteOutcome());
            }
        });
    }

    /**
     * Evade starts a surface route and then sets the sprint flag: that must not take the bot back from Baritone and cancel the route
     * it has just started (the claim bug). The bot ends well away from the zombie it flees.
     */
    @GameTest(maxTicks = 320)
    public void evadeOnBaritoneKeepsItsRoute(GameTestHelper context) {
        Course course = Course.build(context, "PaceBEvadeGT", 2, -26, 26, 14, 6);
        AIPlayerEntity bot = course.spawn(0, 0);
        Zombie zombie = EntityType.ZOMBIE.create(course.world, EntitySpawnReason.COMMAND);
        require(context, zombie != null, "cannot create the zombie");
        zombie.setNoAi(true);
        zombie.setSilent(true);
        zombie.setPersistenceRequired();
        BlockPos zombieCell = course.feet.offset(-2, 0, 0);
        zombie.snapTo(zombieCell.getX() + 0.5D, zombieCell.getY(), zombieCell.getZ() + 0.5D, 0.0F, 0.0F);
        course.world.addFreshEntity(zombie);
        EvadeTask evade = new EvadeTask(new Threat(Threat.Type.HOSTILE, Threat.Severity.HIGH, zombie, zombieCell));
        evade.start(bot);
        int[] ticks = {0};
        double[] farthest = {0.0D};
        boolean[] done = {false};
        context.onEachTick(() -> {
            if (done[0]) {
                return;
            }
            ticks[0]++;
            if (evade.state() == TaskState.RUNNING) {
                evade.tick(bot);
            }
            NavOutcome outcome = bot.getActionPack().lastRouteOutcome();
            if (outcome != null && outcome.reason() != null && outcome.reason().contains("set_sprinting")) {
                done[0] = true;
                zombie.discard();
                fail(context, "setting the sprint flag cancelled the evade route: " + outcome);
            }
            farthest[0] = Math.max(farthest[0], bot.position().distanceTo(zombie.position()));
            boolean over = evade.state() != TaskState.RUNNING && evade.state() != TaskState.PENDING;
            if (farthest[0] >= 8.0D && (over || ticks[0] > 40)) {
                done[0] = true;
                System.out.println("PACE evade ticks=" + ticks[0] + " farthest=" + String.format("%.1f", farthest[0]) + " state=" + evade.state()
                        + " outcome=" + outcome);
                zombie.discard();
                course.finish();
            } else if (ticks[0] > 300) {
                done[0] = true;
                zombie.discard();
                fail(context, "the bot got only " + String.format("%.1f", farthest[0]) + " blocks from the zombie in 300 ticks; evade "
                        + evade.state() + " " + evade.failureReason() + ", route " + outcome);
            }
        });
    }

    /** A gap of three blocks with the goal three blocks past it, under a WALK route lease: the run-up and the jump still sprint. */
    @GameTest(maxTicks = 500)
    public void parkourGapNearGoalUnderWalkPace(GameTestHelper context) {
        Course course = Course.build(context, "PaceBParkourWalkGT", 3, -2, 16, 4, 6);
        gap(course, 4, 3);
        AIPlayerEntity bot = course.spawn(0, 0);
        BlockPos goal = course.feet.offset(4 + 3 + 3, 0, 0);
        ActionResult started = bot.getActionPack().startSurfacePathTo(goal);
        require(context, !started.isFailed() && bot.getActionPack().hasBaritoneRoute(), "the route was not started: " + started.reason());
        bot.getActionPack().requestRoutePace(Gait.WALK, PaceOwner.TASK);
        awaitJump(course, bot, goal, "gap of 3 at a walk pace", () -> { });
    }

    /** A gap of two blocks under a SNEAK lease renewed every tick: the sneak is lifted for the jump and its run-up. */
    @GameTest(maxTicks = 700)
    public void parkourGapUnderSneakLease(GameTestHelper context) {
        Course course = Course.build(context, "PaceBParkourSneakGT", 4, -2, 16, 4, 6);
        gap(course, 4, 2);
        AIPlayerEntity bot = course.spawn(0, 0);
        BlockPos goal = course.feet.offset(4 + 2 + 3, 0, 0);
        ActionResult started = bot.getActionPack().startSurfacePathTo(goal);
        require(context, !started.isFailed() && bot.getActionPack().hasBaritoneRoute(), "the route was not started: " + started.reason());
        awaitJump(course, bot, goal, "gap of 2 under a sneak lease",
                () -> bot.getActionPack().requestPace(Gait.SNEAK, PaceOwner.WARDEN));
    }

    private static void gap(Course course, int fromX, int width) {
        for (int dx = fromX; dx < fromX + width; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                course.set(dx, -1, dz, Blocks.AIR);
            }
        }
    }

    private static void awaitJump(Course course, AIPlayerEntity bot, BlockPos goal, String what, Runnable everyTick) {
        GameTestHelper context = course.context;
        Trip trip = new Trip(bot);
        double[] maxY = {bot.getY()};
        float health = bot.getHealth();
        boolean[] done = {false};
        context.onEachTick(() -> {
            if (done[0]) {
                return;
            }
            everyTick.run();
            trip.sample();
            maxY[0] = Math.max(maxY[0], bot.getY());
            if (trip.ticks > 2 && routeEnded(bot.getActionPack())) {
                done[0] = true;
                System.out.println("PACE " + what + " ticks=" + trip.ticks + " minY=" + (trip.minY - course.feet.getY())
                        + " maxY=" + (maxY[0] - course.feet.getY()) + " outcome=" + bot.getActionPack().lastRouteOutcome());
                requireRouteSucceeded(context, bot.getActionPack());
                require(context, course.distanceTo(goal) <= 1.6D, what + ": not at the goal: " + bot.position());
                require(context, trip.minY >= course.feet.getY() - 0.1D, what + ": the bot fell into the gap (min y offset " + (trip.minY - course.feet.getY()) + ")");
                require(context, maxY[0] >= course.feet.getY() + 0.3D, what + ": the bot never jumped");
                require(context, bot.getHealth() >= health, what + ": the bot lost health");
                course.finish();
            } else if (trip.ticks > 650) {
                done[0] = true;
                fail(context, what + ": the route never ended: " + bot.position() + " outcome=" + bot.getActionPack().lastRouteOutcome());
            }
        });
    }

    /** Climb down a six block vine column with a SNEAK route lease: a sneaking player would not go down it, so the sneak is lifted. */
    @GameTest(maxTicks = 800)
    public void vineDescentUnderSneakLease(GameTestHelper context) {
        Course course = Course.build(context, "PaceBVineGT", 5, -2, 10, 3, 9);
        for (int dx = 3; dx <= 10; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                course.fill(dx, dz, Blocks.BEDROCK, 0, 5);
            }
        }
        BlockState vine = Blocks.VINE.defaultBlockState().setValue(VineBlock.EAST, true);
        for (int dy = 0; dy <= 5; dy++) {
            course.world.setBlock(course.feet.offset(2, dy, 0), vine, Block.UPDATE_CLIENTS);
        }
        AIPlayerEntity bot = course.spawn(7, 6);
        BlockPos goal = course.feet;
        ActionResult started = bot.getActionPack().startSurfacePathTo(goal);
        require(context, !started.isFailed() && bot.getActionPack().hasBaritoneRoute(), "the route was not started: " + started.reason());
        bot.getActionPack().requestRoutePace(Gait.SNEAK, PaceOwner.WARDEN);
        Trip trip = new Trip(bot);
        float health = bot.getHealth();
        int[] sneakingOnVine = {0};
        boolean[] done = {false};
        context.onEachTick(() -> {
            if (done[0]) {
                return;
            }
            trip.sample();
            if (bot.onClimbable() && bot.isShiftKeyDown()) {
                sneakingOnVine[0]++;
            }
            if (trip.ticks > 2 && routeEnded(bot.getActionPack())) {
                done[0] = true;
                System.out.println("PACE vine ticks=" + trip.ticks + " sneakingOnVine=" + sneakingOnVine[0] + " outcome=" + bot.getActionPack().lastRouteOutcome());
                requireRouteSucceeded(context, bot.getActionPack());
                require(context, course.distanceTo(goal) <= 1.6D && bot.getY() <= course.feet.getY() + 0.8D, "not down at the goal: " + bot.position());
                require(context, bot.getHealth() >= health, "the descent cost health: " + health + " -> " + bot.getHealth());
                require(context, sneakingOnVine[0] == 0, "shift was down for " + sneakingOnVine[0] + " ticks on the vines");
                course.finish();
            } else if (trip.ticks > 760) {
                done[0] = true;
                fail(context, "the bot never got down: " + bot.position() + " outcome=" + bot.getActionPack().lastRouteOutcome());
            }
        });
    }
}
