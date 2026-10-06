package io.github.zoyluo.minecraftai.perception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** What a bot remembers having seen: the oldest sighting is always first, so forgetting only looks at the head. */
class SeenCellMemoryTest {
    private record Sighting(long cell, int tick) {
    }

    private static final int TTL = 6_000;

    @Test
    void sightingsComeOldestFirstAndASecondSightingMovesTheCellToTheNewestEnd() {
        SeenCellMemory<Sighting> memory = new SeenCellMemory<>(Sighting::tick, 100);
        memory.remember(1, new Sighting(1, 10));
        memory.remember(2, new Sighting(2, 11));
        memory.remember(3, new Sighting(3, 12));

        memory.remember(1, new Sighting(1, 20));

        assertEquals(List.of(2L, 3L, 1L), cellsOf(memory));
        assertEquals(20, memory.get(1).tick(), "the cell holds its latest sighting");
    }

    @Test
    void lookingAtACellIsNotASighting() {
        SeenCellMemory<Sighting> memory = new SeenCellMemory<>(Sighting::tick, 100);
        memory.remember(1, new Sighting(1, 10));
        memory.remember(2, new Sighting(2, 11));

        memory.get(1);

        assertEquals(List.of(1L, 2L), cellsOf(memory), "a read must not make an old cell look fresh");
    }

    @Test
    void beyondItsCapacityItForgetsTheCellsSeenLongestAgo() {
        SeenCellMemory<Sighting> memory = new SeenCellMemory<>(Sighting::tick, 3);
        for (int cell = 1; cell <= 3; cell++) {
            memory.remember(cell, new Sighting(cell, cell));
        }
        memory.remember(1, new Sighting(1, 4)); // seen again: now the freshest

        memory.remember(4, new Sighting(4, 5));

        assertEquals(List.of(3L, 1L, 4L), cellsOf(memory), "cell 2 was seen longest ago");
        assertNull(memory.get(2));
        assertEquals(3, memory.size());
    }

    @Test
    void expiryDropsExactlyTheSightingsOlderThanTheTimeToLive() {
        SeenCellMemory<Sighting> memory = new SeenCellMemory<>(Sighting::tick, 100);
        memory.remember(1, new Sighting(1, 100));
        memory.remember(2, new Sighting(2, 200));
        memory.remember(3, new Sighting(3, 300));

        memory.expire(100 + TTL, TTL);
        assertEquals(List.of(1L, 2L, 3L), cellsOf(memory), "a sighting TTL ticks old is still remembered");

        memory.expire(101 + TTL, TTL);
        assertEquals(List.of(2L, 3L), cellsOf(memory));

        memory.expire(300 + TTL + 1, TTL);
        assertTrue(memory.isEmpty());
    }

    @Test
    void aCellSeenAgainSurvivesTheExpiryOfItsFirstSighting() {
        SeenCellMemory<Sighting> memory = new SeenCellMemory<>(Sighting::tick, 100);
        memory.remember(1, new Sighting(1, 100));
        memory.remember(1, new Sighting(1, 5_000));

        memory.expire(100 + TTL + 1, TTL);

        assertEquals(List.of(1L), cellsOf(memory));
    }

    @Test
    void aTickWithNothingToForgetLooksAtOneSightingNotAtAllOfThem() {
        // The memory a bot builds in a few seconds of looking around is 32,768 cells; every tick of
        // every bot used to test every one of them to find the few that had expired.
        AtomicLong looked = new AtomicLong();
        SeenCellMemory<Sighting> memory = new SeenCellMemory<>(sighting -> {
            looked.incrementAndGet();
            return sighting.tick();
        }, 32_768);
        for (int cell = 0; cell < 32_768; cell++) {
            memory.remember(cell, new Sighting(cell, 1_000 + cell / 100));
        }
        looked.set(0);

        for (int tick = 1_400; tick < 2_400; tick++) {
            memory.expire(tick, TTL);
        }

        assertEquals(1_000, looked.get(), "one look per tick: the oldest sighting is still fresh");
        assertEquals(32_768, memory.size());
    }

    @Test
    void forgettingTheExpiredCostsAsManyLooksAsThereAreExpiredCellsPlusOne() {
        AtomicLong looked = new AtomicLong();
        SeenCellMemory<Sighting> memory = new SeenCellMemory<>(sighting -> {
            looked.incrementAndGet();
            return sighting.tick();
        }, 32_768);
        for (int cell = 0; cell < 10_000; cell++) {
            memory.remember(cell, new Sighting(cell, cell < 4_000 ? 0 : 5_000));
        }
        looked.set(0);

        memory.expire(TTL + 1, TTL);

        assertEquals(4_001, looked.get());
        assertEquals(6_000, memory.size());
    }

    private static List<Long> cellsOf(SeenCellMemory<Sighting> memory) {
        List<Long> cells = new ArrayList<>();
        memory.sightings().forEach(sighting -> cells.add(sighting.cell()));
        return cells;
    }
}
