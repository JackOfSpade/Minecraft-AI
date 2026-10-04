package io.github.zoyluo.minecraftai.action;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure core of the pace policy: route pace hysteresis, leases, the ceilings and their order. */
class PacePolicyTest {
    private static final double SPRINT_FROM = 8.0D;
    private static final double WALK_TO = 4.5D;

    /** Builder for one decision's inputs; {@code now} advances by one per {@link #decide}. */
    private static final class Rig {
        final PacePolicy.State state = new PacePolicy.State();
        long now = 1000L;
        boolean pressure;
        PacePolicy.Lease lease;
        boolean taskSneak;
        boolean taskSprint;
        double distance = Double.NaN;
        QuietZone.Level quiet = QuietZone.Level.NONE;
        Gait cap;

        Gait decide() {
            now++;
            PacePolicy.Inputs in = new PacePolicy.Inputs(pressure, lease, taskSneak, taskSprint, distance,
                    quiet, cap, SPRINT_FROM, WALK_TO, now);
            return PacePolicy.decide(in, state);
        }

        Gait decideAt(double d) {
            distance = d;
            return decide();
        }
    }

    @Test
    void aCountdownFromTwentyBlocksSwitchesFromSprintToWalkExactlyOnce() {
        Rig rig = new Rig();
        Gait previous = null;
        int switches = 0;
        for (int d = 20; d >= 0; d--) {
            Gait gait = rig.decideAt(d);
            if (previous != null && gait != previous) {
                switches++;
                assertEquals(Gait.SPRINT, previous);
                assertEquals(Gait.WALK, gait);
                assertTrue(d <= WALK_TO, "the switch came at " + d + " blocks");
            }
            previous = gait;
        }
        assertEquals(1, switches);
        assertEquals(Gait.WALK, previous);
    }

    @Test
    void anOscillationBetweenSevenAndFiveBlocksNeverFlaps() {
        Rig sprinting = new Rig();
        for (int i = 0; i < 60; i++) {
            assertEquals(Gait.SPRINT, sprinting.decideAt(i % 2 == 0 ? 7.0D : 5.0D), "started far, stays sprinting, tick " + i);
        }
        Rig walking = new Rig();
        for (int i = 0; i < 60; i++) {
            assertEquals(Gait.WALK, walking.decideAt(i % 2 == 0 ? 5.0D : 7.0D), "started near, stays walking, tick " + i);
        }
    }

    @Test
    void anUpgradeIsImmediateAndTheDowngradeWaitsTenTicks() {
        Rig rig = new Rig();
        assertEquals(Gait.WALK, rig.decideAt(5.0D));
        assertEquals(Gait.SPRINT, rig.decideAt(20.0D), "far away: sprint at once");
        int held = 0;
        Gait gait;
        do {
            gait = rig.decideAt(2.0D);
            if (gait == Gait.SPRINT) {
                held++;
            }
        } while (gait == Gait.SPRINT && held < 50);
        assertEquals(PacePolicy.DOWNGRADE_DWELL_TICKS - 1, held, "the sprint is held until it has lasted ten ticks");
        assertEquals(Gait.WALK, gait);
    }

    @Test
    void aFreshTripStartsAtTheGaitItsDistanceCallsFor() {
        assertEquals(Gait.SPRINT, new Rig().decideAt(30.0D));
        assertEquals(Gait.WALK, new Rig().decideAt(3.0D), "a three block trip walks from its first tick");
        assertEquals(Gait.SPRINT, new Rig().decideAt(Double.NaN), "no goal known: the engine sprints as it always did");
        assertEquals(Gait.WALK, new Rig().decideAt(6.0D), "between the limits a fresh trip is a walk");
    }

    @Test
    void aGapInTheDecisionsStartsTheNextTripFresh() {
        Rig rig = new Rig();
        assertEquals(Gait.SPRINT, rig.decideAt(30.0D));
        rig.now += 100;
        assertEquals(Gait.WALK, rig.decideAt(3.0D), "an old trip's sprint does not linger into a short new one");
    }

    @Test
    void aLeaseSkipsTheDowngradeDwell() {
        Rig rig = new Rig();
        rig.lease = PacePolicy.Lease.route(Gait.SPRINT, PaceOwner.TASK);
        for (int i = 0; i < 15; i++) {
            assertEquals(Gait.SPRINT, rig.decideAt(2.0D));
        }
        rig.lease = PacePolicy.Lease.route(Gait.WALK, PaceOwner.TASK);
        assertEquals(Gait.WALK, rig.decideAt(2.0D), "a lease downgrade is immediate");
        rig.lease = PacePolicy.Lease.route(Gait.SNEAK, PaceOwner.TASK);
        assertEquals(Gait.SNEAK, rig.decideAt(50.0D));
    }

