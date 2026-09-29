package io.github.zoyluo.minecraftai.mining.assist;

import io.github.zoyluo.minecraftai.mining.assist.DetourPolicy.Admission;
import io.github.zoyluo.minecraftai.mining.assist.DetourPolicy.Ranked;
import io.github.zoyluo.minecraftai.mining.assist.DetourPolicy.TargetLock;
import io.github.zoyluo.minecraftai.mining.assist.SightingLedger.Sighting;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins mining-assist design 4.2 (P1 contract section G.1): cost, score, admission, ranking, lease, pose limits. */
class DetourPolicyTest {
    private static final double DELTA = 1e-9;
    private static final MiningAssistConfig.Detour DEFAULT_CFG = MiningAssistConfig.Detour.DEFAULTS;
    /** {@code eye} placed at the feet cell's own block centre, so eye distance equals block-to-block distance. */
    private static final Vec3 ORIGIN_EYE = new Vec3(0.5D, 0.5D, 0.5D);
    private static final BlockPos ORIGIN = pos(0, 0, 0);

    private static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    private static Sighting sighting(BlockPos p, String id) {
        return new Sighting(p, id, 0, 0, 0);
    }

    private static Sighting sighting(BlockPos p, String id, int rawValue) {
        return new Sighting(p, id, rawValue, 0, 0);
    }

    private static Vec3 centre(BlockPos p) {
        return new Vec3(p.getX() + 0.5D, p.getY() + 0.5D, p.getZ() + 0.5D);
    }

    private static MiningAssistConfig.Detour cfg(int minValue, double minScore, int maxRadius, int maxUp, int maxDown,
                                                 int leaseTicks, int announceMinValue) {
        return new MiningAssistConfig.Detour(true, minValue, minScore, maxRadius, maxUp, maxDown, leaseTicks, 200, 24,
                3, 4, 4, announceMinValue);
    }

    private static MiningAssistConfig.Detour cfgMinScore(double minScore) {
        return cfg(25, minScore, 12, 6, 2, 300, 90);
    }

    private static MiningAssistConfig.Detour cfgMinValue(int minValue) {
        return cfg(minValue, 1.2D, 12, 6, 2, 300, 90);
    }

    private static MiningAssistConfig.Detour cfgLeaseTicks(int leaseTicks) {
        return cfg(25, 1.2D, 12, 6, 2, leaseTicks, 90);
    }

    private static MiningAssistConfig.Detour cfgAnnounceMinValue(int announceMinValue) {
        return cfg(25, 1.2D, 12, 6, 2, 300, announceMinValue);
    }

    // ---- horizontalCost / verticalCost / cost ----

    @Test
    void horizontalCostMatchesWorkedExamples() {
        assertEquals(11.0D, DetourPolicy.horizontalCost(11, 0), DELTA);
        assertEquals(5.5D, DetourPolicy.horizontalCost(3, 4), DELTA);
        assertEquals(5.5D, DetourPolicy.horizontalCost(-3, -4), DELTA);
        assertEquals(0.0D, DetourPolicy.horizontalCost(0, 0), DELTA);
    }

    @Test
    void verticalCostMatchesWorkedExamples() {
        assertEquals(0.0D, DetourPolicy.verticalCost(0), DELTA);
        assertEquals(1.6D, DetourPolicy.verticalCost(1), DELTA);
        assertEquals(3.2D, DetourPolicy.verticalCost(2), DELTA);
        assertEquals(3.6D, DetourPolicy.verticalCost(-3), DELTA);
    }

    @Test
    void costCombinesHorizontalAndVertical() {
        assertEquals(11.0D, DetourPolicy.cost(11, 0, 0), DELTA);
    }

    // ---- maxCost ----

    @Test
    void maxCostClampsAndScales() {
        assertEquals(6.0D, DetourPolicy.maxCost(0), DELTA);
        assertEquals(6.0D, DetourPolicy.maxCost(12), DELTA);
        assertEquals(8.0D, DetourPolicy.maxCost(25), DELTA);
        assertEquals(8.8D, DetourPolicy.maxCost(30), DELTA);
        assertEquals(11.2D, DetourPolicy.maxCost(45), DELTA);
        assertEquals(18.4D, DetourPolicy.maxCost(90), DELTA);
        assertEquals(20.0D, DetourPolicy.maxCost(100), DELTA);
        assertEquals(20.0D, DetourPolicy.maxCost(200), DELTA);
        assertEquals(6.0D, DetourPolicy.maxCost(-5), DELTA);
    }

