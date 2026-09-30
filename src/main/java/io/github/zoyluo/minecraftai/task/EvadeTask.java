package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.Gait;
import io.github.zoyluo.minecraftai.action.PaceOwner;
import io.github.zoyluo.minecraftai.action.QuietZone;
import io.github.zoyluo.minecraftai.entity.RecentDamage;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogCategory;
import io.github.zoyluo.minecraftai.manager.AIPlayerManager;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.util.BlockPosText;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.phys.Vec3;

public final class EvadeTask extends AbstractTask {
    private static final double GOAL_REACHED_SQUARED = 6.25D;
    private static final int MAX_PATH_ADMISSION_ATTEMPTS = 5;
    // One twelve-block ring lets the fixed A* budget examine every direction exactly once. Moving
    // pressure is handled by another leg at the arrival boundary; longer rings only starved the
    // fifth direction behind four disconnected twenty-block endpoints.
    private static final int ESCAPE_DISTANCE = 12;
    private static final double[] ESCAPE_ANGLES = {
            0.0D,
            Math.PI / 4.0D,
            -Math.PI / 4.0D,
            Math.PI / 2.0D,
            -Math.PI / 2.0D
    };

    /** The flight budget in ticks of a sprint: a sprinting tick spends one, a sneaking one spends {@code 1 / SNEAK_BUDGET_FACTOR}. */
    private static final int BUDGET_TICKS = 400;
    /** A sneak leg covers about this many times less ground per tick than a sprint (1.3 vs 5.6 blocks per second): 400 -> 1600 ticks. */
    private static final int SNEAK_BUDGET_FACTOR = 4;
    /** Candidate ranking of the escape fan: the straight-away direction first, then the 45 and the 90 degree ones. */
    private static final double ANGLE_STEP_PENALTY = 0.1D;
    /** How much a direction toward the owner is worth (times the cosine of the angle to the owner). */
    static final double OWNER_BIAS = 0.3D;

    private final Threat threat;
    private final FollowEscort escort = new FollowEscort();
    private BlockPos escapeGoal;
    /** Set while the flight is a Baritone run-away goal: the cell it runs from and how far from it the flight is over. */
    private BlockPos runAwayFrom;
    private int runAwayDistance;
    private double budget;
    private Gait lastGait;

    /**
     * How far one escape leg must carry the bot from {@code source}. The generic leg is twelve
     * blocks; a warden's sonic boom reaches fifteen (twenty vertically) and ignores armour, so a
     * flight from a warden must end outside that range or it only relocates the bot inside it.
     */
    static int escapeDistanceFor(LivingEntity source) {
        return source instanceof net.minecraft.world.entity.monster.warden.Warden
                ? CombatCore.WARDEN_ESCAPE_DISTANCE : ESCAPE_DISTANCE;
    }

    /**
     * How the bot moves while it flees {@code source}: it creeps (SNEAK, silent) from a CALM warden, which sniffs only what it can
     * notice and is not chasing anything; every other flight is a sprint, and so is one from a warden that hunts the bot or its owner
     * (see {@link WardenState}: synced anger, a roar, a recent hit) or while the bot is taking damage.
     */
    static Gait fleeGait(AIPlayerEntity bot, LivingEntity source) {
        if (source instanceof Warden warden
                && MinecraftAiConfig.get().behaviour().wardenOrDefaults().sneakAwayEnabled()) {
            long now = bot.level().getGameTime();
            if (WardenState.isCalm(warden, QuietZone.victimsOf(bot), now)
                    && !RecentDamage.tookEntityDamage(bot.getUUID(), now, io.github.zoyluo.minecraftai.action.PacePolicy.DAMAGE_WINDOW_TICKS)) {
                return Gait.SNEAK;
            }
        }
        return Gait.SPRINT;
    }

    /** Who asks for the flee gait: the warden logic for a warden (it outranks the pressure and the calm-warden cap), else evade. */
    private static PaceOwner fleeOwner(LivingEntity source) {
        return source instanceof Warden ? PaceOwner.WARDEN : PaceOwner.EVADE;
    }

    /** Asks for the flee gait as a route lease, for the route that has just been started (starting a route ends the previous lease). */
    private static Gait requestFleeRoutePace(AIPlayerEntity bot, LivingEntity source) {
        Gait gait = fleeGait(bot, source);
        if (MinecraftAiConfig.get().behaviour().paceOrDefaults().paceEnabled()) {
            bot.getActionPack().requestRoutePace(gait, fleeOwner(source));
        } else {
            // pace.enabled=false answers what the code did before the pace policy: the task flag decides.
            bot.getActionPack().setSprinting(true);
        }
        return gait;
    }

    public EvadeTask(Threat threat) {
        this.threat = threat;
    }

