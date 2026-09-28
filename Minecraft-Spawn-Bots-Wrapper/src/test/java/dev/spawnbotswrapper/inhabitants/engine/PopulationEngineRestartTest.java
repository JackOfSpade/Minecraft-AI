package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.BotState;
import dev.spawnbotswrapper.inhabitants.store.StructureRecord;
import dev.spawnbotswrapper.inhabitants.store.StructureStatus;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/** Restarts, crashes, deaths and the reconciliation of inhabitants that outlive the server process. */
class PopulationEngineRestartTest {

    private static final int MANY = Integer.MAX_VALUE;

    private static StructureRecord populate(Rig rig, StructureSnapshot s) {
        rig.engine.submit(s);
        rig.runUntil(() -> rig.hasStatus(s.key(), StructureStatus.POPULATED), 400);
        return rig.record(s.key());
    }

    /** A structure whose four bots have all been requested but none has appeared yet. */
    private static StructureSnapshot allRequestedNoneAppeared(Rig rig) {
        rig.cfg.defaults = new InhabitantsConfig.Rule(1.0, 4);
        // 120 blocks/bot makes a Rig.village() (480 blocks of volume) size-cap at exactly 4.
        rig.cfg.processing.blocksPerBot = 120.0;
        rig.cfg.processing.appearTimeoutTicks = 100_000;
        rig.bots.readyAfterPolls = MANY;
        StructureSnapshot s = Rig.village(0, 0);
        rig.engine.submit(s);
        rig.run(10);
        assertEquals(4, rig.bots.requests.size());
        return s;
    }

    // ------------------------------------------------------------------ crash in the middle of a population

    @Test
    void aCrashMidPopulationResumesWithoutRerollingRenamingOrDoubleSpawning() {
        Rig rig = new Rig();
        StructureSnapshot s = allRequestedNoneAppeared(rig);
        StructureRecord before = InMemoryStorage.copy(rig.record(s.key()));
        List<String> names = Rig.names(before);
        rig.bots.bringOnline(names.get(0)); // this one did appear just before the crash

        rig.restart(false); // crash: only what had been persisted survives
        rig.bots.readyAfterPolls = 1;
        rig.engine.submit(s); // the chunk loads again
        rig.run(80);

        StructureRecord after = rig.record(s.key());
        assertEquals(StructureStatus.POPULATED, after.status);
        assertEquals(1, rig.store.recordCount());
        assertEquals(before.structureSeed, after.structureSeed, "not re-rolled");
        assertEquals(names, Rig.names(after), "not renamed");
        assertEquals(0, rig.engine.stats().structuresRolled());
        assertEquals(4, Rig.count(after, BotState.SPAWNED));

        BotRecord adopted = after.bots.get(0);
        assertEquals(1, adopted.spawnAttempts, "the bot that did appear is adopted, not requested again");
        assertEquals(1, rig.bots.requestsFor(names.get(0)));
        assertNotNull(adopted.profile);
        assertTrue(rig.bots.applied.stream().anyMatch(a -> a.name().equals(names.get(0))));
        for (int i = 1; i < 4; i++) {
            assertEquals(2, after.bots.get(i).spawnAttempts, "the interrupted attempt counts");
            assertEquals(2, rig.bots.requestsFor(names.get(i)), "retried under the same name");
        }
        assertEquals(3, rig.engine.stats().botsRequested(), "this session requested only the three that never appeared");
    }

    @Test
    void theLiveBotCapIsAccurateFromTheFirstDecisionAfterARestart() {
        Rig rig = new Rig();
        rig.cfg.processing.maxLiveBots = 3;
        StructureSnapshot a = Rig.village(0, 0);
        StructureSnapshot b = Rig.village(9, 9);
        rig.engine.submit(a);
        rig.engine.submit(b);
        rig.run(100);
        assertEquals(3, rig.bots.requests.size(), "the cap of three is used up by the first village");
        assertEquals(StructureStatus.OCCUPIED_PENDING, rig.record(b.key()).status);

        rig.cfg.processing.restoreSettleTicks = 50;
        rig.restart(true);
        rig.engine.submit(b);
        rig.run(120);
        assertEquals(3, rig.bots.requests.size(), "the inhabitants that are already online count against the cap");
        assertEquals(3, rig.engine.stats().liveBots());

        rig.bots.kill(rig.record(a.key()).bots.get(0).name);
        rig.run(60);
        assertEquals(4, rig.bots.requests.size(), "and a freed slot is used");
    }

