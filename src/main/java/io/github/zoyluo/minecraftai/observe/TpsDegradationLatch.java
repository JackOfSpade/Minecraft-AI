package io.github.zoyluo.minecraftai.observe;

/**
 * Hysteresis state machine behind {@link TpsGuard}'s degraded verdict. Pure: it is fed one smoothed
 * tick-time average per server tick and never reads a clock.
 *
 * <p>The old verdict was the raw comparison {@code average > 55 ms}, so an average hovering around the
 * threshold flipped the state several times per second (278 {@code tps_guard_state} events in one real
 * session, each flip also toggling the mining-assist gate between {@code tps_degraded} and enabled).
 * This latch adds three things:</p>
 * <ul>
 *   <li><b>separate, baseline-adaptive thresholds</b>: enter above {@link #enterLevel()}, exit only at or below
 *       {@link #exitLevel()} (a dead band in between changes nothing);</li>
 *   <li><b>debounce</b>: the average must stay beyond the relevant threshold for
 *       {@link #ENTER_SAMPLES} / {@link #EXIT_SAMPLES} consecutive samples;</li>
 *   <li><b>minimum dwell</b>: once entered, the degraded state lasts at least
 *       {@link #MIN_DEGRADED_SAMPLES} samples, and after leaving it the normal state lasts at least
 *       {@link #MIN_NORMAL_SAMPLES}, so the state can never toggle faster than that.</li>
 * </ul>
 * A sample is one server tick (about 50 ms), so the defaults are 0.5 s / 2 s to confirm and a 5 s / 2 s
 * dwell. Not thread-safe by itself; {@link TpsGuard} synchronizes access.
 *
 * <p><b>Why adaptive thresholds.</b> The value fed in is an EMA of the interval between consecutive
 * {@code END_SERVER_TICK} calls, not of the work done per tick. The server paces itself at 50 ms, so a healthy
 * server reads about 50 ms and can never read lower: the metric has a floor of 50 ms. This pack's normal, merely
 * busy operation reads 53-59 ms (52.87-56.9 in the measured session). A fixed exit level of 58 ms could never be
 * reached by a server that recovered to a steady 59 ms, so the latch would stay degraded for good. It therefore
 * works like the wrapper's {@code TickHealth}: it learns the server's own baseline (from healthy samples only,
 * each capped at {@link #BASELINE_MAX_MS}) and uses</p>
 * <ul>
 *   <li>enter level {@code max(}{@link #ENTER_FLOOR_MS}{@code , baseline x }{@link #ENTER_FACTOR}{@code )};</li>
 *   <li>exit level {@code max(}{@link #EXIT_FLOOR_MS}{@code , baseline x }{@link #EXIT_FACTOR}{@code )}, never
 *       closer than {@link #MIN_HYSTERESIS_MS} to the enter level and always above the 50 ms floor, so it is
 *       reachable from a server that is steady at its own baseline.</li>
 * </ul>
 * <p><b>Re-learning while degraded.</b> The baseline is learned only while not degraded, so one learned on an idle
 * server (about 50 ms, before the bots load) gives exit level 58 ms; a server that then spikes past the enter level
 * and settles at its real busy 59 ms would never satisfy the exit and stay degraded for good. So while degraded,
 * when the samples stay at or below the enter level for {@link #RELEARN_SAMPLES} in a row (about 60 s), their mean
 * (each sample capped at {@link #BASELINE_MAX_MS}, like every baseline sample) becomes the new baseline and the exit
 * level rises above the new normal. A genuinely slow server, steady at or above the enter level, never builds such a
 * streak and stays degraded.</p>
 *
 * (An earlier draft used exit 48 ms, which is below the 50 ms floor: the latch could never have left the
 * degraded state and the mining-assist gate would have stayed denied for good.)
 */
public final class TpsDegradationLatch {
    /** Enter level floor (about 15.4 TPS): used until a baseline exists and whenever baseline x factor is lower. */
    public static final double ENTER_FLOOR_MS = 65.0D;
    /** Enter level, adaptive part: multiple of the learned baseline. */
    public static final double ENTER_FACTOR = 1.25D;
    /** Exit level floor: above the 50 ms metric floor and inside the pack's normal band. */
    public static final double EXIT_FLOOR_MS = 58.0D;
    /** Exit level, adaptive part: multiple of the learned baseline. */
    public static final double EXIT_FACTOR = 1.10D;
    /** Each baseline sample is capped here, so a server that lives its whole life struggling cannot teach itself that awful is normal. */
    public static final double BASELINE_MAX_MS = 60.0D;
    /** The exit level is always at least this far below the enter level so the two can never coincide. */
    public static final double MIN_HYSTERESIS_MS = 2.0D;
    /** Time constant of the baseline in samples (about 5 minutes); the first samples are a plain running mean. */
    public static final int BASELINE_WINDOW_SAMPLES = 6000;
    public static final int ENTER_SAMPLES = 10;
    public static final int EXIT_SAMPLES = 40;
    public static final int MIN_DEGRADED_SAMPLES = 100;
    public static final int MIN_NORMAL_SAMPLES = 40;
    /** While degraded, this many consecutive samples at or below the enter level (about 60 s) re-learn the baseline. */
    public static final int RELEARN_SAMPLES = 1200;