    // ---- score, cluster multiplier ----

    @Test
    void scoreClusterMultiplierSaturatesAtSixSteps() {
        // Contract G.1 pins "score(30, 7, 14) = 30*1.9/14"; the skeleton Javadoc's formula is
        // value * multiplier / (6 + cost) (matching design 4.2), so the raw cost argument that reaches
        // denominator 14 is 8, not 14 (see the P1 report: this is a contract-example/skeleton mismatch,
        // skeleton wins per assist_ctx.md). Cost 8 reproduces the pinned numeric answer exactly.
        assertEquals(30.0D * 1.9D / 14.0D, DetourPolicy.score(30, 7, 8), DELTA);
        assertEquals(DetourPolicy.score(30, 7, 8), DetourPolicy.score(30, 20, 8), DELTA);
    }

    @Test
    void scoreClusterSizeBelowOneCountsAsOne() {
        assertEquals(DetourPolicy.score(30, 1, 8), DetourPolicy.score(30, 0, 8), DELTA);
        assertEquals(DetourPolicy.score(30, 1, 8), DetourPolicy.score(30, -5, 8), DELTA);
    }

    // ---- admit: worked examples ----

    @Test
    void diamondAtElevenBlocksClusterOneAdmits() {
        BlockPos ore = pos(11, 0, 0);
        Admission a = DetourPolicy.admit(100, 1, ore, ORIGIN, centre(ore), ORIGIN, TargetLock.NONE, DEFAULT_CFG);
        assertEquals(Admission.ADMIT, a);
        double cost = DetourPolicy.cost(11, 0, 0);
        assertEquals(100.0D / 17.0D, DetourPolicy.score(100, 1, cost), DELTA);
    }

    @Test
    void ironAtEightAdmitsUnlockedAndFailsLockedScore() {
        BlockPos ore = pos(8, 0, 0);
        Vec3 eye = centre(ore);
        Admission unlocked = DetourPolicy.admit(30, 1, ore, ORIGIN, eye, ORIGIN, TargetLock.NONE, DEFAULT_CFG);
        assertEquals(Admission.ADMIT, unlocked);
        assertEquals(30.0D / 14.0D, DetourPolicy.score(30, 1, DetourPolicy.cost(8, 0, 0)), DELTA);
        Admission locked = DetourPolicy.admit(30, 1, ore, ORIGIN, eye, ORIGIN, TargetLock.LOCKED, DEFAULT_CFG);
        assertEquals(Admission.LOCKED_SCORE, locked);
    }

    @Test
    void ironAtBoundaryLockedScoreIsInclusive() {
        BlockPos ore = pos(5, 0, 3);
        double cost = DetourPolicy.cost(5, 0, 3);
        assertEquals(2.4D, DetourPolicy.score(30, 1, cost), DELTA);
        Admission a = DetourPolicy.admit(30, 1, ore, ORIGIN, centre(ore), ORIGIN, TargetLock.LOCKED, DEFAULT_CFG);
        assertEquals(Admission.ADMIT, a);
    }

    @Test
    void ironAtTenExceedsMaxCost() {
        BlockPos ore = pos(10, 0, 0);
        Admission a = DetourPolicy.admit(30, 1, ore, ORIGIN, centre(ore), ORIGIN, TargetLock.NONE, DEFAULT_CFG);
        assertEquals(Admission.COST, a);
    }

    @Test
    void lowValueBlocksFailRegardlessOfCluster() {
        BlockPos ore = pos(1, 0, 0);
        assertEquals(Admission.VALUE, DetourPolicy.admit(12, 1, ore, ORIGIN, centre(ore), ORIGIN, TargetLock.NONE, DEFAULT_CFG));
        assertEquals(Admission.VALUE, DetourPolicy.admit(12, 4, ore, ORIGIN, centre(ore), ORIGIN, TargetLock.NONE, DEFAULT_CFG));
        assertEquals(Admission.VALUE, DetourPolicy.admit(15, 7, ore, ORIGIN, centre(ore), ORIGIN, TargetLock.NONE, DEFAULT_CFG));
    }

