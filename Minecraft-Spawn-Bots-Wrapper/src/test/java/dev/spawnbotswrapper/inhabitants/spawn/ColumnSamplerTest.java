package dev.spawnbotswrapper.inhabitants.spawn;

import dev.spawnbotswrapper.inhabitants.structure.IntBox;
import dev.spawnbotswrapper.inhabitants.util.SplitMix64;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static dev.spawnbotswrapper.inhabitants.spawn.Fixtures.box;
import static org.junit.jupiter.api.Assertions.*;

class ColumnSamplerTest {

    private static List<ColumnSampler.Column> drainAll(ColumnSampler s, long seed) {
        SplitMix64 rng = new SplitMix64(seed);
        List<ColumnSampler.Column> out = new ArrayList<>();
        while (s.hasNext()) {
            out.add(s.next(rng));
        }
        return out;
    }

    @Test
    void everyColumnIsDrawnExactlyOnceAndThenTheSamplerIsEmpty() {
        List<IntBox> boxes = List.of(box(0, 0, 0, 4, 9, 2), box(10, 0, 10, 10, 9, 14), box(-5, 0, -5, -4, 9, -4));
        ColumnSampler s = new ColumnSampler(boxes);
        List<ColumnSampler.Column> all = drainAll(s, 3);
        assertEquals(5 * 3 + 5 + 4, all.size());
        Set<String> seen = new HashSet<>();
        for (ColumnSampler.Column c : all) {
            assertTrue(c.box().contains(c.x(), c.box().minY(), c.z()), "column outside its own box: " + c);
            assertTrue(seen.add(c.box() + "@" + c.x() + "," + c.z()), "column drawn twice: " + c);
        }
        assertFalse(s.hasNext());
        assertThrows(IllegalStateException.class, () -> s.next(new SplitMix64(1)));
    }

    @Test
    void identicalBoxesAreCollapsedWhileOverlappingOnesAreKept() {
        IntBox a = box(0, 0, 0, 3, 5, 3);
        assertEquals(16, drainAll(new ColumnSampler(List.of(a, a, a)), 1).size());
        IntBox b = box(2, 0, 2, 5, 5, 5);
        assertEquals(32, drainAll(new ColumnSampler(List.of(a, b)), 1).size(), "overlap is legitimate: Y ranges may differ");
    }

    @Test
    void noBoxesMeansNothingToDraw() {
        assertFalse(new ColumnSampler(List.of()).hasNext());
    }

    @Test
    void sameSeedSameSequenceDifferentSeedDifferentSequence() {
        List<IntBox> boxes = List.of(box(0, 0, 0, 9, 3, 9), box(20, 0, 20, 22, 3, 22));
        assertEquals(drainAll(new ColumnSampler(boxes), 5), drainAll(new ColumnSampler(boxes), 5));
        assertNotEquals(drainAll(new ColumnSampler(boxes), 5), drainAll(new ColumnSampler(boxes), 6));
    }

    @Test
    void theFirstDrawIsUniformOverABoxesColumns() {
        IntBox b = box(0, 0, 0, 9, 3, 9);
        int[] hits = new int[100];
        int runs = 4000;
        for (long seed = 0; seed < runs; seed++) {
            ColumnSampler.Column c = new ColumnSampler(List.of(b)).next(new SplitMix64(seed));
            hits[c.z() * 10 + c.x()]++;
        }
        for (int i = 0; i < hits.length; i++) {
            assertTrue(hits[i] > 10 && hits[i] < 80, "column " + i + " was drawn " + hits[i] + " times of " + runs + " (expected about 40)");
        }
    }

    @Test
    void smallBoxesGetAFixedShareOfDrawsInsteadOfBeingStarved() {
        IntBox big = box(0, 0, 0, 99, 3, 99);
        IntBox tiny = box(500, 0, 500, 500, 3, 500);
        int tinyFirst = 0;
        int runs = 2000;
        for (long seed = 0; seed < runs; seed++) {
            if (new ColumnSampler(List.of(big, tiny)).next(new SplitMix64(seed)).box().equals(tiny)) {
                tinyFirst++;
            }
        }
        double share = (double) tinyFirst / runs;
        double expected = ColumnSampler.UNIFORM_BOX_SHARE / 2;
        assertTrue(share > expected * 0.6 && share < expected * 1.5,
                "share " + share + ", pure area weighting would give 0.0001");
    }

    @Test
    void anExhaustedBoxNoLongerTakesDraws() {
        IntBox big = box(0, 0, 0, 99, 3, 99);
        IntBox tiny = box(500, 0, 500, 500, 3, 500);
        ColumnSampler s = new ColumnSampler(List.of(big, tiny));
        SplitMix64 rng = new SplitMix64(9);
        int tinyDraws = 0;
        for (int i = 0; i < 3000; i++) {
            if (s.next(rng).box().equals(tiny)) {
                tinyDraws++;
            }
        }
        assertEquals(1, tinyDraws, "its single column is drawn once, never again");
    }

    @Test
    void aHugeBoxIsCheapAndStillNeverRepeats() {
        IntBox huge = box(-50_000, 0, -50_000, 50_000, 3, 50_000);
        ColumnSampler s = new ColumnSampler(List.of(huge));
        SplitMix64 rng = new SplitMix64(4);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 20_000; i++) {
            ColumnSampler.Column c = s.next(rng);
            assertTrue(huge.contains(c.x(), 0, c.z()));
            assertTrue(seen.add(c.x() + "," + c.z()));
        }
    }

    @Test
    void aBoxWithMoreColumnsThanAnIntStillSamplesInsideItself() {
        IntBox absurd = box(0, 0, 0, 200_000, 3, 200_000);
        ColumnSampler s = new ColumnSampler(List.of(absurd));
        SplitMix64 rng = new SplitMix64(4);
        for (int i = 0; i < 1000; i++) {
            ColumnSampler.Column c = s.next(rng);
            assertTrue(absurd.contains(c.x(), 0, c.z()), c.toString());
        }
    }
}
