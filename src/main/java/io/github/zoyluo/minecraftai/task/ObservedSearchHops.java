package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;

/**
 * Starts bounded, observation-fenced exploration legs for resource tasks.
 *
 * <p>The remote coordinate is deliberately only a compass heading.  {@link
 * io.github.zoyluo.minecraftai.action.ActionPack#startDirectionalPursuitTo(BlockPos, int, boolean,
 * boolean)} resolves it to a short, actually observed local stance before Baritone receives a
 * route.  This class never reads terrain, chooses a height-map landing point, or grants break
 * permission while looking for a resource.</p>
 *
 * <p>Which compass heading to try is decided by what the bot remembers of its own search
 * ({@link ExplorationMemory}): it keeps walking its way into ground it has not searched and does not
 * circle back over what it has. Each time it reaches a new place it looks around once, and only the
 * ground it saw from there counts as searched.</p>
 */
final class ObservedSearchHops {
    /**
     * One physical leg stays inside the currently observed navigation fence, so a hop is as long as the fence
     * allows: the perception radius less the two cells it keeps for the feet, head and support of the hop's far
     * end ({@code ObservedNavigationFence.pursuitObservationPoint}). Any longer and the fence cuts it back to this.
     */
    static int hopDistance() {
        return Math.max(1, perceptionRadius() - 2);
    }

    private static int perceptionRadius() {
        return Math.max(1, MinecraftAiConfig.get().perception().radius());
    }
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
    /** Survives {@link #reset}: a fresh episode must not search again where the last one already looked. */
    private final ExplorationMemory memory = new ExplorationMemory();
    /** Local stances whose admitted routes made no physical progress in this search episode. */
    private final Set<BlockPos> retiredObservedGoals = new HashSet<>();

    ObservedSearchHops(int maxAttempts) {
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    void reset() {
        attempts = 0;
        retiredObservedGoals.clear();
    }

    int attempts() {
        return attempts;
    }

    boolean exhausted() {
        return attempts >= maxAttempts;
    }

    /**
     * Retires one already-admitted local stance after its route stalls or ends short. The remote
     * heading remains only a compass direction, so retrying the same local goal would reproduce
     * the same spin without discovering any terrain.
     */
    void retireObservedGoal(BlockPos goal) {
        if (goal != null) {
            retiredObservedGoals.add(goal.immutable());
        }
    }

    /** Package-visible for the focused exploration-progress tests. */
    boolean isRetiredObservedGoal(BlockPos goal) {
        return goal != null && retiredObservedGoals.contains(goal);
    }

    /**
     * Begins one observed-only hop.  A remembered, factual resource position may bias the
     * heading, but it never becomes an unobserved path destination.
     */
    Attempt begin(AIPlayerEntity bot, BlockPos rememberedHint) {
        if (exhausted()) {
            return new Attempt(Status.EXHAUSTED, attempts, null, null, false, "search_budget_exhausted");
        }
        BlockPos feet = bot.blockPosition();
        int radius = perceptionRadius();
        // Callers ask for a hop only after they searched what they can perceive from here: the bot looks around
        // once from each new place and counts as searched what that look showed it.
        if (memory.wantsLookAround(feet.getX(), feet.getZ())) {
            memory.markSearched(feet.getX(), feet.getZ(), radius, lookAround(bot, radius));
        }
        int number = attempts + 1;
        boolean guided = rememberedHint != null && horizontalDistanceSquared(feet, rememberedHint) > 4.0D;
        int direction = -1;
        BlockPos heading;
        if (guided) {
            heading = rememberedHint.immutable();
        } else {
            direction = memory.chooseDirection(COMPASS, feet.getX(), feet.getZ(), hopDistance(), radius);
            heading = compassHeading(feet, COMPASS[direction]);
        }
        attempts++;
        ActionResult route = bot.getActionPack().startDirectionalPursuitTo(heading, hopDistance(), false, false);
        if (route.isFailed()) {
            refuse(feet, direction);
            return new Attempt(Status.REFUSED, number, heading, null, guided, route.reason());
        }
        BlockPos observedGoal = bot.getActionPack().activePathGoal();
        if (observedGoal == null) {
            // A directional route must expose an admitted local goal.  Do not leave a route whose
            // only target is the remote heading if that invariant is ever broken.
            bot.getActionPack().stopAll();
            refuse(feet, direction);
            return new Attempt(Status.REFUSED, number, heading, null, guided,
                    "directional_hop_missing_observed_goal");
        }
        if (isRetiredObservedGoal(observedGoal)) {
            // Admission may legitimately resolve several compass headings to the same small
            // visible corridor. Once that exact corridor has already stalled, do not let it turn
            // another heading into a duplicate route/replan loop.
            bot.getActionPack().stopAll();
            refuse(feet, direction);
            return new Attempt(Status.REFUSED, number, heading, null, guided,
                    "directional_hop_retired_observed_goal");
        }
        if (direction >= 0) {
            memory.noteHeading(direction);
        }
        return new Attempt(Status.STARTED, number, heading, observedGoal.immutable(), guided, "");
    }

    /**
     * How far the bot sees from where it stands in each sector of the horizon, along its own line of sight at eye
     * height: the distance to the first thing that blocks it, the full radius where nothing does, 0 where it cannot
     * look at all (the chunk there is not loaded). One look per new place, not one per tick.
     */
    private static double[] lookAround(AIPlayerEntity bot, int radius) {
        double[] sight = new double[ExplorationMemory.SECTORS];
        for (int sector = 0; sector < sight.length; sector++) {
            double[] direction = ExplorationMemory.sectorCentre(sector);
            ObservableWorldQuery.ViewHit view = ObservableWorldQuery.castViewRay(bot, direction[0], 0.0D, direction[1],
                    radius, ObservableWorldQuery.ViewShape.COLLIDER);
            sight[sector] = view.isUnknown() ? 0.0D : view.hit() ? Math.min(radius, view.distance()) : radius;
        }
        return sight;
    }

    /** A refused compass heading is not offered again from the same stance. */
    private void refuse(BlockPos feet, int direction) {
        if (direction >= 0) {
            memory.noteRefused(feet.getX(), feet.getZ(), direction);
        }
    }

    private static BlockPos compassHeading(BlockPos feet, int[] direction) {
        double length = Math.hypot(direction[0], direction[1]);
        int component = (int) Math.floor(HEADING_DISTANCE / length);
        return feet.offset(direction[0] * component, 0, direction[1] * component);
    }

    private static double horizontalDistanceSquared(BlockPos first, BlockPos second) {
        double dx = first.getX() - second.getX();
        double dz = first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }
}
