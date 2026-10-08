package dev.spawnbotswrapper.inhabitants.combat;

import dev.spawnbotswrapper.inhabitants.combat.AggroController.Cause;
import dev.spawnbotswrapper.inhabitants.combat.AggroController.Mode;
import dev.spawnbotswrapper.inhabitants.combat.AggroController.Phase;
import dev.spawnbotswrapper.inhabitants.combat.AggroFakes.Person;
import dev.spawnbotswrapper.inhabitants.combat.AggroFakes.Sim;
import dev.spawnbotswrapper.inhabitants.combat.AggroWorld.Pos;
import dev.spawnbotswrapper.inhabitants.combat.TargetControl.Settings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Noticing, chasing and the rules around them: the continuous reaction time, the view cone, hearing (vanilla vibrations),
 * the 16 block engage limit, NO MAGIC (only what is perceived), the CONFIRMED engagement state machine, being hit, mobs,
 * somebody else's forced target, validity, inert modes and failures. The whole hunt (pursue, search, return) is
 * {@link AggroHuntTest}. Everything runs against fakes, including a small stand-in for PvP BOT's own target
 * resolution; no Minecraft, no PvP BOT.
 */
class AggroControllerTest {
    Sim s;
    Person bot;
    Person steve;

    @BeforeEach
    void setUp() {
        s = new Sim();
        bot = s.bot;
        steve = s.steve;
        bot.look = new double[]{1, 0}; // facing east, +x
    }

    /** Ticks until the controller handed the player to PvP BOT (a forced target), or -1. */
    private int ticksUntilChase(int max) {
        int t = s.runUntil(() -> !s.up.callsOf("set").isEmpty(), max);
        return t > max ? -1 : t;
    }

    private int sets() {
        return s.up.callsOf("set").size();
    }

    // ---------------------------------------------------------------- reaction time

    @Test
    void aPlayerInFrontIsNoticedOnlyAfterTheReactionTime() {
        steve.x = 8; // (0.5 + 1.5 * 8 / 64) = 0.6875 s = 14 ticks of unbroken exposure
        int ticks = ticksUntilChase(40);
        // the exposure starts at the first scan (within 3 ticks) and must reach 0.6875 s, 14 ticks later
        assertTrue(ticks >= 15 && ticks <= 17, "noticed after " + ticks + " ticks");
        assertEquals(List.of("set Warden7->Steve"), s.up.callsOf("set"));
        assertEquals(Phase.CHASE, s.phase());
        assertEquals(Cause.ACQUIRED, s.controller.causeOf("Warden7"));
        assertEquals("Steve", s.controller.confirmedTarget("Warden7"));
    }

    @Test
    void everyDistanceHasItsOwnReactionTime() {
        // 0.5 + 1.5 * d / 64: 4 blocks 0.594 s (12 ticks), 10 blocks 0.734 s (15 ticks), 15 blocks 0.852 s (18 ticks)
        int[] distances = {4, 10, 15};
        int[] expected = {12, 15, 18};
        for (int i = 0; i < distances.length; i++) {
            Sim sim = new Sim();
            sim.bot.look = new double[]{1, 0};
            sim.steve.x = distances[i];
            int t = sim.runUntil(() -> !sim.up.callsOf("set").isEmpty(), 80);
            assertTrue(t >= expected[i] + 1 && t <= expected[i] + 3, distances[i] + " blocks: " + t + " ticks, expected about "
                    + expected[i]);
        }
    }

    @Test
    void thePeripheralFieldTakesLongerContinuously() {
        bot.look = new double[]{0, 1}; // steve at +x is 90 degrees to the side: angle factor 1 + 60/70
        steve.x = 8;
        int ticks = ticksUntilChase(80);
        assertTrue(ticks >= 27 && ticks <= 29, "0.6875 * 1.857 = 1.277 s -> 26 ticks of exposure: " + ticks);
    }

    @Test
    void aSneakingPlayerTakesTwiceAsLong() {
        steve.x = 8;
        steve.subject = Perception.Subject.player(true);
        int ticks = ticksUntilChase(80);
        assertTrue(ticks >= 29 && ticks <= 31, "sneaking: 1.375 s -> 28 ticks of exposure: " + ticks);
    }

    @Test
    void anInvisiblePlayerIsNeverSeen() {
        steve.x = 4;
        steve.subject = new Perception.Subject(false, 0.0);
        s.run(200);
        assertEquals(List.of(), s.up.callsOf("set"));
    }

    @Test
    void aPlayerAtTheEdgeOfTheEngageLimitIsNoticedAtItsNormalReactionTime() {
        steve.x = 16; // 0.875 s = 18 ticks
        int ticks = ticksUntilChase(80);
        assertTrue(ticks >= 19 && ticks <= 21, "16 blocks: 18 ticks of exposure: " + ticks);
    }

    @Test
    void anExposureThatIsBrokenStartsAgain() {
        steve.x = 8;
        s.run(4);
        steve.x = 8;
        bot.blind.add("Steve");
        s.run(6); // out of sight for longer than a missed tick
        bot.blind.clear();
        int ticks = ticksUntilChase(60);
        assertTrue(ticks >= 14, "the earlier glimpse does not count: " + ticks);
    }

    @Test
    void oneMissedTickDoesNotBreakTheExposure() {
        steve.x = 8;
        s.run(5); // the exposure is running and watched every tick by now
        bot.blind.add("Steve");
        s.run(1); // a leaf, a fence post: one tick without a clear view
        bot.blind.clear();
        int ticks = ticksUntilChase(40);
        assertTrue(ticks > 0 && ticks <= 12, "noticed soon after despite one missed tick (a reset would take 14): " + ticks);
    }

    @Test
    void oneMissedTickNeverBreaksTheExposureWhereverItFallsInTheScanCycle() {
        for (int offset = 0; offset < 4; offset++) {
            // a fresh scene: the exposure runs for 3 ticks, one tick is blocked, then it goes on
            AggroFakes.Sim second = new AggroFakes.Sim();
            second.bot.look = new double[]{1, 0};
            second.steve.x = 8;
            second.run(offset); // shifts where in the 3 tick scan cycle the exposure starts
            second.runUntil(() -> second.bot.canSeeCalls >= 1, 10);
            second.run(3);
            second.bot.blind.add("Steve");
            second.run(1);
            second.bot.blind.clear();
            int t = second.runUntil(() -> second.controller.stats().chasing() > 0, 40);
            assertTrue(t <= 11, "offset " + offset + ": noticed " + t + " ticks after the one missed tick; a reset would take 15");
        }
    }

