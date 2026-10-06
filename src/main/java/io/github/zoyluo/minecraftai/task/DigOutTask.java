package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.action.BlockMiner;
import io.github.zoyluo.minecraftai.action.WalkedStep;
import io.github.zoyluo.minecraftai.brain.BrainCoordinator;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/**
 * Digs a stair up out of the dark, the way a player with a pickaxe and no torch leaves a cave pocket.
 * It is what a bot stuck in the dark does when it has nothing to light the cell with and cannot be
 * teleported out ({@link DangerWatcher}): climb one block per step, a block forward and a block up,
 * until the cell it stands in is no longer a dark trap, which is any cell with light enough to spawn
 * nothing and any cell under open sky ({@link DangerWatcher#isDarkTrapCell}). The surface is never
 * lit; it is simply where the stair ends.
 *
 * <p>The stair is {@link OreClimb}'s rise, repeated: each step opens the cell over the bot's head and
 * the landing with its head cell, and lands on a cell that was never dug, so it always has a floor
 * and the way back down is the stair itself. A rise needs a wall to start in, so a bot standing in
 * the middle of a room first walks to the nearest wall it can see. A step is opened only when what
 * the bot can see allows it ({@link StairDig}: no fluid or falling block over, beside or in the
 * cells, nothing a bot may not dig, no player standing on it); a heading that is refused is turned
 * from, to the right, then the left, then back, and the task ends when all four are. Every rise
 * gains a block, and a walk to a wall never goes back onto a cell the bot has stood in since the
 * last rise, so it cannot go in circles; it ends at the build limit if nothing else stops it.</p>
 *
 * <p>Nothing here knows where the sky is. Like a player in the dark it digs up, and what it learns on
 * the way (an open cave, a lit cell, the roof) is only what it sees.</p>
 */
public final class DigOutTask extends AbstractTask {
    /**
     * A step opens at most three cells, and {@link BlockMiner} gives up on a cell after 200 ticks, so a
     * step that has made no progress after three of those is not going to.
     */
    private static final int STALL_TICKS = 600;
    /** The highest cell a rise opens is two over the bot's feet: the head cell of its landing. */
    private static final int RISE_HEAD_CELLS = 2;
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private final BlockMiner miner = new BlockMiner();
    /** The steps that were started and did not land: never tried again from the same cell. */
    private final Set<String> failedSteps = new HashSet<>();
    /**
     * The cells stood in since the stair last rose. A walk to a wall never goes back onto one of them, so a
     * room whose every wall refuses the stair is crossed once and the task ends, instead of circling in it.
     */
    private final Set<BlockPos> visitedAtThisLevel = new HashSet<>();
    private BlockPos startCell;
    private Direction heading;
    /** The latest reason the rock itself was refused (anything but a missing floor), for the report when the task ends. */
    private String lastRockRefusal = StairDig.OPEN_DROP;
    private ActionPack.StepLease lease;
    private BlockPos stepOrigin;
    private BlockPos stepLanding;
    private boolean stepRises;
    private int lastProgressTick;
    private int risen;

    @Override
    public String name() {
        return "dig_out";
    }

    @Override
    public String describe() {
        return "Digging a stair up out of the dark" + (startCell == null ? "" : " from " + startCell.toShortString());
    }

    @Override
    public double progress() {
        return state == TaskState.COMPLETED ? 1.0D : 0.0D;
    }

    /** Standing still to mine is this task's work, not a stall. */
    @Override
    public boolean isWaiting() {
        return miner.target() != null;
    }

