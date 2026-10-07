package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * What a searching bot remembers: from where it has looked and how far it saw, which way it walks, which heading
 * was refused. Pure geometry, so the behaviour of a whole exploration episode can be replayed here.
 */
class ExplorationMemoryTest {
    private static final int[][] COMPASS = {
            {1, 0}, {1, 1}, {0, 1}, {-1, 1},
            {-1, 0}, {-1, -1}, {0, -1}, {1, -1}
    };
    private static final int HOP = 14;
    private static final int RADIUS = 16;
    private static final int EAST = 0;
    private static final int NORTH_EAST = 7;

    /** Nothing blocks the view in any direction: the bot sees the whole disc of its radius. */
    private static double[] open() {
        double[] sight = new double[ExplorationMemory.SECTORS];
        Arrays.fill(sight, RADIUS);
        return sight;
    }

    /** The view is blocked {@code at} blocks out in every sector within {@code halfWidthDegrees} of the heading (dx, dz). */
    private static double[] wall(double dx, double dz, double halfWidthDegrees, double at) {
        double[] sight = open();
        double heading = Math.atan2(dz, dx);
        for (int sector = 0; sector < ExplorationMemory.SECTORS; sector++) {
            double[] centre = ExplorationMemory.sectorCentre(sector);
            double apart = Math.abs(Math.toDegrees(Math.atan2(
                    Math.sin(Math.atan2(centre[1], centre[0]) - heading),
                    Math.cos(Math.atan2(centre[1], centre[0]) - heading))));
            if (apart <= halfWidthDegrees) {
                sight[sector] = at;
            }
        }
        return sight;
    }

    /** A bot walking its episode in open ground: it looks around where it stands, picks a heading, and ends the leg HOP blocks along it. */
    private static final class Walker {
        final ExplorationMemory memory = new ExplorationMemory();
        int x;
        int z;

        Walker(int x, int z) {
            this.x = x;
            this.z = z;
        }

        int hop() {
            memory.markSearched(x, z, RADIUS, open());
            int direction = memory.chooseDirection(COMPASS, x, z, HOP, RADIUS);
            memory.noteHeading(direction);
            double length = Math.hypot(COMPASS[direction][0], COMPASS[direction][1]);
            x += (int) Math.round(COMPASS[direction][0] / length * HOP);
            z += (int) Math.round(COMPASS[direction][1] / length * HOP);
            return direction;
        }
    }

    @Test
    void withNothingSearchedYetEveryDirectionIsEqualAndTheFirstHeadingIsEast() {
        ExplorationMemory memory = new ExplorationMemory();
        memory.markSearched(0, 0, RADIUS, open());

        assertEquals(EAST, memory.chooseDirection(COMPASS, 0, 0, HOP, RADIUS));
    }

    @Test
    void aBotThatSearchedEastwardKeepsWalkingEastRatherThanTurningBack() {
        Walker walker = new Walker(0, 0);
        for (int hop = 0; hop < 6; hop++) {
            assertEquals(EAST, walker.hop(), "hop " + hop + " must continue into ground not searched yet");
        }
        assertEquals(6 * HOP, walker.x);
        assertEquals(0, walker.z);
    }

    @Test
    void sixteenHopsCarryTheBotOutwardInsteadOfCirclingWithinAFewBlocks() {
        // The compass rotation this replaces turned 45 degrees every hop: eight hops closed a loop of
        // radius twelve around the start, and a real session re-covered the same cells for half a minute.
        Walker walker = new Walker(0, 0);
        int farthest = 0;
        for (int hop = 0; hop < 16; hop++) {
            walker.hop();
            farthest = Math.max(farthest, (int) Math.hypot(walker.x, walker.z));
        }

        assertTrue(farthest >= 16 * HOP * 3 / 4, "sixteen hops reached only " + farthest + " blocks");
    }

    @Test
    void everyHopEndsWhereMostOfTheBotsViewIsStillUnsearched() {
        Walker walker = new Walker(0, 0);
        for (int hop = 0; hop < 16; hop++) {
            walker.hop();
            int unsearched = walker.memory.unsearchedProbes(walker.x, walker.z, RADIUS);
            assertTrue(unsearched >= 10, "hop " + hop + " ended where only " + unsearched
                    + " of the 25 probe points of its view are unsearched");
        }
    }

