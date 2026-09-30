package dev.spawnbotswrapper.inhabitants.combat;

import dev.spawnbotswrapper.inhabitants.combat.RangedCycleDetector.Alert;
import dev.spawnbotswrapper.inhabitants.combat.RangedCycleDetector.Sample;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ranged-loop detector: transitions, shot versus aborted, the window, the cooldown, and the alert text. */
class RangedCycleDetectorTest {
    private final RangedCycleDetector d = new RangedCycleDetector();

    private static Sample using(long tick, String item, int useTicks) {
        return new Sample(tick, true, item, useTicks, false, 3, item, -1, 4.0, true);
    }

    private static Sample idle(long tick) {
        return new Sample(tick, false, "none", 0, false, 3, "crossbow", -1, 4.0, true);
    }

    /** One draw of {@code len} ticks starting at {@code start} that ends without a shot; returns any alert raised. */
    private Alert abortedDraw(String bot, long start, int len) {
        Alert last = null;
        for (int i = 0; i < len; i++) {
            Alert a = d.observe(bot, using(start + i, "crossbow", i));
            if (a != null) {
                last = a;
            }
        }
        Alert a = d.observe(bot, idle(start + len));
        return a != null ? a : last;
    }

    @Test
    void threeAbortedDrawsInTenSecondsRaiseOneAlert() {
        assertNull(abortedDraw("Bob", 100, 10));
        assertNull(abortedDraw("Bob", 130, 10));
        Alert alert = abortedDraw("Bob", 160, 10);
        assertNotNull(alert);
        assertEquals("Bob", alert.bot());
        assertEquals(3, alert.count());
        assertEquals(10, alert.draws().get(0).ticks());
        assertEquals("crossbow", alert.draws().get(0).item());
    }

    @Test
    void twoAbortsAreNotALoop() {
        assertNull(abortedDraw("Bob", 100, 10));
        assertNull(abortedDraw("Bob", 130, 10));
    }

    @Test
    void abortsSpreadOverMoreThanTheWindowDoNotAccumulate() {
        assertNull(abortedDraw("Bob", 0, 10));
        assertNull(abortedDraw("Bob", 150, 10));
        assertNull(abortedDraw("Bob", 300, 10));
        assertNull(abortedDraw("Bob", 450, 10));
    }

    @Test
    void aBowDrawEndedByAFreshArrowIsAShotNotAnAbort() {
        for (int round = 0; round < 6; round++) {
            long start = 100 + round * 30L;
            for (int i = 0; i < 20; i++) {
                assertNull(d.observe("Bob", new Sample(start + i, true, "bow", i, false, 1, "bow", -1, 6, true)));
            }
            // the arrow spawned in the very tick the draw ended
            assertNull(d.observe("Bob", new Sample(start + 20, false, "none", 0, false, 1, "bow", start + 20, 6, true)));
        }
    }

    @Test
    void aBowReleasedWithoutAnArrowIsAnAbort() {
        long lastShot = 50; // an old arrow from before every draw below
        Alert alert = null;
        for (int round = 0; round < 3; round++) {
            long start = 100 + round * 20L;
            for (int i = 0; i < 6; i++) {
                d.observe("Bob", new Sample(start + i, true, "bow", i, false, 1, "bow", lastShot, 6, true));
            }
            Alert a = d.observe("Bob", new Sample(start + 6, false, "none", 0, false, 1, "bow", lastShot, 6, true));
            if (a != null) {
                alert = a;
            }
        }
        assertNotNull(alert);
        assertEquals("bow", alert.draws().get(0).item());
    }

    @Test
    void aCrossbowThatEndsTheDrawLoadedIsCompleted() {
        for (int round = 0; round < 6; round++) {
            long start = 100 + round * 40L;
            for (int i = 0; i < 25; i++) {
                assertNull(d.observe("Bob", using(start + i, "crossbow", i)));
            }
            assertNull(d.observe("Bob", new Sample(start + 25, false, "none", 0, true, 3, "crossbow", -1, 4, true)));
        }
    }

    @Test
    void theAlertSaysWhatChangedAtTheStop() {
        // draw 1: stopped by a slot switch
        for (int i = 0; i < 8; i++) {
            d.observe("Bob", using(100 + i, "crossbow", i));
        }
        d.observe("Bob", new Sample(108, false, "none", 0, false, 5, "iron_sword", -1, 4.0, true));
        // draw 2: line of sight lost
        for (int i = 0; i < 8; i++) {
            d.observe("Bob", using(120 + i, "crossbow", i));
        }
        d.observe("Bob", new Sample(128, false, "none", 0, false, 3, "crossbow", -1, 4.0, false));
        // draw 3: nothing visible changed
        for (int i = 0; i < 8; i++) {
            d.observe("Bob", using(140 + i, "crossbow", i));
        }
        Alert alert = d.observe("Bob", idle(148));
        assertNotNull(alert);
        assertEquals(3, alert.draws().size());
        assertTrue(alert.draws().get(0).why().contains("slot switch 3->5"), alert.draws().get(0).why());
        assertTrue(alert.draws().get(0).why().contains("main hand crossbow->iron_sword"));
        assertTrue(alert.draws().get(1).why().contains("line of sight lost"), alert.draws().get(1).why());
        assertEquals("stopped with nothing else changed", alert.draws().get(2).why());
    }

