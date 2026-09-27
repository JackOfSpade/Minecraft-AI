package dev.spawnbotswrapper.inhabitants.spawn;

import dev.spawnbotswrapper.inhabitants.engine.SpawnPlanner.Position;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile.Behavior;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile.Stance;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile.Waypoint;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile.WalkType;
import dev.spawnbotswrapper.inhabitants.structure.IntBox;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;
import dev.spawnbotswrapper.inhabitants.util.SplitMix64;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static dev.spawnbotswrapper.inhabitants.spawn.FakeProbe.FEET;
import static dev.spawnbotswrapper.inhabitants.spawn.FakeProbe.FLOOR;
import static dev.spawnbotswrapper.inhabitants.spawn.Fixtures.box;
import static dev.spawnbotswrapper.inhabitants.spawn.Fixtures.planner;
import static dev.spawnbotswrapper.inhabitants.spawn.Fixtures.single;
import static org.junit.jupiter.api.Assertions.*;

class PlanBehaviorTest {

    private static final IntBox FIELD_BOX = box(0, FLOOR, 0, 59, FLOOR + 10, 59);
    private static final Position HOME = new Position(30.5, FEET, 30.5, 0f);

    private static FakeProbe field() {
        return new FakeProbe().floor(0, 0, 59, 59);
    }

    private static Behavior ask(String stance, double radius, int count) {
        return new Behavior(stance, true, WalkType.WALK, radius, count, List.of());
    }

    private static Behavior plan(FakeProbe probe, Position home, Behavior requested, IntBox bounds, long seed) {
        return planner().planBehavior(probe, home, requested, single(bounds), new SplitMix64(seed));
    }

    private static Behavior plan(FakeProbe probe, Position home, Behavior requested, long seed) {
        return plan(probe, home, requested, FIELD_BOX, seed);
    }

    /** Plans and re-validates the result independently; returns it. */
    private static Behavior planChecked(FakeProbe probe, Position home, Behavior requested, IntBox bounds, long seed) {
        Behavior result = plan(probe, home, requested, bounds, seed);
        PlanAsserts.assertSound(probe, home, requested, result, bounds);
        return result;
    }

    private static Behavior planChecked(FakeProbe probe, Position home, Behavior requested, long seed) {
        return planChecked(probe, home, requested, FIELD_BOX, seed);
    }

    // ---------------------------------------------------------------- STAND and GUARD_POST

    @Test
    void standComesBackUnchangedEvenWhenHomeIsNonsense() {
        Behavior stand = new Behavior(Stance.STAND, false, WalkType.SPRINT, 0, 0, List.of());
        FakeProbe nothing = new FakeProbe();
        assertSame(stand, plan(nothing, new Position(0, 0, 0, 0), stand, 1));
        assertSame(stand, plan(field(), HOME, stand, 1));
        Behavior defaults = Behavior.standing();
        assertSame(defaults, plan(field(), HOME, defaults, 1));
    }

    @Test
    void guardPostIsExactlyOneWaypointAtHome() {
        Behavior req = new Behavior(Stance.GUARD_POST, false, WalkType.SPRINT, 9, 4, List.of());
        Behavior r = plan(field(), HOME, req, 1);
        assertEquals(Stance.GUARD_POST, r.stance());
        assertEquals(1, r.waypointCount());
        assertEquals(List.of(new Waypoint(30.5, FEET, 30.5)), r.waypoints());
        assertEquals(0.0, r.patrolRadius());
        assertFalse(r.combatant());
        assertEquals(WalkType.SPRINT, r.walkType());
        PlanAsserts.assertSound(field(), HOME, req, r, FIELD_BOX);
    }

    @Test
    void guardPostKeepsTheHomeCoordinatesVerbatim() {
        Position odd = new Position(30.3, 64.0, 30.8, 123f);
        Behavior r = plan(field(), odd, ask(Stance.GUARD_POST, 5, 1), 1);
        assertEquals(List.of(new Waypoint(30.3, 64.0, 30.8)), r.waypoints());
    }