    @Test
    void aBotOnItsLastAttemptThatDidAppearBeforeTheCrashIsAdoptedNotFailed() {
        Rig rig = new Rig();
        StructureSnapshot s = allRequestedNoneAppeared(rig);
        BotRecord last = rig.record(s.key()).bots.get(0);
        last.spawnAttempts = PopulationDriver.MAX_SPAWN_ATTEMPTS; // the crashed request was its third
        rig.store.markDirty();
        rig.bots.bringOnline(last.name);

        rig.restart(true);
        rig.bots.readyAfterPolls = 1;
        rig.engine.submit(s);
        rig.run(60);

        BotRecord after = rig.record(s.key()).bots.get(0);
        assertEquals(BotState.SPAWNED, after.state, "it exists, so it counts however many attempts it took");
        assertNotNull(after.profile);
        assertEquals(1, rig.bots.requestsFor(after.name));
    }

    @Test
    void aBotThatKeepsGettingInterruptedByCrashesIsEventuallyGivenUpOn() {
        Rig rig = new Rig();
        StructureSnapshot s = allRequestedNoneAppeared(rig); // attempt 1 of every bot is on disk
        // each crash loses the request; the attempt was still counted, so this cannot go on forever
        for (int round = 1; round <= 2; round++) {
            rig.restart(false);
            rig.engine.submit(s);
            rig.run(5);
            for (BotRecord b : rig.record(s.key()).bots) {
                assertEquals(round + 1, b.spawnAttempts, "round " + round);
                assertEquals(BotState.REQUESTED, b.state);
            }
        }
        rig.restart(false);
        rig.engine.submit(s);
        rig.run(5);
        StructureRecord r = rig.record(s.key());
        assertEquals(StructureStatus.GAVE_UP, r.status);
        for (BotRecord b : r.bots) {
            assertEquals(BotState.FAILED, b.state);
            assertEquals(PopulationDriver.MAX_SPAWN_ATTEMPTS, b.spawnAttempts);
            assertEquals("interrupted before the bot appeared", b.failure);
        }
        assertEquals(12, rig.bots.requests.size(), "4 bots x 3 attempts, and no fourth");
    }

    @Test
    void thePendingRecordWithoutASnapshotSimplyWaitsUntilTheStructureIsSeenAgain() {
        Rig rig = new Rig();
        StructureSnapshot s = allRequestedNoneAppeared(rig);
        rig.restart(false);
        rig.bots.readyAfterPolls = 1;
        rig.run(500);
        assertEquals(4, rig.bots.requests.size(), "nothing can be planned without the structure's geometry");
        assertEquals(StructureStatus.OCCUPIED_PENDING, rig.record(s.key()).status);
        assertEquals(0, rig.engine.stats().queuedStructures());

        rig.engine.submit(s);
        rig.run(60);
        assertEquals(StructureStatus.POPULATED, rig.record(s.key()).status);
    }

    @Test
    void onlyNewlyGeneratedDoesNotBlockResumingAfterARestart() {
        Rig rig = new Rig();
        rig.cfg.processing.onlyNewlyGenerated = true;
        rig.cfg.processing.restoreSettleTicks = 1_000_000; // crash before anything spawned
        StructureSnapshot generated = Rig.snapshot(Rig.OVERWORLD, Rig.VILLAGE, 0, 0, java.util.Set.of(), 5, true);
        rig.engine.submit(generated);
        rig.run(5);
        assertEquals(StructureStatus.OCCUPIED_PENDING, rig.record(generated.key()).status);

        rig.restart(false);
        rig.cfg.processing.restoreSettleTicks = 0;
        StructureSnapshot reloaded = Rig.snapshot(Rig.OVERWORLD, Rig.VILLAGE, 0, 0, java.util.Set.of(), 5, false);
        rig.engine.submit(reloaded); // after a restart the start chunk is merely loaded, not generated
        rig.run(60);
        assertEquals(StructureStatus.POPULATED, rig.record(generated.key()).status);
    }

