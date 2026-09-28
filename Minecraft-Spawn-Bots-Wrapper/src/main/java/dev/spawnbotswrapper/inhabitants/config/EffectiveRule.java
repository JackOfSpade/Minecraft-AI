package dev.spawnbotswrapper.inhabitants.config;

/**
 * The fully resolved rule for one structure: where each value came from is kept for
 * {@code /inhabitants} diagnostics and debug logs.
 * <p>
 * There is no resolved {@code maxBots} here: the ceiling for one structure INSTANCE depends on its own
 * geometry (see {@link #sizeCappedMax}), which this type -- resolved purely from a structure id and its
 * tags -- has no way to know. Callers that also have a {@link
 * dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot} in hand combine the two.
 */
public record EffectiveRule(
        double occupiedChance,
        int minBots,
        String occupiedChanceFrom,
        String minBotsFrom) {

    /** Hard cap on bots for one structure, whatever its size or the config says. */
    public static final int MAX_BOTS_PER_STRUCTURE = 64;

    /** Whether occupiedChance and minBots come from the same source (for compact display). */
    public boolean singleSource() {
        return occupiedChanceFrom.equals(minBotsFrom);
    }

    /**
     * The bot-count ceiling for one structure instance of the given total volume: bigger structures
     * support more bots, up to {@link #MAX_BOTS_PER_STRUCTURE}. A non-positive, NaN or infinite
     * {@code blocksPerBot} (a bad hand-edited config value) is treated as a very fine-grained default
     * rather than dividing by zero or inverting the scaling.
     */
    public static int sizeCappedMax(long structureVolume, double blocksPerBot) {
        double safeBlocksPerBot = Double.isNaN(blocksPerBot) || Double.isInfinite(blocksPerBot) || blocksPerBot <= 0.0
                ? 0.1
                : blocksPerBot;
        long effectiveVolume = Math.max(1, structureVolume);
        long suggested = (long) Math.ceil(effectiveVolume / safeBlocksPerBot);
        return (int) Math.max(1, Math.min(MAX_BOTS_PER_STRUCTURE, suggested));
    }

    @Override
    public String toString() {
        return "occupiedChance=" + occupiedChance + " (" + occupiedChanceFrom + "), minBots="
                + minBots + " (" + minBotsFrom + "), maxBots=size-scaled up to " + MAX_BOTS_PER_STRUCTURE;
    }
}
