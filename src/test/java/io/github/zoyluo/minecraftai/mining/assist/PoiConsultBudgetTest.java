package io.github.zoyluo.minecraftai.mining.assist;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Design 6.6: "At most 6 consults per mission (at most 2 cavern-only), at least 400 ticks between consults
 * per bot, at most 2 in flight globally. The circuit breaker opens after 3 consecutive failures for 6000
 * ticks." */
class PoiConsultBudgetTest {
    private static final UUID BOT = new UUID(1L, 1L);
    private static final UUID OTHER_BOT = new UUID(2L, 2L);
    private static final UUID MISSION = new UUID(9L, 9L);
    private static final MiningAssistConfig.Advisor CFG = MiningAssistConfig.Advisor.DEFAULTS;

    @BeforeEach
    @AfterEach
    void reset() {
        PoiConsultBudget.clearAll();
    }

    private static String key(UUID bot) {
        return PoiConsultBudget.keyFor(bot, MISSION, null);
    }

    @Test
    void aFreshMissionAndBotMayConsult() {
        assertTrue(PoiConsultBudget.canConsult(key(BOT), BOT, false, 1000, CFG));
    }

    @Test
    void reserveThenReleaseFreesTheInFlightSlot() {
        assertEquals(0, PoiConsultBudget.inFlight());
        PoiConsultBudget.reserve(key(BOT), BOT, false, 1000);
        assertEquals(1, PoiConsultBudget.inFlight());
        PoiConsultBudget.release();
        assertEquals(0, PoiConsultBudget.inFlight());
    }

    @Test
    void atMostTwoConsultsMayBeInFlightGlobally() {
        PoiConsultBudget.reserve(key(BOT), BOT, false, 1000);
        PoiConsultBudget.reserve(key(OTHER_BOT), OTHER_BOT, false, 1000);
        assertEquals(2, PoiConsultBudget.inFlight());
        assertFalse(PoiConsultBudget.canConsult(PoiConsultBudget.keyFor(new UUID(3L, 3L), MISSION, null),
                new UUID(3L, 3L), false, 1000, CFG), "a third bot must wait for a slot to free");
        PoiConsultBudget.release();
        assertTrue(PoiConsultBudget.canConsult(PoiConsultBudget.keyFor(new UUID(3L, 3L), MISSION, null),
                new UUID(3L, 3L), false, 1001, CFG));
    }

    @Test
    void aBotMustWaitTheConfiguredIntervalSinceItsOwnLastConsult() {
        PoiConsultBudget.reserve(key(BOT), BOT, false, 1000);
        PoiConsultBudget.release();
        assertFalse(PoiConsultBudget.canConsult(key(BOT), BOT, false, 1000 + CFG.minIntervalTicks() - 1, CFG));
        assertTrue(PoiConsultBudget.canConsult(key(BOT), BOT, false, 1000 + CFG.minIntervalTicks(), CFG));
    }

    @Test
    void anotherBotIsNeverBlockedByThisBotsInterval() {
        PoiConsultBudget.reserve(key(BOT), BOT, false, 1000);
        PoiConsultBudget.release();
        assertTrue(PoiConsultBudget.canConsult(key(OTHER_BOT), OTHER_BOT, false, 1001, CFG));
    }

    @Test
    void atMostSixConsultsPerMissionEvenAcrossManyBots() {
        int tick = 0;
        for (int i = 0; i < CFG.maxConsultsPerMission(); i++) {
            tick += CFG.minIntervalTicks();
            assertTrue(PoiConsultBudget.canConsult(key(BOT), BOT, false, tick, CFG), "consult " + i + " should fit the budget");
            PoiConsultBudget.reserve(key(BOT), BOT, false, tick);
            PoiConsultBudget.release();
        }
        tick += CFG.minIntervalTicks();
        assertFalse(PoiConsultBudget.canConsult(key(BOT), BOT, false, tick, CFG),
                "the mission has used its " + CFG.maxConsultsPerMission() + " consults");
    }