    @Test
    void aRefusedHeadingIsNotOfferedAgainFromTheSameStance() {
        Walker walker = new Walker(0, 0);
        walker.hop(); // east
        walker.memory.markSearched(walker.x, walker.z, RADIUS, open());
        int best = walker.memory.chooseDirection(COMPASS, walker.x, walker.z, HOP, RADIUS);
        assertEquals(EAST, best, "fixture: east is still the best heading from here");

        walker.memory.noteRefused(walker.x, walker.z, best);
        int next = walker.memory.chooseDirection(COMPASS, walker.x, walker.z, HOP, RADIUS);

        assertNotEquals(EAST, next, "the fence turned east down at this very stance");
        assertTrue(COMPASS[next][0] >= 0, "turning aside must not mean turning back: " + next);
    }

    @Test
    void aBotBlockedAtTheEdgeOfAnAreaTurnsIntoUnsearchedGroundNotBackOverItsOwnTrack() {
        Walker walker = new Walker(0, 0);
        for (int hop = 0; hop < 4; hop++) {
            walker.hop();
        }
        walker.memory.markSearched(walker.x, walker.z, RADIUS, open());
        // east is walled off from here on, and so is the diagonal to the north
        walker.memory.noteRefused(walker.x, walker.z, EAST);
        walker.memory.noteRefused(walker.x, walker.z, NORTH_EAST);

        int next = walker.memory.chooseDirection(COMPASS, walker.x, walker.z, HOP, RADIUS);

        assertTrue(COMPASS[next][0] >= 0, "the bot turned back along the track it searched: " + next);
        assertTrue(next != EAST && next != NORTH_EAST, "a refused heading was offered again");
    }

    @Test
    void refusalsAreForgottenOnceTheBotHasMoved() {
        ExplorationMemory memory = new ExplorationMemory();
        memory.markSearched(0, 0, RADIUS, open());
        memory.noteRefused(0, 0, EAST);

        assertNotEquals(EAST, memory.chooseDirection(COMPASS, 0, 0, HOP, RADIUS));
        assertEquals(EAST, memory.chooseDirection(COMPASS, 1, 0, HOP, RADIUS),
                "from another cell the fence may admit east");
    }

    @Test
    void whenEveryHeadingWasRefusedHereTheBotTriesAgainRatherThanHavingNoChoice() {
        ExplorationMemory memory = new ExplorationMemory();
        memory.markSearched(0, 0, RADIUS, open());
        for (int direction = 0; direction < COMPASS.length; direction++) {
            memory.noteRefused(0, 0, direction);
        }

        int chosen = memory.chooseDirection(COMPASS, 0, 0, HOP, RADIUS);

        assertTrue(chosen >= 0 && chosen < COMPASS.length);
    }

    @Test
    void searchingTheSameSpotAgainAddsNothingToTheMemory() {
        ExplorationMemory memory = new ExplorationMemory();
        memory.markSearched(10, 10, RADIUS, open());
        int once = memory.unsearchedProbes(10, 30, RADIUS);
        for (int repeat = 0; repeat < 1000; repeat++) {
            memory.markSearched(8 + repeat % 3, 8 + repeat % 2, RADIUS, wall(1, 0, 90, 2)); // the same 4x4 columns
        }

        assertEquals(once, memory.unsearchedProbes(10, 30, RADIUS));
    }

    @Test
    void aRefusedHopTowardARememberedResourceIsNotAskedForAgainFromTheSameStance() {
        ExplorationMemory memory = new ExplorationMemory();
        assertFalse(memory.isGuidedRefused(10, 20, 60, 20), "nothing was refused yet");

        memory.noteGuidedRefused(10, 20, 60, 20);

        assertTrue(memory.isGuidedRefused(10, 20, 60, 20), "the same hop from the same stance is not asked for again");
        assertFalse(memory.isGuidedRefused(11, 20, 60, 20), "from another stance it is a new question");
        assertFalse(memory.isGuidedRefused(10, 20, 61, 20), "another remembered resource is another question");
    }

    @Test
    void aLookAroundIsOnlyWantedFromAColumnTheBotHasNotSearched() {
        ExplorationMemory memory = new ExplorationMemory();
        assertTrue(memory.wantsLookAround(10, 10));

        memory.markSearched(10, 10, RADIUS, open());

        assertFalse(memory.wantsLookAround(8, 8), "the same 4x4 columns");
        assertFalse(memory.wantsLookAround(11, 11));
        assertTrue(memory.wantsLookAround(12, 10), "the next columns over");
        assertTrue(memory.wantsLookAround(10, 12));
    }

