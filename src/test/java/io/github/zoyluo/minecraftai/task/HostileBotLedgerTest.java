package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The pure ledger: the memory boundary, refresh, promotion, no downgrade, clearing. */
class HostileBotLedgerTest {
    private static final int MEMORY = 600;

    @Test
    void aMarkLastsExactlyTheMemoryAfterTheLastAct() {
        HostileBotLedger.Core ledger = new HostileBotLedger.Core();
        UUID bot = UUID.randomUUID();
        ledger.mark(bot, 1000L, "hit");
        assertTrue(ledger.isMarked(bot, 1000L, MEMORY));
        assertTrue(ledger.isMarked(bot, 1000L + MEMORY, MEMORY), "the boundary tick is still inside the memory");
        assertFalse(ledger.isMarked(bot, 1000L + MEMORY + 1, MEMORY), "one tick later the mark has expired");
        assertFalse(ledger.isSuspect(bot, 1000L, MEMORY), "a mark is not a suspicion");
    }

    @Test
    void reMarkingRefreshesTheTick() {
        HostileBotLedger.Core ledger = new HostileBotLedger.Core();
        UUID bot = UUID.randomUUID();
        ledger.mark(bot, 0L, "hit");
        ledger.mark(bot, 500L, "aim");
        assertTrue(ledger.isMarked(bot, 500L + MEMORY, MEMORY));
        assertFalse(ledger.isMarked(bot, 500L + MEMORY + 1, MEMORY));
        assertEquals("aim", ledger.entry(bot).reason());
    }

    @Test
    void aSuspectIsPromotedByAMark() {
        HostileBotLedger.Core ledger = new HostileBotLedger.Core();
        UUID bot = UUID.randomUUID();
        ledger.suspect(bot, 10L, "swing", MEMORY);
        assertTrue(ledger.isSuspect(bot, 10L, MEMORY));
        assertFalse(ledger.isMarked(bot, 10L, MEMORY));
        ledger.mark(bot, 20L, "hit");
        assertTrue(ledger.isMarked(bot, 20L, MEMORY));
        assertFalse(ledger.isSuspect(bot, 20L, MEMORY), "a marked bot is no longer merely suspect");
    }

    @Test
    void aSuspectNeverDowngradesALiveMarkButRefreshesIt() {
        HostileBotLedger.Core ledger = new HostileBotLedger.Core();
        UUID bot = UUID.randomUUID();
        ledger.mark(bot, 100L, "hit");
        ledger.suspect(bot, 400L, "charge", MEMORY);
        assertTrue(ledger.isMarked(bot, 400L, MEMORY));
        assertEquals("hit", ledger.entry(bot).reason(), "the reason of the mark is kept");
        assertTrue(ledger.isMarked(bot, 400L + MEMORY, MEMORY), "the suspect act refreshed the mark");
        // An older suspect act never moves the tick back.
        ledger.suspect(bot, 50L, "swing", MEMORY);
        assertEquals(400L, ledger.entry(bot).lastActTick());
    }

    @Test
    void anExpiredMarkIsReplacedByAFreshSuspect() {
        HostileBotLedger.Core ledger = new HostileBotLedger.Core();
        UUID bot = UUID.randomUUID();
        ledger.mark(bot, 0L, "hit");
        ledger.suspect(bot, MEMORY + 50L, "swing", MEMORY);
        assertFalse(ledger.isMarked(bot, MEMORY + 50L, MEMORY), "a dead mark must not come back through a suspicion");
        assertTrue(ledger.isSuspect(bot, MEMORY + 50L, MEMORY));
    }

    @Test
    void clearingRemovesTheAggressorAndClearAllEverything() {
        HostileBotLedger.Core ledger = new HostileBotLedger.Core();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ledger.mark(first, 5L, "hit");
        ledger.suspect(second, 5L, "swing", MEMORY);
        ledger.clearAggressor(first);
        assertFalse(ledger.isMarked(first, 5L, MEMORY));
        assertNull(ledger.entry(first));
        assertTrue(ledger.isSuspect(second, 5L, MEMORY));
        ledger.clearAll();
        assertTrue(ledger.isEmpty());
        assertFalse(ledger.isSuspect(second, 5L, MEMORY));
    }

    @Test
    void pruneDropsOnlyExpiredEntriesAndNullsAreIgnored() {
        HostileBotLedger.Core ledger = new HostileBotLedger.Core();
        UUID old = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        ledger.mark(old, 0L, "hit");
        ledger.mark(fresh, 700L, "hit");
        ledger.prune(MEMORY + 100L, MEMORY);
        assertNull(ledger.entry(old));
        assertTrue(ledger.isMarked(fresh, MEMORY + 100L, MEMORY));
        ledger.mark(null, 0L, "hit");
        ledger.suspect(null, 0L, "swing", MEMORY);
        assertFalse(ledger.isMarked(null, 0L, MEMORY));
        assertFalse(ledger.isSuspect(null, 0L, MEMORY));
    }
}
