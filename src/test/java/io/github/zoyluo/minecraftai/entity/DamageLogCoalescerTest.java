package io.github.zoyluo.minecraftai.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DamageLogCoalescerTest {
    private static final String FIRE = "on_fire attacker=- #-1";
    private static final String ZOMBIE = "mob_attack attacker=zombie#7";

    @Test
    void theFirstEventOfARunIsLoggedNow() {
        DamageLogCoalescer coalescer = new DamageLogCoalescer();
        DamageLogCoalescer.Result result = coalescer.record(FIRE, 100, 1.0F, true, 20.0F, 19.0F);
        assertTrue(result.logNow());
        assertNull(result.flushed());
    }

    @Test
    void repeatsOfTheSameSourceWithinTwoSecondsAreCountedNotLogged() {
        DamageLogCoalescer coalescer = new DamageLogCoalescer();
        coalescer.record(FIRE, 100, 1.0F, true, 20.0F, 19.0F);
        for (int i = 1; i <= 5; i++) {
            DamageLogCoalescer.Result repeat = coalescer.record(FIRE, 100 + i * 20, 1.0F, true, 19.0F - (i - 1), 19.0F - i);
            assertFalse(repeat.logNow(), "repeat " + i + " is coalesced");
            assertNull(repeat.flushed());
        }
        DamageLogCoalescer.Summary summary = coalescer.flush();
        assertNotNull(summary);
        assertEquals(5, summary.repeats());
        assertEquals(5.0F, summary.totalAmount());
        assertEquals(5, summary.applied());
        assertEquals(20.0F, summary.hpFrom());
        assertEquals(14.0F, summary.hpTo());
    }

    @Test
    void aDifferentSourceOrAttackerFlushesTheRunAndStartsANewOne() {
        DamageLogCoalescer coalescer = new DamageLogCoalescer();
        coalescer.record(ZOMBIE, 0, 3.0F, true, 20.0F, 17.0F);
        coalescer.record(ZOMBIE, 10, 3.0F, false, 17.0F, 17.0F);
        DamageLogCoalescer.Result change = coalescer.record(FIRE, 20, 1.0F, true, 17.0F, 16.0F);
        assertTrue(change.logNow());
        assertNotNull(change.flushed());
        assertEquals(ZOMBIE, change.flushed().key());
        assertEquals(1, change.flushed().repeats());
        assertEquals(0, change.flushed().applied());
    }

    @Test
    void aRunWithoutRepeatsProducesNoSummary() {
        DamageLogCoalescer coalescer = new DamageLogCoalescer();
        coalescer.record(FIRE, 0, 1.0F, true, 20.0F, 19.0F);
        assertNull(coalescer.flush());
        DamageLogCoalescer.Result next = coalescer.record(FIRE, 5, 1.0F, true, 19.0F, 18.0F);
        assertTrue(next.logNow(), "after a flush the same source starts a fresh run");
    }

    @Test
    void anExpiredWindowStartsANewRunAndFlushesTheOldOne() {
        DamageLogCoalescer coalescer = new DamageLogCoalescer();
        coalescer.record(FIRE, 0, 1.0F, true, 20.0F, 19.0F);
        coalescer.record(FIRE, 20, 1.0F, true, 19.0F, 18.0F);
        DamageLogCoalescer.Result late = coalescer.record(FIRE, 20 + DamageLogCoalescer.WINDOW_TICKS + 1, 1.0F, true, 18.0F, 17.0F);
        assertTrue(late.logNow());
        assertNotNull(late.flushed());
        assertEquals(1, late.flushed().repeats());
    }

    @Test
    void aLongBurnIsSummarisedEveryMaxRunTicks() {
        DamageLogCoalescer coalescer = new DamageLogCoalescer();
        int logged = 0;
        int summaries = 0;
        for (int tick = 0; tick <= 1000; tick += 20) {
            DamageLogCoalescer.Result result = coalescer.record(FIRE, tick, 1.0F, true, 20.0F, 19.0F);
            logged += result.logNow() ? 1 : 0;
            summaries += result.flushed() != null ? 1 : 0;
        }
        assertTrue(logged < 51 / 3, "far fewer lines than the 51 calls: " + logged);
        assertTrue(summaries >= logged - 1, "each finished run reports its count");
    }

    @Test
    void idleFlushOnlyFiresAfterTheWindow() {
        DamageLogCoalescer coalescer = new DamageLogCoalescer();
        coalescer.record(FIRE, 0, 1.0F, true, 20.0F, 19.0F);
        coalescer.record(FIRE, 10, 1.0F, true, 19.0F, 18.0F);
        assertNull(coalescer.flushIfIdle(10 + DamageLogCoalescer.WINDOW_TICKS));
        DamageLogCoalescer.Summary summary = coalescer.flushIfIdle(10 + DamageLogCoalescer.WINDOW_TICKS + 1);
        assertNotNull(summary);
        assertEquals(1, summary.repeats());
        assertNull(coalescer.flushIfIdle(10_000), "nothing left to flush");
    }
}
