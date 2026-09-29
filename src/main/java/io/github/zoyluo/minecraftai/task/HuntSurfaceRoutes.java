package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.pathfinding.AStarPathfinder;
import io.github.zoyluo.minecraftai.pathfinding.FailureReason;
import io.github.zoyluo.minecraftai.pathfinding.PathExecutor;
import io.github.zoyluo.minecraftai.pathfinding.PathfindingResult;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Stateless surface-route proving/pathing primitives shared by HuntTask's roam, prey-approach and
 * pickup phases: prove that a route stays on reversible, no-dig footing (or, for the prey-approach
 * contract, a reversible dig-fallback stair) before HuntTask commits to walking it. Every method
 * here is static and takes only World/BlockPos/AStarPathfinder-level parameters -- no HuntTask
 * instance state -- so HuntTask.SurfaceRouteProof / HuntTask.SurfacePathStart (the shared
 * proof/start-result contracts every phase reads) stay declared on HuntTask itself.
 */
final class HuntSurfaceRoutes {
    private static final int ROAM_RETRY_ROTATION_DEGREES = 11;
    /** Match ActionPack's surface-path budget so its outbound execution reuses A*'s success cache. */
    private static final int SURFACE_ROUTE_MAX_NODES = 10_000;
    private static final long SURFACE_ROUTE_MAX_MILLIS = 50L;

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

    static boolean hasWalkableReturnRoute(ServerLevel world, BlockPos waypoint, BlockPos origin) {
        return hasExactSurfaceRoute(world, waypoint, origin, Integer.MIN_VALUE);
    }

    static boolean hasRoundTripSurfaceRoute(
            ServerLevel world, BlockPos origin, BlockPos destination, int minimumY) {
        return proveRoundTripSurfaceRoute(
                world, origin, destination, minimumY) == HuntTask.SurfaceRouteProof.SAFE;
    }

    static boolean hasExactSurfaceRoute(
            ServerLevel world, BlockPos origin, BlockPos destination, int minimumY) {
        return proveExactSurfaceRoute(
                world, origin, destination, minimumY) == HuntTask.SurfaceRouteProof.SAFE;
    }

    static HuntTask.SurfaceRouteProof proveRoundTripSurfaceRoute(
            ServerLevel world, BlockPos origin, BlockPos destination, int minimumY) {
        return proveSurfaceRouteContract(
                world, origin, destination, minimumY, origin);
    }

    private static HuntTask.SurfaceRouteProof proveSurfaceRouteContract(
            ServerLevel world, BlockPos origin, BlockPos destination,
            int minimumY, BlockPos returnAnchor) {
        HuntTask.SurfaceRouteProof outbound =
                proveExactSurfaceRoute(world, origin, destination, minimumY);
        if (outbound != HuntTask.SurfaceRouteProof.SAFE) {
            return outbound;
        }
        return returnAnchor == null ? HuntTask.SurfaceRouteProof.SAFE
                : proveExactSurfaceRoute(
                        world, destination, returnAnchor, minimumY);
    }

    /**
     * Prey-approach contract: the outbound leg may dig a near-level stair through obstacles
     * (walk-only first, dig fallback at most one block under the lower endpoint), while the
     * return proof stays strictly no-dig. On natural hills the walk-only outbound rejected
     * nearly every visible herd (no_round_trip) and starved whole missions; a dug stair is
     * itself walk-only returnable, and the debt protection - never needing fresh digging on
     * the way back - is unchanged. Deep trench crossings stay rejected: the A* prefers drop
     * shortcuts there, and a reversible stair across a void is not something it will find.
     * Every other route (surface returns, roams, pickup sweeps) stays strict.
     */
    static HuntTask.SurfaceRouteProof provePreyApproachRoute(
            AIPlayerEntity bot, ServerLevel world, BlockPos origin, BlockPos destination,
            int minimumY, BlockPos returnAnchor) {
        HuntTask.SurfaceRouteProof outbound =
                proveExactDigFallbackRoute(bot, world, origin, destination, minimumY);
        if (outbound != HuntTask.SurfaceRouteProof.SAFE) {
            return outbound;
        }
        return returnAnchor == null ? HuntTask.SurfaceRouteProof.SAFE
                : proveExactSurfaceRoute(
                        world, destination, returnAnchor, minimumY);
    }

    /** Near-level breakthrough floor: at most one block under the lower endpoint. */
    static int digBreakthroughFloor(BlockPos origin, BlockPos destination, int minimumY) {
        return Math.max(minimumY,
                Math.min(origin.getY(), destination.getY()) - 1);
    }

