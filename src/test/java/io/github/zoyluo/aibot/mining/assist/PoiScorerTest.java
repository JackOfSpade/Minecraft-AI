package io.github.zoyluo.aibot.mining.assist;

import io.github.zoyluo.aibot.mining.assist.PoiScorer.Band;
import io.github.zoyluo.aibot.mining.assist.PoiScorer.Hysteresis;
import io.github.zoyluo.aibot.mining.assist.PoiScorer.PoiScore;
import io.github.zoyluo.aibot.mining.assist.PoiSignals.Habitation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the design 6.3 scorer: every calibration fixture (with the cell counts used), the bucket
 * rules, the cluster bonus, hysteresis, the mandatory rule and the cavern channel.
 */
class PoiScorerTest {
    /** Tolerance the design table is pinned to. */
    private static final double TOL = 0.03D;
    private static final double EXACT = 1.0e-9D;

    // ------------------------------------------------------------------ helpers

    /**
     * Hands out distinct cells on a step-spaced 6 x 3 lattice per layer. Step 1 packs every cell
     * within about 5.4 blocks of each other (one tight cluster); step 7 keeps every pair farther than
     * the 6-ball apart, so the cluster bonus cannot apply.
     */
    private static final class Grid {
        private final int step;
        private int next;

        Grid(int step) {
            this.step = step;
        }

        BlockPos take() {
            int i = next++;
            return new BlockPos((i % 6) * step, 40 + (i / 18) * step, ((i / 6) % 3) * step);
        }
    }

    private static PoiSignals.Builder add(PoiSignals.Builder b, Grid g, PoiBucket bucket, int n) {
        for (int i = 0; i < n; i++) {
            b.cell(bucket, g.take());
        }
        return b;
    }

    private static double sOf(double sumW) {
        return 1.0D - Math.exp(-sumW / 3.0D);
    }

    private static double withBonus(double s) {
        return 1.0D - (1.0D - s) * 0.85D;
    }

    private static double tOf(double s, double c, double e) {
        return 1.0D - (1.0D - s) * (1.0D - 0.85D * c) * (1.0D - e);
    }

    private static PoiScore eval(PoiSignals.Builder b) {
        return PoiScorer.evaluate(b.build());
    }

    private static PoiBucket[] evidenceBuckets() {
        List<PoiBucket> list = new ArrayList<>();
        for (PoiBucket b : PoiBucket.values()) {
            if (!b.isNatural()) {
                list.add(b);
            }
        }
        return list.toArray(new PoiBucket[0]);
    }

    // ------------------------------------------------------------------ calibration fixtures (6.3)

    /** 8 planks/fence + 3 rails + 3 cobwebs + 2 torches (16 cells, one corridor) and a chest minecart. */
    @Test
    void fixtureMineshaftCorridor() {
        Grid g = new Grid(1);
        PoiSignals.Builder b = PoiSignals.builder();
        add(b, g, PoiBucket.WOOD_BUILD, 8);
        add(b, g, PoiBucket.RAIL, 3);
        add(b, g, PoiBucket.WEB, 3);
        add(b, g, PoiBucket.LIGHT_DRESSING, 2);
        b.entityScore(PoiScorer.entityScore(List.of("minecraft:chest_minecart")));
        PoiSignals signals = b.build();
        PoiScore r = PoiScorer.evaluate(signals);

        assertEquals(8.3D, r.sumW(), EXACT);
        assertTrue(r.weakCounted());
        assertTrue(r.clusterBonus());
        assertEquals(0.95D, r.s(), TOL);
        assertEquals(withBonus(sOf(8.3D)), r.s(), EXACT);
        assertEquals(0.6D, r.e(), EXACT);
        assertEquals(0.98D, r.t(), TOL);
        assertEquals(tOf(r.s(), 0.0D, 0.6D), r.t(), EXACT);
        assertEquals(16, r.distinctCells());
        assertEquals(3, r.nonWeakBuckets());
        assertTrue(r.strongBucket());
        assertEquals(Band.STRUCTURE_CERTAIN, r.band());
        assertFalse(r.downgradedByHabitation());
        assertEquals("", r.mandatoryTrigger());
        assertEquals(PoiLabeler.MINESHAFT, PoiLabeler.label(signals));

        // The design's "strong weight 40%": rails plus cobwebs over the whole sum.
        double strongWeight = 3 * PoiBucket.RAIL.weight() + 3 * PoiBucket.WEB.weight();
        assertEquals(0.40D, strongWeight / r.sumW(), 0.02D);
    }

    /** 8 mossy cobblestone + 1 spawner + 2 chests (11 cells in one room). */
    @Test
    void fixtureDungeon() {
        Grid g = new Grid(1);
        PoiSignals.Builder b = PoiSignals.builder().mossyStone(true);
        add(b, g, PoiBucket.STONE_BUILD, 8);
        add(b, g, PoiBucket.SPAWNER, 1);
        add(b, g, PoiBucket.CONTAINER, 2);
        PoiSignals signals = b.build();
        PoiScore r = PoiScorer.evaluate(signals);

        assertEquals(8.0D, r.sumW(), EXACT);
        assertEquals(0.93D, r.s(), TOL);
        assertEquals(withBonus(sOf(8.0D)), r.s(), EXACT);
        assertEquals(r.s(), r.t(), EXACT);
        assertEquals(11, r.distinctCells());
        assertEquals(Band.STRUCTURE_CERTAIN, r.band());
        assertEquals(PoiLabeler.DUNGEON, PoiLabeler.label(signals));
    }

    /** 6 deepslate build + 2 soul lanterns + 1 shrieker: mandatory whatever else the score says. */
    @Test
    void fixtureAncientCityEdgeIsMandatory() {
        Grid g = new Grid(1);
        PoiSignals.Builder b = PoiSignals.builder().sculkShrieker(1);
        add(b, g, PoiBucket.DEEPSLATE_BUILD, 6);
        add(b, g, PoiBucket.LIGHT_DRESSING, 2);
        add(b, g, PoiBucket.SCULK_STRUCT, 1);
        PoiSignals signals = b.build();
        PoiScore r = PoiScorer.evaluate(signals);

        assertEquals(Band.MANDATORY, r.band());
        assertEquals("sculk_shrieker", r.mandatoryTrigger());
        assertEquals(9.5D, r.sumW(), EXACT);
        assertTrue(r.s() > 0.9D);
        assertEquals(PoiLabeler.ANCIENT_CITY, PoiLabeler.label(signals));
    }

    /** 6 tuff/copper + 1 vault + 2 chests (9 cells). */
    @Test
    void fixtureTrialChamber() {
        Grid g = new Grid(1);
        PoiSignals.Builder b = PoiSignals.builder().vault(true);
        add(b, g, PoiBucket.COPPER_TUFF_BUILD, 6);
        add(b, g, PoiBucket.SPAWNER, 1);
        add(b, g, PoiBucket.CONTAINER, 2);
        PoiSignals signals = b.build();
        PoiScore r = PoiScorer.evaluate(signals);

        assertEquals(7.0D, r.sumW(), EXACT);
        assertEquals(0.90D, r.s(), TOL);
        assertEquals(withBonus(sOf(7.0D)), r.s(), EXACT);
        assertEquals(Band.STRUCTURE_CERTAIN, r.band());
        assertEquals(PoiLabeler.TRIAL_CHAMBER, PoiLabeler.label(signals));
    }

    /** The ledgered torches never reach the scorer, so a plain tunnel has no evidence at all. */
    @Test
    void fixturePlainTunnelWithLedgeredTorches() {
        PoiScore r = eval(PoiSignals.builder().openness(0.02D, 2.0D));

        assertTrue(r.t() < 0.05D);
        assertEquals(0.0D, r.t(), EXACT);
        assertEquals(Band.NONE, r.band());
    }

    /** The bot's own torches and cobblestone seals after a restart without the ledger. */
    @Test
    void fixtureBotTorchesAndCobbleAfterRestartAreWeakOnly() {
        Grid g = new Grid(1);
        PoiSignals.Builder b = PoiSignals.builder();
        add(b, g, PoiBucket.LIGHT_DRESSING, 5);
        add(b, g, PoiBucket.COBBLE, 10);
        PoiScore r = eval(b);

        assertEquals(0.0D, r.sumW(), EXACT);
        assertEquals(0.0D, r.s(), EXACT);
        assertEquals(0.0D, r.t(), EXACT);
        assertEquals(0, r.distinctCells());
        assertEquals(0, r.nonWeakBuckets());
        assertFalse(r.weakCounted());
        assertFalse(r.clusterBonus());
        assertNull(r.centroid());
        assertEquals(Band.NONE, r.band());
    }

    @Test
    void fixtureSingleStrayOakPlanks() {
        PoiScore r = eval(PoiSignals.builder().cell(PoiBucket.WOOD_BUILD, new BlockPos(0, 0, 0)));

        assertEquals(0.15D, r.s(), TOL);
        assertEquals(sOf(0.5D), r.s(), EXACT);
        assertTrue(r.t() < 0.20D);
        assertEquals(Band.NONE, r.band());
    }

    /** Spore blossom, dripleaf and moss are NATURAL: the builder drops them and nothing scores. */
    @Test
    void fixtureLushCaveVegetationIsAllNatural() {
        PoiSignals.Builder b = PoiSignals.builder().openness(0.10D, 8.0D);
        for (int i = 0; i < 40; i++) {
            b.cell(PoiBucket.NATURAL, new BlockPos(i, 30, 0));
        }
        PoiSignals signals = b.build();
        PoiScore r = PoiScorer.evaluate(signals);

        assertEquals(0, signals.totalCells());
        assertEquals(0.0D, r.t(), EXACT);
        assertEquals(Band.NONE, r.band());
    }

    @Test
    void fixtureLushCaveVolume4000AtRadius16() {
        double f = PoiScorer.freeFractionFromVolume(4000.0D, 16.0D);
        assertEquals(0.233D, f, 0.001D);
        PoiScore r = eval(PoiSignals.builder().perceptionRadius(16.0D).openness(f, 8.0D));

        assertEquals(0.10D, r.c(), TOL);
        assertEquals(0.09D, r.t(), TOL);
        assertEquals(tOf(0.0D, r.c(), 0.0D), r.t(), EXACT);
        assertEquals(Band.NONE, r.band());
    }

    @Test
    void fixtureBigNaturalCavernIsCavernOnlyPossible() {
        PoiScore r = eval(PoiSignals.builder().perceptionRadius(16.0D).openness(0.554D, 20.0D));

        assertEquals(1.0D, r.c(), 1.0e-6D);
        assertEquals(0.85D, r.t(), 1.0e-6D);
        assertEquals(0.0D, r.s(), EXACT);
        assertTrue(r.s() < 0.75D);
        assertEquals(Band.CAVERN_ONLY, r.band());
        assertTrue(r.band().isPossibleClass());
        assertTrue(r.cavernActive());
        assertTrue(r.possibleGate());
    }