    @Test
    void theHighestPriorityLeaseWins() {
        long now = 5L;
        PacePolicy.Lease follow = PacePolicy.Lease.tick(Gait.SPRINT, PaceOwner.FOLLOW, now);
        PacePolicy.Lease evade = PacePolicy.Lease.tick(Gait.SPRINT, PaceOwner.EVADE, now);
        PacePolicy.Lease task = PacePolicy.Lease.route(Gait.SNEAK, PaceOwner.TASK);
        assertSame(evade, PacePolicy.Lease.best(follow, evade, now));
        assertSame(evade, PacePolicy.Lease.best(evade, follow, now));
        assertSame(evade, PacePolicy.Lease.best(evade, task, now));
        assertSame(follow, PacePolicy.Lease.best(follow, null, now));
        assertNull(PacePolicy.Lease.best(null, null, now));
        assertSame(evade, PacePolicy.Lease.best(evade, PacePolicy.Lease.tick(Gait.WALK, PaceOwner.EVADE, now), now),
                "equal priorities go to the first argument (the fresher tick lease)");
    }

    @Test
    void aTickLeaseLivesThroughFiveSkippedRenewalsAndDiesOnTheSixth() {
        long start = 200L;
        PacePolicy.Lease lease = PacePolicy.Lease.tick(Gait.SNEAK, PaceOwner.FOLLOW, start);
        for (long t = start; t <= start + 5; t++) {
            assertTrue(lease.validAt(t), "valid at +" + (t - start));
        }
        assertFalse(lease.validAt(start + 6));
        assertNull(PacePolicy.Lease.best(lease, null, start + 6));
        assertTrue(PacePolicy.Lease.route(Gait.WALK, PaceOwner.TASK).validAt(Long.MAX_VALUE - 1L), "a route lease has no clock");
    }

    @Test
    void aCeilingBeatsPressure() {
        Rig rig = new Rig();
        rig.pressure = true;
        assertEquals(Gait.SPRINT, rig.decideAt(2.0D), "pressure sprints even next to the goal");
        rig.cap = Gait.WALK;
        assertEquals(Gait.WALK, rig.decideAt(2.0D), "capPace beats pressure");
        rig.cap = Gait.SNEAK;
        assertEquals(Gait.SNEAK, rig.decideAt(50.0D));
    }

    @Test
    void pressureDecidesOverAnOrdinaryLease() {
        Rig rig = new Rig();
        rig.pressure = true;
        rig.lease = PacePolicy.Lease.route(Gait.WALK, PaceOwner.TASK);
        assertEquals(Gait.SPRINT, rig.decideAt(30.0D), "pressure beats a lease");
    }

    @Test
    void theTaskFlagsDecideBeforeRoutePace() {
        Rig rig = new Rig();
        rig.taskSneak = true;
        assertEquals(Gait.SNEAK, rig.decideAt(50.0D));
        rig.taskSneak = false;
        rig.taskSprint = true;
        assertEquals(Gait.SPRINT, rig.decideAt(2.0D), "a task that sprints sprints, even next to the goal");
        rig.taskSprint = false;
        assertEquals(Gait.WALK, rig.decideAt(2.0D));
    }

    @Test
    void quietZonesCapTheTaskAndRoutePaceButNotLeasesOrPressure() {
        Rig rig = new Rig();
        rig.quiet = QuietZone.Level.CAUTION;
        assertEquals(Gait.WALK, rig.decideAt(50.0D), "caution: at most a walk");
        rig.taskSprint = true;
        assertEquals(Gait.WALK, rig.decideAt(50.0D));
        rig.quiet = QuietZone.Level.SILENT;
        assertEquals(Gait.SNEAK, rig.decideAt(50.0D), "silent: sneak");
        rig.taskSprint = false;
        assertEquals(Gait.SNEAK, rig.decideAt(50.0D));
        rig.pressure = true;
        assertEquals(Gait.SPRINT, rig.decideAt(50.0D), "under attack the bot runs");
        rig.pressure = false;
        rig.lease = PacePolicy.Lease.tick(Gait.SPRINT, PaceOwner.EVADE, rig.now);
        assertEquals(Gait.SPRINT, rig.decideAt(50.0D), "a lease is its owner's decision");
        rig.lease = null;
        rig.quiet = QuietZone.Level.NONE;
        assertEquals(Gait.SPRINT, rig.decideAt(50.0D));
    }

    @Test
    void theLastGaitIsRemembered() {
        Rig rig = new Rig();
        assertEquals(Gait.SPRINT, rig.state.lastGait(), "before the first decision");
        rig.decideAt(2.0D);
        assertEquals(Gait.WALK, rig.state.lastGait());
        rig.state.reset();
        assertEquals(Gait.SPRINT, rig.decideAt(20.0D));
    }
}
