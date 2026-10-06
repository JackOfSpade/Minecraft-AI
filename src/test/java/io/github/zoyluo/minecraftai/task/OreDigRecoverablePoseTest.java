package io.github.zoyluo.minecraftai.task;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The poses OreDig mines an ore from, because a drop broken from there stays inside the bot's recovery reach. */
class OreDigRecoverablePoseTest {
    private static final BlockPos ORE = new BlockPos(5, 20, 5);

    private static boolean from(int dx, int dz, int below) {
        return OreDigTask.isRecoverableBreakPose(new BlockPos(5 + dx, 20 - below, 5 + dz), ORE);
    }

    @Test
    void anOreBesideTheBotAtOrJustOverItsHeadIsMinedFromThere() {
        for (int[] side : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            assertTrue(from(side[0], side[1], 1), "one block over the feet, beside");
            assertTrue(from(side[0], side[1], 0), "level, beside");
            assertTrue(from(side[0], side[1], -1), "one block under the feet, beside");
        }
    }

    @Test
    void twoOverTheFeetIsOnlyAPoseStraightUnderTheOre() {
        assertTrue(from(0, 0, 2), "straight under: the drop falls onto the bot");
        assertFalse(from(1, 0, 2), "beside, two up: the drop can stay on the raised ledge");
    }

    @Test
    void whatIsFurtherAwayOrHigherIsNoPose() {
        assertFalse(from(0, 0, 3), "three up");
        assertFalse(from(1, 0, 3));
        assertFalse(from(2, 0, 1), "two blocks over, not beside");
        assertFalse(from(1, 1, 1), "diagonal");
        assertFalse(from(1, 0, -2), "two down");
    }
}