    @Test
    void perceptionOffMeansPlainLineOfSightAtOnce() {
        s.config = s.config.withPerception(s.config.perception().disabled());
        bot.look = new double[]{-1, 0}; // steve is BEHIND the bot: omnidirectional now
        steve.x = 15;
        int ticks = ticksUntilChase(10);
        assertTrue(ticks > 0 && ticks <= 3, "no cone, no time: the first scan notices: " + ticks);
    }

    @Test
    void perceptionOffUsesVanillaHasLineOfSightAndNothingElse() {
        s.config = s.config.withPerception(s.config.perception().disabled());
        steve.x = 12;
        bot.blind.add("Steve"); // the eye-and-body-centre view is blocked ...
        assertTrue(ticksUntilChase(10) > 0, "... but vanilla's single eye ray is what counts with perception off");
    }

    @Test
    void perceptionOffDoesNotSeeWhatVanillaCannot() {
        s.config = s.config.withPerception(s.config.perception().disabled());
        steve.x = 12;
        bot.plainBlind.add("Steve");
        s.run(60);
        assertEquals(List.of(), s.up.callsOf("set"));
    }

    @Test
    void withoutARayTheBlockedPlayerIsNotSeenEvenAtPointBlank() {
        steve.x = 3;
        bot.blind.add("Steve");
        s.run(100);
        assertEquals(List.of(), s.up.callsOf("set"));
    }

    @Test
    void lineOfSightNotRequiredSeesThroughWalls() {
        s.config = new AggroController.Config(true, false, 10, 200, 1.5, 40, 1200, 3, s.config.perception());
        steve.x = 6;
        bot.blind.add("Steve");
        assertTrue(ticksUntilChase(40) > 0);
    }

    // ---------------------------------------------------------------- the engage limit (16 blocks, the one hard rule)

    @Test
    void aPlayerJustInsideTheEngageLimitIsEngagedAndJustOutsideIsNot() {
        steve.x = 15.9;
        assertTrue(ticksUntilChase(80) > 0, "15.9 blocks: engaged");
        Sim far = new Sim();
        far.bot.look = new double[]{1, 0};
        far.steve.x = 16.1;
        far.run(300);
        assertEquals(List.of(), far.up.callsOf("set"), "16.1 blocks: never engaged, however long it is in plain sight");
        assertEquals(Phase.IDLE, far.phase());
        assertEquals(0, far.bot.canSeeCalls, "beyond the limit no ray is cast at all (the cost stays low)");
    }

    @Test
    void theChaseContinuesWhileTheTargetIsInSightUpToTheEngageLimit() {
        steve.x = 15;
        assertTrue(ticksUntilChase(80) > 0);
        s.run(400);
        assertEquals(Phase.CHASE, s.phase());
        steve.x = 15.9;
        s.run(200);
        assertEquals(Phase.CHASE, s.phase(), "15.9 blocks, still in sight and engaged");
        assertEquals(List.of(), s.up.callsOf("clear"), "never given up");
    }

    @Test
    void anEstablishedChaseContinuesPastTheEngageLimitWhileTheTargetStaysInSight() {
        steve.x = 15;
        assertTrue(ticksUntilChase(80) > 0);
        bot.x = 3; // it moved on while chasing: home is where it began
        steve.x = 70; // still in plain sight, now sighted at 67 blocks
        s.run(1);
        assertEquals(Phase.CHASE, s.phase(), "the 16-block rule gates acquisition, not a visible chase");
        assertEquals(List.of("clear Warden7"), s.up.callsOf("clear"));
        assertFalse(s.up.callsOf("steer").isEmpty(), "the wrapper keeps walking toward the visible target");
        assertNull(s.controller.stats().giveUps().get("too far"), "distance alone never ends an acquired chase");
        steve.x = 15;
        s.run(1);
        assertEquals(Phase.CHASE, s.phase());
        assertEquals(2, sets(), "the same confirmed chase re-arms PvP BOT when the target returns to combat range");
    }

    @Test
    void tooFarIsDecidedOnlyFromASightingNotFromTheTrueDistance() {
        steve.x = 15;
        assertTrue(ticksUntilChase(80) > 0);
        bot.blind.add("Steve");
        steve.x = 200; // far away and unseen: the bot cannot know
        s.run(9);
        assertEquals(Phase.CHASE, s.phase(), "the lost-sight rules apply, as for any unseen target");
        s.run(1);
        assertEquals(Phase.PURSUE, s.phase());
        assertNull(s.controller.stats().giveUps().get("too far"), "never judged too far without seeing it");
        Pos goal = s.planner.goals.isEmpty() ? null : s.planner.goals.get(0);
        s.run(2);
        goal = s.planner.goals.get(0);
        assertEquals(15.0, goal.x(), 1e-9, "it walks to where it LAST SAW the player, not to the true position");
    }

    // ---------------------------------------------------------------- behind, hearing (vanilla vibrations), ambush

    @Test
    void aPlayerStandingStillBehindIsNeverNoticed() {
        bot.look = new double[]{-1, 0};
        steve.x = 3;
        s.run(300);
        assertEquals(List.of(), s.up.callsOf("set"));
        assertEquals(0, bot.canSeeCalls, "behind and silent: the cheap filters say no, no ray is cast");
    }

    /** Steve walks: a vibration reaches the bot every 5 ticks (vanilla decides that; here it is fed in). */
    private int walkingSoundsUntilChase(int max) {
        for (int i = 0; i < max; i++) {
            if (i % 5 == 0) {
                s.sound(steve.x, steve.z);
            }
            s.run(1);
            if (!s.up.callsOf("set").isEmpty()) {
                return i + 1;
            }
        }
        return -1;
    }

    @Test
    void aPlayerWalkingBehindIsHeardAndNoticedWithoutTheViewCone() {
        bot.look = new double[]{-1, 0};
        steve.x = 3;
        s.up.lookTurns = false; // even if the bot never turned its head: hearing removes the view cone requirement
        int ticks = walkingSoundsUntilChase(60);
        // heard-led exposure: 0.5 + 1.5 * 3 / 64 = 0.5703 s -> 12 ticks, from the first sound (the sound is drained on tick 1)
        assertTrue(ticks >= 12 && ticks <= 16, "footsteps at 3 blocks: " + ticks);
        assertTrue(noticedHow().equals(Perception.Sense.HEARING), "noticed by hearing");
    }

