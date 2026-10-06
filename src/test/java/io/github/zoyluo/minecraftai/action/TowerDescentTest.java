package io.github.zoyluo.minecraftai.action;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** Where a descent ends: on the floor the pillar was built from. */
class TowerDescentTest {
    @Test
    void theFloorIsTheHeadOfThePillarLessItsSupports() {
        TowerDescent descent = TowerDescent.over(new BlockPos(5, 71, 9), 7);

        assertEquals(new BlockPos(5, 64, 9), descent.base(),
                "seven supports under feet at 71 stand on a floor whose standing cell is 64");
    }

    @Test
    void aPillarHasAtLeastOneSupport() {
        assertEquals(new BlockPos(5, 70, 9), TowerDescent.over(new BlockPos(5, 71, 9), 0).base());
    }

    @Test
    void aNewDescentHasBrokenNothingAndFailedNowhere() {
        TowerDescent descent = TowerDescent.over(new BlockPos(0, 10, 0), 3);

        assertEquals(0, descent.broken());
        assertEquals("", descent.failureReason());
    }
}
