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
        assertTrue(ordinary.allowBreak() && ordinary.allowPlace() && !ordinary.allowWater());
        // surface-only walk (startSurfacePathTo): neither
        assertEquals(NavRoute.Options.WALK_ONLY, NavRouteRules.optionsFor(false, false, 0, false));
        // a caller that keeps a stone reserve never has it spent by the planner
        assertFalse(NavRouteRules.optionsFor(true, true, 4, false).allowPlace());
        assertTrue(NavRouteRules.optionsFor(true, true, 4, false).allowBreak());
        // cannot pillar -> cannot place
        assertFalse(NavRouteRules.optionsFor(true, false, 0, false).allowPlace());
        // a bot that stands in water may cross water (the way out is the first part of the route)
        assertTrue(NavRouteRules.optionsFor(false, false, 0, true).allowWater());
        assertEquals(NavRoute.Options.SWIM, NavRouteRules.optionsFor(false, false, 0, true));
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
}
