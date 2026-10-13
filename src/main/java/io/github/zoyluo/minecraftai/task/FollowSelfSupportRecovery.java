package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.BlockMiner;
import io.github.zoyluo.minecraftai.action.MiningSafety;
import io.github.zoyluo.minecraftai.baritone.BaritoneRefusals;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import java.util.List;
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
    private int lastHandledRefusalTick = Integer.MIN_VALUE;
    /** The exact support block whose local recovery recently failed, and its bounded retry time. */
    private BlockPos failedTarget;
    private int retryFailedTargetAfterTick = Integer.MIN_VALUE;

    boolean isActive() {
        return target != null;
    }

    /**
     * Adopts only the newest still-live Baritone break refusal.  The live support check means a
     * stale ledger entry can never trigger a delayed terrain break, and a human standing on the
     * block remains a hard no-op.
     */
    boolean beginFromLatestRefusal(AIPlayerEntity bot) {
        if (target != null) {
            return true;
        }
        int now = bot.level().getServer().getTickCount();
        List<BaritoneRefusals.Refusal> refusals = BaritoneRefusals.of(bot.getUUID(), BaritoneRefusals.Op.BREAK);
        for (int index = refusals.size() - 1; index >= 0; index--) {
            BaritoneRefusals.Refusal refusal = refusals.get(index);
            if (refusal.serverTick() <= lastHandledRefusalTick
                    || !MiningSafety.SELF_SUPPORT.equals(refusal.reason())
                    || refusal.pos() == null
                    || MiningSafety.supportOccupancy(bot, refusal.pos()) != MiningSafety.SupportOccupancy.SELF) {
                continue;
            }
            if (refusal.pos().equals(failedTarget) && now < retryFailedTargetAfterTick) {
                // Consume this exact live refusal while its recovery is cooling down. Otherwise
                // every new Baritone route would re-adopt the same impossible sidestep before the
                // normal route backoff can choose a different route around the support block.
                lastHandledRefusalTick = refusal.serverTick();
                continue;
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
        return false;
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
    }
}