    @Test
    void theHorizonIsDividedIntoSectorsThatRoundTrip() {
        for (int sector = 0; sector < ExplorationMemory.SECTORS; sector++) {
            double[] centre = ExplorationMemory.sectorCentre(sector);
            assertEquals(sector, ExplorationMemory.sector(centre[0], centre[1]), "sector " + sector);
            assertEquals(1.0D, Math.hypot(centre[0], centre[1]), 1.0E-9D);
        }
        assertEquals(ExplorationMemory.SECTORS - 1, ExplorationMemory.sector(-1.0D, 0.0D), "negative X, azimuth pi");
        assertEquals(ExplorationMemory.SECTORS / 2, ExplorationMemory.sector(1.0D, 0.0D), "positive X");
    }

    @Test
    void oneSightDistancePerSectorIsRequired() {
        assertThrows(IllegalArgumentException.class,
                () -> new ExplorationMemory().markSearched(0, 0, RADIUS, new double[3]));
    }

    @Test
    void groundBehindAWallTheBotCouldNotSeeIsNotCreditedAsSearched() {
        // Both bots stood at the origin and looked around; one of them had a wall six blocks to its east.
        ExplorationMemory unobstructed = new ExplorationMemory();
        unobstructed.markSearched(0, 0, RADIUS, open());
        ExplorationMemory walled = new ExplorationMemory();
        walled.markSearched(0, 0, RADIUS, wall(1, 0, 45, 6));

        // Looking from a stance east of the origin, the disc beyond the wall: with open ground the bot
        // saw most of it, with the wall it saw none of it, so it was never searched ...
        int unobstructedEast = unobstructed.unsearchedProbes(HOP, 0, RADIUS);
        int walledEast = walled.unsearchedProbes(HOP, 0, RADIUS);
        // ... and is not sought either: nothing says the way past the wall is open.
        assertTrue(walledEast < unobstructedEast,
                "the ground behind the wall was never seen, so the wall must not make it look attractive: "
                        + walledEast + " against " + unobstructedEast);
        // What the wall did not hide is untouched.
        assertEquals(unobstructed.unsearchedProbes(-HOP, 0, RADIUS), walled.unsearchedProbes(-HOP, 0, RADIUS));
    }

    @Test
    void theGroundInFrontOfTheWallWasSeenAndIsSearched() {
        ExplorationMemory memory = new ExplorationMemory();
        memory.markSearched(0, 0, RADIUS, wall(1, 0, 45, 6));

        assertEquals(0, memory.unsearchedProbes(3, 0, 1), "everything within three blocks, in front of the wall, was seen");
        assertEquals(25, new ExplorationMemory().unsearchedProbes(3, 0, 1), "control: a bot that has looked at nothing has searched nothing");
    }

    @Test
    void aBotInAWalledCourseWalksOutAlongItsOpenEndNotTowardTheWallsBesideIt() {
        // The first test course of the real game: a long, narrow place, closed at the near end and on both sides.
        // From the near end the only open way is along the course, and ground behind the walls must not pull the bot
        // toward them.
        ExplorationMemory memory = new ExplorationMemory();
        double[] sight = wall(-1, 0, 60, 4);
        double[] north = wall(0, -1, 60, 8);
        double[] south = wall(0, 1, 60, 8);
        for (int sector = 0; sector < sight.length; sector++) {
            sight[sector] = Math.min(sight[sector], Math.min(north[sector], south[sector]));
        }
        memory.markSearched(0, 0, RADIUS, sight);

        assertEquals(EAST, memory.chooseDirection(COMPASS, 0, 0, HOP, RADIUS),
                "the open end of the course is the one way that leads anywhere");
    }

    @Test
    void aBotThatSawNothingAtAllHasNoGroundWorthSeekingButStillChoosesAHeading() {
        ExplorationMemory memory = new ExplorationMemory();
        memory.markSearched(0, 0, RADIUS, new double[ExplorationMemory.SECTORS]);

        int chosen = memory.chooseDirection(COMPASS, 0, 0, HOP, RADIUS);

        assertEquals(EAST, chosen, "with nothing to prefer it keeps the heading it had");
    }

    @Test
    void aWallToTheNorthNeverPullsTheBotNorth() {
        ExplorationMemory memory = new ExplorationMemory();
        memory.markSearched(0, 0, RADIUS, wall(0, -1, 60, 6));

        for (int heading : new int[] {EAST, 2, 4, 6}) {
            memory.noteHeading(heading);
            int direction = memory.chooseDirection(COMPASS, 0, 0, HOP, RADIUS);
            assertTrue(COMPASS[direction][1] >= 0,
                    "walking " + heading + " the bot turned toward the wall on its north side: " + direction);
        }
    }
}
