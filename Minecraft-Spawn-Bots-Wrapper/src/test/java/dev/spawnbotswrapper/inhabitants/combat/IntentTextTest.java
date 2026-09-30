package dev.spawnbotswrapper.inhabitants.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The per-bot part of the diagnostics' upstream text: what PvP BOT targets and intends. */
class IntentTextTest {

    @Test
    void aBotDrawingAtATarget() {
        assertEquals("target=Steve mode=RANGED draw=1/12", IntentText.of("Steve", "RANGED", true, 12));
    }

    @Test
    void anIdleBotWithNoTargetSaysNone() {
        assertEquals("target=none mode=MELEE draw=0", IntentText.of(null, "MELEE", false, 0));
        assertEquals("target=none mode=MELEE draw=0", IntentText.of(null, "MELEE", false, 7), "no ticks when not drawing");
    }

    @Test
    void unreadableFieldsAreLeftOutRatherThanInvented() {
        assertEquals("target=Steve", IntentText.of("Steve", null, null, null));
        assertEquals("target=Steve mode=RANGED draw=1", IntentText.of("Steve", "RANGED", true, null));
        assertEquals("target=unreadable", IntentText.unreadable());
    }

    @Test
    void joinKeepsWhicheverPartsThereAre() {
        assertEquals("global(combat=1) target=none", IntentText.join("global(combat=1)", "target=none"));
        assertEquals("global(combat=1)", IntentText.join("global(combat=1)", null));
        assertEquals("target=none", IntentText.join(null, "target=none"));
        assertNull(IntentText.join(null, null));
    }
}
