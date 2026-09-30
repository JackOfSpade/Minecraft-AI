package dev.spawnbotswrapper.inhabitants.combat;

import dev.spawnbotswrapper.inhabitants.combat.MeleeGeometry.Box;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The per-attacker memory of the last legally hit primary victim: one record each, this tick only, bounded. */
class SweepMemoryTest {
    private static final Box BOX = new Box(0, 0, 0, 1, 1, 1);

    @Test
    void anotherVictimOfTheSameTickSeesThePrimaryBoxButThePrimaryItselfDoesNot() {
        SweepMemory memory = new SweepMemory();
        UUID attacker = UUID.randomUUID();
        UUID primary = UUID.randomUUID();
        memory.remember(10, attacker, primary, BOX);
        assertSame(BOX, memory.otherPrimaryThisTick(10, attacker, UUID.randomUUID()));
        assertNull(memory.otherPrimaryThisTick(10, attacker, primary));
        assertNull(memory.otherPrimaryThisTick(10, UUID.randomUUID(), UUID.randomUUID()), "another attacker");
    }

    @Test
    void aRecordOfAnEarlierTickIsDroppedWhenRead() {
        SweepMemory memory = new SweepMemory();
        UUID attacker = UUID.randomUUID();
        memory.remember(10, attacker, UUID.randomUUID(), BOX);
        assertNull(memory.otherPrimaryThisTick(11, attacker, UUID.randomUUID()));
        assertEquals(0, memory.size());
    }

    @Test
    void anAttackerHasOneRecordTheNewestWins() {
        SweepMemory memory = new SweepMemory();
        UUID attacker = UUID.randomUUID();
        Box second = new Box(5, 5, 5, 6, 6, 6);
        memory.remember(10, attacker, UUID.randomUUID(), BOX);
        memory.remember(11, attacker, UUID.randomUUID(), second);
        assertEquals(1, memory.size());
        assertSame(second, memory.otherPrimaryThisTick(11, attacker, UUID.randomUUID()));
    }

    @Test
    void theMapStaysBoundedStaleRecordsGoFirst() {
        SweepMemory memory = new SweepMemory();
        for (int i = 0; i < SweepMemory.MAX_ATTACKERS; i++) {
            memory.remember(1, UUID.randomUUID(), UUID.randomUUID(), BOX);
        }
        assertEquals(SweepMemory.MAX_ATTACKERS, memory.size());
        // A new attacker one tick later: every record is stale and goes, only the new one stays.
        UUID fresh = UUID.randomUUID();
        memory.remember(2, fresh, UUID.randomUUID(), BOX);
        assertEquals(1, memory.size());
        assertNotNull(memory.otherPrimaryThisTick(2, fresh, UUID.randomUUID()));
    }

    @Test
    void whenEveryRecordIsCurrentTheMapIsClearedAtTheCapNotGrownPastIt() {
        SweepMemory memory = new SweepMemory();
        for (int i = 0; i < 5 * SweepMemory.MAX_ATTACKERS; i++) {
            memory.remember(7, UUID.randomUUID(), UUID.randomUUID(), BOX);
            assertTrue(memory.size() <= SweepMemory.MAX_ATTACKERS);
        }
        memory.clear();
        assertEquals(0, memory.size());
    }
}
