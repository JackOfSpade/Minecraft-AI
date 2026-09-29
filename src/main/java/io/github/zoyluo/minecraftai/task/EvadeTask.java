package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogCategory;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.util.BlockPosText;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
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

    private final Threat threat;
    private BlockPos escapeGoal;

    /**
     * How far one escape leg must carry the bot from {@code source}. The generic leg is twelve
     * blocks; a warden's sonic boom reaches fifteen (twenty vertically) and ignores armour, so a
     * flight from a warden must end outside that range or it only relocates the bot inside it.
     */
    static int escapeDistanceFor(LivingEntity source) {
        return source instanceof net.minecraft.world.entity.monster.warden.Warden
                ? CombatCore.WARDEN_ESCAPE_DISTANCE : ESCAPE_DISTANCE;
    }

    public EvadeTask(Threat threat) {
        this.threat = threat;
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
        startBestEscapePath(bot);
        // Escaping must sprint: walking at 4.3m/s against a zombie's 4.0m/s pursuit speed is only
        // marginally faster, and pathfinding around obstacles or startup delay lets it close in and
        // grind the bot down at melee range (field-tested: an unequipped bot on a nighttime
        // expedition was chased down and killed by a zombie). Only sprinting at 5.6m/s actually
        // shakes it off.
        // escapeGoal==null (nowhere to flee) -> do not start pathfinding; onTick fails on the first
        // tick and hands off to the wall-building escalation.
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (escapeGoal == null) {
            // Nowhere to flee (deep in a tunnel / surrounded) -> fail cleanly and hand off to
            // DangerWatcher's wall-building self-defense escalation, rather than falsely completing
            // and idling while taking hits.
            failNoValidEscapeRoute(bot, "initial");
            return;
        }
        bot.getActionPack().setSprinting(true); // keep this set continuously (other controllers may reset it every tick)
        if (bot.blockPosition().distSqr(escapeGoal) <= GOAL_REACHED_SQUARED) {
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
        if (elapsed > 400) {
            bot.getActionPack().stopAll();
            fail("evade_timeout");
        }
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
        escapeGoal = admitBestSurfaceEscapePath(
                bot, threat.entity(), threat.pos(), escapeDistanceFor(threat.entity()));
        return escapeGoal != null;
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
                // Sprint belongs only to an admitted live escape path. Leaving it enabled after a
                // failed admission makes ActionPack permanently busy and blocks paused work resume.
                bot.getActionPack().setSprinting(true);
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

    private static List<BlockPos> chooseGoals(AIPlayerEntity bot,
                                               LivingEntity source,
                                               BlockPos rememberedSource,
                                               int distance) {
        Vec3 away = new Vec3(1.0D, 0.0D, 0.0D);
        if (source != null
                && source.isAlive()
                && ObservableWorldQuery.canObserveEntity(bot, source)) {
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
        List<BlockPos> goals = new ArrayList<>();
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
            if (goal != null
                    && bot.blockPosition().distSqr(goal) > GOAL_REACHED_SQUARED
                    && !goals.contains(goal)) {
                goals.add(goal);
            }
        }
        return goals;
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
                || !ObservableWorldQuery.canObserveEntity(bot, source)) {
            return false;
        }
        // Creepers are never a melee target and can close the ordinary ten-block contact envelope
        // while mission work reverses direction. Continue until this exact source leaves factual
        // perception. Other mobs use the shared close/ranged combat pressure boundary.
        return source instanceof Creeper
                || CombatCore.isWithinHostilePressureEnvelope(bot, source);
    }
}
