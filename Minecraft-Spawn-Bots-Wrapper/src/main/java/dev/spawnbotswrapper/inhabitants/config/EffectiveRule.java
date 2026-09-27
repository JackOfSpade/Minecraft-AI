package dev.spawnbotswrapper.inhabitants.config;

/**
 * The fully resolved rule for one structure: where each value came from is kept for
 * {@code /inhabitants} diagnostics and debug logs.
 */
public record EffectiveRule(
        double occupiedChance,
        int minBots,
        int maxBots,
        String occupiedChanceFrom,
        String minBotsFrom,
        String maxBotsFrom) {

    /** Hard cap on bots for one structure, whatever the config says. */
    public static final int MAX_BOTS_PER_STRUCTURE = 64;

    /** Whether all three values come from the same source (for compact display). */
    public boolean singleSource() {
        return occupiedChanceFrom.equals(minBotsFrom) && minBotsFrom.equals(maxBotsFrom);
    }

    @Override
    public String toString() {
        return "occupiedChance=" + occupiedChance + " (" + occupiedChanceFrom + "), bots="
                + minBots + " (" + minBotsFrom + ")-" + maxBots + " (" + maxBotsFrom + ")";
    }
}
