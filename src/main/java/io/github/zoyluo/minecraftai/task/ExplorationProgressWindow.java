package io.github.zoyluo.minecraftai.task;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;

/**
 * Physical-progress watchdog for a bounded observed exploration episode.
 *
 * <p>Baritone can remain active while repeatedly recalculating a local hop, and a sequence of
 * short hops can trace a ring through cells the bot has already visited. Neither should keep a
 * resource search alive forever. A newly reached block cell is real frontier progress; revisiting
 * a cell is not. The clock intentionally advances only while the owning task is in EXPLORE, so a
 * survey scan between two legs cannot consume the next leg's movement budget.</p>
 */
final class ExplorationProgressWindow {
    /** Four seconds of an active exploration leg without reaching new ground is a stalled route. */
    static final int WINDOW_TICKS = 80;

    private final Set<BlockPos> visitedCells = new HashSet<>();
    private BlockPos lastObservedCell;
    private int exploreTicks;
    private int lastFrontierTick;
    private boolean armed;

    /** Starts (or resumes) a leg without treating a repeated starting cell as new progress. */
    void beginLeg(BlockPos cell) {
        observe(cell);
    }

    /**
     * Records one active EXPLORE tick and reports whether the route has stopped expanding the
     * observed search frontier for too long.
     */
    boolean stalled(BlockPos cell) {
        exploreTicks++;
        observe(cell);
        return armed && exploreTicks - lastFrontierTick >= WINDOW_TICKS;
    }

    /** Number of active exploration ticks since the bot last reached a new block cell. */
    int ticksSinceFrontier() {
        return armed ? Math.max(0, exploreTicks - lastFrontierTick) : 0;
    }

    /** A gathered item starts a genuinely new exploration episode. */
    void reset() {
        visitedCells.clear();
        lastObservedCell = null;
        exploreTicks = 0;
        lastFrontierTick = 0;
        armed = false;
    }

    private void observe(BlockPos cell) {
        if (cell == null) {
            return;
        }
        BlockPos immutable = cell.immutable();
        if (!armed) {
            armed = true;
            lastFrontierTick = exploreTicks;
        }
        if (immutable.equals(lastObservedCell)) {
            return;
        }
        lastObservedCell = immutable;
        if (visitedCells.add(immutable)) {
            lastFrontierTick = exploreTicks;
        }
    }
}
