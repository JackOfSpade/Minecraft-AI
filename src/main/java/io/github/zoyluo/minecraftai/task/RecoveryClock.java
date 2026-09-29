package io.github.zoyluo.minecraftai.task;

/**
 * Pure timing/decision state machine behind {@link FollowStuckRecovery}, extracted so its tick
 * counting and branching can be unit-tested without a {@code ServerWorld}, bot, or player: it
 * knows nothing about pathfinding, block positions, or entities, only tick numbers and a single
 * boolean input ("did the caller observe real progress this tick?").
 *
 * <p>Once {@link #tick} sees {@link #stallTicksBeforeRecovery} ticks without progress, it enters
 * "recovering" and alternates {@link #alternateWindowTicks}-tick windows between
 * {@link Action#STEP} (try a small adjacent-step correction) and {@link Action#FORCE_REPATH}
 * (clear the path and let the caller run a genuine fresh search) -- a single-step-only recovery
 * can never find a detour that must first move away from the target, so it needs the real
 * pathfinder's help periodically. After {@link #giveUpTicks} of that without progress, it drops
 * into a low-cost backoff that forces a repath every {@link #lowCostRetryTicks} ticks instead of
 * spinning on single steps forever.
 */
final class RecoveryClock {
    enum Action {
        /** Not stalled long enough yet, or progress was just observed: caller's ordinary logic runs. */
        NOT_HANDLED,
        /** Progress was observed while previously recovering: recovery just ended this tick. */
        RESOLVED,
        /** Try one adjacent-step correction this tick. */
        STEP,
        /** Clear the path and force a fresh replan this tick (still inside the bounded recovery window). */
        FORCE_REPATH,
        /** Clear the path and force a fresh replan this tick (backoff phase, after giving up). */
        BACKOFF_FORCE_REPATH,
        /** Backoff phase, waiting for the next scheduled forced repath: caller should just wait. */
        BACKOFF_HOLD,
    }

    private final int stallTicksBeforeRecovery;
    private final int giveUpTicks;
    private final int alternateWindowTicks;
    private final int lowCostRetryTicks;

    private int lastProgressTick;
    private boolean recovering;
    private int recoveryStartTick = -1;
    private boolean announcedStuck;
    private int nextLowCostRetryTick;

    RecoveryClock(int stallTicksBeforeRecovery, int giveUpTicks, int alternateWindowTicks, int lowCostRetryTicks) {
        this.stallTicksBeforeRecovery = stallTicksBeforeRecovery;
        this.giveUpTicks = giveUpTicks;
        this.alternateWindowTicks = alternateWindowTicks;
        this.lowCostRetryTicks = lowCostRetryTicks;
    }

    void reset(int nowTick) {
        lastProgressTick = nowTick;
        recovering = false;
        recoveryStartTick = -1;
        announcedStuck = false;
        nextLowCostRetryTick = nowTick;
    }

    boolean isRecovering() {
        return recovering;
    }

    boolean hasAnnouncedStuck() {
        return announcedStuck;
    }

    /** The tick recovery most recently started at, retained after {@link Action#RESOLVED} for logging. */
    int lastRecoveryStartTick() {
        return recoveryStartTick;
    }

    Action tick(int elapsed, boolean progressed) {
        if (progressed) {
            boolean wasRecovering = recovering;
            lastProgressTick = elapsed;
            recovering = false;
            announcedStuck = false;
            return wasRecovering ? Action.RESOLVED : Action.NOT_HANDLED;
        }

        if (!recovering) {
            int stallTicks = elapsed - lastProgressTick;
            if (stallTicks < stallTicksBeforeRecovery) {
                return Action.NOT_HANDLED;
            }
            recovering = true;
            recoveryStartTick = elapsed;
        }

        int recoveryTicks = elapsed - recoveryStartTick;
        if (recoveryTicks < giveUpTicks) {
            int windowIndex = recoveryTicks / alternateWindowTicks;
            if (windowIndex % 2 == 1) {
                return recoveryTicks % alternateWindowTicks == 0 ? Action.FORCE_REPATH : Action.NOT_HANDLED;
            }
            return Action.STEP;
        }

        if (!announcedStuck) {
            announcedStuck = true;
            nextLowCostRetryTick = elapsed;
        }
        if (elapsed >= nextLowCostRetryTick) {
            nextLowCostRetryTick = elapsed + lowCostRetryTicks;
            return Action.BACKOFF_FORCE_REPATH;
        }
        return Action.BACKOFF_HOLD;
    }
}
