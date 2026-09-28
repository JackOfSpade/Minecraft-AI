package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.BlockMiner;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Low-priority cleanup for an emergency enclosure after danger clears.  It never identifies a
 * shelter by shape or material: every mining tick re-proves that the exact current state matches
 * a recorded bot placement.  Therefore a player replacing a dirt wall, or an unrelated dirt hut,
 * is never touched.
 */
final class ShelterCleanupTask extends AbstractTask {
    private static final double MINE_REACH_SQUARED = 30.25D;
    private static final int PATH_RETRY_LIMIT = 3;
    private static final int MAX_CLEANUP_TICKS = 1_200;

    private final BlockMiner miner = new BlockMiner();
    private final Set<BlockPos> rejected = new HashSet<>();
    private EmergencyShelterTask.ShelterCleanupDebt debt;
    private BlockPos target;
    private BlockPos approachGoal;
    private int pathRetries;
    private EatTask recoveryEatTask;

    @Override
    public String name() {
        return "shelter_cleanup";
    }

    @Override
    public String describe() {
        return "Removing verified emergency-shelter blocks"
                + " target=" + (target == null ? "(pending)" : target.toShortString())
                + " rejected=" + rejected.size();
    }

    @Override
    public double progress() {
        return state == TaskState.COMPLETED ? 1.0D : Math.min(0.95D, elapsed / 1_200.0D);
    }

    @Override
    public boolean isWaiting() {
        // This task has its own bounded path/mining retries and may intentionally pause to eat.
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        if (DangerWatcher.hasObservableHostilePressure(bot)
                || bot.getHungerManager().getFoodLevel() < 20) {
            complete();
            return;
        }
        debt = EmergencyShelterTask.claimPendingCleanup(bot).orElse(null);
        if (debt == null) {
            complete();
            return;
        }
        BotLog.action(bot, "shelter_cleanup_started", "anchor", debt.anchor());
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (debt == null) {
            complete();
            return;
        }
        // Safety always wins over housekeeping.  Release the lease before ending so any later
        // safe bot may resume this exact-state work after combat/recovery.
        if (DangerWatcher.hasObservableHostilePressure(bot) || bot.hurtTime > 0) {
            releaseAndComplete(bot, "shelter_cleanup_pressure_preempted");
            return;
        }
        if (!EmergencyShelterTask.renewCleanupClaim(bot, debt)) {
            complete();
            return;
        }
        // Start only at food 20, then keep vanilla regeneration supplied while cleanup continues.
        // A bite is an atomic physical action: no block is mined until it settles.
        if (tickRecoveryEating(bot)) {
            return;
        }
        if (elapsed > MAX_CLEANUP_TICKS) {
            releaseAndComplete(bot, "shelter_cleanup_retry_later");
            return;
        }
        if (target == null) {
            Optional<BlockPos> next = EmergencyShelterTask.nextCleanupBlock(bot, debt, rejected);
            if (next.isEmpty()) {
                releaseAndComplete(bot, "shelter_cleanup_complete_or_deferred");
                return;
            }
            target = next.orElseThrow();
            approachGoal = null;
            pathRetries = 0;
        }
        // State equality is checked again immediately before mining.  A player may have replaced
        // the block after this worker chose it, which makes it ineligible rather than a target.
        if (!EmergencyShelterTask.ownsCleanupBlock(bot, debt, target)) {
            miner.cancel(bot);
            if (!ObservableWorldQuery.canObserveBlock(bot, target)) {
                approachTarget(bot);
                return;
            }
            target = null;
            return;
        }
        if (bot.getEyePos().squaredDistanceTo(target.toCenterPos()) <= MINE_REACH_SQUARED) {
            if (miner.target() == null || !target.equals(miner.target())) {
                miner.begin(bot, target);
            }
            BlockMiner.Status status = miner.tick(bot);
            if (status == BlockMiner.Status.DONE) {
                EmergencyShelterTask.settleCleanupBlock(bot, debt, target);
                target = null;
                approachGoal = null;
                pathRetries = 0;
            } else if (status == BlockMiner.Status.FAILED) {
                rejected.add(target);
                target = null;
                approachGoal = null;
            }
            return;
        }
        approachTarget(bot);
    }

