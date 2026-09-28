package io.github.zoyluo.minecraftai.mining.assist;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins mining-assist design 2.4/4.3/4.10/4.13 and the contract's B.1 (start rate limits, ledger lifetime). */
class MissionAssistLedgerTest {
    private static final MiningAssistConfig.Detour CFG = MiningAssistConfig.Detour.DEFAULTS;

    private final UUID bot = UUID.randomUUID();
    private final UUID mission = UUID.randomUUID();
    private final UUID job = UUID.randomUUID();

    @BeforeEach
    @AfterEach
    void clean() {
        MissionAssistLedger.clearAll();
    }

    @Test
    void keyForPrefersMissionThenJobThenAdhoc() {
        assertEquals("m:" + mission + "@" + bot, MissionAssistLedger.keyFor(bot, mission, job));
        assertEquals("m:" + mission + "@" + bot, MissionAssistLedger.keyFor(bot, mission, null));
        assertEquals("j:" + job + "@" + bot, MissionAssistLedger.keyFor(bot, null, job));
        assertEquals("adhoc:" + bot, MissionAssistLedger.keyFor(bot, null, null));
    }

    @Test
    void getReturnsTheSameEntryOnRepeatedLookupsOfOneKey() {
        MissionAssistLedger.Entry a = MissionAssistLedger.get("k", 0);
        a.noteStart(0);
        MissionAssistLedger.Entry b = MissionAssistLedger.get("k", 10);
        assertSame(a, b);
        assertEquals(1, b.detoursStarted());
        assertEquals(1, MissionAssistLedger.size());
    }

    @Test
    void getReplacesAnEntryThatWentIdleLongerThanTtl() {
        MissionAssistLedger.Entry a = MissionAssistLedger.get("k", 0);
        a.noteStart(0);
        MissionAssistLedger.Entry b = MissionAssistLedger.get("k", MissionAssistLedger.TTL_TICKS + 1);
        assertNotSame(a, b);
        assertEquals(0, b.detoursStarted());
    }

    @Test
    void clearBotDropsAllOfThatBotsKeysOnly() {
        UUID other = UUID.randomUUID();
        MissionAssistLedger.get(MissionAssistLedger.keyFor(bot, mission, null), 0);
        MissionAssistLedger.get(MissionAssistLedger.keyFor(bot, null, null), 0);
        MissionAssistLedger.get(MissionAssistLedger.keyFor(other, mission, null), 0);
        assertEquals(3, MissionAssistLedger.size());
        MissionAssistLedger.clearBot(bot);
        assertEquals(1, MissionAssistLedger.size());
    }

    @Test
    void freshEntryStartsOk() {
        MissionAssistLedger.Entry e = new MissionAssistLedger.Entry();
        assertEquals(MissionAssistLedger.StartVerdict.OK, e.startVerdict(0, CFG, 100_000));
    }

    @Test
    void disabledWinsOverEveryOtherReason() {
        MissionAssistLedger.Entry e = new MissionAssistLedger.Entry();
        e.disableDetours();
        e.noteHazard(0);
        assertTrue(e.detoursDisabled());
        assertEquals(MissionAssistLedger.StartVerdict.DISABLED, e.startVerdict(0, CFG, 100_000));
    }

    @Test
    void hazardCooldownBlocksUntilItExpires() {
        MissionAssistLedger.Entry e = new MissionAssistLedger.Entry();
        e.noteHazard(1000);
        assertEquals(MissionAssistLedger.StartVerdict.HAZARD_COOLDOWN,
                e.startVerdict(1000 + MissionAssistLedger.HAZARD_COOLDOWN_TICKS - 1, CFG, 100_000));
        assertEquals(MissionAssistLedger.StartVerdict.OK,
                e.startVerdict(1000 + MissionAssistLedger.HAZARD_COOLDOWN_TICKS, CFG, 100_000));
    }

