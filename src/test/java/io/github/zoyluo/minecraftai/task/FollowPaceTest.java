package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.Gait;
import io.github.zoyluo.minecraftai.action.QuietZone;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Every rule of the follow pace, in order, plus the downgrade dwell. */
class FollowPaceTest {
    private static final double WALK_GAP = 6.0D;
    private static final double SPRINT_GAP = 10.0D;
    private static final int LONG = 100;

    /** A decision with everything neutral: a standing player, no quiet zone, no pressure, long at WALK. */
    private static final class Rig {
        double gap = 8.0D;
        double speed;
        boolean sprinting;
        int sneakTicks;
        QuietZone.Level quiet = QuietZone.Level.NONE;
        boolean pressure;
        Gait previous = Gait.WALK;
        int ticksInGait = LONG;

        Gait decide() {
            return FollowPace.decide(new FollowPace.Input(gap, speed, sprinting, sneakTicks, quiet, pressure,
                    previous, ticksInGait, WALK_GAP, SPRINT_GAP));
        }

        Rig gap(double value) {
            gap = value;
            return this;
        }
    }

    @Test
    void pressureSprintsEvenAtSmallGaps() {
        Rig rig = new Rig().gap(2.0D);
        rig.pressure = true;
        assertEquals(Gait.SPRINT, rig.decide());
    }

    @Test
    void pressureBeatsASneakingPlayer() {
        Rig rig = new Rig().gap(3.0D);
        rig.sneakTicks = 20;
        rig.pressure = true;
        assertEquals(Gait.SPRINT, rig.decide());
    }

    @Test
    void closeToThePlayerWalksOrSneaksWithASneakingOne() {
        Rig rig = new Rig().gap(4.5D);
        assertEquals(Gait.WALK, rig.decide());
        rig.sprinting = true;
        rig.speed = 6.0D;
        assertEquals(Gait.WALK, rig.decide(), "a sprinting player next to the follower does not drag it into a sprint");
        rig.sprinting = false;
        rig.speed = 0.0D;
        rig.sneakTicks = FollowPace.SNEAK_TICKS;
        assertEquals(Gait.SNEAK, rig.decide());
        rig.sneakTicks = FollowPace.SNEAK_TICKS - 1;
        assertEquals(Gait.WALK, rig.decide(), "a tap of the sneak key is not a sneak");
    }

    @Test
    void aSneakingPlayerIsMirroredUpToEightBlocksThenWalkedToAndOnlyCaughtUpWithFarAway() {
        Rig rig = new Rig();
        rig.sneakTicks = 10;
        assertEquals(Gait.SNEAK, rig.gap(8.0D).decide());
        assertEquals(Gait.WALK, rig.gap(8.5D).decide());
        assertEquals(Gait.WALK, rig.gap(13.9D).decide());
        assertEquals(Gait.SPRINT, rig.gap(14.0D).decide());
    }

    @Test
    void aSneakingPlayerFarAwayInAQuietZoneIsWalkedToNotSprintedTo() {
        Rig rig = new Rig();
        rig.sneakTicks = 10;
        rig.quiet = QuietZone.Level.CAUTION;
        assertEquals(Gait.WALK, rig.gap(12.0D).decide());
        assertEquals(Gait.WALK, rig.gap(30.0D).decide());
        rig.quiet = QuietZone.Level.SILENT;
        assertEquals(Gait.WALK, rig.gap(30.0D).decide());
    }

    @Test
    void aSprintingPlayerIsFollowedAtASprintByFlagOrBySpeed() {
        Rig rig = new Rig().gap(5.0D);
        rig.sprinting = true;
        assertEquals(Gait.SPRINT, rig.decide());
        rig.sprinting = false;
        rig.speed = 5.0D;
        assertEquals(Gait.SPRINT, rig.decide());
        rig.speed = 4.9D;
        assertEquals(Gait.WALK, rig.decide(), "4.9 blocks per second is a brisk walk");
    }

    @Test
    void aSilentQuietZoneSneaksUpToEightBlocksAndWalksBeyond() {
        Rig rig = new Rig();
        rig.quiet = QuietZone.Level.SILENT;
        rig.speed = 3.0D;
        assertEquals(Gait.SNEAK, rig.gap(8.0D).decide());
        assertEquals(Gait.WALK, rig.gap(9.0D).decide());
        assertEquals(Gait.WALK, rig.gap(40.0D).decide());
    }