    @Test
    void minValueBoundaryIsInclusive() {
        BlockPos ore = pos(1, 0, 0);
        assertEquals(Admission.VALUE, DetourPolicy.admit(24, 1, ore, ORIGIN, centre(ore), ORIGIN, TargetLock.NONE, DEFAULT_CFG));
        assertEquals(Admission.ADMIT, DetourPolicy.admit(25, 1, ore, ORIGIN, centre(ore), ORIGIN, TargetLock.NONE, DEFAULT_CFG));
    }

    @Test
    void targetNearBeatsEverythingButValue() {
        BlockPos ore = pos(50, 0, 0);
        assertEquals(Admission.TARGET_NEAR,
                DetourPolicy.admit(100, 1, ore, ORIGIN, centre(ore), ORIGIN, TargetLock.NEAR, DEFAULT_CFG));
        assertEquals(Admission.VALUE,
                DetourPolicy.admit(12, 1, ore, ORIGIN, centre(ore), ORIGIN, TargetLock.NEAR, DEFAULT_CFG));
    }

    @Test
    void rangeEyeDistanceBoundary() {
        BlockPos ore = pos(5, 0, 5);
        Vec3 centre = centre(ore);
        Vec3 eyeAtFourteen = new Vec3(centre.x(), centre.y(), centre.z() - 14.0D);
        Vec3 eyeAtFourteenOhOne = new Vec3(centre.x(), centre.y(), centre.z() - 14.01D);
        assertEquals(Admission.ADMIT,
                DetourPolicy.admit(100, 1, ore, ORIGIN, eyeAtFourteen, ORIGIN, TargetLock.NONE, DEFAULT_CFG));
        assertEquals(Admission.RANGE,
                DetourPolicy.admit(100, 1, ore, ORIGIN, eyeAtFourteenOhOne, ORIGIN, TargetLock.NONE, DEFAULT_CFG));
    }

    @Test
    void rangeChebyshevHorizontalBoundary() {
        BlockPos ok = pos(12, 0, 0);
        BlockPos fail = pos(13, 0, 0);
        assertEquals(Admission.ADMIT,
                DetourPolicy.admit(100, 1, ok, ORIGIN, centre(ok), ORIGIN, TargetLock.NONE, DEFAULT_CFG));
        assertEquals(Admission.RANGE,
                DetourPolicy.admit(100, 1, fail, ORIGIN, centre(fail), ORIGIN, TargetLock.NONE, DEFAULT_CFG));
    }

    @Test
    void rangeMaxUpBoundary() {
        BlockPos ok = pos(0, 6, 0);
        BlockPos fail = pos(0, 7, 0);
        assertEquals(Admission.ADMIT,
                DetourPolicy.admit(100, 1, ok, ORIGIN, centre(ok), ORIGIN, TargetLock.NONE, DEFAULT_CFG));
        assertEquals(Admission.RANGE,
                DetourPolicy.admit(100, 1, fail, ORIGIN, centre(fail), ORIGIN, TargetLock.NONE, DEFAULT_CFG));
    }

    @Test
    void rangeMaxDownBoundary() {
        BlockPos ok = pos(0, -2, 0);
        BlockPos fail = pos(0, -3, 0);
        assertEquals(Admission.ADMIT,
                DetourPolicy.admit(100, 1, ok, ORIGIN, centre(ok), ORIGIN, TargetLock.NONE, DEFAULT_CFG));
        assertEquals(Admission.RANGE,
                DetourPolicy.admit(100, 1, fail, ORIGIN, centre(fail), ORIGIN, TargetLock.NONE, DEFAULT_CFG));
    }

    @Test
    void scoreFailsWithCustomMinScore() {
        BlockPos ore = pos(8, 0, 0);
        MiningAssistConfig.Detour custom = cfgMinScore(2.0D);
        double cost = DetourPolicy.cost(8, 0, 0);
        assertEquals(25.0D / 14.0D, DetourPolicy.score(25, 1, cost), DELTA);
        Admission a = DetourPolicy.admit(25, 1, ore, ORIGIN, centre(ore), ORIGIN, TargetLock.NONE, custom);
        assertEquals(Admission.SCORE, a);
    }

