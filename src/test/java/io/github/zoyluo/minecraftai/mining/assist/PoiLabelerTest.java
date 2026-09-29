package io.github.zoyluo.minecraftai.mining.assist;

import io.github.zoyluo.minecraftai.mining.assist.PoiScorer.Band;
import io.github.zoyluo.minecraftai.mining.assist.PoiScorer.PoiScore;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import net.minecraft.core.BlockPos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoiLabelerTest {
    private int nextX;

    /** Adds {@code n} distinct cells to the bucket; positions never repeat inside one test run. */
    private PoiSignals.Builder add(PoiSignals.Builder b, PoiBucket bucket, int n) {
        for (int i = 0; i < n; i++) {
            b.cell(bucket, new BlockPos(nextX++, 40, 0));
        }
        return b;
    }

    private static String label(PoiSignals.Builder b) {
        return PoiLabeler.label(b.build());
    }

    @Test
    void mineshaftFromRailsCobwebPlanksOrFence() {
        assertEquals(PoiLabeler.MINESHAFT, label(add(PoiSignals.builder(), PoiBucket.RAIL, 3)));
        assertEquals(PoiLabeler.MINESHAFT, label(add(PoiSignals.builder(), PoiBucket.WEB, 1)));
        assertEquals(PoiLabeler.MINESHAFT, label(add(PoiSignals.builder(), PoiBucket.WOOD_BUILD, 8)));
        assertEquals(PoiLabeler.MINESHAFT, label(add(add(add(PoiSignals.builder(),
                PoiBucket.WOOD_BUILD, 8), PoiBucket.RAIL, 3), PoiBucket.WEB, 3)));
    }

    @Test
    void mineshaftCaveSpiderSpawnerRoomStaysMineshaft() {
        // Spawner among cobwebs and planks, no mossy cobblestone: not a dungeon.
        PoiSignals.Builder b = add(add(add(PoiSignals.builder(), PoiBucket.SPAWNER, 1), PoiBucket.WEB, 6),
                PoiBucket.WOOD_BUILD, 4);
        assertEquals(PoiLabeler.MINESHAFT, label(b));
    }

    @Test
    void dungeonFromSpawnerPlusMossyStone() {
        PoiSignals.Builder b = add(add(add(PoiSignals.builder().mossyStone(true),
                PoiBucket.SPAWNER, 1), PoiBucket.STONE_BUILD, 8), PoiBucket.CONTAINER, 2);
        assertEquals(PoiLabeler.DUNGEON, label(b));
    }

    @Test
    void dungeonNeedsBothSpawnerAndMossyStone() {
        assertEquals(PoiLabeler.STRUCTURE_UNKNOWN, label(add(PoiSignals.builder(), PoiBucket.SPAWNER, 2)));
        assertEquals(PoiLabeler.STRUCTURE_UNKNOWN,
                label(add(PoiSignals.builder().mossyStone(true), PoiBucket.STONE_BUILD, 8)));
    }

    @Test
    void ancientCityFromDeepslateBuildPlusSculk() {
        assertEquals(PoiLabeler.ANCIENT_CITY, label(add(add(PoiSignals.builder(),
                PoiBucket.DEEPSLATE_BUILD, 6), PoiBucket.SCULK_STRUCT, 1)));
        // Sculk counts also arrive without cells (plain sculk and veins are natural blocks).
        assertEquals(PoiLabeler.ANCIENT_CITY, label(add(PoiSignals.builder().sculkFamilyWithin12(9),
                PoiBucket.DEEPSLATE_BUILD, 4)));
        assertEquals(PoiLabeler.ANCIENT_CITY, label(add(PoiSignals.builder().sculkShrieker(1),
                PoiBucket.DEEPSLATE_BUILD, 6)));
        assertEquals(PoiLabeler.ANCIENT_CITY, label(add(PoiSignals.builder().reinforcedDeepslate(1),
                PoiBucket.DEEPSLATE_BUILD, 1)));
    }

    @Test
    void ancientCityNeedsBothDeepslateBuildAndSculk() {
        assertEquals(PoiLabeler.STRUCTURE_UNKNOWN, label(add(PoiSignals.builder(), PoiBucket.DEEPSLATE_BUILD, 8)));
        assertEquals(PoiLabeler.STRUCTURE_UNKNOWN, label(add(PoiSignals.builder(), PoiBucket.SCULK_STRUCT, 3)));
    }

    @Test
    void trialChamberFromTuffCopperOrVault() {
        assertEquals(PoiLabeler.TRIAL_CHAMBER, label(add(PoiSignals.builder(), PoiBucket.COPPER_TUFF_BUILD, 1)));
        assertEquals(PoiLabeler.TRIAL_CHAMBER, label(PoiSignals.builder().vault(true)));
        assertEquals(PoiLabeler.TRIAL_CHAMBER, label(add(add(PoiSignals.builder().vault(true),
                PoiBucket.SPAWNER, 1), PoiBucket.CONTAINER, 2)));
    }

    @Test
    void strongholdFromIronBarsPlusStoneBricks() {
        assertEquals(PoiLabeler.STRONGHOLD, label(add(PoiSignals.builder().ironBars(true).stoneBricks(true),
                PoiBucket.STONE_BUILD, 6)));
        assertEquals(PoiLabeler.STRUCTURE_UNKNOWN, label(add(PoiSignals.builder().ironBars(true),
                PoiBucket.STONE_BUILD, 6)));
        assertEquals(PoiLabeler.STRUCTURE_UNKNOWN, label(add(PoiSignals.builder().stoneBricks(true),
                PoiBucket.STONE_BUILD, 6)));
    }

    @Test
    void fortressOrBastionFromNetherBricksOrBlackstone() {
        assertEquals(PoiLabeler.FORTRESS_BASTION, label(PoiSignals.builder().netherBricks(true)));
        assertEquals(PoiLabeler.FORTRESS_BASTION, label(PoiSignals.builder().blackstone(true)));
        assertEquals(PoiLabeler.FORTRESS_BASTION, label(add(PoiSignals.builder().netherBricks(true),
                PoiBucket.STONE_BUILD, 8)));
    }

    @Test
    void unknownWhenNothingMatches() {
        assertEquals(PoiLabeler.STRUCTURE_UNKNOWN, PoiLabeler.label(PoiSignals.empty()));
        assertEquals(PoiLabeler.STRUCTURE_UNKNOWN, label(add(PoiSignals.builder(), PoiBucket.UNCLASSIFIED, 4)));
        assertEquals(PoiLabeler.STRUCTURE_UNKNOWN, label(add(PoiSignals.builder(), PoiBucket.FURNISHING, 4)));
        assertEquals(PoiLabeler.STRUCTURE_UNKNOWN, label(add(PoiSignals.builder(), PoiBucket.FOSSIL_GEODE, 4)));
        assertEquals(PoiLabeler.STRUCTURE_UNKNOWN, label(add(PoiSignals.builder(), PoiBucket.CONTAINER, 3)));
        assertEquals(PoiLabeler.STRUCTURE_UNKNOWN, label(add(PoiSignals.builder(), PoiBucket.MODDED, 6)));
    }

    @Test
    void weakBucketsNeverInfluenceTheLabel() {
        assertEquals(PoiLabeler.STRUCTURE_UNKNOWN, label(add(add(PoiSignals.builder(),
                PoiBucket.LIGHT_DRESSING, 3), PoiBucket.COBBLE, 10)));
    }

    /** The design lists mineshaft materials first, but they also occur elsewhere: specific wins. */
    @Test
    void specificStructuresBeatTheGenericMineshaftMaterials() {
        // Trial chambers contain cobwebs.
        assertEquals(PoiLabeler.TRIAL_CHAMBER, label(add(add(PoiSignals.builder().vault(true),
                PoiBucket.WEB, 4), PoiBucket.COPPER_TUFF_BUILD, 6)));
        // Stronghold libraries have planks and cobwebs.
        assertEquals(PoiLabeler.STRONGHOLD, label(add(add(add(PoiSignals.builder().ironBars(true).stoneBricks(true),
                PoiBucket.WOOD_BUILD, 6), PoiBucket.WEB, 3), PoiBucket.STONE_BUILD, 6)));
        // A dungeon beside wooden supports.
        assertEquals(PoiLabeler.DUNGEON, label(add(add(PoiSignals.builder().mossyStone(true),
                PoiBucket.SPAWNER, 1), PoiBucket.WOOD_BUILD, 3)));
        // Bastion or fortress blocks beat planks and rails.
        assertEquals(PoiLabeler.FORTRESS_BASTION, label(add(add(PoiSignals.builder().netherBricks(true),
                PoiBucket.WOOD_BUILD, 3), PoiBucket.RAIL, 2)));
    }

    /**
     * Blackstone is a natural block (Nether, Terralith caves) and the flag is presence-only, so it must
     * not relabel an overworld structure that has its own signature.
     */
    @Test
    void naturalBlackstoneNeverOverridesAMoreSpecificStructure() {
        assertEquals(PoiLabeler.MINESHAFT, label(add(add(PoiSignals.builder().blackstone(true),
                PoiBucket.RAIL, 3), PoiBucket.WEB, 3)));
        assertEquals(PoiLabeler.MINESHAFT, label(add(PoiSignals.builder().blackstone(true), PoiBucket.WOOD_BUILD, 6)));
        assertEquals(PoiLabeler.DUNGEON, label(add(add(PoiSignals.builder().blackstone(true).mossyStone(true),
                PoiBucket.SPAWNER, 1), PoiBucket.STONE_BUILD, 6)));
        assertEquals(PoiLabeler.STRONGHOLD, label(add(PoiSignals.builder().blackstone(true).ironBars(true)
                .stoneBricks(true), PoiBucket.STONE_BUILD, 6)));
        assertEquals(PoiLabeler.TRIAL_CHAMBER, label(add(PoiSignals.builder().blackstone(true).vault(true),
                PoiBucket.COPPER_TUFF_BUILD, 3)));
        assertEquals(PoiLabeler.ANCIENT_CITY, label(add(add(PoiSignals.builder().blackstone(true),
                PoiBucket.DEEPSLATE_BUILD, 4), PoiBucket.SCULK_STRUCT, 1)));
        // Nether bricks are not natural anywhere but a fortress, so they still win over the generic materials.
        assertEquals(PoiLabeler.FORTRESS_BASTION, label(add(PoiSignals.builder().blackstone(true).netherBricks(true),
                PoiBucket.WOOD_BUILD, 6)));
        // Alone (a bastion has no planks, rails or cobwebs) blackstone still gives the bastion label.
        assertEquals(PoiLabeler.FORTRESS_BASTION, label(add(PoiSignals.builder().blackstone(true),
                PoiBucket.STONE_BUILD, 6)));
    }

    /**
     * Exhaustive check of the documented precedence: every combination of the 13 signals maps to the
     * first matching rule in the order ancient_city, trial_chamber, nether bricks, dungeon, stronghold,
     * mineshaft, blackstone, unknown.
     */
    @Test
    void everySignalCombinationFollowsTheDocumentedPrecedence() {
        for (int mask = 0; mask < (1 << 13); mask++) {
            boolean deepslate = (mask & 1) != 0;
            boolean sculkCell = (mask & 2) != 0;
            boolean vault = (mask & 4) != 0;
            boolean copper = (mask & 8) != 0;
            boolean netherBricks = (mask & 16) != 0;
            boolean spawner = (mask & 32) != 0;
            boolean mossy = (mask & 64) != 0;
            boolean bars = (mask & 128) != 0;
            boolean bricks = (mask & 256) != 0;
            boolean rail = (mask & 512) != 0;
            boolean web = (mask & 1024) != 0;
            boolean wood = (mask & 2048) != 0;
            boolean blackstone = (mask & 4096) != 0;

            nextX = 0;
            PoiSignals.Builder b = PoiSignals.builder().vault(vault).netherBricks(netherBricks).mossyStone(mossy)
                    .ironBars(bars).stoneBricks(bricks).blackstone(blackstone);
            add(b, PoiBucket.DEEPSLATE_BUILD, deepslate ? 1 : 0);
            add(b, PoiBucket.SCULK_STRUCT, sculkCell ? 1 : 0);
            add(b, PoiBucket.COPPER_TUFF_BUILD, copper ? 1 : 0);
            add(b, PoiBucket.SPAWNER, spawner ? 1 : 0);
            add(b, PoiBucket.RAIL, rail ? 1 : 0);
            add(b, PoiBucket.WEB, web ? 1 : 0);
            add(b, PoiBucket.WOOD_BUILD, wood ? 1 : 0);

            String expected;
            if (deepslate && sculkCell) {
                expected = PoiLabeler.ANCIENT_CITY;
            } else if (vault || copper) {
                expected = PoiLabeler.TRIAL_CHAMBER;
            } else if (netherBricks) {
                expected = PoiLabeler.FORTRESS_BASTION;
            } else if (spawner && mossy) {
                expected = PoiLabeler.DUNGEON;
            } else if (bars && bricks) {
                expected = PoiLabeler.STRONGHOLD;
            } else if (rail || web || wood) {
                expected = PoiLabeler.MINESHAFT;
            } else if (blackstone) {
                expected = PoiLabeler.FORTRESS_BASTION;
            } else {
                expected = PoiLabeler.STRUCTURE_UNKNOWN;
            }
            assertEquals(expected, label(b), "mask " + Integer.toBinaryString(mask));
        }
    }

    @Test
    void ancientCityBeatsEveryOtherLabel() {
        PoiSignals.Builder b = PoiSignals.builder().vault(true).mossyStone(true).netherBricks(true)
                .ironBars(true).stoneBricks(true);
        add(b, PoiBucket.SPAWNER, 1);
        add(b, PoiBucket.COPPER_TUFF_BUILD, 2);
        add(b, PoiBucket.DEEPSLATE_BUILD, 4);
        add(b, PoiBucket.SCULK_STRUCT, 1);
        add(b, PoiBucket.RAIL, 2);
        assertEquals(PoiLabeler.ANCIENT_CITY, label(b));
    }

    @Test
    void labelsAreDeterministicAndAlwaysFromTheAdvertisedSet() {
        PoiSignals s = add(add(PoiSignals.builder().mossyStone(true), PoiBucket.SPAWNER, 1),
                PoiBucket.STONE_BUILD, 4).build();
        assertEquals(PoiLabeler.label(s), PoiLabeler.label(s));
        assertTrue(PoiLabeler.ALL_LABELS.contains(PoiLabeler.label(s)));
        assertEquals(7, PoiLabeler.ALL_LABELS.size());
        assertEquals(7, new HashSet<>(PoiLabeler.ALL_LABELS).size());
        for (String label : PoiLabeler.ALL_LABELS) {
            assertTrue(label.matches("[a-z_]+"), label);
        }
        assertThrows(NullPointerException.class, () -> PoiLabeler.label(null));
    }

    /** The calibration fixtures of the scorer carry the label the design expects. */
    @Test
    void labelsMatchTheScorerFixtures() {
        // Mineshaft corridor.
        PoiSignals.Builder mineshaft = PoiSignals.builder();
        add(mineshaft, PoiBucket.WOOD_BUILD, 8);
        add(mineshaft, PoiBucket.RAIL, 3);
        add(mineshaft, PoiBucket.WEB, 3);
        add(mineshaft, PoiBucket.LIGHT_DRESSING, 2);
        PoiSignals m = mineshaft.build();
        assertEquals(Band.STRUCTURE_CERTAIN, PoiScorer.evaluate(m).band());
        assertEquals(PoiLabeler.MINESHAFT, PoiLabeler.label(m));

        // Dungeon.
        PoiSignals.Builder dungeon = PoiSignals.builder().mossyStone(true);
        add(dungeon, PoiBucket.STONE_BUILD, 8);
        add(dungeon, PoiBucket.SPAWNER, 1);
        add(dungeon, PoiBucket.CONTAINER, 2);
        PoiSignals d = dungeon.build();
        assertEquals(Band.STRUCTURE_CERTAIN, PoiScorer.evaluate(d).band());
        assertEquals(PoiLabeler.DUNGEON, PoiLabeler.label(d));

        // Ancient city edge.
        PoiSignals.Builder city = PoiSignals.builder().sculkShrieker(1);
        add(city, PoiBucket.DEEPSLATE_BUILD, 6);
        add(city, PoiBucket.LIGHT_DRESSING, 2);
        add(city, PoiBucket.SCULK_STRUCT, 1);
        PoiSignals c = city.build();
        PoiScore cityScore = PoiScorer.evaluate(c);
        assertEquals(Band.MANDATORY, cityScore.band());
        assertEquals(PoiLabeler.ANCIENT_CITY, PoiLabeler.label(c));

        // Trial chamber.
        PoiSignals.Builder trial = PoiSignals.builder().vault(true);
        add(trial, PoiBucket.COPPER_TUFF_BUILD, 6);
        add(trial, PoiBucket.SPAWNER, 1);
        add(trial, PoiBucket.CONTAINER, 2);
        PoiSignals t = trial.build();
        assertEquals(Band.STRUCTURE_CERTAIN, PoiScorer.evaluate(t).band());
        assertEquals(PoiLabeler.TRIAL_CHAMBER, PoiLabeler.label(t));
    }
}
