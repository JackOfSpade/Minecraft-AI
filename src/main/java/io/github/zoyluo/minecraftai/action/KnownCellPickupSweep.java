package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;

/**
 * Bounded walk around a remembered break cell whose drop is not observable.
 *
 * <p>{@link HarvestCore#approachKnownPickupCell} parks the bot in the standable cell nearest to the
 * break cell and only nudges inside it. That is enough while the drop stays in the break cell, but a
 * freshly broken log that still has a log above it (its own cell is then not standable) pops out
 * with a launch velocity and rests one or two cells away, frequently behind the logs that are still
 * standing, so it is never "observable" and never chased: the bot nudges for the whole window while
 * the item lies just outside vanilla's pickup reach, the ledger expires with a pickup miss and one
 * more block is broken for a drop that was collected all along. A player simply walks around the
 * spot. This sweep does the same with factual coordinates only: after a short dwell in the first
 * stand cell it visits the other standable cells within {@link #RADIUS} of the break cell, nearest
 * first, each through an exact no-dig/no-pillar surface route ({@link HarvestCore#startExactPickupPath}).
 * The caller owns the pickup window and stops calling once the item is collected.
 */
public final class KnownCellPickupSweep {
    /** Ticks spent in a cell before moving on (vanilla item pickup delay is 10 ticks). */
    static final int DWELL_TICKS = 12;
    /** Horizontal (Chebyshev) reach of the sweep around the break cell. */
    static final int RADIUS = 2;
    /** Failed route starts / arrival attempts tolerated per cell before it is skipped. */
    static final int MAX_ATTEMPTS = 3;

    /** What one {@link #step} did, so the caller logs a walk only when one really started. */
    public enum Step {
        /** A route (or a descent) toward the current target cell was started by this call. */
        MOVING,
        /** The bot is standing in the target cell, nudging toward the break cell, waiting for the pickup. */
        DWELLING,
        /** Another controller owns a walked step; the pickup ledger retains this candidate and retries later. */
        WAITING,
        /** The current cell was given up on (no route / attempts used up); nothing moved this call. */
        SKIPPED,
        /** Every candidate cell has been visited: nothing left to try. */
        EXHAUSTED
    }

    private final BlockPos origin;
    private final Set<BlockPos> visited = new HashSet<>();
    private BlockPos target;
    private boolean firstTarget = true;
    private boolean targetIsFirst;
    private int dwell;
    private int attempts;
    private int cellsVisited;

    public KnownCellPickupSweep(BlockPos origin) {
        this.origin = origin.immutable();
    }

    public BlockPos origin() {
        return origin;
    }

    /** Number of cells the sweep has finished with (dwelled in or skipped); for logging. */
    public int cellsVisited() {
        return cellsVisited;
    }

    /**
     * One step of the sweep; call it only while the bot is not already moving. Reports what it did;
     * {@link Step#EXHAUSTED} once every candidate cell has been visited (nothing left to try).
     */
    public Step step(AIPlayerEntity bot) {
        if (!bot.getActionPack().stepIdle()) {
            return Step.WAITING;
        }
        if (target == null) {
            target = nextTarget(bot);
            if (target == null) {
                return Step.EXHAUSTED;
            }
            dwell = 0;
            attempts = 0;
        }
        if (bot.blockPosition().equals(target)) {
            if (targetIsFirst) {
                // Same physical handling as the plain approach: nudge toward the remembered cell. The
                // approach re-resolves its stand cell every call, so when the terrain changed under the
                // dwell it starts a route (or a descent) instead of nudging: that is a move, not a dwell.
                BlockPos before = bot.blockPosition();
                HarvestCore.approachKnownPickupCell(bot, origin);
                if (!before.equals(bot.blockPosition())
                        || !bot.getActionPack().isPathExecutorIdle()
                        || !bot.getActionPack().isWalkToIdle()) {
                    BotLog.action(bot, "pickup_sweep_first_cell_reapproach",
                            "origin", origin.toShortString(),
                            "from", before.toShortString(),
                            "to", bot.blockPosition().toShortString());
                    retire();
                    return Step.MOVING;
                }
            } else if (target.equals(origin)) {
                bot.getActionPack().cancelStep();
                bot.getActionPack().stopMovement();
            } else {
                InCellWalk.nudgeToward(bot, target, origin.getCenter(), "physical_drop_pickup");
            }
            if (++dwell >= DWELL_TICKS) {
                retire();
            }
            return Step.DWELLING;
        }
        if (++attempts > MAX_ATTEMPTS) {
            retire();
            return Step.SKIPPED;
        }
        boolean started = targetIsFirst
                ? HarvestCore.approachKnownPickupCell(bot, origin)
                : HarvestCore.startExactPickupPath(bot, target);
        if (!started) {
            retire();
            return Step.SKIPPED;
        }
        return Step.MOVING;
    }

    private void retire() {
        visited.add(target);
        target = null;
        cellsVisited++;
    }

    private BlockPos nextTarget(AIPlayerEntity bot) {
        Standability.clearCache();
        if (firstTarget) {
            firstTarget = false;
            BlockPos stand = HarvestCore.pickupStandPos(bot, origin);
            if (stand != null) {
                targetIsFirst = true;
                return stand.immutable();
            }
        }
        targetIsFirst = false;
        BlockPos current = bot.blockPosition();
        BlockPos best = null;
        double bestOriginDistance = Double.MAX_VALUE;
        double bestBotDistance = Double.MAX_VALUE;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos cell = origin.offset(dx, dy, dz);
                    if (visited.contains(cell) || !Standability.isStandable(bot.level(), cell)) {
                        continue;
                    }
                    double originDistance = cell.distSqr(origin);
                    double botDistance = cell.distSqr(current);
                    if (originDistance < bestOriginDistance
                            || (originDistance == bestOriginDistance && botDistance < bestBotDistance)) {
                        best = cell;
                        bestOriginDistance = originDistance;
                        bestBotDistance = botDistance;
                    }
                }
            }
        }
        return best == null ? null : best.immutable();
    }
}
