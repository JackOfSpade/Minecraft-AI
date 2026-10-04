package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.baritone.BaritoneRegistry;
import io.github.zoyluo.minecraftai.baritone.ObservedNavigationFence;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.navigation.NavRoute;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import net.minecraft.core.BlockPos;

/**
 * Stateless observed-route proving/pathing primitives shared by {@link HuntTask}'s roam,
 * prey-approach, and pickup phases.
 *
 * <p>These helpers deliberately do not invoke the old raw-world A* planner. A hunt target can
 * be visible without the terrain between it and the bot being known; the only valid preview is
 * therefore the same immutable observed-terrain admission used by the Baritone route that will
 * actually move the bot. A preview is non-mutating: it captures evidence to decide whether a
 * candidate is worth attempting, then {@code ActionPack} repeats and publishes that admission at
 * route start.</p>
 */
final class HuntSurfaceRoutes {
    private static final int ROAM_RETRY_ROTATION_DEGREES = 11;

    private HuntSurfaceRoutes() {
    }

    /**
     * Produces a new deterministic surface-sampling fan after every fully rejected roam attempt.
     * Serial zero is byte-for-byte the old cardinal/diagonal geometry. Later attempts rotate that
     * fan through the 45-degree symmetry sector, so a narrow ridge between the compass axes can be
     * discovered without randomness, teleporting, digging, or accepting a one-way drop.
     */
    static BlockPos rotatedRoamColumn(BlockPos origin, int dx, int dz, int distance, int attemptSerial) {
        double baseAngle = Math.atan2(dz, dx);
        int rotation = Math.floorMod(attemptSerial * ROAM_RETRY_ROTATION_DEGREES, 45);
        double angle = baseAngle + Math.toRadians(rotation);
        double radius = distance * Math.sqrt((double) dx * dx + (double) dz * dz);
        int x = origin.getX() + (int) Math.round(Math.cos(angle) * radius);
        int z = origin.getZ() + (int) Math.round(Math.sin(angle) * radius);
        return new BlockPos(x, origin.getY(), z);
    }

    static boolean hasRoundTripSurfaceRoute(
            AIPlayerEntity bot, BlockPos destination, int minimumY) {
        return proveRoundTripSurfaceRoute(bot, destination, minimumY)
                == HuntTask.SurfaceRouteProof.SAFE;
    }

    static boolean hasExactSurfaceRoute(
            AIPlayerEntity bot, BlockPos destination, int minimumY) {
        return proveExactSurfaceRoute(bot, destination, minimumY)
                == HuntTask.SurfaceRouteProof.SAFE;
    }

    static HuntTask.SurfaceRouteProof proveRoundTripSurfaceRoute(
            AIPlayerEntity bot, BlockPos destination, int minimumY) {
        return proveSurfaceRouteContract(bot, destination, minimumY,
                bot == null ? null : bot.blockPosition(), false);
    }

    /** Proves an observed walk-only outbound route and, when supplied, a walk-only return leg. */
    static HuntTask.SurfaceRouteProof proveSurfaceRoute(
            AIPlayerEntity bot, BlockPos destination, int minimumY, BlockPos returnAnchor) {
        return proveSurfaceRouteContract(bot, destination, minimumY, returnAnchor, false);
    }

    /**
     * Prey approaches may break a visibly observed obstacle, but may not place or discover a
     * return route through unseen terrain. The return leg remains a walk-only observed corridor.
     */
    static HuntTask.SurfaceRouteProof provePreyApproachRoute(
            AIPlayerEntity bot, BlockPos destination, int minimumY, BlockPos returnAnchor) {
        return proveSurfaceRouteContract(bot, destination, minimumY, returnAnchor, true);
    }

    /** Near-level breakthrough floor: at most one block under the lower endpoint. */
    static int digBreakthroughFloor(BlockPos origin, BlockPos destination, int minimumY) {
        return Math.max(minimumY,
                Math.min(origin.getY(), destination.getY()) - 1);
    }

