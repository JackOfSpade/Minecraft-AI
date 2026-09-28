package io.github.zoyluo.aibot.mining.assist;

/**
 * Server tick work headroom for acting features (design 2.1, 3.4). Pure state machine: the caller
 * measures how many milliseconds of work a server tick consumed and feeds it to {@link #record};
 * the current TPS-degraded verdict is passed to each query. Nothing here reads a clock.
 *
 * <p>Signals, all on an exponential moving average of tick work (alpha 0.1):</p>
 * <ul>
 *   <li><b>Start gate</b> ({@link #canStart}): a detour or frontier may start only when TPS is not
 *       degraded, the average is at most {@code startWorkMs} (38) and the feature is armed.</li>
 *   <li><b>Abort</b> ({@link #shouldAbort}): an in-flight detour aborts when TPS is degraded or the
 *       average has been above {@code abortWorkMs} (48) for {@code abortTicks} (20) consecutive
 *       recorded ticks.</li>
 *   <li><b>Hysteresis</b>: after an abort the feature is disarmed and re-arms only once the average
 *       has been at most {@code rearmWorkMs} (40, or the abort threshold if that is set lower) for
 *       {@code rearmTicks} (200) consecutive recorded ticks. This prevents start/abort
 *       oscillation.</li>
 *   <li><b>Ray throttle</b> ({@link #halveRays}): true once the average exceeds {@code halveWorkMs}
 *       (44), released by the same 200-tick calm rule. The sensor halves its per-tick ray budget
 *       while it is true.</li>
 * </ul>
 *
 * <p>In {@code deterministic} mode nothing time-derived influences a decision: the work-average
 * start gate and the ray throttle never trigger and the work-average abort never fires. The
 * TPS-degraded verdict still does: {@code canStart(true)} is false and {@code shouldAbort(true)}
 * is true, matching the design rule that the TpsGuard latch stays live (tests drive it through an
 * override). The average is still tracked for observability.</p>
 *
 * <p>Not thread-safe; owned and driven by the server thread.</p>
 */
public final class TickHeadroom {
    public static final double EMA_ALPHA = 0.1D;

    /** Thresholds and tick counts. {@link #DEFAULTS} are the design values. */
    public record Thresholds(
            double startWorkMs,
            double abortWorkMs,
            double halveWorkMs,
            double rearmWorkMs,
            int abortTicks,
            int rearmTicks) {

        public static final Thresholds DEFAULTS = new Thresholds(38.0D, 48.0D, 44.0D, 40.0D, 20, 200);

        public Thresholds {
            if (!isPositiveFinite(startWorkMs) || !isPositiveFinite(abortWorkMs)
                    || !isPositiveFinite(halveWorkMs) || !isPositiveFinite(rearmWorkMs)) {
                throw new IllegalArgumentException("thresholds must be positive and finite");
            }
            if (startWorkMs > abortWorkMs) {
                throw new IllegalArgumentException("startWorkMs must not exceed abortWorkMs");
            }
            if (rearmWorkMs >= halveWorkMs || rearmWorkMs > abortWorkMs) {
                throw new IllegalArgumentException("rearmWorkMs must be below halveWorkMs and not above abortWorkMs");
            }
            if (abortTicks < 1 || rearmTicks < 1) {
                throw new IllegalArgumentException("tick counts must be at least 1");
            }
        }

        /**
         * Design defaults with the two configurable thresholds replaced. Throws when non-finite,
         * non-positive, or when start is above abort.
         *
         * <p>The re-arm threshold is the design's 40 ms, lowered to {@code abortWorkMs} when the
         * abort threshold is set below it: a stricter abort must never be re-armed by a calm level
         * that it would itself call overloaded, and falling back to the (laxer) defaults instead
         * would silently discard the stricter setting.</p>
         */
        public static Thresholds of(double startWorkMs, double abortWorkMs) {
            return new Thresholds(startWorkMs, abortWorkMs, DEFAULTS.halveWorkMs,
                    Math.min(DEFAULTS.rearmWorkMs, abortWorkMs), DEFAULTS.abortTicks, DEFAULTS.rearmTicks);
        }

        /**
         * Like {@link #of} but never throws: an invalid pair falls back to the design defaults.
         * Invalid means non-finite, non-positive, or start above abort. Intended for untrusted
         * config values.
         */
        public static Thresholds sanitized(double startWorkMs, double abortWorkMs) {
            try {
                return of(startWorkMs, abortWorkMs);
            } catch (IllegalArgumentException invalid) {
                return DEFAULTS;
            }
        }

        private static boolean isPositiveFinite(double value) {
            return Double.isFinite(value) && value > 0.0D;
        }
    }