    @Test
    void aHomeThatCannotBeVerifiedDegradesEveryStanceToStand() {
        List<FakeProbe> badWorlds = new ArrayList<>();
        badWorlds.add(new FakeProbe());
        badWorlds.add(new FakeProbe().fill(0, FLOOR, 0, 59, FLOOR, 59, Cell.HAZARD));
        badWorlds.add(field().set(30, FEET, 30, Cell.HAZARD));
        badWorlds.add(field().set(30, FEET + 1, 30, Cell.SOLID_OTHER));
        badWorlds.add(field().set(30, FEET, 30, Cell.SOLID_OTHER));
        badWorlds.add(field().unloaded(30, 30, 30, 30));
        badWorlds.add(field().border(0, 0, 20, 59));
        for (FakeProbe world : badWorlds) {
            for (String stance : List.of(Stance.GUARD_POST, Stance.PATROL_PINGPONG, Stance.PATROL_CYCLE)) {
                Behavior req = new Behavior(stance, false, WalkType.BHOP, 8, 4, List.of());
                Behavior r = plan(world, HOME, req, 1);
                assertEquals(Stance.STAND, r.stance(), stance);
                assertEquals(0, r.waypointCount());
                assertTrue(r.waypoints().isEmpty());
                assertEquals(0.0, r.patrolRadius());
                assertFalse(r.combatant(), "combatant survives the degrade");
                assertEquals(WalkType.BHOP, r.walkType(), "walk type survives the degrade");
                PlanAsserts.assertSound(world, HOME, req, r, FIELD_BOX);
            }
        }
    }

    @Test
    void aHomeOutsideTheWorldHeightIsNotVerified() {
        FakeProbe p = new FakeProbe(0, 20).floor(0, 0, 10, 10, 17).floor(20, 20, 30, 30, 18).floor(40, 40, 50, 50, -1);
        Behavior top = plan(p, new Position(25.5, 19, 25.5, 0), ask(Stance.GUARD_POST, 5, 1), 1);
        assertEquals(Stance.STAND, top.stance(), "feet at 19 leave no spare block below the world's top");
        Behavior fine = plan(p, new Position(5.5, 18, 5.5, 0), ask(Stance.GUARD_POST, 5, 1), 1);
        assertEquals(Stance.GUARD_POST, fine.stance());
        Behavior bottom = plan(new FakeProbe(0, 20).floor(0, 0, 10, 10, 0), new Position(5.5, 1, 5.5, 0), ask(Stance.GUARD_POST, 5, 1), 1);
        assertEquals(Stance.GUARD_POST, bottom.stance(), "a floor at the very bottom of the world is fine");
        Behavior below = plan(p, new Position(45.5, 0, 45.5, 0), ask(Stance.GUARD_POST, 5, 1), 1);
        assertEquals(Stance.STAND, below.stance());
        assertEquals(0, p.outOfRangeReads());
    }

    @Test
    void aHomeBarelyBelowItsBlockAfterFloatRoundTripsIsStillVerified() {
        Position drifted = new Position(30.5, FEET - 1e-9, 30.5, 0);
        Behavior r = plan(field(), drifted, ask(Stance.PATROL_PINGPONG, 8, 3), 1);
        assertEquals(Stance.PATROL_PINGPONG, r.stance());
        assertEquals(FEET - 1e-9, r.waypoints().get(0).y(), 0.0, "first waypoint echoes home verbatim");
        assertTrue(r.waypoints().stream().skip(1).allMatch(w -> w.y() == FEET));
    }

    @Test
    void aHomeWithNonFiniteCoordinatesIsNotVerifiedNotFlooredToTheOrigin() {
        FakeProbe world = field().floor(-5, -5, 5, 5);
        for (Position bad : List.of(new Position(Double.NaN, FEET, 0.5, 0), new Position(0.5, Double.NaN, 0.5, 0),
                new Position(0.5, FEET, Double.POSITIVE_INFINITY, 0), new Position(Double.NEGATIVE_INFINITY, FEET, 0.5, 0))) {
            for (String stance : List.of(Stance.GUARD_POST, Stance.PATROL_PINGPONG, Stance.PATROL_CYCLE)) {
                assertEquals(Stance.STAND, plan(world, bad, ask(stance, 8, 3), 1).stance(), bad + " " + stance);
            }
        }
    }

