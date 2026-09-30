package dev.spawnbotswrapper.inhabitants.combat;

import dev.spawnbotswrapper.inhabitants.combat.MeleeVetoLog.Reason;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertTrue(first.contains("1 without a clear line, 0 outside its attack range"), first);
        assertNull(log.veto(101, "DuskRaven", Reason.BLOCKED, "Steve", 1.7, 3.0));
        assertNull(log.veto(1299, "DuskRaven", Reason.OUT_OF_REACH, "Steve", 3.5, 3.0));
        assertEquals("vetoes 3 (wall 2, reach 1, of which too close 0, farthest 3.50)", log.describe("duskraven"));
    }

    @Test
    void aMinuteLaterTheNextLineReportsEverythingSinceTheLast() {
        MeleeVetoLog log = new MeleeVetoLog();
        log.veto(0, "A", Reason.BLOCKED, "Steve", 1.7, 3.0);
        log.veto(10, "A", Reason.OUT_OF_REACH, "Steve", 3.5, 3.0);
        log.veto(20, "A", Reason.BLOCKED, "Steve", 1.7, 3.0);
        String next = log.veto(MeleeVetoLog.INFO_INTERVAL_TICKS, "A", Reason.BLOCKED, "Steve", 1.7, 3.0);
        assertNotNull(next);
        assertTrue(next.contains("vetoed 3 hit(s) by A since the last line (2 without a clear line, 1 outside its attack range)"), next);
        assertTrue(next.contains("4 vetoed by it in total"), next);
    }

    @Test
    void botsAreCountedAndRateLimitedIndependently() {
        MeleeVetoLog log = new MeleeVetoLog();
        assertNotNull(log.veto(0, "A", Reason.BLOCKED, "Steve", 1.7, 3.0));
        assertNotNull(log.veto(1, "B", Reason.BLOCKED, "Steve", 1.7, 3.0));
        assertEquals("vetoes 1 (wall 1, reach 0, of which too close 0, farthest 1.70)", log.describe("B"));
        assertNull(log.describe("C"));
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
        assertEquals("vetoes 2 (wall 1, reach 1, of which too close 0, farthest 3.50)", log.describe("A"));
        log.reset();
        assertNull(log.describe("A"));
    }

    @Test
    void aTooCloseVetoNamesTheMinimumRangeAndNotTheReach() {
        MeleeVetoLog log = new MeleeVetoLog();
        String line = log.veto(0, "A", Reason.TOO_CLOSE, "Steve", 1.2, 2.0);
        assertTrue(line.contains("1 outside its attack range"), line);
        assertTrue(line.contains("at 1.20 blocks (too close, minimum range 2.00)"), line);
        assertFalse(line.contains("reach"), "a 1.2 block veto must not read as '(reach 4.63)': " + line);
        log.veto(1, "A", Reason.OUT_OF_REACH, "Steve", 5.0, 4.63);
        assertEquals("vetoes 2 (wall 0, reach 2, of which too close 1, farthest 5.00)", log.describe("A"),
                "a too-close veto is not the farthest one");
    }
}
