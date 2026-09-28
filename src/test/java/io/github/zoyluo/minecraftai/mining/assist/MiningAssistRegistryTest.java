package io.github.zoyluo.aibot.mining.assist;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class MiningAssistRegistryTest {
    @BeforeEach
    @AfterEach
    void reset() {
        MiningAssistRuntime.resetForTests();
    }

    private static UUID bot(long n) {
        return new UUID(n, n * 7);
    }

    @Test
    void getOrCreateIsIdempotentAndGetIfPresentNeverCreates() {
        assertNull(MiningAssistRegistry.getIfPresent(bot(1)));
        MiningAssistState state = MiningAssistRegistry.getOrCreate(bot(1));
        assertSame(state, MiningAssistRegistry.getOrCreate(bot(1)));
        assertSame(state, MiningAssistRegistry.getIfPresent(bot(1)));
        assertEquals(bot(1), state.botId());
        assertEquals(1, MiningAssistRegistry.size());
    }

    @Test
    void clearDropsOneBotAndItsDugRing() {
        BotEditsLedger ledger = BotEdits.ledger();
        MiningAssistState a = MiningAssistRegistry.getOrCreate(bot(1));
        MiningAssistRegistry.getOrCreate(bot(2));
        ledger.noteDug(BotEdits.botKey(bot(1)), 42L);
        ledger.noteDug(BotEdits.botKey(bot(2)), 43L);

        MiningAssistRegistry.clear(bot(1));
        assertNull(MiningAssistRegistry.getIfPresent(bot(1)));
        assertEquals(0, ledger.dugSize(BotEdits.botKey(bot(1))));
        assertEquals(1, ledger.dugSize(BotEdits.botKey(bot(2))));
        assertEquals(1, MiningAssistRegistry.size());
        assertNotSame(a, MiningAssistRegistry.getOrCreate(bot(1)), "a cleared bot starts with a fresh state");
    }

    @Test
    void clearAllDropsEverything() {
        MiningAssistRegistry.getOrCreate(bot(1));
        MiningAssistRegistry.getOrCreate(bot(2));
        MiningAssistRegistry.clearAll();
        assertEquals(0, MiningAssistRegistry.size());
    }

    // ---- active sweeper count --------------------------------------------------------------------

    @Test
    void aLoneBotCountsItselfEvenBeforeItHasSwept() {
        MiningAssistState self = MiningAssistRegistry.getOrCreate(bot(1));
        assertEquals(1, MiningAssistRegistry.activeSweepers(100, self));
        assertEquals(1, MiningAssistRegistry.activeSweepers(100, null));
    }

    @Test
    void botsThatSweptThisTickOrTheLastOneAreActive() {
        MiningAssistState a = MiningAssistRegistry.getOrCreate(bot(1));
        MiningAssistState b = MiningAssistRegistry.getOrCreate(bot(2));
        MiningAssistState c = MiningAssistRegistry.getOrCreate(bot(3));
        MiningAssistState d = MiningAssistRegistry.getOrCreate(bot(4));
        a.markSweepTick(100);
        b.markSweepTick(99);
        c.markSweepTick(98);
        // d never swept
        assertEquals(3, MiningAssistRegistry.activeSweepers(100, d), "a, b and d itself");
        assertEquals(2, MiningAssistRegistry.activeSweepers(100, a), "a and b; c is stale");
    }

    @Test
    void theBaseCountIsRecomputedEachTick() {
        MiningAssistState a = MiningAssistRegistry.getOrCreate(bot(1));
        MiningAssistState b = MiningAssistRegistry.getOrCreate(bot(2));
        a.markSweepTick(100);
        b.markSweepTick(100);
        assertEquals(2, MiningAssistRegistry.activeSweepers(100, a));
        assertEquals(1, MiningAssistRegistry.activeSweepers(102, a), "nobody swept for two ticks; a counts itself");
        b.markSweepTick(102);
        assertEquals(2, MiningAssistRegistry.activeSweepers(103, a));
    }

    @Test
    void sweepersCountFeedsTheThrottleDivisor() {
        for (int i = 0; i < 32; i++) {
            MiningAssistRegistry.getOrCreate(bot(i + 1)).markSweepTick(500);
        }
        MiningAssistState self = MiningAssistRegistry.getIfPresent(bot(1));
        int sweepers = MiningAssistRegistry.activeSweepers(500, self);
        assertEquals(32, sweepers);
        assertEquals(20, SenseBudget.raysEff(40, 640, sweepers, false));
    }
}
