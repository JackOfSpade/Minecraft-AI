package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.BlockMiner;
import io.github.zoyluo.minecraftai.action.MiningSafety;
import io.github.zoyluo.minecraftai.baritone.BaritoneRefusals;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;

/**
 * Completes the one terrain change Baritone requested but could not safely begin while Moss was
 * standing on it.  This is deliberately a follow-task adapter, not a Baritone modification: a
 * refused route supplies one already observed block, then {@link BlockMiner} keeps ownership of
 * its real sidestep and of the ensuing break until the block is gone.
 *
 * <p>Giving the route back immediately after an ordinary recovery sidestep is insufficient.  A
 * new Baritone path may step back onto the same support before its next break attempt, producing
 * the {@code self_support -> policy_refused -> sidestep} loop.  Retaining the exact target through
 * the sidestep avoids that race without authorizing terrain discovery or a new pathfinder.</p>
 */
final class FollowSelfSupportRecovery {
    /** Do not re-adopt the same physical break every new Baritone tick after its sidestep failed. */
    private static final int FAILED_TARGET_BACKOFF_TICKS = 80;

    enum Tick {
        IDLE,
        ACTIVE,
        COMPLETE,
        FAILED
    }

    private final BlockMiner miner = new BlockMiner();
    private BlockPos target;
    /**
     * A self-support refusal belongs to the Baritone route that produced it, not to the bot for
     * its entire lifetime. Until a route is marked, fail closed rather than borrowing an old
     * ledger entry from another task or a previous follow attempt. The last refusal already
     * present when the current route began is an exact ledger cursor.
     */
    private BaritoneRefusals.Refusal routeStartCursor;
    private boolean routeMarked;
    private int lastHandledRefusalTick = Integer.MIN_VALUE;
    /** The exact support block whose local recovery recently failed, and its bounded retry time. */
    private BlockPos failedTarget;
    private int retryFailedTargetAfterTick = Integer.MIN_VALUE;

    boolean isActive() {
        return target != null;
    }

    /** Marks the route that may contribute one self-support refusal. Called immediately before it starts. */
    void markRouteStarted(AIPlayerEntity bot) {
        List<BaritoneRefusals.Refusal> refusals = BaritoneRefusals.of(bot.getUUID(), BaritoneRefusals.Op.BREAK);
        routeStartCursor = refusals.isEmpty() ? null : refusals.get(refusals.size() - 1);
        // The cursor excludes prior routes, so a new route may legitimately emit its first
        // refusal in the same server tick as the previous route's handled refusal.
        lastHandledRefusalTick = Integer.MIN_VALUE;
        routeMarked = true;
    }

    /**
     * Adopts only the newest still-live Baritone break refusal emitted by the current route. The
     * live support check means a stale ledger entry can never trigger a delayed terrain break,
     * and a human standing on the block remains a hard no-op.
     */
    boolean beginFromLatestRefusal(AIPlayerEntity bot) {
        if (target != null) {
            return true;
        }
        if (!routeMarked) {
            return false;
        }
        int now = bot.level().getServer().getTickCount();
        List<BaritoneRefusals.Refusal> refusals = BaritoneRefusals.of(bot.getUUID(), BaritoneRefusals.Op.BREAK);
        Optional<BaritoneRefusals.Refusal> latest = newestEligibleRefusal(refusals, routeStartCursor,
                lastHandledRefusalTick, refusal -> MiningSafety.SELF_SUPPORT.equals(refusal.reason())
                        && refusal.pos() != null
                        && MiningSafety.supportOccupancy(bot, refusal.pos()) == MiningSafety.SupportOccupancy.SELF);
        if (latest.isEmpty()) {
            return false;
        }
        BaritoneRefusals.Refusal refusal = latest.get();
        if (refusal.pos().equals(failedTarget) && now < retryFailedTargetAfterTick) {
            // Consume this exact live refusal while its recovery is cooling down. Otherwise every
            // new Baritone route would re-adopt the same impossible sidestep before the normal
            // route backoff can choose a different route around the support block.
            lastHandledRefusalTick = refusal.serverTick();
            return false;
        }
        target = refusal.pos().immutable();
        lastHandledRefusalTick = refusal.serverTick();
        miner.naturalTerrainOnly(true);
        miner.swordsMine(false);
        miner.begin(bot, target);
        BotLog.task(bot, "follow_self_support_recovery_started",
                "target", target.toShortString(), "refusal_tick", refusal.serverTick());
        return true;
    }

