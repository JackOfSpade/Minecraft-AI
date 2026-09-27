package dev.spawnbotswrapper.inhabitants.sample;

import dev.spawnbotswrapper.inhabitants.util.SplitMix64;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CoverageSamplerTest {

    private static CoverageSampler sampler(long seed) {
        return new CoverageSampler(new SplitMix64(seed), new TransientDeckStore());
    }

    @Test
    void everyBucketIsVisitedOncePerCycle() {
        // moveSpeed-like range from the brief: 0.1 .. 2.0
        CoverageSampler s = sampler(1);
        int buckets = CoverageSampler.DEFAULT_BUCKETS;
        double width = (2.0 - 0.1) / buckets;
        for (int cycle = 0; cycle < 50; cycle++) {
            Set<Integer> seen = new HashSet<>();
            for (int i = 0; i < buckets; i++) {
                double v = s.nextDouble("moveSpeed", 0.1, 2.0);
                assertTrue(v >= 0.1 && v <= 2.0, "out of range: " + v);
                int b = Math.min(buckets - 1, (int) ((v - 0.1) / width));
                seen.add(b);
            }
            assertEquals(buckets, seen.size(), "cycle " + cycle + " missed a bucket");
        }
    }

    @Test
    void resultsAreNotClusteredAroundTheMidpoint() {
        CoverageSampler s = sampler(2);
        int veryLow = 0;
        int veryHigh = 0;
        int n = 800;
        for (int i = 0; i < n; i++) {
            double v = s.nextDouble("moveSpeed", 0.1, 2.0);
            if (v < 0.1 + 0.25 * 1.9) {
                veryLow++;
            }
            if (v > 0.1 + 0.75 * 1.9) {
                veryHigh++;
            }
        }
        // each outer quarter must hold ~25% (exactly, up to bucket alignment), not the sliver a normal curve would give
        assertTrue(veryLow > n * 0.20, "low quarter under-represented: " + veryLow);
        assertTrue(veryHigh > n * 0.20, "high quarter under-represented: " + veryHigh);
    }

    @Test
    void exactEndpointsAreReachable() {
        CoverageSampler s = sampler(3);
        boolean lo = false;
        boolean hi = false;
        for (int i = 0; i < 2000; i++) {
            double v = s.nextDouble("x", 0.1, 2.0);
            lo |= v == 0.1;
            hi |= v == 2.0;
        }
        assertTrue(lo, "minimum never produced");
        assertTrue(hi, "maximum never produced");
    }

    @Test
    void integersCoverInclusiveRangeAndBothEnds() {
        CoverageSampler s = sampler(4);
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 4000; i++) {
            int v = s.nextInt("attackCooldown", 1, 40);
            assertTrue(v >= 1 && v <= 40, "out of range: " + v);
            seen.add(v);
        }
        assertTrue(seen.contains(1) && seen.contains(40));
        assertTrue(seen.size() >= 38, "expected nearly every integer, got " + seen.size());
    }

    @Test
    void smallIntegerRangesDealEveryValueOncePerCycle() {
        CoverageSampler s = sampler(5);
        for (int cycle = 0; cycle < 20; cycle++) {
            Set<Integer> seen = new HashSet<>();
            for (int i = 0; i < 3; i++) {
                seen.add(s.nextInt("tiny", 1, 3));
            }
            assertEquals(Set.of(1, 2, 3), seen);
        }
    }

    @Test
    void booleansAreExactlyBalancedPerPair() {
        CoverageSampler s = sampler(6);
        for (int pair = 0; pair < 500; pair++) {
            boolean a = s.nextBoolean("shield");
            boolean b = s.nextBoolean("shield");
            assertNotEquals(a, b, "pair " + pair + " was not one true + one false");
        }
    }

    @Test
    void categoricalPicksCoverEveryOptionPerCycle() {
        CoverageSampler s = sampler(7);
        List<String> opts = List.of("sword", "axe", "mace", "bow", "crossbow", "none");
        for (int cycle = 0; cycle < 30; cycle++) {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < opts.size(); i++) {
                seen.add(s.pick("weapon", opts));
            }
            assertEquals(new HashSet<>(opts), seen);
        }
    }

    @Test
    void settingsHaveIndependentDecks() {
        CoverageSampler s = sampler(8);
        // interleaving draws of two keys must not disturb either key's cycle
        Set<Integer> a = new HashSet<>();
        Set<Integer> b = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            a.add(s.nextInt("a", 0, 3));
            b.add(s.nextInt("b", 0, 3));
        }
        assertEquals(4, a.size());
        assertEquals(4, b.size());
    }

    @Test
    void sameSeedAndFreshDecksReproduceTheSameSequence() {
        CoverageSampler a = sampler(1234);
        CoverageSampler b = sampler(1234);
        for (int i = 0; i < 200; i++) {
            assertEquals(a.nextDouble("d", 0, 10), b.nextDouble("d", 0, 10));
            assertEquals(a.nextInt("i", 0, 100), b.nextInt("i", 0, 100));
            assertEquals(a.nextBoolean("b"), b.nextBoolean("b"));
        }
    }

    @Test
    void degenerateRangesReturnTheOnlyValue() {
        CoverageSampler s = sampler(9);
        assertEquals(5.0, s.nextDouble("k", 5.0, 5.0));
        assertEquals(7, s.nextInt("k2", 7, 7));
        assertThrows(IllegalArgumentException.class, () -> s.nextDouble("k3", 2, 1));
        assertThrows(IllegalArgumentException.class, () -> s.nextInt("k4", 2, 1));
    }

    @Test
    void persistentStoreSurvivesExportImportWithoutRestartingTheCycle() {
        SplitMix64 rng = new SplitMix64(11);
        PersistentDeckStore store = new PersistentDeckStore();
        CoverageSampler s = new CoverageSampler(rng, store);
        Set<Integer> firstHalf = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            firstHalf.add(s.nextInt("k", 0, 7)); // 8 buckets, 4 dealt
        }
        Map<String, PersistentDeckStore.Snapshot> saved = store.exportSnapshots();

        // "restart": new store, restored from snapshot
        PersistentDeckStore restored = new PersistentDeckStore();
        restored.importSnapshots(saved);
        CoverageSampler s2 = new CoverageSampler(new SplitMix64(999), restored);
        Set<Integer> all = new HashSet<>(firstHalf);
        for (int i = 0; i < 4; i++) {
            all.add(s2.nextInt("k", 0, 7));
        }
        assertEquals(8, all.size(), "the remaining half of the deck must complete the cycle after a restart");
    }

    @Test
    void invalidPersistedDeckFallsBackToFresh() {
        Map<String, PersistentDeckStore.Snapshot> bad = new HashMap<>();
        PersistentDeckStore.Snapshot snap = new PersistentDeckStore.Snapshot();
        snap.size = 4;
        snap.order = new int[]{0, 0, 1, 2}; // not a permutation
        snap.cursor = 1;
        bad.put("k", snap);
        PersistentDeckStore store = new PersistentDeckStore();
        store.importSnapshots(bad);
        Deck d = store.deck("k", 4);
        Set<Integer> seen = new HashSet<>();
        SplitMix64 rng = new SplitMix64(1);
        for (int i = 0; i < 4; i++) {
            seen.add(d.draw(rng));
        }
        assertEquals(4, seen.size());
    }

    @Test
    void changingBucketCountResetsTheDeck() {
        TransientDeckStore store = new TransientDeckStore();
        Deck a = store.deck("k", 4);
        Deck b = store.deck("k", 6);
        assertNotSame(a, b);
        assertEquals(6, b.size());
    }
}