    @Test
    void minValueIsHonouredFromConfig() {
        BlockPos ore = pos(1, 0, 0);
        MiningAssistConfig.Detour custom = cfgMinValue(50);
        assertEquals(Admission.VALUE, DetourPolicy.admit(40, 1, ore, ORIGIN, centre(ore), ORIGIN, TargetLock.NONE, custom));
        assertEquals(Admission.ADMIT, DetourPolicy.admit(50, 1, ore, ORIGIN, centre(ore), ORIGIN, TargetLock.NONE, custom));
    }

    // ---- cluster ----

    @Test
    void clusterListsCandidateFirstSameIdOnlyWithinChebyshevThree() {
        Sighting candidate = sighting(pos(0, 0, 0), "diamond_ore");
        Sighting near = sighting(pos(3, 0, 0), "diamond_ore");
        Sighting tooFar = sighting(pos(4, 0, 0), "diamond_ore");
        Sighting otherId = sighting(pos(1, 0, 0), "iron_ore");
        List<Sighting> all = List.of(candidate, near, tooFar, otherId);
        List<BlockPos> cluster = DetourPolicy.cluster(candidate, all);
        assertEquals(2, cluster.size());
        assertEquals(pos(0, 0, 0), cluster.get(0));
        assertTrue(cluster.contains(pos(3, 0, 0)));
        assertFalse(cluster.contains(pos(4, 0, 0)));
        assertFalse(cluster.contains(pos(1, 0, 0)));
    }

    // ---- rank ----

    @Test
    void rankReturnsOnlyAdmittedCandidates() {
        Sighting coal = sighting(pos(1, 0, 0), "coal_ore");
        Sighting diamond = sighting(pos(11, 0, 0), "diamond_ore");
        List<Sighting> all = List.of(coal, diamond);
        List<Ranked> ranked = DetourPolicy.rank(all, ORIGIN, ORIGIN_EYE, ORIGIN, TargetLock.NONE, DEFAULT_CFG);
        assertEquals(1, ranked.size());
        assertEquals("diamond_ore", ranked.get(0).sighting().blockId());
    }

    @Test
    void rankOrdersByScoreThenValueThenDistanceThenPosition() {
        Sighting diamond = sighting(pos(11, 0, 0), "diamond_ore");
        Sighting iron = sighting(pos(2, 0, 0), "iron_ore");
        List<Sighting> all = List.of(iron, diamond);
        List<Ranked> ranked = DetourPolicy.rank(all, ORIGIN, ORIGIN_EYE, ORIGIN, TargetLock.NONE, DEFAULT_CFG);
        assertEquals(2, ranked.size());
        // iron at 2 blocks: cost 2, score 30/8 = 3.75; diamond at 11: score 100/17 = 5.882 -> diamond first.
        assertEquals("diamond_ore", ranked.get(0).sighting().blockId());
        assertEquals("iron_ore", ranked.get(1).sighting().blockId());
    }

    @Test
    void rankBreaksFullTiesByBlockPosOrder() {
        // Two identical-value, identical-distance, identical-cluster candidates at different positions:
        // (0,0,3) and (0,0,-3) are both distance 3 from the origin along one axis with matching cost/score.
        Sighting a = sighting(pos(0, 0, 3), "diamond_ore");
        Sighting b = sighting(pos(0, 0, -3), "diamond_ore");
        List<Sighting> all = List.of(a, b);
        List<Ranked> ranked = DetourPolicy.rank(all, ORIGIN, ORIGIN_EYE, ORIGIN, TargetLock.NONE, DEFAULT_CFG);
        assertEquals(2, ranked.size());
        // Both are in each other's cluster (chebyshev distance 6? no: |3 - (-3)| = 6 > CLUSTER_RADIUS 3, so
        // each is its own singleton cluster); score/value/distanceSq are equal, so BlockPos order (y, z, x)
        // decides: z = -3 sorts before z = 3.
        assertEquals(pos(0, 0, -3), ranked.get(0).sighting().pos());
        assertEquals(pos(0, 0, 3), ranked.get(1).sighting().pos());
    }

