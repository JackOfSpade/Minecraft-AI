package dev.spawnbotswrapper.inhabitants.adapter;

import dev.spawnbotswrapper.inhabitants.profile.BotProfile;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile.Behavior;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile.Stance;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile.Waypoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpstreamPathPlannerTest {

    private static List<Waypoint> points(int n) {
        List<Waypoint> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new Waypoint(10.5 + i, 64, -3.5 + 2 * i));
        }
        return out;
    }

    private static Behavior behavior(String stance, boolean combatant, String walkType, int waypoints) {
        return new Behavior(stance, combatant, walkType, 6.0, waypoints, points(waypoints));
    }

    // ---------------------------------------------------------------- path naming

    @Test
    void pathNameIsPrefixPlusLowerCasedBotName() {
        assertEquals("inh_inh_foo", UpstreamPathPlanner.pathName("Inh_Foo"));
        assertEquals("inh_abc123", UpstreamPathPlanner.pathName("ABC123"));
    }

    @Test
    void pathNameSameForNamesDifferingOnlyInCase() {
        assertEquals(UpstreamPathPlanner.pathName("Inh_Foo"), UpstreamPathPlanner.pathName("iNH_fOO"));
    }

    @Test
    void pathNameReplacesEveryCharacterOutsideTheSafeSet() {
        assertEquals("inh_a_b_c__d", UpstreamPathPlanner.pathName("A-b.c +D"));
        assertEquals("inh_na_ve", UpstreamPathPlanner.pathName("naïve"));
    }

    @Test
    void pathNameIsNeverLongerThanTheLimit() {
        String longName = "x".repeat(200);
        String path = UpstreamPathPlanner.pathName(longName);
        assertEquals(UpstreamPathPlanner.MAX_PATH_NAME, path.length());
        assertTrue(path.startsWith(UpstreamPathPlanner.PATH_PREFIX));
    }

    @Test
    void pathNameMatchesTheSafeCharacterClass() {
        assertTrue(UpstreamPathPlanner.pathName("Any_Name_16chars").matches("[a-z0-9_]{5,40}"));
    }

    @Test
    void pathNameIsNullForNothing() {
        assertNull(UpstreamPathPlanner.pathName(null));
        assertNull(UpstreamPathPlanner.pathName(""));
        assertNull(UpstreamPathPlanner.pathName("   "));
    }

    // ---------------------------------------------------------------- stance mapping

    @Test
    void guardPostIsOnePointAndNoLoop() {
        PatrolPlan plan = UpstreamPathPlanner.plan("Inh_Foo", behavior(Stance.GUARD_POST, true, "bhop", 1)).plan();
        assertNotNull(plan);
        assertEquals(1, plan.points().size());
        assertFalse(plan.loop(), "loop=true would be ping-pong, and a one-point ping-pong crashes upstream");
        assertEquals("inh_inh_foo", plan.pathName());
    }

    @Test
    void guardPostKeepsOnlyTheFirstOfSeveralWaypoints() {
        PatrolPlan plan = UpstreamPathPlanner.plan("Inh_Foo", behavior(Stance.GUARD_POST, true, "bhop", 4)).plan();
        assertEquals(List.of(points(4).get(0)), plan.points());
        assertFalse(plan.loop());
        assertFalse(plan.notes().isEmpty(), "the dropped waypoints are reported");
    }

    @Test
    void patrolCycleIsARingSoLoopIsFalse() {
        PatrolPlan plan = UpstreamPathPlanner.plan("Inh_Foo", behavior(Stance.PATROL_CYCLE, true, "sprint", 5)).plan();
        assertEquals(5, plan.points().size());
        assertFalse(plan.loop(), "upstream's loop=false is the cyclic ring");
        assertEquals("sprint", plan.walkType());
    }

    @Test
    void patrolPingPongLoopsOnlyWithTwoOrMorePoints() {
        PatrolPlan two = UpstreamPathPlanner.plan("Inh_Foo", behavior(Stance.PATROL_PINGPONG, true, "walk", 2)).plan();
        assertTrue(two.loop(), "upstream's loop=true is ping-pong");
        assertEquals(2, two.points().size());

        PatrolPlan five = UpstreamPathPlanner.plan("Inh_Foo", behavior(Stance.PATROL_PINGPONG, true, "walk", 5)).plan();
        assertTrue(five.loop());
    }

    @Test
    void aSinglePointPingPongIsDowngradedNeverPassedToUpstream() {
        // Trap P1: loop=true with one point makes upstream read index -1 in its tick. Even if the caller is
        // wrong the planner must not produce it.
        PatrolPlan plan = UpstreamPathPlanner.plan("Inh_Foo", behavior(Stance.PATROL_PINGPONG, true, "bhop", 1)).plan();
        assertNotNull(plan);
        assertFalse(plan.loop());
        assertEquals(1, plan.points().size());
        assertTrue(plan.notes().stream().anyMatch(n -> n.contains("P1")));
    }

    @Test
    void aSinglePointCycleBecomesAGuardPost() {
        PatrolPlan plan = UpstreamPathPlanner.plan("Inh_Foo", behavior(Stance.PATROL_CYCLE, true, "bhop", 1)).plan();
        assertFalse(plan.loop());
        assertEquals(1, plan.points().size());
    }

    @Test
    void downgradingKeepsTheBotAFighter() {
        PatrolPlan plan = UpstreamPathPlanner.plan("Inh_Foo", behavior(Stance.PATROL_PINGPONG, true, "bhop", 1)).plan();
        assertTrue(plan.attack());
    }

    @Test
    void everyPathIsBuiltWithTheAttackFlagOnWhateverTheLegacyFlagSays() {
        for (String stance : List.of(Stance.GUARD_POST, Stance.PATROL_CYCLE, Stance.PATROL_PINGPONG)) {
            int points = stance.equals(Stance.GUARD_POST) ? 1 : 3;
            assertTrue(UpstreamPathPlanner.plan("Inh_Foo", behavior(stance, true, "bhop", points)).plan().attack(), stance);
            assertTrue(UpstreamPathPlanner.plan("Inh_Foo", behavior(stance, false, "bhop", points)).plan().attack(),
                    stance + " with the retired pacifist flag must still attack");
        }
    }

    @Test
    void standingStillHasNoPath() {
        UpstreamPathPlanner.Outcome o = UpstreamPathPlanner.plan("Inh_Foo", Behavior.standing());
        assertNull(o.plan());
        assertTrue(o.rejection().contains("STAND"));
    }

    @Test
    void noBehaviourNoPath() {
        assertNull(UpstreamPathPlanner.plan("Inh_Foo", null).plan());
    }

    @Test
    void anUnknownStanceIsRefusedNotGuessed() {
        UpstreamPathPlanner.Outcome o = UpstreamPathPlanner.plan("Inh_Foo", behavior("DANCE", true, "bhop", 3));
        assertNull(o.plan());
        assertTrue(o.rejection().contains("DANCE"));
    }

    @Test
    void aPathStanceWithoutWaypointsIsRefused() {
        assertNull(UpstreamPathPlanner.plan("Inh_Foo", behavior(Stance.GUARD_POST, true, "bhop", 0)).plan());
    }

    @Test
    void nonFiniteWaypointsAreRefused() {
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            for (int axis = 0; axis < 3; axis++) {
                List<Waypoint> w = new ArrayList<>(points(3));
                w.set(1, new Waypoint(axis == 0 ? bad : 1, axis == 1 ? bad : 2, axis == 2 ? bad : 3));
                UpstreamPathPlanner.Outcome o = UpstreamPathPlanner.plan("Inh_Foo",
                        new Behavior(Stance.PATROL_CYCLE, true, "bhop", 5, 3, w));
                assertNull(o.plan(), "waypoint with " + bad + " on axis " + axis + " must never reach upstream");
                assertTrue(o.rejection().contains("#1"));
            }
        }
        assertFalse(UpstreamPathPlanner.isFinite(null));
    }

    @Test
    void tooManyWaypointsAreRefused() {
        Behavior b = behavior(Stance.PATROL_CYCLE, true, "bhop", UpstreamPathPlanner.MAX_WAYPOINTS + 1);
        assertNull(UpstreamPathPlanner.plan("Inh_Foo", b).plan());
        assertNotNull(UpstreamPathPlanner.plan("Inh_Foo",
                behavior(Stance.PATROL_CYCLE, true, "bhop", UpstreamPathPlanner.MAX_WAYPOINTS)).plan());
    }

    @Test
    void anUnusableBotNameIsRefused() {
        assertNull(UpstreamPathPlanner.plan("", behavior(Stance.GUARD_POST, true, "bhop", 1)).plan());
        assertNull(UpstreamPathPlanner.plan(null, behavior(Stance.GUARD_POST, true, "bhop", 1)).plan());
    }

    // ---------------------------------------------------------------- walk type

    @Test
    void walkTypeIsValidatedToTheThreeUpstreamValues() {
        assertEquals("bhop", UpstreamPathPlanner.walkType("bhop"));
        assertEquals("sprint", UpstreamPathPlanner.walkType("sprint"));
        assertEquals("walk", UpstreamPathPlanner.walkType("walk"));
        assertEquals("sprint", UpstreamPathPlanner.walkType("  SPRINT "));
        assertEquals("bhop", UpstreamPathPlanner.walkType("run"), "unknown falls back to upstream's default");
        assertEquals("bhop", UpstreamPathPlanner.walkType(null));
        assertEquals("bhop", UpstreamPathPlanner.walkType(""));
    }

    @Test
    void anUnknownWalkTypeIsNormalisedAndReported() {
        PatrolPlan plan = UpstreamPathPlanner.plan("Inh_Foo", behavior(Stance.GUARD_POST, true, "gallop", 1)).plan();
        assertEquals("bhop", plan.walkType());
        assertTrue(plan.notes().stream().anyMatch(n -> n.contains("gallop")));
    }

    @Test
    void aValidWalkTypeProducesNoNote() {
        PatrolPlan plan = UpstreamPathPlanner.plan("Inh_Foo", behavior(Stance.GUARD_POST, true, "WALK", 1)).plan();
        assertEquals("walk", plan.walkType());
        assertTrue(plan.notes().isEmpty());
    }

    // ---------------------------------------------------------------- the plan's own invariants

    @Test
    void thePlanRefusesToRepresentASinglePointPingPongEvenWhenBuiltDirectly() {
        assertThrows(IllegalArgumentException.class, () ->
                new PatrolPlan("inh_x", points(1), true, true, "bhop", List.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new PatrolPlan("inh_x", List.of(), false, true, "bhop", List.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new PatrolPlan("", points(2), true, true, "bhop", List.of()));
        // The safe shapes are fine.
        new PatrolPlan("inh_x", points(1), false, true, "bhop", List.of());
        new PatrolPlan("inh_x", points(2), true, true, "bhop", List.of());
    }

    @Test
    void everyAcceptedPlanSatisfiesTheNoSingleLoopInvariantForAllStancesAndCounts() {
        for (String stance : List.of(Stance.GUARD_POST, Stance.PATROL_CYCLE, Stance.PATROL_PINGPONG)) {
            for (int n = 1; n <= 8; n++) {
                PatrolPlan plan = UpstreamPathPlanner.plan("Inh_Foo", behavior(stance, true, "bhop", n)).plan();
                assertNotNull(plan, stance + " with " + n + " points");
                assertTrue(!plan.loop() || plan.points().size() >= 2, stance + " with " + n + " points");
            }
        }
    }

    @Test
    void behaviorWalkTypeConstantsMatchUpstreamSpelling() {
        assertEquals("bhop", BotProfile.WalkType.BHOP);
        assertEquals("sprint", BotProfile.WalkType.SPRINT);
        assertEquals("walk", BotProfile.WalkType.WALK);
    }
}
