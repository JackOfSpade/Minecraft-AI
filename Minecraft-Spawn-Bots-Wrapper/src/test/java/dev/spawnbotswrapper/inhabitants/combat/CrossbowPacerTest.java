package dev.spawnbotswrapper.inhabitants.combat;

import dev.spawnbotswrapper.inhabitants.combat.CrossbowPacer.Look;
import dev.spawnbotswrapper.inhabitants.combat.CrossbowPacer.Settings;
import dev.spawnbotswrapper.inhabitants.combat.CrossbowPacer.Verdict;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When a loaded crossbow is fired and how shots are spaced (the pure decision core of {@code mc.RangedFire}). */
class CrossbowPacerTest {
    private static final Settings DEFAULT = new Settings(true, 4, 26);
    /** Loaded, idle, target in reach, ranged mode, no cooldown. */
    private static final Look READY = new Look(true, true, false, false, true, true);

    private static Look with(Look base, String what) {
        return switch (what) {
            case "unloaded" -> new Look(base.crossbowInMainHand(), false, base.usingItem(), base.onCooldown(), base.targetReachable(), base.rangedMode());
            case "noCrossbow" -> new Look(false, base.loaded(), base.usingItem(), base.onCooldown(), base.targetReachable(), base.rangedMode());
            case "using" -> new Look(base.crossbowInMainHand(), base.loaded(), true, base.onCooldown(), base.targetReachable(), base.rangedMode());
            case "cooldown" -> new Look(base.crossbowInMainHand(), base.loaded(), base.usingItem(), true, base.targetReachable(), base.rangedMode());
            case "noTarget" -> new Look(base.crossbowInMainHand(), base.loaded(), base.usingItem(), base.onCooldown(), false, base.rangedMode());
            case "melee" -> new Look(base.crossbowInMainHand(), base.loaded(), base.usingItem(), base.onCooldown(), base.targetReachable(), false);
            case "modeUnknown" -> new Look(base.crossbowInMainHand(), base.loaded(), base.usingItem(), base.onCooldown(), base.targetReachable(), null);
            default -> throw new IllegalArgumentException(what);
        };
    }

    @Test
    void aLoadedCrossbowIsFiredOnlyAfterTheAimSettleDelay() {
        CrossbowPacer p = new CrossbowPacer();
        assertEquals(Verdict.SETTLING, p.evaluate("Bot", 100, DEFAULT, READY), "first tick it is seen loaded");
        assertEquals(Verdict.SETTLING, p.evaluate("Bot", 103, DEFAULT, READY));
        assertEquals(Verdict.FIRE, p.evaluate("Bot", 104, DEFAULT, READY), "loaded for four ticks");
    }

    @Test
    void aZeroDelayFiresAtOnce() {
        CrossbowPacer p = new CrossbowPacer();
        assertEquals(Verdict.FIRE, p.evaluate("Bot", 100, new Settings(true, 0, 26), READY));
    }

    @Test
    void eachRefusalHasItsOwnVerdictAndNothingFires() {
        CrossbowPacer p = new CrossbowPacer();
        assertEquals(Verdict.NOT_LOADED, p.evaluate("Bot", 200, DEFAULT, with(READY, "unloaded")));
        assertEquals(Verdict.NOT_LOADED, p.evaluate("Bot", 200, DEFAULT, with(READY, "noCrossbow")));
        assertEquals(Verdict.BUSY, p.evaluate("Bot", 200, DEFAULT, with(READY, "using")));
        assertEquals(Verdict.NO_TARGET, p.evaluate("Bot", 200, DEFAULT, with(READY, "noTarget")));
        assertEquals(Verdict.NOT_RANGED_MODE, p.evaluate("Bot", 200, DEFAULT, with(READY, "melee")));
        assertEquals(Verdict.DISABLED, p.evaluate("Bot", 200, new Settings(false, 4, 26), READY));
        assertEquals(Verdict.DISABLED, p.evaluate("Bot", 200, null, READY));
    }

    @Test
    void anUnreadableModeDoesNotBlockTheShot() {
        CrossbowPacer p = new CrossbowPacer();
        p.evaluate("Bot", 100, DEFAULT, with(READY, "modeUnknown"));
        assertEquals(Verdict.FIRE, p.evaluate("Bot", 104, DEFAULT, with(READY, "modeUnknown")));
    }

