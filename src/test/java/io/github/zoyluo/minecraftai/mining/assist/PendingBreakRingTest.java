package io.github.zoyluo.minecraftai.mining.assist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;

class PendingBreakRingTest {
    @Test
    void defaultCapacityIsSixteen() {
        assertEquals(16, new PendingBreakRing().capacity());
        assertEquals(16, MiningAssistState.PENDING_BREAK_CAP);
    }

    @Test
    void fifoOrder() {
        PendingBreakRing ring = new PendingBreakRing(4);
        ring.offer(1L);
        ring.offer(2L);
        ring.offer(3L);
        assertEquals(3, ring.size());
        assertEquals(1L, ring.poll());
        assertEquals(2L, ring.poll());
        assertEquals(3L, ring.poll());
        assertEquals(PendingBreakRing.EMPTY, ring.poll());
        assertTrue(ring.isEmpty());
    }

    @Test
    void whenFullTheOldestIsDroppedAndTheNewestSurvives() {
        PendingBreakRing ring = new PendingBreakRing(3);
        assertTrue(ring.offer(10L));
        assertTrue(ring.offer(11L));
        assertTrue(ring.offer(12L));
        assertFalse(ring.offer(13L));
        assertFalse(ring.offer(14L));
        assertEquals(2L, ring.dropped());
        assertEquals(3, ring.size());
        assertEquals(12L, ring.poll());
        assertEquals(13L, ring.poll());
        assertEquals(14L, ring.poll());
    }

    @Test
    void wrapsAroundManyTimes() {
        PendingBreakRing ring = new PendingBreakRing(5);
        long next = 0;
        long expected = 0;
        for (int round = 0; round < 200; round++) {
            for (int i = 0; i < 3; i++) {
                ring.offer(next++);
            }
            for (int i = 0; i < 3; i++) {
                assertEquals(expected++, ring.poll());
            }
        }
        assertTrue(ring.isEmpty());
        assertEquals(0L, ring.dropped());
    }

    @Test
    void packedBlockPositionsRoundTrip() {
        PendingBreakRing ring = new PendingBreakRing();
        BlockPos pos = new BlockPos(-30_000_000, -64, 29_999_999);
        ring.offer(pos.asLong());
        assertEquals(pos, BlockPos.of(ring.poll()));
    }

    @Test
    void emptySentinelIsNeverAValidPackedPosition() {
        assertEquals(PendingBreakRing.EMPTY, Long.MIN_VALUE);
        // A packed BlockPos is 26 bits of x, 26 of z and 12 of y. Long.MIN_VALUE decodes to x = -2^25,
        // z = 0, y = 0, and x = -33_554_432 lies beyond the 30_000_000 world border, so no real cell
        // can collide with the sentinel.
        BlockPos decoded = BlockPos.of(Long.MIN_VALUE);
        assertEquals(-33_554_432, decoded.getX());
        assertEquals(0, decoded.getZ());
    }

    @Test
    void clearEmptiesTheRing() {
        PendingBreakRing ring = new PendingBreakRing(4);
        ring.offer(1L);
        ring.offer(2L);
        ring.clear();
        assertEquals(0, ring.size());
        assertEquals(PendingBreakRing.EMPTY, ring.poll());
    }

    @Test
    void capacityMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new PendingBreakRing(0));
    }
}
