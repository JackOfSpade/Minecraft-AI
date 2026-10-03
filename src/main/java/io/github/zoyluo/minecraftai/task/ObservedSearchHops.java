package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import net.minecraft.core.BlockPos;

/**
 * Starts bounded, observation-fenced exploration legs for resource tasks.
 *
 * <p>The remote coordinate is deliberately only a compass heading.  {@link
 * io.github.zoyluo.minecraftai.action.ActionPack#startDirectionalPursuitTo(BlockPos, int, boolean,
 * boolean)} resolves it to a short, actually observed local stance before Baritone receives a
 * route.  This class never reads terrain, chooses a height-map landing point, or grants break
 * permission while looking for a resource.</p>
 */
final class ObservedSearchHops {
    /** One physical leg stays inside the currently observed navigation fence. */
    static final int HOP_DISTANCE = 12;
    /** The remote heading only shapes a direction; it is never a navigation destination. */
    private static final int HEADING_DISTANCE = 48;
    private static final int[][] COMPASS = {
            {1, 0}, {1, 1}, {0, 1}, {-1, 1},
            {-1, 0}, {-1, -1}, {0, -1}, {1, -1}
    };

    enum Status {
        STARTED,
        REFUSED,
        EXHAUSTED
    }

    record Attempt(Status status, int number, BlockPos heading, BlockPos observedGoal,
                   boolean guided, String reason) {
        boolean started() {
            return status == Status.STARTED;
        }
    }

    private final int maxAttempts;
    private int attempts;
    private BlockPos anchor;

    ObservedSearchHops(int maxAttempts) {
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    void reset() {
        attempts = 0;
        anchor = null;
    }

    int attempts() {
        return attempts;
    }

    boolean exhausted() {
        return attempts >= maxAttempts;
    }

    /**
     * Begins one observed-only hop.  A remembered, factual resource position may bias the
     * heading, but it never becomes an unobserved path destination.
     */
    Attempt begin(AIPlayerEntity bot, BlockPos rememberedHint) {
        if (exhausted()) {
            return new Attempt(Status.EXHAUSTED, attempts, null, null, false, "search_budget_exhausted");
        }
        if (anchor == null) {
            anchor = bot.blockPosition().immutable();
        }
        int number = attempts + 1;
        boolean guided = rememberedHint != null && horizontalDistanceSquared(bot.blockPosition(), rememberedHint) > 4.0D;
        BlockPos heading = guided ? rememberedHint.immutable() : compassHeading(attempts);
        attempts++;
        ActionResult route = bot.getActionPack().startDirectionalPursuitTo(heading, HOP_DISTANCE, false, false);
        if (route.isFailed()) {
            return new Attempt(Status.REFUSED, number, heading, null, guided, route.reason());
        }
        BlockPos observedGoal = bot.getActionPack().activePathGoal();
        if (observedGoal == null) {
            // A directional route must expose an admitted local goal.  Do not leave a route whose
            // only target is the remote heading if that invariant is ever broken.
            bot.getActionPack().stopAll();
            return new Attempt(Status.REFUSED, number, heading, null, guided,
                    "directional_hop_missing_observed_goal");
        }
        return new Attempt(Status.STARTED, number, heading, observedGoal.immutable(), guided, "");
    }

    private BlockPos compassHeading(int attempt) {
        int directionIndex = Math.floorMod(attempt, COMPASS.length);
        int ring = attempt / COMPASS.length;
        int distance = HEADING_DISTANCE * (ring + 1);
        int[] direction = COMPASS[directionIndex];
        double length = Math.hypot(direction[0], direction[1]);
        int component = (int) Math.floor(distance / length);
        return anchor.offset(direction[0] * component, 0, direction[1] * component);
    }

    private static double horizontalDistanceSquared(BlockPos first, BlockPos second) {
        double dx = first.getX() - second.getX();
        double dz = first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }
}
