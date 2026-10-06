package io.github.zoyluo.minecraftai.task;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Geometry of the staircase OreDig digs up to an observed ore that hangs above the reach of any
 * ordinary work pose, and the one thing it needs from the world: which cells a step must open.
 *
 * <p>A human who sees coal six blocks over their head in solid rock digs a stair. A straight stair
 * only works when the ore happens to sit exactly as far along the stair as it is high, so this one
 * circles the ore instead. The bot first digs a level tunnel toward the ore's column until it
 * stands next to it (the ring of eight cells around that column), then climbs the ring one block
 * per step. Each step lands on the solid cell beside it, never on a cell dug earlier, so every
 * tread has a floor and the whole stair stays walkable in both directions; a lap is eight steps,
 * far more than the three blocks a step opens, so successive laps never meet. The cell under the
 * ore is never opened: it stays solid and holds the ore's drop in the ore's own cell, beside the
 * pose the climb ends in.</p>
 *
 * <p>Everything here is arithmetic on the bot's offset from the ore column; no world is read.</p>
 */
final class OreClimb {
    /** One step: the horizontal direction, and whether it also gains a block of height. */
    record Move(Direction direction, boolean rise) {
    }

    /**
     * The ways out of the ore's column, in the order they are tried when the bot stands directly
     * beneath the ore. Rising needs the cell above the bot's head, which is the cell right under
     * the ore when the ore is three blocks up: that one holds the drop, so such a bot walks out
     * level and climbs from the ring.
     */
    private static final List<Move> COLUMN_EXITS_RISING = exits(true);
    private static final List<Move> COLUMN_EXITS_LEVEL = exits(false);

    private OreClimb() {
    }

    /**
     * The step(s) that bring a bot standing {@code (rx, rz)} blocks from the ore's column and
     * {@code v} blocks below the ore closer to a work pose, best first. The caller has already
     * established that the bot is not at a work pose. Several alternatives exist only directly
     * beneath the ore (any of the four sides starts the ring equally well); the caller takes the
     * first whose cells are safe to open. Empty when no step helps.
     */
    static List<Move> moves(int rx, int rz, int v) {
        int ring = Math.max(Math.abs(rx), Math.abs(rz));
        if (ring >= 2) {
            // A level tunnel toward the column along the longer axis, as the ordinary approach does.
            return List.of(new Move(Math.abs(rx) >= Math.abs(rz)
                    ? (rx > 0 ? Direction.WEST : Direction.EAST)
                    : (rz > 0 ? Direction.NORTH : Direction.SOUTH), false));
        }
        if (ring == 1) {
            return List.of(new Move(clockwise(rx, rz), v >= 2));
        }
        return v > 3 ? COLUMN_EXITS_RISING : v == 3 ? COLUMN_EXITS_LEVEL : List.of();
    }

    private static List<Move> exits(boolean rise) {
        return List.of(new Move(Direction.NORTH, rise), new Move(Direction.EAST, rise),
                new Move(Direction.SOUTH, rise), new Move(Direction.WEST, rise));
    }

    /**
     * The next ring cell clockwise (seen from above, north up) from the ring cell
     * {@code (rx, rz)}: north to north-east to east and so on around.
     */
    private static Direction clockwise(int rx, int rz) {
        if (rz == -1 && rx < 1) {
            return Direction.EAST;
        }
        if (rx == 1 && rz < 1) {
            return Direction.SOUTH;
        }
        if (rz == 1 && rx > -1) {
            return Direction.WEST;
        }
        return Direction.NORTH;
    }

    /** The cell the bot stands in after {@code move}. */
    static BlockPos landing(BlockPos feet, Move move) {
        BlockPos front = feet.relative(move.direction());
        return move.rise() ? front.above() : front;
    }

    /**
     * The cells {@code move} must open, in the order they come into view: for a rise the cell above
     * the bot's head (which a hop up needs free), then the landing and its head cell; for a level
     * step the landing's head cell and the landing.
     */
    static List<BlockPos> bodyCells(BlockPos feet, Move move) {
        BlockPos landing = landing(feet, move);
        return move.rise()
                ? List.of(feet.above(2), landing, landing.above())
                : List.of(landing.above(), landing);
    }
}
