package dev.spawnbotswrapper.inhabitants.combat;

import dev.spawnbotswrapper.inhabitants.combat.AggroController.Phase;
import dev.spawnbotswrapper.inhabitants.combat.AggroFakes.Person;
import dev.spawnbotswrapper.inhabitants.combat.AggroFakes.Sim;
import dev.spawnbotswrapper.inhabitants.combat.AggroWorld.Pos;
import dev.spawnbotswrapper.inhabitants.combat.AggroWorld.SearchSpot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hunt after the chase: lost target, pursuit to the last known position, the 10 second search, the walk home,
 * noticing again on the way, and that the bot always ends at its FIRST start point without ever teleporting.
 */
class AggroHuntTest {
    Sim s;
    Person bot;
    Person steve;

    @BeforeEach
    void setUp() {
        s = new Sim();
        bot = s.bot;
        steve = s.steve;
        bot.look = new double[]{1, 0};
        s.up.walkSpeed = 0.2; // walking speed, blocks per tick
    }

    /** Steve stands in view at 8 blocks, is noticed, and then hides (the bot can no longer see him): the chase is lost. */
    private void noticeThenLoseSteve() {
        steve.x = 8;
        assertTrue(s.runUntil(() -> s.phase() == Phase.CHASE, 40) <= 40, "noticed");
        s.run(3);
        bot.blind.add("Steve");
        assertTrue(s.runUntil(() -> s.phase() == Phase.PURSUE, 30) <= 30, "lost after the grace");
    }

    private int untilPhase(Phase p, int max) {
        return s.runUntil(() -> s.phase() == p, max);
    }

    // ---------------------------------------------------------------- the whole cycle

    @Test
    void lostThenPursueThenSearchForTenSecondsThenReturnHome() {
        noticeThenLoseSteve();
        Pos home = s.controller.homeOf("Warden7");
        assertEquals(0.0, home.x(), 1e-9);
        assertEquals(List.of("clear Warden7"), s.up.callsOf("clear"), "PvP BOT's target is cleared when the chase is lost");
        assertTrue(s.controller.describe("Warden7").startsWith("pursuing Steve's last position"), s.controller.describe("Warden7"));

        int toSearch = untilPhase(Phase.SEARCH, 200);
        assertTrue(toSearch <= 200, "arrived at the last known position and started searching");
        assertTrue(Math.abs(bot.x - 8.0) <= 1.6, "within 1.5 blocks of where Steve was last seen: " + bot.x);
        assertTrue(s.controller.describe("Warden7").startsWith("searching for Steve, "), s.controller.describe("Warden7"));

        int searched = s.runUntil(() -> s.phase() != Phase.SEARCH, 400);
        assertEquals(200, searched, 1, "the search lasts 10 s (200 ticks)");
        assertEquals(Phase.RETURN, s.phase());
        assertTrue(s.controller.describe("Warden7").startsWith("returning home, "), s.controller.describe("Warden7"));

        int back = untilPhase(Phase.IDLE, 400);
        assertTrue(back <= 400, "walked home");
        assertTrue(Math.abs(bot.x) <= 1.5 + 1e-9, "within 1.5 blocks of where the hunt began: " + bot.x);
        assertFalse(s.controller.hasHome("Warden7"), "the home is cleared on arrival");
        assertEquals("idle", s.controller.describe("Warden7"));
        assertTrue(s.up.maxStep <= 0.2 + 1e-9, "never a teleport: the largest step was " + s.up.maxStep);
        assertTrue(s.up.calls.stream().noneMatch(c -> c.startsWith("set ") && s.controller.phaseOf("Warden7") == Phase.SEARCH));
    }

