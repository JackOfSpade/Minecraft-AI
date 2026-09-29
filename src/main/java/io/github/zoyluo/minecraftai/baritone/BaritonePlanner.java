package io.github.zoyluo.minecraftai.baritone;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.utils.interfaces.IGoalRenderPos;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.HostEnvironment;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.PathCalculationResult;
import baritone.pathing.calc.AStarPathFinder;
import baritone.pathing.calc.AbstractNodeCostSearch;
import baritone.pathing.movement.CalculationContext;
import baritone.utils.pathing.Favoring;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;

/**
 * Plan-only use of Baritone: search for a path from where the bot stands to a goal, on the shared worker pool, without
 * touching the bot. Answers "can it get there, how, and how long would it take" and is what a caller uses to decide before
 * committing a bot to a route. Executing a path is {@code PathingBehavior}'s job.
 *
 * <p>Threading: {@link #plan} must be called on the server thread. Everything a search reads from the world is captured
 * there, before it is queued: a {@link CalculationContext} built with {@code forUseOnAnotherThread} holds a
 * {@code BlockStateInterface} over an O(1) {@link LoadedChunkSnapshot}, the bot's tool set, inventory-derived flags and the
 * settings. The worker then only reads chunks through that snapshot. The future completes on a worker thread; anything
 * that must touch the world or the bot has to hop back to the server thread itself (this class only logs, and does so
 * through the server thread).</p>
 *
 * <p>One search per bot: a new {@link #plan} for the same instance cancels the previous one (its future completes with a
 * {@code CANCELLATION} result), and {@link BaritoneRegistry#forget}/{@code reset} cancel it too.</p>
 */
public final class BaritonePlanner {
    /** The search each instance currently has queued or running. */
    private static final Map<IBaritone, AbstractNodeCostSearch> IN_FLIGHT = new ConcurrentHashMap<>();

    private BaritonePlanner() {
    }

    /**
     * Outcome of one search.
     *
     * @param result      Baritone's result: {@code SUCCESS_TO_GOAL}, {@code SUCCESS_SEGMENT} (a partial path toward the goal),
     *                    {@code FAILURE}, {@code CANCELLATION} or {@code EXCEPTION}
     * @param searchMillis time the search itself ran on its worker
     * @param queueMillis  time it waited for a free worker before that
     * @param movements    simple class name of every movement of the path in order ({@code MovementTraverse},
     *                     {@code MovementAscend}, ...); empty without a path
     */
    public record Plan(PathCalculationResult result, long searchMillis, long queueMillis, List<String> movements) {
        public PathCalculationResult.Type type() {
            return result.getType();
        }

        public boolean reachesGoal() {
            return type() == PathCalculationResult.Type.SUCCESS_TO_GOAL;
        }

        /** The path, if the search produced one (complete or partial). */
        public IPath path() {
            return result.getPath().orElse(null);
        }

        /** Baritone's own estimate of the ticks the whole path takes (walking, breaking and placing included); 0 without a path. */
        public double estimatedTicks() {
            IPath path = path();
            return path == null ? 0.0 : path.ticksRemainingFrom(0);
        }

        public int nodesConsidered() {
            IPath path = path();
            return path == null ? 0 : path.getNumNodesConsidered();
        }
    }

    /** Plans with Baritone's own timeouts ({@code primaryTimeoutMS}, {@code failureTimeoutMS}). Server thread only. */
    public static CompletableFuture<Plan> plan(IBaritone baritone, Goal goal) {
        return plan(baritone, goal, Baritone.settings().primaryTimeoutMS.value, Baritone.settings().failureTimeoutMS.value);
    }

