package io.github.zoyluo.minecraftai.pathfinding;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.core.BlockPos;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathExecutorReplanGateTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/pathfinding/PathExecutor.java");
    private static final Path ACTION_PACK_SOURCE = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/action/ActionPack.java");

    @Test
    void unreachableExecutorCanReplanOnlyOnceUntilARealNodeAdvance() {
        PathExecutor.ReplanGate gate = new PathExecutor.ReplanGate();

        assertTrue(gate.tryAcquire(), "the first stuck event may attempt one internal replan");
        // A successful A* search only replaces the route; it does not call the reset method.
        assertFalse(gate.tryAcquire(),
                "the next timeout must escape to the caller instead of internally replanning again");

        gate.resetAfterNodeAdvance();
        assertTrue(gate.tryAcquire(), "committed node progress earns one new bounded replan");
        assertFalse(gate.tryAcquire(), "the renewed allowance is still single-use");
    }

    @Test
    void compatibilityConstructorIsFailClosedAndExplicitPolicyCannotEscalate() {
        PathExecutor compatibility = new PathExecutor(List.of(), BlockPos.ZERO);
        assertFalse(compatibility.replanCanPillar(),
                "legacy callers must not gain disposable pillars during a replan");
        assertFalse(compatibility.replanAllowDig(),
                "legacy callers must not gain block breaking during a replan");
        assertTrue(compatibility.protectedStoneLikeReserve() == 0,
                "legacy callers must retain the reserve=0 compatibility contract");

        PathExecutor ordinary = new PathExecutor(List.of(), BlockPos.ZERO, true, true);
        assertTrue(ordinary.replanCanPillar());
        assertTrue(ordinary.replanAllowDig());
        assertTrue(ordinary.protectedStoneLikeReserve() == 0);

        PathExecutor walkingOnly = new PathExecutor(List.of(), BlockPos.ZERO, false, false);
        assertFalse(walkingOnly.replanCanPillar());
        assertFalse(walkingOnly.replanAllowDig());

        PathExecutor scoped = new PathExecutor(
                List.of(), BlockPos.ZERO, true, true, 76);
        assertTrue(scoped.replanCanPillar());
        assertTrue(scoped.replanAllowDig());
        assertTrue(scoped.protectedStoneLikeReserve() == 76,
                "the executor must own the mission reserve for its entire lifetime");

        PathExecutor normalized = new PathExecutor(
                List.of(), BlockPos.ZERO, true, true, -1);
        assertTrue(normalized.protectedStoneLikeReserve() == 0,
                "negative external reserve input must normalize to the compatibility floor");

        PathExecutor.RouteContract routeContract =
                PathExecutor.RouteContract.constrainedSurface(48, new BlockPos(8, 64, 8));
        PathExecutor constrained = new PathExecutor(
                List.of(), BlockPos.ZERO, true, true, 12, routeContract);
        assertTrue(constrained.routeContract().equals(routeContract),
                "executor must retain the immutable route contract for every internal replan");
    }

    @Test
    void actionPackCarriesMovementLimitsIntoTheBaritoneRoute() throws IOException {
        String source = Files.readString(ACTION_PACK_SOURCE);
        String dig = between(source, "public ActionResult startDigPathTo", "public ActionResult startPathTo");
        String routed = between(source,
                "private ActionResult startPathTo", "public BlockPos activePathGoal");

        assertTrue(dig.contains("hasPathSupport(player, protectedStoneLikeReserve)"),
                "DIG planning must gate pillars with the caller's reserve");
        assertTrue(dig.contains("routeOnBaritone(\"dig_path_to\""),
                "DIG execution enters the Baritone-only route seam");
        assertTrue(routed.contains("routeOnBaritone(\"path_to\""),
                "ordinary and surface routes enter the same Baritone seam");
        assertTrue(source.contains(
                        "return startPathTo(goal, false, false, 0);"),
                "surface and pickup paths remain no-dig/no-pillar");
        assertTrue(source.contains(
                        "RouteConstraints.constrainedSurface(minimumY, returnAnchor)"),
                "the constrained surface overload must build an immutable route contract");
        assertTrue(source.contains("routeConstraints.minimumY(), routeConstraints.returnAnchor()"),
                "the immutable floor and return anchor are carried into NavRoute admission");
        assertFalse(routed.contains("new AStarPathfinder(") || routed.contains("pathExecutor"),
                "initial routing never installs the retired raw-world executor");
        assertTrue(source.contains("PathRequestIdentity")
                        && source.contains("identity.equals(lastPathRequest)"),
                "path cooldown identity retains the full request contract");
        assertFalse(source.contains("pathExecutor"),
                "the Baritone route seam must not retain an alternate executor field");
        assertTrue(source.contains("return startPathTo(goal, 0);"),
                "ordinary paths retain reserve=0 semantics");
        assertTrue(source.contains("return startDigPathTo(goal, 0);"),
                "DIG paths retain reserve=0 semantics");
    }

    @Test
    void executorWiresResetToAdvanceButNotSuccessfulPathSearch() throws IOException {
        String source = Files.readString(SOURCE);
        String advance = between(source, "private void advanceTo", "private int chooseWalkTargetIndex");
        String stuck = between(source, "private ActionResult handleStuck", "private void cleanup");
        String freshSuccess = between(
                stuck, "if (validation.accepted())", "reason = reason +");

        assertTrue(advance.contains("replanGate.resetAfterNodeAdvance()"));
        assertTrue(stuck.contains("if (replanGate.tryAcquire())"));
        assertTrue(stuck.contains("hasPlaceableBlock(pack.player(), protectedStoneLikeReserve)"),
                "replan pillaring must require both the original policy and current material");
        assertTrue(stuck.contains("!routeContract.constrained() && replanAllowDig"),
                "constrained replans must remain no-dig");
        assertTrue(stuck.contains("CONSTRAINED_ROUTE_MAX_NODES")
                        && stuck.contains("false, false"),
                "constrained replans must reuse the bounded no-dig/no-pillar search");
        assertTrue(stuck.contains("recenterPlayerInCurrentStandableCell")
                        && stuck.contains("finder.findPathUncachedAtOrAbove"),
                "constrained replan must neither cross-cell snap nor reuse a TTL result");
        assertTrue(stuck.contains("proveConstrainedReturnRoute")
                        && stuck.contains("validateRouteContract"),
                "replan must re-prove the stored outbound and optional return contract");
        assertTrue(stuck.indexOf("validateRouteContract")
                        < stuck.indexOf("path = fresh.path()"),
                "a replan must validate its route contract before replacing the active path");
        assertFalse(freshSuccess.contains("resetAfterNodeAdvance"),
                "finding a route must not restore the same executor's replan allowance");
        assertTrue(stuck.lastIndexOf("return ActionResult.failed(reason);")
                        > stuck.indexOf("if (replanGate.tryAcquire())"),
                "an exhausted gate must leave handleStuck through the terminal failure path");
    }

    @Test
    void controllerMovementReprovesReturnLeaseBeforeHandlingItsResult()
            throws IOException {
        String source = Files.readString(SOURCE);
        String walk = between(
                source, "private ActionResult tickWalk", "private ActionResult tickDrop");
        String digWalk = between(
                source, "private ActionResult tickDigThrough",
                "// NAV-9:");

        assertControllerProofOrdering(walk, "ActionResult result = subWalker.tick(pack);");
        assertControllerProofOrdering(digWalk, "ActionResult walk = subWalker.tick(pack);");
    }

    private static void assertControllerProofOrdering(
            String method, String controllerTick) {
        int before = method.indexOf("BlockPos beforeControllerTick");
        int tick = method.indexOf(controllerTick);
        int proof = method.indexOf("ensureRuntimeContractAfterControllerMove");
        int resultHandling = method.indexOf("if (", proof);

        assertTrue(before >= 0 && before < tick,
                "the controller's physical start cell must be captured before ticking");
        assertTrue(tick < proof && proof < resultHandling,
                "a cross-cell controller tick must re-prove the return lease before "
                        + "SUCCESS/IN_PROGRESS/FAILURE handling");
    }

    private static String between(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue(start >= 0 && end > start,
                () -> "missing source markers: " + startMarker + " -> " + endMarker);
        return source.substring(start, end);
    }
}