    /** Package-visible for GameTests: the current leg is a Baritone run-away goal (not a hand-made escape goal). */
    boolean usesRunAway() {
        return runAwayFrom != null;
    }

    /** Package-visible for GameTests: swings the retreat escort has landed. */
    int escortStrikes() {
        return escort.strikes();
    }

    @Override
    public String name() {
        return "evade";
    }

    @Override
    public String describe() {
        return "Evading " + threat.type() + " toward " + (escapeGoal == null ? "(pending)" : BlockPosText.compact(escapeGoal));
    }

    @Override
    public double progress() {
        return state == TaskState.COMPLETED ? 1.0D : Math.min(0.95D, elapsed / 160.0D);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        escort.reset();
        startBestEscapePath(bot);
        // Escaping sprints (walking at 4.3m/s against a zombie's 4.0m/s pursuit speed is only marginally faster; only
        // 5.6m/s actually shakes it off, field-tested), except from a CALM warden, which is crept away from: see fleeGait.
        // escapeGoal==null (nowhere to flee) -> do not start pathfinding; onTick fails on the first
        // tick and hands off to the wall-building escalation.
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        flee(bot);
        // Like a follower, a retreating bot only knocks back what is in its melee reach, on a ready tick, and never turns the flight
        // around (FollowEscort never paths toward the mob; it is silent next to a calm warden, and never touches a creeper or a warden).
        if (state == TaskState.RUNNING) {
            escort.tick(bot, null);
        }
    }

    private void flee(AIPlayerEntity bot) {
        if (escapeGoal == null) {
            // Nowhere to flee (deep in a tunnel / surrounded) -> fail cleanly and hand off to
            // DangerWatcher's wall-building self-defense escalation, rather than falsely completing
            // and idling while taking hits.
            failNoValidEscapeRoute(bot, "initial");
            return;
        }
        Gait gait = keepFleeGait(bot);
        // A sneak leg covers a quarter of the ground of a sprint, so it may last four times as long (400 -> 1600 ticks).
        budget += gait == Gait.SNEAK ? 1.0D / SNEAK_BUDGET_FACTOR : 1.0D;
        if (reachedGoal(bot)) {
            // Reaching the originally projected point is not the same as escaping a moving mob.
            // The strict obsidian run reached its first waypoint while the same Creeper was still
            // visible, completed Evade, and immediately let AcquireWater path back toward it.
            // Keep ownership and project another factual surface leg while the observed source can
            // still apply pressure; this also avoids a pause/resume/cooldown gap between legs.
            if (hasObservedUnsettledPressure(bot)) {
                BlockPos previous = escapeGoal;
                if (!startBestEscapePath(bot)) {
                    failNoValidEscapeRoute(bot, "pressure_extend");
                    return;
                }
                BotLog.action(bot, "evade_pressure_extended",
                        "from_goal", previous,
                        "to_goal", escapeGoal,
                        "source", threat.entity().blockPosition());
                return;
            }
            DangerWatcher.INSTANCE.noteEvadeCompleted(bot);
            bot.getActionPack().stopAll();
            complete();
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle() && elapsed > 10) {
            if (!startBestEscapePath(bot)) {
                failNoValidEscapeRoute(bot, "repath");
                return;
            }
        }
        if (budget > BUDGET_TICKS) {
            bot.getActionPack().stopAll();
            fail("evade_timeout");
        }
    }

    /**
     * Renews the flee gait every tick (a tick lease, so a calm warden that starts to hunt turns the creep into a sprint on the very next
     * tick, and a hunting one that stops chasing turns it back), and re-issues the route lease when the gait changes.
     */
    private Gait keepFleeGait(AIPlayerEntity bot) {
        LivingEntity source = threat.entity();
        Gait gait = fleeGait(bot, source);
        if (!MinecraftAiConfig.get().behaviour().paceOrDefaults().paceEnabled()) {
            bot.getActionPack().setSprinting(true); // keep this set continuously (other controllers may reset it every tick)
            return gait;
        }
        PaceOwner owner = fleeOwner(source);
        bot.getActionPack().requestPace(gait, owner);
        if (gait != lastGait && !bot.getActionPack().isPathExecutorIdle()) {
            bot.getActionPack().requestRoutePace(gait, owner);
        }
        if (gait != lastGait) {
            BotLog.action(bot, "evade_gait", "gait", gait, "threat", threat.type(), "owner", owner);
        }
        lastGait = gait;
        return gait;
    }

    private boolean reachedGoal(AIPlayerEntity bot) {
        if (runAwayFrom != null) {
            // Exactly Baritone's GoalRunAway.isInGoal (horizontal distance between cells).
            long dx = bot.blockPosition().getX() - runAwayFrom.getX();
            long dz = bot.blockPosition().getZ() - runAwayFrom.getZ();
            return dx * dx + dz * dz >= (long) ((double) runAwayDistance * runAwayDistance);
        }
        return bot.blockPosition().distSqr(escapeGoal) <= GOAL_REACHED_SQUARED;
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        bot.getActionPack().stopAll();
    }

