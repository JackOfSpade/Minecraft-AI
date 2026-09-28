package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.mining.assist.DetourPhase;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I14 heartbeat bound: no two beats ({@code DetourHost#noteProgress}) are ever more than
 * {@link OreDigDetourEngine#MAX_HEARTBEAT_GAP_TICKS} task ticks apart, across every scripted phase sequence of the
 * P1 contract's test plan (section G.4/C.4/C.6), including the two corrected creeping-approach cases and the
 * RETURN-while-unstandable keepalive. {@link OreDigDetourEngineTest} covers functional correctness separately.
 */
class EngineDeadlinesTest {

    private static DetourStartSelector.Selection selectionFor(FakeDetourHost host, BlockPos seed, String blockId) {
        DetourHost.Anchor anchor = host.captureAnchor();
        DetourHost.Pose pose = host.poseFor(seed, anchor, Set.of());
        return new DetourStartSelector.Selection(seed, blockId, 100, 5.0D, pose, List.of(seed), anchor);
    }

    /** Drives the engine to FINISH (or trips a generous guard, which is always a test bug, never a real cap). */
    private static OreDigDetourEngine.Result driveToFinish(FakeDetourHost host, OreDigDetourEngine engine, int guardTicks) {
        for (int i = 0; i < guardTicks; i++) {
            host.tickClock();
            OreDigDetourEngine.Result r = engine.tick(host);
            if (r.kind() == OreDigDetourEngine.Kind.FINISHED) {
                return r;
            }
        }
        throw new AssertionError("detour did not finish within the tick guard (" + guardTicks + ")");
    }

    private static void assertBounded(FakeDetourHost host, int startTick, int endTick) {
        int gap = host.maxBeatGap(startTick, endTick);
        assertTrue(gap <= OreDigDetourEngine.MAX_HEARTBEAT_GAP_TICKS,
                "max beat gap " + gap + " exceeds " + OreDigDetourEngine.MAX_HEARTBEAT_GAP_TICKS);
    }

    /**
     * Drives a zero-transit detour (mined right at the anchor) until it enters RETURN, then moves the bot away
     * from the anchor face so the return leg actually has to walk back instead of finding itself arrived on the
     * very first RETURN tick. Used to script the return leg's route in isolation from the approach leg's.
     */
    private static void driveToReturnThenDisplace(FakeDetourHost host, OreDigDetourEngine engine, BlockPos displaced) {
        int guard = 0;
        while (engine.phase() != DetourPhase.RETURN && guard < 500) {
            host.tickClock();
            engine.tick(host);
            guard++;
        }
        assertEquals(DetourPhase.RETURN, engine.phase(), "detour did not reach RETURN within the guard");
        host.feet = displaced;
    }

    // ---- the two corrected creeping-approach cases (M14, review log) --------------------------------------------

    @Test
    void creepingApproachBelowOneBlockPerHundredTicksStallsAtGapOver100() {
        FakeDetourHost host = new FakeDetourHost();
        host.ticksPerBlock = 110;
        BlockPos seed = new BlockPos(60, 40, 0);
        int startTick = host.now;
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, selectionFor(host, seed, "diamond_ore"));
        OreDigDetourEngine.Result r = driveToFinish(host, engine, 5000);
        assertEquals("approach_stall", r.reason());
        assertBounded(host, startTick, host.now);
    }

    @Test
    void creepingApproachNinePointNineBlocksPerTwentyTicksSurvivesToTotalCap() {
        FakeDetourHost host = new FakeDetourHost();
        host.ticksPerBlock = 22; // 0.9 blocks / 20 ticks: the cumulative 1.0-block rule keeps beating
        BlockPos seed = new BlockPos(60, 40, 0);
        int startTick = host.now;
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, selectionFor(host, seed, "diamond_ore"));
        driveToFinish(host, engine, 5000);
        assertBounded(host, startTick, host.now);
    }

    @Test
    void fullyStalledApproachAbortsAtStallClock() {
        FakeDetourHost host = new FakeDetourHost();
        host.ticksPerBlock = 100000; // effectively never arrives, never routes again in time
        BlockPos seed = new BlockPos(60, 40, 0);
        int startTick = host.now;
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, selectionFor(host, seed, "diamond_ore"));
        driveToFinish(host, engine, 5000);
        assertBounded(host, startTick, host.now);
    }

    // ---- mining ------------------------------------------------------------------------------------------------

    @Test
    void slowMiningHitsThe160SwingCap() {
        FakeDetourHost host = new FakeDetourHost();
        host.mineTicks = 100000; // never DONE within MINE_SWING_TICKS
        BlockPos seed = new BlockPos(1, 40, 0);
        int startTick = host.now;
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, selectionFor(host, seed, "diamond_ore"));
        driveToFinish(host, engine, 5000);
        assertBounded(host, startTick, host.now);
    }

    // ---- drop lost then a long return ---------------------------------------------------------------------------

    @Test
    void dropLostTimeoutFollowedByTwentyFiveBlockReturn() {
        FakeDetourHost host = new FakeDetourHost();
        host.pickupEnabled = false; // drop stays visible: the 60-tick drop_lost timeout fires
        host.ticksPerBlock = 5;
        BlockPos seed = new BlockPos(25, 40, 0); // 25 blocks back to the anchor after the break
        int startTick = host.now;
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, selectionFor(host, seed, "diamond_ore"));
        OreDigDetourEngine.Result r = driveToFinish(host, engine, 5000);
        assertEquals("done", r.reason());
        assertBounded(host, startTick, host.now);
    }

    // ---- return ------------------------------------------------------------------------------------------------

    @Test
    void returnRouteFailsThreeTimesRebase() {
        FakeDetourHost host = new FakeDetourHost();
        BlockPos seed = new BlockPos(1, 40, 0);
        int startTick = host.now;
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, selectionFor(host, seed, "diamond_ore"));
        driveToReturnThenDisplace(host, engine, new BlockPos(6, 40, 0));
        host.routeScript.add(DetourHost.RouteResult.FAILED);
        host.routeScript.add(DetourHost.RouteResult.FAILED);
        host.routeScript.add(DetourHost.RouteResult.FAILED);
        OreDigDetourEngine.Result r = driveToFinish(host, engine, 5000);
        assertEquals("return_rebased", r.reason());
        assertBounded(host, startTick, host.now);
    }

    @Test
    void standableReturnUnder150TicksOfBudgetFinishesNormally() {
        // M35: startReturnRoute never actually answers BUDGET in production; this proves the engine would not
        // rebase even if it somehow did, across a 150-tick stretch of them.
        FakeDetourHost host = new FakeDetourHost();
        BlockPos seed = new BlockPos(1, 40, 0);
        int startTick = host.now;
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, selectionFor(host, seed, "diamond_ore"));
        driveToReturnThenDisplace(host, engine, new BlockPos(6, 40, 0));
        for (int i = 0; i < 30; i++) {
            host.routeScript.add(DetourHost.RouteResult.BUDGET);
        }
        OreDigDetourEngine.Result r = driveToFinish(host, engine, 5000);
        assertEquals("done", r.reason());
        assertBounded(host, startTick, host.now);
    }

    @Test
    void unstandableReturnRetriesAndBeatsEvery40UntilThe400Cap() {
        FakeDetourHost host = new FakeDetourHost();
        BlockPos seed = new BlockPos(1, 40, 0);
        int startTick = host.now;
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, selectionFor(host, seed, "diamond_ore"));
        driveToReturnThenDisplace(host, engine, new BlockPos(6, 40, 0));
        host.feetStandable = false;
        host.routeEndsAfterBlocks = 0; // the route never actually arrives
        OreDigDetourEngine.Result r = driveToFinish(host, engine, 5000);
        assertEquals("return_rebased", r.reason());
        assertBounded(host, startTick, host.now);
    }

    @Test
    void returnUnderARouteThatRunsButNeverArrivesIsBoundByTheStallClock() {
        FakeDetourHost host = new FakeDetourHost();
        BlockPos seed = new BlockPos(1, 40, 0);
        int startTick = host.now;
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, selectionFor(host, seed, "diamond_ore"));
        driveToReturnThenDisplace(host, engine, new BlockPos(6, 40, 0));
        host.routeEndsAfterBlocks = 0; // models route_contract_lost: a path idle short of the goal
        OreDigDetourEngine.Result r = driveToFinish(host, engine, 5000);
        assertBounded(host, startTick, host.now);
    }

    @Test
    void engineMissingSeveralTicksWhileInReturnBeatsAndDoesNotAbort() {
        FakeDetourHost host = new FakeDetourHost();
        BlockPos seed = new BlockPos(1, 40, 0);
        int startTick = host.now;
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, selectionFor(host, seed, "diamond_ore"));
        driveToReturnThenDisplace(host, engine, new BlockPos(6, 40, 0));
        // Simulate several missed engine ticks (a starving pre-hook exit): now jumps but tick() is not called for them.
        host.now += 5;
        host.serverTick += 5;
        OreDigDetourEngine.Result r = engine.tick(host);
        assertTrue(r.kind() != OreDigDetourEngine.Kind.FINISHED);
        assertEquals(DetourPhase.RETURN, engine.phase());
        driveToFinish(host, engine, 5000);
        assertBounded(host, startTick, host.now);
    }

    // ---- tick_gap outside RETURN --------------------------------------------------------------------------------

    @Test
    void tickGapOutsideReturnIsBoundedToo() {
        FakeDetourHost host = new FakeDetourHost();
        BlockPos seed = new BlockPos(20, 40, 0);
        int startTick = host.now;
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, selectionFor(host, seed, "diamond_ore"));
        host.now += 10; // a starved tick outside RETURN
        OreDigDetourEngine.Result r = engine.tick(host);
        assertEquals(DetourPhase.RETURN, engine.phase());
        driveToFinish(host, engine, 5000);
        assertBounded(host, startTick, host.now);
    }

    // ---- a full multi-member detour -----------------------------------------------------------------------------

    @Test
    void fullMultiMemberDetourStaysBounded() {
        FakeDetourHost host = new FakeDetourHost();
        BlockPos seed = new BlockPos(3, 40, 0);
        host.vein.add(new BlockPos(4, 40, 0));
        host.vein.add(new BlockPos(5, 40, 0));
        host.vein.add(new BlockPos(6, 40, 0));
        int startTick = host.now;
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, selectionFor(host, seed, "diamond_ore"));
        driveToFinish(host, engine, 5000);
        assertEquals(4, engine.membersStarted());
        assertBounded(host, startTick, host.now);
    }

    // ---- deterministic grid sweep (>= 60 combinations) ------------------------------------------------------------

    @Test
    void deterministicSweepOfAtLeast60Combinations() {
        int[] approachSpeeds = {3, 7, 15};
        int[] mineTicksValues = {1, 5, 20};
        boolean[] pickupEnabledValues = {true, false};
        int[] returnSpeeds = {3, 7, 15};
        boolean[] preFailRoute = {false, true};

        int combos = 0;
        for (int approachSpeed : approachSpeeds) {
            for (int mineTicksValue : mineTicksValues) {
                for (boolean pickupEnabled : pickupEnabledValues) {
                    for (int returnSpeed : returnSpeeds) {
                        for (boolean fail : preFailRoute) {
                            combos++;
                            FakeDetourHost host = new FakeDetourHost();
                            host.ticksPerBlock = approachSpeed;
                            host.mineTicks = mineTicksValue;
                            host.pickupEnabled = pickupEnabled;
                            if (fail) {
                                host.routeScript.add(DetourHost.RouteResult.FAILED);
                            }
                            BlockPos seed = new BlockPos(30, 40, 0);
                            int startTick = host.now;
                            OreDigDetourEngine engine = new OreDigDetourEngine();
                            engine.start(host, selectionFor(host, seed, "diamond_ore"));
                            int endTick = -1;
                            for (int i = 0; i < 5000; i++) {
                                if (engine.phase() == DetourPhase.RETURN) {
                                    host.ticksPerBlock = returnSpeed;
                                }
                                host.tickClock();
                                OreDigDetourEngine.Result r = engine.tick(host);
                                if (r.kind() == OreDigDetourEngine.Kind.FINISHED) {
                                    endTick = host.now;
                                    break;
                                }
                            }
                            assertTrue(endTick >= 0, "combo " + combos + " did not finish");
                            assertBounded(host, startTick, endTick);
                        }
                    }
                }
            }
        }
        assertTrue(combos >= 60, "expected at least 60 combinations, ran " + combos);
    }
}