    private boolean tickRecoveryEating(AIPlayerEntity bot) {
        if (recoveryEatTask == null) {
            if (bot.getHungerManager().getFoodLevel() >= 20) {
                return false;
            }
            if (InventoryAction.findFoodSlot(bot) < 0) {
                // Keep the debt untouched.  DangerWatcher can resupply/hunt; cleanup must not
                // trade the bot's remaining healing window for one more dirt block.  More
                // importantly, do not remain as the active low-priority task: that would
                // prevent DangerWatcher from scheduling the resupply it is supposed to use.
                releaseAndComplete(bot, "shelter_cleanup_food_unavailable");
                return true;
            }
            miner.cancel(bot);
            bot.getActionPack().stopMovement();
            recoveryEatTask = new EatTask();
            recoveryEatTask.start(bot);
            BotLog.action(bot, "shelter_cleanup_recovery_eat_started",
                    "food", bot.getHungerManager().getFoodLevel(), "health", bot.getHealth());
        }
        recoveryEatTask.tick(bot);
        if (recoveryEatTask.state() == TaskState.RUNNING) {
            return true;
        }
        if (recoveryEatTask.state() == TaskState.FAILED) {
            BotLog.action(bot, "shelter_cleanup_recovery_eat_failed",
                    "reason", recoveryEatTask.failureReason());
        }
        recoveryEatTask = null;
        return true;
    }

    private void approachTarget(AIPlayerEntity bot) {
        BlockPos desired = findApproachPose(bot, target);
        if (desired == null) {
            rejected.add(target);
            target = null;
            return;
        }
        boolean arrived = bot.getBlockPos().getSquaredDistance(desired) <= 2.25D;
        if (arrived) {
            approachGoal = null;
            return;
        }
        if (approachGoal == null || !approachGoal.equals(desired)
                || bot.getActionPack().isPathExecutorIdle()) {
            if (++pathRetries > PATH_RETRY_LIMIT) {
                rejected.add(target);
                target = null;
                approachGoal = null;
                bot.getActionPack().stopAll();
                return;
            }
            ActionResult result = bot.getActionPack().startPathTo(desired);
            if (result.isFailed()) {
                approachGoal = null;
                return;
            }
            approachGoal = bot.getActionPack().activePathGoal();
            if (approachGoal == null) {
                approachGoal = desired.toImmutable();
            }
        }
    }

    private static BlockPos findApproachPose(AIPlayerEntity bot, BlockPos target) {
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int dy = -1; dy <= 1; dy++) {
            for (Direction direction : Direction.Type.HORIZONTAL) {
                BlockPos candidate = target.offset(direction).up(dy);
                if (!Standability.isStandable(bot.getEntityWorld(), candidate)) {
                    continue;
                }
                double distance = candidate.getSquaredDistance(target);
                if (distance < bestDistance) {
                    best = candidate.toImmutable();
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private void releaseAndComplete(AIPlayerEntity bot, String reason) {
        miner.cancel(bot);
        if (recoveryEatTask != null) {
            recoveryEatTask.cancel(bot, reason);
            recoveryEatTask = null;
        }
        EmergencyShelterTask.releaseCleanupClaim(bot, debt);
        BotLog.action(bot, reason, "anchor", debt.anchor(), "rejected", rejected.size());
        complete();
    }

    @Override
    protected void onPause(AIPlayerEntity bot) {
        miner.cancel(bot);
        if (recoveryEatTask != null) {
            recoveryEatTask.cancel(bot, "shelter_cleanup_preempted");
            recoveryEatTask = null;
        }
        EmergencyShelterTask.releaseCleanupClaim(bot, debt);
        bot.getActionPack().stopAll();
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        miner.cancel(bot);
        if (recoveryEatTask != null) {
            recoveryEatTask.cancel(bot, "shelter_cleanup_aborted");
            recoveryEatTask = null;
        }
        EmergencyShelterTask.releaseCleanupClaim(bot, debt);
        bot.getActionPack().stopAll();
    }
}
