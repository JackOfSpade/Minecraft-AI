package io.github.zoyluo.minecraftai.mining.assist;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TickHeadroomTest {
    // ---- defaults ------------------------------------------------------------------------------

    @Test
    void defaultsMatchTheDesign() {
        TickHeadroom.Thresholds t = TickHeadroom.Thresholds.DEFAULTS;
        assertEquals(38.0D, t.startWorkMs());
        assertEquals(48.0D, t.abortWorkMs());
        assertEquals(44.0D, t.halveWorkMs());
        assertEquals(40.0D, t.rearmWorkMs());
        assertEquals(20, t.abortTicks());
        assertEquals(200, t.rearmTicks());
        assertEquals(0.1D, TickHeadroom.EMA_ALPHA);
        assertEquals(t, new TickHeadroom(false).thresholds());
    }

    @Test
    void freshInstanceIsArmedAndPermissive() {
        TickHeadroom h = new TickHeadroom(false);
        assertTrue(h.isArmed());
        assertTrue(h.canStart(false));
        assertFalse(h.shouldAbort(false));
        assertFalse(h.halveRays());
        assertEquals(0.0D, h.workEma());
    }

    // ---- EMA -----------------------------------------------------------------------------------

    @Test
    void averageIsSeededByTheFirstTickAndUsesAlphaPointOne() {
        TickHeadroom h = new TickHeadroom(false);
        h.record(60.0D);
        assertEquals(60.0D, h.workEma());
        h.record(10.0D);
        assertEquals(55.0D, h.workEma(), 1e-12);
        h.record(10.0D);
        assertEquals(50.5D, h.workEma(), 1e-12);
    }

    @Test
    void negativeWorkCountsAsZeroAndNonFiniteWorkIsIgnored() {
        TickHeadroom h = new TickHeadroom(false);
        h.record(20.0D);
        h.record(-500.0D);
        assertEquals(18.0D, h.workEma(), 1e-12);
        h.record(Double.NaN);
        h.record(Double.POSITIVE_INFINITY);
        assertEquals(18.0D, h.workEma(), 1e-12);
    }

    @Test
    void ignoredTicksDoNotAdvanceAnyStreak() {
        TickHeadroom h = new TickHeadroom(false);
        feed(h, 60.0D, 19);
        h.record(Double.NaN);
        h.record(Double.NaN);
        assertFalse(h.shouldAbort(false), "NaN ticks are not counted");
        h.record(60.0D);
        assertTrue(h.shouldAbort(false), "the 20th real tick trips");
    }

    // ---- start gate ----------------------------------------------------------------------------

    @Test
    void startGateIsInclusiveAtThirtyEight() {
        TickHeadroom atLimit = new TickHeadroom(false);
        atLimit.record(38.0D);
        assertTrue(atLimit.canStart(false), "38 ms is allowed");

        TickHeadroom over = new TickHeadroom(false);
        over.record(38.0001D);
        assertFalse(over.canStart(false), "above 38 ms is refused");
        assertTrue(over.isArmed(), "refusal by the start gate is not an abort");
    }

    @Test
    void degradedTpsBlocksStartsWhateverTheWork() {
        TickHeadroom h = new TickHeadroom(false);
        feed(h, 1.0D, 50);
        assertTrue(h.canStart(false));
        assertFalse(h.canStart(true));
    }

    @Test
    void startGateNeedsTheAverageNotASingleTick() {
        TickHeadroom h = new TickHeadroom(false);
        feed(h, 20.0D, 100);
        h.record(200.0D);                       // one long tick: 0.9 * 20 + 0.1 * 200 = 38.0 (boundary)
        assertEquals(38.0D, h.workEma(), 1e-9);
        h.record(200.0D);                       // 0.9 * 38 + 20 = 54.2
        assertFalse(h.canStart(false));
    }

    @Test
    void workBetweenThirtyEightAndFortyKeepsTheFeatureArmedButBlocksStarts() {
        TickHeadroom h = new TickHeadroom(false);
        feed(h, 39.0D, 500);
        assertTrue(h.isArmed());
        assertFalse(h.canStart(false), "39 ms average is above the 38 ms start gate");
        assertFalse(h.shouldAbort(false));
        assertFalse(h.halveRays());
    }

    // ---- abort ---------------------------------------------------------------------------------

    @Test
    void abortNeedsTheAverageAboveFortyEightForTwentyConsecutiveTicks() {
        TickHeadroom h = new TickHeadroom(false);
        for (int tick = 1; tick <= 19; tick++) {
            h.record(60.0D);
            assertFalse(h.shouldAbort(false), "tick " + tick);
            assertTrue(h.isArmed(), "tick " + tick);
        }
        h.record(60.0D);
        assertTrue(h.shouldAbort(false), "tick 20");
        assertFalse(h.isArmed());
        assertFalse(h.canStart(false));
        h.record(60.0D);
        assertTrue(h.shouldAbort(false), "stays true while above");
    }

    @Test
    void averageExactlyAtFortyEightNeverAborts() {
        TickHeadroom h = new TickHeadroom(false);
        feed(h, 48.0D, 500);
        assertFalse(h.shouldAbort(false));
        assertTrue(h.isArmed());
    }

    @Test
    void anAverageJustAboveFortyEightAbortsAfterTwentyTicks() {
        TickHeadroom h = new TickHeadroom(false);
        feed(h, 48.0001D, 19);
        assertFalse(h.shouldAbort(false));
        h.record(48.0001D);
        assertTrue(h.shouldAbort(false));
    }

    @Test
    void streakBreaksWhenTheAverageDropsBackToFortyEight() {
        TickHeadroom h = new TickHeadroom(false);
        int streak = 0;
        // A pattern that repeatedly climbs above 48 and falls back before 20 ticks are reached.
        double[] pattern = {90.0D, 90.0D, 90.0D, 90.0D, 90.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D};
        for (int round = 0; round < 12; round++) {
            for (double work : pattern) {
                h.record(work);
                streak = h.workEma() > 48.0D ? streak + 1 : 0;
                assertTrue(streak < 20);
                assertFalse(h.shouldAbort(false), "round " + round + ", streak " + streak);
            }
        }
        assertTrue(h.isArmed());
        // A sustained overload afterwards still trips after exactly twenty consecutive ticks.
        int overTicks = 0;
        while (!h.shouldAbort(false)) {
            h.record(90.0D);
            overTicks = h.workEma() > 48.0D ? overTicks + 1 : 0;
            assertTrue(overTicks <= 20, "runaway");
        }
        assertEquals(20, overTicks);
    }

    @Test
    void degradedTpsAlwaysAborts() {
        TickHeadroom h = new TickHeadroom(false);
        feed(h, 1.0D, 10);
        assertFalse(h.shouldAbort(false));
        assertTrue(h.shouldAbort(true));
        assertTrue(h.shouldAbort(true), "idempotent");
    }

    @Test
    void anAbortOnDegradedTpsDisarmsUntilTheCalmPeriodPasses() {
        TickHeadroom h = new TickHeadroom(false);
        feed(h, 5.0D, 30);
        assertTrue(h.canStart(false));
        assertTrue(h.shouldAbort(true));
        assertFalse(h.isArmed());
        assertFalse(h.canStart(false), "TPS recovered but the calm period has not passed");

        feed(h, 5.0D, 199);
        assertFalse(h.canStart(false), "199 calm ticks are not enough");
        h.record(5.0D);
        assertTrue(h.isArmed());
        assertTrue(h.canStart(false), "the 200th calm tick re-arms");
    }

    @Test
    void repeatedDegradedAbortsKeepRestartingTheCalmCount() {
        TickHeadroom h = new TickHeadroom(false);
        feed(h, 5.0D, 10);
        h.shouldAbort(true);
        feed(h, 5.0D, 150);
        h.shouldAbort(true);                    // another degraded episode after 150 calm ticks
        feed(h, 5.0D, 199);
        assertFalse(h.isArmed());
        h.record(5.0D);
        assertTrue(h.isArmed());
    }

    // ---- hysteresis ----------------------------------------------------------------------------

    @Test
    void reArmsOnlyAfterTwoHundredConsecutiveTicksAtOrBelowForty() {
        TickHeadroom h = new TickHeadroom(false);
        feed(h, 60.0D, 25);
        assertTrue(h.shouldAbort(false));
        assertFalse(h.isArmed());

        int calm = 0;
        while (calm < 200) {
            h.record(0.0D);
            if (h.workEma() <= 40.0D) {
                calm++;
            }
            if (calm < 200) {
                assertFalse(h.isArmed(), "calm ticks so far: " + calm);
                assertFalse(h.canStart(false), "calm ticks so far: " + calm);
            }
        }
        assertTrue(h.isArmed());
        assertTrue(h.canStart(false));
        assertFalse(h.shouldAbort(false));
    }

    @Test
    void aSpikeDuringTheCalmPeriodRestartsTheCount() {
        TickHeadroom h = new TickHeadroom(false);
        feed(h, 60.0D, 25);
        feed(h, 0.0D, 30);                      // well below 40 by now
        int calm = countCalmTicks(h, 150);
        assertEquals(150, calm);
        assertFalse(h.isArmed());

        h.record(1000.0D);                      // one huge tick lifts the average above 40
        assertTrue(h.workEma() > 40.0D);
        assertFalse(h.isArmed());

        calm = 0;
        while (calm < 199) {
            h.record(0.0D);
            if (h.workEma() <= 40.0D) {
                calm++;
            }
            assertFalse(h.isArmed(), "restarted count " + calm);
        }
        h.record(0.0D);
        assertTrue(h.isArmed(), "200 fresh calm ticks after the spike");
    }

    @Test
    void averageBetweenFortyAndFortyEightStallsTheReArm() {
        TickHeadroom h = new TickHeadroom(false);
        feed(h, 60.0D, 25);
        feed(h, 0.0D, 40);                      // average is now far below 40
        feed(h, 42.0D, 1000);                   // creeps to 42: never a calm tick
        assertTrue(h.workEma() > 40.0D && h.workEma() <= 48.0D);
        assertFalse(h.isArmed());
        assertFalse(h.canStart(false));
    }

    @Test
    void afterReArmingASecondOverloadTripsAgain() {
        TickHeadroom h = new TickHeadroom(false);
        for (int round = 0; round < 3; round++) {
            feed(h, 80.0D, 40);                 // the average needs a few ticks to climb above 48
            assertTrue(h.shouldAbort(false), "round " + round);
            assertFalse(h.isArmed());
            feed(h, 0.0D, 300);
            assertTrue(h.isArmed(), "round " + round);
            assertTrue(h.canStart(false), "round " + round);
        }
    }

    @Test
    void anAverageExactlyAtFortyCountsAsCalmForTheReArm() {
        TickHeadroom h = new TickHeadroom(false);
        assertTrue(h.shouldAbort(true));                // disarm without disturbing the average
        feed(h, 40.0D, 199);
        assertEquals(40.0D, h.workEma(), "a constant 40 ms keeps the average at exactly 40");
        assertFalse(h.isArmed(), "199 calm ticks are not enough");
        h.record(40.0D);
        assertTrue(h.isArmed(), "40 ms is at or below the re-arm level");
        assertFalse(h.canStart(false), "but 40 ms is still above the 38 ms start gate");
    }

    // ---- ray throttle --------------------------------------------------------------------------

    @Test
    void anAverageExactlyAtFortyCountsAsCalmForTheThrottleRelease() {
        // halve at 41 so the average can be steered to exactly 40.0: 41.5 + 0.1 * (26.5 - 41.5) = 40.0.
        TickHeadroom h = new TickHeadroom(false, new TickHeadroom.Thresholds(38.0D, 48.0D, 41.0D, 40.0D, 20, 200));
        h.record(41.5D);
        assertTrue(h.halveRays());
        h.record(26.5D);
        assertEquals(40.0D, h.workEma(), "exactly 40");
        feed(h, 40.0D, 198);
        assertTrue(h.halveRays(), "199 calm ticks so far (the steering tick counts as the first)");
        h.record(40.0D);
        assertFalse(h.halveRays(), "the 200th tick at exactly 40 releases the throttle");
    }

    @Test
    void halvingStartsWhenTheAverageExceedsFortyFour() {
        TickHeadroom atLimit = new TickHeadroom(false);
        feed(atLimit, 44.0D, 300);
        assertFalse(atLimit.halveRays(), "44 ms exactly does not throttle");

        TickHeadroom over = new TickHeadroom(false);
        over.record(44.5D);
        assertTrue(over.halveRays());
        assertTrue(over.isArmed(), "throttling is not an abort");
        assertFalse(over.shouldAbort(false));
    }

    @Test
    void halvingIsReleasedOnlyAfterTwoHundredCalmTicks() {
        TickHeadroom h = new TickHeadroom(false);
        feed(h, 46.0D, 30);
        assertTrue(h.halveRays());

        feed(h, 42.0D, 600);                    // between 40 and 44: latched, never calm
        assertTrue(h.halveRays());

        int calm = 0;
        while (calm < 200) {
            h.record(20.0D);
            if (h.workEma() <= 40.0D) {
                calm++;
            }
            if (calm < 200) {
                assertTrue(h.halveRays(), "calm ticks so far: " + calm);
            }
        }
        assertFalse(h.halveRays());
    }

    @Test
    void halvingSurvivesABriefDipAndRestartsItsCount() {
        TickHeadroom h = new TickHeadroom(false);
        feed(h, 46.0D, 20);
        feed(h, 10.0D, 60);                     // calm for a while
        feed(h, 42.0D, 60);                     // back above 40: count restarts
        assertTrue(h.workEma() > 40.0D);
        assertTrue(h.halveRays());
        assertEquals(199, countCalmTicks(h, 199));
        assertTrue(h.halveRays(), "199 fresh calm ticks are not enough");
        assertEquals(1, countCalmTicks(h, 1));
        assertFalse(h.halveRays());
    }

    @Test
    void severeOverloadLatchesBothTheAbortAndTheThrottle() {
        TickHeadroom h = new TickHeadroom(false);
        feed(h, 70.0D, 25);
        assertTrue(h.halveRays());
        assertFalse(h.isArmed());
        assertTrue(h.shouldAbort(false));
        feed(h, 0.0D, 260);
        assertFalse(h.halveRays());
        assertTrue(h.isArmed());
    }

    // ---- deterministic mode --------------------------------------------------------------------

    @Test
    void deterministicModeIgnoresTheMeasuredWork() {
        TickHeadroom h = new TickHeadroom(true);
        feed(h, 500.0D, 1000);
        assertTrue(h.isDeterministic());
        assertTrue(h.workEma() > 400.0D, "the average is still tracked");
        assertTrue(h.canStart(false), "start gate always passes");
        assertFalse(h.halveRays(), "never throttles");
        assertFalse(h.shouldAbort(false), "work-time never aborts");
    }

    @Test
    void deterministicModeStillHonoursDegradedTps() {
        TickHeadroom h = new TickHeadroom(true);
        assertTrue(h.shouldAbort(true));
        assertFalse(h.canStart(true), "the TpsGuard latch stays live");
        assertTrue(h.canStart(false));
        feed(h, 500.0D, 100);
        assertTrue(h.shouldAbort(true));
        assertFalse(h.shouldAbort(false));
    }

    @Test
    void deterministicOutcomeDoesNotDependOnTheTimingHistory() {
        TickHeadroom slow = new TickHeadroom(true);
        TickHeadroom fast = new TickHeadroom(true);
        SplittableRandom random = new SplittableRandom(42);
        for (int tick = 0; tick < 2000; tick++) {
            slow.record(random.nextDouble() * 400.0D);
            fast.record(random.nextDouble() * 2.0D);
            assertEquals(fast.canStart(false), slow.canStart(false));
            assertEquals(fast.halveRays(), slow.halveRays());
            assertEquals(fast.shouldAbort(false), slow.shouldAbort(false));
        }
    }

    // ---- configuration -------------------------------------------------------------------------

    @Test
    void customThresholdsAreHonoured() {
        TickHeadroom h = new TickHeadroom(false, TickHeadroom.Thresholds.of(30.0D, 41.0D));
        h.record(31.0D);
        assertFalse(h.canStart(false), "31 ms is above the custom 30 ms start");
        TickHeadroom low = new TickHeadroom(false, TickHeadroom.Thresholds.of(30.0D, 41.0D));
        low.record(29.0D);
        assertTrue(low.canStart(false));

        TickHeadroom abort = new TickHeadroom(false, TickHeadroom.Thresholds.of(30.0D, 41.0D));
        feed(abort, 41.5D, 19);
        assertFalse(abort.shouldAbort(false));
        abort.record(41.5D);
        assertTrue(abort.shouldAbort(false));

        // The 44 ms throttle and 40 ms re-arm thresholds keep their design values.
        assertEquals(44.0D, abort.thresholds().halveWorkMs());
        assertEquals(40.0D, abort.thresholds().rearmWorkMs());
    }

    @Test
    void invalidThresholdsAreRejectedOrSanitised() {
        assertThrows(IllegalArgumentException.class, () -> TickHeadroom.Thresholds.of(50.0D, 40.0D));
        assertThrows(IllegalArgumentException.class, () -> TickHeadroom.Thresholds.of(0.0D, 48.0D));
        assertThrows(IllegalArgumentException.class, () -> TickHeadroom.Thresholds.of(-1.0D, 48.0D));
        assertThrows(IllegalArgumentException.class, () -> TickHeadroom.Thresholds.of(Double.NaN, 48.0D));
        assertThrows(IllegalArgumentException.class, () -> TickHeadroom.Thresholds.of(38.0D, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> TickHeadroom.Thresholds.of(38.0D, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new TickHeadroom.Thresholds(38, 48, 40, 40, 20, 200));
        assertThrows(IllegalArgumentException.class, () -> new TickHeadroom.Thresholds(38, 48, 44, 49, 20, 200),
                "re-arm above abort");
        assertThrows(IllegalArgumentException.class, () -> new TickHeadroom.Thresholds(38, 48, 44, 40, 0, 200));
        assertThrows(IllegalArgumentException.class, () -> new TickHeadroom.Thresholds(38, 48, 44, 40, 20, 0));
        assertThrows(IllegalArgumentException.class, () -> new TickHeadroom(false, null));

        TickHeadroom.Thresholds defaults = TickHeadroom.Thresholds.DEFAULTS;
        assertEquals(defaults, TickHeadroom.Thresholds.sanitized(50.0D, 40.0D));
        assertEquals(defaults, TickHeadroom.Thresholds.sanitized(Double.NaN, 48.0D));
        assertEquals(defaults, TickHeadroom.Thresholds.sanitized(38.0D, -5.0D));
        assertEquals(defaults, TickHeadroom.Thresholds.sanitized(38.0D, Double.NaN));
        assertEquals(TickHeadroom.Thresholds.of(30.0D, 60.0D), TickHeadroom.Thresholds.sanitized(30.0D, 60.0D));
    }

    @Test
    void anAbortBelowTheDefaultReArmLevelIsHonouredNotReplacedByTheLaxerDefaults() {
        TickHeadroom.Thresholds t = TickHeadroom.Thresholds.of(30.0D, 39.0D);
        assertEquals(30.0D, t.startWorkMs());
        assertEquals(39.0D, t.abortWorkMs());
        assertEquals(39.0D, t.rearmWorkMs(), "re-arm never sits above abort");
        assertEquals(44.0D, t.halveWorkMs());
        assertEquals(t, TickHeadroom.Thresholds.sanitized(30.0D, 39.0D), "a stricter abort survives sanitising");

        assertEquals(40.0D, TickHeadroom.Thresholds.of(38.0D, 40.0D).rearmWorkMs());
        assertEquals(40.0D, TickHeadroom.Thresholds.of(38.0D, 48.0D).rearmWorkMs());
        assertEquals(40.0D, TickHeadroom.Thresholds.of(38.0D, 100.0D).rearmWorkMs());
        assertEquals(1.0D, TickHeadroom.Thresholds.sanitized(1.0D, 1.0D).rearmWorkMs());

        // A tight 20 / 30 ms machine: 35 ms is overloaded and must not count as calm.
        TickHeadroom h = new TickHeadroom(false, TickHeadroom.Thresholds.of(20.0D, 30.0D));
        feed(h, 35.0D, 25);
        assertTrue(h.shouldAbort(false));
        assertFalse(h.isArmed());
        feed(h, 35.0D, 500);
        assertFalse(h.isArmed(), "35 ms is above this abort threshold, so it is not calm");
        int calm = 0;
        while (calm < 200) {
            h.record(10.0D);
            if (h.workEma() <= 30.0D) {
                calm++;
            }
            if (calm < 200) {
                assertFalse(h.isArmed(), "calm ticks so far: " + calm);
            }
        }
        assertTrue(h.isArmed());
        assertTrue(h.canStart(false));
    }

    @Test
    void streakCountersSaturateInsteadOfOverflowing() throws ReflectiveOperationException {
        TickHeadroom h = new TickHeadroom(false);
        h.record(5.0D);
        // The counters would only reach 2^31 after years of server uptime; force the state instead.
        setInt(h, "calmStreak", Integer.MAX_VALUE);
        setInt(h, "armCalmStreak", Integer.MAX_VALUE);
        setInt(h, "overAbortStreak", 0);
        setBoolean(h, "halving", true);
        setBoolean(h, "disarmed", true);
        h.record(5.0D);
        assertFalse(h.halveRays(), "a saturated calm streak still releases the throttle");
        assertTrue(h.isArmed(), "a saturated calm streak still re-arms");
        assertEquals(200, getInt(h, "calmStreak"), "capped at the re-arm count");
        for (int i = 0; i < 1000; i++) {
            h.record(5.0D);
        }
        assertEquals(200, getInt(h, "calmStreak"), "capped at the re-arm count");

        setInt(h, "overAbortStreak", Integer.MAX_VALUE);
        h.record(500.0D);
        assertTrue(h.workEma() > 48.0D);
        assertTrue(getInt(h, "overAbortStreak") > 0, "no wrap-around to a negative streak");
        assertEquals(20, getInt(h, "overAbortStreak"), "capped at the abort count");
    }

    private static void setInt(TickHeadroom h, String field, int value) throws ReflectiveOperationException {
        Field f = TickHeadroom.class.getDeclaredField(field);
        f.setAccessible(true);
        f.setInt(h, value);
    }

    private static void setBoolean(TickHeadroom h, String field, boolean value) throws ReflectiveOperationException {
        Field f = TickHeadroom.class.getDeclaredField(field);
        f.setAccessible(true);
        f.setBoolean(h, value);
    }

    private static int getInt(TickHeadroom h, String field) throws ReflectiveOperationException {
        Field f = TickHeadroom.class.getDeclaredField(field);
        f.setAccessible(true);
        return f.getInt(h);
    }

    @Test
    void resetRestoresTheInitialState() {
        TickHeadroom h = new TickHeadroom(false);
        feed(h, 90.0D, 40);
        h.shouldAbort(true);
        assertFalse(h.isArmed());
        assertTrue(h.halveRays());
        h.reset();
        assertTrue(h.isArmed());
        assertFalse(h.halveRays());
        assertFalse(h.shouldAbort(false));
        assertTrue(h.canStart(false));
        assertEquals(0.0D, h.workEma());
        h.record(12.0D);
        assertEquals(12.0D, h.workEma(), "the average re-seeds");
    }

    // ---- randomised cross-check against an independent history-based model ---------------------

    @Test
    void matchesAHistoryBasedReferenceModelOnRandomLoads() {
        double[] levels = {2.0D, 20.0D, 30.0D, 37.0D, 39.0D, 41.0D, 43.0D, 45.0D, 47.0D, 49.0D, 55.0D, 80.0D};
        for (long seed = 1; seed <= 12; seed++) {
            crossCheck(TickHeadroom.Thresholds.DEFAULTS, false, levels, seed, 4000);
        }
    }

    @Test
    void matchesTheReferenceModelForCustomThresholdsAndTickCounts() {
        TickHeadroom.Thresholds[] custom = {
            TickHeadroom.Thresholds.of(30.0D, 42.0D),
            TickHeadroom.Thresholds.of(20.0D, 30.0D),
            TickHeadroom.Thresholds.of(38.0D, 100.0D),
            new TickHeadroom.Thresholds(25.0D, 35.0D, 30.0D, 28.0D, 5, 50),
            new TickHeadroom.Thresholds(10.0D, 12.0D, 11.0D, 9.0D, 1, 1),
        };
        for (TickHeadroom.Thresholds t : custom) {
            double[] levels = {
                1.0D, t.rearmWorkMs() - 1.0D, t.rearmWorkMs() + 1.0D, t.startWorkMs() - 1.0D,
                t.startWorkMs() + 1.0D, t.halveWorkMs() - 1.0D, t.halveWorkMs() + 1.0D,
                t.abortWorkMs() - 1.0D, t.abortWorkMs() + 1.0D, t.abortWorkMs() * 1.5D, t.abortWorkMs() * 2.5D};
            for (long seed = 1; seed <= 6; seed++) {
                crossCheck(t, false, levels, seed, 3000);
            }
        }
    }

    @Test
    void deterministicInstanceFollowsOnlyTheDegradedTpsVerdictOnRandomLoads() {
        double[] levels = {2.0D, 39.0D, 45.0D, 49.0D, 80.0D, 400.0D};
        for (long seed = 1; seed <= 6; seed++) {
            crossCheck(TickHeadroom.Thresholds.DEFAULTS, true, levels, seed, 3000);
        }
    }

    private static void crossCheck(TickHeadroom.Thresholds t, boolean deterministic, double[] levels, long seed, int totalTicks) {
        SplittableRandom random = new SplittableRandom(seed);
        TickHeadroom real = new TickHeadroom(deterministic, t);
        Reference model = new Reference(t, deterministic);
        int ticks = 0;
        while (ticks < totalTicks) {
            double level = levels[random.nextInt(levels.length)];
            int length = 3 + random.nextInt(400);
            for (int i = 0; i < length && ticks < totalTicks; i++, ticks++) {
                double work = Math.max(0.0D, level + (random.nextDouble() - 0.5D) * 6.0D);
                real.record(work);
                model.record(work);
                boolean degraded = random.nextInt(60) == 0;
                String at = t + " det=" + deterministic + " seed " + seed + " tick " + ticks;

                assertEquals(model.halving(), real.halveRays(), at + " halveRays");
                assertEquals(model.wouldAbort(false), real.shouldAbort(false), at + " shouldAbort");
                assertEquals(model.canStart(degraded), real.canStart(degraded), at + " canStart");
                if (random.nextInt(25) == 0) {
                    assertTrue(real.shouldAbort(true), at + " degraded abort");
                    model.degradedAbort();
                }
                assertEquals(model.armed(), real.isArmed(), at + " armed");
            }
        }
    }

    /**
     * Straight-from-the-prose model: keeps the whole history of the average and derives every
     * latch by scanning it, unlike the incremental counters in the implementation.
     */
    private static final class Reference {
        private final TickHeadroom.Thresholds t;
        private final boolean deterministic;
        private final List<Double> ema = new ArrayList<>();
        /** History length at the most recent abort; the calm period is measured after it. */
        private int lastAbortAt = -1;

        Reference(TickHeadroom.Thresholds t, boolean deterministic) {
            this.t = t;
            this.deterministic = deterministic;
        }

        void record(double work) {
            double previous = ema.isEmpty() ? work : ema.get(ema.size() - 1);
            ema.add(ema.isEmpty() ? work : previous + 0.1D * (work - previous));
            if (overStreak() >= t.abortTicks()) {
                lastAbortAt = ema.size();
            }
        }

        void degradedAbort() {
            lastAbortAt = ema.size();
        }

        int overStreak() {
            int streak = 0;
            for (int i = ema.size() - 1; i >= 0 && ema.get(i) > t.abortWorkMs(); i--) {
                streak++;
            }
            return streak;
        }

        boolean wouldAbort(boolean degraded) {
            return degraded || (!deterministic && overStreak() >= t.abortTicks());
        }

        boolean armed() {
            return lastAbortAt < 0 || hasCalmRun(lastAbortAt);
        }

        boolean halving() {
            if (deterministic) {
                return false;
            }
            int lastAbove = -1;
            for (int i = 0; i < ema.size(); i++) {
                if (ema.get(i) > t.halveWorkMs()) {
                    lastAbove = i;
                }
            }
            return lastAbove >= 0 && !hasCalmRun(lastAbove + 1);
        }

        boolean canStart(boolean degraded) {
            if (degraded) {
                return false;
            }
            if (deterministic) {
                return true;
            }
            double now = ema.isEmpty() ? 0.0D : ema.get(ema.size() - 1);
            return armed() && now <= t.startWorkMs();
        }

        /** True if {@code ema[from..]} contains {@code rearmTicks} consecutive values at or below the re-arm level. */
        private boolean hasCalmRun(int from) {
            int run = 0;
            for (int i = from; i < ema.size(); i++) {
                run = ema.get(i) <= t.rearmWorkMs() ? run + 1 : 0;
                if (run >= t.rearmTicks()) {
                    return true;
                }
            }
            return false;
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static void feed(TickHeadroom h, double workMs, int ticks) {
        for (int i = 0; i < ticks; i++) {
            h.record(workMs);
        }
    }

    /** Records zero-work ticks until {@code wanted} of them left the average at or below 40 ms. */
    private static int countCalmTicks(TickHeadroom h, int wanted) {
        int calm = 0;
        int guard = 0;
        while (calm < wanted && guard++ < 10_000) {
            h.record(0.0D);
            if (h.workEma() <= 40.0D) {
                calm++;
            }
        }
        return calm;
    }
}
