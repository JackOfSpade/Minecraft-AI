package dev.spawnbotswrapper.inhabitants.spawn;

import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.spawnbotswrapper.inhabitants.spawn.FakeProbe.FEET;
import static dev.spawnbotswrapper.inhabitants.spawn.FakeProbe.FLOOR;
import static dev.spawnbotswrapper.inhabitants.spawn.StraightWalk.NOT_WALKABLE;
import static org.junit.jupiter.api.Assertions.*;

class StraightWalkTest {

    private static FakeProbe flat() {
        return new FakeProbe().floor(0, 0, 40, 40);
    }

    private static int walk(FakeProbe probe, double x0, double z0, int y0, double x1, double z1) {
        return new StraightWalk(new ProbeView(probe)).walk(x0, z0, y0, x1, z1);
    }

    /** Along z=5.5 from x=5.5 to x=15.5, starting at ground level. */
    private static int alongTheRow(FakeProbe probe) {
        return walk(probe, 5.5, 5.5, FEET, 15.5, 5.5);
    }

    @Test
    void openFlatGroundIsWalkableInEveryDirection() {
        FakeProbe p = flat();
        assertEquals(FEET, walk(p, 5.5, 5.5, FEET, 25.5, 5.5));
        assertEquals(FEET, walk(p, 25.5, 5.5, FEET, 5.5, 5.5));
        assertEquals(FEET, walk(p, 5.5, 5.5, FEET, 5.5, 25.5));
        assertEquals(FEET, walk(p, 10.5, 10.5, FEET, 20.5, 20.5), "diagonal");
        assertEquals(FEET, walk(p, 10.5, 10.5, FEET, 17.5, 4.5), "arbitrary angle");
    }

    @Test
    void zeroLengthAndSameColumnKeepTheStartLevel() {
        FakeProbe p = flat();
        assertEquals(FEET, walk(p, 5.5, 5.5, FEET, 5.5, 5.5));
        assertEquals(FEET, walk(p, 5.1, 5.1, FEET, 5.9, 5.9));
    }

    // ---------------------------------------------------------------- vertical rules

    @Test
    void aOneBlockStepUpIsAHopAndTheWayBackIsADrop() {
        FakeProbe p = flat().floor(10, 0, 40, 40, FLOOR + 1);
        assertEquals(FEET + 1, alongTheRow(p));
        assertEquals(FEET, walk(p, 15.5, 5.5, FEET + 1, 5.5, 5.5));
    }

    @Test
    void aTwoBlockStepUpIsRejectedButTheSameLedgeCanBeDroppedFrom() {
        FakeProbe p = flat().floor(10, 0, 40, 40, FLOOR + 1).floor(10, 0, 40, 40, FLOOR + 2);
        assertEquals(NOT_WALKABLE, alongTheRow(p), "PvP BOT can only hop one block");
        assertEquals(FEET, walk(p, 15.5, 5.5, FEET + 2, 5.5, 5.5), "a two block drop is fine");
    }

    @Test
    void dropsOfTwoAreWalkableButThreeAreNot() {
        FakeProbe two = new FakeProbe().floor(0, 0, 9, 40, FLOOR).floor(10, 0, 40, 40, FLOOR - 2);
        assertEquals(FEET - 2, alongTheRow(two));
        assertEquals(NOT_WALKABLE, walk(two, 15.5, 5.5, FEET - 2, 5.5, 5.5), "and there is no climbing back");
        FakeProbe three = new FakeProbe().floor(0, 0, 9, 40, FLOOR).floor(10, 0, 40, 40, FLOOR - 3);
        assertEquals(NOT_WALKABLE, alongTheRow(three));
    }

    @Test
    void aFallThroughOccupiedBlocksIsNotADrop() {
        FakeProbe p = new FakeProbe().floor(0, 0, 9, 40, FLOOR).floor(10, 0, 40, 40, FLOOR - 2);
        p.set(10, FLOOR - 1, 5, Cell.WATER);
        assertEquals(NOT_WALKABLE, alongTheRow(p), "water half way down the drop");
        p.set(10, FLOOR - 1, 5, Cell.HAZARD);
        assertEquals(NOT_WALKABLE, alongTheRow(p), "lava half way down the drop");
    }