    /** How many blocks the stair has gained so far. */
    int risen() {
        return risen;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        startCell = bot.blockPosition().immutable();
        heading = bot.getDirection();
        miner.naturalTerrainOnly(true);
        BotLog.action(bot, "dig_out_started", "at", startCell.toShortString(), "heading", heading.getSerializedName());
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (holdForStep(bot)) {
            return;
        }
        ServerLevel world = bot.level();
        BlockPos feet = bot.blockPosition();
        visitedAtThisLevel.add(feet.immutable());
        if (!DangerWatcher.isDarkTrapCell(world, feet)) {
            BotLog.action(bot, "dig_out_done", "at", feet.toShortString(), "risen", risen);
            complete();
            return;
        }
        if (elapsed - lastProgressTick > STALL_TICKS) {
            end(bot, "dig_out_stalled");
            return;
        }
        if (feet.getY() + RISE_HEAD_CELLS > world.getMaxY()) {
            end(bot, "dig_out_build_limit");
            return;
        }
        Direction[] order = {heading, heading.getClockWise(), heading.getCounterClockWise(), heading.getOpposite()};
        for (Direction direction : order) {
            OreClimb.Move move = new OreClimb.Move(direction, true);
            String why = failedSteps.contains(stepKey(feet, OreClimb.landing(feet, move)))
                    ? "step_failed" : StairDig.refusal(bot, world, feet, move);
            if (why == null) {
                if (direction != heading) {
                    BotLog.action(bot, "dig_out_turned", "at", feet.toShortString(),
                            "from", heading.getSerializedName(), "to", direction.getSerializedName());
                }
                heading = direction;
                rise(bot, world, feet, move);
                return;
            }
            if (!StairDig.OPEN_DROP.equals(why)) {
                // A room's open side is refused for want of a floor; what it is told about the rock is the reason worth giving.
                lastRockRefusal = why;
            }
        }
        // No rise from here. In the middle of a room there is no wall to start one in: walk to the nearest.
        Direction toWall = wallToWalkTo(bot, world, feet);
        if (toWall != null) {
            walk(bot, world, feet, toWall);
            return;
        }
        end(bot, "dig_out_blocked:" + lastRockRefusal);
    }

    /** Opens the next closed cell of {@code move}, or climbs onto its landing once every cell is open. */
    private void rise(AIPlayerEntity bot, ServerLevel world, BlockPos feet, OreClimb.Move move) {
        for (BlockPos cell : OreClimb.bodyCells(feet, move)) {
            if (!ObservableWorldQuery.canObserveCell(bot, cell) && !ObservableWorldQuery.canObserveBlock(bot, cell)) {
                // Not in view yet: the cells before it are opened first and bring it into view.
                continue;
            }
            if (world.getBlockState(cell).isAir()) {
                continue;
            }
            mine(bot, move, cell);
            return;
        }
        miner.cancel(bot);
        miner.naturalTerrainOnly(true);
        BlockPos landing = OreClimb.landing(feet, move);
        step(bot, world, feet, landing, WalkedStep.Kind.STEP_UP, true);
    }

    private void mine(AIPlayerEntity bot, OreClimb.Move move, BlockPos cell) {
        if (!cell.equals(miner.target())) {
            bot.getActionPack().stopMovement();
        }
        miner.begin(bot, cell);
        BlockMiner.Status status = miner.tick(bot);
        if (status == BlockMiner.Status.DONE) {
            lastProgressTick = elapsed;
        } else if (status == BlockMiner.Status.FAILED) {
            // The cell cannot be opened (a break the rule refuses, a block that will not give): this step is not made again.
            BlockPos feet = bot.blockPosition();
            failedSteps.add(stepKey(feet, OreClimb.landing(feet, move)));
            BotLog.action(bot, "dig_out_mine_failed", "cell", cell.toShortString(), "reason", miner.failureReason());
        }
    }

    /**
     * The direction to take one step in toward the nearest wall the bot can see at its own level, when
     * that wall is two or more cells away (a wall next to the bot is one a rise starts in) and the
     * first cell toward it can be walked onto. Each such step brings the nearest wall one closer, so
     * the walk ends beside one.
     */
    private Direction wallToWalkTo(AIPlayerEntity bot, ServerLevel world, BlockPos feet) {
        Direction best = null;
        int bestDistance = Integer.MAX_VALUE;
        int range = ObservableWorldQuery.visibleRangeBlocks(bot);
        for (Direction direction : HORIZONTAL) {
            for (int distance = 1; distance <= range; distance++) {
                BlockPos cell = feet.relative(direction, distance);
                if (!ObservableWorldQuery.canObserveCell(bot, cell) && !ObservableWorldQuery.canObserveBlock(bot, cell)) {
                    break;
                }
                if (!world.getBlockState(cell).getCollisionShape(world, cell).isEmpty()) {
                    BlockPos first = feet.relative(direction);
                    if (distance >= 2 && distance < bestDistance && !failedSteps.contains(stepKey(feet, first))
                            && !visitedAtThisLevel.contains(first) && Standability.isStandable(world, first)) {
                        best = direction;
                        bestDistance = distance;
                    }
                    break;
                }
            }
        }
        return best;
    }