    /**
     * @param primaryTimeoutMs stop as soon as the search has a usable path and this long has passed
     * @param failureTimeoutMs give up on finding any path after this long
     */
    public static CompletableFuture<Plan> plan(IBaritone baritone, Goal goal, long primaryTimeoutMs, long failureTimeoutMs) {
        IPlayerContext ctx = baritone.getPlayerContext();
        if (!ctx.minecraft().isSameThread()) {
            throw new IllegalStateException("BaritonePlanner.plan must be called on the server thread");
        }
        BaritoneSettings.applyNavLimits();
        AIPlayerEntity bot = (AIPlayerEntity) ctx.player();

        CalculationContext calc = new CalculationContext(baritone, true);
        BetterBlockPos start = ctx.playerFeet();
        Goal searched = goal;
        if (Baritone.settings().simplifyUnloadedYCoord.value && goal instanceof IGoalRenderPos render) {
            BlockPos pos = render.getGoalPos();
            if (!calc.bsi.worldContainsLoadedChunk(pos.getX(), pos.getZ())) {
                searched = new GoalXZ(pos.getX(), pos.getZ()); // the goal's height cannot be judged from an unloaded column
            }
        }
        AbstractNodeCostSearch finder = new AStarPathFinder(start, start.x, start.y, start.z, searched,
                new Favoring(ctx, null, calc), calc);

        CompletableFuture<Plan> future = new CompletableFuture<>();
        AbstractNodeCostSearch superseded = IN_FLIGHT.put(baritone, finder);
        if (superseded != null) {
            superseded.cancel();
        }
        long queuedAt = System.nanoTime();
        try {
            HostEnvironment.executor().execute(() -> {
                long startedAt = System.nanoTime();
                PathCalculationResult result;
                try {
                    result = finder.calculate(primaryTimeoutMs, failureTimeoutMs);
                } catch (Throwable t) {
                    IN_FLIGHT.remove(baritone, finder);
                    future.completeExceptionally(t);
                    return;
                }
                long endedAt = System.nanoTime();
                IN_FLIGHT.remove(baritone, finder);
                List<String> movements = result.getPath()
                        .map(path -> path.movements().stream().map(m -> m.getClass().getSimpleName()).toList())
                        .orElse(List.of());
                Plan plan = new Plan(result, (endedAt - startedAt) / 1_000_000L, (startedAt - queuedAt) / 1_000_000L, movements);
                // BotLog reads task state that belongs to the server thread.
                ctx.minecraft().execute(() -> BotLog.path(bot, "baritone_plan",
                        "goal", goal, "type", plan.type(), "nodes", plan.nodesConsidered(),
                        "moves", plan.movements().size(), "search_ms", plan.searchMillis(), "queue_ms", plan.queueMillis()));
                future.complete(plan);
            });
        } catch (RuntimeException rejected) {
            IN_FLIGHT.remove(baritone, finder);
            future.completeExceptionally(rejected);
        }
        return future;
    }

    /**
     * Runs one search on the calling (server) thread and returns its result: the admission check of a route request, which
     * callers need answered in the same tick ("can the bot get there at all?") before they commit to it. Uses the same
     * snapshot-backed context as {@link #plan}; the budget is the caller's, and small (a few tens of milliseconds): a goal that
     * is far away answers with a partial path ({@code SUCCESS_SEGMENT}), which is enough to know the bot can start moving.
     * Does not touch {@link #IN_FLIGHT} (nothing else can run for the bot while this call holds the server thread).
     */
    public static Plan planNow(IBaritone baritone, Goal goal, long primaryTimeoutMs, long failureTimeoutMs) {
        IPlayerContext ctx = baritone.getPlayerContext();
        if (!ctx.minecraft().isSameThread()) {
            throw new IllegalStateException("BaritonePlanner.planNow must be called on the server thread");
        }
        CalculationContext calc = new CalculationContext(baritone, true);
        BetterBlockPos start = ctx.playerFeet();
        AbstractNodeCostSearch finder = new AStarPathFinder(start, start.x, start.y, start.z, goal,
                new Favoring(ctx, null, calc), calc);
        long startedAt = System.nanoTime();
        PathCalculationResult result = finder.calculate(primaryTimeoutMs, failureTimeoutMs);
        long endedAt = System.nanoTime();
        List<String> movements = result.getPath()
                .map(path -> path.movements().stream().map(m -> m.getClass().getSimpleName()).toList())
                .orElse(List.of());
        return new Plan(result, (endedAt - startedAt) / 1_000_000L, 0L, movements);
    }

    /** Cancels the instance's queued or running plan, if any. Safe from any thread. */
    public static void cancel(IBaritone baritone) {
        AbstractNodeCostSearch running = IN_FLIGHT.remove(baritone);
        if (running != null) {
            running.cancel();
        }
    }

    /** Whether {@code baritone}'s instance has a search queued or running. */
    public static boolean isInFlight(IBaritone baritone) {
        return IN_FLIGHT.containsKey(baritone);
    }

    /** Plans currently queued or running (all bots). */
    public static int inFlight() {
        return IN_FLIGHT.size();
    }
}