    /**
     * Amethyst geode: 4 counted cells (cap 4) of the FOSSIL_GEODE bucket. Observed spread out, S is
     * 0.28; packed into one 6-ball the bonus lifts it to 0.39, still under the 0.40 gate. Either
     * way, and in any dimension or biome (there is no biome term), it is never POSSIBLE.
     */
    @Test
    void fixtureAmethystGeodeAloneNeverPossible() {
        PoiScore spread = eval(add(PoiSignals.builder(), new Grid(7), PoiBucket.FOSSIL_GEODE, 6));
        assertEquals(1.0D, spread.sumW(), EXACT);
        assertFalse(spread.clusterBonus());
        assertEquals(0.28D, spread.s(), TOL);
        assertEquals(Band.NONE, spread.band());

        PoiScore packed = eval(add(PoiSignals.builder(), new Grid(1), PoiBucket.FOSSIL_GEODE, 12));
        assertTrue(packed.clusterBonus());
        assertEquals(withBonus(sOf(1.0D)), packed.s(), EXACT);
        assertTrue(packed.t() < PoiScorer.T_POSSIBLE);
        assertEquals(Band.NONE, packed.band());
        assertFalse(packed.strongBucket());
    }

    /** 6 cells of a modded namespace in one cluster: capped at 4, bonus applies, strong MODDED. */
    @Test
    void fixtureModdedNamespaceClusterOfSix() {
        PoiScore r = eval(add(PoiSignals.builder(), new Grid(1), PoiBucket.MODDED, 6));

        assertEquals(1.6D, r.sumW(), EXACT);
        assertTrue(r.clusterBonus());
        assertEquals(0.50D, r.s(), TOL);
        assertEquals(withBonus(sOf(1.6D)), r.s(), EXACT);
        assertTrue(r.strongBucket());
        assertEquals(Band.POSSIBLE, r.band());
        assertTrue(r.s() < PoiScorer.CERTAIN_S_MIN);
    }

    /**
     * Bed + crafting table + furnace (3 FURNISHING cells), 2 chests, 8 planks and 3 torches (16
     * cells): S is certain-grade but the habitation downgrade demotes it to POSSIBLE.
     */
    @Test
    void fixturePlayerBaseDowngradesToPossible() {
        Grid g = new Grid(1);
        PoiSignals.Builder b = PoiSignals.builder()
                .habitation(Habitation.BED).habitation(Habitation.CRAFTING_TABLE).habitation(Habitation.FURNACE);
        add(b, g, PoiBucket.FURNISHING, 3);
        add(b, g, PoiBucket.CONTAINER, 2);
        add(b, g, PoiBucket.WOOD_BUILD, 8);
        add(b, g, PoiBucket.LIGHT_DRESSING, 3);
        PoiScore r = eval(b);

        assertEquals(8.7D, r.sumW(), EXACT);
        assertEquals(0.95D, r.s(), TOL);
        assertTrue(r.s() >= PoiScorer.CERTAIN_S_MIN);
        assertEquals(16, r.distinctCells());
        assertEquals(3, r.nonWeakBuckets());
        assertEquals(Band.POSSIBLE, r.band());
        assertTrue(r.downgradedByHabitation());
        assertTrue(r.possibleGate());
    }

    @Test
    void fixtureThreeUnclassifiedCellsGiveNoBand() {
        PoiScore r = eval(add(PoiSignals.builder(), new Grid(1), PoiBucket.UNCLASSIFIED, 3));

        assertEquals(0.22D, r.s(), TOL);
        assertEquals(sOf(0.75D), r.s(), EXACT);
        assertFalse(r.clusterBonus());
        assertEquals(Band.NONE, r.band());
    }

    // ------------------------------------------------------------------ bucket rules

    @Test
    void weakBucketsCountOnlyBesideTwoNonWeakBuckets() {
        Grid g = new Grid(1);
        // No non-weak bucket.
        PoiScore weakOnly = eval(add(add(PoiSignals.builder(), g, PoiBucket.LIGHT_DRESSING, 3), g, PoiBucket.COBBLE, 10));
        assertEquals(0.0D, weakOnly.sumW(), EXACT);

        // Exactly one non-weak bucket: the weak ones are still ignored.
        g = new Grid(1);
        PoiSignals.Builder oneBucket = PoiSignals.builder();
        add(oneBucket, g, PoiBucket.STONE_BUILD, 2);
        add(oneBucket, g, PoiBucket.LIGHT_DRESSING, 3);
        add(oneBucket, g, PoiBucket.COBBLE, 10);
        PoiScore one = eval(oneBucket);
        assertEquals(1.0D, one.sumW(), EXACT);
        assertFalse(one.weakCounted());
        assertEquals(2, one.distinctCells());

        // Exactly two non-weak buckets: both weak buckets join, weight and cells.
        g = new Grid(1);
        PoiSignals.Builder twoBuckets = PoiSignals.builder();
        add(twoBuckets, g, PoiBucket.STONE_BUILD, 2);
        add(twoBuckets, g, PoiBucket.WOOD_BUILD, 1);
        add(twoBuckets, g, PoiBucket.LIGHT_DRESSING, 3);
        add(twoBuckets, g, PoiBucket.COBBLE, 10);
        PoiScore two = eval(twoBuckets);
        double expected = 2 * 0.5D + 1 * 0.5D + 3 * 0.5D + 10 * 0.1D;
        assertEquals(expected, two.sumW(), EXACT);
        assertTrue(two.weakCounted());
        assertEquals(2, two.nonWeakBuckets());
        assertEquals(16, two.distinctCells());
    }

    @Test
    void weakCellsAreExcludedFromCentroidUntilAdmitted() {
        PoiSignals.Builder b = PoiSignals.builder()
                .cell(PoiBucket.STONE_BUILD, new BlockPos(0, 10, 0))
                .cell(PoiBucket.LIGHT_DRESSING, new BlockPos(100, 10, 0));
        assertEquals(new Vec3d(0.5D, 10.5D, 0.5D), eval(b).centroid());

        b.cell(PoiBucket.WOOD_BUILD, new BlockPos(2, 10, 0));
        // Now weak counts: mean x = (0 + 2 + 100) / 3.
        assertEquals(34.0D + 0.5D, eval(b).centroid().x, EXACT);
    }

    @Test
    void perBucketCapsLimitTheWeight() {
        Grid g = new Grid(1);
        PoiScore capped = eval(add(PoiSignals.builder(), g, PoiBucket.WOOD_BUILD, 30));
        assertEquals(8 * 0.5D, capped.sumW(), EXACT);
        assertEquals(30, capped.distinctCells());

        // Two helper buckets far away keep weak buckets admitted; they are identical in both runs.
        PoiBucket[] helpers = {PoiBucket.WOOD_BUILD, PoiBucket.FURNISHING, PoiBucket.FOSSIL_GEODE};
        for (PoiBucket bucket : evidenceBuckets()) {
            PoiSignals.Builder atCap = PoiSignals.builder();
            PoiSignals.Builder overCap = PoiSignals.builder();
            add(atCap, new Grid(1), bucket, bucket.cap());
            add(overCap, new Grid(1), bucket, bucket.cap() + 5);
            int used = 0;
            for (PoiBucket helper : helpers) {
                if (helper != bucket && used < 2) {
                    BlockPos far = new BlockPos(1000 + used, 200, 1000);
                    atCap.cell(helper, far);
                    overCap.cell(helper, far);
                    used++;
                }
            }
            PoiScore a = eval(atCap);
            PoiScore o = eval(overCap);
            assertEquals(a.sumW(), o.sumW(), EXACT, bucket.name());
            assertEquals(o.distinctCells(), a.distinctCells() + 5, bucket.name());
        }
    }

    /** Each bucket is strong only from its {@code strongMinCells}; below that it is a plain bucket. */
    @Test
    void qualifierRuleRespectsStrongMinCells() {
        for (PoiBucket bucket : evidenceBuckets()) {
            if (bucket.strength() != PoiBucket.Strength.STRONG) {
                assertFalse(eval(add(PoiSignals.builder(), new Grid(1), bucket, bucket.cap())).strongBucket(), bucket.name());
                continue;
            }
            int min = bucket.strongMinCells();
            if (min > 1) {
                assertFalse(eval(add(PoiSignals.builder(), new Grid(1), bucket, min - 1)).strongBucket(), bucket.name());
            }
            assertTrue(eval(add(PoiSignals.builder(), new Grid(1), bucket, min)).strongBucket(), bucket.name());
        }
    }

    @Test
    void qualifierRuleGatesPossibleForDeepslateBuild() {
        // 3 deepslate cells: S = 0.63 (T is over 0.40) but the bucket is not yet strong: no band.
        PoiScore three = eval(add(PoiSignals.builder(), new Grid(7), PoiBucket.DEEPSLATE_BUILD, 3));
        assertTrue(three.t() >= PoiScorer.T_POSSIBLE);
        assertFalse(three.strongBucket());
        assertEquals(Band.NONE, three.band());
        assertFalse(three.possibleGate());

        PoiScore four = eval(add(PoiSignals.builder(), new Grid(7), PoiBucket.DEEPSLATE_BUILD, 4));
        assertTrue(four.strongBucket());
        assertEquals(Band.POSSIBLE, four.band());
    }

    @Test
    void qualifierRuleForContainerRailAndModded() {
        PoiScore chest1 = eval(add(PoiSignals.builder(), new Grid(7), PoiBucket.CONTAINER, 1));
        PoiScore chest2 = eval(add(PoiSignals.builder(), new Grid(7), PoiBucket.CONTAINER, 2));
        assertFalse(chest1.strongBucket());
        assertTrue(chest2.strongBucket());
        assertEquals(Band.POSSIBLE, chest2.band());
        assertEquals(Band.NONE, chest1.band());

        assertFalse(eval(add(PoiSignals.builder(), new Grid(7), PoiBucket.RAIL, 1)).strongBucket());
        assertTrue(eval(add(PoiSignals.builder(), new Grid(7), PoiBucket.RAIL, 2)).strongBucket());
        assertEquals(Band.POSSIBLE, eval(add(PoiSignals.builder(), new Grid(7), PoiBucket.RAIL, 3)).band());

        assertFalse(eval(add(PoiSignals.builder(), new Grid(7), PoiBucket.MODDED, 2)).strongBucket());
        assertEquals(Band.NONE, eval(add(PoiSignals.builder(), new Grid(7), PoiBucket.MODDED, 3)).band());
        assertEquals(Band.POSSIBLE, eval(add(PoiSignals.builder(), new Grid(7), PoiBucket.MODDED, 4)).band());
    }

    @Test
    void aLoneSpawnerIsPossible() {
        PoiScore r = eval(add(PoiSignals.builder(), new Grid(1), PoiBucket.SPAWNER, 1));

        assertEquals(sOf(2.0D), r.s(), EXACT);
        assertTrue(r.strongBucket());
        assertEquals(Band.POSSIBLE, r.band());
    }

    @Test
    void aSingleNormalBucketNeverGatesPossibleEvenAtCap() {
        for (PoiBucket bucket : new PoiBucket[]{PoiBucket.WOOD_BUILD, PoiBucket.FURNISHING,
                PoiBucket.FOSSIL_GEODE, PoiBucket.UNCLASSIFIED}) {
            PoiScore r = eval(add(PoiSignals.builder(), new Grid(7), bucket, bucket.cap()));
            assertEquals(Band.NONE, r.band(), bucket.name());
            assertFalse(r.strongBucket(), bucket.name());
        }
        // Planks at cap: S = 0.74, well over the T gate, yet only one non-weak bucket and nothing strong.
        PoiScore planks = eval(add(PoiSignals.builder(), new Grid(7), PoiBucket.WOOD_BUILD, 8));
        assertTrue(planks.t() >= PoiScorer.T_POSSIBLE);
        assertEquals(Band.NONE, planks.band());
    }

