package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.config.EffectiveRule;
import dev.spawnbotswrapper.inhabitants.util.SplitMix64;
import dev.spawnbotswrapper.inhabitants.util.StableHash;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class StructureRollTest {

    /** A volume/blocksPerBot pair generous enough to never cap the range below the global ceiling. */
    private static final long AMPLE_VOLUME = 1_000_000L;
    private static final double AMPLE_BLOCKS_PER_BOT = 1.0;

    private static EffectiveRule rule(double chance, int min) {
        return new EffectiveRule(chance, min, "default", "default");
    }

    @Test
    void chanceZeroIsNeverOccupiedAndChanceOneAlwaysIs() {
        SplitMix64 seeds = new SplitMix64(1);
        for (int i = 0; i < 20_000; i++) {
            long seed = seeds.nextLong();
            assertFalse(StructureRoll.of(rule(0.0, 1), seed, AMPLE_VOLUME, AMPLE_BLOCKS_PER_BOT, ForceMode.ROLL, "RANDOM").occupied());
            assertTrue(StructureRoll.of(rule(1.0, 1), seed, AMPLE_VOLUME, AMPLE_BLOCKS_PER_BOT, ForceMode.ROLL, "RANDOM").occupied());
        }
    }

    @Test
    void verdictIsUniformRollBelowChanceAndTheRollIsRecorded() {
        SplitMix64 seeds = new SplitMix64(2);
        for (int i = 0; i < 5_000; i++) {
            long seed = seeds.nextLong();
            StructureRoll r = StructureRoll.of(rule(0.4, 1), seed, AMPLE_VOLUME, AMPLE_BLOCKS_PER_BOT, ForceMode.ROLL, "RANDOM");
            double expected = new SplitMix64(StableHash.combine(seed, 1)).nextDouble();
            assertEquals(expected, r.roll());
            assertEquals(expected < 0.4, r.occupied());
            assertEquals(0.4, r.chance());
        }
    }

    @Test
    void theComparisonIsStrictlyLessThanSoAChanceExactlyEqualToTheRollIsNotOccupied() {
        long seed = 123;
        double roll = new SplitMix64(StableHash.combine(seed, 1)).nextDouble();
        assertFalse(StructureRoll.of(rule(roll, 1), seed, AMPLE_VOLUME, AMPLE_BLOCKS_PER_BOT, ForceMode.ROLL, "RANDOM").occupied(),
                "u < chance must be false when chance == u exactly");
        assertTrue(StructureRoll.of(rule(Math.nextUp(roll), 1), seed, AMPLE_VOLUME, AMPLE_BLOCKS_PER_BOT, ForceMode.ROLL, "RANDOM").occupied());
    }

    @Test
    void botCountCoversTheWholeInclusiveRangeAndNothingOutside() {
        SplitMix64 seeds = new SplitMix64(3);
        int[] hits = new int[8];
        // volume 7, 1 block/bot -> sizeCappedMax = 7, so with minBots=3 the draw covers [3,7] inclusive.
        for (int i = 0; i < 20_000; i++) {
            int n = StructureRoll.of(rule(1.0, 3), seeds.nextLong(), 7, 1.0, ForceMode.ROLL, "RANDOM").botCount();
            assertTrue(n >= 3 && n <= 7, "count out of range: " + n);
            hits[n]++;
        }
        for (int n = 3; n <= 7; n++) {
            assertTrue(hits[n] > 20_000 / 5 * 0.85, "count " + n + " under-represented: " + hits[n]);
        }
    }

    @Test
    void minEqualsMaxIsExact() {
        SplitMix64 seeds = new SplitMix64(4);
        // volume 4, 1 block/bot -> sizeCappedMax = 4, matching minBots=4 exactly.
        for (int i = 0; i < 1000; i++) {
            assertEquals(4, StructureRoll.of(rule(1.0, 4), seeds.nextLong(), 4, 1.0, ForceMode.ROLL, "RANDOM").botCount());
        }
    }

    @Test
    void forcedModesIgnoreTheVerdictButNotTheRecordedRoll() {
        long seed = 12345;
        StructureRoll natural = StructureRoll.of(rule(0.5, 1), seed, AMPLE_VOLUME, AMPLE_BLOCKS_PER_BOT, ForceMode.ROLL, "DETERMINISTIC");
        StructureRoll occupied = StructureRoll.of(rule(0.0, 1), seed, AMPLE_VOLUME, AMPLE_BLOCKS_PER_BOT, ForceMode.OCCUPIED, "DETERMINISTIC");
        StructureRoll abandoned = StructureRoll.of(rule(1.0, 1), seed, AMPLE_VOLUME, AMPLE_BLOCKS_PER_BOT, ForceMode.ABANDONED, "DETERMINISTIC");
        assertTrue(occupied.occupied());
        assertFalse(abandoned.occupied());
        assertEquals("DETERMINISTIC", natural.source());
        assertEquals("ADMIN_FORCED", occupied.source());
        assertEquals("ADMIN_FORCED", abandoned.source());
        // the underlying roll and count are the same numbers the natural roll would have produced
        assertEquals(natural.roll(), occupied.roll());
        assertEquals(natural.botCount(), occupied.botCount());
        assertEquals(natural.botCount(), abandoned.botCount());
    }

    @Test
    void sameSeedSameRollDifferentSeedUsuallyDifferent() {
        assertEquals(StructureRoll.of(rule(0.5, 1), 77, AMPLE_VOLUME, AMPLE_BLOCKS_PER_BOT, ForceMode.ROLL, "RANDOM"),
                StructureRoll.of(rule(0.5, 1), 77, AMPLE_VOLUME, AMPLE_BLOCKS_PER_BOT, ForceMode.ROLL, "RANDOM"));
        Set<Double> rolls = new HashSet<>();
        for (long s = 0; s < 100; s++) {
            rolls.add(StructureRoll.of(rule(0.5, 1), s, AMPLE_VOLUME, AMPLE_BLOCKS_PER_BOT, ForceMode.ROLL, "RANDOM").roll());
        }
        assertEquals(100, rolls.size());
    }

    @Test
    void botSeedsAreDistinctPerIndexAndStable() {
        Set<Long> seeds = new HashSet<>();
        for (int i = 0; i < 64; i++) {
            assertTrue(seeds.add(StructureRoll.botSeed(555, i)));
            assertEquals(StructureRoll.botSeed(555, i), StructureRoll.botSeed(555, i));
        }
        assertNotEquals(StructureRoll.botSeed(555, 0), StructureRoll.botSeed(556, 0));
        assertEquals(StableHash.combine(555, 0x100 + 3), StructureRoll.botSeed(555, 3));
    }

    @Test
    void positionStreamsDifferPerAttemptAndAreStable() {
        long a0 = StructureRoll.positionRng(9, 0).nextLong();
        long a1 = StructureRoll.positionRng(9, 1).nextLong();
        assertNotEquals(a0, a1);
        assertEquals(a0, StructureRoll.positionRng(9, 0).nextLong());
        assertEquals(new SplitMix64(StableHash.combine(9, 2 + 5)).nextLong(), StructureRoll.positionRng(9, 5).nextLong());
    }

    // ------------------------------------------------------------------ size-aware bot count (blocksPerBot)

    @Test
    void aTinyStructureIsCappedNearOneBotRegardlessOfTheHighMinBots() {
        SplitMix64 seeds = new SplitMix64(10);
        for (int i = 0; i < 2000; i++) {
            // 1 block of volume, 3 blocks-per-bot -> ceil(1/3) = 1, so the effective ceiling is 1 no matter
            // how high minBots is configured (it folds down to meet the ceiling).
            StructureRoll r = StructureRoll.of(rule(1.0, 20), seeds.nextLong(), 1, 3.0, ForceMode.ROLL, "RANDOM");
            assertEquals(1, r.botCount(), "a tiny structure must not draw more than one bot");
            assertEquals(1, r.sizeCappedMax());
            assertEquals(1, r.structureVolume());
        }
    }

    @Test
    void aStructureWithNoVolumeDataCountsAsOneBlock() {
        StructureRoll withZero = StructureRoll.of(rule(1.0, 20), 42, 0, 3.0, ForceMode.ROLL, "RANDOM");
        StructureRoll withOne = StructureRoll.of(rule(1.0, 20), 42, 1, 3.0, ForceMode.ROLL, "RANDOM");
        assertEquals(1, withZero.structureVolume());
        assertEquals(withOne, withZero, "0 and 1 blocks of volume must resolve identically (both are 'no real size data')");
    }

    @Test
    void moreVolumeRaisesTheCeilingUpToTheGlobalCapButNeverBeyondIt() {
        // 3 blocks/bot: 6 blocks of volume -> ceil(6/3) = 2; 300 blocks -> ceil(300/3) = 100, but the global
        // cap (64) still wins.
        StructureRoll small = StructureRoll.of(rule(1.0, 1), 7, 6, 3.0, ForceMode.ROLL, "RANDOM");
        StructureRoll big = StructureRoll.of(rule(1.0, 1), 7, 300, 3.0, ForceMode.ROLL, "RANDOM");
        assertEquals(2, small.sizeCappedMax());
        assertEquals(EffectiveRule.MAX_BOTS_PER_STRUCTURE, big.sizeCappedMax(),
                "the global cap is an absolute ceiling volume can never exceed");
        assertTrue(small.botCount() <= 2);
        assertTrue(big.botCount() <= EffectiveRule.MAX_BOTS_PER_STRUCTURE);
    }

    @Test
    void effectiveMinNeverExceedsTheSizeCappedCeiling() {
        // minBots=5 but only 1 block of volume at 3 blocks/bot caps the ceiling at 1: the floor must fold
        // down too, or nextIntInclusive(5, 1) would be an invalid (inverted) range.
        SplitMix64 seeds = new SplitMix64(11);
        for (int i = 0; i < 500; i++) {
            StructureRoll r = StructureRoll.of(rule(1.0, 5), seeds.nextLong(), 1, 3.0, ForceMode.ROLL, "RANDOM");
            assertEquals(1, r.botCount());
        }
    }

    @Test
    void aBadBlocksPerBotIsClampedAwayFromDivisionProblems() {
        // StructureRoll trusts EffectiveRule.sizeCappedMax's own defensive floor for a degenerate
        // blocksPerBot; this only proves the roll does not blow up given one.
        StructureRoll r = StructureRoll.of(rule(1.0, 1), 5, 1, 0.0, ForceMode.ROLL, "RANDOM");
        assertTrue(r.botCount() >= 1 && r.botCount() <= EffectiveRule.MAX_BOTS_PER_STRUCTURE);
    }

    @Test
    void sizeScalingIsDeterministicGivenTheSameInputs() {
        StructureRoll a = StructureRoll.of(rule(1.0, 1), 999, 12, 3.0, ForceMode.ROLL, "DETERMINISTIC");
        StructureRoll b = StructureRoll.of(rule(1.0, 1), 999, 12, 3.0, ForceMode.ROLL, "DETERMINISTIC");
        assertEquals(a, b);
    }
}