    @Test
    void rankIsDeterministicForEqualAndShuffledInput() {
        List<Sighting> all = new ArrayList<>(List.of(
                sighting(pos(11, 0, 0), "diamond_ore"),
                sighting(pos(2, 0, 0), "iron_ore"),
                sighting(pos(3, 0, 2), "gold_ore"),
                sighting(pos(1, 0, 5), "lapis_ore")));
        List<Ranked> first = DetourPolicy.rank(all, ORIGIN, ORIGIN_EYE, ORIGIN, TargetLock.NONE, DEFAULT_CFG);
        List<Ranked> second = DetourPolicy.rank(all, ORIGIN, ORIGIN_EYE, ORIGIN, TargetLock.NONE, DEFAULT_CFG);
        assertEquals(first, second);
        List<Sighting> shuffled = new ArrayList<>(all);
        Collections.shuffle(shuffled, new Random(42));
        List<Ranked> third = DetourPolicy.rank(shuffled, ORIGIN, ORIGIN_EYE, ORIGIN, TargetLock.NONE, DEFAULT_CFG);
        assertEquals(first, third);
    }

    @Test
    void rankRecomputesValueFromTheIdIgnoringStoredRawValue() {
        Sighting deepslateDiamond = sighting(pos(2, 0, 0), "deepslate_diamond_ore", 0);
        Sighting moddedOre = sighting(pos(3, 0, 0), "modid:zinc_ore", 0);
        List<Sighting> all = List.of(deepslateDiamond, moddedOre);
        List<Ranked> ranked = DetourPolicy.rank(all, ORIGIN, ORIGIN_EYE, ORIGIN, TargetLock.NONE, DEFAULT_CFG);
        assertEquals(2, ranked.size());
        Ranked diamondRanked = ranked.stream().filter(r -> r.sighting().pos().equals(pos(2, 0, 0))).findFirst().orElseThrow();
        Ranked zincRanked = ranked.stream().filter(r -> r.sighting().pos().equals(pos(3, 0, 0))).findFirst().orElseThrow();
        assertEquals(100, diamondRanked.value());
        assertEquals(25, zincRanked.value());
    }

    @Test
    void rankDistanceSqIsFeetToCell() {
        Sighting s = sighting(pos(3, 4, 0), "diamond_ore");
        List<Ranked> ranked = DetourPolicy.rank(List.of(s), ORIGIN, ORIGIN_EYE, ORIGIN, TargetLock.NONE, DEFAULT_CFG);
        assertEquals(1, ranked.size());
        assertEquals(25L, ranked.get(0).distanceSq());
    }

    @Test
    void rankOnEmptyListGivesEmpty() {
        assertTrue(DetourPolicy.rank(List.of(), ORIGIN, ORIGIN_EYE, ORIGIN, TargetLock.NONE, DEFAULT_CFG).isEmpty());
    }

    @Test
    void rankDropsNeverDetourAndUnnaturalContextCandidates() {
        // gilded_blackstone: never detoured, even though its value (30) and position would otherwise admit.
        Sighting blackstone = sighting(pos(2, 0, 0), "gilded_blackstone");
        // raw_iron_block with no _ore neighbour: fails naturalContext.
        Sighting lonelyRaw = sighting(pos(3, 0, 0), "raw_iron_block");
        List<Sighting> all = List.of(blackstone, lonelyRaw);
        assertTrue(DetourPolicy.rank(all, ORIGIN, ORIGIN_EYE, ORIGIN, TargetLock.NONE, DEFAULT_CFG).isEmpty());

        // Add an iron_ore neighbour within Chebyshev 4 of the raw block: naturalContext now passes.
        Sighting neighbourOre = sighting(pos(3, 0, 4), "iron_ore");
        List<Sighting> withContext = List.of(blackstone, lonelyRaw, neighbourOre);
        List<Ranked> ranked = DetourPolicy.rank(withContext, ORIGIN, ORIGIN_EYE, ORIGIN, TargetLock.NONE, DEFAULT_CFG);
        assertTrue(ranked.stream().anyMatch(r -> r.sighting().pos().equals(pos(3, 0, 0))));
        assertFalse(ranked.stream().anyMatch(r -> r.sighting().pos().equals(pos(2, 0, 0))));
    }

