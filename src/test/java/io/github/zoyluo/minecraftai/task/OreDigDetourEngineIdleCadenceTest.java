package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.mining.assist.DetourPhase;
import io.github.zoyluo.minecraftai.mining.assist.SightingLedger;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The IDLE start-check cadence of {@link OreDigDetourEngine#tick} (design 4.3), replacing the old pure
 * {@code DetourStartSelector.checkDue} tests now that the phase test is engine state
 * ({@code nextStartCheckTick}) rather than a stateless {@code (taskTick + staggerSeed) % 10 == 0} predicate.
 *
 * <p>Root cause this guards against: a stateless phase test loses a due check to a busy tick (owners not idle)
 * and the next 10-tick slot might land busy again too, so a sighted valuable could go undetoured indefinitely
 * during continuous blind strip-mining (reproduced 2 FAIL / 3 PASS on identical code before this fix, traced to
 * exactly this). The fix: a due check survives every busy IDLE tick and fires on the first tick it is both due
 * and idle; the expensive part of the check (past the cheap owners/age gate) still runs at most once every
 * {@link DetourStartSelector#START_CHECK_INTERVAL_TICKS} ticks, and the very first due tick of a fresh engine is
 * unchanged (still staggered by {@link DetourHost#staggerSeed()}).</p>
 */
class OreDigDetourEngineIdleCadenceTest {

    /**
     * {@code ownersIdleCalls} counts {@link #ownersIdle()}: {@code select()}'s very first read (the left operand
     * of {@code !host.ownersIdle() || host.now() < MIN_TASK_AGE_TICKS}), so it fires on every invocation of
     * {@code select()} whether or not that invocation gets past the cheap gate -- a direct signal for "the engine
     * decided a check was due this tick", independent of age. {@code sightingsCalls} counts {@link #sightings()},
     * reached only once {@code select()} gets past that cheap gate -- a direct signal for "the expensive part
     * actually ran" (consuming the due check), independent of whatever it finds afterward (including the
     * separate, unrelated 200-tick minimum-interval ledger rule, design 4.3, which this class never exercises).
     */
    private static final class CountingFakeDetourHost extends FakeDetourHost {
        int ownersIdleCalls;
        int sightingsCalls;

        @Override
        public boolean ownersIdle() {
            ownersIdleCalls++;
            return super.ownersIdle();
        }

        @Override
        public List<SightingLedger.Sighting> sightings() {
            sightingsCalls++;
            return super.sightings();
        }
    }

    @Test
    void aDueCheckSurvivesBusyTicksAndFiresOnTheFirstIdleTickAfterward() {
        CountingFakeDetourHost host = new CountingFakeDetourHost();
        host.staggerSeed = 0; // first due tick is task tick 0; host.now starts at 100, already past it
        host.ownersIdle = false;
        BlockPos pos = new BlockPos(3, 40, 0);
        host.sightings.add(new SightingLedger.Sighting(pos, "diamond_ore", 100, 0, 0));
        host.poseFn = ore -> new DetourHost.Pose(host.feet(), true); // zero transit: selects immediately
        OreDigDetourEngine engine = new OreDigDetourEngine();

        // Due from the first tick, but owners are busy: the check must not be lost, just retried.
        for (int i = 0; i < 5; i++) {
            host.tickClock();
            OreDigDetourEngine.Result r = engine.tick(host);
            assertEquals(OreDigDetourEngine.Kind.IDLE, r.kind());
        }
        assertEquals(0, host.sightingsCalls, "still busy: the expensive part must not have run yet");
        assertEquals(DetourPhase.IDLE, engine.phase());

        // Owners go idle: the very next IDLE tick must fire the (still-due) check, not wait another interval.
        host.ownersIdle = true;
        host.tickClock();
        OreDigDetourEngine.Result r = engine.tick(host);
        assertEquals(OreDigDetourEngine.Kind.CONSUMED, r.kind());
        assertEquals(1, host.sightingsCalls);
        assertEquals(DetourPhase.MINE, engine.phase(), "the sighting should have started a detour right away");
    }

    @Test
    void theExpensivePartRunsAtMostOncePerIntervalOnceItHasRunOnce() {
        CountingFakeDetourHost host = new CountingFakeDetourHost();
        host.staggerSeed = 0; // due from task tick 0; host.now starts at 100
        host.ownersIdle = true; // idle throughout: nothing here should ever be lost to a busy tick

        OreDigDetourEngine engine = new OreDigDetourEngine();
        host.tickClock(); // now = 101, already due
        engine.tick(host);
        assertEquals(1, host.sightingsCalls, "due at the first tick: the expensive part ran exactly once");

        // The next START_CHECK_INTERVAL_TICKS - 1 ticks must not run it again.
        for (int i = 0; i < DetourStartSelector.START_CHECK_INTERVAL_TICKS - 1; i++) {
            host.tickClock();
            engine.tick(host);
        }
        assertEquals(1, host.sightingsCalls, "not due yet: the expensive part must not have run again");

        // The tick exactly one interval later is due again.
        host.tickClock();
        engine.tick(host);
        assertEquals(2, host.sightingsCalls, "one full interval later: due again exactly once");
    }

    @Test
    void theInitialStaggerOfAFreshEngineMatchesTheOldCheckDueFormula() {
        // Old checkDue(taskTick, staggerSeed): due when (taskTick + staggerSeed) % 10 == 0.
        // checkDue(3, 7) was true (3 + 7 = 10): the first due tick for staggerSeed=7 is task tick 3. Asserted
        // through ownersIdleCalls (select() invoked at all), not sightingsCalls, so MIN_TASK_AGE_TICKS (60) --
        // which task tick 3 is well under -- cannot confound "due" with "past the cheap gate".
        CountingFakeDetourHost host = new CountingFakeDetourHost();
        host.staggerSeed = 7;
        host.now = 0; // a fresh task/engine at its very first tick
        host.ownersIdle = true;
        OreDigDetourEngine engine = new OreDigDetourEngine();

        for (int tick = 1; tick <= 2; tick++) {
            host.tickClock(); // now becomes 1, then 2
            engine.tick(host);
            assertEquals(0, host.ownersIdleCalls, "not due until task tick 3 (staggerSeed 7)");
        }
        host.tickClock(); // now = 3: due
        engine.tick(host);
        assertEquals(1, host.ownersIdleCalls, "due exactly at task tick 3, matching the old checkDue(3, 7)");
    }

    @Test
    void aFinishedExcursionWaitsTheNormalIntervalBeforeItsNextStartCheck() {
        CountingFakeDetourHost host = new CountingFakeDetourHost();
        host.staggerSeed = 0;
        host.ownersIdle = true;
        BlockPos seed = new BlockPos(5, 40, 0);
        DetourHost.Anchor anchor = host.captureAnchor();
        DetourHost.Pose pose = host.poseFor(seed, anchor, java.util.Set.of());
        OreDigDetourEngine engine = new OreDigDetourEngine();
        engine.start(host, new DetourStartSelector.Selection(seed, "diamond_ore", 100, 5.0D, pose, List.of(seed), anchor));

        // Run the detour to completion (zero-transit, single member: mines and returns).
        int guard = 0;
        OreDigDetourEngine.Result r;
        do {
            host.tickClock();
            r = engine.tick(host);
            guard++;
        } while (r.kind() != OreDigDetourEngine.Kind.FINISHED && guard < 5000);
        assertEquals(OreDigDetourEngine.Kind.FINISHED, r.kind());
        assertEquals(DetourPhase.IDLE, engine.phase());

        int finishTick = host.now;
        int ownersIdleCallsAtFinish = host.ownersIdleCalls;
        // ownersIdleCalls (select()'s very first read, made on every invocation whatever it decides next) is the
        // signal here, not sightingsCalls: the ledger's own, separate 200-tick minimum-interval rule (design 4.3)
        // would legitimately refuse a real start this soon after a detour just ended, and select() returns before
        // ever calling host.sightings() when it does -- irrelevant to the one thing this test pins, which is only
        // when the engine's OWN due check next runs the expensive part of select() AT ALL.

        // Every tick up to (not including) the normal interval boundary must not run the expensive part: no
        // free check right after finishing.
        for (int i = 1; i < DetourStartSelector.START_CHECK_INTERVAL_TICKS; i++) {
            host.tickClock();
            engine.tick(host);
            assertEquals(ownersIdleCallsAtFinish, host.ownersIdleCalls,
                    "must not check before finishTick + " + DetourStartSelector.START_CHECK_INTERVAL_TICKS
                            + " (now=" + host.now + ")");
        }
        assertEquals(finishTick + DetourStartSelector.START_CHECK_INTERVAL_TICKS - 1, host.now);

        // The tick exactly one normal interval after finishing is due again.
        host.tickClock();
        engine.tick(host);
        assertEquals(finishTick + DetourStartSelector.START_CHECK_INTERVAL_TICKS, host.now);
        assertEquals(ownersIdleCallsAtFinish + 1, host.ownersIdleCalls, "due exactly one normal interval after finishing");
    }
}