    @Test
    void unknownStancesAreTreatedAsStanding() {
        Behavior r = plan(field(), HOME, new Behavior("WANDER_FOREVER", true, WalkType.WALK, 8, 4, List.of()), 1);
        assertEquals(Stance.STAND, r.stance());
        assertTrue(r.waypoints().isEmpty());
    }

    // ---------------------------------------------------------------- water at home

    @Test
    void aSubmergedHomeNeedsTheFlagAndCanOnlyGuard() {
        FakeProbe wet = field().fill(30, FEET, 30, 30, FEET + 1, 30, Cell.WATER);
        Behavior patrol = ask(Stance.PATROL_PINGPONG, 8, 4);
        assertEquals(Stance.STAND, plan(wet, HOME, patrol, 1).stance(), "water not allowed: unverifiable");
        DefaultSpawnPlanner allowing = planner(o -> o.allowSubmerged = true);
        Behavior guard = allowing.planBehavior(wet, HOME, patrol, single(FIELD_BOX), new SplitMix64(1));
        assertEquals(Stance.GUARD_POST, guard.stance(), "a bot in water cannot start a walk");
        assertEquals(List.of(new Waypoint(30.5, FEET, 30.5)), guard.waypoints());
        Behavior explicit = allowing.planBehavior(wet, HOME, ask(Stance.GUARD_POST, 3, 1), single(FIELD_BOX), new SplitMix64(1));
        assertEquals(Stance.GUARD_POST, explicit.stance());
    }

    // ---------------------------------------------------------------- open-ground patrols

    @Test
    void everyWaypointCountFromTwoToSixIsHonouredOnOpenGround() {
        for (String stance : List.of(Stance.PATROL_PINGPONG, Stance.PATROL_CYCLE)) {
            for (int count = 2; count <= 6; count++) {
                for (long seed = 0; seed < 8; seed++) {
                    Behavior r = planChecked(field(), HOME, ask(stance, 12, count), seed);
                    assertEquals(stance, r.stance());
                    assertEquals(count, r.waypointCount(), stance + " count " + count + " seed " + seed);
                    assertEquals(count, r.waypoints().size());
                    assertEquals(new Waypoint(30.5, FEET, 30.5), r.waypoints().get(0));
                }
            }
        }
    }

    @Test
    void requestedCountsOutsideTwoToSixAreClamped() {
        assertEquals(6, planChecked(field(), HOME, ask(Stance.PATROL_PINGPONG, 15, 9), 1).waypointCount());
        assertEquals(6, planChecked(field(), HOME, ask(Stance.PATROL_CYCLE, 15, 100), 1).waypointCount());
        for (int low : new int[]{1, 0, -4}) {
            assertEquals(2, planChecked(field(), HOME, ask(Stance.PATROL_PINGPONG, 15, low), 1).waypointCount());
        }
    }

    @Test
    void waypointsKeepToTheRadiusAndPatrolRadiusReportsTheActualReach() {
        for (long seed = 0; seed < 20; seed++) {
            Behavior r = planChecked(field(), HOME, ask(Stance.PATROL_PINGPONG, 5.0, 4), seed);
            assertEquals(Stance.PATROL_PINGPONG, r.stance());
            assertTrue(r.patrolRadius() <= 5.0 + 1e-9);
            assertTrue(r.patrolRadius() >= 2.0, "at least one waypoint is a real distance away");
        }
    }

