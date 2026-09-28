package io.github.zoyluo.minecraftai.mining.assist;

import org.junit.jupiter.api.Test;

import static io.github.zoyluo.minecraftai.mining.assist.AssistTestSupport.BOT;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link MiningAssistLog#noteDetourEvent}: the pure classifier that wires the engine's own detour log
 * events (P1 contract H.1) into the six window counters of {@link SenseCounters}, without logging anything
 * itself.
 */
class MiningAssistLogTest {
    @Test
    void eachOfTheSixEventsBumpsExactlyItsOwnCounterAndNothingElse() {
        record Case(String event, java.util.function.ToLongFunction<SenseCounters> counter) {
        }
        java.util.List<Case> cases = java.util.List.of(
                new Case("ore_dig_detour_start", c -> c.detourStarts),
                new Case("ore_dig_detour_break", c -> c.detourBreaks),
                new Case("ore_dig_detour_seal", c -> c.detourSeals),
                new Case("ore_dig_detour_drop_lost", c -> c.detourDropsLost),
                new Case("ore_dig_detour_abort", c -> c.detourAborts),
                new Case("ore_dig_detour_return_rebased", c -> c.detourRebases));
        for (Case each : cases) {
            MiningAssistState state = new MiningAssistState(BOT);
            MiningAssistLog.noteDetourEvent(state, each.event());
            for (Case other : cases) {
                long expected = other.event().equals(each.event()) ? 1L : 0L;
                assertEquals(expected, other.counter().applyAsLong(state.counters()),
                        each.event() + " must bump only its own counter, checked against " + other.event());
            }
        }
    }

    @Test
    void anEventThatIsNotOneOfTheSixIsANoOp() {
        MiningAssistState state = new MiningAssistState(BOT);
        for (String event : java.util.List.of("ore_dig_detour_skip", "ore_dig_detour_route", "ore_dig_detour_end",
                "ore_dig_detour_orphan", "ore_dig_detour_cursor_drift", "ore_dig_detour_lava_claimed",
                "ore_dig_detour_resume_return", "something_unrelated")) {
            MiningAssistLog.noteDetourEvent(state, event);
        }
        SenseCounters counters = state.counters();
        assertEquals(0, counters.detourStarts);
        assertEquals(0, counters.detourBreaks);
        assertEquals(0, counters.detourSeals);
        assertEquals(0, counters.detourDropsLost);
        assertEquals(0, counters.detourAborts);
        assertEquals(0, counters.detourRebases);
    }

    @Test
    void aNullStateOrEventIsANoOpNeverAThrow() {
        assertDoesNotThrow(() -> MiningAssistLog.noteDetourEvent(null, "ore_dig_detour_start"));
        assertDoesNotThrow(() -> MiningAssistLog.noteDetourEvent(new MiningAssistState(BOT), null));
    }
}
