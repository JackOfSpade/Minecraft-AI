package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.config.EffectiveRule;
import dev.spawnbotswrapper.inhabitants.util.SplitMix64;
import dev.spawnbotswrapper.inhabitants.util.StableHash;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class StructureRollTest {

    /** A piece count generous enough that it never caps the range below {@code maxBots} in these tests. */
    private static final int AMPLE_PIECES = 1_000_000;

    private static EffectiveRule rule(double chance, int min, int max) {
        return rule(chance, min, max, 3.0);
    }

    private static EffectiveRule rule(double chance, int min, int max, double piecesPerBot) {
        return new EffectiveRule(chance, min, max, piecesPerBot, "default", "default", "default", "default");
    }

    @Test
    void chanceZeroIsNeverOccupiedAndChanceOneAlwaysIs() {
        SplitMix64 seeds = new SplitMix64(1);
        for (int i = 0; i < 20_000; i++) {
            long seed = seeds.nextLong();
            assertFalse(StructureRoll.of(rule(0.0, 1, 4), seed, AMPLE_PIECES, ForceMode.ROLL, "RANDOM").occupied());
            assertTrue(StructureRoll.of(rule(1.0, 1, 4), seed, AMPLE_PIECES, ForceMode.ROLL, "RANDOM").occupied());
        }
    }

    @Test
    void verdictIsUniformRollBelowChanceAndTheRollIsRecorded() {
        SplitMix64 seeds = new SplitMix64(2);
        for (int i = 0; i < 5_000; i++) {
            long seed = seeds.nextLong();
            StructureRoll r = StructureRoll.of(rule(0.4, 1, 4), seed, AMPLE_PIECES, ForceMode.ROLL, "RANDOM");
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
        assertFalse(StructureRoll.of(rule(roll, 1, 1), seed, AMPLE_PIECES, ForceMode.ROLL, "RANDOM").occupied(),
                "u < chance must be false when chance == u exactly");
        assertTrue(StructureRoll.of(rule(Math.nextUp(roll), 1, 1), seed, AMPLE_PIECES, ForceMode.ROLL, "RANDOM").occupied());
    }

    @Test
    void botCountCoversTheWholeInclusiveRangeAndNothingOutside() {
        SplitMix64 seeds = new SplitMix64(3);
        int[] hits = new int[9];
        for (int i = 0; i < 20_000; i++) {
            int n = StructureRoll.of(rule(1.0, 3, 7), seeds.nextLong(), AMPLE_PIECES, ForceMode.ROLL, "RANDOM").botCount();
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
        for (int i = 0; i < 1000; i++) {
            assertEquals(4, StructureRoll.of(rule(1.0, 4, 4), seeds.nextLong(), AMPLE_PIECES, ForceMode.ROLL, "RANDOM").botCount());
        }
    }

    @Test
    void forcedModesIgnoreTheVerdictButNotTheRecordedRoll() {
        long seed = 12345;
        StructureRoll natural = StructureRoll.of(rule(0.5, 1, 4), seed, AMPLE_PIECES, ForceMode.ROLL, "DETERMINISTIC");
        StructureRoll occupied = StructureRoll.of(rule(0.0, 1, 4), seed, AMPLE_PIECES, ForceMode.OCCUPIED, "DETERMINISTIC");
        StructureRoll abandoned = StructureRoll.of(rule(1.0, 1, 4), seed, AMPLE_PIECES, ForceMode.ABANDONED, "DETERMINISTIC");
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
        assertEquals(StructureRoll.of(rule(0.5, 1, 4), 77, AMPLE_PIECES, ForceMode.ROLL, "RANDOM"),
                StructureRoll.of(rule(0.5, 1, 4), 77, AMPLE_PIECES, ForceMode.ROLL, "RANDOM"));
        Set<Double> rolls = new HashSet<>();
        for (long s = 0; s < 100; s++) {
            rolls.add(StructureRoll.of(rule(0.5, 1, 4), s, AMPLE_PIECES, ForceMode.ROLL, "RANDOM").roll());
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

    // ------------------------------------------------------------------ size-aware bot count (piecesPerBot)

    @Test
    void aSinglePieceStructureIsCappedNearOneBotRegardlessOfAGenerousMaxBots() {
        SplitMix64 seeds = new SplitMix64(10);
        for (int i = 0; i < 2000; i++) {
            // 1 piece, 3 pieces-per-bot -> ceil(1/3) = 1, so the effective ceiling is 1 no matter how high
            // maxBots is configured, and the effective floor (minBots=1) already agrees.
            StructureRoll r = StructureRoll.of(rule(1.0, 1, 20, 3.0), seeds.nextLong(), 1, ForceMode.ROLL, "RANDOM");
            assertEquals(1, r.botCount(), "a lone piece must not draw more than one bot");
            assertEquals(1, r.sizeCappedMax());
            assertEquals(1, r.pieceCount());
        }
    }

    @Test
    void aStructureWithNoPieceDataCountsAsOnePiece() {
        StructureRoll withZero = StructureRoll.of(rule(1.0, 1, 20, 3.0), 42, 0, ForceMode.ROLL, "RANDOM");
        StructureRoll withOne = StructureRoll.of(rule(1.0, 1, 20, 3.0), 42, 1, ForceMode.ROLL, "RANDOM");
        assertEquals(1, withZero.pieceCount());
        assertEquals(withOne, withZero, "0 and 1 pieces must resolve identically (both are 'no real piece data')");
    }

    @Test
    void moreSourcePiecesRaiseTheCeilingUpToMaxBotsButNeverBeyondIt() {
        // piecesPerBot = 3: 6 pieces -> ceil(6/3) = 2; 30 pieces -> ceil(30/3) = 10, but maxBots (6) still wins.
        StructureRoll small = StructureRoll.of(rule(1.0, 1, 6, 3.0), 7, 6, ForceMode.ROLL, "RANDOM");
        StructureRoll big = StructureRoll.of(rule(1.0, 1, 6, 3.0), 7, 30, ForceMode.ROLL, "RANDOM");
        assertEquals(2, small.sizeCappedMax());
        assertEquals(6, big.sizeCappedMax(), "maxBots is an absolute ceiling piece count can never exceed");
        assertTrue(small.botCount() <= 2);
        assertTrue(big.botCount() <= 6);
    }

    @Test
    void effectiveMinNeverExceedsTheSizeCappedCeiling() {
        // minBots=5 but only 1 piece at piecesPerBot=3 caps the ceiling at 1: the floor must fold down too,
        // or nextIntInclusive(5, 1) would be an invalid (inverted) range.
        SplitMix64 seeds = new SplitMix64(11);
        for (int i = 0; i < 500; i++) {
            StructureRoll r = StructureRoll.of(rule(1.0, 5, 8, 3.0), seeds.nextLong(), 1, ForceMode.ROLL, "RANDOM");
            assertEquals(1, r.botCount());
        }
    }

    @Test
    void aBadPiecesPerBotIsClampedAwayFromDivisionProblemsByRuleResolver() {
        // StructureRoll trusts EffectiveRule's own invariant (piecesPerBot > 0); this only proves the roll
        // does not blow up given the smallest legal value RuleResolver ever hands it.
        StructureRoll r = StructureRoll.of(rule(1.0, 1, 4, 0.1), 5, 1, ForceMode.ROLL, "RANDOM");
        assertTrue(r.botCount() >= 1 && r.botCount() <= 4);
    }

    @Test
    void sizeScalingIsDeterministicGivenTheSameInputs() {
        StructureRoll a = StructureRoll.of(rule(1.0, 1, 10, 3.0), 999, 12, ForceMode.ROLL, "DETERMINISTIC");
        StructureRoll b = StructureRoll.of(rule(1.0, 1, 10, 3.0), 999, 12, ForceMode.ROLL, "DETERMINISTIC");
        assertEquals(a, b);
    }
}
