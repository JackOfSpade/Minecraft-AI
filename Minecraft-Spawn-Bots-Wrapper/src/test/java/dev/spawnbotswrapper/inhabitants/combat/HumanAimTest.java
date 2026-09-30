package dev.spawnbotswrapper.inhabitants.combat;

import dev.spawnbotswrapper.inhabitants.combat.HumanAim.Angles;
import dev.spawnbotswrapper.inhabitants.combat.HumanAim.Params;
import dev.spawnbotswrapper.inhabitants.combat.HumanAim.State;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure human-aim math: the turn rate limit, the fire tolerance, the settle jitter and the arrow re-aim arithmetic. */
class HumanAimTest {
    private static final double EPS = 1.0e-9;
    private static final Params P = Params.defaults();

    @Test
    void theDefaultsAreAFastHumanFlick() {
        assertEquals(540.0, P.maxTurnDegPerSec());
        assertEquals(27.0, P.maxStepDegPerTick(), EPS, "540 deg/s is 27 deg per 20 Hz tick");
        assertEquals(1.5, P.fireToleranceDeg());
        assertEquals(0.3, P.jitterBaseDeg());
        assertEquals(2.5, P.jitterSettleDeg());
        assertEquals(0.25, P.jitterSettleSeconds());
        assertTrue(P.enabled());
    }

    // ---------------------------------------------------------------- rotation rate limit

    @Test
    void wrapDegreesFoldsIntoMinus180To180() {
        assertEquals(-170.0, HumanAim.wrapDegrees(190.0), EPS);
        assertEquals(170.0, HumanAim.wrapDegrees(-190.0), EPS);
        assertEquals(0.0, HumanAim.wrapDegrees(360.0), EPS);
        assertEquals(-180.0, HumanAim.wrapDegrees(180.0), EPS);
        assertEquals(10.0, HumanAim.wrapDegrees(730.0), EPS);
    }

    @Test
    void aTurnWithinTheStepArrivesExactly() {
        Angles a = HumanAim.turn(10.0, 5.0, 30.0, -5.0, 27.0);
        assertEquals(30.0, a.yaw(), EPS);
        assertEquals(-5.0, a.pitch(), EPS);
    }

    @Test
    void aBigTurnIsLimitedToTheMaximumStep() {
        Angles a = HumanAim.turn(0.0, 0.0, 90.0, 0.0, 27.0);
        assertEquals(27.0, a.yaw(), EPS);
        assertEquals(0.0, a.pitch(), EPS);
    }

    @Test
    void theShorterWayRoundIsTaken() {
        // 170 -> -170 is +20 across the seam, not -340
        Angles across = HumanAim.turn(170.0, 0.0, -170.0, 0.0, 27.0);
        assertEquals(190.0, across.yaw(), EPS, "continues past 180 and arrives at -170");
        // -170 -> 170 is -20
        Angles back = HumanAim.turn(-170.0, 0.0, 170.0, 0.0, 27.0);
        assertEquals(-190.0, back.yaw(), EPS);
        // far side: 100 -> -100 is 160 degrees the positive way (through 180), so the first step is +27
        Angles far = HumanAim.turn(100.0, 0.0, -100.0, 0.0, 27.0);
        assertEquals(127.0, far.yaw(), EPS);
        // -100 -> 100 goes the negative way
        Angles far2 = HumanAim.turn(-100.0, 0.0, 100.0, 0.0, 27.0);
        assertEquals(-127.0, far2.yaw(), EPS);
    }

    @Test
    void yawAndPitchMoveTogetherAndTheCombinedStepIsLimited() {
        Angles a = HumanAim.turn(0.0, 0.0, 60.0, 80.0, 27.0);
        assertEquals(27.0, Math.hypot(a.yaw(), a.pitch()), 1.0e-9, "the combined step is the limit");
        assertEquals(60.0 / 80.0, a.yaw() / a.pitch(), 1.0e-9, "straight line in (yaw, pitch)");
    }

