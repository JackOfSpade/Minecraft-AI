package io.github.zoyluo.minecraftai.goal;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F5: Reordering contract for replanning an open obsidian transaction. Missing-resource
 * failures (need_better_tool / bucket-lost / missing-water) must place the fresh plan's
 * supply prefix physically before the resume step; all other failure reasons keep the
 * resume-first physical continuation order.
 */
class GoalExecutorObsidianResumeReconcileTest {

    private static List<GoalStep> freshPlanShape() {
        return new ArrayList<>(List.of(
                GoalStep.hunt(2),
                GoalStep.cookFood(2),
                GoalStep.acquireWater(),
                GoalStep.makeObsidian(24),
                GoalStep.placeStations(),
                GoalStep.makeObsidian(8)));
    }

    @Test
    void missingResourceFailureKeepsSupplyPrefixBeforeResume() {
        List<GoalStep> steps = freshPlanShape();

        int resumeIndex = GoalExecutor.reconcileObsidianSteps(steps, 32, true, true);

        assertEquals(3, resumeIndex);
        assertEquals(List.of(
                GoalStep.hunt(2),
                GoalStep.cookFood(2),
                GoalStep.acquireWater(),
                GoalStep.makeObsidian(32),
                GoalStep.placeStations()), steps);
    }

    @Test
    void physicalContinuationFailureKeepsResumeFirstOrder() {
        List<GoalStep> steps = freshPlanShape();

        int resumeIndex = GoalExecutor.reconcileObsidianSteps(steps, 32, true, false);

        assertEquals(0, resumeIndex);
        assertEquals(List.of(
                GoalStep.makeObsidian(32),
                GoalStep.hunt(2),
                GoalStep.cookFood(2),
                GoalStep.acquireWater(),
                GoalStep.placeStations()), steps);
    }

    @Test
    void missingResourceWithoutAttestedSupplyPrefixResumesAtHead() {
        // No MAKE_OBSIDIAN in the fresh plan -> no attestable supply prefix, so keep resume-first.
        List<GoalStep> withoutMake = new ArrayList<>(List.of(
                GoalStep.hunt(2), GoalStep.cookFood(2)));
        assertEquals(0, GoalExecutor.reconcileObsidianSteps(withoutMake, 32, true, true));
        assertEquals(List.of(
                GoalStep.makeObsidian(32),
                GoalStep.hunt(2),
                GoalStep.cookFood(2)), withoutMake);

        // MAKE is already at the head of the queue -> prefix is empty, resume still leads.
        List<GoalStep> makeFirst = new ArrayList<>(List.of(
                GoalStep.makeObsidian(24), GoalStep.placeStations()));
        assertEquals(0, GoalExecutor.reconcileObsidianSteps(makeFirst, 32, true, true));
        assertEquals(List.of(
                GoalStep.makeObsidian(32), GoalStep.placeStations()), makeFirst);

        // When replan fails, fresh steps are empty -> only the resume step remains (consistent with existing behavior).
        List<GoalStep> empty = new ArrayList<>();
        assertEquals(0, GoalExecutor.reconcileObsidianSteps(empty, 32, true, true));
        assertEquals(List.of(GoalStep.makeObsidian(32)), empty);
    }

    @Test
    void closedTransactionReconcileKeepsInPlaceReplacement() {
        List<GoalStep> steps = freshPlanShape();

        GoalExecutor.reconcileObsidianSteps(steps, 32, false, false);

        assertEquals(List.of(
                GoalStep.hunt(2),
                GoalStep.cookFood(2),
                GoalStep.acquireWater(),
                GoalStep.makeObsidian(32),
                GoalStep.placeStations()), steps);
    }

    @Test
    void missingResourceReasonScopeIsExact() {
        assertTrue(GoalExecutor.isObsidianMissingResourceFailure(
                "need_better_tool:minecraft:diamond_pickaxe"));
        assertTrue(GoalExecutor.isObsidianMissingResourceFailure(
                "create_obsidian_bucket_lost_after_pour"));
        assertTrue(GoalExecutor.isObsidianMissingResourceFailure(
                "create_obsidian_pickup_protection_missing_water"));

        assertFalse(GoalExecutor.isObsidianMissingResourceFailure(null));
        assertFalse(GoalExecutor.isObsidianMissingResourceFailure(""));
        assertFalse(GoalExecutor.isObsidianMissingResourceFailure(
                "create_obsidian_timeout"));
        assertFalse(GoalExecutor.isObsidianMissingResourceFailure(
                "create_obsidian_no_progress collected=3 phase=SEARCH"));
        assertFalse(GoalExecutor.isObsidianMissingResourceFailure(
                "create_obsidian_search_enclosed"));
        assertFalse(GoalExecutor.isObsidianMissingResourceFailure("stuck:blocked"));
    }
}