    @Test
    void twoNonWeakBucketsGatePossible() {
        Grid g = new Grid(7);
        PoiSignals.Builder good = PoiSignals.builder();
        add(good, g, PoiBucket.WOOD_BUILD, 4);
        add(good, g, PoiBucket.FURNISHING, 2);
        PoiScore r = eval(good);
        assertEquals(2, r.nonWeakBuckets());
        assertFalse(r.strongBucket());
        assertEquals(Band.POSSIBLE, r.band());

        // Two buckets but T under the gate.
        g = new Grid(7);
        PoiSignals.Builder low = PoiSignals.builder();
        add(low, g, PoiBucket.WOOD_BUILD, 1);
        add(low, g, PoiBucket.UNCLASSIFIED, 1);
        assertEquals(Band.NONE, eval(low).band());
    }

    @Test
    void certainNeedsScoreCellsAndThreeNonWeakBuckets() {
        // 3 buckets, S over 0.8, but only 5 cells.
        Grid g = new Grid(1);
        PoiSignals.Builder five = PoiSignals.builder();
        add(five, g, PoiBucket.SPAWNER, 1);
        add(five, g, PoiBucket.CONTAINER, 2);
        add(five, g, PoiBucket.STONE_BUILD, 2);
        PoiScore fiveScore = eval(five);
        assertEquals(5, fiveScore.distinctCells());
        assertTrue(fiveScore.s() >= PoiScorer.CERTAIN_S_MIN);
        assertEquals(Band.POSSIBLE, fiveScore.band());

        // A sixth cell makes it certain.
        g = new Grid(1);
        PoiSignals.Builder six = PoiSignals.builder();
        add(six, g, PoiBucket.SPAWNER, 1);
        add(six, g, PoiBucket.CONTAINER, 2);
        add(six, g, PoiBucket.STONE_BUILD, 3);
        assertEquals(Band.STRUCTURE_CERTAIN, eval(six).band());

        // Many cells and S over 0.8 but only 2 non-weak buckets.
        g = new Grid(1);
        PoiSignals.Builder twoBuckets = PoiSignals.builder();
        add(twoBuckets, g, PoiBucket.SPAWNER, 2);
        add(twoBuckets, g, PoiBucket.CONTAINER, 3);
        add(twoBuckets, g, PoiBucket.LIGHT_DRESSING, 3);
        PoiScore two = eval(twoBuckets);
        assertTrue(two.distinctCells() >= 6);
        assertTrue(two.s() >= PoiScorer.CERTAIN_S_MIN);
        assertEquals(2, two.nonWeakBuckets());
        assertEquals(Band.POSSIBLE, two.band());

        // 3 buckets and 6 cells but S under 0.8.
        g = new Grid(7);
        PoiSignals.Builder lowS = PoiSignals.builder();
        add(lowS, g, PoiBucket.WOOD_BUILD, 3);
        add(lowS, g, PoiBucket.FURNISHING, 2);
        add(lowS, g, PoiBucket.UNCLASSIFIED, 1);
        PoiScore low = eval(lowS);
        assertEquals(6, low.distinctCells());
        assertEquals(3, low.nonWeakBuckets());
        assertTrue(low.s() < PoiScorer.CERTAIN_S_MIN);
        assertEquals(Band.POSSIBLE, low.band());
    }

    @Test
    void weakCellsCountTowardTheSixCellRuleOnlyWhenAdmitted() {
        // 3 non-weak buckets, 4 non-weak cells + 2 torches: torches are admitted, 6 cells, S over 0.8.
        Grid g = new Grid(1);
        PoiSignals.Builder b = PoiSignals.builder();
        add(b, g, PoiBucket.SPAWNER, 1);
        add(b, g, PoiBucket.CONTAINER, 2);
        add(b, g, PoiBucket.STONE_BUILD, 1);
        add(b, g, PoiBucket.LIGHT_DRESSING, 2);
        PoiScore r = eval(b);
        assertEquals(6, r.distinctCells());
        assertEquals(Band.STRUCTURE_CERTAIN, r.band());

        // Without the two torches only 4 cells remain: no longer certain.
        Grid g2 = new Grid(1);
        PoiSignals.Builder noTorches = PoiSignals.builder();
        add(noTorches, g2, PoiBucket.SPAWNER, 1);
        add(noTorches, g2, PoiBucket.CONTAINER, 2);
        add(noTorches, g2, PoiBucket.STONE_BUILD, 1);
        PoiScore fewer = eval(noTorches);
        assertEquals(4, fewer.distinctCells());
        assertEquals(Band.POSSIBLE, fewer.band());
    }

    // ------------------------------------------------------------------ habitation downgrade

    private static PoiSignals.Builder certainBase() {
        Grid g = new Grid(1);
        PoiSignals.Builder b = PoiSignals.builder();
        add(b, g, PoiBucket.STONE_BUILD, 4);
        add(b, g, PoiBucket.CONTAINER, 3);
        add(b, g, PoiBucket.WOOD_BUILD, 4);
        return b;
    }

    @Test
    void habitationDowngradeAppliesForEveryHabitationItem() {
        assertEquals(Band.STRUCTURE_CERTAIN, eval(certainBase()).band());
        for (Habitation item : Habitation.values()) {
            PoiScore r = eval(certainBase().habitation(item));
            assertEquals(Band.POSSIBLE, r.band(), item.name());
            assertTrue(r.downgradedByHabitation(), item.name());
            assertTrue(r.s() >= PoiScorer.CERTAIN_S_MIN, item.name());
        }
    }

    @Test
    void habitationDowngradeIsSuppressedBySpawnerSculkRailOrWeb() {
        for (PoiBucket immune : new PoiBucket[]{PoiBucket.SPAWNER, PoiBucket.SCULK_STRUCT, PoiBucket.RAIL, PoiBucket.WEB}) {
            PoiSignals.Builder b = certainBase().habitation(Habitation.BED);
            b.cell(immune, new BlockPos(50, 60, 50));
            PoiScore r = eval(b);
            assertEquals(Band.STRUCTURE_CERTAIN, r.band(), immune.name());
            assertFalse(r.downgradedByHabitation(), immune.name());
        }
    }

    @Test
    void habitationFlagOnlyMattersForCertainCandidates() {
        PoiSignals.Builder b = PoiSignals.builder().habitation(Habitation.ARMOR_STAND);
        add(b, new Grid(1), PoiBucket.SPAWNER, 1);
        PoiScore r = eval(b);
        assertEquals(Band.POSSIBLE, r.band());
        assertFalse(r.downgradedByHabitation());

        PoiScore none = eval(PoiSignals.builder().habitation(Habitation.BED));
        assertEquals(Band.NONE, none.band());
        assertFalse(none.downgradedByHabitation());
    }

    /** Design 6.7 needs "habitation-like" for any POSSIBLE candidate, not only the downgraded ones. */
    @Test
    void habitationLikeHoldsForAnyBandWithAPlayerItemAndNoImmuneBucket() {
        // A small player base that never reached certain: POSSIBLE, habitation-like, not downgraded.
        PoiSignals.Builder small = PoiSignals.builder().habitation(Habitation.FURNACE);
        Grid shared = new Grid(1);
        add(small, shared, PoiBucket.FURNISHING, 2);
        add(small, shared, PoiBucket.CONTAINER, 2);
        PoiScore r = eval(small);
        assertEquals(Band.POSSIBLE, r.band());
        assertTrue(r.habitationLike());
        assertFalse(r.downgradedByHabitation());
        assertTrue(PoiScorer.habitationLike(small.build()));

        // A spawner room with a furnace is not habitation-like.
        PoiSignals.Builder spawnerRoom = PoiSignals.builder().habitation(Habitation.FURNACE);
        add(spawnerRoom, new Grid(1), PoiBucket.SPAWNER, 1);
        assertFalse(eval(spawnerRoom).habitationLike());

        // No habitation item at all.
        assertFalse(eval(certainBase()).habitationLike());
        assertTrue(eval(certainBase().habitation(Habitation.BOOKSHELF)).habitationLike());
        // Mandatory candidates still report the flag but are never downgraded.
        PoiScore mandatory = eval(certainBase().habitation(Habitation.BED).wardenVisible(true));
        assertTrue(mandatory.habitationLike());
        assertFalse(mandatory.downgradedByHabitation());
    }

    @Test
    void distinctBucketsCountAdmittedWeakBucketsToo() {
        Grid g = new Grid(1);
        PoiSignals.Builder b = PoiSignals.builder();
        add(b, g, PoiBucket.STONE_BUILD, 1);
        add(b, g, PoiBucket.LIGHT_DRESSING, 1);
        PoiScore one = eval(b);
        assertEquals(1, one.nonWeakBuckets());
        assertEquals(1, one.distinctBuckets());

        add(b, g, PoiBucket.WOOD_BUILD, 1);
        add(b, g, PoiBucket.COBBLE, 1);
        PoiScore admitted = eval(b);
        assertEquals(2, admitted.nonWeakBuckets());
        assertEquals(4, admitted.distinctBuckets());
    }

    // ------------------------------------------------------------------ cluster bonus

    @Test
    void clusterBonusNeedsFourCellsWithinTheBall() {
        // Three cells: no bonus however close.
        PoiScore three = eval(add(PoiSignals.builder(), new Grid(1), PoiBucket.STONE_BUILD, 3));
        assertFalse(three.clusterBonus());
        assertEquals(sOf(1.5D), three.s(), EXACT);

        PoiScore four = eval(add(PoiSignals.builder(), new Grid(1), PoiBucket.STONE_BUILD, 4));
        assertTrue(four.clusterBonus());
        assertEquals(withBonus(sOf(2.0D)), four.s(), EXACT);
        assertTrue(four.s() > sOf(2.0D));
    }

    @Test
    void clusterBonusUsesUncappedCellsAndIgnoresBucketCaps() {
        // 5 UNCLASSIFIED cells: weight caps at 4 but all 5 are cluster candidates.
        PoiScore r = eval(add(PoiSignals.builder(), new Grid(1), PoiBucket.UNCLASSIFIED, 5));
        assertTrue(r.clusterBonus());
        assertEquals(withBonus(sOf(1.0D)), r.s(), EXACT);
    }

    @Test
    void clusterBallIsCentredOnAnEvidenceCellAndInclusiveAtSix() {
        // Cell at the origin plus three at exactly distance 6 along the axes: all within the ball.
        PoiSignals.Builder edge = PoiSignals.builder()
                .cell(PoiBucket.STONE_BUILD, new BlockPos(0, 0, 0))
                .cell(PoiBucket.STONE_BUILD, new BlockPos(6, 0, 0))
                .cell(PoiBucket.STONE_BUILD, new BlockPos(0, 6, 0))
                .cell(PoiBucket.STONE_BUILD, new BlockPos(0, 0, -6));
        assertTrue(eval(edge).clusterBonus());

        // One step farther on a diagonal (6, 1, 0 -> sqrt 37) drops that cell out.
        PoiSignals.Builder outside = PoiSignals.builder()
                .cell(PoiBucket.STONE_BUILD, new BlockPos(0, 0, 0))
                .cell(PoiBucket.STONE_BUILD, new BlockPos(6, 1, 0))
                .cell(PoiBucket.STONE_BUILD, new BlockPos(0, 6, 0))
                .cell(PoiBucket.STONE_BUILD, new BlockPos(0, 0, -6));
        assertFalse(eval(outside).clusterBonus());

        // Documented reading: a ball around an evidence cell, not any enclosing ball of radius 6.
        // Cells 0, 4, 8, 12 fit in a radius-6 ball centred at 6, but no cell has 4 within 6.
        PoiSignals.Builder line = PoiSignals.builder();
        for (int x = 0; x <= 12; x += 4) {
            line.cell(PoiBucket.STONE_BUILD, new BlockPos(x, 0, 0));
        }
        assertFalse(eval(line).clusterBonus());
    }

