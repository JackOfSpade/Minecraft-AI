package dev.spawnbotswrapper.inhabitants.adapter;

import dev.spawnbotswrapper.inhabitants.profile.BotProfile.Behavior;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile.Stance;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile.Waypoint;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;
import org.stepan1411.pvp_bot.bot.BotPath;
import org.stepan1411.testdouble.Paths;
import org.stepan1411.testdouble.Recorder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Patrols through PvP BOT's path statics: build order, the loop trap, ownership, cleanup, rollback. */
class PvpBotAdapterPatrolTest {

    private static final Set<String> PATH_METHODS = Set.of("createPath", "deletePath", "addPoint", "setLoop",
            "setAttack", "setWalkType", "startFollowing", "stopFollowing", "removeState");

    private static List<Waypoint> points(int n) {
        List<Waypoint> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new Waypoint(100.5 + i * 2, 64, -20.5 + i));
        }
        return out;
    }

    private static Behavior behavior(String stance, boolean combatant, String walkType, int n) {
        return new Behavior(stance, combatant, walkType, 6.0, n, points(n));
    }

    /** The upstream path calls made so far, in order (bot-list reads and settings reads are not path calls). */
    private static List<String> pathCalls() {
        List<String> out = new ArrayList<>();
        for (String c : Recorder.CALLS) {
            String method = c.contains(":") ? c.substring(0, c.indexOf(':')) : c;
            if (PATH_METHODS.contains(method)) {
                out.add(c);
            }
        }
        return out;
    }

    private static boolean assign(AdapterFixture f, String bot, Behavior b) {
        return f.adapter.assignPatrol(null, bot, b);
    }

    // ---------------------------------------------------------------- building

    @Test
    void aCyclePatrolIsBuiltCompletelyBeforeTheBotStartsFollowing() {
        AdapterFixture f = AdapterFixture.probed();
        assertTrue(assign(f, "Inh_Foo", behavior(Stance.PATROL_CYCLE, true, "sprint", 3)));
        assertEquals(List.of(
                "createPath:inh_inh_foo",
                "addPoint:inh_inh_foo", "addPoint:inh_inh_foo", "addPoint:inh_inh_foo",
                "setLoop:inh_inh_foo=false",
                "setAttack:inh_inh_foo=true",
                "setWalkType:inh_inh_foo=sprint",
                "startFollowing:Inh_Foo->inh_inh_foo"), pathCalls());

        BotPath.PathData p = BotPath.getPath("inh_inh_foo");
        assertNotNull(p);
        assertEquals(3, p.points.size());
        assertFalse(p.loop, "loop=false is upstream's ring");
        assertTrue(p.attack);
        assertEquals("sprint", p.walkType);
        assertEquals("inh_inh_foo", BotPath.followerOf("Inh_Foo"));
    }

    @Test
    void theWaypointsReachUpstreamAsTheExactPositionsInOrder() {
        AdapterFixture f = AdapterFixture.probed();
        assertTrue(assign(f, "Inh_Foo", behavior(Stance.PATROL_CYCLE, true, "bhop", 3)));
        List<Vec3d> got = BotPath.getPath("inh_inh_foo").points;
        assertEquals(3, got.size());
        for (int i = 0; i < 3; i++) {
            Waypoint w = points(3).get(i);
            assertEquals(new Vec3d(w.x(), w.y(), w.z()), got.get(i));
        }
    }

    @Test
    void aPingPongPatrolWithTwoPointsLoops() {
        AdapterFixture f = AdapterFixture.probed();
        assertTrue(assign(f, "Inh_Foo", behavior(Stance.PATROL_PINGPONG, true, "walk", 2)));
        BotPath.PathData p = BotPath.getPath("inh_inh_foo");
        assertTrue(p.loop, "loop=true is ping-pong");
        assertEquals(2, p.points.size());
        assertEquals("walk", p.walkType);
    }

    @Test
    void aGuardPostIsOnePointWithoutALoop() {
        AdapterFixture f = AdapterFixture.probed();
        assertTrue(assign(f, "Inh_Foo", behavior(Stance.GUARD_POST, true, "bhop", 1)));
        BotPath.PathData p = BotPath.getPath("inh_inh_foo");
        assertEquals(1, p.points.size());
        assertFalse(p.loop);
    }

    @Test
    void aSinglePointPingPongNeverReachesUpstreamWithTheLoopFlagSet() {
        // Trap P1: upstream reads waypoint -1 in its tick when a one-point path loops.
        AdapterFixture f = AdapterFixture.probed();
        assertTrue(assign(f, "Inh_Foo", behavior(Stance.PATROL_PINGPONG, true, "bhop", 1)));
        BotPath.PathData p = BotPath.getPath("inh_inh_foo");
        assertEquals(1, p.points.size());
        assertFalse(p.loop);
        assertEquals("setLoop:inh_inh_foo=false", pathCalls().get(2));
        assertFalse(pathCalls().contains("setLoop:inh_inh_foo=true"));
    }

    @Test
    void noStanceAndPointCountEverProducesALoopingSinglePointPathUpstream() {
        for (String stance : List.of(Stance.GUARD_POST, Stance.PATROL_CYCLE, Stance.PATROL_PINGPONG)) {
            for (int n = 1; n <= 5; n++) {
                AdapterFixture f = AdapterFixture.probed();
                assertTrue(assign(f, "Inh_Foo", behavior(stance, true, "bhop", n)), stance + " " + n);
                BotPath.PathData p = BotPath.getPath("inh_inh_foo");
                assertTrue(!p.loop || p.points.size() >= 2, stance + " with " + n + " points reached upstream as "
                        + "loop=" + p.loop + " points=" + p.points.size());
                assertTrue(!p.points.isEmpty());
            }
        }
    }

    @Test
    void aPacifistPathHasTheAttackFlagOff() {
        AdapterFixture f = AdapterFixture.probed();
        assertTrue(assign(f, "Inh_Foo", behavior(Stance.PATROL_CYCLE, false, "bhop", 3)));
        assertFalse(BotPath.getPath("inh_inh_foo").attack);
    }

    @Test
    void anUnknownWalkTypeIsNeverPassedToUpstream() {
        AdapterFixture f = AdapterFixture.probed();
        assertTrue(assign(f, "Inh_Foo", behavior(Stance.PATROL_CYCLE, true, "moonwalk", 3)));
        assertEquals("bhop", BotPath.getPath("inh_inh_foo").walkType);
    }

    @Test
    void theBotIsHandedToUpstreamWithTheSpellingPvpBotListsIt() {
        // Upstream's follower map is case-sensitive: a follower under another spelling would silently do nothing.
        AdapterFixture f = AdapterFixture.probed();
        Recorder.LISTED.add("Inh_Foo");
        assertTrue(assign(f, "inh_foo", behavior(Stance.GUARD_POST, true, "bhop", 1)));
        assertEquals("inh_inh_foo", BotPath.followerOf("Inh_Foo"));
        assertNull(BotPath.followerOf("inh_foo"));
    }

    // ---------------------------------------------------------------- refusals reach upstream not at all

    @Test
    void standingStillBuildsNothing() {
        AdapterFixture f = AdapterFixture.probed();
        assertFalse(assign(f, "Inh_Foo", Behavior.standing()));
        assertTrue(pathCalls().isEmpty());
    }

    @Test
    void invalidWaypointsBuildNothing() {
        AdapterFixture f = AdapterFixture.probed();
        List<Waypoint> w = new ArrayList<>(points(3));
        w.set(1, new Waypoint(1, Double.NaN, 2));
        assertFalse(assign(f, "Inh_Foo", new Behavior(Stance.PATROL_CYCLE, true, "bhop", 5, 3, w)));
        assertTrue(pathCalls().isEmpty(), "a NaN must never reach upstream: " + pathCalls());
    }

    @Test
    void anInvalidBotNameBuildsNothing() {
        AdapterFixture f = AdapterFixture.probed();
        assertFalse(assign(f, "bad name", behavior(Stance.GUARD_POST, true, "bhop", 1)));
        assertFalse(assign(f, null, behavior(Stance.GUARD_POST, true, "bhop", 1)));
        assertFalse(assign(f, "ab", behavior(Stance.GUARD_POST, true, "bhop", 1)));
        assertTrue(pathCalls().isEmpty());
    }

    @Test
    void withoutAProbeNothingUpstreamIsTouched() {
        AdapterFixture f = AdapterFixture.healthy();
        assertFalse(assign(f, "Inh_Foo", behavior(Stance.GUARD_POST, true, "bhop", 1)));
        assertTrue(Recorder.CALLS.isEmpty(), "BotPath must only be touched after a successful probe: " + Recorder.CALLS);
        assertFalse(f.adapter.isPatrolling("Inh_Foo"));
        f.adapter.clearPatrol("Inh_Foo");
        assertTrue(Recorder.CALLS.isEmpty());
    }

    @Test
    void anIncompletePathApiDisablesPatrolsWithoutTouchingIt() {
        AdapterFixture f = AdapterFixture.with(TestLocators.replacing(UpstreamNames.CLASS_BOT_PATH, Paths.NoWalkType.class));
        f.adapter.probeWith(AdapterFixture.FULL_TREE);
        assertFalse(assign(f, "Inh_Foo", behavior(Stance.GUARD_POST, true, "bhop", 1)));
        assertTrue(pathCalls().isEmpty());
        assertNull(BotPath.getPath("inh_inh_foo"));
    }

    @Test
    void anUnavailableIntegrationBuildsNothing() {
        AdapterFixture f = AdapterFixture.with(TestLocators.missing(UpstreamNames.CLASS_BOT_MANAGER));
        f.adapter.probeWith(AdapterFixture.FULL_TREE);
        assertFalse(assign(f, "Inh_Foo", behavior(Stance.GUARD_POST, true, "bhop", 1)));
        assertTrue(pathCalls().isEmpty());
    }

    // ---------------------------------------------------------------- stale paths

    @Test
    void aStalePathOfTheSameNameIsDeletedFirst() {
        AdapterFixture f = AdapterFixture.probed();
        BotPath.seed("inh_inh_foo", true, new Vec3d(0, 0, 0), new Vec3d(1, 0, 1), new Vec3d(2, 0, 2), new Vec3d(3, 0, 3));
        BotPath.seedFollower("Inh_Foo", "inh_inh_foo");
        assertTrue(assign(f, "Inh_Foo", behavior(Stance.GUARD_POST, true, "bhop", 1)));
        assertEquals("deletePath:inh_inh_foo", pathCalls().get(0));
        assertEquals("createPath:inh_inh_foo", pathCalls().get(1));
        BotPath.PathData p = BotPath.getPath("inh_inh_foo");
        assertEquals(1, p.points.size(), "the old four-point ping-pong path is gone");
        assertFalse(p.loop);
    }

    @Test
    void reAssigningReplacesThePreviousPathCleanly() {
        AdapterFixture f = AdapterFixture.probed();
        assertTrue(assign(f, "Inh_Foo", behavior(Stance.PATROL_CYCLE, true, "bhop", 4)));
        assertTrue(assign(f, "Inh_Foo", behavior(Stance.PATROL_PINGPONG, true, "walk", 2)));
        BotPath.PathData p = BotPath.getPath("inh_inh_foo");
        assertEquals(2, p.points.size());
        assertTrue(p.loop);
        assertEquals("inh_inh_foo", BotPath.followerOf("Inh_Foo"));
    }

    // ---------------------------------------------------------------- rollback

    @Test
    void aRefusedWaypointRollsTheWholePathBackAndNeverStartsFollowing() {
        AdapterFixture f = AdapterFixture.probed();
        Recorder.FAIL.add("addPoint");
        assertFalse(assign(f, "Inh_Foo", behavior(Stance.PATROL_CYCLE, true, "bhop", 3)));
        assertNull(BotPath.getPath("inh_inh_foo"), "the half-built path must be deleted again");
        assertNull(BotPath.followerOf("Inh_Foo"));
        assertFalse(pathCalls().stream().anyMatch(c -> c.startsWith("startFollowing")));
        assertFalse(f.adapter.isPatrolling("Inh_Foo"));
    }

    @Test
    void aRefusedFlagRollsBack() {
        for (String refused : List.of("setLoop", "setAttack", "setWalkType", "startFollowing")) {
            AdapterFixture f = AdapterFixture.probed();
            Recorder.FAIL.add(refused);
            assertFalse(assign(f, "Inh_Foo", behavior(Stance.PATROL_CYCLE, true, "bhop", 3)), refused);
            assertNull(BotPath.getPath("inh_inh_foo"), refused + " must leave no path behind");
            assertNull(BotPath.followerOf("Inh_Foo"), refused);
            assertFalse(f.adapter.isPatrolling("Inh_Foo"), refused);
        }
    }

    @Test
    void aRefusedCreateLeavesNothingAndDeletesNothingItDidNotCreate() {
        AdapterFixture f = AdapterFixture.probed();
        Recorder.FAIL.add("createPath");
        assertFalse(assign(f, "Inh_Foo", behavior(Stance.PATROL_CYCLE, true, "bhop", 3)));
        assertEquals(List.of("createPath:inh_inh_foo"), pathCalls());
    }

    @Test
    void anExceptionMidBuildRollsBackAndNeverEscapes() {
        AdapterFixture f = AdapterFixture.probed();
        Recorder.THROW.add("setWalkType");
        assertFalse(assertDoesNotThrow(() -> assign(f, "Inh_Foo", behavior(Stance.PATROL_CYCLE, true, "bhop", 3))));
        assertNull(BotPath.getPath("inh_inh_foo"));
        assertNull(BotPath.followerOf("Inh_Foo"));
        assertTrue(f.sink.warn.stream().anyMatch(w -> w.contains("assigning a patrol")));
    }

    @Test
    void aStalePathThatCannotBeDeletedAbortsBeforeBuildingAnything() {
        AdapterFixture f = AdapterFixture.probed();
        BotPath.seed("inh_inh_foo", false, new Vec3d(0, 0, 0));
        Recorder.FAIL.add("deletePath");
        assertFalse(assign(f, "Inh_Foo", behavior(Stance.GUARD_POST, true, "bhop", 1)));
        assertFalse(pathCalls().stream().anyMatch(c -> c.startsWith("createPath")));
    }

    // ---------------------------------------------------------------- ownership and status

    @Test
    void isPatrollingIsTrueOnlyForABotFollowingAPathWeCreated() {
        AdapterFixture f = AdapterFixture.probed();
        assertFalse(f.adapter.isPatrolling("Inh_Foo"));
        assertTrue(assign(f, "Inh_Foo", behavior(Stance.GUARD_POST, true, "bhop", 1)));
        assertTrue(f.adapter.isPatrolling("Inh_Foo"));
        assertTrue(f.adapter.isPatrolling("INH_FOO"), "names are compared case-insensitively");
        assertFalse(f.adapter.isPatrolling("Inh_Bar"));
        assertFalse(f.adapter.isPatrolling(null));
    }

    @Test
    void aBotFollowingSomeoneElsesPathIsNotPatrolling() {
        AdapterFixture f = AdapterFixture.probed();
        BotPath.seed("mine", false, new Vec3d(0, 0, 0));
        BotPath.seedFollower("Inh_Foo", "mine");
        assertFalse(f.adapter.isPatrolling("Inh_Foo"), "not created by this addon");
    }

    @Test
    void isPatrollingFollowsUpstreamRealityWhenSomeoneStopsTheFollower() {
        AdapterFixture f = AdapterFixture.probed();
        assertTrue(assign(f, "Inh_Foo", behavior(Stance.GUARD_POST, true, "bhop", 1)));
        BotPath.stopFollowing("Inh_Foo");
        assertFalse(f.adapter.isPatrolling("Inh_Foo"));
    }

    @Test
    void withoutTheFollowerCheckThePathsExistenceIsTheBestSignal() {
        AdapterFixture f = AdapterFixture.with(TestLocators.replacing(UpstreamNames.CLASS_BOT_PATH, Paths.NoIsFollowing.class));
        f.adapter.probeWith(AdapterFixture.FULL_TREE);
        assertTrue(assign(f, "Inh_Foo", behavior(Stance.GUARD_POST, true, "bhop", 1)));
        assertTrue(f.adapter.isPatrolling("Inh_Foo"));
        BotPath.deletePath("inh_inh_foo");
        assertFalse(f.adapter.isPatrolling("Inh_Foo"));
    }

    // ---------------------------------------------------------------- clearing

    @Test
    void clearPatrolStopsDeletesAndClearsNavigationStateThenIsIdempotent() {
        AdapterFixture f = AdapterFixture.probed();
        assertTrue(assign(f, "Inh_Foo", behavior(Stance.PATROL_CYCLE, true, "bhop", 3)));
        Recorder.CALLS.clear();

        f.adapter.clearPatrol("Inh_Foo");
        assertEquals(List.of("stopFollowing:Inh_Foo", "deletePath:inh_inh_foo", "removeState:Inh_Foo"), pathCalls());
        assertNull(BotPath.getPath("inh_inh_foo"));
        assertNull(BotPath.followerOf("Inh_Foo"));
        assertFalse(f.adapter.isPatrolling("Inh_Foo"));

        Recorder.CALLS.clear();
        f.adapter.clearPatrol("Inh_Foo");
        f.adapter.clearPatrol("Inh_Foo");
        assertTrue(pathCalls().isEmpty(), "a second clear must not touch upstream at all: " + pathCalls());
    }

    @Test
    void eachClearStepIsGuardedOnItsOwn() {
        for (String failing : List.of("stopFollowing", "deletePath", "removeState")) {
            AdapterFixture f = AdapterFixture.probed();
            assertTrue(assign(f, "Inh_Foo", behavior(Stance.PATROL_CYCLE, true, "bhop", 3)));
            Recorder.CALLS.clear();
            Recorder.THROW.add(failing);

            assertDoesNotThrow(() -> f.adapter.clearPatrol("Inh_Foo"));
            for (String step : List.of("stopFollowing", "deletePath", "removeState")) {
                assertTrue(pathCalls().stream().anyMatch(c -> c.startsWith(step)),
                        step + " must still be attempted when " + failing + " throws: " + pathCalls());
            }
            assertTrue(f.sink.warn.stream().anyMatch(w -> w.contains("PvP BOT integration")), failing + " is reported");
            assertFalse(f.adapter.isPatrolling("Inh_Foo"), "tracking is dropped even when upstream misbehaves");
        }
    }

    @Test
    void clearPatrolNeverTouchesABotThatHasNoPathOfOurs() {
        AdapterFixture f = AdapterFixture.probed();
        BotPath.seed("mine", false, new Vec3d(0, 0, 0));
        BotPath.seedFollower("Someone", "mine");
        f.adapter.clearPatrol("Someone");
        assertTrue(pathCalls().isEmpty(), "someone else's path and follower are none of our business");
        assertNotNull(BotPath.getPath("mine"));
        assertEquals("mine", BotPath.followerOf("Someone"));
    }

    @Test
    void clearPatrolDoesNotStopAFollowerThatMovedOnToAnotherPath() {
        AdapterFixture f = AdapterFixture.probed();
        assertTrue(assign(f, "Inh_Foo", behavior(Stance.GUARD_POST, true, "bhop", 1)));
        BotPath.seed("theirs", false, new Vec3d(5, 5, 5));
        BotPath.seedFollower("Inh_Foo", "theirs");
        Recorder.CALLS.clear();

        f.adapter.clearPatrol("Inh_Foo");
        assertFalse(pathCalls().stream().anyMatch(c -> c.startsWith("stopFollowing")));
        assertNull(BotPath.getPath("inh_inh_foo"), "our path is deleted");
        assertEquals("theirs", BotPath.followerOf("Inh_Foo"), "their follow is left alone");
    }

    @Test
    void aLeftoverOfAnEarlierSessionIsRecognisedByTheReservedPrefix() {
        // Upstream persists paths but not followers, so after a restart an inh_ path can outlive its follower.
        AdapterFixture f = AdapterFixture.probed();
        BotPath.seed("inh_inh_bar", false, new Vec3d(0, 0, 0));
        f.adapter.clearPatrol("Inh_Bar");
        assertNull(BotPath.getPath("inh_inh_bar"));
        assertTrue(pathCalls().contains("deletePath:inh_inh_bar"));
        assertTrue(pathCalls().contains("removeState:Inh_Bar"));
        assertFalse(pathCalls().stream().anyMatch(c -> c.startsWith("stopFollowing")),
                "nobody follows it, so nobody is stopped");
    }

    @Test
    void aLeftoverThatIsStillFollowedOnItsOwnPathIsStopped() {
        AdapterFixture f = AdapterFixture.probed();
        BotPath.seed("inh_inh_bar", false, new Vec3d(0, 0, 0));
        BotPath.seedFollower("Inh_Bar", "inh_inh_bar");
        f.adapter.clearPatrol("Inh_Bar");
        assertTrue(pathCalls().contains("stopFollowing:Inh_Bar"));
        assertNull(BotPath.followerOf("Inh_Bar"));
    }

    @Test
    void aLeftoverFollowedOnSomeoneElsesPathIsNotStopped() {
        AdapterFixture f = AdapterFixture.probed();
        BotPath.seed("inh_inh_bar", false, new Vec3d(0, 0, 0));
        BotPath.seed("theirs", false, new Vec3d(0, 0, 0));
        BotPath.seedFollower("Inh_Bar", "theirs");
        f.adapter.clearPatrol("Inh_Bar");
        assertEquals("theirs", BotPath.followerOf("Inh_Bar"));
        assertNull(BotPath.getPath("inh_inh_bar"));
    }

    @Test
    void clearPatrolForAnInvalidOrNullNameIsANoOp() {
        AdapterFixture f = AdapterFixture.probed();
        assertDoesNotThrow(() -> f.adapter.clearPatrol(null));
        assertDoesNotThrow(() -> f.adapter.clearPatrol(""));
        assertDoesNotThrow(() -> f.adapter.clearPatrol("   "));
        assertTrue(pathCalls().isEmpty());
    }

    @Test
    void releaseAllPatrolsClearsEveryTrackedPathAndIsIdempotent() {
        AdapterFixture f = AdapterFixture.probed();
        assertTrue(assign(f, "Inh_A1", behavior(Stance.GUARD_POST, true, "bhop", 1)));
        assertTrue(assign(f, "Inh_B2", behavior(Stance.PATROL_CYCLE, true, "bhop", 3)));
        assertTrue(assign(f, "Inh_C3", behavior(Stance.PATROL_PINGPONG, false, "walk", 2)));
        BotPath.seed("foreign", false, new Vec3d(9, 9, 9));

        assertEquals(3, f.adapter.releaseAllPatrols());
        for (String bot : List.of("Inh_A1", "Inh_B2", "Inh_C3")) {
            assertNull(BotPath.getPath(UpstreamPathPlanner.pathName(bot)), bot);
            assertNull(BotPath.followerOf(bot), bot);
        }
        assertNotNull(BotPath.getPath("foreign"), "never touches paths it did not create");
        assertEquals(0, f.adapter.releaseAllPatrols());
    }

    @Test
    void releaseAllPatrolsWithoutAnythingTrackedTouchesNothing() {
        AdapterFixture f = AdapterFixture.probed();
        assertEquals(0, f.adapter.releaseAllPatrols());
        assertTrue(pathCalls().isEmpty());
    }

    @Test
    void manyBotsKeepIndependentPaths() {
        AdapterFixture f = AdapterFixture.probed();
        for (int i = 0; i < 20; i++) {
            assertTrue(assign(f, "Inh_B" + i, behavior(Stance.GUARD_POST, true, "bhop", 1)));
        }
        for (int i = 0; i < 20; i++) {
            assertTrue(f.adapter.isPatrolling("Inh_B" + i));
        }
        f.adapter.clearPatrol("Inh_B7");
        assertFalse(f.adapter.isPatrolling("Inh_B7"));
        assertTrue(f.adapter.isPatrolling("Inh_B8"));
        assertTrue(f.adapter.isPatrolling("Inh_B6"));
    }
}
