package dev.spawnbotswrapper.inhabitants.spawn;

import dev.spawnbotswrapper.inhabitants.engine.SpawnPlanner.Position;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile.Behavior;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile.Stance;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile.Waypoint;
import dev.spawnbotswrapper.inhabitants.structure.IntBox;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Re-validates a planned behaviour from scratch with {@link WalkRules}. Every promise the planner makes in
 * its Javadoc is checked here, so targeted tests and the random-world property test share one judge.
 */
final class PlanAsserts {

    private static final double EPS = 1e-9;

    private PlanAsserts() {
    }

    /** @return true when the result is a genuine multi-waypoint patrol */
    static boolean assertSound(BlockProbe probe, Position home, Behavior requested, Behavior result, IntBox bounds) {
        if (Stance.STAND.equals(requested.stance())) {
            assertSame(requested, result, "STAND must come back unchanged");
            return false;
        }
        assertEquals(requested.combatant(), result.combatant(), "combatant is kept");
        assertEquals(requested.walkType(), result.walkType(), "walk type is kept");

        int hx = (int) Math.floor(home.x());
        int hz = (int) Math.floor(home.z());
        int hy = (int) Math.floor(home.y() + 1e-6);
        Waypoint homePoint = new Waypoint(home.x(), home.y(), home.z());

        switch (result.stance()) {
            case Stance.STAND -> {
                assertEquals(0, result.waypointCount());
                assertTrue(result.waypoints().isEmpty());
                assertEquals(0.0, result.patrolRadius());
                assertFalse(WalkRules.standable(probe, hx, hy, hz, false), "STAND is only for a home that cannot be verified");
                return false;
            }
            case Stance.GUARD_POST -> {
                assertEquals(1, result.waypointCount());
                assertEquals(List.of(homePoint), result.waypoints());
                assertEquals(0.0, result.patrolRadius());
                assertTrue(WalkRules.standable(probe, hx, hy, hz, true), "a guard post needs a verified home");
                return false;
            }
            default -> {
            }
        }

        assertEquals(requested.stance(), result.stance(), "a patrol either keeps its stance or degrades");
        List<Waypoint> w = result.waypoints();
        int n = w.size();
        int wanted = Math.max(2, Math.min(6, requested.waypointCount()));
        assertTrue(n >= 2 && n <= wanted, "waypoint count " + n + " not within 2.." + wanted);
        assertEquals(n, result.waypointCount());
        assertEquals(homePoint, w.get(0), "first waypoint is home");
        assertTrue(WalkRules.standable(probe, hx, hy, hz, false), "patrols start from dry standing ground");

        double radius = Math.min(128.0, Math.max(0.0, requested.patrolRadius()));
        int[] levels = new int[n];
        levels[0] = hy;
        double farthest = 0;
        for (int i = 1; i < n; i++) {
            Waypoint p = w.get(i);
            int x = (int) Math.floor(p.x());
            int z = (int) Math.floor(p.z());
            int y = (int) p.y();
            levels[i] = y;
            assertEquals(y, p.y(), 0.0, "waypoint Y must be an exact block level: " + p);
            assertEquals(x + 0.5, p.x(), 0.0);
            assertEquals(z + 0.5, p.z(), 0.0);
            assertTrue(WalkRules.standable(probe, x, y, z, false), "waypoint not standable: " + p);
            double d = Math.hypot(p.x() - home.x(), p.z() - home.z());
            farthest = Math.max(farthest, d);
            assertTrue(d <= radius + EPS, "waypoint " + d + " blocks from home, radius " + radius);
            assertTrue(x >= bounds.minX() - 16 && x <= bounds.maxX() + 16
                    && z >= bounds.minZ() - 16 && z <= bounds.maxZ() + 16
                    && y >= bounds.minY() - 16 && y <= bounds.maxY() + 16, "outside the soft limit: " + p);
        }
        assertEquals(farthest, result.patrolRadius(), EPS, "patrolRadius is the largest actual distance");

        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double dx = w.get(i).x() - w.get(j).x();
                double dy = w.get(i).y() - w.get(j).y();
                double dz = w.get(i).z() - w.get(j).z();
                assertTrue(Math.sqrt(dx * dx + dy * dy + dz * dz) >= 2.0 - EPS,
                        "waypoints " + i + " and " + j + " are inside PvP BOT's arrival radius of each other");
            }
        }

        for (int i = 0; i + 1 < n; i++) {
            assertTrue(walkable(probe, w, levels, i, i + 1), "segment " + i + "->" + (i + 1) + " is not walkable");
            assertTrue(walkable(probe, w, levels, i + 1, i),
                    "return leg " + (i + 1) + "->" + i + " is not walkable (ping-pong needs it, a ring is kept two-way by design)");
        }
        if (Stance.PATROL_CYCLE.equals(result.stance())) {
            assertTrue(walkable(probe, w, levels, n - 1, 0), "the ring does not close");
        }
        return true;
    }

    private static boolean walkable(BlockProbe probe, List<Waypoint> w, int[] levels, int from, int to) {
        return WalkRules.walkable(probe, w.get(from).x(), w.get(from).z(), levels[from],
                w.get(to).x(), w.get(to).z(), levels[to]);
    }
}