    @Test
    void aRadiusOfExactlyTwoAllowsTheClosestLegalWaypoint() {
        Set<String> spots = new HashSet<>();
        for (long seed = 0; seed < 40; seed++) {
            Behavior r = planChecked(field(), HOME, ask(Stance.PATROL_PINGPONG, 2.0, 2), seed);
            assertEquals(Stance.PATROL_PINGPONG, r.stance());
            assertEquals(2, r.waypointCount());
            assertEquals(2.0, r.patrolRadius(), 1e-12);
            spots.add(r.waypoints().get(1).x() + "," + r.waypoints().get(1).z());
        }
        assertEquals(Set.of("32.5,30.5", "28.5,30.5", "30.5,32.5", "30.5,28.5"), spots);
    }

    @Test
    void radiusBelowTheWaypointSpacingCannotPatrolAndGuardsInstead() {
        for (double radius : new double[]{1.99, 1.0, 0.5, 0.0, -3.0, Double.NaN}) {
            for (String stance : List.of(Stance.PATROL_PINGPONG, Stance.PATROL_CYCLE)) {
                Behavior r = planChecked(field(), HOME, ask(stance, radius, 4), 1);
                assertEquals(Stance.GUARD_POST, r.stance(), stance + " radius " + radius);
                assertEquals(1, r.waypointCount());
                assertEquals(0.0, r.patrolRadius());
            }
        }
    }

    @Test
    void anIslandOfOneBlockGuardsInsteadOfPatrolling() {
        FakeProbe island = new FakeProbe().floor(30, 30, 30, 30);
        for (String stance : List.of(Stance.PATROL_PINGPONG, Stance.PATROL_CYCLE)) {
            Behavior r = planChecked(island, HOME, ask(stance, 20, 5), 1);
            assertEquals(Stance.GUARD_POST, r.stance());
        }
    }

    @Test
    void aSinglePointPatrolIsReportedAsAGuardPostNeverAsAPatrol() {
        FakeProbe twoAdjacentBlocks = new FakeProbe().floor(30, 30, 31, 30);
        for (long seed = 0; seed < 20; seed++) {
            for (String stance : List.of(Stance.PATROL_PINGPONG, Stance.PATROL_CYCLE)) {
                Behavior r = planChecked(twoAdjacentBlocks, HOME, ask(stance, 20, 6), seed);
                assertEquals(Stance.GUARD_POST, r.stance(), "the neighbour is inside PvP BOT's 1.5 block arrival radius");
                assertEquals(1, r.waypoints().size(), "upstream ping-pong with one point crashes the server tick");
            }
        }
    }

    @Test
    void largeRadiiAreCappedAndStillProduceValidPatrols() {
        Behavior r = planChecked(new FakeProbe().floor(-200, -200, 260, 260), HOME,
                ask(Stance.PATROL_PINGPONG, 1_000_000, 6), box(-100, FLOOR, -100, 200, FLOOR + 10, 200), 1);
        assertEquals(Stance.PATROL_PINGPONG, r.stance());
        assertTrue(r.patrolRadius() <= 128.0 + 1e-9);
    }

    // ---------------------------------------------------------------- soft limit

    @Test
    void waypointsStayInsideTheStructureBoundsExpandedBySixteen() {
        IntBox tight = box(25, FLOOR, 25, 34, FLOOR + 10, 34);
        boolean wanderedOutside = false;
        for (long seed = 0; seed < 30; seed++) {
            Behavior r = planChecked(field(), HOME, ask(Stance.PATROL_PINGPONG, 100, 6), tight, seed);
            for (Waypoint w : r.waypoints()) {
                assertTrue(w.x() >= 9 && w.x() < 51 && w.z() >= 9 && w.z() < 51, "outside bounds+16: " + w);
                wanderedOutside |= w.x() < 25 || w.x() >= 35 || w.z() < 25 || w.z() >= 35;
            }
        }
        assertTrue(wanderedOutside, "the limit is soft: a patrol may leave the raw bounds");
    }

    // ---------------------------------------------------------------- what stops a patrol

