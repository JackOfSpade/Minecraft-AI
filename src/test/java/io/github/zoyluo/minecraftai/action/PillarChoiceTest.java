package io.github.zoyluo.minecraftai.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** Whether to pillar where the bot stands or walk onto the ground above or below first. */
class PillarChoiceTest {
    private static final BlockPos TARGET = new BlockPos(0, 20, 8);

    private static HarvestCore.PillarApproach pillar(int floorY, int supports) {
        return new HarvestCore.PillarApproach(TARGET, new BlockPos(0, floorY + supports, 6), supports);
    }

    @Test
    void groundThatNeedsFewerSupportsIsWorthTheWalk() {
        assertTrue(HarvestCore.otherFloorIsCheaper(pillar(10, 6), List.of(pillar(14, 2))),
                "two supports from a hill beat a six-block tower on the low ground");
    }

    @Test
    void anEqualPillarStaysWhereTheBotStandsWhichNeedsNoWalk() {
        assertFalse(HarvestCore.otherFloorIsCheaper(pillar(10, 3), List.of(pillar(13, 3))));
        assertFalse(HarvestCore.otherFloorIsCheaper(pillar(10, 3), List.of(pillar(8, 5))));
    }

    @Test
    void whenNothingIsClimbableAtTheBotsLevelAnyOtherGroundIs() {
        assertTrue(HarvestCore.otherFloorIsCheaper(null, List.of(pillar(14, 9))));
    }

    @Test
    void withNoOtherGroundThereIsNothingToWalkTo() {
        assertFalse(HarvestCore.otherFloorIsCheaper(pillar(10, 6), List.of()));
        assertFalse(HarvestCore.otherFloorIsCheaper(null, List.of()));
    }

    @Test
    void theFloorOfAPillarIsItsGoalLessItsSupports() {
        assertEquals(new BlockPos(0, 14, 6), HarvestCore.pillarFloor(pillar(14, 2)));
    }

    @Test
    void theCheaperPillarWinsHoweverFarItsLogIs() {
        BlockPos origin = BlockPos.ZERO;
        HarvestCore.PillarApproach near = new HarvestCore.PillarApproach(new BlockPos(2, 20, 2), new BlockPos(2, 14, 2), 8);
        HarvestCore.PillarApproach far = new HarvestCore.PillarApproach(new BlockPos(12, 20, 9), new BlockPos(12, 15, 9), 2);

        assertTrue(HarvestCore.isBetterPillar(far, near, origin), "two supports beat eight, even for the log farther off");
        assertFalse(HarvestCore.isBetterPillar(near, far, origin));
    }

    @Test
    void onEqualSupportsTheNearerLogWinsAndAnyPillarBeatsNone() {
        BlockPos origin = BlockPos.ZERO;
        HarvestCore.PillarApproach near = new HarvestCore.PillarApproach(new BlockPos(2, 20, 2), new BlockPos(2, 17, 2), 3);
        HarvestCore.PillarApproach far = new HarvestCore.PillarApproach(new BlockPos(12, 20, 9), new BlockPos(12, 17, 9), 3);

        assertTrue(HarvestCore.isBetterPillar(near, far, origin));
        assertFalse(HarvestCore.isBetterPillar(far, near, origin));
        assertTrue(HarvestCore.isBetterPillar(far, null, origin));
    }
}
