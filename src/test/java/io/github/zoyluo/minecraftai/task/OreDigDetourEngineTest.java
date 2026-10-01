package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.mining.assist.DetourPhase;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistConfig;
import io.github.zoyluo.minecraftai.mining.assist.MissionAssistLedger;
import io.github.zoyluo.minecraftai.mining.assist.SafeReason;
import io.github.zoyluo.minecraftai.mining.assist.SightingLedger;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        host.safetyFn = stage -> stage == io.github.zoyluo.minecraftai.mining.assist.SafeGate.Stage.TICK_FULL
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
        BlockPos wet = seed.below();
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
    void aDeferredReactiveShieldSealWaitsWithoutSpendingTheSealOrPostbreakBudget() {
        FakeDetourHost host = new FakeDetourHost();
        BlockPos seed = new BlockPos(1, 40, 0);
        BlockPos wet = seed.below();
        final int waitingTicks = 400; // exceeds the default 360t post-break detour lease
        java.util.ArrayDeque<DetourHost.FluidProbe> fluid = new java.util.ArrayDeque<>();
        fluid.add(DetourHost.FluidProbe.CLEAR); // pre-swing gate
        for (int i = 0; i <= waitingTicks; i++) {
            fluid.add(new DetourHost.FluidProbe(wet, false));
        }
        host.fluidScript.put(seed, fluid);
        host.sealResult = DetourHost.SealResult.WAITING;
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, selectionFor(host, seed, "diamond_ore"));

        for (int i = 0; i < 2_000 && host.countCalls("seal:") < waitingTicks; i++) {
            host.tickClock();
            assertFalse(engine.tick(host).kind() == OreDigDetourEngine.Kind.FINISHED,
                    "a deferred hand must not abort the detour");
        }
        assertEquals(waitingTicks, host.countCalls("seal:"),
                "every shield-held tick retries the same observed fluid without advancing phases");
        assertEquals(0, engine.seals(), "a shield-held attempt is not a physical seal");
        assertEquals(DetourPhase.POSTBREAK, engine.phase());
        assertTrue(host.countCalls("beat") >= waitingTicks,
                "the shield-held wait refreshes the outer OreDig heartbeat as well as the local timer");

        host.sealResult = DetourHost.SealResult.SEALED;
        OreDigDetourEngine.Result result = runToFinish(host, engine);
        assertEquals("done", result.reason());
        assertEquals(1, engine.seals(), "the same observed fluid is sealed only after the hand is free");
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
        host.dropViewFn = cell -> new DetourHost.DropView(true, seed, true);
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

    @Test
    void aDifferentBlockSightingRevealedByABreakNeverBecomesAMemberOfTheRunningSameBlockVein() {
        // Design 3.3/4.7/4.8: a break peek that reveals a DIFFERENT valuable as a neighbour of the break
        // folds into SightingLedger as an ordinary opportunistic sighting, never into the same-block vein
        // this detour is following. host.neighbours26Same is contractually keyed by blockId (the real host
        // only ever returns neighbours OBSERVED_PRESENT as the exact same block, OreDigTask.neighbours26Same),
        // so a fixture that leaves it empty for a foreign block is the faithful case; this asserts the
        // engine never schedules, mines or consumes that foreign sighting while following the real vein.
        FakeDetourHost host = new FakeDetourHost();
        BlockPos seed = new BlockPos(1, 40, 0);
        BlockPos differentBlockNeighbour = new BlockPos(2, 40, 0);
        host.sightings.add(new SightingLedger.Sighting(differentBlockNeighbour, "gold_ore", 45, 0, 0));

        DetourStartSelector.Selection sel = selectionFor(host, seed, "diamond_ore");
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, sel);
        OreDigDetourEngine.Result r = runToFinish(host, engine);

        assertEquals("done", r.reason());
        assertEquals(1, engine.membersStarted(), "only the seed was mined -- the foreign neighbour never joined the vein");
        assertFalse(host.calls.contains("mine:2,40,0"), "a different block must never be scheduled as a same-block vein member");
        assertFalse(host.forgotten.contains(differentBlockNeighbour), "the foreign sighting is untouched by this detour");
        assertEquals(1, host.sightings().size(),
                "the foreign sighting still sits in the ledger, unconsumed -- available for its own start check later");
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
        // At rest (it settled into the pit) but no legal stand: a real "no reachable floor", declared
        // as soon as the settle-min grace period allows, not held back waiting for it to "land" again.
        host.dropViewFn = cell -> new DetourHost.DropView(true, null, true);
        BlockPos seed = new BlockPos(1, 40, 0);
        DetourStartSelector.Selection sel = selectionFor(host, seed, "diamond_ore");
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, sel);
        OreDigDetourEngine.Result r = runToFinish(host, engine);
        assertEquals("done", r.reason());
        assertEquals(1, engine.dropsLost());
    }

    @Test
    void settleDropDoesNotDeclareNoStandWhileTheItemIsStillFalling() {
        // Regression for the bug the mining-assist design 4.9 fix addresses: at SETTLE_MIN_TICKS the
        // freshly spawned item has not finished falling out of the break cell, so no stand is computed
        // yet (stand == null, atRest == false). That must NOT be written off as no_stand -- it means
        // "not yet known". A real stand appears once it settles, well before the SETTLE_TOTAL_TICKS cap.
        FakeDetourHost host = new FakeDetourHost();
        host.pickupEnabled = false;
        BlockPos seed = new BlockPos(1, 40, 0);
        java.util.concurrent.atomic.AtomicInteger observeCalls = new java.util.concurrent.atomic.AtomicInteger();
        host.dropViewFn = cell -> {
            int n = observeCalls.getAndIncrement();
            if (n == 0) {
                // First observation (at SETTLE_MIN_TICKS): still airborne, no stand computed yet.
                return new DetourHost.DropView(true, null, false);
            }
            // Settled by the next observation, right where the bot already stands: forced pickup
            // (called every settle tick) grabs it now that it is at rest and in reach.
            host.inventoryTotal++;
            return new DetourHost.DropView(true, host.feet(), true);
        };
        DetourStartSelector.Selection sel = selectionFor(host, seed, "diamond_ore");
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, sel);
        OreDigDetourEngine.Result r = runToFinish(host, engine);

        assertEquals("done", r.reason());
        assertEquals(0, engine.dropsLost(), "a still-falling item must not be written off as no_stand");
        assertTrue(observeCalls.get() >= 2, "the settle logic must re-observe the drop instead of giving up on the first null stand");
    }

    @Test
    void settleDropKeepsWaitingForAnItemStillFallingPastTheNoDropTick() {
        // Real-server flake root cause: a popped item can take 12+ ticks to land one block lower, so it
        // is still airborne (stand == null, atRest == false) at SETTLE_NO_DROP_TICKS. That tick only
        // settles "no visible drop"; it must not turn a still-falling item into no_stand.
        FakeDetourHost host = new FakeDetourHost();
        host.pickupEnabled = false;
        BlockPos seed = new BlockPos(1, 40, 0);
        java.util.concurrent.atomic.AtomicInteger observeCalls = new java.util.concurrent.atomic.AtomicInteger();
        int airborneObservations = OreDigDetourEngine.SETTLE_NO_DROP_TICKS + 4;
        host.dropViewFn = cell -> {
            if (observeCalls.getAndIncrement() < airborneObservations) {
                return new DetourHost.DropView(true, null, false);
            }
            host.inventoryTotal++;
            return new DetourHost.DropView(true, host.feet(), true);
        };
        DetourStartSelector.Selection sel = selectionFor(host, seed, "diamond_ore");
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, sel);
        OreDigDetourEngine.Result r = runToFinish(host, engine);

        assertEquals("done", r.reason());
        assertEquals(0, engine.dropsLost(), "an item still falling after the no-drop tick is not a lost drop");
        assertTrue(observeCalls.get() > airborneObservations, "the drop must be re-observed until it lands");
    }

    @Test
    void settleDropDeclaresNoStandOnceTheStillAirborneGraceWindowElapses() {
        // The other half of the same fix: the "not yet known" grace period is bounded by
        // SETTLE_AIRBORNE_TICKS, so a drop that is STILL not at rest by then is finally written off
        // instead of being chased forever (design 4.9's "at most 60 ticks" cap is not the only bound).
        FakeDetourHost host = new FakeDetourHost();
        host.pickupEnabled = false;
        BlockPos seed = new BlockPos(1, 40, 0);
        // Always airborne, stand never computed -- exercises the "before SETTLE_AIRBORNE_TICKS" bound
        // rather than the "at rest" one.
        host.dropViewFn = cell -> new DetourHost.DropView(true, null, false);
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
        io.github.zoyluo.minecraftai.mining.assist.MissionAssistLedger.Entry firstEntry = host.ledger;
        host.ledger = new io.github.zoyluo.minecraftai.mining.assist.MissionAssistLedger.Entry(); // a later host.ledger() call would resolve a different one
        DetourHost.Anchor a = engine.interrupt(host, "paused");
        assertNotNull(a);
        assertEquals(1, firstEntry.detoursStarted());
        assertEquals(0, host.ledger.detoursStarted());
    }

    @Test
    void memberCapIsTwelve() {
        assertEquals(12, OreDigDetourEngine.MEMBER_CAP);
    }

    // ===========================================================================================================
    // Kind FRONTIER (mining-assist design 5.4, P5 R2b cave frontier)
    // ===========================================================================================================

    @Test
    void frontierSelectionRejectsEmptyOrTooManyWaypointsButAcceptsOneToThree() {
        BlockPos a = new BlockPos(1, 40, 0);
        BlockPos b = new BlockPos(2, 40, 0);
        BlockPos c = new BlockPos(3, 40, 0);
        BlockPos d = new BlockPos(4, 40, 0);

        assertThrows(IllegalArgumentException.class, () -> new OreDigDetourEngine.FrontierSelection(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new OreDigDetourEngine.FrontierSelection(List.of(a, b, c, d)));
        assertDoesNotThrow(() -> new OreDigDetourEngine.FrontierSelection(List.of(a)));
        assertDoesNotThrow(() -> new OreDigDetourEngine.FrontierSelection(List.of(a, b)));
        assertDoesNotThrow(() -> new OreDigDetourEngine.FrontierSelection(List.of(a, b, c)));
        assertEquals(3, OreDigDetourEngine.FRONTIER_MAX_WAYPOINTS);
    }

    @Test
    void startFrontierCapturesAnchorClearsStripOwnershipAndEntersFrontierWalk() {
        FakeDetourHost host = new FakeDetourHost();
        BlockPos wp1 = new BlockPos(6, 40, 0);
        BlockPos wp2 = new BlockPos(12, 40, 0);
        OreDigDetourEngine.FrontierSelection sel = new OreDigDetourEngine.FrontierSelection(List.of(wp1, wp2));
        OreDigDetourEngine engine = new OreDigDetourEngine();

        engine.startFrontier(host, sel);

        assertEquals(DetourPhase.FRONTIER_WALK, engine.phase());
        assertEquals(new BlockPos(0, 40, 0), engine.anchor().face());
        assertTrue(host.called("clearStripOwnership"));
        assertTrue(host.called("stopAll"));
        assertTrue(host.called("beat"));
        assertEquals(1, host.ledger.detoursStarted());
        assertTrue(host.logs.contains("ore_dig_frontier_start"));
    }

    @Test
    void multiWaypointFrontierWalkAdvancesThroughEachLegInOrderThenPanoramaBurstsOnce() {
        FakeDetourHost host = new FakeDetourHost();
        BlockPos wp1 = new BlockPos(3, 40, 0);
        BlockPos wp2 = new BlockPos(6, 40, 0);
        BlockPos wp3 = new BlockPos(9, 40, 0);
        OreDigDetourEngine.FrontierSelection sel = new OreDigDetourEngine.FrontierSelection(List.of(wp1, wp2, wp3));
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.startFrontier(host, sel);
        assertEquals(DetourPhase.FRONTIER_WALK, engine.phase());

        boolean sawWp1 = false;
        boolean sawWp2 = false;
        int guard = 0;
        while (host.panoramaBursts == 0 && guard < 500) {
            host.tickClock();
            engine.tick(host);
            sawWp1 |= host.feet.equals(wp1);
            sawWp2 |= host.feet.equals(wp2);
            guard++;
        }
        assertTrue(sawWp1, "the bot must physically pass through the first waypoint");
        assertTrue(sawWp2, "the bot must physically pass through the second waypoint");
        assertEquals(1, host.panoramaBursts, "panorama burst fires once, at the final waypoint");
        assertEquals(wp3, host.feet, "arrival is processed exactly at the final waypoint");

        // Zero new sightings: unproductive, so the excursion still finishes normally (via RETURN) and the
        // burst count from the walk above must not grow during it.
        OreDigDetourEngine.Result r = runToFinish(host, engine);
        assertEquals("done", r.reason());
        assertEquals(1, host.panoramaBursts, "no extra panorama burst is fired during the return leg");
    }

    @Test
    void frontierExcursionWithFewerThanTwoNewSightingsReturnsToAnchorLikeAnOreDetour() {
        FakeDetourHost host = new FakeDetourHost();
        BlockPos wp1 = new BlockPos(6, 40, 0);
        OreDigDetourEngine.FrontierSelection sel = new OreDigDetourEngine.FrontierSelection(List.of(wp1));
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.startFrontier(host, sel);
        // Exactly one new sighting, recorded only after the snapshot startFrontier already took: below
        // FRONTIER_PRODUCTIVE_SIGHTINGS (2), so the excursion must count as unproductive.
        host.sightings.add(new SightingLedger.Sighting(new BlockPos(20, 40, 0), "coal_ore", 12, 0, 0));

        OreDigDetourEngine.Result r = runToFinish(host, engine);

        assertEquals("done", r.reason());
        assertEquals(1, host.panoramaBursts);
        assertTrue(engine.wasFrontierUnproductive(), "fewer than 2 new sightings must read as unproductive");
        assertTrue(host.called("return:"), "an unproductive excursion walks the ordinary RETURN leg");
        assertFalse(host.called("rebaseCursorHere"));
        assertTrue(host.called("restoreAnchorNumbers"));
        assertEquals(new BlockPos(0, 40, 0), host.feet, "the bot walks all the way back to the anchor face");
    }

    /** Regression test for the bug the final P5 R2b contract revision fixes (contract section 5.7): a prior
     *  draft only ever set {@code lastFrontierProductive} in the unproductive branch, so a genuinely productive
     *  excursion still read as unproductive afterward and wrongly armed the cooldown after every excursion. */
    @Test
    void frontierExcursionThatGainsEnoughSightingsRebasesTheCursorAndSkipsReturn() {
        FakeDetourHost host = new FakeDetourHost();
        BlockPos wp1 = new BlockPos(6, 40, 0);
        OreDigDetourEngine.FrontierSelection sel = new OreDigDetourEngine.FrontierSelection(List.of(wp1));
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.startFrontier(host, sel);
        // Two new sightings, at FRONTIER_PRODUCTIVE_SIGHTINGS (2): the excursion counts as productive.
        host.sightings.add(new SightingLedger.Sighting(new BlockPos(20, 40, 0), "diamond_ore", 100, 0, 0));
        host.sightings.add(new SightingLedger.Sighting(new BlockPos(21, 40, 0), "diamond_ore", 100, 0, 0));

        OreDigDetourEngine.Result r = runToFinish(host, engine);

        assertEquals("productive", r.reason());
        assertEquals(1, host.panoramaBursts);
        assertFalse(engine.wasFrontierUnproductive(),
                "wasFrontierUnproductive() must read false immediately after a productive FINISHED");
        assertTrue(host.called("rebaseCursorHere"));
        assertFalse(host.called("return:"), "a productive excursion must skip RETURN entirely");
        assertFalse(host.called("restoreAnchorNumbers"), "finish(host, false): the anchor numbers are not restored");
        assertEquals(wp1, host.feet, "the bot stays at the frontier; the strip cursor rebases there, not at the anchor");
    }

    @Test
    void routeFailsThreeTimesDuringFrontierWalkAbortsAndReturnsLikeApproach() {
        FakeDetourHost host = new FakeDetourHost();
        BlockPos wp1 = new BlockPos(20, 40, 0);
        host.routeScript.add(DetourHost.RouteResult.FAILED);
        host.routeScript.add(DetourHost.RouteResult.FAILED);
        host.routeScript.add(DetourHost.RouteResult.FAILED);
        OreDigDetourEngine.FrontierSelection sel = new OreDigDetourEngine.FrontierSelection(List.of(wp1));
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.startFrontier(host, sel);

        OreDigDetourEngine.Result r = runToFinish(host, engine);

        assertEquals("route", r.reason());
        assertTrue(engine.wasFrontierUnproductive(), "an aborted-before-arrival excursion reads as unproductive");
        assertEquals(0, host.panoramaBursts, "the excursion never reached the frontier to trigger a burst");
    }

    /** Section 11.5's named risk: no GameTest exercises a SafeGate trip mid-excursion for kind FRONTIER, so this
     *  unit test (mirroring {@link #safetyFailureMidApproachAbortsAndReturns}) covers it at the engine level. */
    @Test
    void safetyTripMidFrontierWalkAbortsAndReturns() {
        FakeDetourHost host = new FakeDetourHost();
        host.ticksPerBlock = 20; // slow enough that the safety flip lands mid-leg
        BlockPos wp1 = new BlockPos(20, 40, 0);
        OreDigDetourEngine.FrontierSelection sel = new OreDigDetourEngine.FrontierSelection(List.of(wp1));
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.startFrontier(host, sel);

        host.safetyFn = stage -> SafeReason.HOSTILE_PRESSURE;
        OreDigDetourEngine.Result r = runToFinish(host, engine);

        assertEquals("safety_hostile_pressure", r.reason());
        assertTrue(engine.wasFrontierUnproductive());
        assertEquals(0, host.panoramaBursts, "the excursion never reached the frontier to trigger a burst");
    }

    /** Audit finding (b): a FRONTIER excursion's own pacing is {@code OreDigTask.frontierCooldownUntilServerTick}
     *  plus design 5.4's own trigger conditions, which never consult the mission ledger to start. Ending a
     *  FRONTIER excursion must therefore leave {@code lastEndTick} alone, so it never arms the shared
     *  {@code min_interval * 2^routeFailures} INTERVAL rule (design 4.3) that gates ordinary ORE detour starts. */
    @Test
    void unproductiveFrontierExcursionEndDoesNotArmTheOreStartInterval() {
        FakeDetourHost host = new FakeDetourHost();
        BlockPos wp1 = new BlockPos(6, 40, 0);
        OreDigDetourEngine.FrontierSelection sel = new OreDigDetourEngine.FrontierSelection(List.of(wp1));
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.startFrontier(host, sel);

        OreDigDetourEngine.Result r = runToFinish(host, engine);
        assertEquals("done", r.reason(), "unproductive: walks the ordinary RETURN leg back to the anchor");

        assertEquals(MissionAssistLedger.NEVER, host.ledger.lastEndTick(),
                "a FRONTIER excursion ending must not set lastEndTick");
        assertEquals(MissionAssistLedger.StartVerdict.OK,
                host.ledger.startVerdict(host.serverTick, host.cfg, host.maxElapsed),
                "the shared ledger must not read INTERVAL for an ORE start right after a FRONTIER excursion ends");
    }

    /** Same as above, for the productive path ({@link #finish(DetourHost, boolean)} called with
     *  {@code restoreAnchor = false} from {@code arriveAtFrontier}), and for a FRONTIER excursion that fails
     *  its walk-only RETURN and rebases the cursor ({@code rebase}) -- both of the other {@code noteEnd} call
     *  sites besides the ordinary FINISH path exercised above. */
    @Test
    void productiveFrontierExcursionEndDoesNotArmTheOreStartInterval() {
        FakeDetourHost host = new FakeDetourHost();
        BlockPos wp1 = new BlockPos(6, 40, 0);
        OreDigDetourEngine.FrontierSelection sel = new OreDigDetourEngine.FrontierSelection(List.of(wp1));
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.startFrontier(host, sel);
        host.sightings.add(new SightingLedger.Sighting(new BlockPos(20, 40, 0), "diamond_ore", 100, 0, 0));
        host.sightings.add(new SightingLedger.Sighting(new BlockPos(21, 40, 0), "diamond_ore", 100, 0, 0));

        OreDigDetourEngine.Result r = runToFinish(host, engine);
        assertEquals("productive", r.reason());

        assertEquals(MissionAssistLedger.NEVER, host.ledger.lastEndTick(),
                "a productive FRONTIER excursion ending must not set lastEndTick either");
    }

    /** Audit finding (a): {@code approach_stall}/{@code route} aborts are reachable from BOTH
     *  {@code tickApproach} (ORE) and {@code tickFrontierWalk} (FRONTIER). A FRONTIER route failure must not
     *  feed the ORE-only route-failure counters that {@code zeroTransitOnly} and the INTERVAL exponent read. */
    @Test
    void frontierRouteFailureDoesNotIncrementOreRouteFailureCounters() {
        FakeDetourHost host = new FakeDetourHost();
        BlockPos wp1 = new BlockPos(20, 40, 0);
        host.routeScript.add(DetourHost.RouteResult.FAILED);
        host.routeScript.add(DetourHost.RouteResult.FAILED);
        host.routeScript.add(DetourHost.RouteResult.FAILED);
        OreDigDetourEngine.FrontierSelection sel = new OreDigDetourEngine.FrontierSelection(List.of(wp1));
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.startFrontier(host, sel);

        OreDigDetourEngine.Result r = runToFinish(host, engine);
        assertEquals("route", r.reason());

        assertEquals(0, host.ledger.consecutiveRouteFailures(),
                "a FRONTIER route failure must not increment the ORE-only consecutiveRouteFailures counter");
        assertEquals(0, host.ledger.missionRouteFailures(),
                "a FRONTIER route failure must not increment the ORE-only missionRouteFailures counter");
        assertFalse(host.ledger.zeroTransitOnly(host.serverTick),
                "a FRONTIER route failure must never arm the ORE-only zeroTransitOnly window");
    }
}
