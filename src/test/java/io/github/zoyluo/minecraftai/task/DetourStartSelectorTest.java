package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.mining.assist.ObservedReach;
import io.github.zoyluo.minecraftai.mining.assist.SightingLedger;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link DetourStartSelector} (P1 contract section C.2, design 4.3/4.5).
 *
 * <p><b>Dependency note (H.2).</b> {@code select(host)}'s very first side-effecting step past the cheap
 * owners/age gate is {@code host.ledger().startVerdict(...)}, a {@code MissionAssistLedger.Entry} instance
 * method (WRITER-2); the next is {@code DetourPolicy.rank(...)} (WRITER-1). {@code MissionAssistLedger.Entry}
 * is a {@code public static final} class (not an interface), so its stub body cannot be overridden or faked:
 * every test that reaches past the owners/age gate necessarily throws {@code UnsupportedOperationException}
 * from that stub until WRITER-2 lands, whatever this class's own logic does. Per this wave's rule ("write the
 * code first, tests last... run the subset that does not need it yet"), the tests below are organized in two
 * groups: {@code CheckDue*}/{@code Gate*} (no dependency, green today) and the rest (written to the contract,
 * intended to go green once WRITER-1 and WRITER-2 land; see the final report for the run actually observed).</p>
 */
class DetourStartSelectorTest {

    // =================================================================================================
    // select(): the cheap owners/age gate, before any WRITER-1/WRITER-2 call is made. Design 4.3 / the engine's
    // due/consume cadence (see OreDigDetourEngine and its IDLE-cadence tests): this is the ONE outcome for which
    // Result.pastCheapGate() is false, telling the engine's caller not to consume the due check. Every other
    // outcome below (including a silent NONE for empty sightings, an empty rank, or a failed safety gate)
    // consumes it -- asserted once here since selectReturnsTheSelectionOnceEveryGatePasses and the other tests
    // below never reach the cheap gate at all.
    // =================================================================================================

    @Test
    void selectRefusesSilentlyWhenOwnersAreNotIdleAndTouchesNothingElse() {
        FakeDetourHost host = new FakeDetourHost();
        host.ownersIdle = false;
        host.now = 1000;

        DetourStartSelector.Result r = DetourStartSelector.select(host);

        assertNull(r.selection());
        assertEquals("", r.reason());
        assertFalse(r.pastCheapGate(), "owners-not-idle must not consume the engine's due check");
        assertTrue(host.calls.isEmpty(), "must not call the host at all once owners are not idle");
        assertTrue(host.logs.isEmpty());
    }

    @Test
    void selectRefusesBeforeTheTaskIsSixtyTicksOld() {
        FakeDetourHost host = new FakeDetourHost();
        host.ownersIdle = true;
        host.now = DetourStartSelector.MIN_TASK_AGE_TICKS - 1;

        DetourStartSelector.Result r = DetourStartSelector.select(host);

        assertNull(r.selection());
        assertFalse(r.pastCheapGate(), "too-young must not consume the engine's due check either");
        assertTrue(host.calls.isEmpty());
    }

    // =================================================================================================
    // Everything below exercises the candidate loop and therefore needs both host.ledger().startVerdict
    // (WRITER-2's MissionAssistLedger.Entry, a stub) and DetourPolicy.rank/pathLengthOk (WRITER-1's
    // DetourPolicy, a stub). They are written to the exact contract (section C.2) and the class Javadoc of
    // DetourStartSelector, and are expected to go green once those two land; see the final report for what
    // actually ran.
    // =================================================================================================

    private static SightingLedger.Sighting sighting(BlockPos pos, String blockId, int value) {
        return new SightingLedger.Sighting(pos, blockId, value, 0, 0);
    }

    /** A little more control than the frozen {@link FakeDetourHost} exposes (a per-position capacity/tool
     * verdict), used only by this file, per H.1 ("subclass in your own test file... and report the wish"). */
    private static final class TunableFakeDetourHost extends FakeDetourHost {
        final Map<BlockPos, Boolean> capacityByPos = new HashMap<>();
        final Map<BlockPos, ToolVerdict> toolByPos = new HashMap<>();

        @Override
        public boolean capacityOk(BlockPos ore, String blockId) {
            Boolean v = capacityByPos.get(ore);
            return v != null ? v : super.capacityOk(ore, blockId);
        }

        @Override
        public ToolVerdict toolVerdict(BlockPos ore, int plannedMembers) {
            ToolVerdict v = toolByPos.get(ore);
            if (v != null) {
                calls.add("tool:" + ore.getX() + "," + ore.getY() + "," + ore.getZ() + ":" + plannedMembers);
                return v;
            }
            return super.toolVerdict(ore, plannedMembers);
        }
    }

    @Test
    void selectReturnsNoneWhenSightingsAreEmpty() {
        FakeDetourHost host = new FakeDetourHost();
        host.now = 1000;

        DetourStartSelector.Result r = DetourStartSelector.select(host);
        assertNull(r.selection());
        assertTrue(r.pastCheapGate(), "got past owners/age, so this DOES consume the engine's due check");
    }

    @Test
    void selectSkipsAnExcludedCandidateAndLogsOnceAtMostPer600Ticks() {
        FakeDetourHost host = new FakeDetourHost();
        host.now = 1000;
        BlockPos pos = new BlockPos(5, 40, 5);
        host.sightings.add(sighting(pos, "diamond_ore", 100));
        host.excludedUntil.put(pos, host.serverTick + 1);

        DetourStartSelector.Result r = DetourStartSelector.select(host);

        assertNull(r.selection());
        assertTrue(host.logs.contains("ore_dig_detour_skip"));
        assertFalse(host.calls.stream().anyMatch(c -> c.startsWith("mine:") || c.startsWith("tool:")),
                "an excluded candidate must never reach the re-proof or the tool gate");
    }

    @Test
    void selectSkipsATargetOreAndABonusOwnedCellSilently() {
        FakeDetourHost host = new FakeDetourHost();
        host.now = 1000;
        BlockPos targetPos = new BlockPos(1, 40, 1);
        BlockPos bonusPos = new BlockPos(2, 40, 2);
        host.sightings.add(sighting(targetPos, "iron_ore", 30));
        host.sightings.add(sighting(bonusPos, "gold_ore", 45));
        host.targetIds.add("iron_ore");
        host.bonusOwned.add(bonusPos);

        DetourStartSelector.Result r = DetourStartSelector.select(host);

        assertNull(r.selection());
        assertTrue(host.logs.isEmpty(), "isTargetOre/bonusOwns skips are silent, never logged");
    }

    @Test
    void selectSkipsACellClaimedByAnotherBotAndLogsIt() {
        FakeDetourHost host = new FakeDetourHost();
        host.now = 1000;
        BlockPos pos = new BlockPos(5, 40, 5);
        host.sightings.add(sighting(pos, "gold_ore", 20));
        host.claimedByOthers.add(pos);

        DetourStartSelector.Result r = DetourStartSelector.select(host);

        assertNull(r.selection());
        assertTrue(host.logs.contains("ore_dig_detour_skip"));
    }

    @Test
    void selectForgetsAGoneSightingAndDoesNotExcludeIt() {
        FakeDetourHost host = new FakeDetourHost();
        host.now = 1000;
        BlockPos pos = new BlockPos(3, 40, 0);
        host.sightings.add(sighting(pos, "diamond_ore", 100));
        host.seenScript.computeIfAbsent(pos, p -> new java.util.ArrayDeque<>()).add(DetourHost.Seen.GONE);

        DetourStartSelector.Result r = DetourStartSelector.select(host);

        assertNull(r.selection());
        assertTrue(host.forgotten.contains(pos));
        assertFalse(host.excludedUntil.containsKey(pos), "a gone sighting is forgotten, never excluded");
    }

    @Test
    void selectKeepsAnUnknownNominationAndLogsItWithoutExcluding() {
        FakeDetourHost host = new FakeDetourHost();
        host.now = 1000;
        BlockPos pos = new BlockPos(3, 40, 0);
        host.sightings.add(sighting(pos, "diamond_ore", 100));
        host.seenScript.computeIfAbsent(pos, p -> new java.util.ArrayDeque<>()).add(DetourHost.Seen.UNKNOWN);

        DetourStartSelector.Result r = DetourStartSelector.select(host);

        assertNull(r.selection());
        assertFalse(host.forgotten.contains(pos));
        assertFalse(host.excludedUntil.containsKey(pos));
        assertTrue(host.logs.contains("ore_dig_detour_skip"));
    }

    @Test
    void selectStopsReproofingAfterMaxReproofsCandidates() {
        FakeDetourHost host = new FakeDetourHost();
        host.now = 1000;
        // Nine candidates that all need a re-proof (none excluded/target/bonus/claimed): only the first
        // MAX_REPROOFS (8) may reach host.observeBlockIs (one scripted UNKNOWN each; a consumed queue
        // entry means the re-proof ran). High value (diamond_ore) and small offsets keep every one of
        // them admitted (within range and under maxCost), so DetourPolicy.rank does not thin the list.
        BlockPos[] positions = new BlockPos[9];
        for (int i = 0; i < 9; i++) {
            positions[i] = new BlockPos(i + 1, 40, 0);
            host.sightings.add(sighting(positions[i], "diamond_ore", 100));
            host.seenScript.computeIfAbsent(positions[i], p -> new java.util.ArrayDeque<>()).add(DetourHost.Seen.UNKNOWN);
        }

        DetourStartSelector.select(host);

        int observed = 0;
        for (BlockPos pos : positions) {
            if (host.seenScript.get(pos).isEmpty()) {
                observed++;
            }
        }
        assertEquals(DetourStartSelector.MAX_REPROOFS, observed);
    }

    @Test
    void selectStopsEvaluatingAfterMaxEvaluatedPresentCandidates() {
        TunableFakeDetourHost host = new TunableFakeDetourHost();
        host.now = 1000;
        // Six candidates that all re-proof PRESENT (the default Seen): only MAX_EVALUATED (4) may reach
        // the pose search (capacityOk is the first "evaluated" gate in the contract's order). High value
        // keeps every one of them admitted by DetourPolicy.rank; the offsets are pairwise more than
        // Chebyshev 3 apart so a capacity skip's own cluster exclusion (SOFT_EXCLUDE_TICKS) never
        // sidelines a later candidate through the "excluded" gate instead of the evaluated cap itself.
        int[][] offsets = {{4, 0}, {-4, 0}, {0, 4}, {0, -4}, {4, 4}, {-4, -4}};
        for (int[] offset : offsets) {
            BlockPos pos = new BlockPos(offset[0], 40, offset[1]);
            host.sightings.add(sighting(pos, "diamond_ore", 100));
            host.capacityByPos.put(pos, false); // always skip after being counted as evaluated
        }

        DetourStartSelector.select(host);

        assertEquals(DetourStartSelector.MAX_EVALUATED,
                host.logs.stream().filter(e -> e.equals("ore_dig_detour_skip")).count());
    }

    @Test
    void selectSkipsForCapacityAndBacksOffTheClusterForOneHundredTicks() {
        TunableFakeDetourHost host = new TunableFakeDetourHost();
        host.now = 1000;
        BlockPos pos = new BlockPos(0, 40, 0);
        host.sightings.add(sighting(pos, "diamond_ore", 100));
        host.capacityByPos.put(pos, false);

        DetourStartSelector.select(host);

        Integer until = host.excludedUntil.get(pos);
        assertEquals(host.serverTick + DetourStartSelector.SOFT_EXCLUDE_TICKS, until);
    }

    @Test
    void selectSkipsForNoPoseAndExcludesTheClusterForSixHundredTicks() {
        FakeDetourHost host = new FakeDetourHost();
        host.now = 1000;
        BlockPos pos = new BlockPos(0, 40, 0);
        host.sightings.add(sighting(pos, "diamond_ore", 100));
        host.poseFn = ore -> null;

        DetourStartSelector.select(host);

        Integer until = host.excludedUntil.get(pos);
        assertEquals(host.serverTick + DetourStartSelector.CLUSTER_EXCLUDE_TICKS, until);
    }

    @Test
    void unknownHazardOnlyNoPoseStaysEligibleAfterMovementReobservation() {
        class UnknownHazardHost extends FakeDetourHost {
            boolean unknownHazardOnly = true;

            @Override
            public boolean poseBlockedOnlyByUnknownHazard(BlockPos ore, DetourHost.Anchor anchor,
                                                          java.util.Set<BlockPos> forbiddenStands) {
                return unknownHazardOnly;
            }
        }
        UnknownHazardHost host = new UnknownHazardHost();
        host.now = 1000;
        BlockPos pos = new BlockPos(4, 40, 0);
        host.sightings.add(sighting(pos, "diamond_ore", 100));
        host.poseFn = ore -> null;

        DetourStartSelector.Result hidden = DetourStartSelector.select(host);

        assertNull(hidden.selection());
        assertFalse(host.excludedUntil.containsKey(pos),
                "an UNKNOWN adjacent-hazard pose must not receive the 600-tick no-pose exclusion");
        assertTrue(host.logs.contains("ore_dig_detour_skip"));

        // A normal walk changes the observation angle; after the hazard proof becomes factual,
        // the same retained sighting is admitted on the next selector pass.
        host.feet = host.feet.east();
        host.unknownHazardOnly = false;
        host.poseFn = ore -> new DetourHost.Pose(host.feet(), true);
        DetourStartSelector.Result reprobed = DetourStartSelector.select(host);

        assertTrue(reprobed.selection() != null && reprobed.selection().seed().equals(pos),
                "the retained vein member was not re-probed after movement");
    }

    @Test
    void selectTakesAZeroTransitPoseWithoutTouchingReachOrRouteBudget() {
        FakeDetourHost host = new FakeDetourHost();
        host.now = 1000;
        BlockPos pos = new BlockPos(1, 40, 0);
        host.sightings.add(sighting(pos, "diamond_ore", 100));
        host.poseFn = ore -> new DetourHost.Pose(host.feet(), true);
        host.routeStartAllowed = false; // must not matter: zero transit never checks the budget

        DetourStartSelector.Result r = DetourStartSelector.select(host);

        assertEquals(pos, r.selection().seed());
        assertFalse(host.calls.stream().anyMatch(c -> c.startsWith("route:")),
                "a zero-transit pose needs no route, so routeStartAllowed()'s refusal must not matter");
    }

    @Test
    void selectExcludesTheClusterWhenObservedReachSaysUnreachable() {
        FakeDetourHost host = new FakeDetourHost();
        host.now = 1000;
        BlockPos pos = new BlockPos(6, 40, 0);
        host.sightings.add(sighting(pos, "diamond_ore", 100));
        host.reach = ObservedReach.Result.UNREACHABLE;

        DetourStartSelector.select(host);

        Integer until = host.excludedUntil.get(pos);
        assertEquals(host.serverTick + DetourStartSelector.CLUSTER_EXCLUDE_TICKS, until);
    }

    @Test
    void selectEndsTheWholeCheckWithBudgetWhenTheRouteBudgetRefuses() {
        FakeDetourHost host = new FakeDetourHost();
        host.now = 1000;
        BlockPos pos = new BlockPos(7, 40, 0);
        host.sightings.add(sighting(pos, "diamond_ore", 100));
        host.routeStartAllowed = false;

        DetourStartSelector.Result r = DetourStartSelector.select(host);

        assertNull(r.selection());
        assertEquals("budget", r.reason());
        assertFalse(host.excludedUntil.containsKey(pos), "budget refusal excludes nothing");
    }

    @Test
    void selectSkipsForMissingSealMaterialAtOrBelowTheLavaBand() {
        FakeDetourHost host = new FakeDetourHost();
        host.now = 1000;
        host.feet = new BlockPos(0, -50, 0);
        BlockPos pos = new BlockPos(1, -50, 0);
        host.sightings.add(sighting(pos, "diamond_ore", 100));
        host.poseFn = ore -> new DetourHost.Pose(host.feet(), true);
        host.sealMaterialOk = false;

        DetourStartSelector.select(host);

        Integer until = host.excludedUntil.get(pos);
        assertEquals(host.serverTick + DetourStartSelector.SOFT_EXCLUDE_TICKS, until);
    }

    @Test
    void selectSkipsForNoToolAndExcludesTheClusterForTwelveHundredTicks() {
        TunableFakeDetourHost host = new TunableFakeDetourHost();
        host.now = 1000;
        BlockPos pos = new BlockPos(0, 40, 0);
        host.sightings.add(sighting(pos, "diamond_ore", 100));
        host.poseFn = ore -> new DetourHost.Pose(host.feet(), true);
        host.toolByPos.put(pos, DetourHost.ToolVerdict.NO_TOOL);

        DetourStartSelector.select(host);

        Integer until = host.excludedUntil.get(pos);
        assertEquals(host.serverTick + DetourStartSelector.TOOL_EXCLUDE_TICKS, until);
    }

    @Test
    void selectSkipsClaimedAtTheEarlyGateAndTriesTheNextCandidate() {
        FakeDetourHost host = new FakeDetourHost();
        host.now = 1000;
        BlockPos first = new BlockPos(0, 40, 0);
        BlockPos second = new BlockPos(1, 40, 0);
        host.sightings.add(sighting(first, "diamond_ore", 100));
        host.sightings.add(sighting(second, "diamond_ore", 90));
        host.poseFn = ore -> new DetourHost.Pose(host.feet(), true);
        host.claimedByOthers.add(first);

        DetourStartSelector.Result r = DetourStartSelector.select(host);

        // first is claimed-by-another before the re-proof (silent, no re-proof cost); second is taken.
        assertEquals(second, r.selection().seed());
        assertTrue(host.logs.contains("ore_dig_detour_skip"));
        assertTrue(host.myClaims.contains(second));
        assertFalse(host.myClaims.contains(first));
    }

    @Test
    void selectReturnsTheSelectionOnceEveryGatePasses() {
        FakeDetourHost host = new FakeDetourHost();
        host.now = 1000;
        BlockPos pos = new BlockPos(11, 40, 0);
        host.sightings.add(sighting(pos, "diamond_ore", 100));
        host.poseFn = ore -> new DetourHost.Pose(host.feet(), true);

        DetourStartSelector.Result r = DetourStartSelector.select(host);

        assertEquals(pos, r.selection().seed());
        assertEquals("diamond_ore", r.selection().blockId());
        assertTrue(host.myClaims.contains(pos));
    }

    // =================================================================================================
    // A break-peek style sighting (design 3.3's "break peek": a DIFFERENT block revealed as the neighbour
    // of a break folds into SightingLedger like any other hit, design 4.7/4.8) sits in the ledger as an
    // ordinary opportunistic nomination -- OreDigDetourEngineTest's
    // aDifferentBlockSightingRevealedByABreakNeverBecomesAMemberOfTheRunningSameBlockVein covers that it
    // never gets swept into a same-block vein follow. These cover the other half of that lifecycle: a
    // LATER, independent start check re-proving it live from wherever the bot happens to be by then
    // (never trusting the old sighting), and only starting when that live re-proof still says PRESENT.
    // =================================================================================================

    @Test
    void laterStartCheckReProvesABreakRevealedSightingLiveFromWhereverTheBotThenIsAndStartsWhenPresent() {
        FakeDetourHost host = new FakeDetourHost();
        host.now = 1000;
        BlockPos sighted = new BlockPos(5, 40, 5);
        host.sightings.add(sighting(sighted, "gold_ore", 45));
        // The bot has walked well away from the break that revealed this sighting by the time the next
        // start check runs -- the re-proof must be live from here, not from wherever it was seen.
        host.feet = new BlockPos(10, 40, 10); // within admission range (<=14 eye distance) but not where it was sighted
        host.poseFn = ore -> new DetourHost.Pose(host.feet(), true);
        java.util.ArrayDeque<DetourHost.Seen> script = new java.util.ArrayDeque<>();
        script.add(DetourHost.Seen.PRESENT);
        host.seenScript.put(sighted, script);

        DetourStartSelector.Result r = DetourStartSelector.select(host);

        assertEquals(sighted, r.selection().seed());
        assertTrue(script.isEmpty(), "the selector must re-observe the cell live before starting on it");
    }

    @Test
    void laterStartCheckSkipsABreakRevealedSightingAsStaleWhenTheLiveReproofReadsUnknown() {
        FakeDetourHost host = new FakeDetourHost();
        host.now = 1000;
        BlockPos sighted = new BlockPos(5, 40, 5);
        host.sightings.add(sighting(sighted, "gold_ore", 45));
        host.feet = new BlockPos(10, 40, 10); // within admission range (<=14 eye distance) but not where it was sighted
        host.seenScript.computeIfAbsent(sighted, p -> new java.util.ArrayDeque<>()).add(DetourHost.Seen.UNKNOWN);

        DetourStartSelector.Result r = DetourStartSelector.select(host);

        assertNull(r.selection(), "a sighting the bot cannot currently observe must not start a detour");
        assertFalse(host.forgotten.contains(sighted), "UNKNOWN keeps the nomination -- it is not proven gone");
    }

    @Test
    void laterStartCheckSkipsABreakRevealedSightingAsStaleWhenTheLiveReproofFindsItAbsent() {
        FakeDetourHost host = new FakeDetourHost();
        host.now = 1000;
        BlockPos sighted = new BlockPos(5, 40, 5);
        host.sightings.add(sighting(sighted, "gold_ore", 45));
        host.feet = new BlockPos(10, 40, 10); // within admission range (<=14 eye distance) but not where it was sighted
        host.seenScript.computeIfAbsent(sighted, p -> new java.util.ArrayDeque<>()).add(DetourHost.Seen.GONE);

        DetourStartSelector.Result r = DetourStartSelector.select(host);

        assertNull(r.selection());
        assertTrue(host.forgotten.contains(sighted), "a live re-proof that finds it gone forgets the stale sighting");
    }
}