    @Test
    void clusterDoesNotCountWeakCellsThatAreNotAdmitted() {
        PoiSignals.Builder b = PoiSignals.builder();
        b.cell(PoiBucket.STONE_BUILD, new BlockPos(0, 0, 0));
        for (int i = 0; i < 6; i++) {
            b.cell(PoiBucket.LIGHT_DRESSING, new BlockPos(i, 1, 0));
        }
        PoiScore r = eval(b);
        assertFalse(r.weakCounted());
        assertFalse(r.clusterBonus());
        assertEquals(sOf(0.5D), r.s(), EXACT);
    }

    // ------------------------------------------------------------------ noisy-OR, bounds, determinism

    @Test
    void totalScoreIsTheNoisyOrOfStructureCavernAndEntities() {
        Grid g = new Grid(7);
        PoiSignals.Builder b = PoiSignals.builder().entityScore(0.3D).perceptionRadius(16.0D).openness(0.35D);
        add(b, g, PoiBucket.STONE_BUILD, 2);
        PoiScore r = eval(b);

        double s = sOf(1.0D);
        assertEquals(s, r.s(), EXACT);
        assertEquals(0.35D, r.c(), EXACT);
        assertEquals(0.3D, r.e(), EXACT);
        assertEquals(1.0D - (1.0D - s) * (1.0D - 0.85D * 0.35D) * (1.0D - 0.3D), r.t(), EXACT);
        assertTrue(r.t() >= Math.max(s, Math.max(0.85D * 0.35D, 0.3D)));
    }

    @Test
    void addingEvidenceNeverLowersSAndTAndEverythingStaysInUnitRange() {
        PoiBucket[] buckets = evidenceBuckets();
        SplittableRandom rnd = new SplittableRandom(0x5EEDL);
        for (int trial = 0; trial < 60; trial++) {
            List<PoiBucket> bs = new ArrayList<>();
            List<BlockPos> ps = new ArrayList<>();
            double c = 0.0D;
            double e = 0.0D;
            double prevS = 0.0D;
            double prevT = 0.0D;
            for (int step = 0; step < 80; step++) {
                bs.add(buckets[rnd.nextInt(buckets.length)]);
                ps.add(new BlockPos(rnd.nextInt(24), rnd.nextInt(12), rnd.nextInt(24)));
                if (rnd.nextInt(4) == 0) {
                    c = Math.min(1.0D, c + rnd.nextDouble() * 0.08D);
                }
                if (rnd.nextInt(6) == 0) {
                    e = Math.min(0.6D, e + rnd.nextDouble() * 0.15D);
                }
                PoiSignals.Builder b = PoiSignals.builder().entityScore(e).openness(c);
                for (int i = 0; i < bs.size(); i++) {
                    b.cell(bs.get(i), ps.get(i));
                }
                PoiScore r = eval(b);

                assertTrue(r.s() >= prevS - 1.0e-12D, "S dropped in trial " + trial + " step " + step);
                assertTrue(r.t() >= prevT - 1.0e-12D, "T dropped in trial " + trial + " step " + step);
                for (double v : new double[]{r.s(), r.t(), r.c(), r.e()}) {
                    assertTrue(v >= 0.0D && v <= 1.0D, "out of [0,1]: " + v);
                }
                assertTrue(r.t() >= r.s() - 1.0e-12D);
                assertTrue(r.t() <= Math.min(1.0D, r.s() + r.c() + r.e()) + 1.0e-12D);
                prevS = r.s();
                prevT = r.t();
            }
        }
    }

    @Test
    void totalScoreIsMonotoneInCavernChannelAndEntityScore() {
        double prev = -1.0D;
        for (int i = 0; i <= 100; i++) {
            PoiSignals.Builder b = PoiSignals.builder().openness(i / 100.0D);
            add(b, new Grid(7), PoiBucket.WOOD_BUILD, 2);
            double t = eval(b).t();
            assertTrue(t >= prev - 1.0e-12D);
            prev = t;
        }
        prev = -1.0D;
        for (int i = 0; i <= 100; i++) {
            PoiSignals.Builder b = PoiSignals.builder().entityScore(i / 100.0D);
            add(b, new Grid(7), PoiBucket.WOOD_BUILD, 2);
            double t = eval(b).t();
            assertTrue(t >= prev - 1.0e-12D);
            prev = t;
        }
    }

    @Test
    void extremeAndInvalidInputsStayBounded() {
        Grid g = new Grid(1);
        PoiSignals.Builder b = PoiSignals.builder()
                .entityScore(Double.POSITIVE_INFINITY).openness(Double.NaN)
                .perceptionRadius(Double.NaN).sculkFamilyWithin12(-5).reinforcedDeepslate(-1);
        for (PoiBucket bucket : evidenceBuckets()) {
            add(b, g, bucket, bucket.cap() + 3);
        }
        PoiScore r = eval(b);
        assertTrue(r.t() <= 1.0D && r.t() >= 0.0D);
        assertTrue(r.s() <= 1.0D);
        assertEquals(0.6D, r.e(), EXACT);
        assertEquals(0.0D, r.c(), EXACT);

        PoiScore nan = eval(PoiSignals.builder().entityScore(Double.NaN));
        assertEquals(0.0D, nan.e(), EXACT);
        assertEquals(0.0D, eval(PoiSignals.builder().entityScore(-3.0D)).e(), EXACT);
    }

    @Test
    void evaluationIsDeterministicAndIndependentOfInsertionOrder() {
        PoiBucket[] buckets = evidenceBuckets();
        SplittableRandom rnd = new SplittableRandom(99L);
        List<PoiBucket> bs = new ArrayList<>();
        List<BlockPos> ps = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            bs.add(buckets[rnd.nextInt(buckets.length)]);
            ps.add(new BlockPos(rnd.nextInt(30), rnd.nextInt(10), rnd.nextInt(30)));
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < bs.size(); i++) {
            order.add(i);
        }
        PoiSignals.Builder forward = PoiSignals.builder().entityScore(0.3D).openness(0.2D);
        for (int i : order) {
            forward.cell(bs.get(i), ps.get(i));
        }
        PoiScore base = eval(forward);
        assertEquals(base, eval(forward));
        assertEquals(base, PoiScorer.evaluate(forward.build()));

