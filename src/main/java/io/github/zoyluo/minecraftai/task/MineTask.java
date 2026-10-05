package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.BlockMiner;
import io.github.zoyluo.minecraftai.action.HarvestCore;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mining.OreScan;
import io.github.zoyluo.minecraftai.mining.ToolTier;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public final class MineTask extends AbstractTask {
    private static final int EXPLORE_MAX_HOPS = 16;
    private static final int EXPLORE_MOVE_LIMIT = 240;

    private enum Phase {
        SEARCHING,
        EXPLORING,
        MOVING,
        MINING,
        PICKING_UP
    }

    private final Block targetBlock;
    private final int countNeeded;
    private final Set<Item> targetDrops;
    private final BlockMiner miner = new BlockMiner();
    private Phase phase = Phase.SEARCHING;
    private BlockPos targetPos;
    private int countSoFar;
    private int inventoryCountBeforeMining;
    private int pickupTicks;
    private boolean pickupSweepAttempted;
    private boolean directMiningTarget;
    /**
     * Count-mode mining searches beyond its first local view by walking only to a short destination
     * selected by the observation fence.  A compass heading is never itself a terrain or route
     * claim, so a failed admission cannot become a fabricated "no stone over there" result.
     */
    private final ObservedSearchHops observedSearchHops = new ObservedSearchHops(EXPLORE_MAX_HOPS);
    private BlockPos exploreTarget;
    private BlockPos exploreStart;
    private int exploreStartedTick;
    private int completedExploreHops;
    /** One fresh, bounded descent after observed horizontal exploration is exhausted. */
    private MiningExplorationTask miningExploration;
    private boolean miningExplorationAttempted;
    private int miningExplorationTimeoutCredit;

    public MineTask(Block targetBlock, int countNeeded) {
        this.targetBlock = targetBlock;
        this.countNeeded = Math.max(1, countNeeded);
        this.targetDrops = HarvestCore.expectedDropsFor(targetBlock);
    }

    @Override
    public String name() {
        return "mine";
    }

    @Override
    public String describe() {
        return "Mining " + BuiltInRegistries.BLOCK.getKey(targetBlock) + " " + countSoFar + "/" + countNeeded + " phase=" + phase;
    }

    @Override
    public double progress() {
        return Math.min(1.0D, (double) countSoFar / countNeeded);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.SEARCHING;
        observedSearchHops.reset();
        exploreTarget = null;
        exploreStart = null;
        completedExploreHops = 0;
        miningExploration = null;
        miningExplorationAttempted = false;
        miningExplorationTimeoutCredit = 0;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        // The child owns its own bounded descent clock.  Do not let MineTask's much shorter
        // generic search timeout kill a safe, already-started descent in mid-stair.
        if (tickMiningExploration(bot)) {
            return;
        }
        // Eligible mining requests may spend their first window revealing terrain through
        // observed hops, then have a separately bounded staircase.  Keep the historical short
        // timeout for every unrelated direct mine request.
        int timeout = MiningExplorationTask.supports(targetBlock) ? 6000 : 2400;
        if (miningExploration == null && elapsed - miningExplorationTimeoutCredit > timeout) {
            BotLog.action(bot, "mine_timeout_detail", "phase", phase, "count", countSoFar + "/" + countNeeded,
                    "target", BuiltInRegistries.BLOCK.getKey(targetBlock));
            fail("mine_timeout");
            return;
        }
        switch (phase) {
            case SEARCHING -> search(bot);
            case EXPLORING -> explore(bot);
            case MOVING -> move(bot);
            case MINING -> mine(bot);
            case PICKING_UP -> pickup(bot);
        }
    }

    private void search(AIPlayerEntity bot) {
        HarvestCore.TargetChoice choice = HarvestCore.nearestReachableBlock(bot, targetBlock, 8, 4, 6);
        if (choice == null) {
            if (startObservedExploration(bot)) {
                return;
            }
            if (startMiningExploration(bot)) {
                return;
            }
            fail(noObservedTargetReason());
            return;
        }
        targetPos = choice.pos();
        directMiningTarget = choice.direct();
        if (directMiningTarget) {
            startMiningTarget(bot);
            return;
        }
        phase = Phase.MOVING;
        bot.getActionPack().startPathTo(choice.stand());
    }

    /**
     * Starts one short exploration leg that was admitted from current line-of-sight terrain.  It
     * gives a generic {@code mine} request the same recovery behavior as gather, without making a
     * raw world scan, blind path, or mining action part of the search.
     */
    private boolean startObservedExploration(AIPlayerEntity bot) {
        ObservedSearchHops.Attempt attempt = observedSearchHops.begin(bot, null);
        if (!attempt.started()) {
            BotLog.action(bot, "mine_explore_hop_refused",
                    "attempt", attempt.number(),
                    "heading", attempt.heading() == null ? "none" : attempt.heading().toShortString(),
                    "reason", attempt.reason(),
                    "remaining", Math.max(0, EXPLORE_MAX_HOPS - observedSearchHops.attempts()));
            // A refusal is a planning fact, not movement.  Continue trying bounded alternatives
            // until the observation fence has exhausted the search budget.
            return !observedSearchHops.exhausted();
        }
        exploreTarget = attempt.observedGoal();
        exploreStart = bot.blockPosition().immutable();
        exploreStartedTick = elapsed;
        phase = Phase.EXPLORING;
        BotLog.action(bot, "mine_explore_hop",
                "attempt", attempt.number(),
                "heading", attempt.heading().toShortString(),
                "to", exploreTarget.toShortString(),
                "max_hop", ObservedSearchHops.HOP_DISTANCE);
        return true;
    }

    private void explore(AIPlayerEntity bot) {
        // Finding a visible target while walking is enough to hand control back to normal mining.
        // The next SEARCHING tick selects a verified local stance and never assumes the remote
        // compass heading contains a block.
        if (HarvestCore.nearestReachableBlock(bot, targetBlock, 8, 4, 6) != null) {
            bot.getActionPack().stopAll();
            clearExploreLeg();
            phase = Phase.SEARCHING;
            return;
        }
        boolean arrived = exploreTarget == null || bot.blockPosition().distSqr(exploreTarget) <= 9.0D;
        if (arrived) {
            recordExploreArrival(bot);
            bot.getActionPack().stopAll();
            clearExploreLeg();
            phase = Phase.SEARCHING;
            return;
        }
        if (elapsed - exploreStartedTick > EXPLORE_MOVE_LIMIT
                || (elapsed - exploreStartedTick > 20 && bot.getActionPack().isPathExecutorIdle())) {
            BotLog.action(bot, "mine_explore_hop_ended",
                    "reason", elapsed - exploreStartedTick > EXPLORE_MOVE_LIMIT ? "timeout" : "route_ended",
                    "to", exploreTarget.toShortString());
            bot.getActionPack().stopAll();
            clearExploreLeg();
            phase = Phase.SEARCHING;
        }
    }

    private void recordExploreArrival(AIPlayerEntity bot) {
        if (exploreStart != null && bot.blockPosition().distSqr(exploreStart) > 9.0D) {
            completedExploreHops++;
            BotLog.action(bot, "mine_explore_arrived",
                    "hop", completedExploreHops,
                    "at", bot.blockPosition().toShortString(),
                    "attempts", observedSearchHops.attempts());
        }
    }

    private void clearExploreLeg() {
        exploreTarget = null;
        exploreStart = null;
    }

    private String noObservedTargetReason() {
        String target = BuiltInRegistries.BLOCK.getKey(targetBlock).toString();
        boolean searched = completedExploreHops > 0;
        if (OreScan.isOreBlock(targetBlock)) {
            return (searched ? "no_observed_ore_after_exploration:" : "no_observed_ore_in_local_view:") + target;
        }
        return (searched ? "no_observed_resource_after_exploration:" : "no_observed_resource_in_local_view:") + target;
    }

    /**
     * The direct mine form can reach this point only after every bounded observed walk leg was
     * tried.  A known ore family or common geological source may then open one fresh safe stair;
     * arbitrary block requests keep the ordinary observed-only result.
     */
    private boolean startMiningExploration(AIPlayerEntity bot) {
        if (miningExplorationAttempted || !MiningExplorationTask.supports(targetBlock)) {
            return false;
        }
        if (!ToolTier.canHarvestWithInventory(bot, targetBlock.defaultBlockState())) {
            fail("need_better_tool:" + ToolTier.requiredPickaxeItemId(targetBlock));
            return true;
        }
        miningExplorationAttempted = true;
        miningExploration = MiningExplorationTask.forBlocks(Set.of(targetBlock));
        miningExploration.start(bot);
        if (miningExploration.state() == TaskState.RUNNING) {
            BotLog.action(bot, "mine_exploration_handoff",
                    "target", BuiltInRegistries.BLOCK.getKey(targetBlock),
                    "from_y", bot.blockPosition().getY());
            return true;
        }
        if (miningExploration.state() == TaskState.FAILED) {
            String reason = miningExploration.failureReason();
            miningExploration = null;
            fail(reason == null || reason.isBlank() ? "mining_exploration_failed" : reason);
            return true;
        }
        // Already at/below the target layer (or no matching profile): the caller reports its
        // normal observed-search result rather than pretending a descent happened.
        miningExploration = null;
        return false;
    }

    /** Returns true while the child owns the bot, including a terminal child failure. */
    private boolean tickMiningExploration(AIPlayerEntity bot) {
        if (miningExploration == null) {
            return false;
        }
        if (miningExploration.state() == TaskState.RUNNING) {
            miningExploration.tick(bot);
        }
        if (miningExploration.state() == TaskState.RUNNING) {
            return true;
        }
        if (miningExploration.state() == TaskState.COMPLETED) {
            miningExplorationTimeoutCredit += miningExploration.elapsedTicks();
            miningExploration = null;
            observedSearchHops.reset();
            clearExploreLeg();
            completedExploreHops = 0;
            phase = Phase.SEARCHING;
            return false;
        }
        String reason = miningExploration.failureReason();
        miningExploration = null;
        fail(reason == null || reason.isBlank() ? "mining_exploration_failed" : reason);
        return true;
    }

    private void move(AIPlayerEntity bot) {
        if (targetPos == null || !bot.level().getBlockState(targetPos).is(targetBlock)) {
            phase = Phase.SEARCHING;
            return;
        }
        if (HarvestCore.canReach(bot, targetPos)) {
            bot.getActionPack().stopAll();
            startMiningTarget(bot);
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            phase = Phase.SEARCHING;
        }
    }

    private void mine(AIPlayerEntity bot) {
        if (targetPos == null || !bot.level().getBlockState(targetPos).is(targetBlock)) {
            miner.cancel(bot);
            pickupTicks = 120;
            phase = Phase.PICKING_UP;
            return;
        }
        // P1-a: mining goes through BlockMiner (only starts when idle, never restarts and resets progress); block break/timeout moves to the pickup phase.
        BlockMiner.Status status = miner.tick(bot);
        if (status == BlockMiner.Status.DONE || status == BlockMiner.Status.FAILED) {
            pickupTicks = 120;
            phase = Phase.PICKING_UP;
        }
    }

    private void pickup(AIPlayerEntity bot) {
        int collected = HarvestCore.countInventoryItems(bot, targetDrops) - inventoryCountBeforeMining;
        if (collected > 0) {
            // A prior tick's chaseDropAnyOf -> approachDropPhysically nudge can leave the action
            // pack mid pickup-nudge (sneaking held); nothing else clears it once this phase stops
            // being ticked, which would otherwise deadlock any paused task waiting on
            // ActionPack.hasActiveActions() to go idle before resuming.
            bot.getActionPack().stopAll();
            BotLog.action(bot, "pickup_collected", "count", collected);
            countSoFar += collected;
            if (countSoFar >= countNeeded) {
                complete();
            } else {
                phase = Phase.SEARCHING;
            }
            return;
        }
        pickupTicks--;
        HarvestCore.chaseDropAnyOf(bot, targetDrops, 8.0D);
        if (pickupTicks <= 0) {
            if (!pickupSweepAttempted && HarvestCore.nearestDropAnyOf(bot, targetDrops, 8.0D).isPresent()) {
                pickupSweepAttempted = true;
                HarvestCore.sweepPickupAnyOf(bot, targetDrops, 8);
                pickupTicks = 60;
                return;
            }
            int partial = HarvestCore.countInventoryItems(bot, targetDrops) - inventoryCountBeforeMining;
            bot.getActionPack().stopAll();
            if (partial > 0) {
                BotLog.action(bot, "pickup_collected", "count", partial, "reason", "partial_pickup");
                countSoFar += partial;
                complete();
                return;
            }
            fail("pickup_timeout");
        }
    }

    private void startMiningTarget(AIPlayerEntity bot) {
        BlockState state = bot.level().getBlockState(targetPos);
        if (!ToolTier.canHarvestWithInventory(bot, state)) {
            fail("need_better_tool:" + ToolTier.requiredPickaxeItemId(targetBlock));
            return;
        }
        // GOALFIX-GF2: pre-mining hazard gate -- do not break the block when lava is adjacent to the target (breaking it would let the lava out and burn us to death); fail safely and let the caller figure out another approach.
        if (lavaAdjacent(bot, targetPos)) {
            BotLog.warn(io.github.zoyluo.minecraftai.log.LogCategory.TASK, bot, "mine_hazard_skip",
                    "pos", targetPos.getX() + "," + targetPos.getY() + "," + targetPos.getZ());
            fail("mine_hazard_lava");
            return;
        }
        inventoryCountBeforeMining = HarvestCore.countInventoryItems(bot, targetDrops);
        pickupSweepAttempted = false;
        miner.begin(bot, targetPos); // P1-a: BlockMiner takes over mining; the mine() phase advances it every tick
        phase = Phase.MINING;
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        if (miningExploration != null) {
            miningExploration.abort(bot);
            miningExploration = null;
        }
        miner.cancel(bot);
        clearExploreLeg();
        bot.getActionPack().stopAll();
    }

    @Override
    protected void onPause(AIPlayerEntity bot) {
        if (miningExploration != null) {
            miningExploration.pause(bot);
        }
        miner.cancel(bot);
        clearExploreLeg();
        bot.getActionPack().stopAll();
    }

    @Override
    protected void onResume(AIPlayerEntity bot) {
        if (miningExploration != null) {
            miningExploration.resume(bot);
        }
    }

    private static boolean lavaAdjacent(AIPlayerEntity bot, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (bot.level().getFluidState(pos.relative(direction)).is(FluidTags.LAVA)) {
                return true;
            }
        }
        return false;
    }
}
