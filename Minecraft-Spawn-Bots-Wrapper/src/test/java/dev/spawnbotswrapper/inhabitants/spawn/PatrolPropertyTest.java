package dev.spawnbotswrapper.inhabitants.spawn;

import dev.spawnbotswrapper.inhabitants.engine.SpawnPlanner.Position;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile.Behavior;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile.Stance;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile.WalkType;
import dev.spawnbotswrapper.inhabitants.structure.IntBox;
import dev.spawnbotswrapper.inhabitants.util.SplitMix64;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.spawnbotswrapper.inhabitants.spawn.FakeProbe.FEET;
import static dev.spawnbotswrapper.inhabitants.spawn.FakeProbe.FLOOR;
import static dev.spawnbotswrapper.inhabitants.spawn.Fixtures.box;
import static dev.spawnbotswrapper.inhabitants.spawn.Fixtures.planner;
import static dev.spawnbotswrapper.inhabitants.spawn.Fixtures.single;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-style test: 500 random obstacle worlds, three stances each, every result re-judged by the
 * independent {@link WalkRules} through {@link PlanAsserts}. The planner is free to give up (guard post or
 * stand) in a hostile map, but it must never return a waypoint or a segment the judge rejects.
 */
class PatrolPropertyTest {
    private static final int SIZE = 36;
    private static final int MAPS = 500;

    private static FakeProbe randomWorld(SplitMix64 r) {
        FakeProbe p = new FakeProbe().floor(0, 0, SIZE - 1, SIZE - 1);
        int features = r.nextInt(SIZE * SIZE / 4);
        for (int i = 0; i < features; i++) {
            int x0 = r.nextInt(SIZE);
            int z0 = r.nextInt(SIZE);
            int x1 = Math.min(SIZE - 1, x0 + r.nextInt(4));
            int z1 = Math.min(SIZE - 1, z0 + r.nextInt(4));
            switch (r.nextInt(14)) {
                case 0, 1, 2 -> p.fill(x0, FEET, z0, x1, FEET + 2, z1, Cell.SOLID_OTHER);
                case 3 -> p.fill(x0, FLOOR, z0, x1, FLOOR, z1, Cell.EMPTY);
                case 4 -> {
                    p.fill(x0, FLOOR, z0, x1, FLOOR, z1, Cell.EMPTY);
                    p.floor(x0, z0, x1, z1, FLOOR - 1);
                }
                case 5 -> {
                    p.fill(x0, FLOOR, z0, x1, FLOOR, z1, Cell.EMPTY);
                    p.floor(x0, z0, x1, z1, FLOOR - 2);
                }
                case 6 -> p.fill(x0, FLOOR, z0, x1, FLOOR, z1, Cell.HAZARD);
                case 7 -> p.fill(x0, FLOOR, z0, x1, FEET + 1, z1, Cell.WATER);
                case 8, 9 -> p.floor(x0, z0, x1, z1, FLOOR + 1);
                case 10 -> p.floor(x0, z0, x1, z1, FLOOR + 1).floor(x0, z0, x1, z1, FLOOR + 2);
                case 11 -> p.fill(x0, FEET + 1, z0, x1, FEET + 1, z1, Cell.SOLID_OTHER);
                case 12 -> p.fill(x0, r.nextBoolean() ? FEET : FEET + 1, z0, x1, r.nextBoolean() ? FEET : FEET + 1, z1,
                        r.nextBoolean() ? Cell.HAZARD : Cell.SOLID_HAZARD);
                default -> {
                    if (r.nextInt(4) == 0) {
                        p.unloaded(x0, z0, x1, z1);
                    }
                }
            }
        }
        if (r.nextInt(4) == 0) {
            p.border(r.nextInt(6), r.nextInt(6), SIZE - 1 - r.nextInt(6), SIZE - 1 - r.nextInt(6));
        }
        return p;
    }

    /** A random dry standing position, found with the independent rule, or null when the map has none. */
    private static Position randomHome(FakeProbe p, SplitMix64 r) {
        for (int i = 0; i < 300; i++) {
            int x = r.nextInt(SIZE);
            int z = r.nextInt(SIZE);
            for (int y = FEET; y <= FEET + 3; y++) {
                if (WalkRules.standable(p, x, y, z, false)) {
                    double ox = r.nextInt(4) == 0 ? r.nextInt(100) / 100.0 : 0.5;
                    double oz = r.nextInt(4) == 0 ? r.nextInt(100) / 100.0 : 0.5;
                    return new Position(x + ox, y, z + oz, 0);
                }
            }
        }
        return null;
    }

