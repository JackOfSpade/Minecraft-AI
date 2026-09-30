package dev.spawnbotswrapper.inhabitants.combat;

import dev.spawnbotswrapper.inhabitants.combat.AggroWorld.Pos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Following a planned route: which waypoint to walk toward, when it is done, when it is stuck. */
class PathFollowerTest {

    private static Pos p(double x, double z) {
        return new Pos(x, 64, z);
    }

    private static final List<Pos> ROUTE = List.of(p(1, 0), p(2, 0), p(3, 1), p(4, 2));

    @Test
    void walksTheWaypointsInOrder() {
        PathFollower f = new PathFollower(ROUTE, 0);
        assertEquals(p(1, 0), f.current(p(0, 0), 0));
        assertEquals(p(1, 0), f.current(p(0.1, 0), 1), "not there yet: 0.9 away");
        assertEquals(p(2, 0), f.current(p(0.3, 0), 2), "within 0.8 of the first: on to the second");
        assertEquals(p(3, 1), f.current(p(2.1, 0.2), 3));
        assertEquals(p(4, 2), f.current(p(3.0, 1.0), 4));
        assertFalse(f.done());
        assertNull(f.current(p(4.0, 2.0), 5), "the last waypoint is reached: done");
        assertTrue(f.done());
    }

    @Test
    void anOvershotWaypointIsSkippedNotWalkedBackTo() {
        PathFollower f = new PathFollower(ROUTE, 0);
        // the bot sailed past the first waypoint (knockback, sprint): it is nearer the second one than the first is
        // and it is within reach of the second one too: on to the third
        assertEquals(p(3, 1), f.current(p(1.7, 0.5), 0));
    }

    @Test
    void aWaypointOnAnotherFloorIsNotReachedFromBelow() {
        PathFollower f = new PathFollower(List.of(new Pos(1, 68, 0)), 0);
        assertEquals(new Pos(1, 68, 0), f.current(new Pos(1, 64, 0), 0), "4 blocks below is not there");
        assertNull(f.current(new Pos(1, 67.5, 0.2), 1));
    }

    @Test
    void stuckWhenNoProgressForTheGivenTicks() {
        PathFollower f = new PathFollower(ROUTE, 0);
        Pos stuck = p(0, 0);
        f.current(stuck, 0);
        assertFalse(f.stuck(stuck, 0, 40));
        assertFalse(f.stuck(stuck, 39, 40));
        assertTrue(f.stuck(stuck, 40, 40), "40 ticks with no progress");
    }

    @Test
    void progressResetsTheStuckClock() {
        PathFollower f = new PathFollower(ROUTE, 0);
        f.current(p(0, 0), 0);
        assertFalse(f.stuck(p(0, 0), 30, 40));
        assertFalse(f.stuck(p(0.4, 0), 35, 40), "0.4 closer is progress");
        assertFalse(f.stuck(p(0.4, 0), 70, 40));
        assertTrue(f.stuck(p(0.4, 0), 75, 40));
    }

    @Test
    void reachingAWaypointCountsAsProgress() {
        PathFollower f = new PathFollower(ROUTE, 0);
        f.current(p(0, 0), 0);
        f.stuck(p(0, 0), 30, 40);
        f.current(p(1, 0), 35); // advanced to the second waypoint
        assertFalse(f.stuck(p(1, 0), 70, 40), "the clock restarted when the waypoint changed");
    }

    @Test
    void remainingIsTheLengthOfTheRestOfTheRoute() {
        PathFollower f = new PathFollower(ROUTE, 0);
        f.current(p(0, 0), 0);
        double expected = 1.0 + 1.0 + Math.sqrt(2) + Math.sqrt(2);
        assertEquals(expected, f.remaining(p(0, 0)), 1e-9);
        assertEquals(0.0, new PathFollower(List.of(), 0).remaining(p(0, 0)));
    }

    @Test
    void anEmptyRouteIsDoneAndHasNoEnd() {
        PathFollower f = new PathFollower(List.of(), 0);
        assertTrue(f.isEmpty());
        assertTrue(f.done());
        assertNull(f.end());
        assertNull(f.current(p(0, 0), 0));
        assertFalse(f.stuck(p(0, 0), 1000, 10));
    }
}
