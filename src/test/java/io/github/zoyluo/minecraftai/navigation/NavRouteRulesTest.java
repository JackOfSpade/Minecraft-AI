package io.github.zoyluo.minecraftai.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** How a walk request becomes a route's permissions, and how a route's state becomes the legacy result vocabulary. */
final class NavRouteRulesTest {
    @Test
    void requestFlagsBecomeRoutePermissions() {
        // ordinary walk: breaking is the legacy dig fallback, placing the legacy "can pillar"
        NavRoute.Options ordinary = NavRouteRules.optionsFor(true, true, 0, false);
        assertTrue(ordinary.allowBreak() && ordinary.allowPlace() && !ordinary.allowWater() && !ordinary.exactWaterGoal());
        // surface-only walk (startSurfacePathTo): neither
        assertEquals(NavRoute.Options.WALK_ONLY, NavRouteRules.optionsFor(false, false, 0, false));
        // a caller that keeps a stone reserve never has it spent by the planner
        assertFalse(NavRouteRules.optionsFor(true, true, 4, false).allowPlace());
        assertTrue(NavRouteRules.optionsFor(true, true, 4, false).allowBreak());
        // cannot pillar -> cannot place
        assertFalse(NavRouteRules.optionsFor(true, false, 0, false).allowPlace());
        // A bot that stands in water may cross water (the way out is the first part of the route),
        // but that ordinary request still resolves its BLOCK goal to an observed dry stance.
        NavRoute.Options waterTraversal = NavRouteRules.optionsFor(false, false, 0, true);
        assertTrue(waterTraversal.allowWater());
        assertFalse(waterTraversal.exactWaterGoal());
        assertTrue(NavRoute.Options.SWIM.allowWater() && !NavRoute.Options.SWIM.exactWaterGoal(),
                "a normal swim route may traverse water without turning its dry destination into a water goal");
        assertTrue(NavRoute.Options.EXACT_SWIM.exactWaterGoal(),
                "only the dedicated exact-water preset uses the actual water/shore cell as its goal");
    }

    @Test
    void onlyARefusalThatSaysWhatWasSeenIsAnObservationRefusal() {
        assertTrue(NavRouteRules.isObservationRefusal("navigation_goal_unobserved"));
        assertTrue(NavRouteRules.isObservationRefusal("navigation_goal_without_observed_stance"));
        assertTrue(NavRouteRules.isObservationRefusal("navigation_observed_corridor_unavailable"));
        assertTrue(NavRouteRules.isObservationRefusal("navigation_observation_fence_insufficient"));
        assertFalse(NavRouteRules.isObservationRefusal(NavRouteRules.GOAL_UNREACHABLE));
        assertFalse(NavRouteRules.isObservationRefusal(NavRouteRules.POLICY_REFUSED));
        assertFalse(NavRouteRules.isObservationRefusal(NavRouteRules.BARITONE_UNAVAILABLE));
        assertFalse(NavRouteRules.isObservationRefusal(""));
        assertFalse(NavRouteRules.isObservationRefusal(null));
    }

    @Test
    void deadlineGrowsWithDistanceAndIsBounded() {
        assertEquals(600, NavRouteRules.deadlineTicks(0.0D));
        assertEquals(600, NavRouteRules.deadlineTicks(-5.0D));
        assertEquals(1000, NavRouteRules.deadlineTicks(20.0D));
        assertEquals(6000, NavRouteRules.deadlineTicks(10_000.0D));
    }

    @Test
    void aRouteThatIsStillRunningContinuesUnlessItIsWetOrLate() {
        assertFalse(NavRouteRules.verdict(NavRoute.Progress.RUNNING, false, false, false).ended());
        assertNull(NavRouteRules.verdict(NavRoute.Progress.RUNNING, false, false, false).status());
        NavRouteRules.Verdict wet = NavRouteRules.verdict(NavRoute.Progress.RUNNING, true, false, false);
        assertEquals(NavOutcome.Status.FAILED, wet.status());
        assertEquals("route_entered_water", wet.reason());
        NavRouteRules.Verdict late = NavRouteRules.verdict(NavRoute.Progress.RUNNING, false, true, false);
        assertEquals(NavOutcome.Status.TIMEOUT, late.status());
        assertEquals("path_timeout", late.reason());
        // wet wins over late: the water is the more urgent fact
        assertEquals("route_entered_water", NavRouteRules.verdict(NavRoute.Progress.RUNNING, true, true, false).reason());
    }

