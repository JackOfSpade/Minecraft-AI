package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks every Hunt movement segment to its runtime surface/return contract.
 *
 * <p>These assertions read {@code HuntTask.java} (and, for the shared stateless route-proving
 * primitives extracted out of it, {@code HuntSurfaceRoutes.java}) as text on purpose: loading the
 * class would initialise its {@code EntityType}/{@code Item} constants, which needs a
 * bootstrapped Minecraft registry that plain unit tests do not have.</p>
 */
class HuntConstrainedRouteSourceContractTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/task/HuntTask.java");
    private static final Path ROUTES = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/task/HuntSurfaceRoutes.java");

    @Test
    void exactSurfaceStarterAlwaysThreadsRuntimeContractIntoActionPack()
            throws IOException {
        // combat-hunttask-surface-route-cluster: this starter (and the proof it delegates to)
        // now live in HuntSurfaceRoutes, the package-private helper HuntTask calls into.
        String routes = Files.readString(ROUTES);
        String starter = from(
                routes, "static HuntTask.SurfacePathStart startExactSurfacePath");

        assertFalse(starter.contains("startSurfacePathTo(destination);"),
                "Hunt must not start an unrestricted one-argument surface path");
        assertTrue(starter.contains(
                        "startSurfacePathTo(destination, minimumY)"),
                "return-to-surface must retain its runtime minimumY");
        assertTrue(starter.contains(
                        "destination, minimumY, returnAnchor"),
                "reversible segments must install their exact return anchor");
    }

    @Test
    void approachAndRoamBindEachSegmentOriginAsReturnAnchor()
            throws IOException {
        String source = Files.readString(SOURCE);
        String approach = between(
                source,
                "private SurfacePathStart startSafePreyApproach",
                "private boolean isSafePreyPose");
        String roam = between(
                source,
                "private RoamResult roamForPrey",
                "// Keep scanning for prey while roaming");

        assertTrue(approach.contains(
                        "BlockPos returnAnchor = bot.blockPosition().immutable();"));
        assertTrue(approach.contains(
                        "digBreakthroughFloor(bot.blockPosition(), attackPose, surfaceFloorY(bot))"),
                "approach execution must bind the near-level dig floor to its own segment");
        assertTrue(approach.contains("returnAnchor, true);"),
                "moving-prey replans must own the start of each new approach segment");
        assertTrue(roam.contains(
                        "bot, ground, surfaceFloorY(bot), feet"),
                "ROAM execution must retain the origin used by its round-trip proof");
    }

    @Test
    void pickupPathsUseTransactionAnchorWithoutHarvestCorePathStarts()
            throws IOException {
        String source = Files.readString(SOURCE);
        String pickup = between(
                source,
                "private void pickup(AIPlayerEntity bot)",
                "private void finishPickupTransaction");
        String pickupRouting = between(
                source,
                "private BlockPos safeObservedDropStand",
                "// Only hunts adult vanilla animals known to drop raw meat");
        String sweep = between(
                source,
                "private boolean startNextPickupSweepStep",
                "private static long pickedUpAuxiliary");

        assertFalse(pickup.contains("HarvestCore.approachDropPhysically"),
                "visible drops must not start an unrestricted HarvestCore path");
        assertFalse(pickup.contains("HarvestCore.approachKnownPickupCell"),
                "known kill cells must not start an unrestricted HarvestCore path");
        assertTrue(pickup.contains("approachPickupStand("));
        assertTrue(pickup.contains("approachKnownPickupCell("));
        assertTrue(pickupRouting.contains(
                        "bot, stand, surfaceFloorY(bot), pickupReturnAnchor"),
                "every pickup path must retain the transaction return anchor");
        assertTrue(pickupRouting.contains(
                        "InCellWalk.nudgeToward("),
                "same-cell physical pickup may retain the bounded nudge, walked with the movement keys");
        assertFalse(pickupRouting.contains("FakePlayerMotion"),
                "the pickup nudge is a walked step, never a teleport primitive");
        assertTrue(sweep.contains(
                        "bot, candidate, surfaceFloorY(bot), pickupReturnAnchor"),
                "pickup observation sweeps must retain the transaction anchor");
    }

    @Test
    void returnToSurfaceUsesFloorWithoutManufacturingReturnDebt()
            throws IOException {
        String source = Files.readString(SOURCE);
        String surfaceReturn = between(
                source,
                "private void beginSurfaceReturn",
                "private static String dimension");

        assertTrue(surfaceReturn.contains(
                        "bot, destination, returnFloor, null"),
                "RETURN_SURFACE needs minimumY but no reverse-route contract");
    }

    @Test
    void exactSurfaceProofUsesTheSameObservedAdmissionAsTheExecutedBaritoneRoute() throws IOException {
        String routes = Files.readString(ROUTES);
        String proof = between(
                routes,
                "private static HuntTask.SurfaceRouteProof proveExactSurfaceRoute",
                "static HuntTask.SurfacePathStart startExactSurfacePath");

        assertTrue(proof.contains("proveSurfaceRouteContract(bot, destination, minimumY, null, false)"),
                "surface proof must defer to the observed route contract");
        assertTrue(routes.contains("ObservedNavigationFence.admit("),
                "hunt previews must use the immutable observed-terrain admission boundary");
        assertFalse(routes.contains("new AStarPathfinder("),
                "hunt must not query raw terrain with the retired A* planner before Baritone starts");
        assertFalse(routes.contains("PathExecutor.isExactConstrainedRoute("),
                "hunt must not retain the legacy raw-world route proof");
    }

    private static String between(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue(start >= 0 && end > start,
                () -> "missing source markers: " + startMarker + " -> " + endMarker);
        return source.substring(start, end);
    }

    private static String from(String source, String startMarker) {
        int start = source.indexOf(startMarker);
        assertTrue(start >= 0, () -> "missing source marker: " + startMarker);
        return source.substring(start);
    }
}