    @Test
    void aHopNeedsThreeBlocksOfClearanceWhereItStartsAndTwoWhereItLands() {
        FakeProbe raised = flat().floor(10, 0, 40, 40, FLOOR + 1);
        assertEquals(FEET + 1, alongTheRow(raised));

        FakeProbe lowCeilingBeforeStep = flat().floor(10, 0, 40, 40, FLOOR + 1);
        lowCeilingBeforeStep.set(9, FEET + 2, 5, Cell.SOLID_OTHER);
        assertEquals(NOT_WALKABLE, alongTheRow(lowCeilingBeforeStep), "no room to jump");

        FakeProbe lowCeilingAfterStep = flat().floor(10, 0, 40, 40, FLOOR + 1);
        lowCeilingAfterStep.set(10, FEET + 2, 5, Cell.SOLID_OTHER);
        assertEquals(NOT_WALKABLE, alongTheRow(lowCeilingAfterStep), "one block of headroom on the landing");
    }

    @Test
    void theRaisedFloorMustItselfBeStandable() {
        FakeProbe p = flat();
        p.set(10, FEET, 5, Cell.SOLID_OTHER);
        assertEquals(NOT_WALKABLE, alongTheRow(p), "a fence-like block one high is a wall, not a step");
        p.set(10, FEET, 5, Cell.SOLID_HAZARD);
        assertEquals(NOT_WALKABLE, alongTheRow(p), "cactus is not a step");
    }

    @Test
    void levelsAtTheTopOfTheWorldAreRejectedEvenWhenTheBlocksExist() {
        FakeProbe p = new FakeProbe(0, 20).floor(0, 0, 40, 40, 17).floor(3, 0, 40, 40, 18);
        assertEquals(NOT_WALKABLE, walk(p, 1.5, 5.5, 18, 5.5, 5.5), "the hop would land at 19; the highest legal feet level is 18");
    }

    // ---------------------------------------------------------------- what is on the line

    @Test
    void anyBadBlockAtFeetOrHeadStopsTheSegment() {
        for (Cell bad : List.of(Cell.WATER, Cell.HAZARD, Cell.SOLID_OTHER, Cell.SOLID_HAZARD)) {
            for (int level : new int[]{FEET, FEET + 1}) {
                FakeProbe p = flat();
                p.set(10, level, 5, bad);
                assertEquals(NOT_WALKABLE, alongTheRow(p), bad + " at level " + level);
            }
        }
        FakeProbe overhang = flat();
        overhang.set(10, FEET + 1, 5, Cell.SOLID_STANDABLE);
        assertEquals(NOT_WALKABLE, alongTheRow(overhang), "a solid block at head height");
    }

    @Test
    void anyBadFloorStopsTheSegment() {
        for (Cell bad : List.of(Cell.EMPTY, Cell.WATER, Cell.HAZARD, Cell.SOLID_OTHER, Cell.SOLID_HAZARD)) {
            FakeProbe p = flat();
            p.set(10, FLOOR, 5, bad);
            assertEquals(NOT_WALKABLE, alongTheRow(p), bad + " as the floor");
        }
    }

    @Test
    void aBlockThePlannerCannotReadStopsTheSegment() {
        FakeProbe p = flat().unloaded(10, 5, 10, 5);
        assertEquals(NOT_WALKABLE, alongTheRow(p));
        FakeProbe ceiling = flat();
        ceiling.set(10, FEET + 2, 5, Cell.SOLID_OTHER);
        assertEquals(FEET, alongTheRow(ceiling), "a ceiling two blocks up is not in the way");
    }

    @Test
    void aOneBlockWideGapWithNothingBelowIsNotCrossable() {
        FakeProbe p = flat().fill(10, FLOOR, 0, 10, FLOOR, 40, Cell.EMPTY);
        assertEquals(NOT_WALKABLE, alongTheRow(p));
    }

    @Test
    void hazardsBesideAStraightLineDoNotMatterBecauseTheBodyStaysInItsColumn() {
        FakeProbe p = flat();
        p.set(10, FLOOR, 6, Cell.HAZARD);
        p.set(10, FLOOR, 4, Cell.HAZARD);
        assertEquals(FEET, alongTheRow(p));
    }

    @Test
    void leavingTheWorldBorderStopsTheSegment() {
        FakeProbe p = flat().border(0, 0, 12, 40);
        assertEquals(NOT_WALKABLE, alongTheRow(p));
        assertEquals(FEET, walk(p, 5.5, 5.5, FEET, 11.5, 5.5));
    }

    // ---------------------------------------------------------------- body width