    @Test
    void randomObstacleWorldsNeverYieldAnUnwalkableWaypointOrSegment() {
        int patrols = 0;
        int patrolsOfThreeOrMore = 0;
        int rings = 0;
        int plans = 0;
        int mapsWithoutHome = 0;
        for (long seed = 0; seed < MAPS; seed++) {
            SplitMix64 r = new SplitMix64(seed * 104729 + 13);
            FakeProbe world = randomWorld(r);
            Position home = randomHome(world, r);
            if (home == null) {
                mapsWithoutHome++;
                continue;
            }
            int x0 = r.nextInt(SIZE / 2);
            int z0 = r.nextInt(SIZE / 2);
            IntBox bounds = box(x0, FLOOR - 2, z0, x0 + 4 + r.nextInt(SIZE), FEET + 8, z0 + 4 + r.nextInt(SIZE));
            double radius = 1 + r.nextInt(20) + r.nextDouble();
            int count = r.nextInt(9);
            boolean combatant = r.nextBoolean();
            String walk = List.of(WalkType.BHOP, WalkType.SPRINT, WalkType.WALK).get(r.nextInt(3));
            for (String stance : List.of(Stance.PATROL_PINGPONG, Stance.PATROL_CYCLE, Stance.GUARD_POST)) {
                Behavior req = new Behavior(stance, combatant, walk, radius, count, List.of());
                long planSeed = seed * 31 + stance.length();
                Behavior first = planner().planBehavior(world, home, req, single(bounds), new SplitMix64(planSeed));
                boolean patrol = PlanAsserts.assertSound(world, home, req, first, bounds);
                assertEquals(first, planner().planBehavior(world, home, req, single(bounds), new SplitMix64(planSeed)),
                        "not deterministic, seed " + seed);
                assertEquals(0, world.outOfRangeReads(), "asked the probe about a block outside the world");
                plans++;
                if (patrol) {
                    patrols++;
                    patrolsOfThreeOrMore += first.waypointCount() >= 3 ? 1 : 0;
                    rings += Stance.PATROL_CYCLE.equals(first.stance()) ? 1 : 0;
                }
            }
        }
        assertTrue(mapsWithoutHome < MAPS / 10, "the generator is too hostile: " + mapsWithoutHome);
        assertTrue(patrols > plans / 4, "the property is vacuous, only " + patrols + " of " + plans + " plans were patrols");
        assertTrue(patrolsOfThreeOrMore > patrols / 3, "long patrols are too rare: " + patrolsOfThreeOrMore + " of " + patrols);
        assertTrue(rings > 50, "rings are too rare: " + rings);
    }

    /** Guards the judge itself: it must reject what the planner is supposed to refuse. */
    @Test
    void theIndependentJudgeRejectsWallsGapsLavaWaterAndTwoBlockClimbs() {
        FakeProbe flat = new FakeProbe().floor(0, 0, 30, 30);
        assertTrue(WalkRules.walkable(flat, 5.5, 5.5, FEET, 20.5, 5.5, FEET));

        FakeProbe wall = new FakeProbe().floor(0, 0, 30, 30).fill(10, FEET, 0, 10, FEET + 2, 30, Cell.SOLID_OTHER);
        assertFalse(WalkRules.walkable(wall, 5.5, 5.5, FEET, 20.5, 5.5, FEET));

        FakeProbe gap = new FakeProbe().floor(0, 0, 30, 30).fill(10, FLOOR, 0, 10, FLOOR, 30, Cell.EMPTY);
        assertFalse(WalkRules.walkable(gap, 5.5, 5.5, FEET, 20.5, 5.5, FEET));

        FakeProbe lava = new FakeProbe().floor(0, 0, 30, 30).fill(10, FLOOR, 0, 10, FLOOR, 30, Cell.HAZARD);
        assertFalse(WalkRules.walkable(lava, 5.5, 5.5, FEET, 20.5, 5.5, FEET));

        FakeProbe water = new FakeProbe().floor(0, 0, 30, 30).fill(10, FEET, 0, 10, FEET, 30, Cell.WATER);
        assertFalse(WalkRules.walkable(water, 5.5, 5.5, FEET, 20.5, 5.5, FEET));

        FakeProbe step1 = new FakeProbe().floor(0, 0, 30, 30).floor(10, 0, 30, 30, FLOOR + 1);
        assertTrue(WalkRules.walkable(step1, 5.5, 5.5, FEET, 20.5, 5.5, FEET + 1));
        FakeProbe step2 = new FakeProbe().floor(0, 0, 30, 30).floor(10, 0, 30, 30, FLOOR + 1).floor(10, 0, 30, 30, FLOOR + 2);
        assertFalse(WalkRules.walkable(step2, 5.5, 5.5, FEET, 20.5, 5.5, FEET + 2));
        assertTrue(WalkRules.walkable(step2, 20.5, 5.5, FEET + 2, 5.5, 5.5, FEET), "two down is fine");
    }
}
