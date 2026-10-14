package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Source contracts for the persisted-anchor promise: every voluntary combat movement shares one boundary. */
final class AnchoredCombatMovementBoundaryContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    @Test
    void anchoredCombatThreadsOneBoundaryThroughRoutesStrafesAndCoverSteps() throws IOException {
        String combat = read("task/CombatTask.java");
        String core = read("task/CombatCore.java");

        assertTrue(combat.contains("private final NavRoute.MovementBoundary defensiveBoundary;"));
        assertTrue(combat.contains("startSurfacePathWithinBoundary(\n                    target.blockPosition(), defensiveBoundary)"),
                "anchored APPROACH must not fall through to CombatCore's unrestricted coordinate walk");
        assertTrue(occurrences(combat,
                "startSurfacePathWithinBoundary(candidate, defensiveBoundary)") == 2,
                "both full and short anchored retreat fans must start boundary-carrying routes");
        assertTrue(combat.contains("CombatCore.safeStrafeInput(\n                bot, elapsed % 40 < 20 ? 0.45F : -0.45F, defensiveBoundary)"),
                "REPOSITION must pass the same boundary to its raw strafe input");
        assertTrue(combat.contains("beginStepByInput(peekHideSpot, false, false, defensiveBoundary)")
                        && combat.contains("beginStepByInput(peekExposeSpot, true, false, defensiveBoundary)")
                        && combat.contains("beginStepByInput(peekHideSpot, true, false, defensiveBoundary)"),
                "every COVER_HIDE/COVER_PEEK input step must retain the same boundary");
        assertTrue(combat.contains("!allowsDefensiveFeet(hideSpot) || !allowsDefensiveFeet(coverFeet)")
                        && combat.contains("!allowsDefensiveFeet(exposeSpot)"),
                "cover and exposure candidates must be rejected before a local raw step is created");
        int retreat = combat.indexOf("private void beginRetreat(AIPlayerEntity bot)");
        int stop = combat.indexOf("bot.getActionPack().stopNavigation();", retreat);
        assertTrue(retreat >= 0 && combat.indexOf("cancelPeekStep(bot);", retreat) > retreat && stop > retreat,
                "an abrupt RETREAT must release an active cover input step immediately");

        assertTrue(core.contains("safeStrafeInput(AIPlayerEntity bot, float desired,\n                                        NavRoute.MovementBoundary movementBoundary)")
                        && core.contains("beginStepByInput(BlockPos cell, boolean keepAim, boolean sprint,\n                                             NavRoute.MovementBoundary movementBoundary)")
                        && core.contains("!allowsMovementFeet(step.movementBoundary, here)"),
                "the local movement primitives must recheck both current and candidate feet positions");
    }

    @Test
    void routeConstraintsReachPlannerCellGateAndPreExecutionChecks() throws IOException {
        String actionPack = read("action/ActionPack.java");
        String route = read("navigation/NavRoute.java");
        String context = read("baritone/ServerPlayerContext.java");
        String navigator = read("baritone/BaritoneNavigator.java");

        assertTrue(actionPack.contains("startSurfacePathWithinBoundary(")
                        && actionPack.contains("RouteConstraints(int minimumY, BlockPos returnAnchor, NavRoute.MovementBoundary movementBoundary)")
                        && actionPack.contains("routeConstraints.movementBoundary()"),
                "the bounded ActionPack API must carry its constraint into the NavRoute request");
        assertTrue(route.contains("record MovementBoundary(BlockPos anchor, int horizontalRadius, int minimumFeetY)")
                        && route.contains("boolean allowsMovementCell(int x, int y, int z)")
                        && route.contains("boolean allowsMovementActionCell(BlockPos position)"),
                "NavRoute owns the immutable circular/feet definition used at execution");
        assertTrue(context.contains("NavigationBoundary(ObservedNavigationFence fence, NavRoute.MovementBoundary movementBoundary,")
                        && occurrences(context, "if (!boundary.allowsColumn(x, z))") >= 2
                        && context.contains("boundary.ownerFollowSnapshot(player())")
                        && context.contains("!boundary.allowsFeet(movement.getSrc())")
                        && context.contains("allowMovementBoundaryFootprints(path, firstMovement)")
                        && context.contains("movement.getDeclaredMovementCells()")
                        && context.contains("movement.getDeclaredActionCells()"),
                "both ServerPlayerContext terrain lookup orders and path footprints must enforce one atomically published boundary");
        assertTrue(route.contains("MOVEMENT_BOUNDARY_REFUSED")
                        && navigator.contains("NavRouteRules.MOVEMENT_BOUNDARY_REFUSED")
                        && navigator.contains("bot.blockPosition().getX()")
                        && navigator.contains("movement.getSrc().getX()")
                        && navigator.contains("movement.getDest().getX()")
                        && navigator.contains("movement.getDeclaredMovementCells()")
                        && navigator.contains("movement.getDeclaredActionCells()")
                        && navigator.contains("route.allowsMovementActionCell(actionCell)"),
                "inline admission and each pre-execution replan must reject an outside body, hop, or action corner");
    }

    @Test
    void vendorFootprintsAndThePhysicalSweepKeepStaticAndDynamicEscapesSeparate() throws IOException {
        String patch = Files.readString(Path.of("tools/baritone/patches/0022-declared-movement-footprints.patch"));
        String traversePatch = Files.readString(Path.of("tools/baritone/patches/0023-bounded-traverse-overshoot.patch"));
        String context = read("baritone/ServerPlayerContext.java");
        String driver = read("baritone/BaritoneDriver.java");
        String entity = read("entity/AIPlayerEntity.java");
        String registry = read("baritone/BaritoneRegistry.java");
        String sweep = read("navigation/MovementBoundarySweep.java");
        String diagonal = Files.readString(Path.of("third_party/baritone/src/main/java/baritone/pathing/movement/movements/MovementDiagonal.java"));

        assertTrue(patch.contains("getDeclaredActionCells()")
                        && patch.contains("getDeclaredMovementCells()")
                        && patch.contains("MovementAscend.java")
                        && patch.contains("MovementDescend.java")
                        && patch.contains("MovementParkour.java")
                        && patch.contains("MovementTraverse.java")
                        && patch.contains("navigationDiagonalFootprintAllowed")
                        && patch.contains("context.bsi.isNavigationCellAllowed(x, y, destZ)")
                        && patch.contains("copyDeclaredActionCells(BetterBlockPos[] toBreak, BetterBlockPos toPlace)")
                        && patch.contains("Collections.addAll(cells, toBreak)"),
                "the replayable vendor patch must expose both footprint channels, exceptional body cells, and diagonal planning corners");
        assertTrue(traversePatch.contains("navigationOvershootAllowed")
                        && traversePatch.contains("context.bsi.isNavigationCellAllowed(firstX, to.getY(), firstZ)")
                        && traversePatch.contains("context.bsi.isNavigationCellAllowed(secondX, to.getY(), secondZ)")
                        && traversePatch.contains("if (overshootAllowed &&"),
                "a bounded traverse must capture both overshoot cells before it can retain them as success positions");
        assertTrue(diagonal.contains("super(baritone, start, end, new BetterBlockPos[]{dir1, dir1.above(), dir2, dir2.above(), end, end.above()});"),
                "the diagonal movement must pass both orthogonal corner/head cells through Movement's declared-action snapshot");
        assertTrue(context.contains("dynamic support or an in-wall block")
                        && context.contains("allowNavigationActionCell(BlockPos pos)"),
                "dynamic fall/pillar/traverse ray targets must remain guarded by the final click callback");
        assertTrue(sweep.contains("Entity.collideBoundingBox(entity, requestedDelta, entity.getBoundingBox(), entity.level(),")
                        && sweep.contains("boolean hasCollisionSupport(Entity entity)")
                        && entity.contains("MovementBoundarySweep.collisionResolvedDelta(this, requestedDelta)")
                        && entity.contains("MovementBoundarySweep.collisionAwareDelta(requestedDelta, collisionResolved)")
                        && entity.contains("MovementBoundarySweep.clip(boundary, this.position(), boundaryDelta)")
                        && entity.contains("noteMovementBoundaryPhysicsRefusal(this)")
                        && entity.contains("clearMovementBoundaryInputs(this)"),
                "actual entity movement must be clipped and latched instead of revoking during Entity.move");
        assertTrue(driver.contains("consumeMovementBoundaryPhysicsFailure(bot)")
                        && driver.contains("revokeObservation(bot, physicalBoundaryFailure, false)")
                        && registry.contains("retainMovementBoundaryForReleasedPhysics")
                        && registry.contains("movementBoundaryForPhysics(AIPlayerEntity bot)")
                        && registry.contains("!MovementBoundarySweep.hasCollisionSupport(bot)"),
                "post-physics teardown and release inertia must retain the physical backstop");
    }

    private static int occurrences(String source, String token) {
        int count = 0;
        int at = 0;
        while ((at = source.indexOf(token, at)) >= 0) {
            count++;
            at += token.length();
        }
        return count;
    }

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }
}
