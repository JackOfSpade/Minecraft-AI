package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.config.EffectiveRule;
import dev.spawnbotswrapper.inhabitants.util.SplitMix64;
import dev.spawnbotswrapper.inhabitants.util.StableHash;

/**
 * The one-time decision for a structure instance and every seed derived from its {@code structureSeed}.
 * <p>
 * Everything random about a structure hangs off a single seed through fixed sub-streams, so that in
 * deterministic mode the whole outcome (roll, count, names, bot seeds, positions) is a function of the
 * structure identity alone, and in normal mode it can still be re-derived from what was persisted:
 * <pre>
 *   stream 1            the roll: uniform u (occupied iff u &lt; chance), then the bot count
 *   stream 2 + attempt  where the bots stand (one stream per population attempt)
 *   0x100 + index       the seed of bot #index (names and profile)
 * </pre>
 *
 * @param structureSeed   the seed everything derives from
 * @param chance          the effective occupied chance in force
 * @param roll            the uniform roll in [0,1) that was compared to it (recorded even when forced)
 * @param occupied        the verdict
 * @param botCount        how many bots an occupied structure gets (drawn even for abandoned rolls so that
 *                        forcing a structure occupied later stays consistent with the deterministic count)
 * @param sizeCappedMax   the ceiling {@code botCount} was actually drawn against ({@link
 *                        EffectiveRule#sizeCappedMax}); recorded for diagnostics even though it is a pure
 *                        function of {@code structureVolume} and {@code blocksPerBot}, since a debug log
 *                        reading it should not have to replay the arithmetic
 * @param structureVolume the structure's own total volume this roll was scaled against
 * @param source          {@code RANDOM}, {@code DETERMINISTIC} or {@code ADMIN_FORCED}
 */
record StructureRoll(long structureSeed, double chance, double roll, boolean occupied, int botCount,
                      int sizeCappedMax, long structureVolume, String source) {

    static final String SOURCE_FORCED = "ADMIN_FORCED";
    /** A brand-new structure recorded abandoned because the world was already at its live-bot ceiling. */
    static final String SOURCE_CAPACITY_FULL = "CAPACITY_FULL";

    private static final long ROLL_STREAM = 1L;
    private static final long POSITION_STREAM_BASE = 2L;
    private static final long BOT_STREAM_BASE = 0x100L;

    /**
     * @param structureVolume the structure's total volume (a structure with no piece data counts the
     *                        overall bounds, matching {@link
     *                        dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot#totalVolume()})
     * @param blocksPerBot    how many blocks of volume justify one more bot (see {@link
     *                        EffectiveRule#sizeCappedMax})
     * @param naturalSource   what to record when the verdict comes from the roll itself
     */
    static StructureRoll of(EffectiveRule rule, long structureSeed, long structureVolume, double blocksPerBot,
                             ForceMode mode, String naturalSource) {
        SplitMix64 rng = new SplitMix64(StableHash.combine(structureSeed, ROLL_STREAM));
        double u = rng.nextDouble();
        long effectiveVolume = Math.max(1, structureVolume);
        int effectiveMax = EffectiveRule.sizeCappedMax(effectiveVolume, blocksPerBot);
        int effectiveMin = Math.min(rule.minBots(), effectiveMax);
        int count = rng.nextIntInclusive(effectiveMin, effectiveMax);
        boolean occupied = switch (mode) {
            case ROLL -> u < rule.occupiedChance();
            case OCCUPIED -> true;
            case ABANDONED -> false;
        };
        String source = mode == ForceMode.ROLL ? naturalSource : SOURCE_FORCED;
        return new StructureRoll(structureSeed, rule.occupiedChance(), u, occupied, count, effectiveMax,
                effectiveVolume, source);
    }

    static long botSeed(long structureSeed, int index) {
        return StableHash.combine(structureSeed, BOT_STREAM_BASE + index);
    }

    /** Generator for one position search; {@code attemptsSoFar} makes every retry try different columns. */
    static SplitMix64 positionRng(long structureSeed, int attemptsSoFar) {
        return new SplitMix64(StableHash.combine(structureSeed, POSITION_STREAM_BASE + attemptsSoFar));
    }
}
