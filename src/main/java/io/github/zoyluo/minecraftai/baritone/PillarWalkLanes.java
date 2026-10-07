package io.github.zoyluo.minecraftai.baritone;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;

/**
 * The cells a bot has to be shown walkable to start a pillar from where it stands: the two cardinal lanes along the sides of
 * the rectangle between it and the pillar's foot (along x and then along z, and along z and then along x).
 *
 * <p>The proof that the foot can be walked to is a search over cardinal steps ({@code ObservedGraphSearch}). The strips that an
 * ordinary route captures toward its stance are parallel lines of the straight line to it, and for a foot exactly diagonal from
 * the bot every cell of every strip has the same parity of {@code x + z}: no two of them are neighbours, so the strips prove no
 * walk at all and the pillar was refused unless some earlier sweep had happened to cross the missing cells. A cardinal lane is
 * connected by construction. Free of Minecraft's registries so that its geometry can be tested without a server.</p>
 */
final class PillarWalkLanes {
    private PillarWalkLanes() {
    }

    /**
     * The stances of both lanes from {@code from} (excluded) to {@code base} (included), at the level of {@code base}, each once,
     * in the order they are walked.
     */
    static List<BlockPos> stances(BlockPos from, BlockPos base) {
        Set<BlockPos> stances = new LinkedHashSet<>();
        for (boolean alongXFirst : new boolean[] {true, false}) {
            int x = from.getX();
            int z = from.getZ();
            while (x != base.getX() || z != base.getZ()) {
                if (x != base.getX() && (alongXFirst || z == base.getZ())) {
                    x += Integer.signum(base.getX() - x);
                } else {
                    z += Integer.signum(base.getZ() - z);
                }
                stances.add(new BlockPos(x, base.getY(), z));
            }
        }
        return List.copyOf(stances);
    }
}
