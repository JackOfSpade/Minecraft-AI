package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.BlockMiner;
import io.github.zoyluo.minecraftai.action.BucketAction;
import io.github.zoyluo.minecraftai.action.BuildAction;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.MaterialPalette;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

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
    private static final int PLACE_RETRIES = 60; // refused pours tolerated while standing still (about 3 s) before the pour is reported failed
    private static final int MAX_REPOSITIONS = 6; // walks to a new stand cell tolerated per pour (a walk itself is never counted per tick)
    private final BlockPos center;
    private final List<BlockPos> cells = new ArrayList<>(); // The 4 cells of the 2x2 area (same y layer)
    private final BlockMiner digMiner = new BlockMiner();
    private Phase phase = Phase.GOTO;
    private int digIndex;
    private boolean digFloorPlaced;
    private int settle;
    private int placedPours;
    private int placeAttempts;
    private int placeRepositions;
    private String note = "";

    public IrrigateTask(BlockPos center) {
        this.center = center.immutable();
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
        cells.add(center.relative(Direction.EAST));
        cells.add(center.relative(Direction.SOUTH));
        cells.add(center.relative(Direction.EAST).relative(Direction.SOUTH));
        phase = Phase.GOTO;
        digIndex = 0;
        digFloorPlaced = false;
        placedPours = 0;
        placeAttempts = 0;
        placeRepositions = 0;
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
        if (bot.getEyePosition().distanceTo(center.getCenter()) <= 4.5D) {
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
        ServerLevel world = bot.level();
        // The pit floor must be solid (otherwise water leaks downward); the existing ground on all sides serves as the retaining wall.
        if (!digFloorPlaced) {
            BlockState below = world.getBlockState(cell.below());
            if (below.isAir() || !world.getFluidState(cell.below()).isEmpty()) {
                OptionalInt slot = MaterialPalette.pickSacrificialBlockSlot(bot);
                if (slot.isEmpty()) {
                    fail("irrigate_missing_floor_material");
                    return;
                }
                if (InventoryAction.equipFromSlot(bot, slot.getAsInt()) < 0) {
                    fail("irrigate_floor_equip_failed");
                    return;
                }
                ActionResult placed = BuildAction.placeBlockAt(bot, cell.below());
                if (placed.isFailed()) {
                    fail("irrigate_floor_place_failed:" + placed.reason());
                    return;
                }
            }
            digFloorPlaced = true;
        }
        // Clear the pit interior to air to hold water: mined tick-by-tick based on real hardness/tool speed, not destroyed instantly.
        // Only skip a cell that is already open when the miner is idle: the mining controller may break the cell
        // between two task ticks, and skipping then would leave the miner holding this cell as a stale target
        // (its next tick would report DONE for the wrong cell and silently skip the following one).
        if (digMiner.target() == null && world.getBlockState(cell).isAir()) {
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

    /**
     * Pours the two diagonal cells (cells[0] then cells[3]) with the real bucket, one pour per tick: the
     * water bucket is aimed at the pit floor's top face (a support that must be visible and inside reach),
     * so vanilla's bucket rules run and the bucket becomes empty by itself. The other two cells (cells[1]/[2])
     * are each adjacent to 2 source blocks and automatically become sources after SETTLE. A pour refused for
     * reach or view walks to a standable cell beside the target and retries a bounded number of times.
     */
    private void place(AIPlayerEntity bot) {
        BlockPos cell = cells.get(placedPours == 0 ? 0 : 3);
        ActionResult pour = BucketAction.placeWater(bot, cell.below(), Direction.UP);
        if (pour.isSuccess()) {
            placedPours++;
            placeAttempts = 0;
            placeRepositions = 0;
            if (placedPours >= 2) {
                phase = Phase.SETTLE;
            }
            return;
        }
        String reason = pour.reason() == null ? "" : pour.reason();
        boolean positional = reason.equals("water_placement_not_visible")
                || reason.equals("water_support_out_of_reach")
                || reason.equals("water_support_face_not_visible");
        if (!positional) {
            fail("place_water_failed:" + reason);
            return;
        }
        note = "repositioning:" + reason;
        if (!bot.getActionPack().isPathExecutorIdle()) {
            return; // still walking to the last chosen stand cell: not an attempt (the walk has its own bounds)
        }
        // Only real attempts count: a new walk to a stand cell, or a refusal while standing still with nowhere
        // better to go. (Counting every tick of a walk let a long approach exhaust the budget mid-route.)
        BlockPos stand = adjacentStand(bot, cell);
        if (stand != null && !bot.blockPosition().equals(stand)) {
            if (placeRepositions >= MAX_REPOSITIONS) {
                fail("place_water_failed:" + reason);
                return;
            }
            if (bot.getActionPack().startPathTo(stand).isFailed()) {
                // No walk started (no route right now): that is not a reposition. It backs off like a refusal
                // while standing still, so a route that fails every tick cannot burn the walk budget in six ticks.
                if (++placeAttempts > PLACE_RETRIES) {
                    fail("place_water_failed:" + reason);
                }
                return;
            }
            placeRepositions++;
        } else if (++placeAttempts > PLACE_RETRIES) {
            fail("place_water_failed:" + reason);
        }
    }

    private void settle() {
        settle++;
        if (settle >= SETTLE_TICKS) {
            phase = Phase.DONE;
        }
    }

    private static BlockPos adjacentStand(AIPlayerEntity bot, BlockPos target) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = target.relative(d).above();
            if (Standability.isStandable(bot.level(), candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
