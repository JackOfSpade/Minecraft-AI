package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;

/**
 * The pure state machine behind {@link TpsGovernor}: decides, from a series of smoothed tick-time readings,
 * whether the server is genuinely degraded. No Minecraft, no logging, no clock of its own (the caller passes
 * the tick), so every number can be driven from a unit test.
 * <p>
 * Why it is shaped like this. The metric fed in is the rolling average wall-clock time between two server ticks
 * ({@link TpsGateway}). It can never read below ~50 ms (20 TPS), and a modded pack that is merely busy idles at
 * 53-59 ms. A fixed 55.6 ms threshold (the old design) therefore sat INSIDE the normal band and despawned
 * inhabitants for nothing. This machine instead:
 * <ul>
 *   <li>compares against an <b>enter level</b> {@code max(degradedFloorMillis, baseline x degradedFactor)}, where
 *       the baseline is the server's own slow-moving typical tick time (learned only while not degraded and only
 *       from readings that are not themselves excessive, capped at {@code baselineMaxMillis});</li>
 *   <li>needs the reading to stay above that level, uninterrupted, for {@code sustainTicks} before it degrades;</li>
 *   <li>recovers only below a separate, lower <b>exit level</b> {@code max(recoveredFloorMillis, baseline x
 *       recoveredFactor)} (hysteresis; always at least 2 ms under the enter level and above the ~50 ms floor, so it
 *       is reachable), held for {@code sustainTicks}, and only after {@code minDwellTicks} in the degraded state.</li>
 * </ul>
 * With the defaults a normal 50-60 ms server never gets near the 65 ms+ enter level.
 */
final class TickHealth {

    enum State {NORMAL, DEGRADED}

    /** A change of state, with every number that led to it, ready to be logged. */
    record Transition(State from, State to, long tick, double averageMillis, double baselineMillis,
                      double enterMillis, double exitMillis, long heldTicks) {
    }

    /** Distance kept between the exit and the enter level so the two are never the same number. */
    static final double MIN_HYSTERESIS_MILLIS = 2.0;

    private State state = State.NORMAL;
    private double baseline = Double.NaN;
    private int baselineSamples;
    private long stateSince = Long.MIN_VALUE;
    /** Tick from which the reading has been beyond the level that would flip the state; -1 while it is not. */
    private long beyondSince = -1;

    State state() {
        return state;
    }

    /** The learned typical tick time, or NaN before the first usable reading. */
    double baselineMillis() {
        return baseline;
    }

    boolean degraded() {
        return state == State.DEGRADED;
    }

    /** Level above which (sustained) the server counts as degraded. */
    double enterLevel(InhabitantsConfig.TpsThrottle t) {
        double byBaseline = Double.isNaN(baseline) ? 0.0 : baseline * t.degradedFactor;
        return Math.max(t.degradedFloorMillis, byBaseline);
    }

    /** Level at or below which (sustained) a degraded server counts as recovered; always below the enter level. */
    double exitLevel(InhabitantsConfig.TpsThrottle t) {
        double byBaseline = Double.isNaN(baseline) ? 0.0 : baseline * t.recoveredFactor;
        double exit = Math.max(t.recoveredFloorMillis, byBaseline);
        return Math.min(exit, enterLevel(t) - MIN_HYSTERESIS_MILLIS);
    }

    /** Back to the initial state (feature switched off, or a new world). The baseline is forgotten too. */
    void reset() {
        state = State.NORMAL;
        baseline = Double.NaN;
        baselineSamples = 0;
        stateSince = Long.MIN_VALUE;
        beyondSince = -1;
    }

    /** Only the state is cleared (throttle disabled meanwhile); what was learned about the server stays. */
    void clearState() {
        state = State.NORMAL;
        stateSince = Long.MIN_VALUE;
        beyondSince = -1;
    }

    /**
     * Feeds one reading.
     *
     * @param now       the current tick
     * @param avgMillis smoothed ms per tick; a negative value means "no data" and is ignored
     * @return the transition this reading caused, or null when the state did not change
     */
    Transition observe(long now, double avgMillis, InhabitantsConfig.TpsThrottle t) {
        if (avgMillis < 0 || Double.isNaN(avgMillis)) {
            return null;
        }
        if (stateSince == Long.MIN_VALUE) {
            stateSince = now;
        }
        double enter = enterLevel(t);
        double exit = exitLevel(t);
        Transition result = null;
        if (state == State.NORMAL) {
            if (avgMillis > enter) {
                if (beyondSince < 0) {
                    beyondSince = now;
                }
                if (now - beyondSince >= Math.max(0, t.sustainTicks)) {
                    result = new Transition(State.NORMAL, State.DEGRADED, now, avgMillis, baseline, enter, exit,
                            now - beyondSince);
                }
            } else {
                beyondSince = -1;
                learn(avgMillis, t);
            }
        } else {
            if (avgMillis <= exit) {
                if (beyondSince < 0) {
                    beyondSince = now;
                }
                boolean held = now - beyondSince >= Math.max(0, t.sustainTicks);
                boolean dwelled = now - stateSince >= Math.max(0, t.minDwellTicks);
                if (held && dwelled) {
                    result = new Transition(State.DEGRADED, State.NORMAL, now, avgMillis, baseline, enter, exit,
                            now - beyondSince);
                }
            } else {
                beyondSince = -1;
            }
        }
        if (result != null) {
            state = result.to();
            stateSince = now;
            beyondSince = -1;
        }
        return result;
    }

    /**
     * A plain running mean for the first readings (fast convergence after start-up), then an exponential moving
     * average whose time constant is {@code baselineWindowTicks}. Each reading is capped at {@code baselineMaxMillis}.
     */
    private void learn(double avgMillis, InhabitantsConfig.TpsThrottle t) {
        double sample = Math.min(avgMillis, t.baselineMaxMillis);
        baselineSamples++;
        if (Double.isNaN(baseline)) {
            baseline = sample;
            return;
        }
        double steady = Math.min(1.0, Math.max(1, t.checkIntervalTicks) / (double) Math.max(1, t.baselineWindowTicks));
        double alpha = Math.max(steady, 1.0 / baselineSamples);
        baseline += alpha * (sample - baseline);
    }
}
