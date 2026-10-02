package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.action.WalkedStep;
import io.github.zoyluo.minecraftai.action.WalkedStepRules;
import io.github.zoyluo.minecraftai.brain.BrainCoordinator;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogCategory;
import io.github.zoyluo.minecraftai.log.LogFields;
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
 * step attempts with windows that instead force a genuine fresh observed Baritone replan
 * (see {@link #tick}'s {@code FORCE_REPATH} handling) -- the route is constrained to what the
 * bot can actually see, rather than creating a tunnel through an unseen wall.
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
 * <p>{@link #attemptStep} tries a verified adjacent walk/step-up/step-down first. If none is
 * available, recovery requests a fresh observed Baritone route instead of breaking terrain.
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
    /** A recovery step is running on the bot's action pack (it is a real walk, hop or drop over several ticks). */
    private boolean stepOwned;
    /** Exact admission for the recovery step; stale recovery state must not observe or cancel a successor. */
    private ActionPack.StepLease stepLease;
    private WalkedStep step;
    /** Immutable observed-world proof retained by the exact physical recovery lease. */
    private StepAdmission stepAdmission;

    private record StepAdmission(BlockPos origin, BlockPos destination) {
    }

    /** Legacy dig-out recovery is retired; follow recovery never owns a terrain break. */
    boolean isDigging() {
        return false;
    }

    void reset(AIPlayerEntity bot, int nowTick) {
        if (stepOwned) {
            ActionPack pack = bot.getActionPack();
            if (pack.stepInFlightFor(stepLease)) {
                pack.cancelStep();
            }
            stepOwned = false;
            stepLease = null;
            step = null;
            stepAdmission = null;
        }
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
        if (stepOwned) {
            ActionPack pack = bot.getActionPack();
            ActionPack.StepLease lease = stepLease;
            if (pack.stepInFlightFor(lease)) {
                // A step is deliberate progress in flight: the stall clock does not count these ticks, and the next window is not opened
                // until the step has ended.
                return true;
            }
            if (!pack.stepIdle()) {
                // A safety successor owns the pack. Clear only stale recovery bookkeeping and
                // yield; its global result must never be treated as this recovery step.
                stepOwned = false;
                stepLease = null;
                step = null;
                stepAdmission = null;
                return true;
            }
            // A terminal recovery step is reconciled through its exact admission. A failed or
            // cancelled own step has not established physical recovery, so give the ordinary
            // follower an immediate fresh route instead of treating any unrelated global result
            // as progress.
            WalkedStep.Result result = pack.stepResultFor(lease);
            stepOwned = false;
            stepLease = null;
            step = null;
            stepAdmission = null;
            if (result == null || !result.succeeded()) {
                forceRepathPending = true;
                return false;
            }
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
     * {@link WalkedStep#refusal} rejects either case before any key is pressed). The step is a
     * real walk, hop or drop over several ticks (never a teleport). Finding nothing safe to do this tick is not an error: the caller just retries later,
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
                // SwimRoute's dry-cell admission proves feet, head, and support before its
                // standability read. A follow recovery is a local physical escape, not an
                // exception to the shared observed-terrain boundary.
                if (SwimRoute.observedCell(bot, world, candidate, false)
                        != SwimRoute.Cell.DRY) {
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
        if (best != null) {
            StepStart started = beginStep(bot, current, best);
            if (started != StepStart.REFUSED) {
                // A guarded owner denied admission, or this exact recovery step started. In both
                // cases leave the newly opened/verified candidate alone and retry later rather
                // than falling through into a competing dig-out controller.
                return;
            }
        }
        // No adjacent physical step is legal. Let FollowTask issue its next observed Baritone
        // replan; recovering by mining through a wall would turn a follow route into terrain
        // discovery and bypass the shared navigation fence.
        BotLog.action(bot, "follow_recovery_baritone_required",
                "from", LogFields.pos(current), "target", LogFields.pos(targetPos));
        forceRepathPending = true;
    }

    /**
     * Starts a real walk, hop or drop onto the adjacent standable {@code best} (the bot's keys are pressed, it is never moved). The
     * step is refused, and nothing happens, when something is in the way (a block, or the followed player standing in the gap): the
     * caller tries again shortly, or the next window forces a replan.
     */
    private enum StepStart {
        STARTED,
        REFUSED,
        ADMISSION_DENIED
    }

    private StepStart beginStep(AIPlayerEntity bot, BlockPos current, BlockPos best) {
        WalkedStep.Kind kind = WalkedStepRules.walkKindFor(best.getY() - current.getY());
        if (kind == null
                || SwimRoute.observedCell(bot, bot.level(), best, false) != SwimRoute.Cell.DRY
                || !SwimRoute.canObserveWalkedStepRefusalEnvelope(bot, best, kind)
                || WalkedStep.refusal(bot, best, kind) != null) {
            return StepStart.REFUSED;
        }
        StepAdmission admission = new StepAdmission(current.immutable(), best.immutable());
        WalkedStep next = WalkedStep.begin(bot, best, kind, "follow_recovery_step");
        ActionPack.StepLease lease = bot.getActionPack().runStep(next,
                (guardBot, guardedStep) -> canContinueObservedStep(
                        guardBot, guardedStep, admission));
        if (lease == null) {
            return StepStart.ADMISSION_DENIED;
        }
        step = next;
        stepLease = lease;
        stepAdmission = admission;
        stepOwned = true;
        return StepStart.STARTED;
    }

    /** Re-proves this exact one-cell recovery before each later WalkedStep terrain read. */
    private static boolean canContinueObservedStep(AIPlayerEntity bot, WalkedStep step,
                                                   StepAdmission admission) {
        if (!step.cell().equals(admission.destination())
                || !withinStepContinuationEnvelope(bot.blockPosition(), admission,
                step.kind(), step.ticks())) {
            return false;
        }
        return SwimRoute.observedCell(bot, bot.level(), step.cell(), false)
                == SwimRoute.Cell.DRY
                && SwimRoute.canObserveWalkedStepRefusalEnvelope(bot, step.cell(), step.kind());
    }

    private static boolean withinStepContinuationEnvelope(BlockPos feet, StepAdmission admission,
                                                          WalkedStep.Kind kind, int activeStepTicks) {
        if (withinStepCorridor(feet, admission.origin(), admission.destination())) {
            return true;
        }
        BlockPos origin = admission.origin();
        return (kind == WalkedStep.Kind.FLAT
                || kind == WalkedStep.Kind.STEP_UP
                || kind == WalkedStep.Kind.STEP_DOWN)
                && activeStepTicks >= 0 && activeStepTicks <= 1
                && feet.getX() == origin.getX()
                && feet.getZ() == origin.getZ()
                && Math.abs(feet.getY() - origin.getY()) == 1;
    }

    private static boolean withinStepCorridor(BlockPos feet, BlockPos origin, BlockPos destination) {
        return between(feet.getX(), origin.getX(), destination.getX())
                && between(feet.getY(), origin.getY(), destination.getY())
                && between(feet.getZ(), origin.getZ(), destination.getZ());
    }

    private static boolean between(int value, int first, int second) {
        return value >= Math.min(first, second) && value <= Math.max(first, second);
    }
}
