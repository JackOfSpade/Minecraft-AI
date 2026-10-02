package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.baritone.BaritoneRegistry;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.navigation.NavEngine;
import io.github.zoyluo.minecraftai.navigation.NavEngineSelector;
import io.github.zoyluo.minecraftai.navigation.NavigationMeasurement;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import io.github.zoyluo.minecraftai.navigation.NavOutcome;
import io.github.zoyluo.minecraftai.runtime.TaskOrigin;
import io.github.zoyluo.minecraftai.task.NavigationCourses.Course;
import io.github.zoyluo.minecraftai.task.NavigationCourses.Expect;
import io.github.zoyluo.minecraftai.task.NavigationCourses.Mode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one Baritone navigation course and measures it (reached, ticks, damage, blocks broken/placed, water entered, failure reason).
 * The bot is driven only through the real task API ({@code FollowTask} on a stand-in player, or {@code MoveTask}) on a fresh arena.
 * The result is logged as one {@code NAVCOURSE} line (console and {@code <game dir>/nav_courses/results.tsv}) and in the bot's own
 * log (PATH category, {@code nav_course_result}). Each run asserts the strict-survival safety and outcome invariants.
 */
final class NavigationCourseRun {
    private static final Logger LOGGER = LoggerFactory.getLogger("NavigationCourses");
    private static final NavEngine ENGINE = NavEngine.BARITONE;
    /** {@code STOP_DISTANCE} (3.0) plus the arrival slack (0.5) of {@code FollowTask}. */
    private static final double ARRIVED = 3.6D;
    /** How far above or below the followed player the follower may stand and still count as having reached them. */
    private static final double LEVEL = 1.6D;
    private static final double AT_GOAL = 1.7D;
    private static final double LOOP_SPEED = 0.2D;
    /** Ticks a follower may wait within range but on the wrong level before the run gives up on the leg. */
    private static final int WRONG_LEVEL_TICKS = 120;
    private static final int MOVE_SETTLE_TICKS = 80;

    private final GameTestHelper context;
    private final Course course;
    private final BaritoneEngineArena arena;
    private final List<Runner> runners = new ArrayList<>();
    private final List<Integer> legTicks = new ArrayList<>();
    private final List<long[]> forcedChunks = new ArrayList<>();
    private final Tracker tracker;
    /** Non-null only for the explicit P3 scale-one GameTest evidence mode. */
    private NavigationMeasurement.Run measurement;
    private AIPlayerEntity holder;
    private int tick;
    private int leg;
    private int legStartTick;
    private boolean done;
    // moving target statistics
    private int loopTicksAfterCatchUp;
    private int closeTicks;
    private double gapSum;
    private double maxGapAfterCatchUp;
    private boolean caughtUp;
    private int lostTicks;

    private static final class Runner {
        final AIPlayerEntity bot;
        final String name;
        FollowTask follow;
        MoveTask move;
        float lastHealth;
        double damage;
        int waterTicks;
        int lavaTicks;
        int wrongLevelTicks;
        int settleTicks;
        double walked;
        net.minecraft.world.phys.Vec3 lastPos;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        boolean dead;

        Runner(AIPlayerEntity bot, String name) {
            this.bot = bot;
            this.name = name;
            this.lastHealth = bot.getHealth();
        }
    }

    private NavigationCourseRun(GameTestHelper context, Course course) {
        this.context = context;
        this.course = course;
        ServerLevel world = context.getLevel();
        BlockPos origin = context.absolutePos(new BlockPos(8, 60 + 12 * course.layer, 8));
        forceChunks(world, origin, course.halfX + 2, course.halfZ + 2);
        this.arena = BaritoneEngineArena.build(context, course.layer, course.halfX, course.halfZ, course.floorDepth);
        course.build.accept(arena);
        clearItems();
        this.tracker = new Tracker(arena, course.halfX, course.halfZ, course.floorDepth);
    }

    static void run(GameTestHelper context, Course course) {
        NavigationCourseRun run = new NavigationCourseRun(context, course);
        run.start();
    }

