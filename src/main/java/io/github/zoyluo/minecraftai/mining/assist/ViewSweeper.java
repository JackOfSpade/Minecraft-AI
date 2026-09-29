package io.github.zoyluo.minecraftai.mining.assist;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.observe.BotProfiler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The Minecraft adapter of the honest sensor (mining-assist design 3.3): each tick it casts a
 * throttled batch of first-hit view rays from the bot's own eye and hands the answers to the pure
 * {@link SweepEngine}. Every ray goes through {@code ObservableWorldQuery.castViewRay}, which clamps to the
 * live perception radius and reads the state of the single first-hit cell; this class never reads the
 * world itself, so a hit is only ever a nomination that the memories store and a consumer must re-prove.
 *
 * <p>Ray budget per tick (design 3.4): {@code min(target, floor(global / sweepingBots))}, halved while
 * the tick-headroom latch is set and throttling is adaptive, where {@code target} is the configured
 * rate or {@value SenseBudget#BREAKTHROUGH_RAYS_PER_TICK} during a breakthrough sweep. It is cheap
 * arithmetic, not a wall-clock cut-off, and never drops below one ray.</p>
 *
 * <p>Call this only after the gate has opened for the bot ({@code MiningAssistRuntime.enabledFor}) and
 * the bot is underground; it does not check either. At most one call per bot per tick.</p>
 */
public final class ViewSweeper {
    /** Profiler section of the whole step (casts, occupancy, folds). Observability only. */
    public static final String SECTION_SWEEP = "assist_sweep";
    /** Profiler section of the fold part of the step (ring, occupancy, hazards, ledgers). */
    public static final String SECTION_FOLD = "assist_fold";

    private ViewSweeper() {
    }

    /** {@link #step(AIPlayerEntity, MiningAssistState, int, int)} at the bot's current server tick. */
    public static int step(AIPlayerEntity bot, MiningAssistState state, int raysPerTick) {
        return step(bot, state, raysPerTick, MiningAssistRuntime.serverTick(bot));
    }

    /**
     * Runs one sweep slice.
     *
     * @param raysPerTick the configured COLLIDER rays per tick ({@code sense.raysPerTick}); the throttle
     *                    and the breakthrough rate are applied on top of it here
     * @return the number of COLLIDER rays cast (0 never happens: the budget is at least one)
     */
    public static int step(AIPlayerEntity bot, MiningAssistState state, int raysPerTick, int serverTick) {
        long started = System.nanoTime();
        MiningAssistConfig config = MiningAssistRuntime.config();
        int target = SenseBudget.target(state.breakthroughActive(), raysPerTick);
        boolean halve = config.adaptiveThrottleActive(MiningAssistRuntime.isForced(bot.getUUID()))
                && MiningAssistRuntime.headroom().halveRays();
        int sweeping = MiningAssistRegistry.activeSweepers(serverTick, state);
        int rays = SenseBudget.raysEff(target, config.sense().globalRaysPerTick(), sweeping, halve);
        state.counters().raysThrottledOut += Math.max(0, target - rays);

        Vec3 eye = bot.getEyePosition();
        BlockPos feet = bot.blockPosition();
        double radius = SenseBudget.sweepRadius(MinecraftAiConfig.get().perception().radius());
        String dimension = BotEdits.dimensionKey(bot.level());
        SweepEngine.Context context = new SweepEngine.Context(
                eye.x, eye.y, eye.z, feet.getX(), feet.getY(), feet.getZ(), radius, serverTick, dimension,
                packed -> BotEdits.wasPlaced(dimension, packed));
        boolean lush = state.lush();
        long foldBefore = state.counters().foldNanos;
        int cast = SweepEngine.step(state, context,
                (dx, dy, dz, range, outline) -> probe(bot, lush, dx, dy, dz, range, outline), rays);

        BotProfiler.INSTANCE.record(bot, SECTION_FOLD, state.counters().foldNanos - foldBefore);
        BotProfiler.INSTANCE.record(bot, SECTION_SWEEP, System.nanoTime() - started);
        return cast;
    }

    private static SweepEngine.RayResult probe(AIPlayerEntity bot, boolean lush,
                                               double dx, double dy, double dz,
                                               double range, boolean outline) {
        ObservableWorldQuery.ViewHit view = ObservableWorldQuery.castViewRay(bot, dx, dy, dz, range,
                outline ? ObservableWorldQuery.ViewShape.OUTLINE : ObservableWorldQuery.ViewShape.COLLIDER);
        if (view.isUnknown()) {
            return SweepEngine.RayResult.UNKNOWN;
        }
        if (!view.hit()) {
            return SweepEngine.RayResult.miss(view.distance());
        }
        BlockState hitState = view.state();
        return SweepEngine.RayResult.hit(
                view.pos(),
                view.distance(),
                BlockFactsAdapter.of(hitState, lush),
                BlockFactsAdapter.fluidKind(hitState),
                BlockFactsAdapter.holdsFluid(hitState));
    }
}