    @Test
    void aHeardSoundOnlyCountsWhileTheSourceStaysInClearView() {
        bot.look = new double[]{-1, 0};
        steve.x = 3;
        s.up.lookTurns = false;
        for (int i = 0; i < 40; i++) {
            if (i % 5 == 0) {
                s.sound(steve.x, steve.z);
            }
            bot.blind.add("Steve"); // a wall: the sound gets through, the line does not
            s.run(1);
        }
        assertEquals(List.of(), s.up.callsOf("set"), "vibrations pass through ordinary walls, a notice does not");
    }

    @Test
    void aPlayerSneakingBehindIsSilentAndNeverNoticedUntilItHits() {
        bot.look = new double[]{-1, 0};
        steve.x = 1.5;
        steve.subject = Perception.Subject.player(true); // vanilla emits no step vibrations for it: nothing is fed in
        s.run(300);
        assertEquals(List.of(), s.up.callsOf("set"));
        assertEquals(Phase.IDLE, s.phase());
        // the stab: being hit makes the bot aware of the striker it sees, and it reacts
        s.hit(steve);
        s.run(1);
        assertNotEquals(Phase.IDLE, s.phase());
        assertEquals("Steve", s.controller.engagedWith("Warden7"));
        assertEquals(Cause.HIT, s.controller.causeOf("Warden7"));
    }

    private static void assertNotEquals(Object a, Object b) {
        assertFalse(a.equals(b), a + " should differ from " + b);
    }

    @Test
    void aSoundWithNoVisiblePlayerNearIsOnlyAPlaceToLookNeverAChase() {
        bot.look = new double[]{-1, 0};
        steve.x = 3;
        bot.blind.add("Steve");
        for (int i = 0; i < 60; i++) {
            if (i % 5 == 0) {
                s.sound(steve.x, steve.z);
            }
            s.run(1);
        }
        assertEquals(List.of(), s.up.callsOf("set"));
        assertTrue(bot.canSeeCalls > 0, "the sound was heard and the line checked");
        assertNotNull(bot.lookedAt, "the idle bot turned to look at the sound");
        assertEquals(3.0, bot.lookedAt.x(), 1e-9, "at the position of the sound");
        assertEquals(Phase.IDLE, s.phase());
    }

    @Test
    void aLaterUnmatchedSoundInTheSameTickDoesNotEraseTheMatchedCandidate() {
        bot.look = new double[]{-1, 0};
        steve.x = 3;
        s.up.lookTurns = false;
        int noticed = -1;
        for (int i = 0; i < 60 && noticed < 0; i++) {
            if (i % 4 == 0) {
                s.sound(steve.x, steve.z); // matched: a visible player stands there
                s.sound(-40, 30);          // unmatched, the same tick: only a place to look
            }
            s.run(1);
            if (!s.up.callsOf("set").isEmpty()) {
                noticed = i;
            }
        }
        assertTrue(noticed > 0, "still noticed by hearing (angle factor 1) despite the second sound");
        assertEquals(Perception.Sense.HEARING, noticedHow());
    }

    @Test
    void aSoundOnlyMakesAVisiblePlayerNearItTheSourceNotOnesFarAway() {
        bot.look = new double[]{-1, 0};
        steve.x = 30; // visible, but 27 blocks from the sound
        s.up.lookTurns = false;
        for (int i = 0; i < 60; i++) {
            if (i % 5 == 0) {
                s.sound(3, 0);
            }
            s.run(1);
        }
        assertEquals(List.of(), s.up.callsOf("set"), "behind the bot and not where the sound was: not noticed");
    }

    private Perception.Sense noticedHow() {
        String d = s.controller.describe("Warden7");
        return d.contains("by hearing") ? Perception.Sense.HEARING : d.contains("by sight") ? Perception.Sense.SIGHT : null;
    }

    @Test
    void describeShowsHowThePlayerWasNoticedAndTheReactionUsed() {
        steve.x = 8;
        assertTrue(ticksUntilChase(40) > 0);
        String d = s.controller.describe("Warden7");
        assertTrue(d.startsWith("chasing Steve (seen 0.0 s ago; noticed by sight after 0."), d);
    }

    // ---------------------------------------------------------------- chase: lost, and the CONFIRMED engagement

    @Test
    void aTreeTrunkDoesNotBreakTheChaseButEveryResightingNeedsTheFullReactionTime() {
        steve.x = 8;
        assertTrue(ticksUntilChase(40) > 0);
        assertEquals(1, sets());
        s.run(5);
        bot.blind.add("Steve");
        s.run(1);
        assertEquals(Phase.CHASE, s.phase());
        assertNull(s.controller.confirmedTarget("Warden7"), "the FIRST unseen tick ends the confirmation");
        assertEquals(List.of("clear Warden7"), s.up.callsOf("clear"), "and PvP BOT's target is cleared at that tick");
        s.run(8); // 9 unseen ticks: inside the 10 tick grace
        assertEquals(Phase.CHASE, s.phase());
        bot.blind.clear();
        s.run(14); // seen again: exposure 0 .. 13, the reaction takes 14 ticks
        assertEquals(1, sets(), "no shot at the instant the player reappears: the reaction time applies again");
        assertNull(s.controller.confirmedTarget("Warden7"));
        s.run(1);
        assertEquals(2, sets(), "confirmed again after a full reaction time of unbroken sight");
        assertEquals("Steve", s.controller.confirmedTarget("Warden7"));
        bot.blind.add("Steve");
        s.run(10);
        assertEquals(Phase.PURSUE, s.phase(), "10 ticks without sight: the target is lost");
    }

    @Test
    void aBlinkBehindAPillarOfFiveTicksStillNeedsAFullReaction() {
        steve.x = 8;
        assertTrue(ticksUntilChase(40) > 0);
        s.run(3);
        bot.blind.add("Steve");
        s.run(5);
        bot.blind.clear();
        s.run(13);
        assertEquals(1, sets(), "13 ticks after it reappeared: still reacting");
        assertFalse(s.controller.mayAttackPlayer("Warden7", "Steve"), "no blow, no shot inside the window");
        s.run(2);
        assertEquals(2, sets());
        assertTrue(s.controller.mayAttackPlayer("Warden7", "Steve"), "confirmed: allowed");
    }

    @Test
    void whileConfirmingTheBotFacesAndClosesInButPvpBotHoldsNoTarget() {
        steve.x = 12;
        assertTrue(ticksUntilChase(80) > 0);
        bot.blind.add("Steve");
        s.run(1);
        bot.blind.clear();
        s.up.calls.clear();
        s.run(5);
        assertTrue(s.up.callsOf("steer").size() >= 4, "closes in on the player it sees again: " + s.up.calls);
        assertEquals(List.of(), s.up.callsOf("set"));
        assertNull(s.up.current.get("Warden7"), "PvP BOT holds no target until the reaction is served");
    }

