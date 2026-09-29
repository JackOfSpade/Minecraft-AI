package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * BLOCKMINER: the correct shared primitive for mining a single block, curing the root cause of
 * "every task hand-writes its own mining loop, each one wrong in a different way".
 *
 * Background (real bugs #5/#8/#9, same class of bug): the underlying
 * {@code ActionPack.startMining(pos,face)} `new`s a fresh MiningController every time it's called
 * (semantically "start mining over", progress reset to zero). Multiple tasks each hand-rolled
 * {@code if(isMiningIdle()) startMining} + progress checks and repeatedly got it wrong: some
 * resent startMining every tick / every 20 ticks, zeroing progress and never breaking the block;
 * some computed face backwards; some never gave up even when the block was unreachable.
 *
 * This class wraps "mine this one block until it breaks / times out / is unreachable" into a
 * small **stateful, pollable** state machine:
 * - Issues startMining exactly once, only while mining is idle, then lets MiningController
 *   accumulate progress on its own — never resending mid-mine to reset it;
 * - Automatically computes the correct face (from the bot's eye position toward the block);
 * - Target already air -> DONE; block changed / unreachable / timed out -> FAILED; otherwise MINING.
 *
 * Usage: each mining task holds one BlockMiner field, calling {@link #tick} every tick:
 * <pre>
 *   if (miner.target() == null || miner.isDone()) miner.begin(targetPos);
 *   switch (miner.tick(bot)) { case DONE -> ...; case FAILED -> ...; case MINING -> {} }
 * </pre>
 * Runs entirely on the main thread (G2); never assign() (G1).
 */
public final class BlockMiner {
    /** Per-block mining timeout (ticks). Even bedrock-tier hard stone with a diamond pickaxe finishes well under this; a timeout is treated as unreachable/abnormal. */
    private static final int MINE_TIMEOUT_TICKS = 200;

    public enum Status { IDLE, MINING, DONE, FAILED }

    private BlockPos target;
    private int sinceTick;
    private boolean started;
    private boolean miningChannelToolPolicy;
    private String failureReason = "";

    /** Start mining a new target block (if it's the same as the current target and already mining, don't interrupt or reset progress). */
    public void begin(AIPlayerEntity bot, BlockPos pos) {
        begin(bot, pos, false);
    }

    /** OreDig may opt into lowest-sufficient pickaxe selection; the default remains unchanged. */
    public void begin(AIPlayerEntity bot, BlockPos pos, boolean miningChannelToolPolicy) {
        if (pos != null && pos.equals(target) && started) {
            return; // Same block, keep mining — never reset (this was the exact root cause of the #9 hang)
        }
        // Switching targets: stop the old mining first, then lock in the new target.
        bot.getActionPack().stopMining();
        this.target = pos == null ? null : pos.immutable();
        this.sinceTick = 0;
        this.started = false;
        this.miningChannelToolPolicy = miningChannelToolPolicy;
        this.failureReason = "";
    }

    public BlockPos target() {
        return target;
    }

    public boolean isDone() {
        return target == null;
    }

    public String failureReason() {
        return failureReason;
    }

    /** Advance mining by one tick, returning the status. After DONE/FAILED, target is cleared to null; the caller should begin() the next block. */
    public Status tick(AIPlayerEntity bot) {
        if (target == null) {
            return Status.IDLE;
        }
        ServerLevel world = bot.level();
        // Target already broken (air / replaced with something else is the caller's concern; here we only recognize "no longer minable" = air).
        BlockState targetState = world.getBlockState(target);
        if (targetState.isAir()) {
            bot.getActionPack().stopMining();
            target = null;
            return Status.DONE;
        }
        // Fluids can't be "broken" (mining progress never completes): a caller using !isAir to test
        // for solid blocks would treat water as a minable target, burning the full 200t timeout on
        // every block before it gets blacklisted (observed: miner_slow_dump block=water, 13 in a row
        // blacklisted). Fail immediately instead and move to the next block.
        if (!targetState.getFluidState().isEmpty()) {
            bot.getActionPack().stopMining();
            failureReason = "target_is_fluid";
            target = null;
            return Status.FAILED;
        }
        sinceTick++;
        if (sinceTick > MINE_TIMEOUT_TICKS) {
            bot.getActionPack().stopMining();
            failureReason = "mine_timeout";
            target = null;
            return Status.FAILED;
        }
        // Diagnostic: 5 seconds of mining without breaking (a stone pickaxe should break it in ~2.5s)
        // means mining hits are landing but ineffective — dump the key state to help locate it
        // (observed: ore_dig dist=3.9, locked on and actively mining yet every block hit the 200t
        // timeout and got blacklisted, 13 in a row, with the root cause hidden below this layer).
        if (sinceTick == 100) {
            double dist = Math.sqrt(bot.getEyePosition().distanceToSqr(target.getCenter()));
            io.github.zoyluo.minecraftai.log.BotLog.action(bot, "miner_slow_dump",
                    "target", target.toShortString(),
                    "dist", String.format(java.util.Locale.ROOT, "%.1f", dist),
                    "mining_idle", bot.getActionPack().isMiningIdle(),
                    "started", started,
                    "block", world.getBlockState(target).getBlock().toString());
        }
        // Issue startMining exactly once, only while mining is idle (not yet started on this block /
        // the previous block finished); after that, let MiningController accumulate progress on its own.
        // Never resend startMining every tick — that resets progress to zero and the block never breaks.
        if (bot.getActionPack().isMiningIdle()) {
            BlockState equipTarget = world.getBlockState(target);
            if (miningChannelToolPolicy) {
                ToolSelector.Selection selection = ToolSelector.equipMiningChannelTool(bot, equipTarget);
                // Empty hand is a valid mining channel for dirt/grass/sand and other blocks that
                // do not require a tool.  Keep the explicit failure for stone/ores: those blocks
                // would otherwise be broken without a harvest drop or consume a scarce wrong tier.
                if (selection.slot() < 0
                        || (equipTarget.requiresCorrectToolForDrops() && selection.stack().isEmpty())) {
                    bot.getActionPack().stopMining();
                    failureReason = "missing_mining_channel_tool:"
                            + BuiltInRegistries.ITEM.getKey(
                            ToolSelector.requiredMiningChannelTool(equipTarget));
                    target = null;
                    return Status.FAILED;
                }
            } else {
                ToolSelector.equipBestTool(bot, equipTarget);
            }
            Direction face = faceToward(bot, target);
            MiningAction.startMining(bot, target, face);
            started = true;
        }
        return Status.MINING;
    }

    /** Abandon the current mining operation (called when a task is paused/aborted); does not change the external intent of "which block to mine". */
    public void cancel(AIPlayerEntity bot) {
        bot.getActionPack().stopMining();
        target = null;
        started = false;
        sinceTick = 0;
        miningChannelToolPolicy = false;
    }

    /** The direction from the bot's eyes toward the block's center, used as the breaking face (snapped to the dominant axis). Just needs to roughly face the block; doesn't need to be exact. */
    private static Direction faceToward(AIPlayerEntity bot, BlockPos pos) {
        return Direction.getApproximateNearest(
                pos.getX() + 0.5 - bot.getEyePosition().x,
                pos.getY() + 0.5 - bot.getEyePosition().y,
                pos.getZ() + 0.5 - bot.getEyePosition().z);
    }
}
