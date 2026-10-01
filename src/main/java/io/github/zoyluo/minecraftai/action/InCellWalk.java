package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The small walks a bot makes inside one cell with its movement keys where the old code teleported it a few tenths of a block: a
 * few steps toward an item that lies on the far side of the cell (the pickup nudge), the walk back to the middle of the cell before
 * a placement, and the sneak shift over the edge of the support with the walk back from it. Each one is a {@link WalkedStep}
 * ({@code RECENTER} or {@code SNEAK_SHIFT}) run through {@link ActionPack#runStep}: the bot moves at the speed a player has, the next
 * action of the owner happens only when the step has ended, and nothing here ever moves the bot itself. A guarded owner can
 * temporarily refuse a new step admission; that is not a completed in-cell walk.
 *
 * <p>A walk inside a cell is finished when the bot is within {@link WalkedStepRules#POINT_TOLERANCE} of its point, so a point that
 * close to where the bot already stands is "there" (no step is started).</p>
 */
public final class InCellWalk {
    /** How far from the middle of its cell a bot walks toward an item (the body stays inside the cell it stands in). */
    public static final double NUDGE_REACH = 0.4D;
    /**
     * The longest in-cell nudge: its centre stops 0.1 blocks short of the cell edge. A player's
     * roughly 0.3-block half-width therefore overhangs the edge by about 0.2 blocks; it is the
     * centre/collision cell, not the whole body, that must remain in the owning cell.
     */
    public static final double MAX_NUDGE_REACH = 0.4D;
    /** How far over the edge of its support a sneaking bot leans to place a block on the side face of that support. */
    public static final double EDGE_SHIFT = 0.62D;
    /** A bot moving faster than this (squared, per tick) has not stopped yet. */
    private static final double SETTLED_SPEED_SQUARED = 1.0E-8D;
    private static final double REACH_STEP = 0.05D;
    /** Steps a recentre is given before its owner is told it cannot be done. */
    private static final int MAX_RECENTER_STARTS = 4;

    private int recenterStarts;
    private WalkedStep recenterStep;

    /** What one call of {@link #recenter} found. */
    public enum Centering {
        /** The bot stands (still) within the arrival tolerance of the middle of the cell. */
        CENTERED,
        /** A step toward the middle is in flight (or was started by this call): ask again next tick. */
        WALKING,
        /** The bot is not in the cell, or the walk was refused or ended short too many times. */
        FAILED
    }

    /** Forgets the failed walks of an earlier centring (a new transaction starts with a clean count). */
    public void reset() {
        recenterStarts = 0;
        recenterStep = null;
    }

    /**
     * Walks the bot toward {@code target} inside its own cell, as far as it can go without leaving the cell or touching a block
     * ({@code reach} at most, {@link #MAX_NUDGE_REACH}): what a player does to get an item on the far side of the block, close enough
     * for vanilla's pickup box. Call it every tick while the item is wanted: a step in flight is left alone, a bot that is already at
     * the point is left alone. Returns whether the nudge is in place or under way (false: the bot is not standing supported in
     * {@code anchorFeet}, the cell is not standable, or there is no room to move).
     */
    public static boolean nudgeToward(AIPlayerEntity bot, BlockPos anchorFeet, Vec3 target, double reach, String reason) {
        ActionPack pack = bot.getActionPack();
        if (!bot.blockPosition().equals(anchorFeet)) {
            return false;
        }
        if (!pack.stepIdle()) {
            return true;
        }
        if (!WalkedStep.supported(bot)) {
            return false;
        }
        ServerLevel world = bot.level();
        Standability.clearCache();
        if (!Standability.isStandable(world, anchorFeet)) {
            return false;
        }
        double centerX = anchorFeet.getX() + 0.5D;
        double centerZ = anchorFeet.getZ() + 0.5D;
        double vx = target.x - centerX;
        double vz = target.z - centerZ;
        double length = Math.sqrt(vx * vx + vz * vz);
        if (length < 1.0E-6D) {
            return false;
        }
        double ux = vx / length;
        double uz = vz / length;
        double free = 0.0D;
        double limit = Mth.clamp(reach, REACH_STEP, MAX_NUDGE_REACH);
        for (double r = REACH_STEP; r <= limit + 1.0E-9D; r += REACH_STEP) {
            AABB moved = bot.getBoundingBox().move(centerX + ux * r - bot.getX(), 0.0D, centerZ + uz * r - bot.getZ());
            if (!world.noCollision(bot, moved)) {
                break;
            }
            free = r;
        }
        if (free < REACH_STEP) {
            BotLog.action(bot, "pickup_nudge_rejected", "reason", reason, "anchor", anchorFeet);
            return false;
        }
        Vec3 point = new Vec3(centerX + ux * free, bot.getY(), centerZ + uz * free);
        if (Math.hypot(point.x - bot.getX(), point.z - bot.getZ()) <= WalkedStepRules.POINT_TOLERANCE) {
            return true;
        }
        WalkedStep step = WalkedStep.begin(bot, point, WalkedStep.Kind.RECENTER, reason);
        return pack.runStep(step) != null;
    }

    /** {@link #nudgeToward} with the default reach. */
    public static boolean nudgeToward(AIPlayerEntity bot, BlockPos anchorFeet, Vec3 target, String reason) {
        return nudgeToward(bot, anchorFeet, target, NUDGE_REACH, reason);
    }

    /**
     * Keeps a bot that stands in {@code anchorFeet} walking back to the middle of it until it is there and has stopped. Call it every
     * tick while the owner needs the centred pose (a placement, a drop into a pocket) and act only on {@link Centering#CENTERED}.
     */
    public Centering recenter(AIPlayerEntity bot, BlockPos anchorFeet, String reason) {
        ActionPack pack = bot.getActionPack();
        if (!pack.stepIdle()) {
            return Centering.WALKING;
        }
        if (!bot.blockPosition().equals(anchorFeet)) {
            return Centering.FAILED;
        }
        Vec3 middle = new Vec3(anchorFeet.getX() + 0.5D, bot.getY(), anchorFeet.getZ() + 0.5D);
        double offset = Math.hypot(middle.x - bot.getX(), middle.z - bot.getZ());
        if (offset <= WalkedStepRules.POINT_TOLERANCE && WalkedStep.supported(bot)) {
            if (bot.getDeltaMovement().horizontalDistanceSqr() <= SETTLED_SPEED_SQUARED) {
                recenterStarts = 0;
                return Centering.CENTERED;
            }
            return Centering.WALKING;
        }
        // A step that ended because the bot was not on its feet at that moment (still settling out of a hop or a fall) says nothing
        // about the cell: it is asked again next tick and does not count against the owner.
        boolean unsettled = recenterStep != null && recenterStep.outcome() != null
                && recenterStep.outcome().failed() && "not_supported".equals(recenterStep.outcome().reason());
        if (!unsettled && recenterStarts + 1 > MAX_RECENTER_STARTS) {
            return Centering.FAILED;
        }
        WalkedStep candidate = WalkedStep.begin(bot, middle, WalkedStep.Kind.RECENTER, reason);
        if (pack.runStep(candidate) == null) {
            // A foreign guarded owner has not released its completed step yet.  Keep the prior
            // retry count and step outcome intact so this harmless admission delay neither
            // consumes a retry nor publishes a step that the pack never owns.
            return Centering.WALKING;
        }
        if (!unsettled) {
            recenterStarts++;
        }
        recenterStep = candidate;
        return Centering.WALKING;
    }

    /**
     * Starts the sneak shift over the edge of the support of {@code anchorFeet} toward {@code direction}: the bot leans out by
     * {@link #EDGE_SHIFT} (a sneaking body does not fall off its support) so the side face of the support is in view of a placement.
     * Returns the step (its {@link WalkedStep#ended} and {@link WalkedStep#outcome} tell when and how it ended, whatever else the pack
     * runs meanwhile), or null when it cannot start (not standing supported in the anchor, a vertical direction, or another guarded
     * owner refuses admission). Sneak stays held after a successful shift.
     */
    public static WalkedStep beginEdgeShift(AIPlayerEntity bot, BlockPos anchorFeet, Direction direction, String reason) {
        if (direction.getAxis().isVertical() || !bot.blockPosition().equals(anchorFeet) || !WalkedStep.supported(bot)) {
            BotLog.action(bot, "edge_shift_rejected", "reason", "invalid_origin:" + reason,
                    "from", bot.blockPosition(), "anchor", anchorFeet, "direction", direction);
            return null;
        }
        Vec3 point = new Vec3(anchorFeet.getX() + 0.5D + direction.getStepX() * EDGE_SHIFT, bot.getY(),
                anchorFeet.getZ() + 0.5D + direction.getStepZ() * EDGE_SHIFT);
        WalkedStep step = WalkedStep.beginAnchored(bot, anchorFeet, point, WalkedStep.Kind.SNEAK_SHIFT, reason);
        return bot.getActionPack().runStep(step) == null ? null : step;
    }

    /**
     * Starts the walk from the shifted pose back to the middle of {@code anchorFeet} (sneak stays on until the step ends), or returns
     * {@code null} when another guarded owner refuses its admission.
     */
    public static WalkedStep beginEdgeReturn(AIPlayerEntity bot, BlockPos anchorFeet, String reason) {
        Vec3 middle = new Vec3(anchorFeet.getX() + 0.5D, bot.getY(), anchorFeet.getZ() + 0.5D);
        WalkedStep step = WalkedStep.beginAnchored(bot, anchorFeet, middle, WalkedStep.Kind.RECENTER, reason);
        return bot.getActionPack().runStep(step) == null ? null : step;
    }
}