    private void failNoValidEscapeRoute(AIPlayerEntity bot, String stage) {
        // "no_valid_escape_route" is the same reason string from three different call sites
        // (initial admission, mid-escape pressure re-plan, and idle repath); without recording
        // which stage it was, a reader could not tell whether the bot never found a route at all
        // or lost one partway through an otherwise-successful escape.
        BotLog.warn(LogCategory.TASK, bot, "evade_no_escape_route", "stage", stage,
                "threat", threat.type());
        bot.getActionPack().stopAll();
        fail("no_valid_escape_route");
    }

    private boolean startBestEscapePath(AIPlayerEntity bot) {
        LivingEntity source = threat.entity();
        int distance = escapeDistanceFor(source);
        runAwayFrom = null;
        // On the Baritone engine the flight is Baritone's own run-away goal (it picks the way); the fan of hand-made goals below is
        // the legacy engine's, and what a refused run-away falls back to.
        BlockPos from = runAwaySource(bot, source);
        if (from != null) {
            double dx = bot.getX() - (from.getX() + 0.5D);
            double dz = bot.getZ() - (from.getZ() + 0.5D);
            // Each leg carries the bot the escape distance further from the source than it stands now (as a fan leg does), so a goal
            // that is already satisfied can never end a flight that is not over.
            int total = (int) Math.ceil(Math.sqrt(dx * dx + dz * dz)) + distance;
            ActionResult run = bot.getActionPack().startRunAwayFrom(from, total);
            if (!run.isFailed()) {
                requestFleeRoutePace(bot, source);
                runAwayFrom = from;
                runAwayDistance = total;
                Vec3 away = new Vec3(dx, 0.0D, dz).normalize();
                escapeGoal = BlockPos.containing(
                        from.getX() + 0.5D + away.x * total, bot.getY(), from.getZ() + 0.5D + away.z * total);
                BotLog.path(bot, "evade_run_away", "from", from, "distance", total, "threat", threat.type());
                return true;
            }
        }
        escapeGoal = admitBestSurfaceEscapePath(bot, source, threat.pos(), distance);
        return escapeGoal != null;
    }

    /** Where the flight runs from: the observed source, else where it was last seen; null when there is no spatial direction to flee. */
    private BlockPos runAwaySource(AIPlayerEntity bot, LivingEntity source) {
        BlockPos from = null;
        if (source != null && source.isAlive() && ObservableWorldQuery.canNoticeCreature(bot, source)) {
            from = source.blockPosition();
        } else if (threat.pos() != null) {
            from = threat.pos();
        }
        if (from == null) {
            return null;
        }
        double dx = bot.getX() - (from.getX() + 0.5D);
        double dz = bot.getZ() - (from.getZ() + 0.5D);
        return dx * dx + dz * dz < 0.01D ? null : from.immutable();
    }

    /**
     * Admits the first real surface-only route in the shared five-direction escape fan.
     * Callers receive the path executor's resolved goal, or {@code null} after every candidate
     * failed. Failure always releases all movement state so a safety owner can fail atomically.
     */
    static BlockPos admitBestSurfaceEscapePath(AIPlayerEntity bot,
                                                LivingEntity source,
                                                BlockPos rememberedSource,
                                                int distance) {
        int attempts = 0;
        for (BlockPos candidate : chooseGoals(bot, source, rememberedSource, distance)) {
            ActionResult path = bot.getActionPack().startSurfacePathTo(candidate);
            attempts++;
            if (!path.isFailed()) {
                // The flee gait (a sprint, or a creep from a calm warden) belongs only to an admitted live escape path, and is requested after
                // the route has started (starting a route ends the previous lease). A gait left behind after a failed admission would make
                // ActionPack permanently busy and block the resume of paused work; the failure path below calls stopAll, which clears it.
                requestFleeRoutePace(bot, source);
                BlockPos resolved = bot.getActionPack().activePathGoal();
                return resolved == null ? candidate : resolved.immutable();
            }
            if (attempts >= MAX_PATH_ADMISSION_ATTEMPTS) {
                break;
            }
        }
        bot.getActionPack().stopAll();
        return null;
    }