    @Test
    void searchingLooksAllTheWayRoundBeforeItWalks() {
        noticeThenLoseSteve();
        untilPhase(Phase.SEARCH, 200);
        List<Pos> looks = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            s.run(1);
            if (bot.lookedAt != null) {
                looks.add(bot.lookedAt);
            }
        }
        // four quarter turns: the look targets go round the compass
        boolean plusX = looks.stream().anyMatch(p -> p.x() - bot.x > 5 && Math.abs(p.z() - bot.z) < 1);
        boolean minusX = looks.stream().anyMatch(p -> p.x() - bot.x < -5 && Math.abs(p.z() - bot.z) < 1);
        boolean plusZ = looks.stream().anyMatch(p -> p.z() - bot.z > 5 && Math.abs(p.x() - bot.x) < 1);
        boolean minusZ = looks.stream().anyMatch(p -> p.z() - bot.z < -5 && Math.abs(p.x() - bot.x) < 1);
        assertTrue(plusX && minusX && plusZ && minusZ, "swept the whole circle: " + looks.size() + " looks");
        assertTrue(s.up.haltCalls > 0, "and stood still while doing it");
    }

    @Test
    void searchWalksToTheBestSpotsAndCountsThePointsChecked() {
        s.world.spots = (b, focus) -> List.of(new SearchSpot(new Pos(12, 64, 0), 0.2), new SearchSpot(new Pos(8, 64, 8), 0.9),
                new SearchSpot(new Pos(4, 64, -6), 0.3));
        noticeThenLoseSteve();
        untilPhase(Phase.SEARCH, 200);
        s.runUntil(() -> s.controller.pointsChecked("Warden7") >= 2, 200);
        assertTrue(s.controller.pointsChecked("Warden7") >= 2, "visited several search points");
        assertTrue(s.planner.goals.stream().anyMatch(g -> g.x() == 8 && g.z() == 8), "the doorway-like spot was chosen: " + s.planner.goals);
        assertTrue(s.world.searchSpotCalls > 0);
        assertTrue(s.controller.describe("Warden7").contains("points checked"));
    }

    @Test
    void theSearchWindowIsFixedNoMatterHowManyPointsAreWalked() {
        s.world.spots = (b, focus) -> List.of(new SearchSpot(new Pos(12, 64, 0), 0.5), new SearchSpot(new Pos(8, 64, 8), 0.5),
                new SearchSpot(new Pos(4, 64, -6), 0.5), new SearchSpot(new Pos(8, 64, -9), 0.5));
        noticeThenLoseSteve();
        untilPhase(Phase.SEARCH, 200);
        int searched = s.runUntil(() -> s.phase() != Phase.SEARCH, 400);
        assertEquals(200, searched, 1);
    }

    @Test
    void aMovingPlayerIsFollowedAlongItsLastHeadingBeforeTheSearch() {
        steve.x = 8;
        assertTrue(s.runUntil(() -> s.phase() == Phase.CHASE, 40) <= 40);
        // steve walks +z at 0.25 blocks per tick in view, then disappears round a corner
        for (int i = 0; i < 12; i++) {
            steve.z += 0.25;
            s.run(1);
        }
        bot.blind.add("Steve");
        untilPhase(Phase.PURSUE, 30);
        assertEquals(Phase.PURSUE, s.phase());
        untilPhase(Phase.SEARCH, 400);
        boolean followedHeading = s.planner.goals.stream().anyMatch(g -> g.z() > steve.z + 2.5 && Math.abs(g.x() - 8) < 1.5);
        assertTrue(followedHeading, "went on a few blocks along the last heading (+z): " + s.planner.goals);
    }

    @Test
    void aStandingPlayerHasNoHeadingSoThePursuitEndsAtTheLastPosition() {
        noticeThenLoseSteve();
        untilPhase(Phase.SEARCH, 400);
        assertEquals(1, s.planner.goals.stream().filter(g -> g.x() == 8).count());
        assertTrue(s.planner.goals.stream().allMatch(g -> Math.abs(g.x() - 8) < 1e-9 && Math.abs(g.z()) < 1e-9), s.planner.goals.toString());
    }

    // ---------------------------------------------------------------- the patrol

    @Test
    void thePatrolIsPausedForThePursuitSearchAndWalkHomeAndResumedWhenHome() {
        steve.x = 8;
        assertTrue(untilPhase(Phase.CHASE, 40) <= 40);
        assertEquals(List.of(), s.up.callsOf("pause-patrol"), "PvP BOT's own combat handling covers the chase itself");
        s.run(3);
        bot.blind.add("Steve");
        untilPhase(Phase.PURSUE, 30);
        assertEquals(List.of("pause-patrol Warden7"), s.up.callsOf("pause-patrol"), "paused once, when the walking starts");
        untilPhase(Phase.SEARCH, 300);
        untilPhase(Phase.RETURN, 400);
        assertEquals(1, s.up.callsOf("pause-patrol").size());
        assertEquals(List.of(), s.up.callsOf("resume-patrol"), "still walking home");
        assertEquals(Phase.IDLE, runUntilIdle(600));
        assertEquals(List.of("resume-patrol Warden7"), s.up.callsOf("resume-patrol"));
    }

    @Test
    void theWalkHomeAfterAMobFightPausesThePatrolToo() {
        Person zombie = s.world.add("Zombie", 3);
        zombie.player = false;
        s.up.other.put("Warden7", zombie);
        s.run(2);
        bot.x = 12;
        zombie.alive = false;
        s.run(2);
        assertEquals(Phase.RETURN, s.phase());
        assertEquals(List.of("pause-patrol Warden7"), s.up.callsOf("pause-patrol"));
        assertEquals(Phase.IDLE, runUntilIdle(400));
        assertEquals(List.of("resume-patrol Warden7"), s.up.callsOf("resume-patrol"));
    }

    @Test
    void aWalkHomeThatGivesUpAlsoResumesThePatrol() {
        s.config = new AggroController.Config(true, true, 10, 200, 1.5, 40, 60, 3, s.config.perception());
        noticeThenLoseSteve();
        s.up.walkSpeed = 0.0;
        assertEquals(Phase.IDLE, runUntilIdle(1500));
        assertEquals(List.of("resume-patrol Warden7"), s.up.callsOf("resume-patrol"));
    }

    @Test
    void switchingTheControllerOffMidHuntResumesThePatrol() {
        noticeThenLoseSteve();
        s.config = new AggroController.Config(false, true, 10, 200, 1.5, 40, 1200, 3, s.config.perception());
        s.run(2);
        assertEquals(List.of("resume-patrol Warden7"), s.up.callsOf("resume-patrol"));
    }

    // ---------------------------------------------------------------- seen again

    @Test
    void seeingThePlayerAgainDuringTheSearchIsANewChaseWithTheHomeUnchanged() {
        noticeThenLoseSteve();
        untilPhase(Phase.SEARCH, 200);
        s.run(20);
        bot.blind.clear();
        bot.look = new double[]{1, 0}; // steve (still at x=8) is in front again
        steve.x = bot.x + 6;
        int t = untilPhase(Phase.CHASE, 40);
        assertTrue(t <= 40, "noticed again");
        assertTrue(t >= 5, "the reaction time applies again: " + t);
        assertEquals(2, s.up.callsOf("set").size());
        Pos home = s.controller.homeOf("Warden7");
        assertNotNull(home);
        assertEquals(0.0, home.x(), 1e-9, "home is unchanged");
    }

    @Test
    void seeingThePlayerWhileReturningIsAChaseAndTheBotStillGoesToTheFirstStartPoint() {
        noticeThenLoseSteve();
        assertEquals(Phase.PURSUE, s.phase());
        untilPhase(Phase.RETURN, 800);
        s.run(10);
        assertEquals(Phase.RETURN, s.phase());
        double firstHomeX = 0.0;
        // steve steps into view again while the bot walks home (west): put him in front of it
        bot.blind.clear();
        steve.x = bot.x - 7;
        bot.look = new double[]{-1, 0};
        assertTrue(untilPhase(Phase.CHASE, 40) <= 40, "chase again");
        assertEquals(firstHomeX, s.controller.homeOf("Warden7").x(), 1e-9);
        // he hides again; the bot loses him, pursues, searches, and returns to the FIRST start point, not to the second
        s.run(3);
        bot.blind.add("Steve");
        assertTrue(untilPhase(Phase.PURSUE, 30) <= 30);
        assertEquals(Phase.IDLE, runUntilIdle(1500), "the cycle ends idle");
        assertTrue(Math.abs(bot.x - firstHomeX) <= 1.5 + 1e-9, "ended within 1.5 of the FIRST start point: " + bot.x);
        assertTrue(s.up.maxStep <= 0.2 + 1e-9, "no teleport");
    }

    private Phase runUntilIdle(int max) {
        s.runUntil(() -> s.phase() == Phase.IDLE, max);
        return s.phase();
    }

    @Test
    void aNewHuntAfterArrivingHomeSetsAFreshHome() {
        noticeThenLoseSteve();
        assertEquals(Phase.IDLE, runUntilIdle(1500));
        assertFalse(s.controller.hasHome("Warden7"));
        bot.x = 20; // the bot is elsewhere now (patrol)
        bot.blind.clear();
        steve.x = 28;
        bot.look = new double[]{1, 0};
        assertTrue(untilPhase(Phase.CHASE, 60) <= 60);
        assertEquals(20.0, s.controller.homeOf("Warden7").x(), 1e-9, "a fresh home where this hunt began");
    }

    // ---------------------------------------------------------------- pursuit problems

    @Test
    void noRouteToTheLastPositionMeansSearchingFromWhereItStands() {
        s.planner.outcome = PathPlanner.Outcome.NONE;
        steve.x = 8;
        assertTrue(untilPhase(Phase.CHASE, 40) <= 40);
        s.run(3);
        bot.blind.add("Steve");
        untilPhase(Phase.PURSUE, 30);
        int t = untilPhase(Phase.SEARCH, 20);
        assertTrue(t <= 3, "no way there: search from here at once: " + t);
        assertTrue(Math.abs(bot.x) < 1.0, "did not move");
    }

    @Test
    void aRouteThatOnlyLeadsPartWayEndsInASearchWhereItStops() {
        s.planner.outcome = PathPlanner.Outcome.PARTIAL;
        s.planner.route = (b, goal) -> List.of(new Pos(4, 64, 0));
        steve.x = 8;
        assertTrue(untilPhase(Phase.CHASE, 40) <= 40);
        s.run(3);
        bot.blind.add("Steve");
        untilPhase(Phase.PURSUE, 30);
        assertTrue(untilPhase(Phase.SEARCH, 200) <= 200);
        assertTrue(Math.abs(bot.x - 4) < 1.0, "searched where the route ended: " + bot.x);
    }

    @Test
    void aBotThatGetsNowhereIsReplannedAndThenSearchesWhereItStands() {
        s.up.walkSpeed = 0; // stuck: steering does not move it
        steve.x = 8;
        assertTrue(untilPhase(Phase.CHASE, 40) <= 40);
        s.run(3);
        bot.blind.add("Steve");
        untilPhase(Phase.PURSUE, 30);
        int t = untilPhase(Phase.SEARCH, 600);
        assertTrue(t <= 600, "gave up walking and searched");
        assertTrue(s.planner.plans >= 2, "replanned when stuck: " + s.planner.plans);
    }

    @Test
    void withoutAPlannerTheBotStillWalksStraightAtTheGoal() {
        s.planner.outcome = PathPlanner.Outcome.UNAVAILABLE;
        noticeThenLoseSteve();
        assertTrue(untilPhase(Phase.SEARCH, 300) <= 300);
        assertTrue(Math.abs(bot.x - 8) <= 1.6, "walked straight to the last position: " + bot.x);
    }

    @Test
    void routePlanningIsBoundedPerTickWhateverTheNumberOfHunters() {
        for (int i = 0; i < 6; i++) {
            Person g = s.world.addInhabitant("Guard" + i, 10 + i);
            g.look = new double[]{1, 0};
        }
        steve.x = 60;
        s.run(1);
        s.up.other.put("Warden7", steve);
        for (int i = 0; i < 6; i++) {
            s.up.other.put("Guard" + i, steve);
        }
        s.run(2);
        s.up.other.clear();
        s.up.current.clear();
        for (Person p : s.world.online.values()) {
            p.blind.add("Steve");
        }
        s.planner.plans = 0;
        // each tick at most MAX_PLANS_PER_TICK plans in total
        for (int i = 0; i < 30; i++) {
            int before = s.planner.plans;
            s.run(1);
            assertTrue(s.planner.plans - before <= AggroController.MAX_PLANS_PER_TICK, "plans this tick: " + (s.planner.plans - before));
        }
    }

    // ---------------------------------------------------------------- hearing clues during the search

    @Test
    void aSoundFromBehindCoverMovesTheSearchThere() {
        List<Pos> foci = new ArrayList<>();
        s.world.spots = (b, focus) -> {
            foci.add(focus);
            return List.of();
        };
        noticeThenLoseSteve();
        untilPhase(Phase.SEARCH, 200);
        s.run(10);
        foci.clear();
        // steve walks (noisy) 3 blocks from the bot, behind a wall: heard, occluded -> a hint
        steve.x = bot.x;
        steve.z = bot.z + 3;
        steve.subject = Perception.Subject.player(false, true, false);
        bot.look = new double[]{1, 0};
        s.run(60);
        assertTrue(foci.stream().anyMatch(f -> Math.abs(f.z() - (bot.z + 3)) < 0.5 || Math.abs(f.z() - 3) < 0.5),
                "the search focus moved to the sound: " + foci);
        assertEquals(Phase.SEARCH, s.phase(), "and it is still the same search window (a sound is no notice)");
    }

    // ---------------------------------------------------------------- limits of the walk home

    @Test
    void theWalkHomeGivesUpAfterTheMaximumTimeAndStaysWhereItIs() {
        s.config = new AggroController.Config(true, true, 10, 200, 1.5, 40, 100, 3, s.config.perception());
        s.up.walkSpeed = 0.02; // far too slow to get home in 100 ticks
        noticeThenLoseSteve();
        s.up.walkSpeed = 0.2;
        untilPhase(Phase.RETURN, 500);
        s.up.walkSpeed = 0.02;
        int t = s.runUntil(() -> s.phase() == Phase.IDLE, 300);
        assertTrue(t <= 101, "gave up after returnMaxTicks: " + t);
        assertFalse(s.controller.hasHome("Warden7"));
        assertTrue(bot.x > 1.5, "stayed where it was");
        assertTrue(s.log.info.stream().anyMatch(l -> l.contains("gave up walking home")), s.log.info.toString());
    }

    @Test
    void aBotThatChangedLevelHasNothingToWalkBackTo() {
        noticeThenLoseSteve();
        untilPhase(Phase.SEARCH, 200);
        bot.dimension = "the_nether";
        s.run(220);
        assertEquals(Phase.IDLE, s.phase());
        assertFalse(s.controller.hasHome("Warden7"));
    }

    @Test
    void withoutSteeringTheBotStaysWhereItLostThePlayerWithOneWarning() {
        s.up.steering = false;
        steve.x = 8;
        assertTrue(untilPhase(Phase.CHASE, 40) <= 40);
        s.run(3);
        bot.blind.add("Steve");
        s.run(20);
        assertEquals(Phase.IDLE, s.phase());
        assertNull(s.controller.homeOf("Warden7"));
        assertEquals(1, s.log.warn.stream().filter(l -> l.contains("cannot walk")).count());
    }

    // ---------------------------------------------------------------- configuration

    @Test
    void theGraceAndTheSearchTimeComeFromTheConfiguration() {
        s.config = new AggroController.Config(true, true, 30, 100, 1.5, 40, 1200, 3, s.config.perception());
        steve.x = 8;
        assertTrue(untilPhase(Phase.CHASE, 40) <= 40);
        s.run(3);
        bot.blind.add("Steve");
        s.run(29);
        assertEquals(Phase.CHASE, s.phase(), "30 ticks of grace");
        s.run(2);
        assertEquals(Phase.PURSUE, s.phase());
        untilPhase(Phase.SEARCH, 200);
        assertEquals(100, s.runUntil(() -> s.phase() != Phase.SEARCH, 300), 1);
    }

    @Test
    void theReactionTimeComesFromTheConfiguration() {
        Perception.Params p = s.config.perception();
        Perception.Params slow = new Perception.Params(p.enabled(), p.frontHalfAngleDeg(), p.peripheralHalfAngleDeg(),
                p.peripheralMultiplier(), p.sneakMultiplier(), 10.0, 0.0, p.hearWalk(), p.hearSprint(), p.hearCombat(),
                p.hearNoisyMob(), p.hearPrimedCreeper(), p.hearWarden(), p.hearAnimal(), p.combatNoiseTicks());
        s.config = s.config.withPerception(slow);
        steve.x = 8;
        int t = s.runUntil(() -> s.phase() == Phase.CHASE, 60);
        assertTrue(t >= 11 && t <= 13, "10 ticks of exposure: " + t);
    }
}
