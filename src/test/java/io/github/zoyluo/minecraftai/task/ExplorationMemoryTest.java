package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * What a searching bot remembers: from where it has looked, which way it walks, which heading was
 * refused. Pure geometry, so the behaviour of a whole exploration episode can be replayed here.
 */
class ExplorationMemoryTest {
    private static final int[][] COMPASS = {
            {1, 0}, {1, 1}, {0, 1}, {-1, 1},
            {-1, 0}, {-1, -1}, {0, -1}, {1, -1}
    };
    private static final int HOP = 12;
    private static final int RADIUS = 16;
    private static final int EAST = 0;
    private static final int SOUTH_EAST = 1;
    private static final int NORTH_EAST = 7;

    /** A bot walking its episode: it searches where it stands, picks a heading, and ends the leg HOP blocks along it. */
    private static final class Walker {
        final ExplorationMemory memory = new ExplorationMemory();
        int x;
        int z;

        Walker(int x, int z) {
            this.x = x;
            this.z = z;
        }

        int hop() {
            memory.markSearched(x, z);
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
        memory.markSearched(0, 0);

        assertEquals(EAST, memory.chooseDirection(COMPASS, 0, 0, HOP, RADIUS));
    }

    @Test
    void aBotThatSearchedEastwardKeepsWalkingEastRatherThanTurningBack() {
        Walker walker = new Walker(0, 0);
        for (int hop = 0; hop < 6; hop++) {
            assertEquals(EAST, walker.hop(), "hop " + hop + " must continue into ground not searched yet");
        }
        assertEquals(72, walker.x);
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

        assertTrue(farthest >= 16 * HOP * 3 / 4, "sixteen twelve-block hops reached only " + farthest + " blocks");
    }

    @Test
    void everyHopEndsWhereMostOfTheBotsViewIsStillUnsearched() {
        Walker walker = new Walker(0, 0);
        for (int hop = 0; hop < 16; hop++) {
            walker.hop();
            int unsearched = walker.memory.unsearchedProbes(walker.x, walker.z, RADIUS);
            assertTrue(unsearched >= 8, "hop " + hop + " ended where only " + unsearched
                    + " of the 19 probe points of its view are unsearched");
        }
    }

    @Test
    void aRefusedHeadingIsNotOfferedAgainFromTheSameStance() {
        Walker walker = new Walker(0, 0);
        walker.hop(); // east
        walker.memory.markSearched(walker.x, walker.z);
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
        walker.memory.markSearched(walker.x, walker.z);
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
        memory.markSearched(0, 0);
        memory.noteRefused(0, 0, EAST);

        assertNotEquals(EAST, memory.chooseDirection(COMPASS, 0, 0, HOP, RADIUS));
        assertEquals(EAST, memory.chooseDirection(COMPASS, 1, 0, HOP, RADIUS),
                "from another cell the fence may admit east");
    }

    @Test
    void whenEveryHeadingWasRefusedHereTheBotTriesAgainRatherThanHavingNoChoice() {
        ExplorationMemory memory = new ExplorationMemory();
        memory.markSearched(0, 0);
        for (int direction = 0; direction < COMPASS.length; direction++) {
            memory.noteRefused(0, 0, direction);
        }

        int chosen = memory.chooseDirection(COMPASS, 0, 0, HOP, RADIUS);

        assertTrue(chosen >= 0 && chosen < COMPASS.length);
    }

    @Test
    void searchingTheSameSpotAgainAddsNothingToTheMemory() {
        ExplorationMemory memory = new ExplorationMemory();
        memory.markSearched(10, 10);
        int once = memory.unsearchedProbes(10, 30, RADIUS);
        for (int repeat = 0; repeat < 1000; repeat++) {
            memory.markSearched(8 + repeat % 3, 8 + repeat % 2); // the same 4x4 columns
        }

        assertEquals(once, memory.unsearchedProbes(10, 30, RADIUS));
    }
}