    private final boolean deterministic;
    private final Thresholds thresholds;
    private final Ema workEma = new Ema(EMA_ALPHA);

    /** Consecutive recorded ticks with the average above the abort threshold. */
    private int overAbortStreak;
    /** Consecutive recorded ticks with the average at or below the re-arm threshold. */
    private int calmStreak;
    /** Like {@link #calmStreak} but restarted whenever the feature is (re)disarmed. */
    private int armCalmStreak;
    private boolean disarmed;
    private boolean halving;

    public TickHeadroom(boolean deterministic) {
        this(deterministic, Thresholds.DEFAULTS);
    }

    public TickHeadroom(boolean deterministic, Thresholds thresholds) {
        if (thresholds == null) {
            throw new IllegalArgumentException("thresholds");
        }
        this.deterministic = deterministic;
        this.thresholds = thresholds;
    }

    /**
     * Records the work one server tick consumed, in milliseconds. Call once per tick, at the end of
     * the tick. A negative value counts as zero; a non-finite value is ignored (the tick is not
     * counted).
     */
    public void record(double workMs) {
        if (!Double.isFinite(workMs)) {
            return;
        }
        double ema = workEma.update(Math.max(0.0D, workMs));

        // Streaks saturate at the count they are compared with, so a server that stays healthy for
        // years cannot overflow them into a negative number.
        overAbortStreak = ema > thresholds.abortWorkMs()
                ? bump(overAbortStreak, thresholds.abortTicks())
                : 0;
        if (overAbortStreak >= thresholds.abortTicks()) {
            disarm();
        }
        if (ema > thresholds.halveWorkMs()) {
            halving = true;
        }

        if (ema <= thresholds.rearmWorkMs()) {
            calmStreak = bump(calmStreak, thresholds.rearmTicks());
            armCalmStreak = bump(armCalmStreak, thresholds.rearmTicks());
        } else {
            calmStreak = 0;
            armCalmStreak = 0;
        }
        if (disarmed && armCalmStreak >= thresholds.rearmTicks()) {
            disarmed = false;
        }
        if (halving && calmStreak >= thresholds.rearmTicks()) {
            halving = false;
        }
    }

    /**
     * True when a detour or frontier may start now: TPS is not degraded, the average is at most the
     * start threshold, and the feature is armed. In deterministic mode only the TPS verdict counts.
     */
    public boolean canStart(boolean tpsDegraded) {
        if (tpsDegraded) {
            return false;
        }
        if (deterministic) {
            return true;
        }
        return !disarmed && workEma.value() <= thresholds.startWorkMs();
    }

    /**
     * True when an in-flight detour must abort: TPS is degraded, or (outside deterministic mode) the
     * average has been above the abort threshold for the required consecutive ticks.
     *
     * <p>This is the abort decision point, so it has one side effect: a true result disarms the
     * feature and restarts the re-arm count, so a new start needs the 200-tick calm period. It is
     * idempotent and safe to call for several bots in one tick. Call it only while a detour is live,
     * otherwise a degraded-TPS episode delays the next start by the re-arm period for nothing.</p>
     */
    public boolean shouldAbort(boolean tpsDegraded) {
        if (tpsDegraded) {
            disarm();
            return true;
        }
        if (deterministic) {
            return false;
        }
        return overAbortStreak >= thresholds.abortTicks();
    }

    /**
     * True while the sensor should halve its per-tick ray budget: latched when the average exceeds
     * the throttle threshold, released after the calm period. Always false in deterministic mode.
     */
    public boolean halveRays() {
        return !deterministic && halving;
    }

    /** Current average of tick work in milliseconds (0.0 before the first sample). */
    public double workEma() {
        return workEma.value();
    }

    /** False after an abort until the calm period has elapsed. */
    public boolean isArmed() {
        return !disarmed;
    }

    public boolean isDeterministic() {
        return deterministic;
    }

    public Thresholds thresholds() {
        return thresholds;
    }

    /** Forgets every sample and latch (used when world runtime state is cleared). */
    public void reset() {
        workEma.reset();
        overAbortStreak = 0;
        calmStreak = 0;
        armCalmStreak = 0;
        disarmed = false;
        halving = false;
    }

    private void disarm() {
        disarmed = true;
        armCalmStreak = 0;
    }

    private static int bump(int streak, int cap) {
        return streak < cap ? streak + 1 : cap;
    }
}