    @Test
    void thePitchNeverLeavesMinusNinetyToNinety() {
        assertEquals(90.0, HumanAim.turn(0.0, 80.0, 0.0, 200.0, 27.0).pitch(), EPS);
        assertEquals(-90.0, HumanAim.turn(0.0, -80.0, 0.0, -200.0, 27.0).pitch(), EPS);
    }

    @Test
    void aHalfTurnTakesSevenTicksAndNoTickTurnsMoreThanTheLimit() {
        State s = new State();
        s.snapTo(0.0, 0.0, 0);
        double previous = 0.0;
        int ticks = 0;
        // PvP BOT snaps to 180 degrees once, then nobody touches the rotation again: the bot goes on turning.
        double entityYaw = 180.0;
        while (Math.abs(HumanAim.wrapDegrees(s.yaw() - 180.0)) > 1.0e-6) {
            ticks++;
            Angles a = s.advance(P, ticks, entityYaw, 0.0, P.fireToleranceDeg());
            double step = Math.abs(HumanAim.wrapDegrees(a.yaw() - previous));
            assertTrue(step <= 27.0 + 1.0e-6, "tick " + ticks + " turned " + step);
            previous = a.yaw();
            entityYaw = (float) a.yaw(); // the entity now has what was written
            assertTrue(ticks < 20, "never arrives");
        }
        assertEquals(7, ticks, "180 / 27 = 6.7: seven ticks, about a third of a second");
    }

    @Test
    void aNewWantedDirectionIsOnlyTakenFromARotationThatDiffersFromWhatWasWritten() {
        State s = new State();
        s.snapTo(0.0, 0.0, 0);
        // wants +90; after one tick it is at 27 and the entity holds exactly what was written
        Angles a = s.advance(P, 1, 90.0, 0.0, 1.5);
        assertEquals(27.0, a.yaw(), EPS);
        assertEquals(90.0, s.wantedYaw(), EPS);
        // nobody set anything (the entity holds what we wrote): the old wanted direction is still pursued
        Angles b = s.advance(P, 2, (float) a.yaw(), 0.0, 1.5);
        assertEquals(54.0, b.yaw(), EPS);
        Angles c = s.advance(P, 3, (float) b.yaw(), 0.0, 1.5);
        assertEquals(81.0, c.yaw(), EPS);
        Angles d = s.advance(P, 4, (float) c.yaw(), 0.0, 1.5);
        assertEquals(90.0, d.yaw(), EPS);
        // a new order to look back turns the other way at once
        Angles e = s.advance(P, 5, 0.0, 0.0, 1.5);
        assertEquals(63.0, e.yaw(), EPS);
    }

    @Test
    void theTrackedYawIsKeptWrappedAndWrapAroundIsHandled() {
        State s = new State();
        s.snapTo(170.0, 0.0, 0);
        Angles a = s.advance(P, 1, -170.0, 0.0, 1.5); // wants -170: 20 degrees the short way (through 180)
        assertEquals(-170.0, a.yaw(), 1.0e-9);
        assertEquals(0.0, s.errorDeg(), 1.0e-4);
        State t = new State();
        t.snapTo(-179.0, 0.0, 0);
        Angles b = t.advance(P, 1, 179.0, 0.0, 1.5); // 2 degrees the short way
        assertEquals(179.0, b.yaw(), 1.0e-9);
    }

    @Test
    void theFirstRotationSeenIsTakenAsIsWithoutTurning() {
        State s = new State();
        Angles a = s.advance(P, 5, 123.0, -20.0, 1.5);
        assertEquals(123.0, a.yaw(), EPS);
        assertEquals(-20.0, a.pitch(), EPS);
        assertTrue(s.started());
        assertTrue(s.onTarget(0.001));
    }

    // ---------------------------------------------------------------- tolerance

