package dev.spawnbotswrapper.inhabitants.combat;

import dev.spawnbotswrapper.inhabitants.combat.MeleeVetoLog.Reason;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The veto counters and the one-INFO-line-per-bot-per-minute rule. */
class MeleeVetoLogTest {

    @Test
    void theFirstVetoOfABotIsLoggedAndLaterOnesInTheSameMinuteAreOnlyCounted() {
        MeleeVetoLog log = new MeleeVetoLog();
        String first = log.veto(100, "DuskRaven", Reason.BLOCKED, "Steve", 1.7, 3.0);
        assertNotNull(first);
        assertTrue(first.contains("vetoed 1 hit(s) by DuskRaven"), first);
        assertTrue(first.contains("1 without a clear line, 0 beyond reach"), first);
        assertNull(log.veto(101, "DuskRaven", Reason.BLOCKED, "Steve", 1.7, 3.0));
        assertNull(log.veto(1299, "DuskRaven", Reason.OUT_OF_REACH, "Steve", 3.5, 3.0));
        assertEquals(3, log.total("duskraven"));
        assertEquals(2, log.total("DuskRaven", Reason.BLOCKED));
        assertEquals(1, log.total("DuskRaven", Reason.OUT_OF_REACH));
    }

    @Test
    void aMinuteLaterTheNextLineReportsEverythingSinceTheLast() {
        MeleeVetoLog log = new MeleeVetoLog();
        log.veto(0, "A", Reason.BLOCKED, "Steve", 1.7, 3.0);
        log.veto(10, "A", Reason.OUT_OF_REACH, "Steve", 3.5, 3.0);
        log.veto(20, "A", Reason.BLOCKED, "Steve", 1.7, 3.0);
        String next = log.veto(MeleeVetoLog.INFO_INTERVAL_TICKS, "A", Reason.BLOCKED, "Steve", 1.7, 3.0);
        assertNotNull(next);
        assertTrue(next.contains("vetoed 3 hit(s) by A since the last line (2 without a clear line, 1 beyond reach)"), next);
        assertTrue(next.contains("4 vetoed by it in total"), next);
    }

    @Test
    void botsAreCountedAndRateLimitedIndependently() {
        MeleeVetoLog log = new MeleeVetoLog();
        assertNotNull(log.veto(0, "A", Reason.BLOCKED, "Steve", 1.7, 3.0));
        assertNotNull(log.veto(1, "B", Reason.BLOCKED, "Steve", 1.7, 3.0));
        assertEquals(2, log.totalAll());
        assertEquals(1, log.total("B"));
        assertEquals(0, log.total("C"));
    }

    @Test
    void aClockThatWentBackwardsStillWritesALine() {
        MeleeVetoLog log = new MeleeVetoLog();
        log.veto(5000, "A", Reason.BLOCKED, "Steve", 1.7, 3.0);
        assertNotNull(log.veto(10, "A", Reason.BLOCKED, "Steve", 1.7, 3.0), "a restarted server's ticks begin at 0 again");
    }

    @Test
    void describeSummarisesOneBotAndIsNullWithoutVetoes() {
        MeleeVetoLog log = new MeleeVetoLog();
        assertNull(log.describe("A"));
        log.veto(0, "A", Reason.BLOCKED, "Steve", 1.7, 3.0);
        log.veto(1, "A", Reason.OUT_OF_REACH, "Steve", 3.5, 3.0);
        assertEquals("meleeVetoes=2 (no-line 1, reach 1, farthest 3.50)", log.describe("A"));
        log.reset();
        assertNull(log.describe("A"));
        assertEquals(0, log.totalAll());
    }
}