    /**
     * Returns a factual nearby surface stance in the direction of a distant visible prey pose.
     *
     * <p>Prey sight deliberately reaches farther than ordinary terrain admission.  A route may
     * therefore only advance to the next stance whose feet, head, and support the bot can prove
     * at the normal navigation radius.  Repeating these bounded legs lets the moving bot earn a
     * fresh view of the final attack pose without treating the intervening terrain as known.</p>
     */
    static BlockPos nextObservedSurfaceLeg(
            AIPlayerEntity bot, BlockPos destination, int minimumY) {
        if (bot == null || destination == null) {
            return null;
        }
        BlockPos current = bot.blockPosition();
        int perception = Math.max(1, MinecraftAiConfig.get().perception().radius());
        if (ObservableWorldQuery.canObserveCell(bot, destination)
                && ObservableWorldQuery.canObserveCell(bot, destination.above())
                && ObservableWorldQuery.canObserveCollider(bot, destination.below())) {
            return destination;
        }
        double dx = destination.getX() - current.getX();
        double dz = destination.getZ() - current.getZ();
        // Prefer a full, ordinary-radius leg.  Starting a fresh Baritone route for every
        // adjacent cell exhausts the path-start cooldown before an open-ground hunt reaches its
        // prey.  The short adjacent step remains below as the fallback for a steep, exposed
        // stair whose interpolated landing cannot yet be observed.
        double horizontal = Math.hypot(dx, dz);
        if (horizontal >= 1.0E-9D) {
            double legLength = Math.min(Math.max(1, perception - 3), horizontal);
            double scale = legLength / horizontal;
            int x = current.getX() + (int) Math.round(dx * scale);
            int z = current.getZ() + (int) Math.round(dz * scale);
            int centreY = current.getY() + (int) Math.round(
                    (destination.getY() - current.getY()) * scale);
            // A candidate at the current height can be a slope.  Search a modest,
            // individually observed vertical slice and only then ask Standability about the
            // earned cells. If the direct line ends at a newly occluding wall, inspect only a
            // small perpendicular fan; each detour stance still has to be visible. The caller
            // proves a reversible route before walking it, so stepping sideways around a wall
            // cannot become a blind one-way escape.
            double sideX = -dz / horizontal;
            double sideZ = dx / horizontal;
            for (int lateral : new int[]{0, 1, -1, 2, -2, 3, -3}) {
                int candidateX = x + (int) Math.round(sideX * lateral);
                int candidateZ = z + (int) Math.round(sideZ * lateral);
                for (int yOffset = 0; yOffset <= 6; yOffset++) {
                    for (int signed : yOffset == 0 ? new int[]{0} : new int[]{-yOffset, yOffset}) {
                        BlockPos candidate = new BlockPos(candidateX, centreY + signed, candidateZ);
                        if (candidate.equals(current)
                                || candidate.getY() < minimumY
                                || !ObservableWorldQuery.canObserveCell(bot, candidate)
                                || !ObservableWorldQuery.canObserveCell(bot, candidate.above())
                                || !ObservableWorldQuery.canObserveCollider(bot, candidate.below())) {
                            continue;
                        }
                        Standability.clearCache();
                        if (Standability.isStandable(bot.level(), candidate)) {
                            return candidate.immutable();
                        }
                    }
                }
            }
        }
        int stepX = Integer.signum(destination.getX() - current.getX());
        int stepZ = Integer.signum(destination.getZ() - current.getZ());
        int stepY = Integer.signum(destination.getY() - current.getY());
        for (int localY : new int[]{stepY, 0, -stepY}) {
            BlockPos adjacent = current.offset(stepX, localY, stepZ);
            if (adjacent.equals(current)
                    || adjacent.getY() < minimumY
                    || !ObservableWorldQuery.canObserveCell(bot, adjacent)
                    || !ObservableWorldQuery.canObserveCell(bot, adjacent.above())
                    || !ObservableWorldQuery.canObserveCollider(bot, adjacent.below())) {
                continue;
            }
            Standability.clearCache();
            if (Standability.isStandable(bot.level(), adjacent)) {
                return adjacent.immutable();
            }
        }
        return null;
    }

