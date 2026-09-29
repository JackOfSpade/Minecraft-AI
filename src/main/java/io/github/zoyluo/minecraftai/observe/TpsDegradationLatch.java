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
 *   <li><b>separate thresholds</b>: enter above {@link #ENTER_MS}, exit only at or below {@link #EXIT_MS}
 *       (a dead band in between changes nothing);</li>
 *   <li><b>debounce</b>: the average must stay beyond the relevant threshold for
 *       {@link #ENTER_SAMPLES} / {@link #EXIT_SAMPLES} consecutive samples;</li>
 *   <li><b>minimum dwell</b>: once entered, the degraded state lasts at least
 *       {@link #MIN_DEGRADED_SAMPLES} samples, and after leaving it the normal state lasts at least
 *       {@link #MIN_NORMAL_SAMPLES}, so the state can never toggle faster than that.</li>
 * </ul>
 * A sample is one server tick (about 50 ms), so the defaults are 0.5 s / 2 s to confirm and a 5 s / 2 s
 * dwell. Not thread-safe by itself; {@link TpsGuard} synchronizes access.
 *
 * <p><b>Why these thresholds.</b> The value fed in is an EMA of the interval between consecutive
 * {@code END_SERVER_TICK} calls, not of the work done per tick. The server paces itself at 50 ms, so a healthy
 * server reads about 50 ms and can never read lower: the metric has a floor of 50 ms. This pack's normal, merely
 * busy operation reads 53-59 ms (52.87-56.9 in the measured session). The thresholds therefore sit outside that
 * band, both reachable from either side:</p>
 * <ul>
 *   <li>{@link #ENTER_MS} 62.5 ms = 16 TPS: sustained throughput below 16 TPS is genuinely degraded;</li>
 *   <li>{@link #EXIT_MS} 58 ms: above the healthy floor and inside the pack's normal band, so a server that has
 *       recovered to its usual load leaves the degraded state, yet 4.5 ms below {@link #ENTER_MS} so noise near
 *       one threshold cannot trip the other.</li>
 * </ul>
 * (An earlier draft used exit 48 ms, which is below the 50 ms floor: the latch could never have left the
 * degraded state and the mining-assist gate would have stayed denied for good.)
 */
public final class TpsDegradationLatch {
    /** 16 TPS. Strictly above this (as a smoothed average) for {@link #ENTER_SAMPLES} samples means degraded. */
    public static final double ENTER_MS = 62.5D;
    /** At or below this (as a smoothed average) for {@link #EXIT_SAMPLES} samples, after the dwell, means recovered. */
    public static final double EXIT_MS = 58.0D;
    public static final int ENTER_SAMPLES = 10;
    public static final int EXIT_SAMPLES = 40;
    public static final int MIN_DEGRADED_SAMPLES = 100;
    public static final int MIN_NORMAL_SAMPLES = 40;

    private boolean degraded;
    private int samplesInState;
    private int beyondStreak;

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
            return false;
        }
        if (degraded) {
            beyondStreak = averageTickMs <= EXIT_MS ? beyondStreak + 1 : 0;
            if (beyondStreak >= EXIT_SAMPLES && samplesInState >= MIN_DEGRADED_SAMPLES) {
                flip(false);
                return true;
            }
        } else {
            beyondStreak = averageTickMs > ENTER_MS ? beyondStreak + 1 : 0;
            if (beyondStreak >= ENTER_SAMPLES && samplesInState >= MIN_NORMAL_SAMPLES) {
                flip(true);
                return true;
            }
        }
        return false;
    }

    public boolean degraded() {
        return degraded;
    }

    /** Samples spent in the current state (saturating), for the transition log line. */
    public int samplesInState() {
        return samplesInState;
    }

    public void reset() {
        degraded = false;
        samplesInState = 0;
        beyondStreak = 0;
    }

    private void flip(boolean nowDegraded) {
        degraded = nowDegraded;
        samplesInState = 0;
        beyondStreak = 0;
    }
}
