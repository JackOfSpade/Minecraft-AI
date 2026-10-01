package io.github.zoyluo.minecraftai.pathfinding;

import io.github.zoyluo.minecraftai.log.BotLog;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Pays the legacy A* class-load/JIT cost while the server starts, before a bot can make the first user-visible route request.
 *
 * <p>The two searches use an observed, standable pair near the ordinary world spawn. The first has a generous startup allowance;
 * the second is an unscaled, real-wall-clock probe at {@link AStarPathfinder#DEFAULT_MAX_MILLIS}. That probe deliberately does
 * not inherit the GameTest time multiplier: a tick-counted harness cannot certify a production 50 ms bound. The result is logged
 * rather than treated as a reason to stop a server, because an unusual user-built spawn may leave no nearby route to warm.</p>
 */
public final class RuntimePathfinderWarmup {
    private static final int MAX_NODES = 10_000;
    private static final long STARTUP_WARMUP_MILLIS = 1_000L;
    private static final int SPAWN_HORIZONTAL_RADIUS = 8;
    private static final int SPAWN_VERTICAL_DOWN = 32;
    private static final int SPAWN_VERTICAL_UP = 8;
    private static final int ROUTE_RADIUS = 4;

    private static MinecraftServer warmedServer;

    private RuntimePathfinderWarmup() {
    }

    /** Runs exactly once per server lifecycle, from {@code SERVER_STARTED} before restored bot work can ask for a route. */
    public static void warmAtServerStart(MinecraftServer server) {
        if (server == null || server == warmedServer) {
            return;
        }
        warmedServer = server;
        ServerLevel world = server.overworld();
        Optional<WarmRoute> route = warmRoute(world);
        if (route.isEmpty()) {
            BotLog.path(null, "pathfinder_warm_up", "result", "skipped_no_observed_spawn_route");
            return;
        }

        WarmRoute pair = route.get();
        long warmStarted = System.nanoTime();
        PathfindingResult warm = AStarPathfinder.withRealTimeBudget(world, pair.start(), pair.goal(),
                MAX_NODES, STARTUP_WARMUP_MILLIS, false, false).findPathUncached();
        long warmNanos = System.nanoTime() - warmStarted;

        long probeStarted = System.nanoTime();
        PathfindingResult probe = AStarPathfinder.withRealTimeBudget(world, pair.start(), pair.goal(),
                MAX_NODES, AStarPathfinder.DEFAULT_MAX_MILLIS, false, false).findPathUncached();
        long probeNanos = System.nanoTime() - probeStarted;
        boolean meaningful = didRealWork(warm);
        boolean withinBudget = passedRealTimeProbe(probe, probeNanos);
        BotLog.path(null, "pathfinder_warm_up",
                "start", pair.start().toShortString(),
                "goal", pair.goal().toShortString(),
                "warm_success", warm.success(),
                "warm_nodes", warm.nodesExplored(),
                "warm_wall_ms", millis(warmNanos),
                "probe_success", probe.success(),
                "probe_nodes", probe.nodesExplored(),
                "probe_wall_ms", millis(probeNanos),
                "meaningful", meaningful,
                "within_50ms", withinBudget);
    }

    /** Clears the per-server guard so an integrated-server restart receives a fresh cold-start probe. */
    public static void clear(MinecraftServer server) {
        if (warmedServer == server) {
            warmedServer = null;
        }
    }

    /** A success or more than one expanded node proves this was a search, not an endpoint validation no-op. */
    static boolean didRealWork(PathfindingResult result) {
        return result.success() || result.nodesExplored() > 1;
    }

    /** The actual elapsed monotonic clock, not A*'s harness-scaled allowance, decides this production regression probe. */
    static boolean passedRealTimeProbe(PathfindingResult result, long elapsedNanos) {
        return didRealWork(result)
                && elapsedNanos <= TimeUnit.MILLISECONDS.toNanos(AStarPathfinder.DEFAULT_MAX_MILLIS);
    }

    private static long millis(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, nanos));
    }

    /** Finds only factually standable cells; it does not make or assume a route through an altered spawn. */
    private static Optional<WarmRoute> warmRoute(ServerLevel world) {
        if (world == null) {
            return Optional.empty();
        }
        Optional<BlockPos> start = Standability.findNearestStandable(world, world.getRespawnData().pos(),
                SPAWN_HORIZONTAL_RADIUS, SPAWN_VERTICAL_DOWN, SPAWN_VERTICAL_UP);
        if (start.isEmpty()) {
            return Optional.empty();
        }
        BlockPos from = start.get();
        for (int radius = 1; radius <= ROUTE_RADIUS; radius++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                            continue;
                        }
                        BlockPos candidate = from.offset(dx, dy, dz);
                        if (Standability.isStandableFresh(world, candidate)) {
                            return Optional.of(new WarmRoute(from, candidate.immutable()));
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    private record WarmRoute(BlockPos start, BlockPos goal) {
    }
}