    @Test
    void anUnseenTargetIsSteeredToTheLastKnownPositionNotTheTruePosition() {
        steve.x = 8;
        assertTrue(ticksUntilChase(40) > 0);
        s.run(2);
        bot.blind.add("Steve");
        steve.x = 30; // the true position changes while it is unseen
        steve.z = 12;
        s.up.steered.clear();
        s.run(4);
        assertFalse(s.up.steered.isEmpty(), "the bot steers while it has lost sight");
        for (Pos p : s.up.steered) {
            assertEquals(8.0, p.x(), 1e-9, "toward the last known position");
            assertEquals(0.0, p.z(), 1e-9);
        }
    }

    @Test
    void aTargetThatDiesOutOfSightIsJustLost() {
        steve.x = 8;
        assertTrue(ticksUntilChase(40) > 0);
        bot.blind.add("Steve");
        s.run(3);
        steve.alive = false; // not observable
        s.run(3);
        assertEquals(Phase.CHASE, s.phase(), "still inside the grace: it does not know");
        assertEquals(List.of("clear Warden7"), s.up.callsOf("clear"), "only the unseen-tick clear");
        s.run(4);
        assertEquals(Phase.PURSUE, s.phase(), "lost, like any unseen target: last known position, search, then home");
    }

    @Test
    void aTargetThatLogsOutOutOfSightIsJustLost() {
        steve.x = 8;
        assertTrue(ticksUntilChase(40) > 0);
        bot.blind.add("Steve");
        s.run(2);
        s.world.online.remove("steve");
        s.run(2);
        assertEquals(Phase.CHASE, s.phase());
        s.run(6);
        assertEquals(Phase.PURSUE, s.phase());
    }

    @Test
    void aTargetThatChangesLevelOutOfSightIsJustLost() {
        steve.x = 8;
        assertTrue(ticksUntilChase(40) > 0);
        bot.blind.add("Steve");
        s.run(2);
        steve.dimension = "the_nether";
        s.run(2);
        assertEquals(Phase.CHASE, s.phase());
        s.run(6);
        assertEquals(Phase.PURSUE, s.phase());
    }

    @Test
    void aDeathTheBotSeesSendsItHomeAtOnceAndClearsPvpBotsState() {
        steve.x = 8;
        assertTrue(ticksUntilChase(40) > 0);
        bot.x = 12;
        steve.alive = false; // in plain view a tick ago
        s.run(1);
        assertEquals(List.of("clear Warden7"), s.up.callsOf("clear"));
        assertEquals(Phase.RETURN, s.phase());
    }

    @Test
    void aTargetThatDiesTheTickItStepsBehindCoverIsLostSightNotASeenDeath() {
        steve.x = 8;
        assertTrue(ticksUntilChase(40) > 0);
        bot.blind.add("Steve"); // it stepped behind cover ...
        steve.alive = false;    // ... and died on the same tick: the bot cannot have seen that
        s.run(1);
        assertEquals(Phase.CHASE, s.phase(), "not an observed death: no immediate RETURN");
        s.run(10);
        assertEquals(Phase.PURSUE, s.phase(), "lost like any unseen target");
    }

    @Test
    void aLogoutTheBotSeesEndsTheEngagementAtOnce() {
        steve.x = 8;
        assertTrue(ticksUntilChase(40) > 0);
        s.world.online.remove("steve");
        s.run(1);
        assertEquals(List.of("clear Warden7"), s.up.callsOf("clear"));
        assertNotEquals(Phase.CHASE, s.phase());
    }

    @Test
    void aLevelChangeTheBotSeesEndsTheEngagementAtOnce() {
        steve.x = 8;
        assertTrue(ticksUntilChase(40) > 0);
        steve.dimension = "the_nether";
        s.run(1);
        assertEquals(List.of("clear Warden7"), s.up.callsOf("clear"));
        assertEquals(Phase.RETURN, s.phase(), "a level change the bot SAW ends the engagement at once (not lost sight)");
    }

    @Test
    void aTargetThatBecameCreativeInViewIsDropped() {
        steve.x = 8;
        assertTrue(ticksUntilChase(40) > 0);
        steve.creative = true;
        s.run(1);
        assertEquals(List.of("clear Warden7"), s.up.callsOf("clear"));
    }

    @Test
    void theHomeIsWhereTheFirstHuntBegan() {
        bot.x = 5;
        bot.z = 7;
        steve.x = 13;
        steve.z = 7;
        assertTrue(ticksUntilChase(40) > 0);
        Pos home = s.controller.homeOf("Warden7");
        assertNotNull(home);
        assertEquals(5.0, home.x(), 1e-9);
        assertEquals(7.0, home.z(), 1e-9);
    }

    // ---------------------------------------------------------------- the damage-level gate (mayAttackPlayer)

    @Test
    void noPlayerMayBeHurtWithoutAConfirmedEngagementWithExactlyThatPlayer() {
        s.run(1);
        assertFalse(s.controller.mayAttackPlayer("Warden7", "Steve"), "idle: PvP BOT's revenge or stale target may not strike");
        steve.x = 8;
        assertTrue(ticksUntilChase(40) > 0);
        assertTrue(s.controller.mayAttackPlayer("Warden7", "Steve"));
        assertTrue(s.controller.mayAttackPlayer("Warden7", "steve"), "names compare ignoring case");
        assertFalse(s.controller.mayAttackPlayer("Warden7", "Alex"), "not that player");
    }

    @Test
    void theGateIsOpenWhenNothingIsManagedOrSomebodyElseForcedTheTarget() {
        s.config = s.config.withPerception(s.config.perception().disabled());
        s.run(2);
        assertTrue(s.controller.mayAttackPlayer("Warden7", "Steve"), "perception off: no reaction time to enforce");
        Sim other = new Sim();
        other.run(1);
        other.up.forced.put("Warden7", "Steve"); // a /pvpbot command
        assertTrue(other.controller.mayAttackPlayer("Warden7", "Steve"), "an admin's forced fight is not ours to veto");
        Sim inert = new Sim();
        inert.up.settings = new Settings(true, true, true, false, false, false, false, 64.0);
        inert.run(2);
        assertTrue(inert.controller.mayAttackPlayer("Warden7", "Steve"), "PvP BOT's own acquisition is left alone");
        Sim broken = new Sim();
        broken.run(1);
        broken.up.failEverything = true;
        assertTrue(broken.controller.mayAttackPlayer("Warden7", "Steve"), "fail-open when the state cannot be read");
    }