    @Test
    void theShotIntervalBindsFromTheLastShotEvenWhenTheCrossbowIsLoadedAgainAtOnce() {
        CrossbowPacer p = new CrossbowPacer();
        p.shot("Bot", 100);
        assertEquals(Verdict.SETTLING, p.evaluate("Bot", 105, DEFAULT, READY), "seen loaded at 105");
        assertEquals(Verdict.WAITING_INTERVAL, p.evaluate("Bot", 110, DEFAULT, READY), "settled but 10 < 26 since the shot");
        assertEquals(Verdict.WAITING_INTERVAL, p.evaluate("Bot", 125, DEFAULT, READY));
        assertEquals(Verdict.FIRE, p.evaluate("Bot", 126, DEFAULT, READY), "exactly the interval after the shot");
    }

    @Test
    void anItemCooldownAlsoHoldsTheShot() {
        CrossbowPacer p = new CrossbowPacer();
        p.evaluate("Bot", 100, DEFAULT, READY);
        assertEquals(Verdict.WAITING_INTERVAL, p.evaluate("Bot", 150, DEFAULT, with(READY, "cooldown")));
        assertEquals(Verdict.FIRE, p.evaluate("Bot", 151, DEFAULT, READY));
    }

    @Test
    void aShotRestartsTheLoadedClock() {
        CrossbowPacer p = new CrossbowPacer();
        p.evaluate("Bot", 100, DEFAULT, READY);
        assertEquals(Verdict.FIRE, p.evaluate("Bot", 104, DEFAULT, READY));
        p.shot("Bot", 104);
        assertEquals(Verdict.NOT_LOADED, p.evaluate("Bot", 105, DEFAULT, with(READY, "unloaded")));
        assertEquals(Verdict.SETTLING, p.evaluate("Bot", 140, DEFAULT, READY), "loaded again at 140: the settle delay starts anew");
        assertEquals(Verdict.FIRE, p.evaluate("Bot", 144, DEFAULT, READY));
    }

    @Test
    void anUnloadedCrossbowResetsTheClockSoAReloadSettlesAgain() {
        CrossbowPacer p = new CrossbowPacer();
        p.evaluate("Bot", 100, DEFAULT, READY);
        p.evaluate("Bot", 102, DEFAULT, with(READY, "unloaded"));
        assertEquals(Verdict.SETTLING, p.evaluate("Bot", 103, DEFAULT, READY));
        assertEquals(Verdict.FIRE, p.evaluate("Bot", 107, DEFAULT, READY));
    }

    @Test
    void botsAreIndependent() {
        CrossbowPacer p = new CrossbowPacer();
        p.shot("A", 100);
        p.evaluate("B", 100, DEFAULT, READY);
        assertEquals(Verdict.FIRE, p.evaluate("B", 104, DEFAULT, READY));
        assertEquals(Verdict.SETTLING, p.evaluate("A", 104, DEFAULT, READY));
    }

    @Test
    void aClockThatWentBackwardsIsTreatedAsANewWorld() {
        CrossbowPacer p = new CrossbowPacer();
        p.shot("Bot", 5000);
        p.evaluate("Bot", 10, DEFAULT, READY);
        assertEquals(Verdict.FIRE, p.evaluate("Bot", 14, DEFAULT, READY), "a shot from the future does not block");
    }

    @Test
    void theCooldownAfterAShotIsTheWholeIntervalOrNothingWhenDisabled() {
        assertEquals(26, CrossbowPacer.cooldownAfterShot(DEFAULT));
        assertEquals(1, CrossbowPacer.cooldownAfterShot(new Settings(true, 4, 0)));
        assertEquals(0, CrossbowPacer.cooldownAfterShot(new Settings(false, 4, 26)));
        assertEquals(0, CrossbowPacer.cooldownAfterShot(null));
    }

    @Test
    void forgettingAndResettingDropAllState() {
        CrossbowPacer p = new CrossbowPacer();
        p.shot("A", 100);
        p.evaluate("B", 100, DEFAULT, READY);
        assertEquals(Set.of("A", "B"), p.tracked());
        assertTrue(p.isTracked("A"));
        assertFalse(p.isEmpty());
        p.forget("A");
        assertFalse(p.isTracked("A"));
        assertEquals(-1, p.ticksSinceShot("A", 200));
        p.reset();
        assertTrue(p.isEmpty());
        assertEquals(Set.of(), p.tracked());
    }

    @Test
    void ticksSinceShotCountsFromTheLastShot() {
        CrossbowPacer p = new CrossbowPacer();
        p.shot("Bot", 100);
        assertEquals(20, p.ticksSinceShot("Bot", 120));
    }
}
