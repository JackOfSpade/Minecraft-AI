package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.structure.IntBox;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure decisions of nearest-first population: distance, the walk over the sorted structures, the vacancy arithmetic. */
class AllocationTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    private static StructureKey key(String id, int x, int z) {
        return new StructureKey(OVERWORLD, id, x, z);
    }

    private static Allocation.Candidate cand(StructureKey k, double distance, int target, int prot, boolean incumbent) {
        return new Allocation.Candidate(k, distance, target, prot, incumbent);
    }

    // ------------------------------------------------------------------ distance

    @Test
    void distanceIsThe3DPointToBoxDistanceAndZeroInside() {
        IntBox box = new IntBox(0, 60, 0, 9, 69, 9);
        List<BotGateway.PlayerPos> inside = List.of(new BotGateway.PlayerPos(OVERWORLD, 5, 65, 5));
        assertEquals(0.0, Allocation.distance(box, OVERWORLD, inside));
        List<BotGateway.PlayerPos> east = List.of(new BotGateway.PlayerPos(OVERWORLD, 20, 65, 5));
        assertEquals(10.0, Allocation.distance(box, OVERWORLD, east), 1e-9); // the box reaches to max + 1 = 10
        List<BotGateway.PlayerPos> above = List.of(new BotGateway.PlayerPos(OVERWORLD, 5, 90, 5));
        assertEquals(20.0, Allocation.distance(box, OVERWORLD, above), 1e-9);
        List<BotGateway.PlayerPos> diagonal = List.of(new BotGateway.PlayerPos(OVERWORLD, 13, 80, 14));
        assertEquals(Math.sqrt(9 + 100 + 16), Allocation.distance(box, OVERWORLD, diagonal), 1e-9);
    }

    @Test
    void theNearestOfSeveralPlayersCountsAndAnotherDimensionNever() {
        IntBox box = new IntBox(0, 60, 0, 9, 69, 9);
        List<BotGateway.PlayerPos> players = List.of(
                new BotGateway.PlayerPos(OVERWORLD, 100, 65, 0),
                new BotGateway.PlayerPos(OVERWORLD, 30, 65, 0),
                new BotGateway.PlayerPos(NETHER, 5, 65, 5));
        assertEquals(20.0, Allocation.distance(box, OVERWORLD, players), 1e-9);
        assertEquals(0.0, Allocation.distance(box, NETHER, players), 1e-9);
        assertTrue(Double.isInfinite(Allocation.distance(box, "minecraft:the_end", players)), "nobody in that dimension");
        assertTrue(Double.isInfinite(Allocation.distance(box, OVERWORLD, List.of())));
    }

    @Test
    void aStructureNinetyBlocksBelowIsFartherThanAVillageSixtyBlocksAwayOnTheSurface() {
        // The user's case: trial chambers under the route ate the whole budget while the surface villages got nothing.
        IntBox trialChamber = new IntBox(-20, -60, -20, 20, -20, 20);       // its top is 89 blocks under the player
        IntBox village = new IntBox(60, 60, -20, 100, 90, 20);               // 60 blocks east, same height
        List<BotGateway.PlayerPos> player = List.of(new BotGateway.PlayerPos(OVERWORLD, 0, 70, 0));
        double below = Allocation.distance(trialChamber, OVERWORLD, player);
        double surface = Allocation.distance(village, OVERWORLD, player);
        assertEquals(89.0, below, 1e-9);
        assertEquals(60.0, surface, 1e-9);

        StructureKey trial = key("minecraft:trial_chambers", 0, 0);
        StructureKey vill = key("minecraft:village_plains", 4, 0);
        Allocation.Result r = Allocation.allocate(List.of(cand(trial, below, 6, 0, false), cand(vill, surface, 6, 0, false)),
                8, 0, 8.0);
        assertEquals(List.of(vill, trial), r.order(), "the village is nearer in 3D");
        assertEquals(6, r.of(vill), "the village gets its full fill target first");
        assertEquals(2, r.of(trial), "and only what is left goes to the chamber");
    }

    @Test
    void horizontalDistanceIgnoresHeight() {
        IntBox box = new IntBox(0, 0, 0, 9, 9, 9);
        List<BotGateway.PlayerPos> p = List.of(new BotGateway.PlayerPos(OVERWORLD, 20, 200, 5));
        assertEquals(10.0, Allocation.horizontalDistance(box, OVERWORLD, p), 1e-9);
    }

    // ------------------------------------------------------------------ the walk

    @Test
    void theNearestGetsItsFullTargetThenTheRestGoesToTheNextClosest() {
        StructureKey a = key("a", 0, 0);
        StructureKey b = key("b", 5, 0);
        StructureKey c = key("c", 9, 0);
        Allocation.Result r = Allocation.allocate(List.of(
                cand(c, 90, 5, 0, false), cand(a, 10, 4, 0, false), cand(b, 50, 5, 0, false)), 10, 0, 8.0);
        assertEquals(List.of(a, b, c), r.order());
        assertEquals(4, r.of(a));
        assertEquals(5, r.of(b));
        assertEquals(1, r.of(c), "the farthest gets what is left of the budget");
        assertEquals(0, r.of(key("unknown", 1, 1)));
    }

    @Test
    void anUnlimitedBudgetGivesEveryStructureItsFullTarget() {
        StructureKey a = key("a", 0, 0);
        StructureKey b = key("b", 5, 0);
        Allocation.Result r = Allocation.allocate(List.of(cand(a, 10, 7, 0, false), cand(b, 500, 9, 0, false)), 0, 0, 8.0);
        assertEquals(7, r.of(a));
        assertEquals(9, r.of(b));
    }

    @Test
    void protectedBotsUseTheBudgetFirstAndCountTowardTheirOwnStructure() {
        StructureKey near = key("near", 0, 0);
        StructureKey far = key("far", 8, 0);
        // 6 slots; 2 protected bots of the FAR structure (seen / engaged) are already there.
        Allocation.Result r = Allocation.allocate(List.of(cand(near, 10, 5, 0, false), cand(far, 200, 4, 2, false)), 6, 2, 8.0);
        assertEquals(4, r.of(near), "the near one gets what the budget leaves after the protected bots");
        assertEquals(2, r.of(far), "the far one keeps its protected bots and gets nothing more");
    }

    @Test
    void protectedBotsBeyondTheBudgetAreKeptAndStarveEveryoneElse() {
        StructureKey near = key("near", 0, 0);
        StructureKey far = key("far", 8, 0);
        Allocation.Result r = Allocation.allocate(List.of(cand(near, 10, 5, 0, false), cand(far, 200, 4, 3, false)), 2, 3, 8.0);
        assertEquals(0, r.of(near));
        assertEquals(3, r.of(far), "a protected bot is never allocated away");
    }

    @Test
    void hysteresisKeepsAnIncumbentAgainstANearlyEquidistantRival() {
        StructureKey incumbent = key("inc", 0, 0);
        StructureKey rival = key("riv", 5, 0);
        // The rival is 5 blocks nearer: not enough (margin 8), so the incumbent keeps first place.
        Allocation.Result kept = Allocation.allocate(List.of(cand(incumbent, 100, 4, 0, true), cand(rival, 95, 4, 0, false)), 4, 0, 8.0);
        assertEquals(4, kept.of(incumbent));
        assertEquals(0, kept.of(rival));
        // 10 blocks nearer beats it.
        Allocation.Result swapped = Allocation.allocate(List.of(cand(incumbent, 100, 4, 0, true), cand(rival, 90, 4, 0, false)), 4, 0, 8.0);
        assertEquals(0, swapped.of(incumbent));
        assertEquals(4, swapped.of(rival));
    }

    @Test
    void theOrderIsDeterministicForEqualDistances() {
        StructureKey a = key("a", 0, 0);
        StructureKey b = key("b", 1, 0);
        Allocation.Result one = Allocation.allocate(List.of(cand(a, 50, 3, 0, false), cand(b, 50, 3, 0, false)), 3, 0, 8.0);
        Allocation.Result two = Allocation.allocate(List.of(cand(b, 50, 3, 0, false), cand(a, 50, 3, 0, false)), 3, 0, 8.0);
        assertEquals(one.order(), two.order());
        assertEquals(one.desired(), two.desired());
    }

    @Test
    void unreachableStructuresAreNeverCandidates() {
        StructureKey a = key("a", 0, 0);
        Allocation.Result r = Allocation.allocate(List.of(cand(a, Double.POSITIVE_INFINITY, 3, 0, false)), 10, 0, 8.0);
        assertTrue(r.order().isEmpty());
        assertEquals(0, r.of(a));
    }

    // ------------------------------------------------------------------ vacancy: a death never frees a slot

    @Test
    void vacantIsPlannedMinusDeadMinusFailedMinusOccupied() {
        assertEquals(3, Allocation.vacant(5, 0, 0, 2));
        assertEquals(1, Allocation.vacant(5, 2, 0, 2), "two dead: their slots are spent, not free");
        assertEquals(0, Allocation.vacant(5, 2, 1, 2));
        assertEquals(0, Allocation.vacant(3, 3, 0, 0), "a structure whose bots all died has nothing left to fill");
        assertEquals(0, Allocation.vacant(3, 0, 0, 5), "never negative");
    }

    @Test
    void afterTheUnseenAreDeletedVacantIsPlannedMinusDeadMinusSeenAlive() {
        assertEquals(3, Allocation.vacantAfterUnseenDeleted(6, 1, 2));
        assertEquals(4, Allocation.vacantAfterUnseenDeleted(6, 0, 2));
        assertEquals(0, Allocation.vacantAfterUnseenDeleted(3, 2, 1));
    }

    @Test
    void aDeathNeverMakesASlotVacant() {
        int planned = 4;
        int seenAlive = 1;
        int deadBefore = 0;
        int vacantBefore = Allocation.vacantAfterUnseenDeleted(planned, deadBefore, seenAlive);
        int vacantAfterOneDeath = Allocation.vacantAfterUnseenDeleted(planned, deadBefore + 1, seenAlive);
        assertEquals(vacantBefore - 1, vacantAfterOneDeath, "each death removes a slot for good");
        // Over any sequence of deaths the structure can never yield more than N bots in total.
        int alive = planned;
        int dead = 0;
        for (int i = 0; i < 20; i++) {
            if (alive > 0) {
                alive--;
                dead++;
            }
            assertEquals(0, Allocation.vacant(planned, dead, 0, alive), "nothing is ever refilled after a death");
        }
    }

    // ------------------------------------------------------------------ the spatial index

    @Test
    void theIndexOnlyReturnsStructuresNearTheQueryAndEachOnce() {
        StructureIndex index = new StructureIndex();
        List<StructureKey> keys = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            StructureKey k = key("s" + i, i * 20, 0);
            keys.add(k);
            index.put(k, new IntBox(i * 320, 60, 0, i * 320 + 40, 80, 40));
        }
        // a big one spanning many cells is still returned once
        StructureKey big = key("big", 0, 0);
        index.put(big, new IntBox(0, 0, 0, 2000, 100, 300));
        List<StructureKey> found = new ArrayList<>();
        index.forEachNear(OVERWORLD, 640, 20, 200, e -> found.add(e.key));
        assertTrue(found.contains(keys.get(2)));
        assertTrue(found.contains(big));
        assertEquals(1, found.stream().filter(big::equals).count(), "de-duplicated across cells");
        assertFalse(found.contains(keys.get(40)), "far structures are not visited");
        assertTrue(found.size() < 10);
        assertTrue(index.remove(big));
        assertFalse(index.remove(big));
        List<StructureKey> after = new ArrayList<>();
        index.forEachNear(OVERWORLD, 640, 20, 200, e -> after.add(e.key));
        assertFalse(after.contains(big));
        List<StructureKey> other = new ArrayList<>();
        index.forEachNear(NETHER, 640, 20, 200, e -> other.add(e.key));
        assertTrue(other.isEmpty(), "another dimension has no structures");
    }

    @Test
    void theIndexHandlesNegativeCoordinates() {
        StructureIndex index = new StructureIndex();
        StructureKey k = key("neg", -30, -30);
        index.put(k, new IntBox(-500, 60, -500, -460, 80, -460));
        List<StructureKey> found = new ArrayList<>();
        index.forEachNear(OVERWORLD, -480, -480, 16, e -> found.add(e.key));
        assertEquals(List.of(k), found);
        assertEquals(1, index.size());
    }
}
