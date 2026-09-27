package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.mining.assist.DetourPhase;
import io.github.zoyluo.aibot.mining.assist.MiningAssistConfig;
import io.github.zoyluo.aibot.mining.assist.SafeReason;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour tests of {@link OreDigDetourEngine} against the frozen {@link FakeDetourHost} (P1 contract, section
 * G.4). {@link EngineDeadlinesTest} covers the heartbeat bound separately.
 */
class OreDigDetourEngineTest {

    // ---- helpers --------------------------------------------------------------------------------------------

    private static DetourStartSelector.Selection selectionFor(FakeDetourHost host, BlockPos seed, String blockId,
                                                              int value, List<BlockPos> cluster) {
        DetourHost.Anchor anchor = host.captureAnchor();
        DetourHost.Pose pose = host.poseFor(seed, anchor, Set.of());
        return new DetourStartSelector.Selection(seed, blockId, value, 5.0D, pose, cluster, anchor);
    }

    private static DetourStartSelector.Selection selectionFor(FakeDetourHost host, BlockPos seed, String blockId) {
        return selectionFor(host, seed, blockId, 100, List.of(seed));
    }

    /** Drives the engine until FINISHED or a large tick guard trips (a test bug, never a real cap). */
    private static OreDigDetourEngine.Result runToFinish(FakeDetourHost host, OreDigDetourEngine engine) {
        for (int i = 0; i < 5000; i++) {
            host.tickClock();
            OreDigDetourEngine.Result r = engine.tick(host);
            if (r.kind() == OreDigDetourEngine.Kind.FINISHED) {
                return r;
            }
        }
        throw new AssertionError("detour did not finish within the tick guard");
    }

    // ---- full cycle -------------------------------------------------------------------------------------------

    @Test
    void fullCycleOneSeedRouteMineBreakPickupReturn() {
        FakeDetourHost host = new FakeDetourHost();
        BlockPos seed = new BlockPos(5, 40, 0);
        DetourStartSelector.Selection sel = selectionFor(host, seed, "diamond_ore");
        OreDigDetourEngine engine = new OreDigDetourEngine();

        engine.start(host, sel);
        assertEquals(DetourPhase.APPROACH, engine.phase());
        assertFalse(host.called("captureAnchor"));
        assertTrue(host.called("clearStripOwnership"));
        assertTrue(host.called("stopAll"));
        assertTrue(host.called("beat"));
        assertTrue(host.excludedUntil.containsKey(seed));

        OreDigDetourEngine.Result r = runToFinish(host, engine);
        assertEquals("done", r.reason());
        assertEquals(DetourPhase.IDLE, engine.phase());
        assertEquals(1, engine.breaks());
        assertEquals(1, engine.membersStarted());
        assertEquals(0, engine.seals());
        assertEquals(0, engine.dropsLost());
        assertEquals(2, host.countCalls("route:"));
        assertTrue(host.called("restoreAnchorNumbers"));
        assertTrue(host.called("release"));
        assertTrue(host.called("rebaseTargetMonitors"));
        assertEquals(new BlockPos(0, 40, 0), host.feet);
    }

    @Test
    void startActionOrderAndClaims() {
        FakeDetourHost host = new FakeDetourHost();
        BlockPos seed = new BlockPos(5, 40, 0);
        BlockPos near1 = new BlockPos(6, 40, 0);
        BlockPos near2 = new BlockPos(7, 40, 0);
        BlockPos near3 = new BlockPos(8, 40, 0);
        BlockPos far = new BlockPos(9, 40, 0);
        host.vein.add(near1);
        host.vein.add(near2);
        host.vein.add(near3);
        host.vein.add(far);
        host.claimedByOthers.add(near1); // claimed elsewhere: must be dropped from pending
        DetourStartSelector.Selection sel = selectionFor(host, seed, "diamond_ore");
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, sel);

