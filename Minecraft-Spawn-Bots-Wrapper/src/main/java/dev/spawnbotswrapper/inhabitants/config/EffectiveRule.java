package dev.spawnbotswrapper.inhabitants.config;

/**
 * The fully resolved rule for one structure: where each value came from is kept for
 * {@code /inhabitants} diagnostics and debug logs.
 */
public record EffectiveRule(
        double occupiedChance,
        int minBots,
        int maxBots,
        double piecesPerBot,
        String occupiedChanceFrom,
        String minBotsFrom,
        String maxBotsFrom,
        String piecesPerBotFrom) {

    /** Hard cap on bots for one structure, whatever the config says. */
    public static final int MAX_BOTS_PER_STRUCTURE = 64;

    /**
     * Whether occupiedChance, minBots and maxBots come from the same source (for compact display).
     * {@code piecesPerBot} is deliberately not part of this: it is not shown on the same line as the
     * bot-count range, so a config that only overrides it must not force the verbose per-value display.
     */
    public boolean singleSource() {
        return occupiedChanceFrom.equals(minBotsFrom) && minBotsFrom.equals(maxBotsFrom);
    }

    @Override
    public String toString() {
        return "occupiedChance=" + occupiedChance + " (" + occupiedChanceFrom + "), bots="
                + minBots + " (" + minBotsFrom + ")-" + maxBots + " (" + maxBotsFrom + "), piecesPerBot="
                + piecesPerBot + " (" + piecesPerBotFrom + ")";
    }
}