    @Test
    void aDiagonalCannotSqueezeBetweenTwoSolidCorners() {
        FakeProbe open = flat();
        assertEquals(FEET, walk(open, 10.5, 10.5, FEET, 11.5, 11.5));

        FakeProbe squeezed = flat();
        squeezed.fill(11, FEET, 10, 11, FEET + 1, 10, Cell.SOLID_OTHER);
        squeezed.fill(10, FEET, 11, 10, FEET + 1, 11, Cell.SOLID_OTHER);
        assertEquals(NOT_WALKABLE, walk(squeezed, 10.5, 10.5, FEET, 11.5, 11.5), "both corners solid");
        assertTrue(WalkRules.walkable(squeezed, 10.5, 10.5, FEET, 11.5, 11.5, FEET),
                "the plain centre-line rule would have let it through; the planner is deliberately stricter");

        FakeProbe oneCorner = flat();
        oneCorner.fill(11, FEET, 10, 11, FEET + 1, 10, Cell.SOLID_OTHER);
        assertEquals(NOT_WALKABLE, walk(oneCorner, 10.5, 10.5, FEET, 11.5, 11.5), "a 0.6 wide body clips one corner");
    }

    @Test
    void aOneWideStraightCorridorIsWalkableAlongItsAxis() {
        FakeProbe p = new FakeProbe().floor(0, 5, 30, 5);
        p.fill(0, FEET, 4, 30, FEET + 2, 4, Cell.SOLID_OTHER);
        p.fill(0, FEET, 6, 30, FEET + 2, 6, Cell.SOLID_OTHER);
        assertEquals(FEET, walk(p, 2.5, 5.5, FEET, 28.5, 5.5));
        assertEquals(FEET, walk(p, 28.5, 5.5, FEET, 2.5, 5.5));
    }

    // ---------------------------------------------------------------- sampling

    @Test
    void samplesAreAnEvenMultipleOfTheHalfBlockSpacingAndNeverCoarserThanAQuarterBlock() {
        for (double dx = -30; dx <= 30; dx += 1.7) {
            for (double dz = -30; dz <= 30; dz += 2.3) {
                int n = StraightWalk.sampleCount(dx, dz);
                double length = Math.sqrt(dx * dx + dz * dz);
                if (length == 0) {
                    assertEquals(0, n);
                    continue;
                }
                assertEquals(0, n % 2, "even, so every plain 0.5-block sample is included");
                assertTrue(length / n <= StraightWalk.SAMPLE_STEP + 1e-12, "step " + length / n);
                assertEquals(2 * (int) Math.ceil(length / 0.5), n);
            }
        }
    }

    @Test
    void absurdOrNonFiniteSegmentsAreRefusedNotWavedThrough() {
        FakeProbe p = flat();
        assertEquals(NOT_WALKABLE, walk(p, 5.5, 5.5, FEET, 5000.5, 5.5));
        assertEquals(NOT_WALKABLE, walk(p, 5.5, 5.5, FEET, Double.NaN, 5.5));
        assertEquals(NOT_WALKABLE, walk(p, 5.5, 5.5, FEET, 5.5, Double.POSITIVE_INFINITY));
    }

    @Test
    void longSegmentsAreCheapBecauseCellsAreMemoised() {
        FakeProbe p = new FakeProbe().floor(0, 0, 200, 0);
        assertEquals(FEET, walk(p, 0.5, 0.5, FEET, 199.5, 0.5));
        assertTrue(p.reads() < 200 * 6, "reads " + p.reads());
    }

    @Test
    void aDropIntoAHoleAtTheBottomOfTheWorldNeverAsksAboutBlocksBelowIt() {
        FakeProbe p = new FakeProbe(0, 20).floor(0, 0, 9, 0, 0);
        p.set(5, 0, 0, Cell.EMPTY);
        assertEquals(NOT_WALKABLE, walk(p, 1.5, 0.5, 1, 8.5, 0.5), "nothing below the hole to land on");
        assertEquals(0, p.outOfRangeReads(), "the fall check must stop at the world's floor");
    }

    @Test
    void neverAsksTheProbeAboutBlocksOutsideTheWorld() {
        FakeProbe p = new FakeProbe(0, 20).floor(0, 0, 40, 40, 0);
        walk(p, 1.5, 5.5, 1, 30.5, 5.5);
        walk(p, 1.5, 5.5, 1, 30.5, 25.5);
        assertEquals(0, p.outOfRangeReads());
    }
}