    @Test
    void theToleranceIsTheBaseAtShortRangeAndShrinksWhereTheTargetSubtendsLess() {
        assertEquals(1.5, HumanAim.toleranceDeg(P, 2.0), EPS);
        assertEquals(1.5, HumanAim.toleranceDeg(P, 5.0), EPS, "atan(0.25 / 5) = 2.86 degrees is wider than the base");
        double at10 = HumanAim.toleranceDeg(P, 10.0);
        assertEquals(Math.toDegrees(Math.atan(0.025)), at10, 1.0e-9);
        assertTrue(at10 < 1.5 && at10 > 1.3, "1.43 degrees at 10 blocks: " + at10);
        double at40 = HumanAim.toleranceDeg(P, 40.0);
        assertEquals(0.358, at40, 0.005);
        assertTrue(HumanAim.toleranceDeg(P, 64.0) < at40);
        assertTrue(HumanAim.toleranceDeg(P, 1.0e6) >= 0.05, "never below the floor");
    }

    @Test
    void theToleranceRuleMeansAShotWithinItWouldHitTheTargetsRadius() {
        for (double d : new double[]{6.0, 10.0, 25.0, 64.0}) {
            double tol = HumanAim.toleranceDeg(P, d);
            double missBlocks = Math.tan(Math.toRadians(tol)) * d;
            assertTrue(missBlocks <= P.fireTargetRadius() + 1.0e-9, "at " + d + " blocks a shot at the edge of the tolerance misses by " + missBlocks);
        }
    }

    @Test
    void aimIsOnTargetOnlyWithinTheTolerance() {
        State s = new State();
        s.snapTo(0.0, 0.0, 0);
        s.advance(P, 1, 20.0, 0.0, 1.5); // wants 20, turned to 20 (within one step)
        assertTrue(s.onTarget(1.5));
        s.advance(P, 2, 100.0, 0.0, 1.5); // wants 100: after a step the error is 53 degrees
        assertFalse(s.onTarget(1.5));
        assertEquals(53.0, s.errorDeg(), 1.0e-6);
    }

    // ---------------------------------------------------------------- jitter

    @Test
    void theJitterDecaysFromTheFullSettleValueToTheBase() {
        assertEquals(2.8, HumanAim.jitterSigmaDeg(P, 0.0), EPS, "0.3 + 2.5 right after the flick");
        assertEquals(0.3 + 2.5 * Math.exp(-1.0), HumanAim.jitterSigmaDeg(P, 0.25), EPS, "one time constant later");
        assertEquals(0.3, HumanAim.jitterSigmaDeg(P, 10.0), 1.0e-6, "steady state");
        double last = Double.MAX_VALUE;
        for (double t = 0.0; t <= 2.0; t += 0.05) {
            double sigma = HumanAim.jitterSigmaDeg(P, t);
            assertTrue(sigma < last, "monotonically decreasing at " + t);
            last = sigma;
        }
        assertEquals(HumanAim.jitterSigmaDeg(P, 0.0), HumanAim.jitterSigmaDeg(P, -3.0), EPS, "negative time counts as none");
    }

    @Test
    void theSettleTimerStartsWhenTheAimComesOnTargetAndRestartsWhenItIsLost() {
        State s = new State();
        s.snapTo(0.0, 0.0, 0);
        // a 90 degree order: three ticks of turning, on target on the fourth
        double entityYaw = 90.0;
        long tick = 0;
        double yaw = 0.0;
        do {
            tick++;
            yaw = s.advance(P, tick, entityYaw, 0.0, 1.5).yaw();
            entityYaw = (float) yaw;
            assertTrue(tick < 10);
        } while (!s.onTarget(1.5));
        assertEquals(4, tick);
        assertEquals(0.0, s.settledSeconds(tick), EPS, "just came on target");
        for (int i = 0; i < 5; i++) {
            tick++;
            s.advance(P, tick, (float) yaw, 0.0, 1.5);
        }
        assertEquals(0.25, s.settledSeconds(tick), 1.0e-9, "five ticks later");
        // a new order 60 degrees away: the aim is lost (error above twice the tolerance) and the timer restarts
        tick++;
        s.advance(P, tick, 150.0, 0.0, 1.5);
        assertFalse(s.onTarget(1.5));
        assertEquals(0.0, s.settledSeconds(tick), EPS);
    }