    private static HuntTask.SurfaceRouteProof proveExactSurfaceRoute(
            AIPlayerEntity bot, BlockPos destination, int minimumY) {
        return proveSurfaceRouteContract(bot, destination, minimumY, null, false);
    }

    private static HuntTask.SurfaceRouteProof proveSurfaceRouteContract(
            AIPlayerEntity bot, BlockPos destination, int minimumY,
            BlockPos returnAnchor, boolean allowBreak) {
        if (bot == null || destination == null || destination.getY() < minimumY) {
            return HuntTask.SurfaceRouteProof.UNREACHABLE;
        }
        NavRoute request = new NavRoute(
                NavRoute.Shape.BLOCK,
                destination,
                0,
                new NavRoute.Options(allowBreak, false, false),
                allowBreak ? "hunt_observed_dig_preview" : "hunt_observed_surface_preview",
                bot.getServer().getTickCount(),
                minimumY,
                returnAnchor);
        BaritoneRegistry registry = BaritoneRegistry.INSTANCE;
        ObservedNavigationFence.Capture capture = ObservedNavigationFence.admit(
                bot,
                request,
                registry.observationMemory(bot),
                registry.observationFence(bot).generation() + 1L);
        if (capture.accepted()) {
            return HuntTask.SurfaceRouteProof.SAFE;
        }
        return retryableObservationFailure(capture.failure())
                ? HuntTask.SurfaceRouteProof.RETRY
                : HuntTask.SurfaceRouteProof.UNREACHABLE;
    }

    private static boolean retryableObservationFailure(String failure) {
        return "navigation_goal_unobserved".equals(failure)
                || "navigation_observed_corridor_unavailable".equals(failure)
                || "navigation_observation_fence_insufficient".equals(failure);
    }

    static HuntTask.SurfacePathStart startExactSurfacePath(
            AIPlayerEntity bot, BlockPos destination, int minimumY,
            BlockPos returnAnchor) {
        return startExactSurfacePath(bot, destination, minimumY, returnAnchor, false);
    }

    /** Prey-approach variant: the executed outbound leg may break only snapshot-observed cells. */
    static HuntTask.SurfacePathStart startExactSurfacePath(
            AIPlayerEntity bot, BlockPos destination, int minimumY,
            BlockPos returnAnchor, boolean digFallbackOutbound) {
        HuntTask.SurfaceRouteProof proof = digFallbackOutbound
                ? provePreyApproachRoute(bot, destination, minimumY, returnAnchor)
                : proveSurfaceRouteContract(bot, destination, minimumY, returnAnchor, false);
        if (proof == HuntTask.SurfaceRouteProof.RETRY) {
            return HuntTask.SurfacePathStart.RETRY;
        }
        if (proof != HuntTask.SurfaceRouteProof.SAFE) {
            return HuntTask.SurfacePathStart.UNREACHABLE;
        }
        ActionResult result = returnAnchor == null
                ? bot.getActionPack().startSurfacePathTo(destination, minimumY)
                : digFallbackOutbound
                ? bot.getActionPack().startSurfaceDigFallbackPathTo(
                        destination, minimumY, returnAnchor)
                : bot.getActionPack().startSurfacePathTo(
                        destination, minimumY, returnAnchor);
        if (result.isFailed()) {
            return "pathfinding_throttled".equals(result.reason())
                    || result.reason().contains("NO_START")
                    || retryableObservationFailure(result.reason())
                    ? HuntTask.SurfacePathStart.RETRY : HuntTask.SurfacePathStart.UNREACHABLE;
        }
        BlockPos resolved = bot.getActionPack().activePathGoal();
        if (resolved == null || !resolved.equals(destination)) {
            bot.getActionPack().stopAll();
            return HuntTask.SurfacePathStart.UNREACHABLE;
        }
        return HuntTask.SurfacePathStart.STARTED;
    }
}
