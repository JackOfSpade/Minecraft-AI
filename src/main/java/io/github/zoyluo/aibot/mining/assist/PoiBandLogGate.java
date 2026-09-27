package io.github.zoyluo.aibot.mining.assist;

/**
 * Rate limit of the {@code assist_poi_band} shadow line of one bot. The band is scored from noisy, aged
 * evidence, so a bot standing near a threshold could flip it on every evaluation (once per second) and write
 * a long line each time. The gate keeps the log to what a reader needs: the band the bot is in, at most one
 * line per {@value #MIN_GAP_TICKS} ticks, and the number of changes it withheld in between. A change that
 * flips back within the gap costs no line at all, and a change that is still in effect when the gap has
 * passed is logged then (deferred, not lost). Pure and clock-free: time is the server tick the caller passes.
 */
public final class PoiBandLogGate {
    /** Fewest ticks between two band lines of one bot (ten seconds). */
    public static final int MIN_GAP_TICKS = 200;
    /** Returned by {@link #consider} when no line is due. */
    public static final int NO_LINE = -1;

    private static final int NEVER = Integer.MIN_VALUE;

    private PoiScorer.Band lastLogged = PoiScorer.Band.NONE;
    private int lastLogTick = NEVER;
    private int withheld;

    /**
     * @param band    the band of this evaluation
     * @param changed whether it differs from the previous evaluation's band
     * @param tick    the server tick of the evaluation
     * @return {@link #NO_LINE}, or the number of band changes withheld since the previous line (0 or more)
     *         when a line is due now; in that case the gate records the band as logged
     */
    public int consider(PoiScorer.Band band, boolean changed, int tick) {
        PoiScorer.Band current = band == null ? PoiScorer.Band.NONE : band;
        if (current == lastLogged) {
            withheld = 0;
            return NO_LINE;
        }
        boolean gapPassed = lastLogTick == NEVER || tick < lastLogTick || tick - lastLogTick >= MIN_GAP_TICKS;
        if (!gapPassed) {
            if (changed) {
                withheld++;
            }
            return NO_LINE;
        }
        int missed = withheld;
        withheld = 0;
        lastLogged = current;
        lastLogTick = tick;
        return missed;
    }

    /** The band of the most recent line (NONE before the first). */
    public PoiScorer.Band lastLoggedBand() {
        return lastLogged;
    }

    /** Forgets everything (dimension change, state reset). */
    public void reset() {
        lastLogged = PoiScorer.Band.NONE;
        lastLogTick = NEVER;
        withheld = 0;
    }
}
