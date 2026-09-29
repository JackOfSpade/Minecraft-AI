package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.brain.BrainCoordinator;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogCategory;
import io.github.zoyluo.minecraftai.log.LogFields;
import io.github.zoyluo.minecraftai.mode.FakePlayerMotion;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * A standing follow order must never be permanently abandoned by {@link StuckWatcher}: a real
 * player watching the bot "give up" mid-follow (see the 2026-09-28 session log this was written
 * against -- FollowTask pathed down the bot's own old mining staircase, then sat wedged one
 * jump short of the surface for the entire 200-tick watchdog window before being aborted twice in
 * a row) is a worse outcome than the bot taking a few extra seconds to work itself free.
 *
 * <p>This is a self-contained mini state machine, mirrored on {@link ShelterExitDebtRepayer}'s
 * pattern: {@link FollowTask} calls {@link #tick} once per land-follow tick <em>before</em> its
 * ordinary path/walk logic. It watches the bot's own {@code BlockPos} -- not path/step "success"
 * reports, which the observed bug proved can misleadingly report progress while the bot's real
 * position never changes (a degenerate path/goal resolution can snap onto the bot's own stuck
 * cell and trivially "complete" a zero-length route). Once that real position has been frozen for
 * {@link RecoveryClock#stallTicksBeforeRecovery} ticks -- comfortably inside {@code StuckWatcher}'s
 * default 200-tick window, so this always gets there first -- it takes over.
 *
 * <p>Unlike the first version of this class, recovery no longer relies solely on single adjacent
 * steps: a detour that first has to move AWAY from the target (around a wall, out of a pit whose
 * only exit faces away) can never be found by a routine that only accepts steps which shorten the
 * distance. Recovery now alternates {@link RecoveryClock#alternateWindowTicks}-tick windows of
 * step attempts with windows that instead force a genuine fresh {@code AStarPathfinder} replan
 * (see {@link #tick}'s {@code FORCE_REPATH} handling) -- unlike the old jump-blocked corridor,
 * a real A* search already looks for any legal detour, not only ones that get closer every step.
 * The give-up/backoff phase, similarly, now forces a repath every
 * {@link RecoveryClock#lowCostRetryTicks} ticks instead of only ever retrying a single step.
 * Recovery resolves the instant the bot's real position changes OR the target itself closes the
 * distance by at least {@link #RESOLVE_DISTANCE_IMPROVEMENT} blocks (a moving player walking back
 * toward a genuinely stuck bot should not have to wait for the bot to free itself first).
 *
 * <p>{@link FollowTask} reports {@link Task#isWaiting()} for every tick this class actually
 * intercepts (a step-attempt window, or the backoff hold between forced repaths) -- the
 * documented, already-used way a task tells {@code StuckWatcher} "this is a deliberate
 * wait/reacquire state, not a stall". On a forced-repath tick, {@link #tick} returns {@code false}
 * (this tick is handed back to {@link FollowTask}'s own path logic, with its repath schedule
 * pulled forward via {@link #consumeForcedRepath()}) so {@code isWaiting()} reflects whatever the
 * ordinary follow logic decides, not a blanket "waiting" -- {@code StuckWatcher} drops its sample
 * outright on a waiting task either way, so it can never fire while this class still owns the
 * problem, and no change to {@code StuckWatcher} itself was needed or made.
 *
 * <p><b>Dig-out (the last step):</b> {@link #attemptStep} is the single place that enumerates and
 * executes a recovery move. It tries the verified-standable adjacent walk/step-up/step-down first;
 * only when none of those got anywhere does it hand over to {@link FollowDigOut}, which tunnels
 * through observable natural whitelist blocks with a tool that can break them (see its header for
 * the full safety envelope). A dig-out owns its ticks until it has gone through or given up; it is a
 * new kind of step, not a parallel state machine -- {@link RecoveryClock} still owns all the
 * timing/backoff decisions and needed no change.
 */
final class FollowStuckRecovery {
    /** Real position/target-distance improvement, in blocks, that resolves recovery outright. */
    private static final double RESOLVE_DISTANCE_IMPROVEMENT = 2.0D;

    private final RecoveryClock clock = new RecoveryClock(
            /* stallTicksBeforeRecovery= */ 100,
            /* giveUpTicks= */ 600,
            /* alternateWindowTicks= */ 40,
            /* lowCostRetryTicks= */ 100);

    private BlockPos lastPos;
    private double recoveryStartDistance = Double.NaN;
    private boolean forceRepathPending;
    private final FollowDigOut digOut = new FollowDigOut();

    /** True while a dig-out owns the bot (it is breaking blocks toward the player). */
    boolean isDigging() {
        return digOut.isActive();
    }

    void reset(AIPlayerEntity bot, int nowTick) {
        digOut.cancel(bot);
        lastPos = bot.blockPosition().immutable();
        recoveryStartDistance = Double.NaN;
        forceRepathPending = false;
        clock.reset(nowTick);
    }

    /**
     * @return true when this tick was handled by recovery (a step attempt, or a backoff hold
     *         between forced repaths) -- the caller must skip its own path logic and report
     *         {@code isWaiting()}; false when the bot is making real progress on its own, or when
     *         this tick is a forced-replan tick handed back to the caller's ordinary logic (see
     *         {@link #consumeForcedRepath()}).
     */
    boolean tick(AIPlayerEntity bot, ServerPlayer target, int elapsed, double minDistance) {
        BlockPos current = bot.blockPosition().immutable();
        boolean positionChanged = !current.equals(lastPos);
        if (positionChanged) {
            lastPos = current;
        }
        if (digOut.isActive()) {
            // A dig-out is deliberate progress in its own right (cells being broken and entered):
            // it owns the tick, and the stall clock must not count it as being stuck.
            clock.tick(elapsed, true);
            if (digOut.tick(bot)) {
                return true;
            }
            // Finished or abandoned this tick: hand it back to the ordinary path logic.
            lastPos = bot.blockPosition().immutable();
            forceRepathPending = true;
            return false;
        }
        boolean wasRecovering = clock.isRecovering();
        boolean targetCloser = wasRecovering
                && bot.distanceTo(target) <= recoveryStartDistance - RESOLVE_DISTANCE_IMPROVEMENT;
        boolean wasAnnounced = clock.hasAnnouncedStuck();

        RecoveryClock.Action action = clock.tick(elapsed, positionChanged || targetCloser);

        boolean nowRecovering = clock.isRecovering();
        if (wasRecovering && !nowRecovering) {
            BotLog.task(bot, "follow_recovery_resolved",
                    "recovery_ticks", elapsed - clock.lastRecoveryStartTick(),
                    "pos", LogFields.pos(current),
                    "reason", positionChanged ? "moved" : "target_closer");
        } else if (!wasRecovering && nowRecovering) {
            recoveryStartDistance = bot.distanceTo(target);
            BotLog.warn(LogCategory.TASK, bot, "follow_recovery_started",
                    "pos", LogFields.pos(current));
        }
        if (!wasAnnounced && clock.hasAnnouncedStuck()) {
            BotLog.warn(LogCategory.TASK, bot, "follow_recovery_gave_up", "pos", LogFields.pos(current));
            BrainCoordinator.INSTANCE.sendPanelChat(bot, "bot",
                    "I'm stuck and can't reach you right now. I'll keep trying.");
        }

        switch (action) {
            case STEP:
                attemptStep(bot, target, minDistance);
                return true;
            case FORCE_REPATH:
            case BACKOFF_FORCE_REPATH:
                bot.getActionPack().stopMovement();
                forceRepathPending = true;
                return false;
            case BACKOFF_HOLD:
                return true;
            case NOT_HANDLED:
            case RESOLVED:
            default:
                return false;
        }
    }

    /**
     * @return true exactly once per forced-replan tick (see {@link RecoveryClock.Action#FORCE_REPATH}):
     *         the caller must pull its own repath schedule forward to run this same tick, so the
     *         replan {@link #tick} just cleared the path for actually happens, instead of a stale
     *         schedule silently skipping it until the next periodic repath.
     */
    boolean consumeForcedRepath() {
        boolean pending = forceRepathPending;
        forceRepathPending = false;
        return pending;
    }

    /**
     * Tries every {@code Standability}-verified neighbour (same level, one up, one down) that
     * would measurably shorten the distance to {@code target} without landing closer than
     * {@code minDistance} (this is an unstick routine, not the final approach -- overshooting
     * past the caller's own personal-space floor here would defeat FollowTask's STOP_DISTANCE
     * the moment recovery ever fires), nearest improvement first, and steps onto the first one
     * that isn't blocked (by geometry or, e.g., the followed player standing in the way --
     * {@link FakePlayerMotion#stepToStandable} rejects and reports either case without moving the
     * bot). Finding nothing safe to do this tick is not an error: the caller just retries later,
     * which is exactly "wait a moment" for a landing that is only momentarily occupied -- and, on
     * the {@code RecoveryClock}'s next alternate window, a fresh replan is tried instead.
     */
    private void attemptStep(AIPlayerEntity bot, ServerPlayer target, double minDistance) {
        bot.getActionPack().stopMovement();
        ServerLevel world = bot.level();
        BlockPos current = bot.blockPosition();
        BlockPos targetPos = target.blockPosition();
        double currentDistSq = current.distSqr(targetPos);
        double minDistSq = minDistance * minDistance;

        BlockPos best = null;
        double bestDistSq = currentDistSq;
        for (int dy = -1; dy <= 1; dy++) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos candidate = current.relative(direction).offset(0, dy, 0);
                if (!Standability.isStandableFresh(world, candidate)) {
                    continue;
                }
                double distSq = candidate.distSqr(targetPos);
                if (distSq < minDistSq) {
                    // Would land closer than the caller's own floor -- never an "unstick" move.
                    continue;
                }
                if (distSq + 0.01D < bestDistSq) {
                    best = candidate.immutable();
                    bestDistSq = distSq;
                }
            }
        }
        if (best != null && FakePlayerMotion.stepToStandable(bot, best, "follow_recovery_step")) {
            return;
        }
        // LAST recovery step before "I am stuck": no adjacent verified step got any closer (or the
        // one found was refused), so with a tool that can break it, tunnel through the natural
        // terrain in the way. FollowDigOut is deliberately narrow (see its header): observable
        // natural whitelist blocks only, horizontal only, never near fluid, never onto a drop.
        if (digOut.start(bot, target)) {
            digOut.tick(bot);
        }
    }
}
