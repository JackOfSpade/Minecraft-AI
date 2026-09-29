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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import io.github.zoyluo.minecraftai.mining.assist.BotEdits;

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
 *       cell it steps into must keep a solid floor, so it never digs into an unknown drop. The
 *       floor test reads the same raw floor state the pathfinder's own dig-enterable check does
 *       ({@code NeighborEnumerator#digEnterable}) and nothing more; once a cell is opened the bot
 *       stands next to it and the floor is re-checked before it steps in;</li>
 *   <li>only cells in {@code NeighborEnumerator}'s natural-block whitelist (stone family, dirt,
 *       sand, gravel, ores) that are currently observable and that the carried tools can break --
 *       and never a cell a bot placed itself (the mining-assist {@code BotEdits} ledger, which is
 *       only kept while that assist mode is on) nor a typical building block
 *       ({@link #isBuildingBlock}: cobblestone, bricks, planks, doors, glass, wool, stairs...);</li>
 *   <li>never through or next to an observed fluid, and never under a suspended falling block;</li>
 *   <li>at most {@link #MAX_CELLS} cells, and it stops as soon as the way ahead is open.</li>
 * </ul>
 *
 * <p><b>Limits (not a guarantee).</b> A block cannot tell natural terrain from a player's wall:
 * plain stone, dirt, sand or gravel that somebody else placed looks exactly like the natural kind,
 * and the placed-block ledger only knows the bots' own placements. What is guaranteed is exactly the
 * list above; digging stays a last resort after a whole stall window, bounded to {@link #MAX_CELLS} cells.
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
    boolean start(AIPlayerEntity bot, ServerPlayer target) {
        if (active) {
            return true;
        }
        Standability.clearCache();
        BlockPos feet = bot.blockPosition();
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
                            "pos", LogFields.pos(feet), "dir", candidate.getSerializedName());
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
        ServerLevel world = bot.level();
        BlockPos feet = bot.blockPosition();
        BlockPos ahead = feet.relative(direction);
        if (isOpen(world, ahead) && isOpen(world, ahead.above())) {
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

    private boolean stepInto(AIPlayerEntity bot, ServerLevel world, BlockPos ahead) {
        Standability.clearCache();
        if (!isSolidFloor(world, ahead.below()) || DigNav.adjacentHazardFluid(bot, ahead)
                || DigNav.adjacentHazardFluid(bot, ahead.above())) {
            return finish(bot, "step_unsafe");
        }
        if (!FakePlayerMotion.stepToStandable(bot, ahead, "follow_dig_step")) {
            // Momentarily occupied (the followed player standing in the gap): try again shortly.
            return ++stepFailures >= MAX_STEP_FAILURES ? finish(bot, "step_blocked") : true;
        }
        advanced++;
        stepFailures = 0;
        BlockPos next = ahead.relative(direction);
        if (advanced >= MAX_CELLS || (isOpen(world, next) && isOpen(world, next.above()))) {
            return finish(bot, "through");
        }
        return true;
    }

    private boolean finish(AIPlayerEntity bot, String reason) {
        if (active) {
            bot.getActionPack().stopMining();
            BotLog.action(bot, "follow_dig_out_finished", "reason", reason, "cells", advanced,
                    "pos", LogFields.pos(bot.blockPosition()));
        }
        active = false;
        direction = null;
        return false;
    }

    private record Plan(BlockPos cell) {
    }

    /** The next block to break (feet cell first, then head cell), or null when it is not safe/possible. */
    private static Plan plan(AIPlayerEntity bot, BlockPos feet, Direction direction) {
        ServerLevel world = bot.level();
        BlockPos stand = feet.relative(direction);
        BlockPos head = stand.above();
        if (!isSolidFloor(world, stand.below())) {
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
                    || isBuildingBlock(state)
                    || BotEdits.wasPlaced(world, cell)
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
                || world.getBlockState(head.above()).getBlock() instanceof FallingBlock) {
            return null;
        }
        return new Plan(first);
    }

    /**
     * Blocks that are far more likely to be somebody's construction than terrain. The pathfinder's
     * whitelist already excludes most of them; this is the explicit backstop (and the one place that
     * removes cobblestone, which the whitelist allows for the bot's own mining).
     */
    static boolean isBuildingBlock(BlockState state) {
        return state.is(Blocks.COBBLESTONE) || state.is(Blocks.MOSSY_COBBLESTONE)
                || state.is(Blocks.STONE_BRICKS) || state.is(Blocks.BRICKS)
                || state.is(Blocks.SMOOTH_STONE) || state.is(Blocks.POLISHED_ANDESITE)
                || state.is(Blocks.POLISHED_DIORITE) || state.is(Blocks.POLISHED_GRANITE)
                || state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE)
                || state.is(BlockTags.PLANKS) || state.is(BlockTags.DOORS)
                || state.is(BlockTags.TRAPDOORS) || state.is(BlockTags.FENCES)
                || state.is(BlockTags.FENCE_GATES) || state.is(BlockTags.WALLS)
                || state.is(BlockTags.STAIRS) || state.is(BlockTags.SLABS)
                || state.is(BlockTags.WOOL) || state.is(BlockTags.BEDS)
                || state.is(BlockTags.IMPERMEABLE) || state.is(BlockTags.ICE);
    }

    private static boolean isOpen(ServerLevel world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.getCollisionShape(world, pos).isEmpty() && state.getFluidState().isEmpty()
                && !Standability.isDangerous(state);
    }

    private static boolean isSolidFloor(ServerLevel world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return !state.getCollisionShape(world, pos).isEmpty()
                && state.getFluidState().isEmpty() && !Standability.isDangerous(state);
    }

    private static boolean hasSuitableTool(AIPlayerEntity bot, BlockState state) {
        if (!state.requiresCorrectToolForDrops()) {
            return true;
        }
        for (ItemStack stack : bot.getInventory().getNonEquipmentItems()) {
            if (!stack.isEmpty() && stack.isCorrectToolForDrops(state)) {
                return true;
            }
        }
        return bot.getItemBySlot(EquipmentSlot.OFFHAND).isCorrectToolForDrops(state);
    }
}
