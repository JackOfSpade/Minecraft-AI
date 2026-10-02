package io.github.zoyluo.minecraftai.baritone;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.navigation.NavRoute;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Strict GameTest-only route starter. It goes through the production navigator rather than
 * submitting a raw Baritone goal, so fixture routes receive the same observation fence, stance
 * resolution, break/place policy, remembered-target checks, and water lease as an ActionPack
 * request. It never constructs a chunk snapshot from fixture terrain.
 */
public final class ObservedBaritoneTestRoutes {
    private ObservedBaritoneTestRoutes() {
    }

    public static void block(AIPlayerEntity bot, BlockPos target, String label) {
        block(bot, target, NavRoute.Options.WALK_ONLY, label);
    }

    /** Starts an exact observed route with the operation permissions the fixture is exercising. */
    public static void block(AIPlayerEntity bot, BlockPos target, NavRoute.Options options, String label) {
        requireAdmitted(bot, admitBlock(bot, target, options, label), target);
    }

    public static void near(AIPlayerEntity bot, BlockPos target, int radius, String label) {
        near(bot, target, radius, NavRoute.Options.WALK_ONLY, label);
    }

    /** Starts a near observed route with the operation permissions the fixture is exercising. */
    public static void near(AIPlayerEntity bot, BlockPos target, int radius, NavRoute.Options options, String label) {
        requireAdmitted(bot, admitNear(bot, target, radius, options, label), target);
    }

    /**
     * Admits an exact fixture route without turning an expected strict-policy refusal into an exception.
     * The result still comes directly from {@link BaritoneNavigator}; this helper never manufactures observation state.
     */
    public static BaritoneNavigator.Admission admitBlock(AIPlayerEntity bot, BlockPos target,
                                                           NavRoute.Options options, String label) {
        return start(bot, new NavRoute(
                NavRoute.Shape.BLOCK, target, 0, options, label, serverTick(bot)));
    }

    /** See {@link #admitBlock(AIPlayerEntity, BlockPos, NavRoute.Options, String)}. */
    public static BaritoneNavigator.Admission admitNear(AIPlayerEntity bot, BlockPos target, int radius,
                                                          NavRoute.Options options, String label) {
        return start(bot, new NavRoute(
                NavRoute.Shape.NEAR, target, radius, options, label, serverTick(bot)));
    }

    /** Production-equivalent explicit swim fixture, including its safety-net lease. */
    public static void swim(AIPlayerEntity bot, BlockPos target, String label) {
        requireAdmitted(bot, start(bot, new NavRoute(
                NavRoute.Shape.BLOCK, target, 0, NavRoute.Options.SWIM, label, serverTick(bot))), target);
    }

    private static BaritoneNavigator.Admission start(AIPlayerEntity bot, NavRoute route) {
        BaritoneNavigator.Admission admission = BaritoneNavigator.start(bot, route, true);
        return admission;
    }

    private static void requireAdmitted(AIPlayerEntity bot, BaritoneNavigator.Admission admission, BlockPos target) {
        if (!admission.accepted()) {
            ObservedNavigationFence fence = BaritoneRegistry.INSTANCE.observationMemory(bot);
            throw new IllegalStateException("GameTest route was not visibly admissible: " + admission.failure()
                    + " target=" + target
                    + " observed_cells=" + fence.cellCount()
                    + " target_state=" + fence.stateAt(target)
                    + " target_below=" + fence.stateAt(target.below())
                    + " target_above=" + fence.stateAt(target.above())
                    + " target_east=" + fence.stateAt(target.east())
                    + " target_west=" + fence.stateAt(target.west())
                    + " target_below_east=" + fence.stateAt(target.below().east())
                    + " bot_state=" + fence.stateAt(bot.blockPosition())
                    + " bot_below=" + fence.stateAt(bot.blockPosition().below())
                    + " bot_above=" + fence.stateAt(bot.blockPosition().above())
                    + " toward_goal_column=" + column(fence,
                    bot.blockPosition().offset(Integer.signum(target.getX() - bot.blockPosition().getX()), 0, 0),
                    Math.min(target.getY() - 1, bot.blockPosition().getY() - 2), bot.blockPosition().getY() + 2)
                    + " bot=" + bot.blockPosition());
        }
    }

    private static String column(ObservedNavigationFence fence, BlockPos pos, int lower, int upper) {
        StringBuilder text = new StringBuilder();
        for (int y = upper; y >= lower; y--) {
            if (text.length() > 0) {
                text.append(',');
            }
            BlockState state = fence.stateAt(pos.getX(), y, pos.getZ());
            text.append(y).append('=').append(state == null ? '?' : state.getBlock());
        }
        return pos + "[" + text + ']';
    }

    private static int serverTick(AIPlayerEntity bot) {
        return bot.getServer().getTickCount();
    }
}