    /**
     * Selects only a refusal appended after this route's ledger cursor. Identity is intentional:
     * two identical refusal values can be emitted in one server tick, but only the one created
     * after the route start belongs to this route. If the bounded FIFO ledger has evicted the
     * cursor, all remaining entries are necessarily newer.
     */
    static Optional<BaritoneRefusals.Refusal> newestEligibleRefusal(List<BaritoneRefusals.Refusal> refusals,
                                                                      BaritoneRefusals.Refusal routeStartCursor,
                                                                      int lastHandledRefusalTick,
                                                                      Predicate<BaritoneRefusals.Refusal> eligible) {
        if (refusals == null || eligible == null) {
            return Optional.empty();
        }
        int firstCurrentRouteRefusal = firstRefusalAfterCursor(refusals, routeStartCursor);
        for (int index = refusals.size() - 1; index >= firstCurrentRouteRefusal; index--) {
            BaritoneRefusals.Refusal refusal = refusals.get(index);
            if (refusal.serverTick() <= lastHandledRefusalTick) {
                continue;
            }
            if (eligible.test(refusal)) {
                return Optional.of(refusal);
            }
        }
        return Optional.empty();
    }

    /** Returns the first ledger element emitted after {@code cursor}; null means the route started with none. */
    private static int firstRefusalAfterCursor(List<BaritoneRefusals.Refusal> refusals,
                                                BaritoneRefusals.Refusal cursor) {
        if (cursor == null) {
            return 0;
        }
        for (int index = refusals.size() - 1; index >= 0; index--) {
            if (refusals.get(index) == cursor) {
                return index + 1;
            }
        }
        return 0;
    }

    /** Advances the retained physical sidestep and the exact one-block mine. */
    Tick tick(AIPlayerEntity bot) {
        if (target == null) {
            return Tick.IDLE;
        }
        BlockMiner.Status status = miner.tick(bot);
        if (status == BlockMiner.Status.MINING) {
            return Tick.ACTIVE;
        }
        BlockPos completed = target;
        target = null;
        if (status == BlockMiner.Status.DONE) {
            if (completed.equals(failedTarget)) {
                failedTarget = null;
                retryFailedTargetAfterTick = Integer.MIN_VALUE;
            }
            BotLog.task(bot, "follow_self_support_recovery_complete", "target", completed.toShortString());
            return Tick.COMPLETE;
        }
        if (!BlockMiner.MINING_PREEMPTED.equals(miner.failureReason())) {
            failedTarget = completed.immutable();
            retryFailedTargetAfterTick = bot.level().getServer().getTickCount() + FAILED_TARGET_BACKOFF_TICKS;
        }
        BotLog.warn(io.github.zoyluo.minecraftai.log.LogCategory.TASK, bot,
                "follow_self_support_recovery_failed", "target", completed.toShortString(),
                "reason", miner.failureReason());
        return Tick.FAILED;
    }

    /** Cancels the miner when land following yields to another movement mode or task lifecycle. */
    void reset(AIPlayerEntity bot) {
        // BlockMiner.cancel stops the shared mining controller. Do not touch it when this helper
        // owns no target: another follow child may legitimately be finishing its own action.
        if (target != null || miner.target() != null) {
            miner.cancel(bot);
        }
        target = null;
        routeStartCursor = null;
        routeMarked = false;
    }
}
