package io.github.zoyluo.minecraftai.mining.assist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure rules of the break peek; the world-facing part is covered by the source contract. */
class BreakPeekRulesTest {
    @Test
    void openSpaceTheSweepAlreadySawIsNotNew() {
        assertTrue(BreakPeek.alreadySeenOpen(ObservedOccupancy.AIR));
        assertTrue(BreakPeek.alreadySeenOpen(ObservedOccupancy.FLUID));
    }

    @Test
    void unknownAndSolidCellsAreNotOpenSpaceAnyRayHasSeen() {
        assertFalse(BreakPeek.alreadySeenOpen(ObservedOccupancy.UNKNOWN),
                "never seen: a wall that opens into it is a real discovery");
        assertFalse(BreakPeek.alreadySeenOpen(ObservedOccupancy.SOLID),
                "seen as solid, open now: something changed the world");
    }

    @Test
    void aBotThatKeepsOpeningNewPocketsDoesNotHoldTheBreakthroughRateForGood() {
        MiningAssistState state = new MiningAssistState(AssistTestSupport.BOT);
        // The worst case: a break every ten ticks, each opening an undug pocket no ray has seen. One minute is
        // 120 attempts; without the gap every one of them restarted the 96-rays-per-tick sweep.
        int attempts = 0;
        int restarts = 0;
        for (int tick = 1000; tick < 1000 + 1200; tick += 10) {
            attempts++;
            if (state.requestBreakthrough(tick)) {
                restarts++;
            }
        }
        assertEquals(120, attempts);
        assertEquals(1200 / MiningAssistState.MIN_BREAKTHROUGH_GAP_TICKS, restarts);
        assertEquals(attempts - restarts, state.counters().breakthroughsDeferred);
    }
}
