package dev.spawnbotswrapper.inhabitants.util;

/**
 * The one rule about the archer distances the addon manages in PvP BOT ({@code pvpbotSettings} in the config): they
 * are positive and ordered {@code min < optimal <= max <= maxTargetDistance}. Pure, shared by the config validator
 * (which sees only what the file says) and the adapter (which also knows the values PvP BOT itself has).
 */
public final class RangedDistances {
    private RangedDistances() {
    }

    /**
     * Why these distances cannot all hold, or null when they can. A null argument is skipped, so a partial set is
     * only checked as far as it goes.
     */
    public static String problem(Double min, Double optimal, Double max, Double maxTarget) {
        if ((min != null && min <= 0) || (optimal != null && optimal <= 0) || (max != null && max <= 0)) {
            return "the ranged ranges must be positive";
        }
        if (min != null && optimal != null && min >= optimal) {
            return "rangedMinRange (" + text(min) + ") must be below rangedOptimalRange (" + text(optimal) + ")";
        }
        if (optimal != null && max != null && optimal > max) {
            return "rangedOptimalRange (" + text(optimal) + ") must not exceed rangedMaxRange (" + text(max) + ")";
        }
        if (min != null && max != null && min >= max) {
            return "rangedMinRange (" + text(min) + ") must be below rangedMaxRange (" + text(max) + ")";
        }
        if (maxTarget != null && max != null && max > maxTarget) {
            return "rangedMaxRange (" + text(max) + ") must not exceed maxTargetDistance (" + text(maxTarget) + ")";
        }
        if (maxTarget != null && optimal != null && optimal > maxTarget) {
            return "rangedOptimalRange (" + text(optimal) + ") must not exceed maxTargetDistance (" + text(maxTarget) + ")";
        }
        return null;
    }

    /** A distance without a needless ".0": 10 rather than 10.0, but 6.5 stays 6.5. */
    public static String text(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }
}
