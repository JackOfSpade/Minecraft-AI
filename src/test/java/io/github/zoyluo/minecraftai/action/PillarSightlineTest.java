package io.github.zoyluo.minecraftai.action;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** The pillar head must still see its target: a cell the bot has seen shut on every line rules the column out. */
class PillarSightlineTest {
    private static final BlockPos TARGET = new BlockPos(4, 8, 0);
    /** A pillar head beside the target, level with it: its eye sits at about the target's height. */
    private static final BlockPos GOAL = new BlockPos(2, 7, 0);
    private static final Vec3 EYE = new Vec3(2.5D, 7.0D + 1.62D, 0.5D);

    @Test
    void nothingSeenToBeShutLeavesTheTargetInSight() {
        assertFalse(PillarSightline.isBlocked(EYE, TARGET, GOAL, cell -> false));
    }

    @Test
    void aSeenCellBetweenTheHeadAndTheTargetShutsEveryLine() {
        BlockPos between = new BlockPos(3, 8, 0);

        assertTrue(PillarSightline.isBlocked(EYE, TARGET, GOAL, between::equals),
                "the one cell in front of the only face turned to the eye hides the whole block");
    }

    @Test
    void aCellBesideTheLineLeavesTheTargetInSight() {
        assertFalse(PillarSightline.isBlocked(EYE, TARGET, GOAL, new BlockPos(3, 10, 0)::equals));
        assertFalse(PillarSightline.isBlocked(EYE, TARGET, GOAL, new BlockPos(3, 8, 3)::equals));
    }

    @Test
    void aHeadHigherUpLooksAlongOtherLinesAndSeesPastTheSameCell() {
        BlockPos ledge = new BlockPos(3, 8, 0);
        BlockPos higher = new BlockPos(2, 11, 0);

        assertTrue(PillarSightline.isBlocked(EYE, TARGET, GOAL, ledge::equals));
        assertFalse(PillarSightline.isBlocked(new Vec3(2.5D, 11.0D + 1.62D, 0.5D), TARGET, higher, ledge::equals),
                "from three blocks up the top of the target is in plain view over that cell");
    }

    @Test
    void aFaceTurnedAwayFromTheEyeIsNeverAimedAtAndTheTargetAndTheColumnAreNeverAskedAbout() {
        Set<BlockPos> asked = new HashSet<>();
        PillarSightline.isBlocked(EYE, TARGET, GOAL, cell -> {
            asked.add(cell);
            return false;
        });

        assertFalse(asked.isEmpty());
        for (BlockPos cell : asked) {
            assertTrue(cell.getX() < TARGET.getX(),
                    "a line to the far side of the block would run through it: " + cell);
            assertFalse(cell.getX() == GOAL.getX() && cell.getZ() == GOAL.getZ(),
                    "the pillar's own column is proven air elsewhere: " + cell);
        }
    }
}