    @Test
    void aTargetThatMovedAwayAndOneThatVanishedAreNamed() {
        for (int i = 0; i < 4; i++) {
            d.observe("Bob", using(100 + i, "bow", i));
        }
        d.observe("Bob", new Sample(104, false, "none", 0, false, 3, "crossbow", -1, 9.0, true));
        for (int i = 0; i < 4; i++) {
            d.observe("Bob", using(110 + i, "bow", i));
        }
        d.observe("Bob", new Sample(114, false, "none", 0, false, 3, "crossbow", -1, -1, null));
        for (int i = 0; i < 4; i++) {
            d.observe("Bob", using(120 + i, "bow", i));
        }
        Alert alert = d.observe("Bob", idle(124));
        assertNotNull(alert);
        assertTrue(alert.draws().get(0).why().contains("target distance 4.0->9.0"), alert.draws().get(0).why());
        assertTrue(alert.draws().get(1).why().contains("target lost"), alert.draws().get(1).why());
    }

    @Test
    void aRestartWithoutAStopIsDetectedFromTheUseTicksDropping() {
        d.observe("Bob", using(100, "crossbow", 0));
        d.observe("Bob", using(101, "crossbow", 1));
        d.observe("Bob", using(102, "crossbow", 2));
        assertNull(d.observe("Bob", using(103, "crossbow", 0))); // restarted: abort 1
        d.observe("Bob", using(104, "crossbow", 1));
        assertNull(d.observe("Bob", using(105, "crossbow", 0))); // abort 2
        d.observe("Bob", using(106, "crossbow", 1));
        Alert alert = d.observe("Bob", using(107, "crossbow", 0)); // abort 3
        assertNotNull(alert);
        assertTrue(alert.draws().get(0).why().contains("use restarted"), alert.draws().get(0).why());
    }

    @Test
    void switchingBetweenBowAndCrossbowEndsTheFirstDraw() {
        d.observe("Bob", using(100, "bow", 0));
        d.observe("Bob", using(101, "bow", 1));
        d.observe("Bob", using(102, "crossbow", 0)); // abort 1, new draw
        d.observe("Bob", using(103, "crossbow", 1));
        d.observe("Bob", using(104, "bow", 0)); // abort 2
        d.observe("Bob", using(105, "bow", 1));
        Alert alert = d.observe("Bob", using(106, "crossbow", 0)); // abort 3
        assertNotNull(alert);
        assertEquals("bow", alert.draws().get(0).item());
        assertEquals("crossbow", alert.draws().get(1).item());
        assertTrue(alert.draws().get(0).why().contains("switched to using crossbow"), alert.draws().get(0).why());
    }

    @Test
    void theAlertIsRateLimitedPerBotAndFiresAgainAfterTheCooldown() {
        assertNull(abortedDraw("Bob", 100, 5));
        assertNull(abortedDraw("Bob", 120, 5));
        assertNotNull(abortedDraw("Bob", 140, 5));
        assertNull(abortedDraw("Bob", 160, 5), "the loop goes on but the log must not");
        assertNull(abortedDraw("Bob", 180, 5));
        long later = 145 + RangedCycleDetector.ALERT_COOLDOWN_TICKS + 40;
        assertNull(abortedDraw("Bob", later, 5));
        assertNull(abortedDraw("Bob", later + 20, 5));
        assertNotNull(abortedDraw("Bob", later + 40, 5));
    }

    @Test
    void anotherBotHasItsOwnHistoryAndCooldown() {
        assertNull(abortedDraw("Bob", 100, 5));
        assertNull(abortedDraw("Alice", 110, 5));
        assertNull(abortedDraw("Bob", 120, 5));
        assertNull(abortedDraw("Alice", 130, 5));
        assertNotNull(abortedDraw("Bob", 140, 5));
        assertNotNull(abortedDraw("Alice", 150, 5));
    }

    @Test
    void aServerClockRestartForgetsTheOldHistory() {
        assertNull(abortedDraw("Bob", 5000, 5));
        assertNull(abortedDraw("Bob", 5020, 5));
        assertNull(abortedDraw("Bob", 10, 5), "a new server: the old aborts do not count");
    }

    @Test
    void forgettingABotDropsItsHalfFinishedDrawWithoutCountingAnAbort() {
        d.observe("Bob", using(100, "crossbow", 0));
        d.observe("Bob", using(101, "crossbow", 1));
        assertTrue(d.tracking("Bob"));
        d.forget("Bob");
        assertFalse(d.tracking("Bob"));
        assertNull(d.observe("Bob", idle(102)));
    }

    @Test
    void retainOnlyDropsTheOthersAndIdleBotsAreNotTracked() {
        d.observe("Bob", idle(1));
        d.observe("Alice", using(1, "bow", 0));
        assertFalse(d.tracking("Bob"));
        assertTrue(d.tracking("Alice"));
        d.retainOnly(Set.of("Bob"));
        assertEquals(1, d.trackedBots());
        assertFalse(d.tracking("Alice"));
    }

    @Test
    void theLogLineSaysWhoHowManyTheTimingsAndTheState() {
        Alert alert = null;
        for (int r = 0; r < 3; r++) {
            Alert a = abortedDraw("Bob", 100 + r * 30L, 12);
            if (a != null) {
                alert = a;
            }
        }
        assertNotNull(alert);
        String line = RangedCycleDetector.line(alert, "slot=3 main=crossbow(unloaded)");
        assertTrue(line.startsWith("ranged loop: Bob aborted 3 bow/crossbow draws in 10 s | draws: "), line);
        assertTrue(line.contains("crossbow started t100 lasted 12 ticks, ended: stopped with nothing else changed"), line);
        assertTrue(line.contains("ticks between a stop and the next start: 18,18"), line);
        assertTrue(line.endsWith(" | state: slot=3 main=crossbow(unloaded)"), line);
    }
}