    // bot is only used to gate NeighborEnumerator's DIG_THROUGH lava/water preflight on real
    // observability (see AStarPathfinder's AIPlayerEntity constructor); it may be null (e.g. a
    // proof run with no live bot handy), which safely degrades to allow-unknown at plan time --
    // PathExecutor.tickDigThrough()'s reactive check is what actually keeps that safe at runtime.
    private static HuntTask.SurfaceRouteProof proveExactDigFallbackRoute(
            AIPlayerEntity bot, ServerLevel world, BlockPos origin, BlockPos destination, int minimumY) {
        HuntTask.SurfaceRouteProof walk = proveExactSurfaceRoute(world, origin, destination, minimumY);
        if (walk == HuntTask.SurfaceRouteProof.SAFE) {
            return walk;
        }
        // RETRY must fall through too: on natural hillsides the walk-only search burns its whole
        // node/time budget hunting for a way around and reports RETRY forever - the dig search
        // straight through the hill is cheaper and decisive (from-zero evidence: walk RETRY
        // starved every hunt while the dig proof never ran).
        int digFloor = digBreakthroughFloor(origin, destination, minimumY);
        Standability.clearCache();
        PathfindingResult result = new AStarPathfinder(
                bot, world, origin, destination,
                SURFACE_ROUTE_MAX_NODES, SURFACE_ROUTE_MAX_MILLIS,
                false, true).findPathUncachedAtOrAbove(digFloor);
        if (result.success()) {
            // The dug corridor is itself the walk-only return route, but only when every
            // step is stair-shaped: a dig path that drops more than one block cannot be
            // walked back up, and digging on the way home is exactly the debt this
            // contract exists to prevent.
            return PathExecutor.isExactConstrainedRoute(result, origin, destination, digFloor)
                    && PathExecutor.isReversibleStair(result)
                    ? HuntTask.SurfaceRouteProof.SAFE : HuntTask.SurfaceRouteProof.UNREACHABLE;
        }
        return result.reason() == FailureReason.NO_START
                || result.reason() == FailureReason.TIMEOUT
                || result.reason() == FailureReason.SEARCH_LIMIT
                ? HuntTask.SurfaceRouteProof.RETRY : HuntTask.SurfaceRouteProof.UNREACHABLE;
    }

    private static HuntTask.SurfaceRouteProof proveExactSurfaceRoute(
            ServerLevel world, BlockPos origin, BlockPos destination, int minimumY) {
        Standability.clearCache();
        if (origin.equals(destination)) {
            return origin.getY() >= minimumY && Standability.isStandable(world, origin)
                    ? HuntTask.SurfaceRouteProof.SAFE : HuntTask.SurfaceRouteProof.RETRY;
        }
        PathfindingResult result = new AStarPathfinder(
                world, origin, destination,
                SURFACE_ROUTE_MAX_NODES, SURFACE_ROUTE_MAX_MILLIS,
                false, false).findPathUncachedAtOrAbove(minimumY);
        if (result.success()) {
            return PathExecutor.isExactConstrainedRoute(
                            result, origin, destination, minimumY)
                    ? HuntTask.SurfaceRouteProof.SAFE : HuntTask.SurfaceRouteProof.UNREACHABLE;
        }
        return result.reason() == FailureReason.NO_START
                || result.reason() == FailureReason.TIMEOUT
                || result.reason() == FailureReason.SEARCH_LIMIT
                ? HuntTask.SurfaceRouteProof.RETRY : HuntTask.SurfaceRouteProof.UNREACHABLE;
    }

    static HuntTask.SurfacePathStart startExactSurfacePath(
            AIPlayerEntity bot, BlockPos destination, int minimumY,
            BlockPos returnAnchor) {
        return startExactSurfacePath(bot, destination, minimumY, returnAnchor, false);
    }

    /** Prey-approach variant: the executed outbound leg matches the dig-fallback proof. */
    static HuntTask.SurfacePathStart startExactSurfacePath(
            AIPlayerEntity bot, BlockPos destination, int minimumY,
            BlockPos returnAnchor, boolean digFallbackOutbound) {
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        HuntTask.SurfaceRouteProof proof = digFallbackOutbound
                ? provePreyApproachRoute(bot, world, origin, destination, minimumY, returnAnchor)
                : proveSurfaceRouteContract(
                        world, origin, destination, minimumY, returnAnchor);
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