    // ---- leaseTicks ----

    @Test
    void leaseTicksScalesWithBreaksAndCaps() {
        assertEquals(300, DetourPolicy.leaseTicks(DEFAULT_CFG, 0));
        assertEquals(360, DetourPolicy.leaseTicks(DEFAULT_CFG, 1));
        assertEquals(540, DetourPolicy.leaseTicks(DEFAULT_CFG, 4));
        assertEquals(600, DetourPolicy.leaseTicks(DEFAULT_CFG, 5));
        assertEquals(600, DetourPolicy.leaseTicks(DEFAULT_CFG, 50));
        assertEquals(300, DetourPolicy.leaseTicks(DEFAULT_CFG, -3));
    }

    @Test
    void leaseTicksHonoursConfiguredBaseAndCaps() {
        MiningAssistConfig.Detour custom = cfgLeaseTicks(60);
        assertEquals(60, DetourPolicy.leaseTicks(custom, 0));
        assertEquals(120, DetourPolicy.leaseTicks(custom, 1));
        assertEquals(600, DetourPolicy.leaseTicks(custom, 50));
    }

    // ---- standWithinLimits ----

    @Test
    void standWithinLimitsRespectsAnchorFloorAndMinStandY() {
        BlockPos anchorHigh = pos(0, 40, 0);
        assertTrue(DetourPolicy.standWithinLimits(pos(0, 37, 0), anchorHigh, -59, DEFAULT_CFG));
        assertFalse(DetourPolicy.standWithinLimits(pos(0, 36, 0), anchorHigh, -59, DEFAULT_CFG));

        BlockPos anchorLow = pos(0, -58, 0);
        assertTrue(DetourPolicy.standWithinLimits(pos(0, -59, 0), anchorLow, -59, DEFAULT_CFG));
        assertFalse(DetourPolicy.standWithinLimits(pos(0, -60, 0), anchorLow, -59, DEFAULT_CFG));
    }

    @Test
    void standWithinLimitsRespectsHorizontalRadius() {
        assertTrue(DetourPolicy.standWithinLimits(pos(12, 0, 0), ORIGIN, -59, DEFAULT_CFG));
        assertFalse(DetourPolicy.standWithinLimits(pos(13, 0, 0), ORIGIN, -59, DEFAULT_CFG));

        MiningAssistConfig.Detour narrow = cfg(25, 1.2D, 6, 6, 2, 300, 90);
        assertTrue(DetourPolicy.standWithinLimits(pos(6, 0, 0), ORIGIN, -59, narrow));
        assertFalse(DetourPolicy.standWithinLimits(pos(7, 0, 0), ORIGIN, -59, narrow));
    }

    // ---- inAnchorFloorPatch ----