    @Test
    void aStructureRolledOccupiedButNowExcludedIsNotResumed() {
        Rig rig = new Rig();
        rig.cfg.processing.restoreSettleTicks = 1_000_000;
        StructureSnapshot s = Rig.village(0, 0);
        rig.engine.submit(s);
        rig.run(5);
        rig.restart(false);
        rig.cfg.processing.restoreSettleTicks = 0;
        rig.cfg.exclude = new ArrayList<>(List.of(Rig.VILLAGE));
        rig.engine.submit(s);
        rig.run(100);
        assertEquals(0, rig.bots.requests.size());
        assertEquals(StructureStatus.OCCUPIED_PENDING, rig.record(s.key()).status, "stays pending, is never dropped");

        rig.cfg.exclude = new ArrayList<>();
        rig.engine.submit(s);
        rig.run(60);
        assertEquals(StructureStatus.POPULATED, rig.record(s.key()).status);
    }

    @Test
    void aRecordWhoseBotsAreAllResolvedIsSettledOnResumeWithoutAnyRequest() {
        Rig rig = new Rig();
        StructureSnapshot s = Rig.village(0, 0);
        populate(rig, s);
        // crash between the last bot being recorded and the structure being marked settled
        rig.record(s.key()).status = StructureStatus.OCCUPIED_PENDING;
        rig.store.markDirty();
        rig.restart(true);
        int requests = rig.bots.requests.size();
        rig.engine.submit(s);
        rig.run(20);
        assertEquals(StructureStatus.POPULATED, rig.record(s.key()).status);
        assertEquals(requests, rig.bots.requests.size());
    }

    @Test
    void theRestartAuditWaitsForTheSettlePeriod() {
        Rig rig = new Rig();
        StructureSnapshot s = allRequestedNoneAppeared(rig);
        String appeared = rig.record(s.key()).bots.get(0).name;
        rig.bots.bringOnline(appeared);
        rig.restart(false);
        rig.cfg.processing.restoreSettleTicks = 100;
        rig.bots.readyAfterPolls = 1;
        rig.engine.submit(s);
        rig.run(99);
        assertEquals(BotState.REQUESTED, rig.record(s.key()).bots.get(0).state, "upstream may still be restoring it");
        assertEquals(4, rig.bots.requests.size());
        rig.run(5);
        assertEquals(BotState.SPAWNED, rig.record(s.key()).bots.get(0).state);
    }

    // ------------------------------------------------------------------ death is permanent

    @Test
    void spawnedBotsAreNeverRespawnedWhenTheyAreAbsentAfterARestart() {
        Rig rig = new Rig();
        StructureSnapshot s = Rig.village(0, 0);
        StructureRecord r = populate(rig, s);
        List<String> names = Rig.names(r);
        int requests = rig.bots.requests.size();

        rig.restart(true);
        for (String n : names) {
            rig.bots.kill(n); // e.g. botsRelogs is off, or they died while the server was down
        }
        rig.engine.submit(s);
        rig.run(3000);

        assertEquals(requests, rig.bots.requests.size(), "an inhabitant is never respawned");
        assertEquals(StructureStatus.POPULATED, rig.record(s.key()).status);
        assertEquals(3, rig.record(s.key()).spawnedCount());
        assertEquals(0, rig.engine.stats().structuresRolled());
        assertEquals(0, rig.engine.stats().liveBots());
    }

    @Test
    void aBotThatStaysOfflineIsForgottenExactlyOnceAfterTheGoneConfirmPeriod() {
        Rig rig = new Rig();
        StructureSnapshot s = Rig.village(0, 0);
        StructureRecord r = populate(rig, s);
        String dead = r.bots.get(0).name;
        int requests = rig.bots.requests.size();
        rig.cfg.processing.goneConfirmTicks = 200;

        rig.bots.kill(dead);
        rig.run(200 - BotRoster.SCAN_PERIOD_TICKS - 5);
        assertTrue(rig.bots.forgets.isEmpty(), "not yet: it may just be relogging");
        rig.run(BotRoster.SCAN_PERIOD_TICKS + 10);
        assertEquals(List.of(dead), rig.bots.forgets);

        rig.run(3000);
        assertEquals(List.of(dead), rig.bots.forgets, "exactly once");
        assertEquals(requests, rig.bots.requests.size(), "never respawned");
        assertEquals(StructureStatus.POPULATED, rig.record(s.key()).status, "the structure is not touched");
        assertEquals(BotState.SPAWNED, rig.record(s.key()).bots.get(0).state, "the record keeps what happened");
    }

