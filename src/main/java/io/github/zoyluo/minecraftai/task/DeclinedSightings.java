package io.github.zoyluo.minecraftai.task;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;

/**
 * Sightings that a gather look-around handed to its caller, who could do nothing with them from
 * where the bot stands. A first-hit sweep reaches the same block through many rays, so without this
 * memo one unusable lead (a leaf straight overhead, an excluded or unreachable log) is offered
 * again on every step and the rest of the sweep never runs.
 *
 * <p>A sighting is judged from one cell. Once the bot stands in a different cell the same block is
 * new evidence (it may now have a heading or a stance), so the memo forgets it.</p>
 */
final class DeclinedSightings {
    private final Set<BlockPos> declined = new HashSet<>();
    private BlockPos judgedFrom;

    void decline(BlockPos feet, BlockPos sighting) {
        if (!feet.equals(judgedFrom)) {
            declined.clear();
            judgedFrom = feet.immutable();
        }
        declined.add(sighting.immutable());
    }

    boolean isDeclined(BlockPos feet, BlockPos sighting) {
        if (!feet.equals(judgedFrom)) {
            declined.clear();
            judgedFrom = null;
            return false;
        }
        return declined.contains(sighting);
    }

    void clear() {
        declined.clear();
        judgedFrom = null;
    }
}
