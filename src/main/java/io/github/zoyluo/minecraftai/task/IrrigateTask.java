package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.BlockMiner;
import io.github.zoyluo.minecraftai.action.BuildAction;
import io.github.zoyluo.minecraftai.action.FarmAction;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.MaterialPalette;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * Builds a 2x2 infinite water source (for irrigation / water collection). Digs a 1-deep 2x2 pit
 * into the ground at center, then pours 1 water bucket into each of the two diagonal cells; the
 * other two cells are each orthogonally adjacent to 2 source blocks -> after a few ticks the
 * flowing water automatically converts to source blocks -> all 4 cells become source blocks.
 * A 2x2 pool that is all source blocks like this is an "infinite water source": scooping out any
 * one cell causes the other 3 to refill it back to a source block; it also irrigates farmland
 * within 4 blocks around it.
 * Requires >=2 WATER_BUCKET in inventory (each becomes an empty BUCKET after pouring).
 */
public final class IrrigateTask extends AbstractTask {
    private enum Phase {GOTO, DIG, PLACE, SETTLE, DONE}

    private static final int SETTLE_TICKS = 20; // After placing water, wait for the flow to spread and the two empty cells to convert into source blocks
    private final BlockPos center;
    private final List<BlockPos> cells = new ArrayList<>(); // The 4 cells of the 2x2 area (same y layer)
    private final BlockMiner digMiner = new BlockMiner();
    private Phase phase = Phase.GOTO;
    private int digIndex;
    private boolean digFloorPlaced;
    private int settle;
    private String note = "";

    public IrrigateTask(BlockPos center) {
        this.center = center.toImmutable();
    }

    @Override
    public String name() {
        return "irrigate";
    }

    @Override
    public String describe() {
        return "irrigate center=" + center.getX() + "," + center.getY() + "," + center.getZ()
                + " phase=" + phase + (note.isBlank() ? "" : " note=" + note);
    }

    @Override
    public double progress() {
        return switch (phase) {
            case GOTO -> 0.1D;
            case DIG -> 0.4D;
            case PLACE -> 0.7D;
            case SETTLE -> 0.9D;
            case DONE -> 1.0D;
        };
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        cells.clear();
        cells.add(center);
        cells.add(center.offset(Direction.EAST));
        cells.add(center.offset(Direction.SOUTH));
        cells.add(center.offset(Direction.EAST).offset(Direction.SOUTH));
        phase = Phase.GOTO;
        digIndex = 0;
        digFloorPlaced = false;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > 2400) {
            fail("irrigate_timeout phase=" + phase);
            return;
        }
        switch (phase) {
            case GOTO -> goToCenter(bot);
            case DIG -> dig(bot);
            case PLACE -> place(bot);
            case SETTLE -> settle();
            case DONE -> complete();
        }
    }

    private void goToCenter(AIPlayerEntity bot) {
        if (bot.getEyePos().distanceTo(center.toCenterPos()) <= 4.5D) {
            bot.getActionPack().stopAll();
            phase = Phase.DIG;
            return;
        }
        BlockPos stand = adjacentStand(bot, center);
        if (stand == null) {
            // No adjacent cell to stand on: still proceed to DIG and let BlockMiner attempt to mine
            // from the bot's current position (if out of reach, it will fail through the normal
            // mining logic due to exceeding interaction range, rather than instantly breaking the block).
            note = "unreachable";
            phase = Phase.DIG;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            bot.getActionPack().startPathTo(stand);
        }
    }

    /**
     * Digs the 2x2 pit one cell at a time: floors any open cell below with a real block drawn from
     * inventory (never conjured), then mines the cell itself through the same tool-and-hardness-
     * paced BlockMiner every other digging task uses -- no instant/creative-style block removal.
     */
    private void dig(AIPlayerEntity bot) {
        if (digIndex >= cells.size()) {
            phase = Phase.PLACE;
            return;
        }
        BlockPos cell = cells.get(digIndex);
        ServerWorld world = bot.getEntityWorld();
        // The pit floor must be solid (otherwise water leaks downward); the existing ground on all sides serves as the retaining wall.
        if (!digFloorPlaced) {
            BlockState below = world.getBlockState(cell.down());
            if (below.isAir() || !world.getFluidState(cell.down()).isEmpty()) {
                OptionalInt slot = MaterialPalette.pickSacrificialBlockSlot(bot);
                if (slot.isEmpty()) {
                    fail("irrigate_missing_floor_material");
                    return;
                }
                if (InventoryAction.equipFromSlot(bot, slot.getAsInt()) < 0) {
                    fail("irrigate_floor_equip_failed");
                    return;
                }
                ActionResult placed = BuildAction.placeBlockAt(bot, cell.down());
                if (placed.isFailed()) {
                    fail("irrigate_floor_place_failed:" + placed.reason());
                    return;
                }
            }
            digFloorPlaced = true;
        }
        // Clear the pit interior to air to hold water: mined tick-by-tick based on real hardness/tool speed, not destroyed instantly.
        if (world.getBlockState(cell).isAir()) {
            digIndex++;
            digFloorPlaced = false;
            return;
        }
        if (digMiner.target() == null) {
            digMiner.begin(bot, cell);
        }
        BlockMiner.Status status = digMiner.tick(bot);
        if (status == BlockMiner.Status.FAILED) {
            fail("irrigate_dig_failed:" + digMiner.failureReason());
            return;
        }
        if (status == BlockMiner.Status.DONE) {
            digIndex++;
            digFloorPlaced = false;
        }
    }

    private void place(AIPlayerEntity bot) {
        // Place water in the two diagonal cells (cells[0] and cells[3]); the other two cells (cells[1]/[2]) are each adjacent to 2 source blocks and automatically become source blocks after SETTLE.
        ActionResult a = FarmAction.placeWater(bot, cells.get(0));
        if (a.isFailed()) {
            fail("place_water_failed:" + a.reason());
            return;
        }
        ActionResult b = FarmAction.placeWater(bot, cells.get(3));
        if (b.isFailed()) {
            fail("place_water_failed:" + b.reason());
            return;
        }
        phase = Phase.SETTLE;
    }

    private void settle() {
        settle++;
        if (settle >= SETTLE_TICKS) {
            phase = Phase.DONE;
        }
    }

    private static BlockPos adjacentStand(AIPlayerEntity bot, BlockPos target) {
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockPos candidate = target.offset(d).up();
            if (Standability.isStandable(bot.getEntityWorld(), candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