    @Test
    void aGoneBotThatComesBackIsRestoredAgainAndCanBeForgottenAgain() {
        Rig rig = new Rig();
        StructureSnapshot s = Rig.village(0, 0);
        StructureRecord r = populate(rig, s);
        String name = r.bots.get(1).name;
        rig.cfg.processing.goneConfirmTicks = 200;

        rig.bots.kill(name);
        rig.run(260);
        assertEquals(1, rig.bots.forgets.size());
        assertEquals(0, rig.bots.restores.size(), "a fresh spawn is already dressed and following its path");

        rig.bots.bringOnline(name);
        rig.run(BotRoster.SCAN_PERIOD_TICKS + 5);
        assertEquals(1, rig.bots.restores.size());
        assertEquals(name, rig.bots.restores.get(0).name());
        assertEquals(r.bots.get(1).profile, rig.bots.restores.get(0).profile());

        rig.run(500);
        assertEquals(1, rig.bots.restores.size(), "once per return");
        rig.bots.kill(name);
        rig.run(300);
        assertEquals(2, rig.bots.forgets.size(), "a second disappearance is a second episode");
    }

    @Test
    void aBotOfflineOnlyBrieflyIsNotForgottenAndIsRestored() {
        Rig rig = new Rig();
        StructureSnapshot s = Rig.village(0, 0);
        StructureRecord r = populate(rig, s);
        String name = r.bots.get(0).name;
        rig.cfg.processing.goneConfirmTicks = 200;
        rig.bots.kill(name);
        rig.run(100);
        rig.bots.bringOnline(name); // relogged (botsRelogs)
        rig.run(600);
        assertTrue(rig.bots.forgets.isEmpty());
        assertEquals(1, rig.bots.restores.size(), "its path following is re-established after the relog");
    }

    @Test
    void theGoneCountdownStartsOnlyAfterTheSettlePeriod() {
        Rig rig = new Rig();
        StructureSnapshot s = Rig.village(0, 0);
        StructureRecord r = populate(rig, s);
        String dead = r.bots.get(0).name;
        rig.cfg.processing.restoreSettleTicks = 300;
        rig.cfg.processing.goneConfirmTicks = 200;
        rig.restart(true);
        rig.bots.kill(dead); // upstream has not (yet) restored it

        rig.run(499);
        assertTrue(rig.bots.forgets.isEmpty(), "300 ticks of settling + 200 of confirming");
        rig.run(3);
        assertEquals(List.of(dead), rig.bots.forgets);
    }

    @Test
    void aBotThatIsRestoredLateByUpstreamDuringTheSettlePeriodIsNeverForgotten() {
        Rig rig = new Rig();
        StructureSnapshot s = Rig.village(0, 0);
        StructureRecord r = populate(rig, s);
        String name = r.bots.get(2).name;
        rig.cfg.processing.restoreSettleTicks = 300;
        rig.cfg.processing.goneConfirmTicks = 200;
        rig.restart(true);
        rig.bots.kill(name);
        rig.run(250);
        rig.bots.bringOnline(name); // upstream restores its bots one by one
        rig.run(600);
        assertTrue(rig.bots.forgets.isEmpty());
        assertEquals(1, rig.bots.restores.stream().filter(a -> a.name().equals(name)).count());
    }

    // ------------------------------------------------------------------ restoring state upstream does not persist

    @Test
    void everyOnlineManagedBotIsRestoredOnceAfterTheSettlePeriodAndNotBefore() {
        Rig rig = new Rig();
        StructureSnapshot s = Rig.village(0, 0);
        StructureRecord r = populate(rig, s);
        rig.cfg.processing.restoreSettleTicks = 100;
        rig.restart(true);

        rig.run(99);
        assertTrue(rig.bots.restores.isEmpty(), "reconciling earlier would race upstream's own restore");
        rig.run(BotRoster.SCAN_PERIOD_TICKS + 5);
        assertEquals(3, rig.bots.restores.size());
        for (BotRecord b : r.bots) {
            FakeBots.Applied call = rig.bots.restores.stream().filter(a -> a.name().equals(b.name)).findFirst().orElseThrow();
            assertEquals(b.profile, call.profile(), "restored from the STORED profile");
        }
        rig.run(2000);
        assertEquals(3, rig.bots.restores.size(), "once per bot per session");
        assertEquals(0, rig.bots.applied.size() - 3, "and never re-dressed from a new profile");
    }