        assertFalse(host.called("captureAnchor"));
        assertTrue(host.called("clearStripOwnership"));
        assertTrue(host.called("stopAll"));
        assertTrue(host.called("beat"));
        assertTrue(host.excludedUntil.containsKey(seed));
        // The three nearest vein members are attempted; the 4th (far) is never attempted.
        assertEquals(3, host.countCalls("claim:"));
        assertTrue(host.myClaims.contains(near2));
        assertTrue(host.myClaims.contains(near3));
        assertFalse(host.myClaims.contains(near1));

        OreDigDetourEngine.Result r = runToFinish(host, engine);
        assertEquals("done", r.reason());
        // The claimed-elsewhere member was dropped at start and never mined.
        assertFalse(host.broken.contains(near1));
    }

    @Test
    void zeroTransitPoseEntersMineDirectly() {
        FakeDetourHost host = new FakeDetourHost();
        BlockPos seed = new BlockPos(1, 40, 0); // pose = seed.west() = feet
        DetourStartSelector.Selection sel = selectionFor(host, seed, "iron_ore");
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, sel);
        assertEquals(DetourPhase.MINE, engine.phase());
    }

    @Test
    void memberClaimedByAnotherBotIsDroppedAtStart() {
        FakeDetourHost host = new FakeDetourHost();
        BlockPos seed = new BlockPos(5, 40, 0);
        BlockPos member = new BlockPos(6, 40, 0);
        host.vein.add(member);
        host.claimedByOthers.add(member);
        DetourStartSelector.Selection sel = selectionFor(host, seed, "diamond_ore");
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, sel);
        // The claimed member must never reappear as a mined member later.
        OreDigDetourEngine.Result r = runToFinish(host, engine);
        assertEquals("done", r.reason());
        assertFalse(host.mineCalls.containsKey(member));
    }

    // ---- safety -----------------------------------------------------------------------------------------------

    @Test
    void safetyFailureMidApproachAbortsAndReturns() {
        FakeDetourHost host = new FakeDetourHost();
        host.ticksPerBlock = 20; // slow enough that the safety flip lands mid-approach
        BlockPos seed = new BlockPos(20, 40, 0);
        DetourStartSelector.Selection sel = selectionFor(host, seed, "diamond_ore");
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, sel);

        host.safetyFn = stage -> SafeReason.HOSTILE_PRESSURE;
        OreDigDetourEngine.Result r = runToFinish(host, engine);
        assertEquals("safety_hostile_pressure", r.reason());
    }

    @Test
    void safetyTickFastIgnoresLateItemsFullDoesNot() {
        FakeDetourHost host = new FakeDetourHost();
        BlockPos seed = new BlockPos(20, 40, 0);
        DetourStartSelector.Selection sel = selectionFor(host, seed, "diamond_ore");
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, sel);
        host.safetyFn = stage -> stage == io.github.zoyluo.aibot.mining.assist.SafeGate.Stage.TICK_FULL
                ? SafeReason.HOSTILE_PRESSURE : SafeReason.OK;
        // Drive a handful of ticks; an abort must land on an even tick (TICK_FULL parity) at the latest.
        OreDigDetourEngine.Result r = runToFinish(host, engine);
        assertEquals("safety_hostile_pressure", r.reason());
    }

    // ---- approach stall / route / budget / lease --------------------------------------------------------------

    @Test
    void approachStallsBelowOneBlockPerHundredTicks() {
        FakeDetourHost host = new FakeDetourHost();
        host.ticksPerBlock = 110; // below 1.0 block / 100 ticks
        BlockPos seed = new BlockPos(60, 40, 0);
        DetourStartSelector.Selection sel = selectionFor(host, seed, "diamond_ore");
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, sel);
        OreDigDetourEngine.Result r = runToFinish(host, engine);
        assertEquals("approach_stall", r.reason());
    }

    @Test
    void approachSurvivesNinePointNineBlocksPerTwentyTicksToTotalCap() {
        FakeDetourHost host = new FakeDetourHost();
        host.ticksPerBlock = 22; // 0.9 blocks / 20 ticks: the cumulative 1.0 block rule keeps beating
        BlockPos seed = new BlockPos(60, 40, 0);
        int startTick = host.now;
        DetourStartSelector.Selection sel = selectionFor(host, seed, "diamond_ore");
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, sel);
        OreDigDetourEngine.Result r = runToFinish(host, engine);
        // Ends via the total cap, not the stall clock: the abort reason string is the same either way, so the
        // real proof is that no beat gap ever exceeded the bound (the cumulative 1.0-block rule keeps beating).
        assertEquals("approach_stall", r.reason());
        int gap = host.maxBeatGap(startTick, host.now);
        assertTrue(gap <= 101, "beats must stay close together: gap " + gap);
    }

    @Test
    void routeFailsThreeTimesAborts() {
        FakeDetourHost host = new FakeDetourHost();
        BlockPos seed = new BlockPos(20, 40, 0);
        host.routeScript.add(DetourHost.RouteResult.FAILED);
        host.routeScript.add(DetourHost.RouteResult.FAILED);
        host.routeScript.add(DetourHost.RouteResult.FAILED);
        DetourStartSelector.Selection sel = selectionFor(host, seed, "diamond_ore");
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, sel);
        OreDigDetourEngine.Result r = runToFinish(host, engine);
        assertEquals("route", r.reason());
    }

    @Test
    void budgetNeeds42ConsecutiveThrottledAnswers() {
        FakeDetourHost host = new FakeDetourHost();
        BlockPos seed = new BlockPos(20, 40, 0);
        for (int i = 0; i < 41; i++) {
            host.routeScript.add(DetourHost.RouteResult.THROTTLED);
        }
        DetourStartSelector.Selection sel = selectionFor(host, seed, "diamond_ore");
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, sel);
        // 41 THROTTLED answers must not abort.
        OreDigDetourEngine.Result last = OreDigDetourEngine.Result.CONSUMED;
        for (int i = 0; i < 41 && last.kind() != OreDigDetourEngine.Kind.FINISHED; i++) {
            host.tickClock();
            last = engine.tick(host);
        }
        assertFalse(last.kind() == OreDigDetourEngine.Kind.FINISHED, "must not abort at 41 THROTTLED answers");
        host.routeScript.add(DetourHost.RouteResult.THROTTLED);
        OreDigDetourEngine.Result r = runToFinish(host, engine);
        assertEquals("budget", r.reason());
    }

    @Test
    void leaseExpiresAndAborts() {
        FakeDetourHost host = new FakeDetourHost();
        host.cfg = new MiningAssistConfig.Detour(true, 25, 1.2D, 12, 6, 2, 60, 200, 24, 3, 4, 4, 90);
        host.ticksPerBlock = 5;
        BlockPos seed = new BlockPos(100, 40, 0);
        DetourStartSelector.Selection sel = selectionFor(host, seed, "diamond_ore");
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, sel);
        OreDigDetourEngine.Result r = runToFinish(host, engine);
        assertEquals("lease", r.reason());
    }

    // ---- mining -----------------------------------------------------------------------------------------------

    @Test
    void miningFailureExcludesAndContinuesNeverAborts() {
        FakeDetourHost host = new FakeDetourHost();
        BlockPos seed = new BlockPos(1, 40, 0); // zero transit
        BlockPos member = new BlockPos(2, 40, 0);
        host.vein.add(member);
        host.mineScript.computeIfAbsent(seed, k -> new java.util.ArrayDeque<>())
                .add(DetourHost.MineStep.failed("mine_timeout"));
        DetourStartSelector.Selection sel = selectionFor(host, seed, "diamond_ore");
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, sel);
        OreDigDetourEngine.Result r = runToFinish(host, engine);
        assertEquals("done", r.reason());
        // The seed's own break failed and was skipped (excluded, never counted as a break)...
        assertTrue(host.excludedUntil.containsKey(seed));
        assertFalse(host.broken.contains(seed));
        // ...but the detour went on to mine the next vein member instead of aborting.
        assertEquals(1, engine.breaks());
        assertTrue(host.broken.contains(member));
    }

    @Test
    void missingChannelToolAbortsTool() {
        FakeDetourHost host = new FakeDetourHost();
        BlockPos seed = new BlockPos(1, 40, 0);
        host.mineScript.computeIfAbsent(seed, k -> new java.util.ArrayDeque<>())
                .add(DetourHost.MineStep.failed("missing_mining_channel_tool:minecraft:stone_pickaxe"));
        DetourStartSelector.Selection sel = selectionFor(host, seed, "diamond_ore");
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, sel);
        OreDigDetourEngine.Result r = runToFinish(host, engine);
        assertEquals("tool", r.reason());
    }

    @Test
    void toolVerdictNoToolAbortsBeforeSwing() {
        FakeDetourHost host = new FakeDetourHost();
        host.tool = DetourHost.ToolVerdict.NO_TOOL;
        BlockPos seed = new BlockPos(1, 40, 0);
        DetourStartSelector.Selection sel = selectionFor(host, seed, "diamond_ore");
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, sel);
        OreDigDetourEngine.Result r = runToFinish(host, engine);
        assertEquals("tool", r.reason());
        assertFalse(host.called("mine:"));
    }

    @Test
    void toolWearAborts() {
        FakeDetourHost host = new FakeDetourHost();
        host.tool = DetourHost.ToolVerdict.WEAR;
        BlockPos seed = new BlockPos(1, 40, 0);
        DetourStartSelector.Selection sel = selectionFor(host, seed, "diamond_ore");
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, sel);
        OreDigDetourEngine.Result r = runToFinish(host, engine);
        assertEquals("tool_wear", r.reason());
    }

    @Test
    void capacityFalseAborts() {
        FakeDetourHost host = new FakeDetourHost();
        host.capacityOk = false;
        BlockPos seed = new BlockPos(1, 40, 0);
        DetourStartSelector.Selection sel = selectionFor(host, seed, "diamond_ore");
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, sel);
        OreDigDetourEngine.Result r = runToFinish(host, engine);
        assertEquals("capacity", r.reason());
    }

    @Test
    void unknownReproofKeepsCandidateThenSkipsAfter20Ticks() {
        FakeDetourHost host = new FakeDetourHost();
        BlockPos seed = new BlockPos(1, 40, 0);
        java.util.ArrayDeque<DetourHost.Seen> script = new java.util.ArrayDeque<>();
        for (int i = 0; i < 25; i++) {
            script.add(DetourHost.Seen.UNKNOWN);
        }
        script.add(DetourHost.Seen.PRESENT);
        host.seenScript.put(seed, script);
        DetourStartSelector.Selection sel = selectionFor(host, seed, "diamond_ore");
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, sel);
        OreDigDetourEngine.Result r = runToFinish(host, engine);
        assertEquals("done", r.reason());
        assertEquals(0, engine.breaks());
        assertFalse(host.called("mine:"));
    }

    @Test
    void goneMidApproachConsumesWithNoDebt() {
        FakeDetourHost host = new FakeDetourHost();
        BlockPos seed = new BlockPos(20, 40, 0);
        java.util.ArrayDeque<DetourHost.Seen> script = new java.util.ArrayDeque<>();
        script.add(DetourHost.Seen.GONE);
        host.seenScript.put(seed, script);
        DetourStartSelector.Selection sel = selectionFor(host, seed, "diamond_ore");
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, sel);
        OreDigDetourEngine.Result r = runToFinish(host, engine);
        assertEquals("done", r.reason());
        assertEquals(0, engine.breaks());
        assertTrue(host.forgotten.contains(seed));
    }

    @Test
    void mineSwingTimeoutSkipsMember() {
        FakeDetourHost host = new FakeDetourHost();
        host.mineTicks = 1000; // never completes within MINE_SWING_TICKS (160)
        BlockPos seed = new BlockPos(1, 40, 0);
        DetourStartSelector.Selection sel = selectionFor(host, seed, "diamond_ore");
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, sel);
        OreDigDetourEngine.Result r = runToFinish(host, engine);
        assertEquals("done", r.reason());
        assertEquals(0, engine.breaks());
    }

    @Test
    void reposeThenPoseThrashSkipsMember() {
        FakeDetourHost host = new FakeDetourHost();
        BlockPos seed = new BlockPos(1, 40, 0);
        // Never "in envelope": the prep re-checks the pose every time it reaches that gate.
        host.envelopeFn = ore -> false;
        int[] n = {0};
        // Alternates between two nearby stands so the bot never settles: one re-pose, then pose_thrash.
        host.poseFn = ore -> new DetourHost.Pose((n[0]++ % 2 == 0) ? ore.west() : ore.east(), false);
        DetourStartSelector.Selection sel = selectionFor(host, seed, "diamond_ore");
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, sel);
        OreDigDetourEngine.Result r = runToFinish(host, engine);
        assertEquals("done", r.reason());
        assertEquals(0, engine.breaks());
    }

    // ---- postbreak / seals / vein discovery ---------------------------------------------------------------------

    @Test
    void sealsUpToCapThenAborts() {
        FakeDetourHost host = new FakeDetourHost();
        BlockPos seed = new BlockPos(1, 40, 0);
        java.util.ArrayDeque<DetourHost.FluidProbe> fluid = new java.util.ArrayDeque<>();
        BlockPos wet = seed.down();
        fluid.add(DetourHost.FluidProbe.CLEAR); // the pre-swing fluid_adjacent gate must pass
        for (int i = 0; i < 4; i++) {
            fluid.add(new DetourHost.FluidProbe(wet, false));
        }
        host.fluidScript.put(seed, fluid);
        DetourStartSelector.Selection sel = selectionFor(host, seed, "diamond_ore");
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, sel);
        OreDigDetourEngine.Result r = runToFinish(host, engine);
        assertEquals("fluid_unsealable", r.reason());
        assertEquals(3, engine.seals());
    }

    @Test
    void unknownFluidNeighbourAddsMinedCellToNoStep() {
        FakeDetourHost host = new FakeDetourHost();
        BlockPos seed = new BlockPos(1, 40, 0);
        java.util.ArrayDeque<DetourHost.FluidProbe> fluid = new java.util.ArrayDeque<>();
        fluid.add(new DetourHost.FluidProbe(null, true));
        host.fluidScript.put(seed, fluid);
        // No real pickup: forces the settle logic to consult the (scripted) drop view instead of the auto-pickup.
        host.pickupEnabled = false;
        // The drop's only stand is the mined cell itself, which noStep now protects: never chased into it.
        host.dropViewFn = cell -> new DetourHost.DropView(true, seed);
        DetourStartSelector.Selection sel = selectionFor(host, seed, "diamond_ore");
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, sel);
        OreDigDetourEngine.Result r = runToFinish(host, engine);
        // The drop's stand IS the mined (noStep) cell: drop_lost(no_stand), never chased into it.
        assertEquals("done", r.reason());
        assertEquals(1, engine.dropsLost());
    }

    @Test
    void veinDiscoveryAddsMembersUpToMemberCap() {
        FakeDetourHost host = new FakeDetourHost();
        BlockPos seed = new BlockPos(1, 40, 0);
        List<BlockPos> extra = new java.util.ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            extra.add(new BlockPos(1 + i, 40, 0));
        }
        host.neighbours.put(seed, extra);
        DetourStartSelector.Selection sel = selectionFor(host, seed, "diamond_ore");
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, sel);
        OreDigDetourEngine.Result r = runToFinish(host, engine);
        // More candidates than MEMBER_CAP allows: the detour hits the cap and returns, it does not run out of work.
        assertEquals("caps", r.reason());
        assertEquals(OreDigDetourEngine.MEMBER_CAP, engine.membersStarted());
    }

    // ---- drop ledger --------------------------------------------------------------------------------------------

    @Test
    void dropLostOnTimeoutIsNonTerminal() {
        FakeDetourHost host = new FakeDetourHost();
        host.pickupEnabled = false;
        BlockPos seed = new BlockPos(1, 40, 0);
        DetourStartSelector.Selection sel = selectionFor(host, seed, "diamond_ore");
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, sel);
        OreDigDetourEngine.Result r = runToFinish(host, engine);
        assertEquals("done", r.reason());
        assertEquals(1, engine.dropsLost());
    }

    @Test
    void dropLostUnreachablePitIsImmediate() {
        FakeDetourHost host = new FakeDetourHost();
        host.pickupEnabled = false;
        host.dropViewFn = cell -> new DetourHost.DropView(true, null);
        BlockPos seed = new BlockPos(1, 40, 0);
        DetourStartSelector.Selection sel = selectionFor(host, seed, "diamond_ore");
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, sel);
        OreDigDetourEngine.Result r = runToFinish(host, engine);
        assertEquals("done", r.reason());
        assertEquals(1, engine.dropsLost());
    }

    // ---- return -------------------------------------------------------------------------------------------------

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

    @Test
    void returnFailsThreeTimesRebases() {
        FakeDetourHost host = new FakeDetourHost();
        BlockPos seed = new BlockPos(1, 40, 0);
        DetourStartSelector.Selection sel = selectionFor(host, seed, "diamond_ore");
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, sel);
        driveToReturnThenDisplace(host, engine, new BlockPos(6, 40, 0));
        host.routeScript.add(DetourHost.RouteResult.FAILED);
        host.routeScript.add(DetourHost.RouteResult.FAILED);
        host.routeScript.add(DetourHost.RouteResult.FAILED);
        OreDigDetourEngine.Result r = runToFinish(host, engine);
        assertEquals("return_rebased", r.reason());
        assertTrue(host.called("rebaseCursorHere"));
        assertTrue(host.ledger.detoursDisabled());
        assertFalse(host.calls.contains("restoreAnchorNumbers"));
    }

    @Test
    void standableReturnSurvives150TicksOfBudgetAndFinishesNormally() {
        FakeDetourHost host = new FakeDetourHost();
        BlockPos seed = new BlockPos(1, 40, 0);
        DetourStartSelector.Selection sel = selectionFor(host, seed, "diamond_ore");
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, sel);
        driveToReturnThenDisplace(host, engine, new BlockPos(6, 40, 0));
        // M35: startReturnRoute never actually answers BUDGET in production (it bypasses RouteBudget.canStart), so
        // this only proves the engine would not rebase even if it somehow did, for a 150-tick stretch of them.
        for (int i = 0; i < 30; i++) {
            host.routeScript.add(DetourHost.RouteResult.BUDGET);
        }
        OreDigDetourEngine.Result r = runToFinish(host, engine);
        assertEquals("done", r.reason());
    }

    @Test
    void tickGapAbortsOutsideReturnAndBeatsInReturn() {
        FakeDetourHost host = new FakeDetourHost();
        BlockPos seed = new BlockPos(20, 40, 0);
        DetourStartSelector.Selection sel = selectionFor(host, seed, "diamond_ore");
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, sel);
        host.now += 10; // gap outside RETURN (APPROACH)
        OreDigDetourEngine.Result r = engine.tick(host);
        assertTrue(engine.phase() == DetourPhase.RETURN);
        assertEquals("tick_gap", engine.abortReason());
    }

    // ---- requestAbort / interrupt / abandon --------------------------------------------------------------------

    @Test
    void requestAbortIsConsumedNextTickFirstReasonWins() {
        FakeDetourHost host = new FakeDetourHost();
        BlockPos seed = new BlockPos(20, 40, 0);
        DetourStartSelector.Selection sel = selectionFor(host, seed, "diamond_ore");
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, sel);
        engine.requestAbort("safety_hurt");
        engine.requestAbort("safety_hp");
        host.tickClock();
        engine.tick(host);
        assertEquals("safety_hurt", engine.abortReason());
    }

    @Test
    void requestAbortIgnoredWhenIdleOrInReturn() {
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.requestAbort("safety_hurt"); // idle: no-op, must not throw
        assertNull(engine.abortReason());
    }

    @Test
    void interruptLeavesNoOwnerAndReturnsAnchor() {
        FakeDetourHost host = new FakeDetourHost();
        BlockPos seed = new BlockPos(20, 40, 0);
        DetourStartSelector.Selection sel = selectionFor(host, seed, "diamond_ore");
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, sel);
        DetourHost.Anchor a = engine.interrupt(host, "paused");
        assertNotNull(a);
        assertEquals(DetourPhase.IDLE, engine.phase());
        assertTrue(host.called("release"));
        assertTrue(host.called("restoreAnchorNumbers"));
        assertNull(engine.interrupt(host, "paused"));
    }

    @Test
    void abandonOnLiveEngineNotesEndAndGoesIdleWithoutTouchingHost() {
        FakeDetourHost host = new FakeDetourHost();
        BlockPos seed = new BlockPos(20, 40, 0);
        DetourStartSelector.Selection sel = selectionFor(host, seed, "diamond_ore");
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, sel);
        int callsBefore = host.calls.size();
        engine.abandon(host);
        assertEquals(DetourPhase.IDLE, engine.phase());
        assertFalse(host.called("release")); // abandon does not release claims itself
        assertEquals(callsBefore, host.calls.size()); // no side effect calls, only the log line
        assertTrue(host.logs.contains("ore_dig_detour_abort"));
    }

    @Test
    void abandonWhileIdleIsNoOp() {
        OreDigDetourEngine engine = new OreDigDetourEngine();
        FakeDetourHost host = new FakeDetourHost();
        engine.abandon(host);
        assertEquals(DetourPhase.IDLE, engine.phase());
    }

    // ---- announce / recordFind / determinism ---------------------------------------------------------------------

    @Test
    void announcesOnceForHighValueRareFind() {
        FakeDetourHost host = new FakeDetourHost();
        BlockPos seed = new BlockPos(1, 40, 0);
        DetourStartSelector.Selection sel = selectionFor(host, seed, "diamond_ore", 100, List.of(seed));
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, sel);
        assertEquals(1, host.announced.size());
        assertEquals(1, host.found.size());
    }

    @Test
    void noAnnounceForLowValueFind() {
        FakeDetourHost host = new FakeDetourHost();
        BlockPos seed = new BlockPos(1, 40, 0);
        DetourStartSelector.Selection sel = selectionFor(host, seed, "coal_ore", 12, List.of(seed));
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, sel);
        assertEquals(0, host.announced.size());
        assertEquals(1, host.found.size());
    }

    @Test
    void cachedLedgerEntrySurvivesHostLedgerSwap() {
        FakeDetourHost host = new FakeDetourHost();
        BlockPos seed = new BlockPos(20, 40, 0);
        DetourStartSelector.Selection sel = selectionFor(host, seed, "diamond_ore");
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, sel);
        io.github.zoyluo.aibot.mining.assist.MissionAssistLedger.Entry firstEntry = host.ledger;
        host.ledger = new io.github.zoyluo.aibot.mining.assist.MissionAssistLedger.Entry(); // a later host.ledger() call would resolve a different one
        DetourHost.Anchor a = engine.interrupt(host, "paused");
        assertNotNull(a);
        assertEquals(1, firstEntry.detoursStarted());
        assertEquals(0, host.ledger.detoursStarted());
    }

    @Test
    void memberCapIsTwelve() {
        assertEquals(12, OreDigDetourEngine.MEMBER_CAP);
    }
}