    static List<BlockPos> chooseGoals(AIPlayerEntity bot,
                                               LivingEntity source,
                                               BlockPos rememberedSource,
                                               int distance) {
        Vec3 away = new Vec3(1.0D, 0.0D, 0.0D);
        if (source != null
                && source.isAlive()
                && ObservableWorldQuery.canNoticeCreature(bot, source)) {
            away = bot.position().subtract(source.position());
        } else if (rememberedSource != null) {
            away = bot.position().subtract(Vec3.atCenterOf(rememberedSource));
        }
        // Escape is surface displacement, never vertical excavation. An entity-less LOW_HP used
        // to point at the bot's own block center; the only non-zero component was Y=-0.5, which
        // normalized into a destination twenty blocks underground. Flatten every source vector so
        // even defensive or future threat types cannot turn "run away" into "dig into a cave".
        away = new Vec3(away.x, 0.0D, away.z);
        if (away.lengthSqr() < 0.01D) {
            return List.of(); // no spatial direction is safer than inventing an arbitrary route
        }

        // A straight projection can land on a cliff or over a ravine even when a factual lateral
        // escape exists. Search one bounded twelve-block fan and give all five directions one path
        // admission; a still-observed moving threat causes the next leg to be projected later.
        Vec3 normalized = away.normalize();
        // The straight-away direction ranks first, the 45 degree ones next, the 90 degree ones last; a companion that can see its
        // owner adds OWNER_BIAS * cos(angle to the owner) to every direction that still increases the distance from the source, so
        // between two open sides it flees toward its owner instead of off on its own (the sort is stable: without an owner the
        // order is the fan's own).
        Vec3 toOwner = ownerDirection(bot);
        double sourceDistanceSquared = away.lengthSqr();
        Vec3 sourcePosition = bot.position().subtract(away);
        List<BlockPos> goals = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        int index = 0;
        for (double angle : ESCAPE_ANGLES) {
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            Vec3 direction = new Vec3(
                    normalized.x * cos - normalized.z * sin,
                    0.0D,
                    normalized.x * sin + normalized.z * cos);
            Vec3 horizontal = bot.position().add(direction.scale(Math.max(1, distance)));
            BlockPos base = new BlockPos(
                    net.minecraft.util.Mth.floor(horizontal.x),
                    bot.blockPosition().getY(),
                    net.minecraft.util.Mth.floor(horizontal.z));
            BlockPos goal = findStandableNear(bot, base);
            int step = (index + 1) / 2;
            index++;
            if (goal != null
                    && bot.blockPosition().distSqr(goal) > GOAL_REACHED_SQUARED
                    && !goals.contains(goal)) {
                double score = 1.0D - ANGLE_STEP_PENALTY * step;
                double gx = goal.getX() + 0.5D - sourcePosition.x;
                double gz = goal.getZ() + 0.5D - sourcePosition.z;
                if (toOwner != null && gx * gx + gz * gz > sourceDistanceSquared) {
                    score += OWNER_BIAS * (toOwner.x * direction.x + toOwner.z * direction.z);
                }
                // insertion by descending score, after equal scores (stable)
                int at = goals.size();
                while (at > 0 && scores.get(at - 1) < score) {
                    at--;
                }
                goals.add(at, goal);
                scores.add(at, score);
            }
        }
        return goals;
    }

    /** The horizontal unit vector from the bot to its owner when the owner is online in this level and in the bot's view, else null. */
    private static Vec3 ownerDirection(AIPlayerEntity bot) {
        if (bot.level().getServer() == null) {
            return null;
        }
        ServerPlayer owner = AIPlayerManager.INSTANCE.ownerOf(bot)
                .map(id -> bot.level().getServer().getPlayerList().getPlayer(id))
                .filter(player -> player != null && player.level() == bot.level() && player.isAlive())
                .orElse(null);
        if (owner == null || !ObservableWorldQuery.canObserveEntity(bot, owner)) {
            return null;
        }
        Vec3 delta = new Vec3(owner.getX() - bot.getX(), 0.0D, owner.getZ() - bot.getZ());
        return delta.lengthSqr() < 1.0D ? null : delta.normalize();
    }

    private static BlockPos findStandableNear(AIPlayerEntity bot, BlockPos base) {
        for (int radius = 0; radius <= 4; radius++) {
            for (BlockPos candidate : BlockPos.betweenClosed(
                    base.offset(-radius, -2, -radius), base.offset(radius, 2, radius))) {
                if (io.github.zoyluo.minecraftai.pathfinding.Standability.isStandable(
                        bot.level(), candidate)) {
                    return candidate.immutable();
                }
            }
        }
        return null;
    }

    private boolean hasObservedUnsettledPressure(AIPlayerEntity bot) {
        LivingEntity source = threat.entity();
        if (source == null
                || !DangerWatcher.isActiveHostileThreat(bot, source)
                || !ObservableWorldQuery.canNoticeCreature(bot, source)) {
            return false;
        }
        // Creepers are never a melee target and can close the ordinary ten-block contact envelope
        // while mission work reverses direction. Continue until this exact source leaves factual
        // perception. Other mobs use the shared close/ranged combat pressure boundary.
        return source instanceof Creeper
                || CombatCore.isWithinHostilePressureEnvelope(bot, source);
    }
}