    // ---------------------------------------------------------------- being hit

    @Test
    void aVisibleMeleeHitIsConfirmedAfterTheReactionDelayWhilePvpBotsRevengeIsHeldBack() {
        steve.x = 6; // pain: 0.5 + 1.5 * 6 / 64 = 0.6406 s -> 13 ticks
        steve.subject = Perception.Subject.player(false);
        s.hit(steve);
        s.run(1);
        assertEquals(Phase.CHASE, s.phase());
        assertNull(s.controller.confirmedTarget("Warden7"), "the hit starts a reaction, not a fight");
        assertFalse(s.up.callsOf("clear").isEmpty(), "the instant revenge is cleared on the hit tick");
        assertNull(s.up.current.get("Warden7"), "PvP BOT has no target meanwhile");
        assertFalse(s.controller.mayAttackPlayer("Warden7", "Steve"), "so no counter-hit lands inside the window");
        s.run(12);
        assertEquals(List.of(), s.up.callsOf("set"), "not yet: 12 ticks after the hit tick");
        assertTrue(s.up.lookCalls > 0, "the bot turns to the pain");
        s.run(1);
        assertEquals(1, sets());
        assertEquals(List.of("set Warden7->Steve"), s.up.callsOf("set"));
        assertEquals(Cause.HIT, s.controller.causeOf("Warden7"));
        assertTrue(s.controller.mayAttackPlayer("Warden7", "Steve"));
    }

    @Test
    void sneakingDoesNotSlowTheReactionToPain() {
        steve.x = 6;
        steve.subject = Perception.Subject.player(true);
        s.hit(steve);
        s.run(14);
        assertEquals(1, sets(), "13 ticks, exactly as for a player who does not sneak");
    }

    @Test
    void aSecondHitDuringTheDelayDoesNotRestartIt() {
        steve.x = 6;
        s.hit(steve);
        s.run(9);
        s.hit(steve);
        s.run(5); // 14 ticks after the first hit tick
        assertEquals(1, sets(), "confirmed 13 ticks after the FIRST hit");
    }

    @Test
    void aDifferentLatestAttackerReplacesAnAlreadyConfirmedChase() {
        steve.x = 8;
        assertTrue(ticksUntilChase(40) > 0);
        assertEquals("Steve", s.controller.confirmedTarget("Warden7"));

        Person alex = s.world.add("Alex", 4);
        s.hit(alex);
        s.run(1);

        assertEquals(Phase.CHASE, s.phase());
        assertEquals("Alex", s.controller.engagedWith("Warden7"));
        assertNull(s.controller.confirmedTarget("Warden7"), "the old forced target must be cleared before retaliation");
        assertTrue(s.up.callsOf("clear").size() >= 1);
        s.run(13);
        assertEquals("Alex", s.controller.confirmedTarget("Warden7"));
    }

    @Test
    void aProjectileFromAnUnseenShooterSendsTheBotAlongTheIncomingLine() {
        steve.x = 20;
        steve.z = 5;
        bot.blind.add("Steve");
        s.up.walkSpeed = 0.2;
        bot.look = new double[]{-1, 0}; // facing away from the shot
        bot.traceEnd = new Pos(4.6, 64.9, 0.0); // the first block on the line: a wall
        s.shot(steve, 1, 0);
        s.run(2);
        assertEquals(Phase.PURSUE, s.phase());
        assertEquals(List.of(), s.up.callsOf("set"), "an unseen shooter is not chased through the wall");
        assertFalse(s.up.callsOf("clear").isEmpty(), "PvP BOT's revenge is cleared");
        assertNotNull(bot.lastTrace, "the incoming line was traced back");
        assertEquals(1.0, bot.lastTrace[0], 1e-9, "from the reverse of the projectile's velocity");
        assertEquals(0.0, bot.lastTrace[2], 1e-9);
        assertEquals(Perception.ENGAGE_LIMIT, bot.lastTrace[3], 1e-9, "at most the engage limit");
        Pos goal = s.planner.goals.get(0);
        assertEquals(4.6, goal.x(), 1e-9, "it goes to where the line ends, NOT to the shooter's position");
        assertEquals(0.0, goal.z(), 1e-9);
        assertNotNull(bot.lookedAt);
        assertTrue(bot.lookedAt.x() > bot.x + 5, "it turned to look along the incoming direction");
        assertTrue(s.controller.hasHome("Warden7"), "the home is where the bot stood when it was hit");
        assertTrue(s.controller.describe("Warden7").startsWith("pursuing the shot's line"), s.controller.describe("Warden7"));
    }

    @Test
    void anUnblockedShotLineRunsToTheEngageLimit() {
        bot.blind.add("Steve");
        steve.x = 90;
        s.shot(steve, 1, 0);
        s.run(2);
        assertEquals(Perception.ENGAGE_LIMIT, s.planner.goals.get(0).x(), 1e-9, "no block on the line: it ends at the engage limit, not at the shooter");
    }

    @Test
    void aProjectileFromAShooterThatIsSeenIsChasedOnlyAfterTheReactionTime() {
        steve.x = 15; // in plain view, 15 blocks ahead: 0.5 + 1.5 * 15 / 64 = 0.852 s
        s.shot(steve, 1, 0);
        s.run(14);
        assertEquals(List.of(), s.up.callsOf("set"), "it turned and looks, but the reaction time applies");
        int ticks = ticksUntilChase(60);
        assertTrue(ticks > 0 && ticks <= 8, "noticed by sight once the exposure ran long enough: " + ticks);
        assertEquals("Steve", s.controller.confirmedTarget("Warden7"));
    }

    @Test
    void aProjectileFromBeyondTheEngageLimitIsNeverEngagedOnlyInvestigatedAlongItsLine() {
        // RULES no magic knowledge: the hit gives a DIRECTION only. A hit starts the hunt (RULES 'Engagement') as an
        // investigation toward where the line ends (at most the engage limit away from the bot); no forced target and no CHASE
        // without a sighting within 16 blocks.
        steve.x = 70;
        s.shot(steve, 1, 0);
        s.run(2);
        assertEquals(Phase.PURSUE, s.phase(), "an investigation toward the shot is allowed");
        assertEquals(List.of(), s.up.callsOf("set"), "but never an engagement");
        assertEquals(Perception.ENGAGE_LIMIT, bot.lastTrace[3], 1e-9, "the traced line is at most the engage limit long");
        assertTrue(s.planner.goals.get(0).x() <= bot.x + Perception.ENGAGE_LIMIT + 1e-9,
                "it goes to a point within 16 blocks of where it was hit, not to the shooter at 70");
        for (int i = 0; i < 200; i++) {
            s.run(1);
            assertNotEquals(Phase.CHASE, s.phase());
        }
        assertEquals(List.of(), s.up.callsOf("set"), "a shooter sighted beyond 16 is never engaged");
    }