    @Test
    void neverEndedMeansNoIntervalRestriction() {
        MissionAssistLedger.Entry e = new MissionAssistLedger.Entry();
        assertEquals(MissionAssistLedger.StartVerdict.OK, e.startVerdict(0, CFG, 100_000));
    }

    @Test
    void intervalBlocksUntilMinIntervalTicksPassedSinceLastEnd() {
        MissionAssistLedger.Entry e = new MissionAssistLedger.Entry();
        e.noteEnd(1000, true, 5);
        int interval = CFG.minIntervalTicks();
        assertEquals(MissionAssistLedger.StartVerdict.INTERVAL,
                e.startVerdict(1000 + interval - 1, CFG, 100_000));
        assertEquals(MissionAssistLedger.StartVerdict.OK, e.startVerdict(1000 + interval, CFG, 100_000));
    }

    @Test
    void routeFailuresDoubleTheIntervalUpToTheCap() {
        MissionAssistLedger.Entry e = new MissionAssistLedger.Entry();
        e.noteRouteFailure(0);
        e.noteRouteFailure(0);
        e.noteEnd(1000, false, 5); // completed=false: failures survive noteEnd
        int interval = MissionAssistLedger.intervalTicks(CFG.minIntervalTicks(), 2);
        assertEquals(MissionAssistLedger.StartVerdict.INTERVAL,
                e.startVerdict(1000 + interval - 1, CFG, 100_000));
        assertEquals(MissionAssistLedger.StartVerdict.OK, e.startVerdict(1000 + interval, CFG, 100_000));
    }

    @Test
    void aCompletedDetourResetsConsecutiveRouteFailures() {
        MissionAssistLedger.Entry e = new MissionAssistLedger.Entry();
        e.noteRouteFailure(0);
        e.noteRouteFailure(0);
        e.noteEnd(1000, true, 5);
        assertEquals(0, e.consecutiveRouteFailures());
        assertEquals(2, e.missionRouteFailures());
    }

    @Test
    void maxPerMissionBlocksAtTheConfiguredCount() {
        MissionAssistLedger.Entry e = new MissionAssistLedger.Entry();
        for (int i = 0; i < CFG.maxPerMission(); i++) {
            e.noteStart(0);
        }
        assertEquals(CFG.maxPerMission(), e.detoursStarted());
        assertEquals(MissionAssistLedger.StartVerdict.MAX_PER_MISSION, e.startVerdict(0, CFG, 100_000));
    }

    @Test
    void tickBudgetBlocksOnceDetourTicksReachTheBudget() {
        MissionAssistLedger.Entry e = new MissionAssistLedger.Entry();
        int maxElapsed = 1200; // budget = min(3600, 1200/6) = 200
        e.noteEnd(0, true, 200);
        assertEquals(200, MissionAssistLedger.detourTickBudget(maxElapsed));
        assertEquals(MissionAssistLedger.StartVerdict.TICK_BUDGET, e.startVerdict(1_000_000, CFG, maxElapsed));
    }

    @Test
    void verdictOrderIsDisabledThenHazardThenIntervalThenMaxThenBudget() {
        // Arrange an entry that would fail every rule except DISABLED, to prove DISABLED wins first.
        MissionAssistLedger.Entry e = new MissionAssistLedger.Entry();
        e.noteHazard(0);
        e.noteEnd(0, true, 0);
        for (int i = 0; i < CFG.maxPerMission(); i++) {
            e.noteStart(0);
        }
        e.disableDetours();
        assertEquals(MissionAssistLedger.StartVerdict.DISABLED, e.startVerdict(0, CFG, 1));

        // Same entry, undisabled: hazard cooldown wins over interval/max/budget.
        MissionAssistLedger.Entry e2 = new MissionAssistLedger.Entry();
        e2.noteHazard(0);
        e2.noteEnd(0, true, 0);
        for (int i = 0; i < CFG.maxPerMission(); i++) {
            e2.noteStart(0);
        }
        assertEquals(MissionAssistLedger.StartVerdict.HAZARD_COOLDOWN, e2.startVerdict(0, CFG, 1));
    }