    private static final List<Consumer<FakeProbe>> BARRIERS = List.of(
            p -> p.fill(20, FEET, 0, 20, FEET + 2, 59, Cell.SOLID_OTHER),
            p -> p.fill(20, FLOOR, 0, 20, FLOOR, 59, Cell.EMPTY),
            p -> p.fill(20, FLOOR, 0, 20, FLOOR, 59, Cell.HAZARD),
            p -> p.fill(20, FLOOR, 0, 20, FEET + 1, 59, Cell.WATER),
            p -> p.fill(20, FEET, 0, 20, FEET + 1, 59, Cell.SOLID_HAZARD),
            p -> p.fill(20, FEET, 0, 20, FEET, 59, Cell.HAZARD),
            p -> p.fill(20, FEET + 1, 0, 20, FEET + 1, 59, Cell.HAZARD),
            p -> p.fill(20, FEET + 1, 0, 20, FEET + 1, 59, Cell.SOLID_OTHER),
            p -> p.fill(20, FLOOR + 1, 0, 20, FLOOR + 2, 59, Cell.SOLID_STANDABLE),
            p -> {
                p.fill(20, FLOOR, 0, 20, FLOOR, 59, Cell.EMPTY);
                p.floor(20, 0, 20, 59, FLOOR - 3);
            },
            p -> p.unloaded(20, 0, 20, 59));

    @Test
    void noBarrierIsEverCrossedByAPatrolStartingOnOneSideOfIt() {
        Position west = new Position(10.5, FEET, 30.5, 0);
        int realPatrols = 0;
        for (int b = 0; b < BARRIERS.size(); b++) {
            for (String stance : List.of(Stance.PATROL_PINGPONG, Stance.PATROL_CYCLE)) {
                for (long seed = 0; seed < 12; seed++) {
                    FakeProbe p = field();
                    BARRIERS.get(b).accept(p);
                    Behavior req = ask(stance, 25, 4);
                    Behavior r = planChecked(p, west, req, seed);
                    for (Waypoint w : r.waypoints()) {
                        assertTrue(w.x() < 20, "barrier " + b + " was crossed by " + w);
                    }
                    if (r.stance().startsWith("PATROL")) {
                        realPatrols++;
                    }
                }
            }
        }
        assertEquals(BARRIERS.size() * 2 * 12, realPatrols, "the west side alone is roomy enough for every patrol");
    }

    @Test
    void aDoorwayBetweenTwoRoomsIsUsedOnlyAlongTheStraightLineThroughIt() {
        FakeProbe p = field();
        for (int x = 5; x <= 30; x++) {
            for (int z = 25; z <= 35; z++) {
                boolean wall = x == 5 || x == 30 || z == 25 || z == 35 || (x == 20 && z != 30);
                if (wall) {
                    p.fill(x, FEET, z, x, FEET + 2, z, Cell.SOLID_OTHER);
                }
            }
        }
        Position west = new Position(10.5, FEET, 30.5, 0);
        boolean crossed = false;
        for (long seed = 0; seed < 200; seed++) {
            Behavior r = planChecked(p, west, ask(Stance.PATROL_PINGPONG, 25, 4), seed);
            for (Waypoint w : r.waypoints()) {
                assertTrue(w.x() > 5 && w.x() < 30 && w.z() > 25 && w.z() < 35, "left the rooms: " + w);
                crossed |= w.x() > 21;
            }
        }
        assertTrue(crossed, "through the door is straight, open and legal");
    }

    @Test
    void aOneBlockStepUpIsPatrolledInBothDirectionsWithExactLevels() {
        FakeProbe pit = field().floor(0, 0, 59, 59, FLOOR + 1);
        pit.set(30, FLOOR + 1, 30, Cell.EMPTY);
        Position inPit = new Position(30.5, FEET, 30.5, 0);
        for (long seed = 0; seed < 10; seed++) {
            Behavior r = planChecked(pit, inPit, ask(Stance.PATROL_PINGPONG, 8, 4), seed);
            assertEquals(Stance.PATROL_PINGPONG, r.stance());
            for (Waypoint w : r.waypoints().subList(1, r.waypoints().size())) {
                assertEquals(FEET + 1.0, w.y(), 0.0, "the rim is one block above the pit floor");
            }
        }
    }

