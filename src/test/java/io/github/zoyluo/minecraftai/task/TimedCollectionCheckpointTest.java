package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TimedCollectionCheckpointTest {
    @Test
    void roundTripRetainsTheRemainingWindowAndPriorYield() {
        TimedCollectionCheckpoint checkpoint = new TimedCollectionCheckpoint(12_000, 4_321, 17, 9);

        assertEquals(checkpoint, TimedCollectionCheckpoint.decode(checkpoint.encode()).orElseThrow());
    }

    @Test
    void rejectsMalformedOrOverdueAccountingInsteadOfRefreshingTheWindow() {
        Map<String, String> malformed = new HashMap<>(
                new TimedCollectionCheckpoint(12_000, 4_321, 17, 9).encode());
        malformed.put("elapsed_ticks", "12001");

        assertTrue(TimedCollectionCheckpoint.decode(malformed).isEmpty());
        assertTrue(TimedCollectionCheckpoint.decode(Map.of()).isEmpty());
    }
}