    @Test
    void aProjectileOfAnyShooterSendsTheBotAlongTheLineButNeverEngagesAnyone() {
        // A mob's or an ally's arrow looks the same as a player's: the bot knows a direction, not an owner (no magic), so
        // it investigates the same way, and never starts a CHASE or forces a target from the hit alone.
        steve.x = 20;
        bot.blind.add("Steve");
        bot.hitQueue.add(AggroFakes.projectile(bot, 1, 0)); // no revenge target, no shooter information at all
        s.run(2);
        assertEquals(Phase.PURSUE, s.phase());
        assertEquals(List.of(), s.up.callsOf("set"));
    }

    @Test
    void aMeleeHitByAnAttackerItCannotSeeGoesToWhereTheBlowCameFrom() {
        steve.x = 2;
        bot.blind.add("Steve");
        s.hit(steve);
        s.run(2);
        assertEquals(Phase.PURSUE, s.phase());
        assertEquals(List.of(), s.up.callsOf("set"));
    }

    @Test
    void aHitByAnAttackerThatIsNoValidTargetIsIgnoredLikeAnyOtherCandidate() {
        // the hit feed alone (PvP BOT's revenge is off), so only this controller decides
        steve.x = 6;
        s.up.settings = new Settings(true, false, false, false, false, false, false, 64.0);
        bot.hitQueue.add(AggroFakes.melee(bot, steve));
        s.run(12);
        assertEquals(Phase.IDLE, s.phase(), "targetPlayers is off: a player hit does not widen who is hunted");
        assertEquals(List.of(), s.up.callsOf("set"));
        s.up.settings = new Settings(true, false, true, false, false, false, false, 64.0);
        s.up.listed.add("Steve");
        bot.hitQueue.add(AggroFakes.melee(bot, steve));
        s.run(12);
        assertEquals(Phase.IDLE, s.phase(), "another PvP BOT bot without targetOtherBots");
        s.up.settings = new Settings(true, false, true, true, false, false, false, 64.0);
        bot.hitQueue.add(AggroFakes.melee(bot, steve));
        s.run(2);
        assertEquals(Phase.CHASE, s.phase(), "with targetOtherBots it is hunted");
    }

    @Test
    void aHitByAFactionAllyIsIgnoredUnlessFriendlyFire() {
        s.up.settings = new Settings(true, false, true, false, false, true, false, 64.0);
        s.up.allies.add("Warden7,Steve");
        steve.x = 6;
        bot.hitQueue.add(AggroFakes.melee(bot, steve));
        s.run(12);
        assertEquals(Phase.IDLE, s.phase());
        assertEquals(List.of(), s.up.callsOf("set"));
    }

    @Test
    void aHitIsNoticedThroughTheHitFeedEvenWithoutPvpBotsRevenge() {
        steve.x = 6;
        bot.hitQueue.add(AggroFakes.melee(bot, steve)); // PvP BOT's revenge did not fire; the damage feed still reports
        s.run(1);
        assertEquals(Phase.CHASE, s.phase());
        assertEquals(List.of(), s.up.callsOf("set"));
        s.run(13);
        assertEquals(1, sets());
    }

    @Test
    void aHitByAMobIsPvpBotsBusinessNotAPlayerHunt() {
        Person zombie = s.world.add("Zombie", 3);
        zombie.player = false;
        s.up.lastAttacker.put("Warden7", zombie);
        s.run(1);
        assertEquals(Phase.CHASE, s.phase());
        assertEquals(List.of(), s.up.callsOf("clear"), "PvP BOT's native revenge fights the mob");
        assertEquals(List.of(), s.up.callsOf("set"));
    }

    @Test
    void aHitByAnExemptPlayerIsNotRetaliatedAgainst() {
        steve.creative = true; // the wrapper does not hunt an exempt player, and PvP BOT's revenge is not kept for it
        steve.x = 4;
        s.hit(steve);
        s.run(1);
        assertEquals(Phase.IDLE, s.phase());
        assertEquals(List.of(), s.up.callsOf("set"));
    }

    @Test
    void pvpBotsRevengeIsNeverInformationAboutAnUnseenShooter() {
        // A revenge target without any hit felt (the hit poller missed it): dropped, not followed.
        steve.x = 30;
        bot.blind.add("Steve");
        s.up.lastAttacker.put("Warden7", steve);
        s.run(3);
        assertEquals(Phase.IDLE, s.phase());
        assertFalse(s.up.callsOf("clear").isEmpty());
        assertEquals(List.of(), s.planner.goals, "it did not walk to the player's true position");
    }

    // ---------------------------------------------------------------- mobs

    @Test
    void aMobFightIsTrackedAndWalkedAwayFromWhenSightIsLost() {
        Person zombie = s.world.add("Zombie", 3);
        zombie.player = false;
        s.up.other.put("Warden7", zombie);
        s.up.walkSpeed = 0.2;
        s.run(2);
        assertEquals(Phase.CHASE, s.phase());
        assertEquals("Zombie", s.controller.engagedWith("Warden7"));
        bot.x = 15; // the fight took the bot away from its post
        bot.blind.add("Zombie");
        s.run(10);
        assertEquals(List.of("clear Warden7"), s.up.callsOf("clear"));
        assertEquals(Phase.RETURN, s.phase(), "no pursuit and no search for a mob: straight home");
        int back = s.runUntil(() -> s.phase() == Phase.IDLE, 200);
        assertTrue(back <= 200, "walked home");
        assertTrue(bot.x < 1.6, "back within the arrive distance: " + bot.x);
        assertFalse(s.controller.hasHome("Warden7"));
    }

    @Test
    void aMobFightThatEndsSendsTheBotHome() {
        Person zombie = s.world.add("Zombie", 3);
        zombie.player = false;
        s.up.other.put("Warden7", zombie);
        s.run(2);
        bot.x = 9;
        zombie.alive = false; // dead: PvP BOT's target is gone
        s.up.walkSpeed = 0.2;
        s.run(2);
        assertEquals(Phase.RETURN, s.phase());
    }