        // Positions may repeat across buckets above (first bucket wins, so order matters there). The
        // invariance check uses a conflict-free cell set.
        PoiSignals.Builder cleanA = PoiSignals.builder();
        PoiSignals.Builder cleanB = PoiSignals.builder();
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            ids.add(i);
        }
        for (int i : ids) {
            cleanA.cell(buckets[i % buckets.length], new BlockPos(i * 3, 20, i % 5));
        }
        Collections.shuffle(ids, new Random(11L));
        for (int i : ids) {
            cleanB.cell(buckets[i % buckets.length], new BlockPos(i * 3, 20, i % 5));
        }
        assertEquals(eval(cleanA), eval(cleanB));
    }

    // ------------------------------------------------------------------ centroid

    @Test
    void centroidIsTheMeanOfContributingCellCentres() {
        PoiSignals.Builder b = PoiSignals.builder()
                .cell(PoiBucket.SPAWNER, new BlockPos(0, 10, 0))
                .cell(PoiBucket.CONTAINER, new BlockPos(4, 12, 8))
                .cell(PoiBucket.RAIL, new BlockPos(2, 11, -2));
        PoiScore r = eval(b);

        assertEquals(new Vec3d(2.5D, 11.5D, 2.0D + 0.5D), r.centroid());
        assertEquals(new BlockPos(2, 11, 2), r.centroidBlock());
        assertNull(eval(PoiSignals.builder()).centroid());
        assertNull(eval(PoiSignals.builder()).centroidBlock());
    }

    // ------------------------------------------------------------------ mandatory rule

    @Test
    void mandatoryWardenVisibleAloneFires() {
        PoiScore r = eval(PoiSignals.builder().wardenVisible(true));

        assertEquals(Band.MANDATORY, r.band());
        assertEquals("warden_visible", r.mandatoryTrigger());
        assertTrue(r.isMandatory());
        assertEquals(0.0D, r.t(), EXACT);
        assertFalse(r.possibleGate());
        assertNull(r.centroid());
    }

    @Test
    void mandatoryOnSingleReinforcedDeepslateShriekerOrCatalyst() {
        assertEquals("reinforced_deepslate", eval(PoiSignals.builder().reinforcedDeepslate(1)).mandatoryTrigger());
        assertEquals("sculk_shrieker", eval(PoiSignals.builder().sculkShrieker(1)).mandatoryTrigger());
        assertEquals("sculk_catalyst", eval(PoiSignals.builder().sculkCatalyst(1)).mandatoryTrigger());
        for (PoiSignals.Builder b : List.of(PoiSignals.builder().reinforcedDeepslate(1),
                PoiSignals.builder().sculkShrieker(1), PoiSignals.builder().sculkCatalyst(1))) {
            assertEquals(Band.MANDATORY, eval(b).band());
        }
    }

    @Test
    void mandatoryNeedsTwoSculkSensors() {
        assertEquals(Band.NONE, eval(PoiSignals.builder().sculkSensors(1)).band());
        PoiScore two = eval(PoiSignals.builder().sculkSensors(2));
        assertEquals(Band.MANDATORY, two.band());
        assertEquals("sculk_sensors", two.mandatoryTrigger());
    }

    @Test
    void mandatorySculkFamilyNeedsSixCellsAndCavernChannelAtLeastPointThree() {
        PoiSignals.Builder six = PoiSignals.builder().sculkFamilyWithin12(6).perceptionRadius(16.0D);
        assertEquals(Band.MANDATORY, eval(six.openness(0.30D)).band());
        assertEquals("sculk_family_cavern", eval(six).mandatoryTrigger());
        assertEquals(Band.NONE, eval(PoiSignals.builder().sculkFamilyWithin12(6).openness(0.29D)).band());
        // Five cells is one short: a big open cavern is only CAVERN_ONLY, never mandatory.
        assertEquals(Band.CAVERN_ONLY, eval(PoiSignals.builder().sculkFamilyWithin12(5).openness(1.0D)).band());
        // Without a usable channel the family rule cannot fire.
        assertEquals(Band.NONE, eval(PoiSignals.builder().sculkFamilyWithin12(20)).band());
        assertEquals(Band.NONE, eval(PoiSignals.builder().sculkFamilyWithin12(20).openness(1.0D)
                .dimensionId("minecraft:the_nether")).band());
        assertEquals(Band.NONE, eval(PoiSignals.builder().sculkFamilyWithin12(20).openness(1.0D)
                .perceptionRadius(8.0D)).band());
    }

    @Test
    void mandatoryIsIndependentOfEverythingElse() {
        // Nothing else in the picture: cavern disabled, no cells, no entities.
        PoiScore bare = eval(PoiSignals.builder().sculkShrieker(1).dimensionId("minecraft:the_nether"));
        assertEquals(Band.MANDATORY, bare.band());

        // Habitation items never soften it.
        PoiSignals.Builder base = certainBase().habitation(Habitation.BED).sculkShrieker(1);
        PoiScore withBase = eval(base);
        assertEquals(Band.MANDATORY, withBase.band());
        assertFalse(withBase.downgradedByHabitation());

        // A certain structure with a warden nearby is mandatory, not merely certain.
        assertEquals(Band.MANDATORY, eval(certainBase().wardenVisible(true)).band());
    }

    @Test
    void mandatoryTriggersJoinInFixedOrder() {
        PoiScore r = eval(PoiSignals.builder().wardenVisible(true).reinforcedDeepslate(2)
                .sculkShrieker(1).sculkCatalyst(1).sculkSensors(3));
        assertEquals("warden_visible+reinforced_deepslate+sculk_shrieker+sculk_catalyst+sculk_sensors",
                r.mandatoryTrigger());
    }

    // ------------------------------------------------------------------ entities

    @Test
    void entityScoreHelperFollowsTheDesignTable() {
        assertEquals(0.0D, PoiScorer.entityScore(List.of()), EXACT);
        assertEquals(0.6D, PoiScorer.entityScore(List.of("minecraft:chest_minecart")), EXACT);
        assertEquals(0.3D, PoiScorer.entityScore(List.of("minecraft:villager")), EXACT);
        assertEquals(0.3D, PoiScorer.entityScore(List.of("minecraft:pillager")), EXACT);
        assertEquals(0.3D, PoiScorer.entityScore(List.of("minecraft:vindicator")), EXACT);
        assertEquals(0.3D, PoiScorer.entityScore(List.of("minecraft:evoker")), EXACT);
        assertEquals(0.3D, PoiScorer.entityScore(List.of("minecraft:illusioner")), EXACT);
        assertEquals(0.15D, PoiScorer.entityScore(List.of("minecraft:item_frame")), EXACT);
        assertEquals(0.15D, PoiScorer.entityScore(List.of("minecraft:armor_stand")), EXACT);
        assertEquals(0.3D, PoiScorer.entityScore(List.of("minecraft:item_frame", "minecraft:armor_stand")), EXACT);
        assertEquals(0.4D, PoiScorer.entityScore(List.of("create:contraption")), EXACT);
        // One non-minecraft namespace bonus, not one per entity.
        assertEquals(0.4D, PoiScorer.entityScore(List.of("create:contraption", "mobs:golem")), EXACT);
        // Ordinary mobs and the warden add nothing.
        assertEquals(0.0D, PoiScorer.entityScore(List.of("minecraft:zombie", "minecraft:warden", "minecraft:bat")), EXACT);
    }

    @Test
    void entityGroupCapIsPointSix() {
        assertEquals(0.6D, PoiScorer.entityScore(List.of("minecraft:villager", "minecraft:villager", "minecraft:villager")), EXACT);
        assertEquals(0.6D, PoiScorer.entityScore(List.of("minecraft:chest_minecart", "minecraft:chest_minecart")), EXACT);
        assertEquals(0.6D, PoiScorer.entityScore(List.of("create:contraption", "minecraft:villager")), EXACT);
        assertEquals(0.6D, PoiScorer.entityScore(List.of("minecraft:armor_stand", "minecraft:armor_stand",
                "minecraft:armor_stand", "minecraft:armor_stand", "minecraft:armor_stand")), EXACT);
        // The scorer also caps defensively whatever the caller passes.
        assertEquals(0.6D, eval(PoiSignals.builder().entityScore(5.0D)).e(), EXACT);
        assertEquals(0.6D, eval(PoiSignals.builder().entityScore(0.75D)).e(), EXACT);
    }

    @Test
    void isWardenMatchesOnlyTheWarden() {
        assertTrue(PoiScorer.isWarden("minecraft:warden"));
        assertFalse(PoiScorer.isWarden("minecraft:zombie"));
        assertFalse(PoiScorer.isWarden(null));
    }

    @Test
    void entityScoreOfPointFourGatesPossibleWithoutAnyBlock() {
        PoiScore atGate = eval(PoiSignals.builder().entityScore(0.4D));
        assertEquals(0.4D, atGate.t(), EXACT);
        assertEquals(Band.POSSIBLE, atGate.band());
        assertEquals(0, atGate.distinctCells());

        assertEquals(Band.NONE, eval(PoiSignals.builder().entityScore(0.39D)).band());
        // A single villager (0.3) is not enough; two are.
        assertEquals(Band.NONE, eval(PoiSignals.builder()
                .entityScore(PoiScorer.entityScore(List.of("minecraft:villager")))).band());
        assertEquals(Band.POSSIBLE, eval(PoiSignals.builder()
                .entityScore(PoiScorer.entityScore(List.of("minecraft:villager", "minecraft:villager")))).band());
        assertEquals(Band.POSSIBLE, eval(PoiSignals.builder()
                .entityScore(PoiScorer.entityScore(List.of("create:contraption")))).band());
        assertEquals(Band.POSSIBLE, eval(PoiSignals.builder()
                .entityScore(PoiScorer.entityScore(List.of("minecraft:chest_minecart")))).band());
    }

    // ------------------------------------------------------------------ cavern channel

    @Test
    void cavernChannelFormula() {
        assertEquals(0.0D, PoiScorer.cavernChannel(0.0D, 20.0D), EXACT);
        assertEquals(0.0D, PoiScorer.cavernChannel(0.175D, 20.0D), EXACT);
        assertEquals(0.0D, PoiScorer.cavernChannel(0.10D, 20.0D), EXACT);
        assertEquals(1.0D, PoiScorer.cavernChannel(0.554D, 20.0D), 1.0e-9D);
        assertEquals(1.0D, PoiScorer.cavernChannel(1.0D, 16.0D), EXACT);
        // Ceiling: 0.6 at upFree <= 6, 0.8 at 11, 1.0 from 16.
        assertEquals(0.6D, PoiScorer.cavernChannel(1.0D, 6.0D), EXACT);
        assertEquals(0.6D, PoiScorer.cavernChannel(1.0D, 0.0D), EXACT);
        assertEquals(0.8D, PoiScorer.cavernChannel(1.0D, 11.0D), EXACT);
        assertEquals(1.0D, PoiScorer.cavernChannel(1.0D, 40.0D), EXACT);
        // Mid range of f: half the span gives half the ceiling.
        assertEquals(0.5D * 0.6D, PoiScorer.cavernChannel(0.175D + 0.379D / 2.0D, 6.0D), 1.0e-9D);
        // Non-finite inputs are treated as zero.
        assertEquals(0.0D, PoiScorer.cavernChannel(Double.NaN, 20.0D), EXACT);
        assertEquals(0.6D, PoiScorer.cavernChannel(1.0D, Double.NaN), EXACT);
    }

    @Test
    void cavernChannelIsMonotoneInFreeFractionAndUpFree() {
        double prev = -1.0D;
        for (int i = 0; i <= 200; i++) {
            double c = PoiScorer.cavernChannel(i / 200.0D, 12.0D);
            assertTrue(c >= prev);
            prev = c;
        }
        prev = -1.0D;
        for (int i = 0; i <= 60; i++) {
            double c = PoiScorer.cavernChannel(0.4D, i);
            assertTrue(c >= prev);
            prev = c;
        }
    }

    @Test
    void freeFractionNormalisesByTheRadiusCubed() {
        assertEquals(1.0D, PoiScorer.freeFractionFromMeanCube(4096.0D, 16.0D), EXACT);
        assertEquals(1.0D, PoiScorer.freeFractionFromVolume(4.0D / 3.0D * Math.PI * 4096.0D, 16.0D), 1.0e-12D);
        assertEquals(0.554D, PoiScorer.freeFractionFromVolume(9505.0D, 16.0D), 0.001D);
        assertEquals(0.0D, PoiScorer.freeFractionFromMeanCube(-5.0D, 16.0D), EXACT);
        assertEquals(0.0D, PoiScorer.freeFractionFromMeanCube(100.0D, 0.0D), EXACT);
        assertEquals(0.0D, PoiScorer.freeFractionFromVolume(Double.NaN, 16.0D), EXACT);
    }

    /**
     * Radius sweep R = 8, 12, 16, 32. The same fraction of the sphere being free (f = 0.554) gives the
     * same channel at every live radius that allows it; the same absolute volume does not.
     */
    @Test
    void cavernRadiusSweepNormalisesByLiveRadius() {
        for (double radius : new double[]{8.0D, 12.0D, 16.0D, 32.0D}) {
            // Constant fraction: mean(L^3) = 0.554 R^3.
            double f = PoiScorer.freeFractionFromMeanCube(0.554D * radius * radius * radius, radius);
            assertEquals(0.554D, f, 1.0e-12D);
            PoiScore r = eval(PoiSignals.builder().perceptionRadius(radius).openness(f, 20.0D));
            if (radius < 12.0D) {
                assertFalse(r.cavernActive(), "R=" + radius);
                assertEquals(0.0D, r.c(), EXACT);
                assertEquals(Band.NONE, r.band());
            } else {
                assertTrue(r.cavernActive(), "R=" + radius);
                assertEquals(1.0D, r.c(), 1.0e-6D);
                assertEquals(0.85D, r.t(), 1.0e-6D);
                assertEquals(Band.CAVERN_ONLY, r.band());
            }
        }

        // Constant absolute volume 9505 (about 3000..9500 at R = 16): a big cavern at R=16, a
        // trivially small share of the R=32 sphere, and a full sphere at R=12 that clamps to 1.
        double volume = 9505.0D;
        assertEquals(1.0D, cAt(volume, 16.0D), 1.0e-3D);
        assertEquals(0.0D, cAt(volume, 32.0D), EXACT);
        assertEquals(1.0D, cAt(volume, 12.0D), EXACT);
        assertEquals(0.0D, cAt(volume, 8.0D), EXACT);
        // Lush cave: 4000 at R = 16 is a weak signal, at R = 12 it is a big one.
        assertTrue(cAt(4000.0D, 16.0D) < 0.2D);
        assertTrue(cAt(4000.0D, 12.0D) > 0.5D);
    }

    private static double cAt(double volume, double radius) {
        double f = PoiScorer.freeFractionFromVolume(volume, radius);
        return eval(PoiSignals.builder().perceptionRadius(radius).openness(f, 20.0D)).c();
    }

    @Test
    void cavernChannelBoundaryAtTwelveBlocks() {
        assertFalse(eval(PoiSignals.builder().perceptionRadius(11.999D).openness(1.0D)).cavernActive());
        assertTrue(eval(PoiSignals.builder().perceptionRadius(12.0D).openness(1.0D)).cavernActive());
        assertTrue(PoiScorer.cavernChannelActive(true, 12.0D));
        assertFalse(PoiScorer.cavernChannelActive(true, 11.0D));
        assertFalse(PoiScorer.cavernChannelActive(false, 32.0D));
    }

    @Test
    void cavernChannelIsDisabledOutsideEnabledDimensions() {
        for (String dim : new String[]{"minecraft:the_nether", "minecraft:the_end", "modded:twilight"}) {
            PoiScore r = eval(PoiSignals.builder().dimensionId(dim).openness(1.0D));
            assertFalse(r.cavernActive(), dim);
            assertEquals(0.0D, r.c(), EXACT, dim);
            assertEquals(Band.NONE, r.band(), dim);
        }
        PoiScore overworld = eval(PoiSignals.builder().dimensionId("minecraft:overworld").openness(1.0D));
        assertTrue(overworld.cavernActive());
        assertEquals(Band.CAVERN_ONLY, overworld.band());

        // poi.cavernDimensions can list more dimensions...
        PoiSignals nether = PoiSignals.builder().dimensionId("minecraft:the_nether")
                .cavernDimensions(List.of("minecraft:overworld", "minecraft:the_nether")).openness(1.0D).build();
        assertTrue(nether.cavernDimensionEnabled());
        assertTrue(PoiScorer.evaluate(nether).cavernActive());
        // ...or drop the overworld.
        PoiSignals dropped = PoiSignals.builder().cavernDimensions(Set.of("minecraft:the_nether")).openness(1.0D).build();
        assertFalse(dropped.cavernDimensionEnabled());
        assertFalse(PoiScorer.evaluate(dropped).cavernActive());
        // The explicit flag overrides the derived one.
        assertTrue(PoiSignals.builder().dimensionId("x:y").cavernDimensionEnabled(true).build().cavernDimensionEnabled());
        assertFalse(PoiSignals.builder().cavernDimensionEnabled(false).build().cavernDimensionEnabled());
    }

    @Test
    void cavernChannelNeedsValidOpenness() {
        PoiScore none = eval(PoiSignals.builder().opennessUnavailable());
        assertFalse(none.cavernActive());
        assertEquals(0.0D, none.c(), EXACT);
        assertFalse(PoiSignals.empty().opennessValid());

        PoiSignals signals = PoiSignals.builder().openness(0.9D).opennessUnavailable().build();
        assertFalse(signals.opennessValid());
        assertEquals(0.0D, signals.opennessC(), EXACT);
    }

    @Test
    void cavernOnlyBoundaryAtPointFortySevenAndSAtPointTwentyFive() {
        // C = 0.47 leaves T = 0.3995: just under the gate.
        PoiScore below = eval(PoiSignals.builder().openness(0.47D));
        assertEquals(0.3995D, below.t(), 1.0e-9D);
        assertEquals(Band.NONE, below.band());
        assertFalse(below.possibleGate());

        // C = 0.4706 clears T = 0.40 and C >= 0.47.
        PoiScore above = eval(PoiSignals.builder().openness(0.4706D));
        assertTrue(above.t() >= 0.40D);
        assertEquals(Band.CAVERN_ONLY, above.band());

        // A stray plank (S = 0.15) does not block cavern-only; nor do the bot's own torches.
        Grid g = new Grid(1);
        PoiScore plank = eval(add(PoiSignals.builder().openness(1.0D), g, PoiBucket.WOOD_BUILD, 1));
        assertEquals(Band.CAVERN_ONLY, plank.band());
        PoiScore torches = eval(add(PoiSignals.builder().openness(1.0D), new Grid(1), PoiBucket.LIGHT_DRESSING, 4));
        assertEquals(Band.CAVERN_ONLY, torches.band());

        // Literal rule: once S reaches 0.25 without any structural qualifier there is no cavern-only band.
        PoiScore geode = eval(add(PoiSignals.builder().openness(1.0D), new Grid(7), PoiBucket.FOSSIL_GEODE, 4));
        assertTrue(geode.s() >= PoiScorer.CAVERN_ONLY_S_MAX);
        assertEquals(Band.NONE, geode.band());
    }

    @Test
    void cavernPlusStructuralEvidenceIsPossibleNotCavernOnly() {
        Grid g = new Grid(1);
        PoiSignals.Builder b = PoiSignals.builder().openness(1.0D);
        add(b, g, PoiBucket.WOOD_BUILD, 2);
        add(b, g, PoiBucket.FURNISHING, 1);
        assertEquals(Band.POSSIBLE, eval(b).band());

        assertEquals(Band.POSSIBLE, eval(add(PoiSignals.builder().openness(1.0D), new Grid(1), PoiBucket.SPAWNER, 1)).band());
        assertEquals(Band.POSSIBLE, eval(PoiSignals.builder().openness(1.0D).entityScore(0.4D)).band());
    }

    // ------------------------------------------------------------------ hysteresis

    @Test
    void hysteresisNeedsTwoHitsInTheLastThreeEvaluations() {
        Hysteresis h = new Hysteresis();
        assertFalse(h.record(0, true));
        assertTrue(h.record(20, true));
        assertTrue(h.record(40, false));
        assertEquals(3, h.evaluations());

        Hysteresis alt = new Hysteresis();
        assertFalse(alt.record(0, true));
        assertFalse(alt.record(20, false));
        assertTrue(alt.record(40, true));

        Hysteresis late = new Hysteresis();
        assertFalse(late.record(0, false));
        assertFalse(late.record(20, true));
        assertTrue(late.record(40, true));

        Hysteresis miss = new Hysteresis();
        assertFalse(miss.record(0, false));
        assertFalse(miss.record(20, true));
        assertFalse(miss.record(40, false));
        assertFalse(miss.record(60, false));
    }

    @Test
    void hysteresisWindowSlidesAndOldHitsFallOut() {
        Hysteresis h = new Hysteresis();
        h.record(0, true);
        h.record(20, true);
        assertTrue(h.satisfied(20));
        h.record(40, false);
        assertTrue(h.satisfied(40));      // hit, hit, miss
        assertFalse(h.record(60, false)); // hit, miss, miss
        assertEquals(1, h.hits(60));
        assertEquals(3, h.evaluations());
        assertFalse(h.record(80, false));
        assertFalse(h.record(100, true));          // miss, miss, hit
        assertTrue(h.record(120, true));           // miss, hit, hit
    }

    @Test
    void hysteresisIgnoresEvaluationsCloserThanTwentyTicks() {
        Hysteresis h = new Hysteresis();
        assertFalse(h.record(100, true));
        assertFalse(h.record(105, true));
        assertFalse(h.record(119, true));
        assertEquals(1, h.evaluations());
        assertEquals(100L, h.lastTick());
        assertTrue(h.record(120, true));
        assertEquals(2, h.evaluations());
    }

    @Test
    void hysteresisDropsEntriesOlderThanTheEvidenceWindow() {
        Hysteresis h = new Hysteresis();
        h.record(0, true);
        h.record(20, true);
        assertTrue(h.satisfied(240 + 0));            // the oldest is exactly 240 old: still fresh
        assertFalse(h.satisfied(241));               // the first hit went stale
        // A gap keeps stale hits from combining with a fresh one.
        assertFalse(h.record(400, true));
        assertEquals(1, h.hits(400));

        Hysteresis edge = new Hysteresis();
        edge.record(0, true);
        edge.record(20, true);
        assertTrue(edge.record(260, true));          // hit at 0 is stale (260), 20 is 240 old, fresh
        assertEquals(2, edge.hits(260));
    }

    @Test
    void hysteresisResetsWhenTheClockGoesBackwards() {
        Hysteresis h = new Hysteresis();
        h.record(1000, true);
        h.record(1020, true);
        assertTrue(h.satisfied(1020));
        assertFalse(h.record(10, true));
        assertEquals(1, h.evaluations());
        h.reset();
        assertEquals(0, h.evaluations());
        assertEquals(Long.MIN_VALUE, h.lastTick());
        assertFalse(h.satisfied(0));
    }

    @Test
    void hysteresisCountsThePossibleGateNotTheRawTotal() {
        // T is over 0.40 but there is no qualifying condition: not a hit.
        PoiScore planks = eval(add(PoiSignals.builder(), new Grid(7), PoiBucket.WOOD_BUILD, 8));
        assertTrue(planks.t() >= PoiScorer.T_POSSIBLE);
        assertFalse(planks.possibleGate());
        PoiScore spawner = eval(add(PoiSignals.builder(), new Grid(1), PoiBucket.SPAWNER, 1));
        assertTrue(spawner.possibleGate());

        Hysteresis h = new Hysteresis();
        assertFalse(h.record(0, planks));
        assertFalse(h.record(20, planks));
        assertFalse(h.record(40, spawner));
        assertTrue(h.record(60, spawner));
        assertThrows(NullPointerException.class, () -> h.record(80, (PoiScore) null));
    }

    // ------------------------------------------------------------------ PoiSignals contract

    @Test
    void signalsDeduplicateCellsAndIgnoreNaturalBlocks() {
        PoiSignals s = PoiSignals.builder()
                .cell(PoiBucket.WOOD_BUILD, new BlockPos(1, 2, 3))
                .cell(PoiBucket.WOOD_BUILD, new BlockPos(1, 2, 3))
                .cell(PoiBucket.RAIL, new BlockPos(1, 2, 3))
                .cell(PoiBucket.NATURAL, new BlockPos(9, 9, 9))
                .cell(PoiBucket.COBBLE, new BlockPos(4, 5, 6))
                .build();

        assertEquals(1, s.cellCount(PoiBucket.WOOD_BUILD));
        assertEquals(0, s.cellCount(PoiBucket.RAIL));
        assertEquals(0, s.cellCount(PoiBucket.NATURAL));
        assertEquals(2, s.totalCells());
        assertEquals(Set.of(PoiBucket.WOOD_BUILD, PoiBucket.COBBLE), s.presentBuckets());
        assertEquals(List.of(new BlockPos(1, 2, 3)), s.cells(PoiBucket.WOOD_BUILD));
        assertTrue(s.cells(PoiBucket.SPAWNER).isEmpty());
    }

    @Test
    void signalsAreImmutableSnapshots() {
        PoiSignals.Builder b = PoiSignals.builder().cell(PoiBucket.SPAWNER, new BlockPos(0, 0, 0));
        PoiSignals first = b.build();
        b.cell(PoiBucket.SPAWNER, new BlockPos(1, 0, 0)).habitation(Habitation.BED);
        PoiSignals second = b.build();

        assertEquals(1, first.cellCount(PoiBucket.SPAWNER));
        assertEquals(2, second.cellCount(PoiBucket.SPAWNER));
        assertFalse(first.hasHabitationItem());
        assertTrue(second.hasHabitationItem());
        assertThrows(UnsupportedOperationException.class, () -> first.cells(PoiBucket.SPAWNER).add(new BlockPos(2, 0, 0)));
        assertThrows(UnsupportedOperationException.class, () -> second.habitation().add(Habitation.ANVIL));
        assertThrows(NullPointerException.class, () -> PoiSignals.builder().cell(null, new BlockPos(0, 0, 0)));
        assertThrows(NullPointerException.class, () -> PoiSignals.builder().cell(PoiBucket.RAIL, null));
    }

    @Test
    void signalsDefaultsAndSanitising() {
        PoiSignals empty = PoiSignals.empty();
        assertEquals(0, empty.totalCells());
        assertEquals("minecraft:overworld", empty.dimensionId());
        assertTrue(empty.cavernDimensionEnabled());
        assertEquals(PoiSignals.DEFAULT_PERCEPTION_RADIUS, empty.perceptionRadius(), EXACT);
        assertFalse(empty.wardenVisible());
        assertEquals(0.0D, empty.entityScore(), EXACT);
        assertNotNull(empty.habitation());

        PoiSignals clamped = PoiSignals.builder().sculkShrieker(-2).sculkCatalyst(-1).sculkSensors(-9)
                .reinforcedDeepslate(-4).sculkFamilyWithin12(-3).openness(7.0D).build();
        assertEquals(0, clamped.sculkShriekerCount());
        assertEquals(0, clamped.sculkCatalystCount());
        assertEquals(0, clamped.sculkSensorCount());
        assertEquals(0, clamped.reinforcedDeepslateCount());
        assertEquals(0, clamped.sculkFamilyWithin12());
        assertEquals(1.0D, clamped.opennessC(), EXACT);
        assertEquals(0, PoiScorer.evaluate(clamped).distinctCells());
    }

    @Test
    void aFullRegistryWindowOf512CellsScoresAndCapsCorrectly() {
        PoiSignals.Builder b = PoiSignals.builder();
        PoiBucket[] buckets = evidenceBuckets();
        for (int i = 0; i < 512; i++) {
            b.cell(buckets[i % buckets.length], new BlockPos(i * 2, 30 + i % 7, i % 13));
        }
        PoiScore r = eval(b);
        double allCaps = 0.0D;
        for (PoiBucket bucket : buckets) {
            allCaps += bucket.weight() * bucket.cap();
        }
        assertEquals(512, r.distinctCells());
        assertEquals(allCaps, r.sumW(), EXACT);
        assertTrue(r.weakCounted());
        assertTrue(r.t() <= 1.0D);
        assertEquals(Band.STRUCTURE_CERTAIN, r.band());
    }

    // ------------------------------------------------------------------ threshold boundaries

    /**
     * S = 0.80 boundary, with three non-weak buckets and 6 cells so only the score decides: SPAWNER (4
     * cells, 2 count) + WEB + FOSSIL_GEODE is sumW 4.65 (S 0.788), SPAWNER + WEB + STONE_BUILD is 4.9
     * (S 0.805). Spread cells keep the cluster bonus out of it.
     */
    @Test
    void certainThresholdSitsAtEightyPercentStructureScore() {
        Grid g = new Grid(7);
        PoiSignals.Builder below = PoiSignals.builder();
        add(below, g, PoiBucket.SPAWNER, 4);
        add(below, g, PoiBucket.WEB, 1);
        add(below, g, PoiBucket.FOSSIL_GEODE, 1);
        PoiScore lo = eval(below);
        assertEquals(6, lo.distinctCells());
        assertEquals(3, lo.nonWeakBuckets());
        assertEquals(4.65D, lo.sumW(), EXACT);
        assertFalse(lo.clusterBonus());
        assertTrue(lo.s() < PoiScorer.CERTAIN_S_MIN);
        assertEquals(Band.POSSIBLE, lo.band());

        g = new Grid(7);
        PoiSignals.Builder above = PoiSignals.builder();
        add(above, g, PoiBucket.SPAWNER, 4);
        add(above, g, PoiBucket.WEB, 1);
        add(above, g, PoiBucket.STONE_BUILD, 1);
        PoiScore hi = eval(above);
        assertEquals(4.9D, hi.sumW(), EXACT);
        assertFalse(hi.clusterBonus());
        assertTrue(hi.s() >= PoiScorer.CERTAIN_S_MIN);
        assertEquals(Band.STRUCTURE_CERTAIN, hi.band());
    }

    /** The cluster bonus is part of S, so it can carry a candidate over the certain line. */
    @Test
    void clusterBonusCanLiftACandidateOverTheCertainLine() {
        Grid g = new Grid(1);
        PoiSignals.Builder b = PoiSignals.builder();
        add(b, g, PoiBucket.SPAWNER, 4);
        add(b, g, PoiBucket.WEB, 1);
        add(b, g, PoiBucket.FOSSIL_GEODE, 1);
        PoiScore r = eval(b);

        assertEquals(4.65D, r.sumW(), EXACT);
        assertTrue(r.clusterBonus());
        assertEquals(withBonus(sOf(4.65D)), r.s(), EXACT);
        assertTrue(sOf(4.65D) < PoiScorer.CERTAIN_S_MIN);
        assertTrue(r.s() >= PoiScorer.CERTAIN_S_MIN);
        assertEquals(Band.STRUCTURE_CERTAIN, r.band());
    }

    /**
     * T = 0.40 boundary for a candidate that qualifies structurally (4 non-weak buckets): sumW 1.4 is
     * T 0.373, and the same cells packed into one ball reach 0.467 through the bonus.
     */
    @Test
    void possibleThresholdSitsAtFortyPercentTotalScore() {
        PoiSignals.Builder spread = PoiSignals.builder();
        Grid g = new Grid(7);
        add(spread, g, PoiBucket.WOOD_BUILD, 1);
        add(spread, g, PoiBucket.FURNISHING, 1);
        add(spread, g, PoiBucket.FOSSIL_GEODE, 1);
        add(spread, g, PoiBucket.UNCLASSIFIED, 1);
        PoiScore lo = eval(spread);
        assertEquals(1.4D, lo.sumW(), EXACT);
        assertEquals(4, lo.nonWeakBuckets());
        assertFalse(lo.clusterBonus());
        assertTrue(lo.t() < PoiScorer.T_POSSIBLE);
        assertEquals(Band.NONE, lo.band());
        assertFalse(lo.possibleGate());

        PoiSignals.Builder packed = PoiSignals.builder();
        g = new Grid(1);
        add(packed, g, PoiBucket.WOOD_BUILD, 1);
        add(packed, g, PoiBucket.FURNISHING, 1);
        add(packed, g, PoiBucket.FOSSIL_GEODE, 1);
        add(packed, g, PoiBucket.UNCLASSIFIED, 1);
        PoiScore hi = eval(packed);
        assertTrue(hi.clusterBonus());
        assertEquals(withBonus(sOf(1.4D)), hi.t(), EXACT);
        assertTrue(hi.t() >= PoiScorer.T_POSSIBLE);
        assertEquals(Band.POSSIBLE, hi.band());
        assertTrue(hi.possibleGate());
    }

    // ------------------------------------------------------------------ PoiSignals extras

    /** The window sweep hands out mutable positions and reuses them; the snapshot must not alias them. */
    @Test
    void signalsCopyMutablePositionsAtInsertion() {
        BlockPos.Mutable cursor = new BlockPos.Mutable(3, 40, 5);
        PoiSignals.Builder b = PoiSignals.builder().cell(PoiBucket.SPAWNER, cursor);
        cursor.set(9, 41, 9);
        b.cell(PoiBucket.CONTAINER, cursor);
        cursor.set(0, 0, 0);
        PoiSignals s = b.build();

        assertEquals(List.of(new BlockPos(3, 40, 5)), s.cells(PoiBucket.SPAWNER));
        assertEquals(List.of(new BlockPos(9, 41, 9)), s.cells(PoiBucket.CONTAINER));
    }

    @Test
    void signalsBulkInsertKeepsOrderAndFirstBucketWins() {
        List<BlockPos> line = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            line.add(new BlockPos(x, 10, 0));
        }
        PoiSignals s = PoiSignals.builder()
                .cells(PoiBucket.RAIL, line)
                .cells(PoiBucket.WEB, List.of(new BlockPos(2, 10, 0), new BlockPos(7, 10, 0)))
                .build();

        assertEquals(line, s.cells(PoiBucket.RAIL));
        assertEquals(List.of(new BlockPos(7, 10, 0)), s.cells(PoiBucket.WEB));
        assertEquals(6, s.totalCells());
        assertThrows(NullPointerException.class, () -> PoiSignals.builder().cells(PoiBucket.RAIL, null));
        assertThrows(UnsupportedOperationException.class, () -> s.presentBuckets().add(PoiBucket.SPAWNER));
        assertThrows(UnsupportedOperationException.class,
                () -> PoiSignals.empty().presentBuckets().add(PoiBucket.SPAWNER));
    }

    @Test
    void signalsSnapshotTheCavernDimensionListAtBuildTime() {
        Set<String> configured = new HashSet<>(Set.of("minecraft:overworld"));
        PoiSignals.Builder b = PoiSignals.builder().dimensionId("minecraft:the_nether").cavernDimensions(configured);
        configured.add("minecraft:the_nether");
        assertFalse(b.build().cavernDimensionEnabled());

        assertThrows(NullPointerException.class, () -> PoiSignals.builder().dimensionId(null));
        assertThrows(NullPointerException.class, () -> PoiSignals.builder().cavernDimensions(null));
        assertThrows(NullPointerException.class, () -> PoiSignals.builder().habitation(null));
        assertThrows(NullPointerException.class, () -> PoiScorer.evaluate(null));
    }

    // ------------------------------------------------------------------ reference model

    /** A random snapshot kept as plain data so a reference evaluation can be recomputed from it. */
    private static final class Spec {
        final List<PoiBucket> buckets = new ArrayList<>();
        final List<BlockPos> positions = new ArrayList<>();
        final Set<Habitation> habitation = EnumSet.noneOf(Habitation.class);
        boolean warden;
        int reinforced;
        int shrieker;
        int catalyst;
        int sensors;
        int family;
        double entity;
        boolean opennessValid;
        double c;
        double radius;
        boolean dimensionEnabled;

        PoiSignals build() {
            PoiSignals.Builder b = PoiSignals.builder().wardenVisible(warden).reinforcedDeepslate(reinforced)
                    .sculkShrieker(shrieker).sculkCatalyst(catalyst).sculkSensors(sensors)
                    .sculkFamilyWithin12(family).entityScore(entity).perceptionRadius(radius)
                    .cavernDimensionEnabled(dimensionEnabled);
            if (opennessValid) {
                b.openness(c);
            } else {
                b.opennessUnavailable();
            }
            for (Habitation h : habitation) {
                b.habitation(h);
            }
            for (int i = 0; i < buckets.size(); i++) {
                b.cell(buckets.get(i), positions.get(i));
            }
            return b.build();
        }
    }

    private static Spec randomSpec(SplittableRandom rnd) {
        Spec sp = new Spec();
        List<PoiBucket> pool = new ArrayList<>(List.of(evidenceBuckets()));
        for (int i = pool.size() - 1; i > 0; i--) {
            Collections.swap(pool, i, rnd.nextInt(i + 1));
        }
        int mode = rnd.nextInt(3);
        Set<Long> used = new HashSet<>();
        int active = rnd.nextInt(6);
        for (int k = 0; k < active; k++) {
            PoiBucket bucket = pool.get(k);
            int n = 1 + rnd.nextInt(bucket.cap() + 3);
            for (int i = 0; i < n; i++) {
                BlockPos p;
                do {
                    p = switch (mode) {
                        case 0 -> new BlockPos(rnd.nextInt(14), 20 + rnd.nextInt(8), rnd.nextInt(14));
                        case 1 -> new BlockPos(rnd.nextInt(24), 30, rnd.nextInt(24));
                        default -> new BlockPos(rnd.nextInt(30) * 7, 30, rnd.nextInt(30) * 7);
                    };
                } while (!used.add(p.asLong()));
                sp.buckets.add(bucket);
                sp.positions.add(p);
            }
        }
        if (rnd.nextBoolean()) {
            for (Habitation h : Habitation.values()) {
                if (rnd.nextInt(5) == 0) {
                    sp.habitation.add(h);
                }
            }
        }
        sp.warden = rnd.nextInt(30) == 0;
        sp.reinforced = rnd.nextInt(40) == 0 ? 1 + rnd.nextInt(2) : 0;
        sp.shrieker = rnd.nextInt(40) == 0 ? 1 + rnd.nextInt(2) : 0;
        sp.catalyst = rnd.nextInt(40) == 0 ? 1 + rnd.nextInt(2) : 0;
        sp.sensors = rnd.nextInt(15) == 0 ? 1 + rnd.nextInt(3) : 0;
        sp.family = rnd.nextInt(8) == 0 ? rnd.nextInt(10) : 0;
        sp.entity = rnd.nextInt(5) < 3 ? 0.0D : rnd.nextDouble() * 0.9D;
        sp.opennessValid = rnd.nextInt(4) != 0;
        sp.c = rnd.nextInt(5) < 2 ? 0.0D : rnd.nextDouble();
        sp.radius = new double[]{8.0D, 11.999D, 12.0D, 16.0D, 32.0D}[rnd.nextInt(5)];
        sp.dimensionEnabled = rnd.nextInt(7) != 0;
        return sp;
    }

    private static final class Expected {
        double sumW;
        double s;
        double c;
        double e;
        double t;
        int cells;
        int nonWeak;
        boolean strong;
        boolean gate;
        boolean cluster;
        boolean weakCounted;
        int distinctBuckets;
        boolean habitationLike;
        boolean downgraded;
        String trigger;
        Band band;
        double[] centroid;
    }

    /** Design 6.2 and 6.3 re-derived from the text, with a brute-force Euclidean cluster test. */
    private static Expected expected(Spec sp) {
        Map<PoiBucket, List<BlockPos>> byBucket = new EnumMap<>(PoiBucket.class);
        for (int i = 0; i < sp.buckets.size(); i++) {
            byBucket.computeIfAbsent(sp.buckets.get(i), k -> new ArrayList<>()).add(sp.positions.get(i));
        }
        Expected x = new Expected();
        for (Map.Entry<PoiBucket, List<BlockPos>> en : byBucket.entrySet()) {
            PoiBucket b = en.getKey();
            if (b.strength() == PoiBucket.Strength.WEAK) {
                continue;
            }
            x.nonWeak++;
            if (b.strength() == PoiBucket.Strength.STRONG && en.getValue().size() >= b.strongMinCells()) {
                x.strong = true;
            }
        }
        boolean weakIn = x.nonWeak >= 2;
        List<BlockPos> evidence = new ArrayList<>();
        int weakBuckets = 0;
        for (Map.Entry<PoiBucket, List<BlockPos>> en : byBucket.entrySet()) {
            PoiBucket b = en.getKey();
            boolean weak = b.strength() == PoiBucket.Strength.WEAK;
            if (weak && !weakIn) {
                continue;
            }
            if (weak) {
                weakBuckets++;
            }
            x.sumW += b.weight() * Math.min(en.getValue().size(), b.cap());
            evidence.addAll(en.getValue());
        }
        x.cells = evidence.size();
        x.weakCounted = weakBuckets > 0;
        x.distinctBuckets = x.nonWeak + weakBuckets;

        x.s = 1.0D - Math.exp(-x.sumW / 3.0D);
        for (BlockPos a : evidence) {
            int within = 0;
            for (BlockPos o : evidence) {
                double dx = a.getX() - o.getX();
                double dy = a.getY() - o.getY();
                double dz = a.getZ() - o.getZ();
                if (Math.sqrt(dx * dx + dy * dy + dz * dz) <= 6.0D) {
                    within++;
                }
            }
            if (within >= 4) {
                x.cluster = true;
                break;
            }
        }
        if (x.cluster) {
            x.s = 1.0D - (1.0D - x.s) * 0.85D;
        }
        x.c = sp.opennessValid && sp.dimensionEnabled && sp.radius >= 12.0D ? sp.c : 0.0D;
        x.e = Math.min(0.6D, sp.entity);
        x.t = 1.0D - (1.0D - x.s) * (1.0D - 0.85D * x.c) * (1.0D - x.e);

        boolean qualifies = x.nonWeak >= 2 || x.strong || x.e >= 0.4D;
        boolean cavernOnly = x.s < 0.25D && x.c >= 0.47D;
        x.gate = x.t >= 0.40D && (qualifies || cavernOnly);
        boolean certain = x.s >= 0.80D && x.cells >= 6 && x.nonWeak >= 3;
        boolean anyImmune = false;
        for (PoiBucket immune : new PoiBucket[]{PoiBucket.SPAWNER, PoiBucket.SCULK_STRUCT, PoiBucket.RAIL, PoiBucket.WEB}) {
            anyImmune |= byBucket.containsKey(immune);
        }
        x.habitationLike = !sp.habitation.isEmpty() && !anyImmune;

        List<String> triggers = new ArrayList<>();
        if (sp.warden) {
            triggers.add("warden_visible");
        }
        if (sp.reinforced >= 1) {
            triggers.add("reinforced_deepslate");
        }
        if (sp.shrieker >= 1) {
            triggers.add("sculk_shrieker");
        }
        if (sp.catalyst >= 1) {
            triggers.add("sculk_catalyst");
        }
        if (sp.sensors >= 2) {
            triggers.add("sculk_sensors");
        }
        if (sp.family >= 6 && x.c >= 0.3D) {
            triggers.add("sculk_family_cavern");
        }
        x.trigger = String.join("+", triggers);

        if (!triggers.isEmpty()) {
            x.band = Band.MANDATORY;
        } else if (certain) {
            x.band = x.habitationLike ? Band.POSSIBLE : Band.STRUCTURE_CERTAIN;
            x.downgraded = x.habitationLike;
        } else if (x.gate) {
            x.band = qualifies ? Band.POSSIBLE : Band.CAVERN_ONLY;
        } else {
            x.band = Band.NONE;
        }
        if (!evidence.isEmpty()) {
            double sx = 0.0D;
            double sy = 0.0D;
            double sz = 0.0D;
            for (BlockPos p : evidence) {
                sx += p.getX() + 0.5D;
                sy += p.getY() + 0.5D;
                sz += p.getZ() + 0.5D;
            }
            x.centroid = new double[]{sx / evidence.size(), sy / evidence.size(), sz / evidence.size()};
        }
        return x;
    }

    @Test
    void scorerMatchesAnIndependentReferenceModelOnRandomSnapshots() {
        SplittableRandom rnd = new SplittableRandom(0xC0FFEEL);
        Map<Band, Integer> seen = new EnumMap<>(Band.class);
        int clustered = 0;
        int unclustered = 0;
        int weakAdmitted = 0;
        int downgraded = 0;
        for (int trial = 0; trial < 30000; trial++) {
            Spec sp = randomSpec(rnd);
            PoiScore r = PoiScorer.evaluate(sp.build());
            Expected x = expected(sp);
            String msg = "trial " + trial;

            assertEquals(x.sumW, r.sumW(), 1.0e-12D, msg);
            assertEquals(x.s, r.s(), 1.0e-12D, msg);
            assertEquals(x.c, r.c(), 1.0e-12D, msg);
            assertEquals(x.e, r.e(), 1.0e-12D, msg);
            assertEquals(x.t, r.t(), 1.0e-12D, msg);
            assertEquals(x.cells, r.distinctCells(), msg);
            assertEquals(x.nonWeak, r.nonWeakBuckets(), msg);
            assertEquals(x.distinctBuckets, r.distinctBuckets(), msg);
            assertEquals(x.strong, r.strongBucket(), msg);
            assertEquals(x.cluster, r.clusterBonus(), msg);
            assertEquals(x.weakCounted, r.weakCounted(), msg);
            assertEquals(x.gate, r.possibleGate(), msg);
            assertEquals(x.habitationLike, r.habitationLike(), msg);
            assertEquals(x.downgraded, r.downgradedByHabitation(), msg);
            assertEquals(x.trigger, r.mandatoryTrigger(), msg);
            assertEquals(x.band, r.band(), msg);
            if (x.centroid == null) {
                assertNull(r.centroid(), msg);
            } else {
                assertEquals(x.centroid[0], r.centroid().x, 1.0e-9D, msg);
                assertEquals(x.centroid[1], r.centroid().y, 1.0e-9D, msg);
                assertEquals(x.centroid[2], r.centroid().z, 1.0e-9D, msg);
            }
            // Cross-band invariants that hold whatever the inputs.
            assertEquals(r.band() == Band.MANDATORY, !r.mandatoryTrigger().isEmpty(), msg);
            assertFalse(r.band() == Band.STRUCTURE_CERTAIN && r.habitationLike(), msg);
            if (r.band().isPossibleClass() || r.band() == Band.STRUCTURE_CERTAIN) {
                assertTrue(r.possibleGate(), msg);
            }
            if (r.band() == Band.CAVERN_ONLY) {
                assertTrue(r.c() >= PoiScorer.CAVERN_ONLY_C_MIN - 1.0e-9D, msg);
                assertFalse(r.strongBucket(), msg);
            }

            seen.merge(r.band(), 1, Integer::sum);
            if (r.clusterBonus()) {
                clustered++;
            } else if (r.distinctCells() >= 4) {
                unclustered++;
            }
            if (r.weakCounted()) {
                weakAdmitted++;
            }
            if (r.downgradedByHabitation()) {
                downgraded++;
            }
        }
        // The generator must actually reach every branch, or the comparison proves nothing.
        for (Band band : Band.values()) {
            assertTrue(seen.getOrDefault(band, 0) >= 100, band + " seen " + seen.getOrDefault(band, 0));
        }
        assertTrue(clustered >= 500 && unclustered >= 500, clustered + "/" + unclustered);
        assertTrue(weakAdmitted >= 500, "weak admitted " + weakAdmitted);
        assertTrue(downgraded >= 50, "downgraded " + downgraded);
    }

    /** Naive model of "2 of the last 3 accepted evaluations, none older than 240 ticks". */
    @Test
    void hysteresisMatchesANaiveModelOnRandomTickSequences() {
        SplittableRandom rnd = new SplittableRandom(0x7A11L);
        long[] gaps = {0, 1, 5, 19, 20, 21, 39, 40, 100, 239, 240, 241, 500, 3000};
        for (int trial = 0; trial < 400; trial++) {
            Hysteresis h = new Hysteresis();
            List<long[]> accepted = new ArrayList<>();
            long tick = rnd.nextInt(1000);
            for (int step = 0; step < 60; step++) {
                long next = tick + gaps[rnd.nextInt(gaps.length)];
                if (rnd.nextInt(25) == 0) {
                    next = tick - 1 - rnd.nextInt(300);
                }
                tick = next;
                boolean hit = rnd.nextInt(5) < 2;

                boolean ignored = false;
                if (!accepted.isEmpty()) {
                    long last = accepted.get(accepted.size() - 1)[0];
                    if (tick < last) {
                        accepted.clear();
                    } else if (tick - last < 20) {
                        ignored = true;
                    }
                }
                if (!ignored) {
                    accepted.add(new long[]{tick, hit ? 1 : 0});
                    while (accepted.size() > 3) {
                        accepted.remove(0);
                    }
                }
                int hits = 0;
                for (long[] entry : accepted) {
                    if (entry[1] == 1 && tick - entry[0] <= 240) {
                        hits++;
                    }
                }
                String msg = "trial " + trial + " step " + step + " tick " + tick;
                assertEquals(hits >= 2, h.record(tick, hit), msg);
                assertEquals(hits, h.hits(tick), msg);
                assertEquals(accepted.size(), h.evaluations(), msg);
                assertEquals(accepted.get(accepted.size() - 1)[0], h.lastTick(), msg);
            }
        }
    }

    @Test
    void aSparseWindowOfManyCellsScoresWithoutAClusterAndStaysBounded() {
        // 512 cells on a 7-block lattice: no ball of radius 6 holds two of them, the worst case for the
        // cluster search. The evaluation must still finish and cap the weights.
        PoiSignals.Builder b = PoiSignals.builder();
        PoiBucket[] buckets = evidenceBuckets();
        for (int i = 0; i < 512; i++) {
            b.cell(buckets[i % buckets.length], new BlockPos((i % 32) * 7, 30, (i / 32) * 7));
        }
        PoiSignals signals = b.build();
        for (int round = 0; round < 20; round++) {
            PoiScore r = PoiScorer.evaluate(signals);
            assertEquals(512, r.distinctCells());
            assertFalse(r.clusterBonus());
            assertTrue(r.s() <= 1.0D && r.t() <= 1.0D);
        }
    }
}