    @Test
    void aTwoBlockRimTrapsAGuard() {
        FakeProbe pit = field().floor(0, 0, 59, 59, FLOOR + 1).floor(0, 0, 59, 59, FLOOR + 2);
        pit.set(30, FLOOR + 1, 30, Cell.EMPTY).set(30, FLOOR + 2, 30, Cell.EMPTY);
        Position inPit = new Position(30.5, FEET, 30.5, 0);
        for (String stance : List.of(Stance.PATROL_PINGPONG, Stance.PATROL_CYCLE)) {
            Behavior r = planChecked(pit, inPit, ask(stance, 8, 4), 1);
            assertEquals(Stance.GUARD_POST, r.stance(), "PvP BOT cannot hop two blocks");
        }
    }

    // ---------------------------------------------------------------- one-way segments

    private static FakeProbe pillarOverLowGround(int groundFloorY) {
        FakeProbe p = new FakeProbe().floor(0, 0, 29, 29, groundFloorY);
        for (int y = groundFloorY; y <= FLOOR; y++) {
            p.set(15, y, 15, Cell.SOLID_STANDABLE);
        }
        return p;
    }

    private static final IntBox LOW_BOX = box(0, FLOOR - 5, 0, 29, FLOOR + 10, 29);
    private static final Position PILLAR_TOP = new Position(15.5, FEET, 15.5, 0);

    @Test
    void aTwoBlockDropIsOneWayAndSoNeverPatrolled() {
        FakeProbe p = pillarOverLowGround(FLOOR - 2);
        for (String stance : List.of(Stance.PATROL_PINGPONG, Stance.PATROL_CYCLE)) {
            for (int count = 2; count <= 6; count++) {
                for (long seed = 0; seed < 5; seed++) {
                    Behavior r = planChecked(p, PILLAR_TOP, ask(stance, 10, count), LOW_BOX, seed);
                    assertEquals(Stance.GUARD_POST, r.stance(),
                            stance + ": walking down is fine, but the bot could never come back up");
                }
            }
        }
    }

    @Test
    void aOneBlockDropIsTwoWayAndPatrolledAtTheLowerLevel() {
        FakeProbe p = pillarOverLowGround(FLOOR - 1);
        for (String stance : List.of(Stance.PATROL_PINGPONG, Stance.PATROL_CYCLE)) {
            for (long seed = 0; seed < 5; seed++) {
                Behavior r = planChecked(p, PILLAR_TOP, ask(stance, 10, 4), LOW_BOX, seed);
                assertEquals(stance, r.stance());
                for (Waypoint w : r.waypoints().subList(1, r.waypoints().size())) {
                    assertEquals(FEET - 1.0, w.y(), 0.0, "ground level, one below the pillar top");
                }
            }
        }
    }

    /**
     * A 1-wide corridor whose level drops: columns 0-2 at the home level, 3-5 one lower, 6-9 two lower
     * again. The last drop is one-way, so anything beyond it can be neither a ping-pong end nor a ring member.
     */
    private static FakeProbe terracedCorridor() {
        FakeProbe p = new FakeProbe();
        p.floor(0, 5, 2, 5, FLOOR).floor(3, 5, 5, 5, FLOOR - 1).floor(6, 5, 9, 5, FLOOR - 3);
        return p;
    }

