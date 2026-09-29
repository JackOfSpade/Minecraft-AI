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
 */
public final class TpsDegradationLatch {
    public static final double ENTER_MS = 55.0D;
    public static final double EXIT_MS = 48.0D;
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
