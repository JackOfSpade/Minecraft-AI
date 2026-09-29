package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.action.DigNav;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogFields;
import io.github.zoyluo.minecraftai.mode.FakePlayerMotion;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.pathfinding.NeighborEnumerator;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import net.minecraft.block.BlockState;
import net.minecraft.block.FallingBlock;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * The last thing {@link FollowStuckRecovery} tries before telling the player it is stuck: with a
 * tool that can actually break the blocks, tunnel a short horizontal passage toward the player
 * through the natural terrain that blocks the way. It is only reached once walking, replanning and
 * every adjacent verified step have failed for a whole stall window.
 *
 * <p>Deliberately narrow, reusing the ordinary legal machinery (the {@link ActionPack} mining
 * controller, the pathfinder's own dig whitelist, {@link DigNav}'s observed-hazard test):
 * <ul>
 *   <li>only horizontal, at the bot's own feet and head level -- never below the feet, and every
 *       cell it steps into must keep a solid floor, so it never digs into an unknown drop;</li>
 *   <li>only cells in {@code NeighborEnumerator}'s natural-block whitelist (stone family, dirt,
 *       sand, gravel, ores) that are currently observable and that the carried tools can break --
 *       never doors, chests, beds, glass, planks or any other block entity / built block;</li>
 *   <li>never through or next to an observed fluid, and never under a suspended falling block;</li>
 *   <li>at most {@link #MAX_CELLS} cells, and it stops as soon as the way ahead is open.</li>
 * </ul>
 */
final class FollowDigOut {
    static final int MAX_CELLS = 8;
    private static final int MAX_TICKS = 600;
    private static final int MAX_STEP_FAILURES = 8;

    private boolean active;
    private Direction direction;
    private int advanced;
    private int ticks;
    private int stepFailures;

    boolean isActive() {
        return active;
    }

    /**
     * Starts a tunnel toward the player when a safe first cell exists.
     *
     * @return true when a dig-out is now active
     */
    boolean start(AIPlayerEntity bot, ServerPlayerEntity target) {
        if (active) {
            return true;
        }
        Standability.clearCache();
        BlockPos feet = bot.getBlockPos();
        int dx = target.getBlockX() - feet.getX();
        int dz = target.getBlockZ() - feet.getZ();
        Direction along = Math.abs(dx) >= Math.abs(dz)
                ? (dx >= 0 ? Direction.EAST : Direction.WEST)
                : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
        Direction across = Math.abs(dx) >= Math.abs(dz)
                ? (dz >= 0 ? Direction.SOUTH : Direction.NORTH)
                : (dx >= 0 ? Direction.EAST : Direction.WEST);
        for (Direction candidate : new Direction[]{along, across}) {
            if (candidate == along || (dx != 0 && dz != 0)) {
                if (plan(bot, feet, candidate) != null) {
                    active = true;
                    direction = candidate;
                    advanced = 0;
                    ticks = 0;
                    stepFailures = 0;
                    BotLog.action(bot, "follow_dig_out_started",
                            "pos", LogFields.pos(feet), "dir", candidate.asString());
                    return true;
                }
            }
        }
        return false;
    }

    /** @return true while the dig-out still owns this tick; false once finished or abandoned. */
    boolean tick(AIPlayerEntity bot) {
        if (!active) {
            return false;
        }
        ActionPack pack = bot.getActionPack();
        if (++ticks > MAX_TICKS) {
            return finish(bot, "timeout");
        }
        if (!pack.isMiningIdle()) {
            return true;
        }
        ServerWorld world = bot.getEntityWorld();
        BlockPos feet = bot.getBlockPos();
        BlockPos ahead = feet.offset(direction);
        if (isOpen(world, ahead) && isOpen(world, ahead.up())) {
            return stepInto(bot, world, ahead);
        }
        Plan plan = plan(bot, feet, direction);
        if (plan == null) {
            return finish(bot, "unsafe_or_blocked");
        }
        pack.stopMovement();
        pack.startMining(plan.cell(), direction.getOpposite());
        return true;
    }

    void cancel(AIPlayerEntity bot) {
        if (active) {
            finish(bot, "cancelled");
        }
    }

    private boolean stepInto(AIPlayerEntity bot, ServerWorld world, BlockPos ahead) {
        Standability.clearCache();
        if (!isSolidFloor(world, ahead.down()) || DigNav.adjacentHazardFluid(bot, ahead)
                || DigNav.adjacentHazardFluid(bot, ahead.up())) {
            return finish(bot, "step_unsafe");
        }
        if (!FakePlayerMotion.stepToStandable(bot, ahead, "follow_dig_step")) {
            // Momentarily occupied (the followed player standing in the gap): try again shortly.
            return ++stepFailures >= MAX_STEP_FAILURES ? finish(bot, "step_blocked") : true;
        }
        advanced++;
        stepFailures = 0;
        BlockPos next = ahead.offset(direction);
        if (advanced >= MAX_CELLS || (isOpen(world, next) && isOpen(world, next.up()))) {
            return finish(bot, "through");
        }
        return true;
    }

    private boolean finish(AIPlayerEntity bot, String reason) {
        if (active) {
            bot.getActionPack().stopMining();
            BotLog.action(bot, "follow_dig_out_finished", "reason", reason, "cells", advanced,
                    "pos", LogFields.pos(bot.getBlockPos()));
        }
        active = false;
        direction = null;
        return false;
    }

    private record Plan(BlockPos cell) {
    }

    /** The next block to break (feet cell first, then head cell), or null when it is not safe/possible. */
    private static Plan plan(AIPlayerEntity bot, BlockPos feet, Direction direction) {
        ServerWorld world = bot.getEntityWorld();
        BlockPos stand = feet.offset(direction);
        BlockPos head = stand.up();
        if (!isSolidFloor(world, stand.down())) {
            return null;
        }
        BlockPos first = null;
        for (BlockPos cell : new BlockPos[]{stand, head}) {
            BlockState state = world.getBlockState(cell);
            if (isOpen(world, cell)) {
                continue;
            }
            if (!state.getFluidState().isEmpty()
                    || !NeighborEnumerator.isMineable(world, cell)
                    || !ObservableWorldQuery.canObserveBlock(bot, cell)
                    || !hasSuitableTool(bot, state)) {
                return null;
            }
            if (first == null) {
                first = cell;
            }
        }
        if (first == null) {
            return null;
        }
        if (DigNav.adjacentHazardFluid(bot, stand) || DigNav.adjacentHazardFluid(bot, head)
                || world.getBlockState(head.up()).getBlock() instanceof FallingBlock) {
            return null;
        }
        return new Plan(first);
    }

    private static boolean isOpen(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.getCollisionShape(world, pos).isEmpty() && state.getFluidState().isEmpty()
                && !Standability.isDangerous(state);
    }

    private static boolean isSolidFloor(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return !state.getCollisionShape(world, pos).isEmpty()
                && state.getFluidState().isEmpty() && !Standability.isDangerous(state);
    }

    private static boolean hasSuitableTool(AIPlayerEntity bot, BlockState state) {
        if (!state.isToolRequired()) {
            return true;
        }
        for (ItemStack stack : bot.getInventory().getMainStacks()) {
            if (!stack.isEmpty() && stack.isSuitableFor(state)) {
                return true;
            }
        }
        return bot.getEquippedStack(EquipmentSlot.OFFHAND).isSuitableFor(state);
    }
}
