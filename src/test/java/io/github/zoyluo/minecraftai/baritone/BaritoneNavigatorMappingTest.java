package io.github.zoyluo.minecraftai.baritone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import baritone.api.utils.PathCalculationResult;
import io.github.zoyluo.minecraftai.navigation.NavRoute;
import org.junit.jupiter.api.Test;

/** The pure mappings of the Baritone navigator: request permissions to the per-bot policy, search result to admission answer. */
final class BaritoneNavigatorMappingTest {
    @Test
    void routePermissionsMapOntoTheExistingPolicyPresets() {
        assertEquals(BaritonePolicy.UNRESTRICTED, BaritoneNavigator.policyOf(new NavRoute.Options(true, true, false)));
        assertEquals(BaritonePolicy.NO_PLACING, BaritoneNavigator.policyOf(new NavRoute.Options(true, false, false)));
        assertEquals(BaritonePolicy.NO_BREAKING, BaritoneNavigator.policyOf(new NavRoute.Options(false, true, false)));
        assertEquals(BaritonePolicy.WALK_ONLY, BaritoneNavigator.policyOf(NavRoute.Options.WALK_ONLY));
        assertEquals(BaritonePolicy.WALK_ONLY, BaritoneNavigator.policyOf(NavRoute.Options.SWIM), "swimming is not breaking or placing");
    }

    @Test
    void aPartialPathThatEndedEarlyTowardsALoadedGoalIsUnreachable() {
        PathCalculationResult.Type partial = PathCalculationResult.Type.SUCCESS_SEGMENT;
        assertEquals(true, BaritoneNavigator.exhaustedPartial(partial, 12L, 40L, true), "ran out of places to look long before the budget");
        assertEquals(false, BaritoneNavigator.exhaustedPartial(partial, 40L, 40L, true), "cut off by the budget: keep going");
        assertEquals(false, BaritoneNavigator.exhaustedPartial(partial, 36L, 40L, true), "within the timer slack: keep going");
        assertEquals(false, BaritoneNavigator.exhaustedPartial(partial, 5L, 40L, false), "the goal is in an unloaded column: a partial path is all there can be");
        assertEquals(false, BaritoneNavigator.exhaustedPartial(PathCalculationResult.Type.SUCCESS_TO_GOAL, 5L, 40L, true));
        assertEquals(false, BaritoneNavigator.exhaustedPartial(PathCalculationResult.Type.FAILURE, 5L, 40L, true), "failures are refused by their own rule");
    }

    @Test
    void theInlineSearchResultBecomesTheLegacyAnswer() {
        assertNull(BaritoneNavigator.admissionFailure(PathCalculationResult.Type.SUCCESS_TO_GOAL, 3L, 100L));
        assertNull(BaritoneNavigator.admissionFailure(PathCalculationResult.Type.SUCCESS_SEGMENT, 3L, 100L),
                "a partial path gets the bot moving: Baritone's answer to the legacy straight-line fallback");
        assertEquals("pathfinding_failed: GOAL_UNREACHABLE", BaritoneNavigator.admissionFailure(PathCalculationResult.Type.FAILURE, 12L, 100L));
        assertNull(BaritoneNavigator.admissionFailure(PathCalculationResult.Type.FAILURE, 100L, 100L),
                "a search that used its whole budget for nothing proves nothing (cold start, busy server): the async search decides");
        assertEquals("pathfinding_failed: GOAL_UNREACHABLE", BaritoneNavigator.admissionFailure(PathCalculationResult.Type.CANCELLATION, 1L, 100L));
        assertEquals("pathfinding_failed: baritone_exception", BaritoneNavigator.admissionFailure(PathCalculationResult.Type.EXCEPTION, 1L, 100L));
    }
}
