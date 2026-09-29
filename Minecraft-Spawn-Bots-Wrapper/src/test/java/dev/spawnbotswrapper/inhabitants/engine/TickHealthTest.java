package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.engine.TickHealth.State;
import dev.spawnbotswrapper.inhabitants.engine.TickHealth.Transition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lag governor's state machine with REALISTIC numbers: this pack's normal tick time is 53-59 ms (the metric can
 * never read below ~50), so 50-60 must never degrade, 70-100 must, and recovery must be reachable.
 */
class TickHealthTest {

    private static final int CHECK = 100;

    private final InhabitantsConfig.TpsThrottle t = new InhabitantsConfig.TpsThrottle();
    private final TickHealth health = new TickHealth();
    private long now;

    /** One governor check, {@code CHECK} ticks after the previous one. */
    private Transition check(double avgMillis) {
        Transition tr = health.observe(now, avgMillis, t);
        now += CHECK;
        return tr;
    }

    /** Feeds a repeating pattern for {@code checks} checks and returns the first transition seen, or null. */
    private Transition feed(int checks, double... pattern) {
        Transition first = null;
        for (int i = 0; i < checks; i++) {
            Transition tr = check(pattern[i % pattern.length]);
            if (first == null) {
                first = tr;
            }
        }
        return first;
    }

    @Test
    void theDefaultLevelsSitAboveTheNormalBusyBandAndHaveReachableHysteresis() {
        assertTrue(t.degradedFloorMillis >= 65.0);
        assertTrue(t.recoveredFloorMillis < t.degradedFloorMillis);
        for (double baseline : new double[]{50.0, 53.0, 55.0, 57.0, 59.0, 60.0}) {
            TickHealth h = new TickHealth();
            h.observe(0, baseline, t);
            double enter = h.enterLevel(t);
            double exit = h.exitLevel(t);
            assertTrue(enter > 59.0 + 5, "enter level " + enter + " must clear the 53-59 ms normal band by a margin");
            assertTrue(exit < enter - 1.99, "exit " + exit + " must be below enter " + enter);
            assertTrue(exit > 50.0, "exit " + exit + " must be reachable above the ~50 ms metric floor");
        }
    }

    @Test
    void aServerThatIdlesBetween50And60MillisNeverDegradesNoMatterHowLong() {
        // A deterministic wobble through the whole normal band, ten hours of checks.
        double[] wobble = {50.2, 53.1, 58.9, 55.5, 59.7, 51.0, 57.3, 54.4, 56.8, 59.9, 50.0, 52.7};
        for (int i = 0; i < 6000; i++) {
            assertNull(check(wobble[i % wobble.length]), "check " + i + " must not change the state");
        }
        assertEquals(State.NORMAL, health.state());
        assertFalse(health.degraded());
        assertEquals(55.0, health.baselineMillis(), 3.0, "the baseline is the server's own typical tick time");
    }

    @Test
    void theOldFixedThresholdWouldHaveShedAtNormalLoad() {
        // 55.6 ms was the old "degraded" level; a normal busy pack sits above it all the time.
        for (int i = 0; i < 500; i++) {
            assertNull(check(57.0));
        }
        assertFalse(health.degraded());
    }

    @Test
    void aLoadSpikeShorterThanTheSustainWindowIsIgnored() {
        feed(20, 55.0);
        // 5 checks of 100 ms, then normal again: 5 x 100 = 500 ticks < sustainTicks (600).
        for (int i = 0; i < 5; i++) {
            assertNull(check(100.0));
        }
        assertNull(check(55.0));
        assertEquals(State.NORMAL, health.state());
        // ... and the excess clock restarted: another short spike is also ignored.
        for (int i = 0; i < 5; i++) {
            assertNull(check(90.0));
        }
        assertFalse(health.degraded());
    }

    @Test
    void sustainedOverloadDegradesOnlyOnceTheWindowHasElapsedWithTheNumbers() {
        feed(20, 55.0);
        long startedAt = now;
        Transition tr = null;
        int checks = 0;
        while (tr == null && checks < 50) {
            tr = check(80.0);
            checks++;
        }
        assertNotNull(tr);
        assertEquals(State.NORMAL, tr.from());
        assertEquals(State.DEGRADED, tr.to());
        assertEquals(t.sustainTicks, tr.tick() - startedAt, "degraded exactly when the excess has lasted sustainTicks");
        assertEquals(t.sustainTicks, tr.heldTicks());
        assertEquals(80.0, tr.averageMillis());
        assertEquals(55.0, tr.baselineMillis(), 0.5);
        assertTrue(tr.enterMillis() >= 65.0 && tr.enterMillis() <= 75.0, "enter level " + tr.enterMillis());
        assertTrue(tr.exitMillis() < tr.enterMillis());
        assertTrue(health.degraded());
    }

    @Test
    void anyReadingsBetween70And100AreDegradedWhenSustained() {
        for (double level : new double[]{70.0, 76.0, 85.0, 100.0, 180.0}) {
            TickHealth h = new TickHealth();
            h.observe(0, 55.0, t);
            boolean degraded = false;
            for (long tick = 100; tick <= 100 + t.sustainTicks + 200; tick += CHECK) {
                degraded |= h.observe(tick, level, t) != null;
            }
            assertTrue(degraded, level + " ms sustained must degrade a server whose baseline is 55 ms");
        }
    }

    @Test
    void aReadingAtTheEnterLevelIsNotAboveIt() {
        health.observe(0, 55.0, t);
        double enter = health.enterLevel(t);
        for (long tick = 100; tick < 5000; tick += CHECK) {
            assertNull(health.observe(tick, enter, t));
        }
    }