    @Test
    void aSmallWobbleInsideTwiceTheToleranceDoesNotRestartTheTimer() {
        State s = new State();
        s.snapTo(0.0, 0.0, 0);
        for (int t = 1; t <= 10; t++) {
            s.advance(P, t, 0.0, 0.0, 1.5);
        }
        double before = s.settledSeconds(10);
        s.advance(P, 11, 2.0, 0.0, 1.5); // wants 2 degrees away: turns onto it within one tick
        assertTrue(s.settledSeconds(11) > before);
    }

    // ---------------------------------------------------------------- directions and the arrow re-aim arithmetic

    @Test
    void directionsAreVanillasViewVectors() {
        double[] south = HumanAim.direction(0.0, 0.0);
        assertEquals(0.0, south[0], 1.0e-12);
        assertEquals(0.0, south[1], 1.0e-12);
        assertEquals(1.0, south[2], 1.0e-12);
        double[] east = HumanAim.direction(-90.0, 0.0);
        assertEquals(1.0, east[0], 1.0e-12);
        assertEquals(0.0, east[2], 1.0e-12);
        double[] down = HumanAim.direction(0.0, 90.0);
        assertEquals(-1.0, down[1], 1.0e-12, "positive pitch looks down");
    }

    @Test
    void anglesOfIsTheInverseOfDirection() {
        for (double yaw : new double[]{-179.0, -90.0, -1.0, 0.0, 45.0, 135.0, 179.0}) {
            for (double pitch : new double[]{-80.0, -10.0, 0.0, 30.0, 85.0}) {
                double[] d = HumanAim.direction(yaw, pitch);
                Angles back = HumanAim.anglesOf(d[0] * 7.0, d[1] * 7.0, d[2] * 7.0);
                assertEquals(yaw, HumanAim.wrapDegrees(back.yaw()), 1.0e-9);
                assertEquals(pitch, back.pitch(), 1.0e-9);
            }
        }
    }

    @Test
    void theAngleBetweenTwoDirections() {
        assertEquals(0.0, HumanAim.angleBetween(30.0, 10.0, 30.0, 10.0), 1.0e-4);
        assertEquals(90.0, HumanAim.angleBetween(0.0, 0.0, 90.0, 0.0), 1.0e-9);
        assertEquals(180.0, HumanAim.angleBetween(0.0, 0.0, 180.0, 0.0), 1.0e-4);
        assertEquals(20.0, HumanAim.angleBetween(170.0, 0.0, -170.0, 0.0), 1.0e-9, "across the seam");
        assertEquals(30.0, HumanAim.angleBetween(0.0, 0.0, 0.0, 30.0), 1.0e-9);
    }

    @Test
    void aReAimedArrowKeepsItsLaunchSpeedAndFollowsTheTrackedAimPlusJitter() {
        // a full-power bow arrow (3.0) launched by a bot standing still: the launch speed is the length of the velocity
        assertEquals(3.0, HumanAim.launchSpeed(0.0, 0.0, 3.0, 0.0, 0.0, 0.0), 1.0e-12);
        // the shooter's own movement (which vanilla adds afterwards) is taken out of the measure
        assertEquals(3.0, HumanAim.launchSpeed(0.1, 0.0, 3.0, 0.1, 0.0, 0.0), 1.0e-12);
        // with no jitter the shot direction is exactly the tracked aim
        Angles none = HumanAim.jittered(35.0, -12.0, 0.0, 1.7, -0.4);
        assertEquals(35.0, none.yaw(), EPS);
        assertEquals(-12.0, none.pitch(), EPS);
        // with sigma s and standard normal samples (1, -1) it moves by exactly one sigma on each axis
        Angles j = HumanAim.jittered(35.0, -12.0, 2.8, 1.0, -1.0);
        assertEquals(37.8, j.yaw(), 1.0e-12);
        assertEquals(-14.8, j.pitch(), 1.0e-12);
        // and the pitch never leaves +-90
        assertEquals(90.0, HumanAim.jittered(0.0, 89.0, 5.0, 0.0, 3.0).pitch(), EPS);
        // the aim a re-aimed arrow gets differs from PvP BOT's snapped direction by the size of the turn still to do
        double snapped = 150.0;
        double tracked = 27.0;
        assertEquals(123.0, HumanAim.angleBetween(snapped, 0.0, tracked, 0.0), 1.0e-9);
    }
}