    // ---------------------------------------------------------------- somebody else's forced target

    @Test
    void anExternalForcedTargetIsTrackedButNeverClearedOrReturnedFrom() {
        bot.x = 10;
        s.up.forced.put("Warden7", "Steve"); // a /pvpbot command
        steve.x = 25;
        bot.blind.add("Steve");
        s.run(400);
        assertEquals(List.of(), s.up.callsOf("clear"));
        assertEquals(List.of(), s.up.callsOf("steer"));
        assertEquals(List.of(), s.up.callsOf("set"));
        assertEquals(Phase.CHASE, s.phase());
        assertFalse(s.controller.hasHome("Warden7"), "an external target has no home to return to");
        assertTrue(s.controller.describe("Warden7").contains("forced by someone else"));
    }

    @Test
    void whenTheExternalForceIsRemovedTheBotIsIdleAgain() {
        s.up.forced.put("Warden7", "Steve");
        steve.x = 25;
        s.run(5);
        s.up.forced.remove("Warden7");
        s.up.current.clear();
        s.run(2);
        assertEquals(Phase.IDLE, s.phase());
    }

    @Test
    void aForcedNameThatAppearsDuringOurChaseIsExternalUnlessItIsTheAttacker() {
        steve.x = 8;
        assertTrue(ticksUntilChase(40) > 0);
        // PvP BOT writes the attacker's name itself (wind burst): still our chase, still hunted by us
        s.up.forced.put("Warden7", "Steve");
        s.run(3);
        assertEquals(Phase.CHASE, s.phase());
        assertFalse(s.controller.describe("Warden7").contains("forced by someone else"));
        // somebody else's force on another player: external now
        Person alex = s.world.add("Alex", 20);
        s.up.forced.put("Warden7", "Alex");
        s.run(2);
        assertTrue(s.controller.describe("Warden7").contains("forced by someone else"), s.controller.describe("Warden7"));
        bot.blind.add("Alex");
        s.run(300);
        assertEquals(List.of(), s.up.callsOf("clear"), "an external target is never given up on");
    }

    @Test
    void aForcedNameThatIsAlreadyThereWhenARevengeTargetStartsTheHuntIsNotExternal() {
        // PvP BOT's own wind-burst flow wrote the attacker's name as the forced target before the hunt began
        steve.x = 6;
        s.up.forced.put("Warden7", "Steve");
        s.hit(steve);
        s.run(1);
        assertEquals(Phase.CHASE, s.phase());
        assertFalse(s.controller.describe("Warden7").contains("forced by someone else"), s.controller.describe("Warden7"));
        assertTrue(s.controller.hasHome("Warden7"), "a hunt of ours has a home to return to (an external one has none)");
    }

    @Test
    void aMobFightWhoseForcedNameIsThatMobStaysOurs() {
        Person zombie = s.world.add("Zombie", 3);
        zombie.player = false;
        s.up.other.put("Warden7", zombie);
        s.run(2);
        s.up.forced.put("Warden7", "Zombie"); // PvP BOT's own flow names its target
        s.run(2);
        bot.blind.add("Zombie");
        s.run(10);
        assertEquals(List.of("clear Warden7"), s.up.callsOf("clear"), "still judged like any mob fight");
    }

    // ---------------------------------------------------------------- who counts as a target

    @Test
    void creativeAndSpectatorPlayersAreNotNoticedUnlessAttackInvincible() {
        steve.x = 8;
        steve.creative = true;
        s.run(100);
        assertEquals(List.of(), s.up.callsOf("set"));
        steve.creative = false;
        steve.spectator = true;
        s.run(100);
        assertEquals(List.of(), s.up.callsOf("set"));
        s.up.settings = new Settings(true, false, true, false, true, false, false, 64.0);
        assertTrue(ticksUntilChase(40) > 0, "attackInvincible: they are valid");
    }

    @Test
    void otherPvpBotBotsOnlyWhenTargetOtherBots() {
        s.up.listed.add("Steve");
        steve.x = 8;
        s.run(100);
        assertEquals(List.of(), s.up.callsOf("set"));
        s.up.settings = new Settings(true, false, true, true, false, false, false, 64.0);
        assertTrue(ticksUntilChase(40) > 0);
    }

    @Test
    void realPlayersOnlyWhenTargetPlayers() {
        s.up.settings = new Settings(true, false, false, false, false, false, false, 64.0);
        steve.x = 8;
        s.run(100);
        assertEquals(List.of(), s.up.callsOf("set"));
    }

    @Test
    void factionAlliesAreSkippedUnlessFriendlyFire() {
        s.up.settings = new Settings(true, false, true, false, false, true, false, 64.0);
        s.up.allies.add("Warden7,Steve");
        steve.x = 8;
        s.run(100);
        assertEquals(List.of(), s.up.callsOf("set"));
        assertTrue(s.up.areAlliesCalls > 0);
        s.up.settings = new Settings(true, false, true, false, false, true, true, 64.0);
        assertTrue(ticksUntilChase(40) > 0, "friendly fire: allies are fair game");
    }

    @Test
    void aFactionCheckThatFailsMeansNoTargetAndOneWarning() {
        s.up.settings = new Settings(true, false, true, false, false, true, false, 64.0);
        s.up.failEverything = true;
        steve.x = 8;
        s.run(60);
        assertEquals(List.of(), s.up.callsOf("set"));
    }

    @Test
    void theNearestPlayerIsTakenFirstAndOnlyOneTargetIsSet() {
        Person alex = s.world.add("Alex", 6);
        steve.x = 4;
        assertTrue(ticksUntilChase(40) > 0);
        assertEquals(List.of("set Warden7->Steve"), s.up.callsOf("set"), "Steve is nearer than Alex");
    }

    @Test
    void aBotNeverTargetsItselfNorAnotherInhabitantByDefault() {
        s.world.addInhabitant("Ranger3", 5);
        s.up.listed.add("Ranger3");
        steve.x = 90;
        s.world.online.get("ranger3").look = new double[]{1, 0};
        s.run(100);
        assertTrue(s.up.callsOf("set").stream().noneMatch(c -> c.contains("->Ranger3") || c.contains("->Warden7")),
                s.up.calls.toString());
    }

    // ---------------------------------------------------------------- modes

