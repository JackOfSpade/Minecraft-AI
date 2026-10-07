package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.MiningSafety;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The verdict on one step of a stair dug through rock ({@link OreClimb} lays it out; OreDig digs it up
 * to an ore and {@link DigOutTask} up out of a dark pocket): whether what the digger can see forbids
 * opening the step's cells or standing where it ends. Nothing is read that is not in view, so a cell
 * is judged once the cells opened before it have brought it into view.
 */
final class StairDig {
    /** The stair's treads have a floor or the stair ends: nothing is placed under one. */
    static final String OPEN_DROP = "open_drop";

    private StairDig() {
    }

    /** Why a step is refused, and the cell the reason was found at: a cell of the step, or the floor it lands on. */
    record Refusal(String reason, BlockPos cell) {
    }

    /**
     * The first reason, from what {@code bot} can see, that {@code move} cannot be opened and stood on
     * safely, or null when nothing seen forbids it. Every cell of the step is checked by
     * {@link MiningSafety#openingRefusal(AIPlayerEntity, BlockPos)}, an open one included: what an
     * opening revealed over or beside it is the reason to stop before the next cell is opened.
     */
    static String refusal(AIPlayerEntity bot, ServerLevel world, BlockPos feet, OreClimb.Move move) {
        Refusal refusal = refusalAt(bot, world, feet, move);
        return refusal == null ? null : refusal.reason();
    }

    /** {@link #refusal} with the cell it was found at: the digger that opened that cell is the one that may close it again. */
    static Refusal refusalAt(AIPlayerEntity bot, ServerLevel world, BlockPos feet, OreClimb.Move move) {
        for (BlockPos cell : OreClimb.bodyCells(feet, move)) {
            String refusal = MiningSafety.openingRefusal(bot, cell);
            if (refusal != null) {
                return new Refusal(refusal, cell);
            }
        }
        BlockPos floor = OreClimb.landing(feet, move).below();
        if (ObservableWorldQuery.canObserveCell(bot, floor) || ObservableWorldQuery.canObserveBlock(bot, floor)) {
            BlockState floorState = world.getBlockState(floor);
            if (!floorState.getFluidState().isEmpty()) {
                return new Refusal(floorState.getFluidState().is(FluidTags.WATER) ? "water" : "lava", floor);
            }
            if (Standability.isDangerous(floorState) || floorState.getCollisionShape(world, floor).isEmpty()) {
                return new Refusal(OPEN_DROP, floor);
            }
        }
        return null;
    }
}