    @Test
    void aBotThatIsNotYetManagedIsRestoredAsSoonAsItIs() {
        Rig rig = new Rig();
        StructureSnapshot s = Rig.village(0, 0);
        StructureRecord r = populate(rig, s);
        String name = r.bots.get(0).name;
        rig.restart(true);
        rig.bots.unmanaged.add(name.toLowerCase(Locale.ROOT));
        rig.run(200);
        assertEquals(2, rig.bots.restores.size());
        rig.bots.unmanaged.clear();
        rig.run(BotRoster.SCAN_PERIOD_TICKS + 5);
        assertEquals(3, rig.bots.restores.size());
    }

    @Test
    void restoreStillRunsWithReapplyOnRestoreOffSoPatrolsAreReassigned() {
        // reapplyOnRestore only gates re-dressing (loadout/vitals) INSIDE BotGateway.restore() itself (see
        // McBotGatewayMcTest for that half); the engine must always call restore() for an online, managed,
        // profiled bot, because that same call is what re-establishes patrol following, which PvP BOT never
        // persists across a restart regardless of this setting. Skipping the call entirely (the old bug)
        // left every patrol/guard-post bot standing still forever after a restart under this config.
        Rig rig = new Rig();
        StructureSnapshot s = Rig.village(0, 0);
        populate(rig, s);
        rig.cfg.profiles.reapplyOnRestore = false;
        rig.restart(true);
        rig.run(500);
        assertEquals(3, rig.bots.restores.size(), "restore() must still be called for every restored bot");
    }

    @Test
    void botsThatAreNotOnlineAreNotRestored() {
        Rig rig = new Rig();
        StructureRecord r = populate(rig, Rig.village(0, 0));
        rig.restart(true);
        rig.bots.kill(r.bots.get(1).name);
        rig.run(200);
        assertEquals(2, rig.bots.restores.size());
        assertTrue(rig.bots.restores.stream().noneMatch(a -> a.name().equals(r.bots.get(1).name)));
    }

    @Test
    void theRosterScanIsSpreadOverTicksNotDoneAllAtOnce() {
        Rig rig = new Rig(new InMemoryStorage(), 3);
        rig.cfg.defaults = new InhabitantsConfig.Rule(1.0, 4);
        // 150 blocks/bot makes a Rig.structure() (525 blocks of volume) size-cap at exactly 4.
        rig.cfg.processing.blocksPerBot = 150.0;
        rig.cfg.processing.maxBotsPerTick = 64;
        for (int i = 0; i < 100; i++) {
            rig.engine.submit(Rig.structure("minecraft:igloo", i, 0));
        }
        rig.run(60);
        assertEquals(400, rig.engine.stats().botsSpawned());
        rig.restart(true);
        rig.run(1); // first tick after the restart: settle is 0, so reconciling starts now
        int afterOneTick = rig.bots.restores.size();
        assertTrue(afterOneTick > 0 && afterOneTick <= 400 / BotRoster.SCAN_PERIOD_TICKS + 1, "restored " + afterOneTick + " in one tick");
        rig.run(BotRoster.SCAN_PERIOD_TICKS + 2);
        assertEquals(400, rig.bots.restores.size());
    }

    // ------------------------------------------------------------------ clean shutdown

    @Test
    void shutdownFlushesAndTheEngineThenDoesNothing() {
        Rig rig = new Rig();
        rig.engine.submit(Rig.village(0, 0));
        rig.run(2);
        rig.engine.shutdown();
        assertEquals(1, rig.store.flushCalls);
        assertEquals(rig.record(Rig.village(0, 0).key()).status,
                rig.store.persisted(Rig.village(0, 0).key()).orElseThrow().status);

        int puts = rig.store.puts;
        int requests = rig.bots.requests.size();
        rig.engine.submit(Rig.village(9, 9));
        rig.run(200);
        assertEquals(puts, rig.store.puts);
        assertEquals(requests, rig.bots.requests.size());
        assertEquals(EngineControl.ProcessOutcome.Kind.REJECTED, rig.engine.process(Rig.village(8, 8), ForceMode.ROLL).kind());
    }

    @Test
    void aCleanRestartKeepsEverythingIncludingTheCoverageDecks() {
        Rig rig = new Rig();
        StructureSnapshot s = Rig.village(0, 0);
        populate(rig, s);
        assertFalse(rig.store.decks().exportSnapshots().isEmpty());
        var decksBefore = rig.store.decks().exportSnapshots().keySet();
        rig.restart(true);
        assertEquals(decksBefore, rig.store.decks().exportSnapshots().keySet());
        assertEquals(StructureStatus.POPULATED, rig.record(s.key()).status);
    }
}