    @Test
    void whenPvpBotsAutoTargetIsOnNothingIsNoticedHereAndItSaysSoOnce() {
        s.up.settings = new Settings(true, true, true, false, false, false, false, 64.0);
        steve.x = 8;
        s.run(100);
        assertEquals(Mode.INERT_AUTO_TARGET, s.controller.mode());
        assertEquals(List.of(), s.up.callsOf("set"));
        assertEquals(1, s.log.info.stream().filter(l -> l.contains("auto-target is on")).count());
    }

    @Test
    void inInertAutoTargetModeAHeldTargetIsStillSupervised() {
        s.up.settings = new Settings(true, true, true, false, false, false, false, 64.0);
        s.up.other.put("Warden7", steve);
        steve.x = 8;
        s.run(2);
        assertEquals(Phase.CHASE, s.phase());
        assertEquals(List.of(), s.up.callsOf("clear"), "PvP BOT's own acquisition is left alone: no reaction delay games");
    }

    @Test
    void whenPvpBotsCombatIsOffNothingHappens() {
        s.up.settings = new Settings(false, false, true, false, false, false, false, 64.0);
        steve.x = 8;
        s.run(100);
        assertEquals(Mode.INERT_COMBAT_OFF, s.controller.mode());
        assertEquals(List.of(), s.up.callsOf("set"));
    }

    @Test
    void unreadableSettingsMeanIdleWithOneWarning() {
        s.up.settingsFail = true;
        steve.x = 8;
        s.run(100);
        assertEquals(Mode.SETTINGS_UNREADABLE, s.controller.mode());
        assertEquals(List.of(), s.up.callsOf("set"));
        assertEquals(1, s.log.warn.stream().filter(l -> l.contains("settings could not be read")).count());
    }

    @Test
    void aMissingUpstreamMemberDisablesTheControllerCleanly() {
        s.up.available = false;
        steve.x = 8;
        s.run(50);
        assertEquals(Mode.UPSTREAM_UNAVAILABLE, s.controller.mode());
        assertEquals("unavailable", s.controller.describe("Warden7"));
        assertEquals(1, s.log.warn.stream().filter(l -> l.contains("target control unavailable")).count());
    }

    @Test
    void repeatedUpstreamFailuresSwitchItOffAfterTwentyInARow() {
        s.up.failEverything = true;
        s.up.other.put("Warden7", steve);
        s.run(60);
        assertEquals(Mode.FAILED, s.controller.mode());
        assertEquals("failed", s.controller.describe("Warden7"));
    }

    @Test
    void switchedOffByTheConfigurationDropsEverything() {
        steve.x = 8;
        assertTrue(ticksUntilChase(40) > 0);
        s.config = new AggroController.Config(false, true, 10, 200, 1.5, 40, 1200, 3, s.config.perception());
        s.run(2);
        assertEquals(Mode.OFF, s.controller.mode());
        assertEquals(Phase.IDLE, s.phase());
        assertEquals("off", s.controller.describe("Warden7"));
    }

    // ---------------------------------------------------------------- bots that die, leave, restart

    @Test
    void aBotThatDiedDropsItsHuntAndItsHome() {
        steve.x = 8;
        assertTrue(ticksUntilChase(40) > 0);
        assertTrue(s.controller.hasHome("Warden7"));
        bot.alive = false;
        s.run(1);
        assertEquals(Phase.IDLE, s.phase());
        assertFalse(s.controller.hasHome("Warden7"));
    }

    @Test
    void aBotThatLeftDropsItsHuntAndReleasesOurForce() {
        steve.x = 8;
        assertTrue(ticksUntilChase(40) > 0);
        s.world.online.remove("warden7");
        s.world.inhabitants.remove("warden7");
        s.run(1);
        assertFalse(s.controller.hasHome("Warden7"));
        assertEquals(List.of("clear Warden7"), s.up.callsOf("clear"), "the force this controller set is released");
    }

    @Test
    void aServerRestartForgetsEverything() {
        steve.x = 8;
        assertTrue(ticksUntilChase(40) > 0);
        s.up.forced.clear(); // PvP BOT restarted too
        s.up.current.clear();
        s.now = 5; // ticks restarted at 0
        s.run(1);
        assertEquals(Phase.IDLE, s.phase());
        assertFalse(s.controller.hasHome("Warden7"));
    }

    // ---------------------------------------------------------------- cost

    @Test
    void anIdleBotWithNobodyAroundCostsNoRays() {
        s.world.online.remove("steve");
        s.run(300);
        assertEquals(0, bot.canSeeCalls);
    }

    @Test
    void anIdleBotScansOncePerScanIntervalNotEveryTick() {
        steve.x = 90;
        bot.look = new double[]{-1, 0}; // behind: never becomes exposure, so scans stay sparse
        s.world.playersWithinCalls = 0;
        s.run(300);
        assertTrue(s.world.playersWithinCalls <= 101, "about every 3 ticks: " + s.world.playersWithinCalls);
        assertTrue(s.world.playersWithinCalls >= 99, "and not less often: " + s.world.playersWithinCalls);
    }

    @Test
    void manyBotsAreStaggeredAcrossTheScanInterval() {
        for (int i = 0; i < 30; i++) {
            Person p = s.world.addInhabitant("Guard" + i, 40 + i);
            p.look = new double[]{-1, 0};
            s.up.listed.add(p.name); // PvP BOT's own bots are not each other's targets
        }
        s.up.listed.add("Warden7");
        steve.x = 100; // behind the guards (they look west), in front of Warden7
        steve.z = 0;
        s.world.playersWithinCalls = 0;
        s.run(3);
        // 31 bots, 3 ticks: each is scanned once, not all in the same tick
        assertTrue(s.world.playersWithinCalls >= 30 && s.world.playersWithinCalls <= 34, "" + s.world.playersWithinCalls);
    }

    @Test
    void theLogHasAtMostOneInfoLinePerBotEveryTenSeconds() {
        // a bot that keeps reacting to hits: many transitions, one INFO per 200 ticks
        steve.x = 6;
        for (int i = 0; i < 6; i++) {
            s.hit(steve);
            s.run(30);
        }
        long infos = s.log.info.stream().filter(l -> l.contains("Warden7")).count();
        assertTrue(infos <= 2, "rate limited: " + infos + " " + s.log.info);
    }

    @Test
    void theStatsCountWhatHappened() {
        steve.x = 8;
        assertTrue(ticksUntilChase(40) > 0);
        AggroController.Stats st = s.controller.stats();
        assertEquals(1, st.acquisitions());
        assertEquals(1, st.chasing());
        assertEquals(Mode.ACTIVE, st.mode());
    }
}