    @Test
    void atMostTwoCavernOnlyConsultsPerMissionEvenWithBudgetRemaining() {
        int tick = 0;
        for (int i = 0; i < PoiConsultBudget.MAX_CAVERN_ONLY_PER_MISSION; i++) {
            tick += CFG.minIntervalTicks();
            assertTrue(PoiConsultBudget.canConsult(key(BOT), BOT, true, tick, CFG));
            PoiConsultBudget.reserve(key(BOT), BOT, true, tick);
            PoiConsultBudget.release();
        }
        tick += CFG.minIntervalTicks();
        assertFalse(PoiConsultBudget.canConsult(key(BOT), BOT, true, tick, CFG),
                "cavern-only cap reached even though the plain mission cap has room left");
        assertTrue(PoiConsultBudget.canConsult(key(BOT), BOT, false, tick, CFG),
                "a non-cavern-only consult is unaffected by the cavern-only sub-cap");
    }

    @Test
    void anUnrelatedMissionHasItsOwnBudget() {
        int tick = 0;
        for (int i = 0; i < CFG.maxConsultsPerMission(); i++) {
            tick += CFG.minIntervalTicks();
            PoiConsultBudget.reserve(key(BOT), BOT, false, tick);
            PoiConsultBudget.release();
        }
        String otherMissionKey = PoiConsultBudget.keyFor(BOT, new UUID(8L, 8L), null);
        assertTrue(PoiConsultBudget.canConsult(otherMissionKey, BOT, false, tick + CFG.minIntervalTicks(), CFG));
    }

    @Test
    void theBreakerOpensAfterTheConfiguredConsecutiveFailuresAndBlocksEveryMissionAndBot() {
        int tick = 1000;
        for (int i = 0; i < CFG.breakerFailures(); i++) {
            PoiConsultBudget.recordFailure(tick, CFG);
        }
        assertTrue(PoiConsultBudget.breakerOpen(tick));
        assertFalse(PoiConsultBudget.canConsult(key(BOT), BOT, false, tick, CFG));
        assertFalse(PoiConsultBudget.canConsult(key(OTHER_BOT), OTHER_BOT, false, tick, CFG),
                "the breaker is process-global, not per-bot");
    }

    @Test
    void theBreakerClosesAgainAfterItsOpenWindow() {
        int tick = 1000;
        for (int i = 0; i < CFG.breakerFailures(); i++) {
            PoiConsultBudget.recordFailure(tick, CFG);
        }
        assertFalse(PoiConsultBudget.breakerOpen(tick + CFG.breakerOpenTicks()));
        assertTrue(PoiConsultBudget.canConsult(key(BOT), BOT, false, tick + CFG.breakerOpenTicks(), CFG));
    }

    @Test
    void aSuccessResetsTheConsecutiveFailureStreak() {
        PoiConsultBudget.recordFailure(1000, CFG);
        PoiConsultBudget.recordFailure(1001, CFG);
        assertEquals(2, PoiConsultBudget.consecutiveFailures());
        PoiConsultBudget.recordSuccess();
        assertEquals(0, PoiConsultBudget.consecutiveFailures());
        for (int i = 0; i < CFG.breakerFailures() - 1; i++) {
            PoiConsultBudget.recordFailure(2000 + i, CFG);
        }
        assertFalse(PoiConsultBudget.breakerOpen(2000), "one failure short of the threshold after the reset");
    }

    @Test
    void clearAllResetsEveryPieceOfState() {
        PoiConsultBudget.reserve(key(BOT), BOT, true, 1000);
        for (int i = 0; i < CFG.breakerFailures(); i++) {
            PoiConsultBudget.recordFailure(1000 + i, CFG);
        }
        PoiConsultBudget.clearAll();
        assertEquals(0, PoiConsultBudget.inFlight());
        assertEquals(0, PoiConsultBudget.consecutiveFailures());
        assertFalse(PoiConsultBudget.breakerOpen(1000));
        assertEquals(0, PoiConsultBudget.size());
        assertTrue(PoiConsultBudget.canConsult(key(BOT), BOT, true, 1000, CFG));
    }
}