    @Test
    void noteStartIncrementsDetoursStarted() {
        MissionAssistLedger.Entry e = new MissionAssistLedger.Entry();
        e.noteStart(0);
        e.noteStart(0);
        assertEquals(2, e.detoursStarted());
    }

    @Test
    void noteEndSetsLastEndTickAndAccumulatesDetourTicks() {
        MissionAssistLedger.Entry e = new MissionAssistLedger.Entry();
        e.noteEnd(500, true, 30);
        e.noteEnd(700, true, 20);
        assertEquals(700, e.lastEndTick());
        assertEquals(50, e.detourTicks());
    }

    @Test
    void zeroTransitOnlyArmsAtTheFailureLimitAndReArmsOnEveryLaterFailure() {
        MissionAssistLedger.Entry e = new MissionAssistLedger.Entry();
        for (int i = 0; i < MissionAssistLedger.MISSION_ROUTE_FAILURE_LIMIT - 1; i++) {
            e.noteRouteFailure(0);
        }
        assertFalse(e.zeroTransitOnly(0));
        e.noteRouteFailure(1000); // reaches the limit
        assertTrue(e.zeroTransitOnly(1000));
        assertTrue(e.zeroTransitOnly(1000 + MissionAssistLedger.ZERO_TRANSIT_ONLY_TICKS - 1));
        assertFalse(e.zeroTransitOnly(1000 + MissionAssistLedger.ZERO_TRANSIT_ONLY_TICKS));
        e.noteRouteFailure(1000 + MissionAssistLedger.ZERO_TRANSIT_ONLY_TICKS); // re-arms
        assertTrue(e.zeroTransitOnly(1000 + MissionAssistLedger.ZERO_TRANSIT_ONLY_TICKS));
    }

    @Test
    void announceAllowedIsTrueOnceThenRateLimitedFor600Ticks() {
        MissionAssistLedger.Entry e = new MissionAssistLedger.Entry();
        assertTrue(e.announceAllowed(0));
        e.noteAnnounced(0);
        assertFalse(e.announceAllowed(MissionAssistLedger.ANNOUNCE_INTERVAL_TICKS - 1));
        assertTrue(e.announceAllowed(MissionAssistLedger.ANNOUNCE_INTERVAL_TICKS));
    }

    @Test
    void intervalTicksDoublesPerFailureAndClampsAtTheCap() {
        assertEquals(200, MissionAssistLedger.intervalTicks(200, 0));
        assertEquals(400, MissionAssistLedger.intervalTicks(200, 1));
        assertEquals(800, MissionAssistLedger.intervalTicks(200, 2));
        assertEquals(MissionAssistLedger.INTERVAL_CAP_TICKS, MissionAssistLedger.intervalTicks(200, 10));
        assertEquals(MissionAssistLedger.INTERVAL_CAP_TICKS, MissionAssistLedger.intervalTicks(200, 999));
    }

    @Test
    void intervalTicksTreatsNegativeFailuresAsZero() {
        assertEquals(200, MissionAssistLedger.intervalTicks(200, -5));
    }

    @Test
    void intervalTicksNeverOverflowsWithALargeMinInterval() {
        assertEquals(MissionAssistLedger.INTERVAL_CAP_TICKS,
                MissionAssistLedger.intervalTicks(Integer.MAX_VALUE, 10));
    }

    @Test
    void detourTickBudgetDividesAndCapsAndFloorsAtZero() {
        assertEquals(0, MissionAssistLedger.detourTickBudget(0));
        assertEquals(0, MissionAssistLedger.detourTickBudget(-500));
        assertEquals(100, MissionAssistLedger.detourTickBudget(600));
        assertEquals(MissionAssistLedger.TICK_BUDGET_CAP,
                MissionAssistLedger.detourTickBudget(MissionAssistLedger.TICK_BUDGET_CAP * 6 + 6000));
    }
}