    @Test
    void ringsAndPingPongsNeverReachPastAOneWayDrop() {
        FakeProbe p = terracedCorridor();
        Position home = new Position(0.5, FEET, 5.5, 0);
        IntBox bounds = box(0, FLOOR - 5, 0, 9, FLOOR + 5, 10);
        for (String stance : List.of(Stance.PATROL_PINGPONG, Stance.PATROL_CYCLE)) {
            for (int count = 2; count <= 5; count++) {
                for (long seed = 0; seed < 25; seed++) {
                    Behavior r = planChecked(p, home, ask(stance, 20, count), bounds, seed);
                    assertEquals(stance, r.stance(), "columns 2..5 are walkable both ways");
                    for (Waypoint w : r.waypoints()) {
                        assertTrue(w.x() < 6, "no way back from " + w);
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- corridors and rings

    private static FakeProbe walledRow() {
        FakeProbe p = new FakeProbe().floor(0, 30, 59, 30);
        p.fill(0, FEET, 29, 59, FEET + 2, 29, Cell.SOLID_OTHER);
        p.fill(0, FEET, 31, 59, FEET + 2, 31, Cell.SOLID_OTHER);
        return p;
    }

    @Test
    void aOneWideCorridorYieldsWaypointsAlongItsAxis() {
        for (String stance : List.of(Stance.PATROL_PINGPONG, Stance.PATROL_CYCLE)) {
            for (long seed = 0; seed < 20; seed++) {
                Behavior r = planChecked(walledRow(), HOME, ask(stance, 20, 4), seed);
                assertEquals(stance, r.stance());
                for (Waypoint w : r.waypoints()) {
                    assertEquals(30.5, w.z(), 0.0);
                    assertTrue(w.x() >= 10.5 && w.x() <= 50.5);
                }
            }
        }
    }

    private static FakeProbe lShapedPath() {
        return new FakeProbe().floor(10, 30, 40, 30).floor(40, 10, 40, 30);
    }

    private static final IntBox L_BOX = box(0, FLOOR, 0, 59, FLOOR + 10, 59);
    private static final Position L_HOME = new Position(12.5, FEET, 30.5, 0);

    @Test
    void aPingPongMayFollowABendBecauseEachSegmentIsStraight() {
        boolean turnedTheCorner = false;
        for (long seed = 0; seed < 300; seed++) {
            Behavior r = planChecked(lShapedPath(), L_HOME, ask(Stance.PATROL_PINGPONG, 40, 5), L_BOX, seed);
            for (Waypoint w : r.waypoints()) {
                turnedTheCorner |= w.z() < 30;
            }
        }
        assertTrue(turnedTheCorner, "home -> corner -> up the second arm is legal");
    }

    @Test
    void aRingCannotUseAnArmItCannotCloseBack() {
        for (int count = 2; count <= 6; count++) {
            for (long seed = 0; seed < 60; seed++) {
                Behavior r = planChecked(lShapedPath(), L_HOME, ask(Stance.PATROL_CYCLE, 40, count), L_BOX, seed);
                assertEquals(Stance.PATROL_CYCLE, r.stance());
                for (Waypoint w : r.waypoints()) {
                    assertEquals(30.5, w.z(), 0.0, "the last waypoint must lead straight back to home; only the first arm does");
                }
            }
        }
    }

    @Test
    void aRingKeepsItsFullLengthWhenAChoiceThatClosesExists() {
        for (long seed = 0; seed < 400; seed++) {
            Behavior r = planChecked(lShapedPath(), L_HOME, ask(Stance.PATROL_CYCLE, 40, 3), L_BOX, seed);
            assertEquals(3, r.waypointCount(), "the last waypoint is chosen so that it can lead home, seed " + seed);
        }
    }

    @Test
    void aRingOnOpenGroundClosesBackToHome() {
        for (long seed = 0; seed < 20; seed++) {
            Behavior r = planChecked(field(), HOME, ask(Stance.PATROL_CYCLE, 12, 6), seed);
            List<Waypoint> w = r.waypoints();
            assertTrue(WalkRules.walkable(field(), w.get(w.size() - 1).x(), w.get(w.size() - 1).z(), FEET, HOME.x(), HOME.z(), FEET));
        }
    }

    // ---------------------------------------------------------------- vertical limits

    @Test
    void waypointsRespectTheVerticalSoftLimitToo() {
        FakeProbe stairs = new FakeProbe();
        for (int x = 0; x < 60; x++) {
            stairs.floor(x, 20, x, 40, FLOOR + x);
        }
        Position bottom = new Position(0.5, FEET, 30.5, 0);
        IntBox bounds = box(0, FLOOR, 0, 59, FLOOR + 2, 59);
        boolean climbed = false;
        for (long seed = 0; seed < 30; seed++) {
            Behavior r = planChecked(stairs, bottom, ask(Stance.PATROL_PINGPONG, 60, 6), bounds, seed);
            for (Waypoint w : r.waypoints()) {
                assertTrue(w.y() <= FLOOR + 2 + 16, "above the vertical soft limit: " + w);
                climbed |= w.y() > FLOOR + 3;
            }
        }
        assertTrue(climbed, "a staircase of one-block hops is walkable, so the patrol does climb");
    }

    // ---------------------------------------------------------------- unloaded chunks

    @Test
    void unloadedTerritoryIsNeitherEnteredNorCrossed() {
        FakeProbe p = field().unloaded(36, 0, 45, 59);
        for (String stance : List.of(Stance.PATROL_PINGPONG, Stance.PATROL_CYCLE)) {
            for (long seed = 0; seed < 25; seed++) {
                Behavior r = planChecked(p, HOME, ask(stance, 15, 5), seed);
                for (Waypoint w : r.waypoints()) {
                    assertTrue(w.x() < 36, "walked into an unloaded chunk: " + w);
                }
            }
        }
    }

    // ---------------------------------------------------------------- fractional homes

    @Test
    void aHomeThatIsNotAtABlockCentreStartsTheFirstSegmentWhereTheBotReallyIs() {
        Position odd = new Position(30.15, FEET, 30.85, 90f);
        for (long seed = 0; seed < 20; seed++) {
            Behavior r = planChecked(field(), odd, ask(Stance.PATROL_PINGPONG, 10, 4), seed);
            assertEquals(new Waypoint(30.15, FEET, 30.85), r.waypoints().get(0));
        }
    }

    // ---------------------------------------------------------------- determinism and variety

    @Test
    void sameGeneratorStateGivesTheSameRoute() {
        for (String stance : List.of(Stance.PATROL_PINGPONG, Stance.PATROL_CYCLE)) {
            for (long seed = 0; seed < 10; seed++) {
                assertEquals(plan(field(), HOME, ask(stance, 12, 5), seed), plan(field(), HOME, ask(stance, 12, 5), seed));
            }
        }
    }

    @Test
    void differentGeneratorsWalkDifferentRoutes() {
        Set<List<Waypoint>> routes = new HashSet<>();
        for (long seed = 0; seed < 30; seed++) {
            routes.add(plan(field(), HOME, ask(Stance.PATROL_PINGPONG, 12, 4), seed).waypoints());
        }
        assertTrue(routes.size() >= 25, "routes should vary, got " + routes.size());
    }

    // ---------------------------------------------------------------- bounded work

    @Test
    void thePlanReadsABoundedNumberOfBlocksEvenInAnOpenHundredAndTwentyEightBlockRadius() {
        StructureSnapshot s = single(box(-200, FLOOR, -200, 400, FLOOR + 10, 400));
        for (String stance : List.of(Stance.PATROL_PINGPONG, Stance.PATROL_CYCLE)) {
            FakeProbe p = new FakeProbe().floor(-200, -200, 400, 400);
            Behavior r = planner().planBehavior(p, HOME, ask(stance, 128, 6), s, new SplitMix64(1));
            assertEquals(stance, r.stance());
            assertTrue(p.reads() < 60_000, "reads " + p.reads());
        }
    }

    @Test
    void anImpossiblePatrolAlsoStopsQuickly() {
        FakeProbe p = field().fill(0, FEET, 0, 59, FEET + 2, 59, Cell.SOLID_OTHER);
        p.fill(30, FEET, 30, 30, FEET + 2, 30, Cell.EMPTY);
        Behavior r = plan(p, HOME, ask(Stance.PATROL_CYCLE, 50, 6), 1);
        assertEquals(Stance.GUARD_POST, r.stance());
        assertTrue(p.reads() < 200, "reads " + p.reads());
    }
}
