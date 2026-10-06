package io.github.zoyluo.minecraftai.baritone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The bounded-memory rule of the observation fence, free of any Minecraft type. */
class ObservedCellRetentionTest {
    private static List<Map.Entry<Long, Integer>> entries(Map<Long, Integer> cells) {
        return new ArrayList<>(cells.entrySet());
    }

    @Test
    void anUnderfullSnapshotIsKeptWhole() {
        Map<Long, Integer> cells = new HashMap<>();
        for (long key = 0; key < 10; key++) {
            cells.put(key, (int) key);
        }

        List<Map.Entry<Long, Integer>> kept = ObservedCellRetention.freshest(entries(cells), seen -> seen, 10);

        assertEquals(10, kept.size());
    }

    @Test
    void anOverfullSnapshotKeepsTheFreshestCellsAndNothingOlder() {
        Map<Long, Integer> cells = new HashMap<>();
        for (long key = 0; key < 100; key++) {
            cells.put(key, (int) (key % 50)); // two cells for every tick 0..49
        }

        List<Map.Entry<Long, Integer>> kept = ObservedCellRetention.freshest(entries(cells), seen -> seen, 40);

        assertEquals(40, kept.size());
        int oldestKept = kept.stream().mapToInt(Map.Entry::getValue).min().orElseThrow();
        assertEquals(30, oldestKept, "ticks 30..49 are the freshest forty of the hundred cells");
        for (Map.Entry<Long, Integer> dropped : entries(cells)) {
            if (!kept.contains(dropped)) {
                assertTrue(dropped.getValue() < 30, "a cell fresher than the cut was dropped: " + dropped);
            }
        }
    }

    @Test
    void cellsSeenAtTheSameTickAreCutByPackedPositionNotByInsertionOrder() {
        Map<Long, Integer> cells = new HashMap<>();
        for (long key = 0; key < 6; key++) {
            cells.put(key, 7);
        }

        List<Map.Entry<Long, Integer>> kept = ObservedCellRetention.freshest(entries(cells), seen -> seen, 4);

        assertEquals(List.of(5L, 4L, 3L, 2L), kept.stream().map(Map.Entry::getKey).toList(),
                "the larger packed position wins a tie, so the cut never depends on hash order");
    }

    @Test
    void shedsAnAdmissionsWorthOfNewCellsInOnePassNotOneFullScanPerCell() {
        // A saturated 8192-cell snapshot to which one admission adds 3000 fresher cells. Evicting inside every
        // put scanned all 8192 cells for each of them (24.6 million cell reads, 300-900 ms of server thread).
        Map<Long, Integer> cells = new HashMap<>();
        for (long key = 0; key < ObservedNavigationFence.MAX_CELLS; key++) {
            cells.put(key, 100);
        }
        for (long key = 100_000; key < 103_000; key++) {
            cells.put(key, 200);
        }
        AtomicLong reads = new AtomicLong();

        List<Map.Entry<Long, Integer>> kept = ObservedCellRetention.freshest(entries(cells),
                seen -> {
                    reads.incrementAndGet();
                    return seen;
                }, ObservedNavigationFence.MAX_CELLS);

        assertEquals(ObservedNavigationFence.MAX_CELLS, kept.size());
        assertEquals(3000, kept.stream().filter(entry -> entry.getValue() == 200).count(),
                "every fresh cell is kept");
        assertFalse(kept.stream().anyMatch(entry -> entry.getKey() < 3000 && entry.getValue() == 100),
                "the cells shed are the oldest, smallest packed positions");
        assertTrue(reads.get() < 1_000_000L,
                "freezing read " + reads.get() + " cell ticks; one pass over 11192 cells needs a few hundred thousand,"
                        + " while a scan per new cell needs 24 million");
    }
}
