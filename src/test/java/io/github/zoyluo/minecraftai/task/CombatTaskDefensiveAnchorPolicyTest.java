package io.github.zoyluo.minecraftai.task;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit boundary checks for the legacy anchored defensive-combat compatibility overload. */
class CombatTaskDefensiveAnchorPolicyTest {
    @Test
    void legacyAnchorAllowsLocalDefenseButNeverADownwardOrOpenEndedChase() {
        BlockPos anchor = new BlockPos(0, 64, 0);

        assertTrue(CombatTask.isWithinDefensiveLeash(anchor, anchor));
        assertTrue(CombatTask.isWithinDefensiveLeash(anchor, anchor.offset(8, -2, 0)),
                "the inclusive local edge remains available for a safe defense");
        assertFalse(CombatTask.isWithinDefensiveLeash(anchor, anchor.below(3)),
                "the anchored form must not descend beyond its protected floor");
        assertFalse(CombatTask.isWithinDefensiveLeash(anchor, anchor.east(9)),
                "the anchored form must not turn a work-site defense into an open-ended pursuit");
    }

    @Test
    void anchoredRetreatOnlyAdmitsEndpointsInsideTheProtectedWorkSite() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        BlockPos inLeashCandidate = anchor.east(7);

        assertTrue(CombatTask.isDefensiveRetreatEndpointAllowed(anchor, anchor.offset(8, -2, 0)),
                "an inclusive in-leash escape endpoint remains available");
        assertFalse(CombatTask.isDefensiveRetreatEndpointAllowed(anchor, anchor.east(9)),
                "a full retreat fan must not admit an endpoint outside the horizontal leash");
        assertFalse(CombatTask.isDefensiveRetreatEndpointAllowed(anchor, anchor.below(3)),
                "a normalized route endpoint must not admit a descent below the anchor floor");
        assertTrue(CombatTask.isDefensiveRetreatEndpointAllowed(null, anchor.east(40)),
                "ordinary unanchored defensive combat retains its existing retreat behaviour");
        assertEquals(inLeashCandidate, EvadeTask.admittedEscapeEndpoint(
                        inLeashCandidate, null,
                        endpoint -> CombatTask.isDefensiveRetreatEndpointAllowed(anchor, endpoint)),
                "an unresolved route retains its verified candidate endpoint");
        assertNull(EvadeTask.admittedEscapeEndpoint(
                        inLeashCandidate, anchor.east(9),
                        endpoint -> CombatTask.isDefensiveRetreatEndpointAllowed(anchor, endpoint)),
                "a route that normalizes outside the leash must be cancelled before admission");
        assertNull(EvadeTask.admittedEscapeEndpoint(
                        anchor.east(9), inLeashCandidate,
                        endpoint -> CombatTask.isDefensiveRetreatEndpointAllowed(anchor, endpoint)),
                "an out-of-leash requested candidate must not start a route even if it could resolve inward");
    }

    @Test
    void anchoredCombatUsesOneFeetBoundaryForRoutesAndRawLocalCandidates() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        var boundary = CombatTask.defensiveMovementBoundary(anchor);

        assertTrue(boundary.allowsFeet(anchor.east(8)),
                "the retained cardinal edge must remain usable by a local route or strafe");
        assertTrue(boundary.allowsFeet(anchor.offset(7, 0, -3)),
                "the in-leash short retreat/candidate cell remains usable");
        assertFalse(boundary.allowsFeet(anchor.offset(8, 0, -1)),
                "an outward diagonal raw-step or Baritone hop is denied by the same boundary");
        assertFalse(boundary.allowsFeet(anchor.below(3)),
                "the raw-input boundary also applies the legacy feet floor");
        assertTrue(boundary.allowsColumn(anchor.getX() + 8, anchor.getZ()),
                "the support/body column at an allowed edge feet cell stays available to planning");
    }
}