    @Test
    void anEndedRouteIsSuccessOrFailureWithTheLegacyReason() {
        NavRouteRules.Verdict arrived = NavRouteRules.verdict(NavRoute.Progress.ARRIVED, false, false, false);
        assertEquals(NavOutcome.Status.SUCCESS, arrived.status());
        assertEquals("", arrived.reason());
        // arriving beats every other flag
        assertEquals(NavOutcome.Status.SUCCESS, NavRouteRules.verdict(NavRoute.Progress.ARRIVED, true, true, true).status());
        NavRouteRules.Verdict noWay = NavRouteRules.verdict(NavRoute.Progress.ENDED_SHORT, false, false, true);
        assertEquals(NavOutcome.Status.FAILED, noWay.status());
        assertEquals("pathfinding_failed: GOAL_UNREACHABLE", noWay.reason(), "the reason callers of the legacy path API already know");
        NavRouteRules.Verdict stopped = NavRouteRules.verdict(NavRoute.Progress.ENDED_SHORT, false, false, false);
        assertEquals(NavOutcome.Status.FAILED, stopped.status());
        assertEquals("path_incomplete", stopped.reason());
    }

    @Test
    void aPhysicalBoundaryRefusalKeepsItsTypedReason() {
        NavRouteRules.Verdict refusal = NavRouteRules.verdict(
                NavRoute.Progress.MOVEMENT_BOUNDARY_REFUSED, false, false, false);

        assertEquals(NavOutcome.Status.FAILED, refusal.status());
        assertEquals(NavRouteRules.MOVEMENT_BOUNDARY_REFUSED, refusal.reason());
    }

    @Test
    void outcomesAreLoggedUnderTheLegacyEventNames() {
        BlockPos goal = new BlockPos(1, 2, 3);
        assertEquals("path_complete", new NavOutcome(NavOutcome.Status.SUCCESS, "", "path_to", goal, 10).event());
        assertEquals("path_failed", new NavOutcome(NavOutcome.Status.FAILED, "x", "path_to", goal, 10).event());
        assertEquals("path_timeout", new NavOutcome(NavOutcome.Status.TIMEOUT, "x", "path_to", goal, 10).event());
        assertEquals("path_cancelled", new NavOutcome(NavOutcome.Status.CANCELLED, "x", "path_to", goal, 10).event());
        assertTrue(new NavOutcome(NavOutcome.Status.SUCCESS, "", "path_to", goal, 10).success());
        assertFalse(new NavOutcome(NavOutcome.Status.CANCELLED, "x", "path_to", goal, 10).success());
    }

    @Test
    void aRouteRemembersItsResolvedGoalAndDeadline() {
        NavRoute route = new NavRoute(NavRoute.Shape.NEAR, new BlockPos(5, 6, 7), 3, NavRoute.Options.WALK_ONLY, "approach", 100);
        assertEquals(700, route.deadlineTick());
        assertNull(route.resolvedGoal());
        route.setResolvedGoal(new BlockPos(8, 9, 10));
        route.setDeadlineTick(900);
        assertEquals(new BlockPos(8, 9, 10), route.resolvedGoal());
        assertEquals(900, route.deadlineTick());
        assertEquals(3, route.radius());
        assertEquals(100, route.startTick());
    }

    @Test
    void circularMovementBoundaryIncludesTheEdgeButRejectsDiagonalActionCornersAndLowFeet() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        NavRoute.MovementBoundary boundary = new NavRoute.MovementBoundary(anchor, 8, 62);
        NavRoute route = new NavRoute(NavRoute.Shape.BLOCK, anchor.east(7), 0,
                NavRoute.Options.WALK_ONLY, "anchored_surface", 0,
                Integer.MIN_VALUE, null, boundary);

        assertTrue(boundary.allowsFeet(anchor.east(8)), "the inclusive cardinal edge is legal");
        assertTrue(boundary.allowsFeet(anchor.offset(7, 0, -3)),
                "the in-circle short retreat leg remains available");
        assertFalse(boundary.allowsFeet(anchor.offset(8, 0, -1)),
                "the first diagonal cell beyond the circular edge is outside the site");
        assertFalse(boundary.allowsFeet(anchor.offset(0, -3, 0)),
                "the vertical feet floor remains part of the same movement boundary");
        assertTrue(boundary.allowsColumn(0, 0), "terrain support below legal feet stays in its allowed column");
        assertEquals(boundary, route.movementBoundary(), "the route retains the immutable boundary object");
        assertFalse(route.allowsMovementCell(8, 64, -1),
                "pre-execution route validation rejects the outside diagonal hop");

        // A diagonal can keep both feet endpoints in the circle while Baritone's adjacent
        // corner/head action cell lies outside it. That exact footprint must be rejected before
        // the executor's stateful per-click observation gate can create a retry loop.
        BlockPos diagonalSource = anchor.east(8);
        BlockPos diagonalDestination = anchor.offset(7, 0, 1);
        BlockPos outsideCorner = anchor.offset(8, 0, 1);
        assertTrue(route.allowsMovementCell(diagonalSource.getX(), diagonalSource.getY(), diagonalSource.getZ()));
        assertTrue(route.allowsMovementCell(
                diagonalDestination.getX(), diagonalDestination.getY(), diagonalDestination.getZ()));
        assertFalse(route.allowsMovementActionCell(outsideCorner),
                "an outside declared diagonal corner is not an admissible action cell");
        assertTrue(route.allowsMovementActionCell(anchor.east(8).below(3)),
                "support below the feet floor remains a legal action in an in-circle column");
    }
}