    private boolean degraded;
    private int samplesInState;
    private int beyondStreak;
    private double baseline = Double.NaN;
    private int baselineSamples;
    // Degraded-state re-learn: the current run of samples at or below the enter level, and their capped sum.
    private int calmStreak;
    private double calmSum;
    private int relearnCount;

    /**
     * Feeds one smoothed average tick time in milliseconds.
     *
     * @return true when this sample flipped the state (the caller logs exactly these transitions)
     */
    public boolean update(double averageTickMs) {
        if (samplesInState < Integer.MAX_VALUE) {
            samplesInState++;
        }
        if (!Double.isFinite(averageTickMs)) {
            beyondStreak = 0;
            calmStreak = 0;
            calmSum = 0.0D;
            return false;
        }
        if (degraded) {
            relearnWhileDegraded(averageTickMs);
            beyondStreak = averageTickMs <= exitLevel() ? beyondStreak + 1 : 0;
            if (beyondStreak >= EXIT_SAMPLES && samplesInState >= MIN_DEGRADED_SAMPLES) {
                flip(false);
                return true;
            }
        } else {
            if (averageTickMs > enterLevel()) {
                beyondStreak++;
            } else {
                beyondStreak = 0;
                learn(averageTickMs);
            }
            if (beyondStreak >= ENTER_SAMPLES && samplesInState >= MIN_NORMAL_SAMPLES) {
                flip(true);
                return true;
            }
        }
        return false;
    }

    /** Level above which (sustained) the server counts as degraded: {@code max(65, baseline x 1.25)}. */
    public double enterLevel() {
        double byBaseline = Double.isNaN(baseline) ? 0.0D : baseline * ENTER_FACTOR;
        return Math.max(ENTER_FLOOR_MS, byBaseline);
    }

    /** Level at or below which (sustained) a degraded server counts as recovered; always below {@link #enterLevel()}. */
    public double exitLevel() {
        double byBaseline = Double.isNaN(baseline) ? 0.0D : baseline * EXIT_FACTOR;
        return Math.min(Math.max(EXIT_FLOOR_MS, byBaseline), enterLevel() - MIN_HYSTERESIS_MS);
    }

    /** The learned typical smoothed tick time, or NaN before the first healthy sample. */
    public double baselineMs() {
        return baseline;
    }

    public boolean degraded() {
        return degraded;
    }

    /** How many times the baseline was re-adopted while degraded (the caller logs when this grows). */
    public int relearnCount() {
        return relearnCount;
    }

    /** Samples spent in the current state (saturating), for the transition log line. */
    public int samplesInState() {
        return samplesInState;
    }

    public void reset() {
        degraded = false;
        samplesInState = 0;
        beyondStreak = 0;
        baseline = Double.NaN;
        baselineSamples = 0;
        calmStreak = 0;
        calmSum = 0.0D;
        relearnCount = 0;
    }

    /**
     * Tracks the run of samples that stay at or below the enter level while degraded. A long enough run means
     * the server settled at a level that is normal for it (just not what the baseline, learned earlier while it
     * was idle, expected): its capped mean becomes the baseline so the exit level rises above that level. A single
     * sample above the enter level restarts the run, so a server that really is slow never gets here.
     */
    private void relearnWhileDegraded(double averageTickMs) {
        if (averageTickMs > enterLevel()) {
            calmStreak = 0;
            calmSum = 0.0D;
            return;
        }
        calmStreak++;
        calmSum += Math.min(averageTickMs, BASELINE_MAX_MS);
        if (calmStreak >= RELEARN_SAMPLES) {
            baseline = calmSum / calmStreak;
            baselineSamples = calmStreak;
            calmStreak = 0;
            calmSum = 0.0D;
            relearnCount++;
        }
    }

    /**
     * Learns the server's own typical tick time, only from healthy samples (never while degraded, never from a
     * sample above the enter level, each capped at {@link #BASELINE_MAX_MS}): a running mean for the first
     * samples, then an EMA with a {@link #BASELINE_WINDOW_SAMPLES} time constant.
     */
    private void learn(double averageTickMs) {
        double sample = Math.min(averageTickMs, BASELINE_MAX_MS);
        if (baselineSamples < Integer.MAX_VALUE) {
            baselineSamples++;
        }
        if (Double.isNaN(baseline)) {
            baseline = sample;
            return;
        }
        double alpha = Math.max(1.0D / BASELINE_WINDOW_SAMPLES, 1.0D / baselineSamples);
        baseline += alpha * (sample - baseline);
    }

    private void flip(boolean nowDegraded) {
        degraded = nowDegraded;
        samplesInState = 0;
        beyondStreak = 0;
        calmStreak = 0;
        calmSum = 0.0D;
    }
}