    // ---------------------------------------------------------------------------------------------------------------

    private String botName(int index) {
        return "Nc" + course.id + "B" + (course.starts.length > 1 ? Integer.toString(index + 1) : "");
    }

    private void start() {
        arena.require(MinecraftAiConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL, "fixture is not running under strict_survival");
        int[] first = course.legs[0];
        if (course.mode == Mode.FOLLOW) {
            int[] at = course.moving ? loopPoint(0) : first;
            holder = arena.spawnHolder(botName(0) + "T", arena.cell(at[0], at[1], at[2]));
        }
        for (int i = 0; i < course.starts.length; i++) {
            int[] s = course.starts[i];
            BlockPos feet = arena.cell(s[0], s[1], s[2]);
            AIPlayerEntity bot = arena.spawnOnBaritone(botName(i), feet);
            if (course.pickaxe) {
                bot.getInventory().setItem(0, new ItemStack(Items.STONE_PICKAXE));
                bot.getInventory().setSelectedSlot(0);
            }
            Runner runner = new Runner(bot, botName(i));
            runners.add(runner);
        }
        measurement = NavigationMeasurement.startScaleOneGameTest(course.id, ENGINE,
                runners.stream().map(runner -> runner.bot.getUUID()).toList());
        if (measurement != null) {
            for (Runner runner : runners) {
                NavigationMeasurement.noteEffectiveEngine(runner.bot.getUUID(),
                        NavEngineSelector.effectiveFor(runner.bot.getUUID()));
            }
        }
        for (Runner r : runners) {
            if (course.mode == Mode.FOLLOW) {
                r.follow = new FollowTask(holder.getGameProfile().name());
                TaskManager.INSTANCE.assign(r.bot, r.follow, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_course_" + course.id));
            } else {
                assignMove(r);
            }
        }
        legStartTick = 0;
        context.failIfEver(this::tick);
    }

    private void assignMove(Runner r) {
        int[] goal = course.legs[leg];
        r.move = new MoveTask(r.bot, arena.cell(goal[0], goal[1], goal[2]));
        r.settleTicks = 0;
        TaskManager.INSTANCE.assign(r.bot, r.move, TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_course_" + course.id + "_leg" + leg));
    }

    // ---------------------------------------------------------------------------------------------------------------

    private void tick() {
        if (done) {
            return;
        }
        int now = ++tick;
        if (course.moving && holder != null) {
            moveHolder(now);
        }
        for (Runner r : runners) {
            AIPlayerEntity bot = r.bot;
            if (measurement != null) {
                NavigationMeasurement.noteEffectiveEngine(bot.getUUID(), NavEngineSelector.effectiveFor(bot.getUUID()));
            }
            if (!bot.isAlive() || bot.isRemoved()) {
                r.dead = true;
                end("died", "bot " + r.name + " is dead or removed at " + describePosition(bot));
                return;
            }
            float health = bot.getHealth();
            if (health < r.lastHealth) {
                r.damage += r.lastHealth - health;
            }
            r.lastHealth = health;
            if (bot.isInWater()) {
                r.waterTicks++;
            }
            if (bot.isInLava()) {
                r.lavaTicks++;
            }
            if (r.lastPos != null) {
                r.walked += r.lastPos.distanceTo(bot.position());
            }
            r.lastPos = bot.position();
            r.minY = Math.min(r.minY, bot.getY() - arena.origin.getY());
            r.maxX = Math.max(r.maxX, bot.getX() - arena.origin.getX());
            tracker.sample(bot);
        }
        if (now >= course.budget) {
            timeout();
            return;
        }
        if (course.mode == Mode.FOLLOW) {
            followProgress(now);
        } else {
            moveProgress(now);
        }
    }

    private void moveHolder(int now) {
        double[] p = loopPointExact(now * LOOP_SPEED);
        double stop = 400.0D * LOOP_SPEED;
        if (now * LOOP_SPEED > stop) {
            p = loopPointExact(stop);
        }
        holder.teleportTo(arena.world, arena.origin.getX() + p[0] + 0.5D, arena.origin.getY(), arena.origin.getZ() + p[1] + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        holder.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
    }

    /** The loop the followed player walks: x -8..8, z -4..4, counter-clockwise from (-8, -4); {@code s} is the distance walked. */
    private static double[] loopPointExact(double s) {
        double w = 16.0D;
        double h = 8.0D;
        double perimeter = 2.0D * (w + h);
        double t = s % perimeter;
        if (t < w) {
            return new double[] {-8.0D + t, -4.0D};
        }
        t -= w;
        if (t < h) {
            return new double[] {8.0D, -4.0D + t};
        }
        t -= h;
        if (t < w) {
            return new double[] {8.0D - t, 4.0D};
        }
        t -= w;
        return new double[] {-8.0D, 4.0D - t};
    }

    private static int[] loopPoint(int s) {
        double[] p = loopPointExact(s);
        return new int[] {(int) Math.floor(p[0]), 0, (int) Math.floor(p[1])};
    }

    // ---------------------------------------------------------------------------------------------------------------

    private boolean followerArrived(Runner r) {
        return r.follow.isWaiting() && r.bot.distanceTo(holder) <= ARRIVED && Math.abs(r.bot.getY() - holder.getY()) <= LEVEL;
    }

    private void followProgress(int now) {
        boolean all = true;
        for (Runner r : runners) {
            FollowTask follow = r.follow;
            if (follow.state() != TaskState.RUNNING) {
                end("task_failed", r.name + " follow " + follow.state() + " " + follow.failureReason());
                return;
            }
            boolean arrived = followerArrived(r);
            all &= arrived;
            if (follow.isWaiting() && r.bot.distanceTo(holder) <= ARRIVED && Math.abs(r.bot.getY() - holder.getY()) > LEVEL) {
                if (++r.wrongLevelTicks >= WRONG_LEVEL_TICKS && course.expect != Expect.HOLD) {
                    end("settled_on_wrong_level", r.name + " waits within range but " + String.format(Locale.ROOT, "%.1f", holder.getY() - r.bot.getY())
                            + " blocks below the player, at " + describePosition(r.bot));
                    return;
                }
            } else {
                r.wrongLevelTicks = 0;
            }
        }
        if (course.moving) {
            movingStats(now);
            // The player stops after 400 ticks; the run ends when every follower has settled next to them.
            if (now > 420 && all) {
                completeLeg(now);
            }
            return;
        }
        if (all) {
            completeLeg(now);
        }
    }

    private void movingStats(int now) {
        Runner r = runners.get(0);
        double gap = r.bot.distanceTo(holder);
        if (!caughtUp && gap <= ARRIVED) {
            caughtUp = true;
        }
        if (caughtUp) {
            loopTicksAfterCatchUp++;
            gapSum += gap;
            maxGapAfterCatchUp = Math.max(maxGapAfterCatchUp, gap);
            if (gap <= 4.5D) {
                closeTicks++;
            }
            if (gap > 8.0D) {
                lostTicks++;
            }
        }
    }

    private void completeLeg(int now) {
        legTicks.add(now - legStartTick);
        if (leg + 1 >= course.legs.length) {
            end(null, "");
            return;
        }
        leg++;
        legStartTick = now;
        int[] next = course.legs[leg];
        arena.teleportTo(holder, arena.cell(next[0], next[1], next[2]));
    }

    private void moveProgress(int now) {
        Runner r = runners.get(0);
        MoveTask move = r.move;
        if (move.state() == TaskState.COMPLETED) {
            if (r.bot.getActionPack().hasBaritoneRoute() && ++r.settleTicks < MOVE_SETTLE_TICKS) {
                return;
            }
            int[] goal = course.legs[leg];
            double distance = r.bot.position().distanceTo(arena.cell(goal[0], goal[1], goal[2]).getCenter());
            if (distance > AT_GOAL + 0.6D) {
                end("completed_short_of_goal", "move completed " + String.format(Locale.ROOT, "%.1f", distance) + " blocks from the goal, at " + describePosition(r.bot));
                return;
            }
            legTicks.add(now - legStartTick);
            if (leg + 1 >= course.legs.length) {
                end(null, "");
                return;
            }
            leg++;
            legStartTick = now;
            assignMove(r);
        } else if (move.state() != TaskState.RUNNING) {
            end("task_failed", "move " + move.state() + " " + move.failureReason());
        }
    }

    private void timeout() {
        String reason = "timeout";
        String detail = "";
        if (course.expect == Expect.HOLD) {
            reason = "held_no_route";
        }
        for (Runner each : runners) {
            detail += each.name + ": " + describeTask(each) + "; ";
        }
        end(reason, detail);
    }

    private String describeTask(Runner r) {
        StringBuilder text = new StringBuilder();
        if (r.follow != null) {
            text.append("follow=").append(r.follow.state()).append(r.follow.failureReason() == null ? "" : ":" + r.follow.failureReason())
                    .append(" waiting=").append(r.follow.isWaiting())
                    .append(" notices=").append(r.follow.noRouteNotices())
                    .append(" baritoneStarts=").append(r.follow.baritoneStarts())
                    .append(" regoals=").append(r.follow.baritoneRegoals())
                    .append(" directWalks=").append(r.follow.directWalkCount())
                    .append(" digOut=").append(r.follow.digOutActive());
        }
        if (r.move != null) {
            text.append("move=").append(r.move.state()).append(r.move.failureReason() == null ? "" : ":" + r.move.failureReason())
                    .append(" ").append(r.move.describe());
        }
        NavOutcome outcome = r.bot.getActionPack().lastRouteOutcome();
        text.append(" lastRoute=").append(outcome == null ? "none" : outcome.status() + (outcome.reason() == null ? "" : ":" + outcome.reason()))
                .append(" at=").append(describePosition(r.bot));
        if (holder != null) {
            text.append(" toTarget=").append(String.format(Locale.ROOT, "%.1f", r.bot.distanceTo(holder)));
        }
        return text.toString();
    }

    private String describePosition(AIPlayerEntity bot) {
        return String.format(Locale.ROOT, "(%.1f, %.1f, %.1f)", bot.getX() - arena.origin.getX(), bot.getY() - arena.origin.getY(),
                bot.getZ() - arena.origin.getZ());
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** Ends the run: {@code failure == null} is a reach. Records the result, asserts, cleans up. */
    private void end(String failure, String detail) {
        if (done) {
            return;
        }
        done = true;
        tracker.finalScan();
        boolean reached = failure == null;
        double damage = 0.0D;
        int water = 0;
        int lava = 0;
        int baritoneStarts = 0;
        boolean baritoneInstance = true;
        for (Runner r : runners) {
            damage += r.damage;
            water += r.waterTicks;
            lava += r.lavaTicks;
            if (r.follow != null) {
                baritoneStarts += r.follow.baritoneStarts();
            }
            baritoneInstance &= BaritoneRegistry.INSTANCE.find(r.bot.getUUID()) != null;
        }
        int ticks = reached ? tick : course.budget;
        String reason = reached ? "-" : failure;
        StringBuilder extra = new StringBuilder();
        extra.append("legs=").append(legTicks);
        extra.append(" minDy=").append(String.format(Locale.ROOT, "%.1f", runners.get(0).minY));
        extra.append(" walked=").append(String.format(Locale.ROOT, "%.0f", runners.get(0).walked));
        extra.append(" maxDx=").append(String.format(Locale.ROOT, "%.1f", runners.get(0).maxX));
        extra.append(" baritoneStarts=").append(baritoneStarts);
        extra.append(" baritoneInstance=").append(baritoneInstance);
        if (course.moving) {
            extra.append(" caughtUp=").append(caughtUp);
            extra.append(" meanGap=").append(String.format(Locale.ROOT, "%.2f", loopTicksAfterCatchUp == 0 ? -1.0D : gapSum / loopTicksAfterCatchUp));
            extra.append(" maxGap=").append(String.format(Locale.ROOT, "%.1f", maxGapAfterCatchUp));
            extra.append(" within4_5=").append(loopTicksAfterCatchUp == 0 ? 0 : 100 * closeTicks / loopTicksAfterCatchUp).append('%');
            extra.append(" lostTicks=").append(lostTicks);
        }
        String text = String.join("\t", "NAVCOURSE", course.id, ENGINE.name().toLowerCase(Locale.ROOT), Boolean.toString(reached), Integer.toString(ticks),
                String.format(Locale.ROOT, "%.1f", damage), Integer.toString(tracker.broken), Integer.toString(tracker.placed),
                Integer.toString(water), Integer.toString(lava), reason, (detail + " | " + extra).replace('\t', ' ').replace('\n', ' '));
        LOGGER.info(text);
        append(text);
        for (Runner r : runners) {
            BotLog.path(r.bot, "nav_course_result", "course", course.id, "engine", ENGINE.name(), "reached", reached, "ticks", ticks,
                    "damage", r.damage, "broken", tracker.broken, "placed", tracker.placed, "water_ticks", r.waterTicks,
                    "reason", reason, "detail", describeTask(r));
        }

        NavigationMeasurement.Snapshot measurementSnapshot = null;
        boolean measurementPersisted = true;
        if (measurement != null) {
            measurementSnapshot = NavigationMeasurement.finish(measurement,
                    new NavigationMeasurement.Outcome(reached, ticks, damage, tracker.broken, tracker.placed, water, lava, reason));
            measurement = null;
            measurementPersisted = appendMeasurement(measurementSnapshot);
        }

        List<String> violations = new ArrayList<>();
        if (measurementSnapshot != null && !measurementPersisted) {
            violations.add("could not persist scale-one navigation evidence");
        }
        if (measurementSnapshot != null && !measurementSnapshot.hasRequiredEvidence()) {
            violations.add("incomplete scale-one navigation evidence: " + measurementSnapshot.evidenceProblem());
        }
        if (damage > 0.001D) {
            violations.add("took " + damage + " damage");
        }
        if (water > 0) {
            violations.add("was in water for " + water + " ticks");
        }
        if (lava > 0) {
            violations.add("was in lava for " + lava + " ticks");
        }
        if ("died".equals(failure)) {
            violations.add("died");
        }
        if (course.expect == Expect.REACH && !reached) {
            violations.add("did not reach the target (" + failure + "): " + detail);
        }
        if (course.expect == Expect.HOLD) {
            if (reached) {
                violations.add("reached a target that has no dry route");
            }
            for (Runner r : runners) {
                if (r.maxX >= 0.5D) {
                    violations.add(r.name + " crossed the moat, max x offset " + r.maxX);
                }
                if (course.notice && r.follow != null && r.follow.noRouteNotices() < 1) {
                    violations.add("the player was never told there is no dry route");
                }
                if (course.notice && r.follow != null && r.follow.noRouteNotices() > 2) {
                    violations.add("the no-route notice repeats: " + r.follow.noRouteNotices());
                }
            }
        }
        if (course.moving && lostTicks > 0) {
            violations.add("the follower fell more than 8 blocks behind for " + lostTicks + " ticks");
        }
        if (course.noBreak && tracker.broken > 0) {
            violations.add("broke " + tracker.broken + " block(s) although a walkable way exists");
        }
        if (course.pickaxe && course.id.equals("sealed") && reached && tracker.broken < 1) {
            violations.add("got through a sealed wall without breaking anything?");
        }
        if (course.mode == Mode.FOLLOW && baritoneStarts < 1) {
            violations.add("no Baritone route was ever started");
        }
        if (!baritoneInstance) {
            violations.add("a bot has no Baritone instance");
        }
        cleanup();
        if (!violations.isEmpty()) {
            String message = "course " + course.id + " on " + ENGINE + ": " + String.join("; ", violations);
            context.fail(Component.nullToEmpty(message));
            throw new IllegalStateException(message);
        }
        context.succeed();
    }

    private void cleanup() {
        for (Runner r : runners) {
            TaskManager.INSTANCE.cancelIntentTasks(r.bot, "gametest_complete");
            AIPlayerManager.INSTANCE.despawn(arena.world.getServer(), r.bot.getGameProfile().name());
        }
        if (holder != null) {
            TaskManager.INSTANCE.cancelIntentTasks(holder, "gametest_complete");
            AIPlayerManager.INSTANCE.despawn(arena.world.getServer(), holder.getGameProfile().name());
        }
        clearItems();
        for (long[] chunk : forcedChunks) {
            arena.world.setChunkForced((int) chunk[0], (int) chunk[1], false);
        }
    }

    private void forceChunks(ServerLevel world, BlockPos origin, int radiusX, int radiusZ) {
        for (int cx = (origin.getX() - radiusX) >> 4; cx <= (origin.getX() + radiusX) >> 4; cx++) {
            for (int cz = (origin.getZ() - radiusZ) >> 4; cz <= (origin.getZ() + radiusZ) >> 4; cz++) {
                world.setChunkForced(cx, cz, true);
                forcedChunks.add(new long[] {cx, cz});
            }
        }
    }

    private void clearItems() {
        AABB box = new AABB(arena.origin).inflate(course.halfX + 2, course.floorDepth + 8, course.halfZ + 2);
        for (Entity item : arena.world.getEntitiesOfClass(ItemEntity.class, box)) {
            item.discard();
        }
    }

    private static void append(String line) {
        appendTo("results.tsv", line, "course result");
    }

    /** Writes an explicit unpaced/scale-one summary and one raw planner row per route planning call. */
    private static boolean appendMeasurement(NavigationMeasurement.Snapshot snapshot) {
        NavigationMeasurement.Stats engineTick = snapshot.engineTick();
        NavigationMeasurement.Stats serverTick = snapshot.serverTick();
        NavigationMeasurement.Stats plannerStats = snapshot.planner();
        NavigationMeasurement.DriverStats driver = snapshot.driver();
        NavigationMeasurement.Outcome outcome = snapshot.outcome();
        String summary = String.join("\t", "NAVMEASURE", "1", snapshot.environment(), snapshot.course(),
                snapshot.engine().name().toLowerCase(Locale.ROOT), Long.toString(snapshot.runId()),
                Long.toString(snapshot.pathfinderBudgetScale()), Boolean.toString(snapshot.engineIsolated()),
                Boolean.toString(outcome.reached()), Integer.toString(outcome.ticks()), format(outcome.damage()),
                Integer.toString(outcome.broken()), Integer.toString(outcome.placed()), Integer.toString(outcome.waterTicks()),
                Integer.toString(outcome.lavaTicks()), clean(outcome.reason()),
                Integer.toString(engineTick.count()), format(engineTick.avgMs()), format(engineTick.p95Ms()), format(engineTick.maxMs()),
                Integer.toString(serverTick.count()), format(serverTick.avgMs()), format(serverTick.p95Ms()), format(serverTick.maxMs()),
                Integer.toString(plannerStats.count()), format(plannerStats.avgMs()), format(plannerStats.p95Ms()), format(plannerStats.maxMs()),
                Integer.toString(driver.baritoneDriverTicks()), Integer.toString(driver.actionPackUpdateTicks()),
                Integer.toString(driver.baritoneFallbacks()));
        LOGGER.info(summary);
        boolean persisted = appendTo("measurements.tsv", summary, "navigation measurement");
        for (NavigationMeasurement.PlannerSample plan : snapshot.planners()) {
            String planner = String.join("\t", "NAVPLAN", "1", snapshot.environment(), snapshot.course(),
                    snapshot.engine().name().toLowerCase(Locale.ROOT), Long.toString(snapshot.runId()), clean(plan.phase()),
                    format(plan.wallMs()), Long.toString(plan.reportedMs()), Integer.toString(plan.nodes()),
                    Integer.toString(plan.moves()), clean(plan.outcome()));
            LOGGER.info(planner);
            persisted &= appendTo("planner.tsv", planner, "navigation planner measurement");
        }
        return persisted;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String clean(String value) {
        return value == null ? "-" : value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static boolean appendTo(String name, String line, String description) {
        try {
            Path file = FabricLoader.getInstance().getGameDir().resolve("nav_courses").resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return true;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("could not append the {}: {}", description, e.toString());
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Counts the blocks the bot broke and placed: the arena volume is snapshotted (block and "solid" = neither air nor fluid) and the
     * cells around each bot are compared with it every tick (a full comparison at the end catches the rest). A solid cell that
     * turned non-solid is a break; a non-solid cell that turned solid is a placement; a solid cell replaced by another block counts
     * as both. Doors and gates opening are state changes of the same block and are not counted.
     */
    private static final class Tracker {
        private final BaritoneEngineArena arena;
        private final int minX;
        private final int minY;
        private final int minZ;
        private final int sx;
        private final int sy;
        private final int sz;
        private final Block[] blocks;
        private final boolean[] solid;
        int broken;
        int placed;

        Tracker(BaritoneEngineArena arena, int halfX, int halfZ, int floorDepth) {
            this.arena = arena;
            this.minX = -halfX;
            this.minZ = -halfZ;
            this.minY = -floorDepth;
            this.sx = 2 * halfX + 1;
            this.sz = 2 * halfZ + 1;
            this.sy = floorDepth + BaritoneEngineArena.CEILING + 1;
            this.blocks = new Block[sx * sy * sz];
            this.solid = new boolean[sx * sy * sz];
            for (int x = 0; x < sx; x++) {
                for (int y = 0; y < sy; y++) {
                    for (int z = 0; z < sz; z++) {
                        BlockState state = arena.world.getBlockState(arena.cell(minX + x, minY + y, minZ + z));
                        int i = index(x, y, z);
                        blocks[i] = state.getBlock();
                        solid[i] = isSolid(state);
                    }
                }
            }
        }

        private int index(int x, int y, int z) {
            return (x * sy + y) * sz + z;
        }

        private static boolean isSolid(BlockState state) {
            return !state.isAir() && state.getFluidState().isEmpty();
        }

        void sample(AIPlayerEntity bot) {
            int cx = bot.blockPosition().getX() - arena.origin.getX();
            int cy = bot.blockPosition().getY() - arena.origin.getY();
            int cz = bot.blockPosition().getZ() - arena.origin.getZ();
            scan(cx - 5, cx + 5, cy - 3, cy + 4, cz - 5, cz + 5);
        }

        void finalScan() {
            scan(minX, minX + sx - 1, minY, minY + sy - 1, minZ, minZ + sz - 1);
        }

        private void scan(int fromX, int toX, int fromY, int toY, int fromZ, int toZ) {
            for (int dx = Math.max(fromX, minX); dx <= Math.min(toX, minX + sx - 1); dx++) {
                for (int dy = Math.max(fromY, minY); dy <= Math.min(toY, minY + sy - 1); dy++) {
                    for (int dz = Math.max(fromZ, minZ); dz <= Math.min(toZ, minZ + sz - 1); dz++) {
                        BlockState state = arena.world.getBlockState(arena.cell(dx, dy, dz));
                        int i = index(dx - minX, dy - minY, dz - minZ);
                        boolean nowSolid = isSolid(state);
                        Block block = state.getBlock();
                        if (solid[i] == nowSolid && (!nowSolid || blocks[i] == block)) {
                            continue;
                        }
                        if (solid[i]) {
                            broken++;
                        }
                        if (nowSolid) {
                            placed++;
                        }
                        solid[i] = nowSolid;
                        blocks[i] = block;
                    }
                }
            }
        }
    }
}