    @Test
    void inAnchorFloorPatchCoversTheNineFloorCells() {
        BlockPos anchor = ORIGIN;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                assertTrue(DetourPolicy.inAnchorFloorPatch(pos(dx, -1, dz), anchor),
                        "dx=" + dx + " dz=" + dz);
            }
        }
        assertFalse(DetourPolicy.inAnchorFloorPatch(pos(0, 0, 0), anchor));
        assertFalse(DetourPolicy.inAnchorFloorPatch(pos(2, -1, 0), anchor));
        assertFalse(DetourPolicy.inAnchorFloorPatch(pos(0, -2, 0), anchor));
    }

    // ---- pathLengthOk ----

    @Test
    void pathLengthOkBoundary() {
        assertTrue(DetourPolicy.pathLengthOk(24, DEFAULT_CFG));
        assertFalse(DetourPolicy.pathLengthOk(25, DEFAULT_CFG));
    }

    // ---- announces ----

    @Test
    void announcesBoundary() {
        assertTrue(DetourPolicy.announces(90, DEFAULT_CFG));
        assertFalse(DetourPolicy.announces(89, DEFAULT_CFG));
        MiningAssistConfig.Detour custom = cfgAnnounceMinValue(100);
        assertTrue(DetourPolicy.announces(100, custom));
        assertFalse(DetourPolicy.announces(99, custom));
    }

    // ---- announceText ----

    @Test
    void announceTextFormatsKnownIds() {
        assertEquals("Spotted diamond ore nearby, grabbing it.", DetourPolicy.announceText("diamond_ore"));
        assertEquals("Spotted diamond ore nearby, grabbing it.", DetourPolicy.announceText("deepslate_diamond_ore"));
        assertEquals("Spotted ancient debris nearby, grabbing it.", DetourPolicy.announceText("minecraft:ancient_debris"));
        assertEquals("Spotted zinc ore nearby, grabbing it.", DetourPolicy.announceText("modid:Zinc_Ore"));
    }

    @Test
    void announceTextFallsBackForNullOrBlank() {
        assertEquals("Spotted a valuable nearby, grabbing it.", DetourPolicy.announceText(null));
        assertEquals("Spotted a valuable nearby, grabbing it.", DetourPolicy.announceText("   "));
    }

    @Test
    void announceTextTruncatesLongIdsToEightyCharacters() {
        String longId = "a".repeat(200);
        String text = DetourPolicy.announceText(longId);
        assertTrue(text.length() <= 80, "length=" + text.length());
        assertTrue(text.endsWith("grabbing it."), text);
    }

    // ---- lavaBandTopY ----

    @Test
    void lavaBandTopYPerDimension() {
        assertEquals(-50, DetourPolicy.lavaBandTopY("minecraft:overworld"));
        assertEquals(-50, DetourPolicy.lavaBandTopY(null));
        assertEquals(32, DetourPolicy.lavaBandTopY("minecraft:the_nether"));
        assertEquals(Integer.MIN_VALUE, DetourPolicy.lavaBandTopY("minecraft:the_end"));
        assertEquals(-50, DetourPolicy.lavaBandTopY("modid:somewhere"));
    }

    // ---- neverDetour ----

    @Test
    void neverDetourOnlyGildedBlackstone() {
        assertTrue(DetourPolicy.neverDetour("gilded_blackstone"));
        assertTrue(DetourPolicy.neverDetour("minecraft:gilded_blackstone"));
        assertFalse(DetourPolicy.neverDetour("raw_iron_block"));
        assertFalse(DetourPolicy.neverDetour("diamond_ore"));
        assertFalse(DetourPolicy.neverDetour("ancient_debris"));
    }

    // ---- naturalContext ----

    @Test
    void naturalContextRequiresAnOreNeighbourForRawBlocks() {
        Sighting lonelyRaw = sighting(pos(0, 0, 0), "raw_iron_block");
        assertFalse(DetourPolicy.naturalContext(lonelyRaw, List.of(lonelyRaw)));

        Sighting oreAtFour = sighting(pos(4, 0, 0), "iron_ore");
        assertTrue(DetourPolicy.naturalContext(lonelyRaw, List.of(lonelyRaw, oreAtFour)));

        Sighting oreAtFive = sighting(pos(5, 0, 0), "iron_ore");
        assertFalse(DetourPolicy.naturalContext(lonelyRaw, List.of(lonelyRaw, oreAtFive)));
    }

    @Test
    void naturalContextDeepslateNeighbourCounts() {
        Sighting lonelyRaw = sighting(pos(0, 0, 0), "raw_gold_block");
        Sighting deepslateOre = sighting(pos(3, 0, 0), "deepslate_diamond_ore");
        assertTrue(DetourPolicy.naturalContext(lonelyRaw, List.of(lonelyRaw, deepslateOre)));
    }

    @Test
    void naturalContextOresAndAncientDebrisNeedNoContext() {
        Sighting ore = sighting(pos(0, 0, 0), "diamond_ore");
        assertTrue(DetourPolicy.naturalContext(ore, List.of(ore)));
        Sighting debris = sighting(pos(0, 0, 0), "ancient_debris");
        assertTrue(DetourPolicy.naturalContext(debris, List.of(debris)));
    }

    @Test
    void naturalContextNeverDetourIdFailsEvenWithOreNeighbour() {
        Sighting blackstone = sighting(pos(0, 0, 0), "gilded_blackstone");
        Sighting oreNextDoor = sighting(pos(1, 0, 0), "iron_ore");
        assertFalse(DetourPolicy.naturalContext(blackstone, List.of(blackstone, oreNextDoor)));
    }
}
