package io.github.zoyluo.minecraftai.navigation;

/**
 * The pure rules of the navigator seam (no Minecraft, no Baritone): how a walk request maps onto what a Baritone route may do,
 * and how the state of a running route maps onto the legacy result vocabulary. {@code ActionPack} applies them; the unit tests
 * pin them.
 */
public final class NavRouteRules {
    /** Failure reason of a route that ended short of its goal because Baritone's own search found no way on. */
    public static final String GOAL_UNREACHABLE = "pathfinding_failed: GOAL_UNREACHABLE";
    /** Failure reason of a route that ended short of its goal without saying why. */
    public static final String PATH_INCOMPLETE = "path_incomplete";
    /** Failure reason of a dry route that got into water. */
    public static final String ROUTE_ENTERED_WATER = "route_entered_water";
    public static final String PATH_TIMEOUT = "path_timeout";

    private NavRouteRules() {
    }

    /**
     * What an ordinary request from the ActionPack path API may do. Breaking is the legacy "dig fallback" permission; placing is
     * the legacy "can pillar" one, but never while the caller keeps a stone reserve (the planner cannot see the reserve); a bot
     * that stands in water when asked is allowed to cross water, since the way out of it is the first thing the route has to do.
     */
    public static NavRoute.Options optionsFor(boolean allowDigFallback, boolean canPillar, int protectedStoneLikeReserve,
                                              boolean botInWater) {
        return new NavRoute.Options(allowDigFallback, canPillar && protectedStoneLikeReserve <= 0, botInWater);
    }

    /** Ticks a route may run before it is abandoned: a base allowance plus twenty ticks per block of straight-line distance. */
    public static int deadlineTicks(double horizontalDistance) {
        return Math.min(6000, 600 + (int) (20.0D * Math.max(0.0D, horizontalDistance)));
    }

    /** The reason of a route that ended short of its goal. */
    public static String shortFailureReason(boolean searchFailed) {
        return searchFailed ? GOAL_UNREACHABLE : PATH_INCOMPLETE;
    }

    /** The answer for a route check: {@code status == null} means the route carries on. */
    public record Verdict(NavOutcome.Status status, String reason) {
        static final Verdict CONTINUE = new Verdict(null, "");

        public boolean ended() {
            return status != null;
        }
    }

    /**
     * Maps the state of a route onto its verdict.
     *
     * @param progress     where Baritone leaves the route
     * @param dryRouteWet  the route may not swim and the bot is in water
     * @param pastDeadline the route ran past its deadline
     * @param searchFailed Baritone reported that its search failed (only read when the route ended short)
     */
    public static Verdict verdict(NavRoute.Progress progress, boolean dryRouteWet, boolean pastDeadline, boolean searchFailed) {
        return switch (progress) {
            case ARRIVED -> new Verdict(NavOutcome.Status.SUCCESS, "");
            case ENDED_SHORT -> new Verdict(NavOutcome.Status.FAILED, shortFailureReason(searchFailed));
            case RUNNING -> dryRouteWet
                    ? new Verdict(NavOutcome.Status.FAILED, ROUTE_ENTERED_WATER)
                    : pastDeadline ? new Verdict(NavOutcome.Status.TIMEOUT, PATH_TIMEOUT) : Verdict.CONTINUE;
        };
    }
}
