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
 * @param structureSeed the seed everything derives from
 * @param chance        the effective occupied chance in force
 * @param roll          the uniform roll in [0,1) that was compared to it (recorded even when forced)
 * @param occupied      the verdict
 * @param botCount      how many bots an occupied structure gets (drawn even for abandoned rolls so that
 *                      forcing a structure occupied later stays consistent with the deterministic count)
 * @param sizeCappedMax the ceiling {@code botCount} was actually drawn against (min(rule.maxBots(),
 *                      size-suggested max); recorded for diagnostics even though it is a pure function
 *                      of {@code pieceCount} and the rule, since a debug log reading it should not have
 *                      to replay the arithmetic)
 * @param pieceCount    the structure's own piece count this roll was scaled against
 * @param source        {@code RANDOM}, {@code DETERMINISTIC} or {@code ADMIN_FORCED}
 */
record StructureRoll(long structureSeed, double chance, double roll, boolean occupied, int botCount,
                      int sizeCappedMax, int pieceCount, String source) {

    static final String SOURCE_FORCED = "ADMIN_FORCED";

    private static final long ROLL_STREAM = 1L;
    private static final long POSITION_STREAM_BASE = 2L;
    private static final long BOT_STREAM_BASE = 0x100L;

    /**
     * @param pieceCount    the structure's piece count (a structure with no piece data counts as one,
     *                      matching {@link dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot#sampleBoxes()})
     * @param naturalSource what to record when the verdict comes from the roll itself
     */
    static StructureRoll of(EffectiveRule rule, long structureSeed, int pieceCount, ForceMode mode, String naturalSource) {
        SplitMix64 rng = new SplitMix64(StableHash.combine(structureSeed, ROLL_STREAM));
        double u = rng.nextDouble();
        int effectivePieces = Math.max(1, pieceCount);
        // A structure smaller than the configured range pulls the ceiling DOWN, never up: maxBots stays
        // the absolute cap an operator set, and piecesPerBot only makes a small structure draw from a
        // narrower range than a big one sharing the same tag, without needing a separate override per size.
        int sizeSuggestedMax = (int) Math.min(Integer.MAX_VALUE,
                Math.max(1, Math.ceil(effectivePieces / rule.piecesPerBot())));
        int effectiveMax = Math.min(rule.maxBots(), sizeSuggestedMax);
        int effectiveMin = Math.min(rule.minBots(), effectiveMax);
        int count = rng.nextIntInclusive(effectiveMin, effectiveMax);
        boolean occupied = switch (mode) {
            case ROLL -> u < rule.occupiedChance();
            case OCCUPIED -> true;
            case ABANDONED -> false;
        };
        String source = mode == ForceMode.ROLL ? naturalSource : SOURCE_FORCED;
        return new StructureRoll(structureSeed, rule.occupiedChance(), u, occupied, count, effectiveMax,
                effectivePieces, source);
    }

    static long botSeed(long structureSeed, int index) {
        return StableHash.combine(structureSeed, BOT_STREAM_BASE + index);
    }

    /** Generator for one position search; {@code attemptsSoFar} makes every retry try different columns. */
    static SplitMix64 positionRng(long structureSeed, int attemptsSoFar) {
        return new SplitMix64(StableHash.combine(structureSeed, POSITION_STREAM_BASE + attemptsSoFar));
    }
}