    @Test
    void recoveryNeedsTheExitLevelSustainedAndTheMinimumDwell() {
        feed(20, 55.0);
        Transition in = null;
        while (in == null) {
            in = check(90.0);
        }
        long degradedAt = in.tick();

        // Above the exit level but below the enter level (a dead zone, e.g. 62 ms): stays degraded, forever.
        for (int i = 0; i < 100; i++) {
            assertNull(check(62.5), "the dead zone between exit and enter holds the state");
        }
        assertTrue(health.degraded());

        // Back to a healthy 54 ms: recovers only when it has been there for sustainTicks AND minDwellTicks passed.
        Transition out = null;
        long healthySince = now;
        while (out == null && now < healthySince + 20000) {
            out = check(54.0);
        }
        assertNotNull(out, "recovery must be reachable at a normal 54 ms");
        assertEquals(State.DEGRADED, out.from());
        assertEquals(State.NORMAL, out.to());
        assertTrue(out.tick() - degradedAt >= t.minDwellTicks, "minimum dwell honoured");
        assertTrue(out.heldTicks() >= t.sustainTicks, "recovered level held for the sustain window");
        assertEquals(54.0, out.averageMillis());
        assertFalse(health.degraded());
    }

    @Test
    void theMinimumDwellDelaysRecoveryEvenWhenTheServerIsHealthyImmediately() {
        t.sustainTicks = 200;
        t.minDwellTicks = 2000;
        feed(20, 55.0);
        Transition in = null;
        while (in == null) {
            in = check(95.0);
        }
        Transition out = null;
        while (out == null) {
            out = check(52.0);
        }
        assertTrue(out.tick() - in.tick() >= 2000, "left after " + (out.tick() - in.tick()) + " ticks");
    }

    @Test
    void aFullCycleNormalDegradedNormalDegradedWithoutFlipFlopping() {
        int transitions = 0;
        double[] phases = {55.0, 92.0, 55.0, 88.0, 57.0};
        for (double phase : phases) {
            for (int i = 0; i < 60; i++) { // 6000 ticks per phase
                if (check(phase) != null) {
                    transitions++;
                }
            }
        }
        assertEquals(4, transitions, "in, out, in, out: one transition per real change of load");
        assertEquals(State.NORMAL, health.state());
    }

    @Test
    void aStateJitteringAroundTheEnterLevelDoesNotFlipFlop() {
        feed(20, 56.0);
        double enter = health.enterLevel(t);
        Transition in = null;
        while (in == null) {
            in = check(enter + 6);
        }
        // Now wobble between just under the enter level and clearly above the exit level: no recovery, no re-entry.
        int changes = 0;
        for (int i = 0; i < 200; i++) {
            if (check(i % 2 == 0 ? enter - 1 : enter + 3) != null) {
                changes++;
            }
        }
        assertEquals(0, changes);
        assertTrue(health.degraded());
    }

    @Test
    void theBaselineIsTheServersOwnTypicalTickTimeAndAdaptsTheEnterLevel() {
        // A heavier pack idling at 60 ms: enter level 75 instead of the 65 floor.
        for (int i = 0; i < 100; i++) {
            check(60.0);
        }
        assertEquals(60.0, health.baselineMillis(), 0.1);
        assertEquals(75.0, health.enterLevel(t), 0.2);
        // 72 ms is above the fixed floor (65) but is not overload for THIS server.
        for (int i = 0; i < 200; i++) {
            assertNull(check(72.0));
        }
        assertFalse(health.degraded());
        // A lighter pack idling at 50 ms: enter level stays at the 65 floor.
        TickHealth light = new TickHealth();
        for (long tick = 0; tick < 10000; tick += CHECK) {
            light.observe(tick, 50.0, t);
        }
        assertEquals(65.0, light.enterLevel(t), 1e-9);
    }

    @Test
    void theBaselineIsCappedSoAStrugglingServerCannotLearnThatMisery() {
        for (int i = 0; i < 500; i++) {
            health.observe(i * (long) CHECK, 64.0, t); // just under the floor: learned, but capped at baselineMaxMillis
        }
        assertTrue(health.baselineMillis() <= t.baselineMaxMillis + 1e-9);
        assertTrue(health.enterLevel(t) <= t.baselineMaxMillis * t.degradedFactor + 1e-9);
        // A startup spike is not learned at all (it is above the enter level).
        TickHealth fresh = new TickHealth();
        fresh.observe(0, 130.0, t);
        assertTrue(Double.isNaN(fresh.baselineMillis()));
        assertEquals(t.degradedFloorMillis, fresh.enterLevel(t));
    }

    @Test
    void degradedReadingsNeverRaiseTheBaseline() {
        feed(50, 53.0);
        double before = health.baselineMillis();
        Transition in = null;
        while (in == null) {
            in = check(95.0);
        }
        for (int i = 0; i < 200; i++) {
            check(95.0);
        }
        assertEquals(before, health.baselineMillis(), 1e-9);
    }

    @Test
    void noDataIsIgnoredAndResetForgetsEverything() {
        assertNull(health.observe(0, -1, t));
        assertNull(health.observe(0, Double.NaN, t));
        assertTrue(Double.isNaN(health.baselineMillis()));
        feed(50, 55.0);
        assertFalse(Double.isNaN(health.baselineMillis()));
        health.reset();
        assertTrue(Double.isNaN(health.baselineMillis()));
        assertEquals(State.NORMAL, health.state());
    }

    @Test
    void theExitLevelIsAlwaysBelowTheEnterLevelEvenForOddConfigs() {
        t.degradedFloorMillis = 60.0;
        t.recoveredFloorMillis = 59.5;
        t.recoveredFactor = 3.0;
        health.observe(0, 58.0, t);
        assertTrue(health.exitLevel(t) <= health.enterLevel(t) - TickHealth.MIN_HYSTERESIS_MILLIS + 1e-9);
    }
}