    private void walk(AIPlayerEntity bot, ServerLevel world, BlockPos feet, Direction direction) {
        step(bot, world, feet, feet.relative(direction), WalkedStep.Kind.FLAT, false);
    }

    /** Starts a walked step from {@code feet} onto {@code landing}, or records it as one that cannot be made. */
    private void step(AIPlayerEntity bot, ServerLevel world, BlockPos feet, BlockPos landing,
                      WalkedStep.Kind kind, boolean rises) {
        Standability.clearCache();
        if (!Standability.isStandable(world, landing)) {
            failedSteps.add(stepKey(feet, landing));
            return;
        }
        ActionPack pack = bot.getActionPack();
        if (pack.stepAdmissionBlocked()) {
            return;
        }
        String refused = WalkedStep.refusal(bot, landing, kind);
        if (refused != null) {
            failedSteps.add(stepKey(feet, landing));
            BotLog.action(bot, "dig_out_step_refused", "from", feet.toShortString(),
                    "to", landing.toShortString(), "why", refused);
            return;
        }
        lease = pack.runStep(WalkedStep.begin(bot, landing, kind, "dig_out_step"));
        if (lease != null) {
            stepOrigin = feet.immutable();
            stepLanding = landing.immutable();
            stepRises = rises;
        }
    }

    /** True while a step is in flight; settles it once it has ended. */
    private boolean holdForStep(AIPlayerEntity bot) {
        if (lease == null) {
            return false;
        }
        ActionPack pack = bot.getActionPack();
        if (pack.stepInFlightFor(lease)) {
            return true;
        }
        WalkedStep.Result result = pack.stepResultFor(lease);
        lease = null;
        if (result != null && result.succeeded() && bot.blockPosition().equals(stepLanding)) {
            if (stepRises) {
                risen++;
                visitedAtThisLevel.clear();
            }
            lastProgressTick = elapsed;
            BotLog.action(bot, "dig_out_step", "to", stepLanding.toShortString(), "risen", risen);
            return false;
        }
        // Lost or failed: this step is not made again from there, and the bot is left to settle.
        failedSteps.add(stepKey(stepOrigin, stepLanding));
        BotLog.action(bot, "dig_out_step_failed", "from", stepOrigin.toShortString(), "to", stepLanding.toShortString(),
                "why", result == null ? "cancelled" : result.reason());
        return !pack.stepIdle();
    }

    private void end(AIPlayerEntity bot, String reason) {
        miner.cancel(bot);
        bot.getActionPack().stopAll();
        BotLog.action(bot, "dig_out_ended", "reason", reason, "at", bot.blockPosition().toShortString(), "risen", risen);
        BrainCoordinator.INSTANCE.sendPanelChat(bot, "system", bot.getGameProfile().name()
                + " could not dig its way out of the dark at " + bot.blockPosition().toShortString() + ": " + reason);
        fail(reason);
    }

    private static String stepKey(BlockPos origin, BlockPos landing) {
        return origin.toShortString() + ">" + landing.toShortString();
    }

    @Override
    protected void onPause(AIPlayerEntity bot) {
        stopWork(bot);
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        stopWork(bot);
    }

    private void stopWork(AIPlayerEntity bot) {
        miner.cancel(bot);
        miner.naturalTerrainOnly(true);
        if (lease != null && bot.getActionPack().stepInFlightFor(lease)) {
            bot.getActionPack().cancelStep();
        }
        lease = null;
        bot.getActionPack().stopAll();
    }
}
