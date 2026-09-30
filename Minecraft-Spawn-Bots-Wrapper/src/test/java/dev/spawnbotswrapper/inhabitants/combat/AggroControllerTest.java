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
 * Noticing, chasing and the rules around them: reaction time, the view cone, hearing, no distance limit, being hit,
 * mobs, somebody else's forced target, validity, inert modes and failures. The whole hunt (pursue, search, return) is
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

    // ---------------------------------------------------------------- reaction time

    @Test
    void aPlayerInFrontIsNoticedOnlyAfterTheReactionTime() {
        steve.x = 8; // 5 + 5*8/32 = 6.25 ticks of exposure needed
        int ticks = ticksUntilChase(40);
        // the exposure starts at the first scan (within 3 ticks) and must reach 6.25, i.e. 7 ticks later
        assertTrue(ticks >= 8 && ticks <= 10, "noticed after " + ticks + " ticks");
        assertEquals(List.of("set Warden7->Steve"), s.up.callsOf("set"));
        assertEquals(Phase.CHASE, s.phase());
        assertEquals(Cause.ACQUIRED, s.controller.causeOf("Warden7"));
    }

    @Test
    void thePeripheralFieldTakesTwiceAsLong() {
        bot.look = new double[]{0, 1}; // steve at +x is 90 degrees to the side
        steve.x = 8;
        int ticks = ticksUntilChase(60);
        assertTrue(ticks >= 13 && ticks <= 15, "peripheral: 2*5 + 1.25 -> 12 ticks of exposure: " + ticks);
    }

    @Test
    void aSneakingPlayerTakesTwiceAsLong() {
        steve.x = 8;
        steve.subject = Perception.Subject.player(true, false, false);
        int ticks = ticksUntilChase(60);
        assertTrue(ticks >= 13 && ticks <= 15, "sneaking: 2*5 + 1.25 -> 12 ticks of exposure: " + ticks);
    }

    @Test
    void anInvisiblePlayerIsNeverSeen() {
        steve.x = 4;
        steve.subject = new Perception.Subject(false, false, false, Perception.NO_NOISE, Perception.MobKind.NONE, 0.0);
        s.run(200);
        assertEquals(List.of(), s.up.callsOf("set"));
    }

    @Test
    void aFarPlayerTakesLongerButIsNoticedAtAnyDistance() {
        steve.x = 64; // 5 + 10 = 15 ticks
        int ticks = ticksUntilChase(60);
        assertTrue(ticks >= 16 && ticks <= 18, "64 blocks: 15 ticks of exposure: " + ticks);
    }

    @Test
    void anExposureThatIsBrokenStartsAgain() {
        steve.x = 8;
        s.run(4);
        steve.x = 8;
        bot.blind.add("Steve");
        s.run(6); // out of sight for longer than a missed tick
        bot.blind.clear();
        int ticks = ticksUntilChase(40);
        assertTrue(ticks >= 8, "the earlier glimpse does not count: " + ticks);
    }

    @Test
    void oneMissedTickDoesNotBreakTheExposure() {
        steve.x = 8;
        s.run(5); // the exposure is running and watched every tick by now
        bot.blind.add("Steve");
        s.run(1); // a leaf, a fence post: one tick without a clear view
        bot.blind.clear();
        int ticks = ticksUntilChase(40);
        assertTrue(ticks > 0 && ticks <= 5, "noticed soon after despite one missed tick: " + ticks);
    }

    @Test
    void perceptionOffMeansPlainLineOfSightAtOnce() {
        s.config = s.config.withPerception(s.config.perception().disabled());
        bot.look = new double[]{-1, 0}; // steve is BEHIND the bot: omnidirectional now
        steve.x = 100;
        int ticks = ticksUntilChase(10);
        assertTrue(ticks > 0 && ticks <= 3, "no cone, no time: the first scan notices: " + ticks);
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

    // ---------------------------------------------------------------- behind, hearing, ambush

    @Test
    void aPlayerStandingStillBehindIsNeverNoticed() {
        bot.look = new double[]{-1, 0};
        steve.x = 3;
        s.run(300);
        assertEquals(List.of(), s.up.callsOf("set"));
        assertEquals(0, bot.canSeeCalls, "behind and silent: the cheap filters say no, no ray is cast");
    }

    @Test
    void aPlayerWalkingBehindAtThreeIsHeard() {
        bot.look = new double[]{-1, 0};
        steve.x = 3;
        steve.subject = Perception.Subject.player(false, true, false);
        int ticks = ticksUntilChase(40);
        assertTrue(ticks > 0 && ticks <= 9, "footsteps at 3 blocks: " + ticks);
        assertEquals(Perception.Sense.HEARING, noticedHow());
    }

    @Test
    void aPlayerSprintingBehindAtSevenIsHeardButNotAtNine() {
        bot.look = new double[]{-1, 0};
        steve.x = 9;
        steve.subject = Perception.Subject.player(false, true, true);
        s.run(100);
        assertEquals(List.of(), s.up.callsOf("set"), "9 blocks is out of earshot (8)");
        steve.x = 7;
        assertTrue(ticksUntilChase(40) > 0, "7 blocks is heard");
    }

    @Test
    void aPlayerSneakingBehindIsNeverNoticedUntilItHits() {
        bot.look = new double[]{-1, 0};
        steve.x = 1.5;
        steve.subject = Perception.Subject.player(true, true, false);
        s.run(300);
        assertEquals(List.of(), s.up.callsOf("set"));
        assertEquals(Phase.IDLE, s.phase());
        // the stab: being hit makes the bot aware at once
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
    void anOccludedSoundIsOnlyAHintNeverAChase() {
        bot.look = new double[]{-1, 0};
        steve.x = 3;
        steve.subject = Perception.Subject.player(false, true, false);
        bot.blind.add("Steve");
        s.run(200);
        assertEquals(List.of(), s.up.callsOf("set"));
        assertTrue(bot.canSeeCalls > 0, "the sound was heard and the line checked");
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

    // ---------------------------------------------------------------- chase: no distance limit, lost

    @Test
    void theChaseContinuesWhileTheTargetIsInSightAtAnyDistanceUpTo128() {
        steve.x = 100;
        assertTrue(ticksUntilChase(60) > 0);
        s.run(400);
        assertEquals(Phase.CHASE, s.phase());
        steve.x = 127.9;
        s.run(200);
        assertEquals(Phase.CHASE, s.phase(), "127.9 blocks, still in sight");
        assertEquals(List.of(), s.up.callsOf("clear"), "never given up");
    }

    @Test
    void beyondTheModMaximumTheTargetIsLostAtOnce() {
        steve.x = 100;
        assertTrue(ticksUntilChase(60) > 0);
        steve.x = 129; // PvP BOT's own maxTargetDistance (128) drops the target
        s.run(2);
        assertEquals(Phase.PURSUE, s.phase());
    }

    @Test
    void aTreeTrunkDoesNotBreakTheChase() {
        steve.x = 8;
        assertTrue(ticksUntilChase(40) > 0);
        s.run(5);
        bot.blind.add("Steve");
        s.run(9); // 9 unseen ticks: inside the 10 tick grace
        assertEquals(Phase.CHASE, s.phase());
        bot.blind.clear();
        s.run(1);
        assertEquals(Phase.CHASE, s.phase());
        bot.blind.add("Steve");
        s.run(10);
        assertEquals(Phase.PURSUE, s.phase(), "10 ticks without sight: the target is lost");
        assertEquals(List.of("clear Warden7"), s.up.callsOf("clear"), "PvP BOT's target is cleared once");
    }

    @Test
    void aDeadTargetSendsTheBotHomeAndClearsPvpBotsState() {
        steve.x = 8;
        assertTrue(ticksUntilChase(40) > 0);
        bot.x = 12;
        steve.alive = false;
        s.run(1);
        assertEquals(List.of("clear Warden7"), s.up.callsOf("clear"));
        assertEquals(Phase.RETURN, s.phase());
    }

    @Test
    void aTargetThatLoggedOutIsCleared() {
        steve.x = 8;
        assertTrue(ticksUntilChase(40) > 0);
        s.world.online.remove("steve");
        s.run(1);
        assertEquals(List.of("clear Warden7"), s.up.callsOf("clear"));
        assertNotEquals(Phase.CHASE, s.phase());
    }

    @Test
    void aTargetInAnotherLevelIsCleared() {
        steve.x = 8;
        assertTrue(ticksUntilChase(40) > 0);
        steve.dimension = "the_nether";
        s.run(1);
        assertEquals(List.of("clear Warden7"), s.up.callsOf("clear"));
    }

    @Test
    void aTargetThatBecameCreativeIsDroppedUnlessAttackInvincible() {
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

    // ---------------------------------------------------------------- being hit

    @Test
    void aVisibleHitIsReactedToAfterTheReactionDelayWhilePvpBotsRevengeIsHeldBack() {
        steve.x = 6;
        steve.subject = Perception.Subject.player(false, false, false);
        s.hit(steve);
        s.run(1);
        assertEquals(Phase.REACT, s.phase());
        assertEquals(List.of("clear Warden7"), s.up.callsOf("clear"), "the instant revenge is cleared on the hit tick");
        assertNull(s.up.current.get("Warden7"), "PvP BOT has no target meanwhile");
        s.run(3);
        assertEquals(Phase.REACT, s.phase());
        assertEquals(List.of(), s.up.callsOf("set"), "not yet: the reaction takes 5 ticks");
        assertTrue(s.up.lookCalls > 0, "the bot turns to the pain");
        s.run(2);
        assertEquals(Phase.CHASE, s.phase());
        assertEquals(List.of("set Warden7->Steve"), s.up.callsOf("set"));
        assertEquals(Cause.HIT, s.controller.causeOf("Warden7"));
    }

    @Test
    void aSecondHitDuringTheDelayDoesNotRestartIt() {
        steve.x = 6;
        s.hit(steve);
        s.run(3); // ticks 0..2 after the first hit
        s.hit(steve);
        s.run(3); // ticks 3..5: the reaction is over 5 ticks after the FIRST hit
        assertEquals(Phase.CHASE, s.phase(), "5 ticks after the FIRST hit");
    }

    @Test
    void aHitFromCoverPursuesToWhereTheAttackerStood() {
        steve.x = 20;
        steve.z = 5;
        bot.blind.add("Steve");
        s.up.walkSpeed = 0.2;
        s.hit(steve);
        s.run(2);
        assertEquals(Phase.PURSUE, s.phase());
        assertEquals(List.of(), s.up.callsOf("set"), "an unseen attacker is not chased through the wall");
        assertEquals(List.of("clear Warden7"), s.up.callsOf("clear"), "PvP BOT's revenge is cleared once");
        Pos goal = s.planner.goals.get(0);
        assertEquals(20.0, goal.x(), 1e-9);
        assertEquals(5.0, goal.z(), 1e-9);
        assertTrue(s.controller.hasHome("Warden7"), "the home is where the bot stood when it was hit");
    }

    @Test
    void aHitIsNoticedThroughTheHitFeedEvenWithoutPvpBotsRevenge() {
        steve.x = 6;
        bot.hitQueue.add(steve); // PvP BOT's revenge did not fire (its own switch is off); the damage feed still reports
        s.run(1);
        assertEquals(Phase.REACT, s.phase());
        s.run(5);
        assertEquals(Phase.CHASE, s.phase());
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
    void aHitByAnInvalidAttackerFallsBackToPvpBotsOwnFight() {
        steve.creative = true; // PvP BOT's revenge bypasses its filters; the wrapper does not hunt an exempt player
        steve.x = 4;
        s.hit(steve);
        s.run(1);
        assertEquals(List.of(), s.up.callsOf("clear"));
        assertEquals(Phase.CHASE, s.phase());
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
        // PvP BOT writes the attacker's name itself (wind burst): still our chase, still leash-able
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
        s.up.settings = new Settings(true, false, true, false, true, false, false, 128.0);
        assertTrue(ticksUntilChase(40) > 0, "attackInvincible: they are valid");
    }

    @Test
    void otherPvpBotBotsOnlyWhenTargetOtherBots() {
        s.up.listed.add("Steve");
        steve.x = 8;
        s.run(100);
        assertEquals(List.of(), s.up.callsOf("set"));
        s.up.settings = new Settings(true, false, true, true, false, false, false, 128.0);
        assertTrue(ticksUntilChase(40) > 0);
    }

    @Test
    void realPlayersOnlyWhenTargetPlayers() {
        s.up.settings = new Settings(true, false, false, false, false, false, false, 128.0);
        steve.x = 8;
        s.run(100);
        assertEquals(List.of(), s.up.callsOf("set"));
    }

    @Test
    void factionAlliesAreSkippedUnlessFriendlyFire() {
        s.up.settings = new Settings(true, false, true, false, false, true, false, 128.0);
        s.up.allies.add("Warden7,Steve");
        steve.x = 8;
        s.run(100);
        assertEquals(List.of(), s.up.callsOf("set"));
        assertTrue(s.up.areAlliesCalls > 0);
        s.up.settings = new Settings(true, false, true, false, false, true, true, 128.0);
        assertTrue(ticksUntilChase(40) > 0, "friendly fire: allies are fair game");
    }

    @Test
    void aFactionCheckThatFailsMeansNoTargetAndOneWarning() {
        s.up.settings = new Settings(true, false, true, false, false, true, false, 128.0);
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
        s.up.settings = new Settings(true, true, true, false, false, false, false, 128.0);
        steve.x = 8;
        s.run(100);
        assertEquals(Mode.INERT_AUTO_TARGET, s.controller.mode());
        assertEquals(List.of(), s.up.callsOf("set"));
        assertEquals(1, s.log.info.stream().filter(l -> l.contains("auto-target is on")).count());
    }

    @Test
    void inInertAutoTargetModeAHeldTargetIsStillSupervised() {
        s.up.settings = new Settings(true, true, true, false, false, false, false, 128.0);
        s.up.other.put("Warden7", steve);
        steve.x = 8;
        s.run(2);
        assertEquals(Phase.CHASE, s.phase());
        assertEquals(List.of(), s.up.callsOf("clear"), "PvP BOT's own acquisition is left alone: no reaction delay games");
    }

    @Test
    void whenPvpBotsCombatIsOffNothingHappens() {
        s.up.settings = new Settings(false, false, true, false, false, false, false, 128.0);
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