    @Test
    void aCautionQuietZoneSprintsOnlyFromSixteenBlocks() {
        Rig rig = new Rig();
        rig.quiet = QuietZone.Level.CAUTION;
        assertEquals(Gait.WALK, rig.gap(12.0D).decide());
        assertEquals(Gait.WALK, rig.gap(15.9D).decide());
        assertEquals(Gait.SPRINT, rig.gap(16.0D).decide());
    }

    @Test
    void aWalkingPlayerIsWalkedWithUnlessFarAhead() {
        Rig rig = new Rig();
        rig.speed = 4.3D;
        assertEquals(Gait.WALK, rig.gap(6.5D).decide());
        assertEquals(Gait.WALK, rig.gap(9.9D).decide());
        assertEquals(Gait.SPRINT, rig.gap(10.0D).decide());
        rig.speed = 1.5D;
        assertEquals(Gait.WALK, rig.gap(9.0D).decide(), "1.5 blocks per second is still standing about");
        rig.speed = 1.6D;
        assertEquals(Gait.WALK, rig.gap(9.0D).decide());
    }

    @Test
    void aStandingPlayerSprintsFromTenWalksFromSixAndKeepsTheGaitInBetween() {
        Rig rig = new Rig();
        assertEquals(Gait.SPRINT, rig.gap(10.0D).decide());
        assertEquals(Gait.WALK, rig.gap(6.0D).decide());
        rig.gap = 8.0D;
        rig.previous = Gait.SPRINT;
        assertEquals(Gait.SPRINT, rig.decide());
        rig.previous = Gait.WALK;
        assertEquals(Gait.WALK, rig.decide());
    }

    @Test
    void theBetweenBandNeverKeepsASneakOnceThePlayerStoppedSneaking() {
        Rig rig = new Rig().gap(8.0D);
        rig.previous = Gait.SNEAK;
        assertEquals(Gait.WALK, rig.decide());
    }

    @Test
    void anUpgradeIsImmediateButADowngradeNeedsTenTicksAtTheGait() {
        Rig rig = new Rig().gap(12.0D);
        rig.previous = Gait.WALK;
        rig.ticksInGait = 0;
        assertEquals(Gait.SPRINT, rig.decide(), "an upgrade does not wait");

        rig.gap = 5.0D;
        rig.previous = Gait.SPRINT;
        rig.ticksInGait = FollowPace.DWELL_TICKS - 1;
        assertEquals(Gait.SPRINT, rig.decide(), "still inside the dwell");
        rig.ticksInGait = FollowPace.DWELL_TICKS;
        assertEquals(Gait.WALK, rig.decide(), "the dwell is over");
    }

    @Test
    void theDwellAlsoHoldsAnAggroSprintAfterThePressureEnds() {
        Rig rig = new Rig().gap(2.0D);
        rig.previous = Gait.SPRINT;
        rig.ticksInGait = 3;
        assertEquals(Gait.SPRINT, rig.decide());
        rig.ticksInGait = FollowPace.DWELL_TICKS;
        assertEquals(Gait.WALK, rig.decide());
    }

    @Test
    void theSneakMirrorIsTheWalkMirrorOfAWalkingPlayer() {
        // A sneaking player and a standing one at the same close gap: the follower's gait is the player's (creep vs walk).
        Rig sneak = new Rig().gap(3.0D);
        sneak.sneakTicks = 12;
        sneak.previous = Gait.SNEAK;
        assertEquals(Gait.SNEAK, sneak.decide());
        Rig walk = new Rig().gap(3.0D);
        walk.previous = Gait.WALK;
        assertEquals(Gait.WALK, walk.decide());
    }

    @Test
    void wantedIgnoresTheDwell() {
        Rig rig = new Rig().gap(3.0D);
        rig.previous = Gait.SPRINT;
        rig.ticksInGait = 0;
        assertEquals(Gait.WALK, FollowPace.wanted(new FollowPace.Input(3.0D, 0.0D, false, 0, QuietZone.Level.NONE, false,
                Gait.SPRINT, 0, WALK_GAP, SPRINT_GAP)));
    }
}
