package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.profile.GlobalCapabilities;
import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.BotState;
import dev.spawnbotswrapper.inhabitants.store.StructureRecord;
import dev.spawnbotswrapper.inhabitants.store.StructureStatus;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A bug or a broken port must never crash the server tick, and must degrade to "try again later" rather than
 * to lost or duplicated inhabitants.
 */
class PopulationEngineResilienceTest {

    private static StructureRecord populate(Rig rig, StructureSnapshot s, int maxTicks) {
        rig.engine.submit(s);
        rig.runUntil(() -> rig.store.find(s.key()).map(r -> r.status.isTerminal()).orElse(false), maxTicks);
        return rig.record(s.key());
    }

    // ------------------------------------------------------------------ the bot side misbehaves

    @Test
    void aThrowingAvailabilityCheckCountsAsUnavailable() {
        Rig rig = new Rig();
        rig.bots.throwAvailable = true;
        assertDoesNotThrow(() -> {
            rig.engine.submit(Rig.village(0, 0));
            rig.run(50);
        });
        assertEquals(0, rig.store.puts);
        assertTrue(rig.engine.containedErrors() > 0);

        rig.bots.throwAvailable = false;
        StructureRecord r = populate(rig, Rig.village(0, 0), 100);
        assertEquals(StructureStatus.POPULATED, r.status);
    }

    @Test
    void everyKindOfThrowableFromAPortIsContainedExceptOutOfMemory() {
        for (Throwable failure : new Throwable[]{
                new NoClassDefFoundError("upstream class missing"),
                new AssertionError("assertion in upstream"),
                new StackOverflowError(),
                new IllegalStateException("plain bug")}) {
            Rig rig = new Rig();
            rig.bots.availableFailure = failure;
            assertDoesNotThrow(() -> {
                rig.engine.submit(Rig.village(0, 0));
                rig.run(5);
                rig.engine.process(Rig.village(1, 1), ForceMode.OCCUPIED);
                rig.engine.stats();
            }, failure.toString());
            assertEquals(0, rig.store.puts, failure.toString());
        }
        Rig rig = new Rig();
        rig.engine.submit(Rig.village(0, 0));
        rig.bots.availableFailure = new OutOfMemoryError("test");
        assertThrows(OutOfMemoryError.class, () -> rig.run(1), "the JVM is beyond help, hiding it would only delay the crash");
    }

    @Test
    void aFailingNameCheckDefersTheSpawnWithoutUsingAnyAttempt() {
        Rig rig = new Rig();
        rig.bots.throwNameAvailable = true;
        StructureSnapshot s = Rig.village(0, 0);
        rig.engine.submit(s);
        rig.run(60);
        StructureRecord r = rig.record(s.key());
        assertEquals(0, rig.bots.requests.size());
        assertEquals(0, r.attempts);
        for (BotRecord b : r.bots) {
            assertEquals(BotState.PLANNED, b.state);
            assertEquals(0, b.spawnAttempts);
        }
        rig.bots.throwNameAvailable = false;
        rig.run(30);
        assertEquals(StructureStatus.POPULATED, rig.record(s.key()).status);
    }

    @Test
    void aThrowingSpawnRequestIsAFailedAttemptAndEventuallyGivesUp() {
        Rig rig = new Rig();
        rig.bots.throwRequest = true;
        StructureRecord r = populate(rig, Rig.village(0, 0), 200);
        assertEquals(StructureStatus.GAVE_UP, r.status);
        for (BotRecord b : r.bots) {
            assertEquals(BotState.FAILED, b.state);
            assertEquals(3, b.spawnAttempts);
            assertTrue(b.failure.startsWith("spawn request failed"), b.failure);
        }
        assertTrue(rig.engine.containedErrors() >= 9);
        assertEquals(0, rig.engine.stats().botsInFlight());
    }

    @Test
    void aGatewayThatReturnsNoHandleIsAFailureToo() {
        Rig rig = new Rig();
        rig.bots.nullHandle = true;
        StructureRecord r = populate(rig, Rig.village(0, 0), 200);
        assertEquals(StructureStatus.GAVE_UP, r.status);
        assertEquals(0, rig.engine.stats().botsInFlight());
    }

    @Test
    void aThrowingPollIsTreatedAsPendingAndTheTimeoutStillApplies() {
        Rig rig = new Rig();
        rig.bots.throwPoll = true;
        StructureRecord r = populate(rig, Rig.village(0, 0), 400);
        assertEquals(StructureStatus.GAVE_UP, r.status);
        for (BotRecord b : r.bots) {
            assertTrue(b.failure.contains("did not appear"), b.failure);
        }
        assertTrue(rig.engine.containedErrors() > 0);
    }

    @Test
    void aThrowingApplyLeavesTheBotSpawnedAndHonestlyNotApplied() {
        Rig rig = new Rig();
        rig.bots.throwApply = true;
        StructureRecord r = populate(rig, Rig.village(0, 0), 100);
        assertEquals(StructureStatus.POPULATED, r.status);
        for (BotRecord b : r.bots) {
            assertEquals(BotState.SPAWNED, b.state);
            assertFalse(b.profileApplied);
            assertNotNull(b.profile, "the stored profile stays authoritative so it can be re-applied later");
        }
    }

    @Test
    void unreadableCapabilitiesFallBackToUpstreamDefaults() {
        Rig rig = new Rig();
        rig.bots.throwCapabilities = true;
        StructureRecord r = populate(rig, Rig.village(0, 0), 100);
        assertEquals(StructureStatus.POPULATED, r.status);
        assertFalse(rig.profiles.calls.isEmpty());
        for (FakeProfiles.Call c : rig.profiles.calls) {
            assertEquals(GlobalCapabilities.upstreamDefaults(), c.capabilities());
        }
    }

    @Test
    void whenTheProfileCannotBeMadeTheUndressedBotIsRemovedAndTheAttemptCounts() {
        Rig rig = new Rig();
        rig.profiles.throwOnCreate = true;
        StructureRecord r = populate(rig, Rig.village(0, 0), 300);
        assertEquals(StructureStatus.GAVE_UP, r.status);
        for (BotRecord b : r.bots) {
            assertEquals(BotState.FAILED, b.state);
            assertEquals(3, b.spawnAttempts);
            assertTrue(b.failure.startsWith("profile generation failed"), b.failure);
            assertNull(b.profile);
        }
        assertEquals(9, rig.bots.removes.size(), "a bot that exists but cannot be dressed is not left standing around");
        assertTrue(rig.bots.applied.isEmpty());
        assertEquals(0, rig.engine.stats().liveBots());
    }

    @Test
    void aFactoryReturningNothingIsTreatedLikeAFailedFactory() {
        Rig rig = new Rig();
        rig.profiles.returnNull = true;
        StructureRecord r = populate(rig, Rig.village(0, 0), 300);
        assertEquals(StructureStatus.GAVE_UP, r.status);
        assertTrue(rig.bots.applied.isEmpty());
    }

    @Test
    void aFailingBehaviourPlannerStillYieldsASpawnedBotThatStandsStill() {
        Rig rig = new Rig();
        rig.planner.throwOnBehavior = true;
        StructureRecord r = populate(rig, Rig.village(0, 0), 100);
        assertEquals(StructureStatus.POPULATED, r.status);
        for (BotRecord b : r.bots) {
            assertEquals(BotState.SPAWNED, b.state);
            assertTrue(b.profile.behavior().waypoints().isEmpty());
        }
    }

    @Test
    void failuresWhileCleaningUpDoNotStopAnAdminReset() {
        Rig rig = new Rig();
        StructureSnapshot s = Rig.village(0, 0);
        populate(rig, s, 100);
        rig.bots.throwRemove = true;
        rig.bots.throwForget = true;
        assertTrue(rig.engine.reset(s.key(), true));
        assertTrue(rig.store.find(s.key()).isEmpty());
        assertTrue(rig.engine.containedErrors() >= 6);
    }

    @Test
    void aFailingOnlineCheckNeverMakesTheReconcilerLoopOrGiveUpOnBots() {
        Rig rig = new Rig();
        StructureSnapshot s = Rig.village(0, 0);
        populate(rig, s, 100);
        rig.restart(true);
        rig.bots.throwIsOnline = true;
        assertDoesNotThrow(() -> rig.run(2000));
        assertTrue(rig.bots.forgets.isEmpty(), "not knowing is not the same as being gone");
        assertTrue(rig.bots.restores.isEmpty());

        rig.bots.throwIsOnline = false;
        rig.run(60);
        assertEquals(3, rig.bots.restores.size(), "as soon as the server can be asked, the bots are restored");
    }

    @Test
    void aFailingRestoreIsNotRetriedInATightLoop() {
        Rig rig = new Rig();
        populate(rig, Rig.village(0, 0), 100);
        rig.restart(true);
        rig.bots.throwRestore = true;
        rig.run(2000);
        assertEquals(3, rig.bots.restoreCalls, "one attempt per bot per session");
    }

    // ------------------------------------------------------------------ the world and the planner misbehave

    @Test
    void aPlannerFailureForOneStructureDoesNotBlockTheOthersAndEventuallyGivesUpOnIt() {
        Rig rig = new Rig();
        rig.planner.throwForStructure = "igloo";
        StructureSnapshot bad = Rig.structure("minecraft:igloo", 0, 0);
        StructureSnapshot good = Rig.village(9, 9);
        rig.engine.submit(bad);
        rig.engine.submit(good);
        rig.runUntil(() -> rig.hasStatus(good.key(), StructureStatus.POPULATED)
                && rig.hasStatus(bad.key(), StructureStatus.GAVE_UP), 200);

        StructureRecord r = rig.record(bad.key());
        assertEquals(3, r.attempts, "each failure is a failed attempt, so a hopeless structure ends");
        for (BotRecord b : r.bots) {
            assertEquals(BotState.FAILED, b.state);
            assertTrue(b.failure.startsWith("gave up after repeated errors"), b.failure);
        }
        assertTrue(rig.engine.containedErrors() >= 3);
    }

    @Test
    void aFailingProbeCostsAttemptsButAStructureThatRecoversStillSucceeds() {
        Rig rig = new Rig();
        rig.world.throwOnProbe = true;
        StructureSnapshot s = Rig.village(0, 0);
        rig.engine.submit(s);
        rig.run(15); // two failed searches
        assertEquals(2, rig.record(s.key()).attempts);
        rig.world.throwOnProbe = false;
        rig.run(40);
        assertEquals(StructureStatus.POPULATED, rig.record(s.key()).status);
    }

    @Test
    void aFailingWorldSeedInDeterministicModeDropsTheRollNotTheEngine() {
        Rig rig = new Rig();
        rig.cfg.deterministic.enabled = true;
        rig.world.throwOnSeed = true;
        StructureSnapshot s = Rig.village(0, 0);
        assertDoesNotThrow(() -> {
            rig.engine.submit(s);
            rig.run(10);
        });
        assertTrue(rig.store.find(s.key()).isEmpty(), "no roll without the seed: a wrong roll could never be undone");
        assertTrue(rig.engine.containedErrors() > 0);

        rig.world.throwOnSeed = false;
        rig.engine.submit(s);
        rig.run(40);
        assertEquals(StructureStatus.POPULATED, rig.record(s.key()).status);
    }

    // ------------------------------------------------------------------ the store misbehaves

    @Test
    void aFailingStoreLookupNeverEscapesAndRecoversWhenItStopsFailing() {
        Rig rig = new Rig();
        rig.store.throwOnFind = true;
        StructureSnapshot s = Rig.village(0, 0);
        assertDoesNotThrow(() -> {
            rig.engine.submit(s);
            rig.run(20);
            rig.engine.process(s, ForceMode.OCCUPIED);
            rig.engine.reset(s.key(), true);
        });
        assertEquals(0, rig.store.puts);
        rig.store.throwOnFind = false;
        assertEquals(StructureStatus.POPULATED, populate(rig, s, 100).status);
    }

    @Test
    void aFailingPutMeansNoRollIsRecordedAndTheStructureIsSimplyDetectedAgain() {
        Rig rig = new Rig();
        rig.store.throwOnPut = true;
        StructureSnapshot s = Rig.village(0, 0);
        assertDoesNotThrow(() -> {
            rig.engine.submit(s);
            rig.run(10);
        });
        assertEquals(0, rig.store.recordCount());
        assertEquals(0, rig.bots.requests.size());
        rig.store.throwOnPut = false;
        assertEquals(StructureStatus.POPULATED, populate(rig, s, 100).status);
    }

    @Test
    void aFailingNameLookupInTheStoreStopsTheRollInsteadOfRiskingADuplicateName() {
        Rig rig = new Rig();
        rig.store.throwOnFindBot = true;
        StructureSnapshot s = Rig.village(0, 0);
        assertDoesNotThrow(() -> {
            rig.engine.submit(s);
            rig.run(10);
        });
        assertEquals(0, rig.store.recordCount());
        rig.store.throwOnFindBot = false;
        assertEquals(StructureStatus.POPULATED, populate(rig, s, 100).status);
    }

    @Test
    void aFailingSaveNeverStopsTheTickAndBlocksSpawningUntilItWorks() {
        Rig rig = new Rig();
        rig.store.throwOnSave = true;
        StructureSnapshot s = Rig.village(0, 0);
        assertDoesNotThrow(() -> {
            rig.engine.submit(s);
            rig.run(60);
        });
        assertEquals(0, rig.bots.requests.size(), "an unrecordable spawn must not happen");
        rig.store.throwOnSave = false;
        rig.run(30);
        assertEquals(StructureStatus.POPULATED, rig.record(s.key()).status);
    }

    @Test
    void aFailingRosterLoadIsRetriedAndDoesNotStopPopulation() {
        Rig rig = new Rig();
        rig.store.throwOnNonAbandoned = true;
        StructureSnapshot s = Rig.village(0, 0);
        assertDoesNotThrow(() -> {
            rig.engine.submit(s);
            rig.run(60);
        });
        assertEquals(StructureStatus.POPULATED, rig.record(s.key()).status);
        rig.store.throwOnNonAbandoned = false;
        rig.run(5);
        assertEquals(3, rig.engine.stats().liveBots());
    }

    // ------------------------------------------------------------------ persistence rhythm

    @Test
    void anIdleEngineTouchesTheDiskOnlyOncePerSaveInterval() {
        Rig rig = new Rig();
        rig.run(1000);
        assertTrue(rig.store.saveCalls <= 11, "saveIfDirty called " + rig.store.saveCalls + " times in 1000 idle ticks");
        assertEquals(0, rig.store.writes);
    }

    @Test
    void changesThatMayWaitAreSavedWithinOneSaveInterval() {
        Rig rig = new Rig();
        StructureSnapshot s = Rig.village(0, 0);
        populate(rig, s, 100);
        StructureRecord onDisk = rig.store.persisted(s.key()).orElseThrow();
        assertNotEquals(StructureStatus.POPULATED, onDisk.status, "settling may wait for the interval");

        rig.run(rig.cfg.processing.saveIntervalTicks + 1);
        onDisk = rig.store.persisted(s.key()).orElseThrow();
        assertEquals(StructureStatus.POPULATED, onDisk.status);
        assertEquals(3, onDisk.spawnedCount());
        for (BotRecord b : onDisk.bots) {
            assertNotNull(b.profile, "the profile is on disk too");
        }
    }

    @Test
    void afterTheStateSettlesTheDiskIsNotWrittenAgainAndAgain() {
        Rig rig = new Rig();
        populate(rig, Rig.village(0, 0), 100);
        rig.run(rig.cfg.processing.saveIntervalTicks + 5);
        int writes = rig.store.writes;
        int calls = rig.store.saveCalls;
        rig.run(1000);
        assertEquals(writes, rig.store.writes, "nothing changed, nothing is rewritten");
        assertTrue(rig.store.saveCalls - calls <= 11);
    }

    @Test
    void saveIntervalFollowsTheConfigLive() {
        Rig rig = new Rig();
        rig.cfg.processing.saveIntervalTicks = 500;
        rig.run(1000);
        assertTrue(rig.store.saveCalls <= 3, "calls " + rig.store.saveCalls);
    }

    // ------------------------------------------------------------------ garbage in

    @Test
    void nullArgumentsAreHarmless() {
        Rig rig = new Rig();
        assertDoesNotThrow(() -> rig.engine.submit(null));
        assertEquals(EngineControl.ProcessOutcome.Kind.REJECTED, rig.engine.process(null, ForceMode.ROLL).kind());
        EngineControl.ProcessOutcome out = rig.engine.process(Rig.village(0, 0), null);
        assertEquals(EngineControl.ProcessOutcome.Kind.OCCUPIED_QUEUED, out.kind(), "no mode means a normal roll");
    }

    @Test
    void aCorruptStoredRecordWithoutNamesOrBotsDoesNotWedgeTheQueue() {
        Rig rig = new Rig();
        rig.cfg.processing.restoreSettleTicks = 0;
        StructureSnapshot empty = Rig.structure("minecraft:igloo", 0, 0);
        StructureRecord noBots = new StructureRecord();
        rig.store.put(empty.key(), noBots);
        StructureSnapshot nameless = Rig.structure("minecraft:igloo", 5, 5);
        StructureRecord broken = new StructureRecord();
        broken.plannedBots = 2;
        broken.bots.add(new BotRecord(0, null, 7));
        broken.bots.add(new BotRecord(1, null, 8));
        rig.store.put(nameless.key(), broken);

        rig.engine.submit(empty);
        rig.engine.submit(nameless);
        rig.run(100);
        assertEquals(StructureStatus.GAVE_UP, rig.record(empty.key()).status);
        assertTrue(rig.record(nameless.key()).status.isTerminal());
        StructureSnapshot healthy = Rig.village(9, 9);
        rig.engine.submit(healthy);
        rig.run(100);
        assertEquals(StructureStatus.POPULATED, rig.record(healthy.key()).status);
    }

    @Test
    void aBotWhoseStoredNameIsInvalidGetsAValidNameBeforeItIsRequested() {
        Rig rig = new Rig();
        StructureSnapshot s = Rig.structure("minecraft:igloo", 0, 0);
        StructureRecord rec = new StructureRecord();
        rec.plannedBots = 1;
        rec.structureSeed = 99;
        rec.bots.add(new BotRecord(0, "not a valid name!", 1234));
        rig.store.put(s.key(), rec);
        rig.engine.submit(s);
        rig.run(50);
        BotRecord b = rig.record(s.key()).bots.get(0);
        assertEquals(BotState.SPAWNED, b.state);
        assertTrue(NameGenerator.isValid(b.name), b.name);
        assertEquals(List.of(b.name), rig.bots.requestedNames());
    }

    @Test
    void containedErrorsAreLoggedSparinglyNotOncePerTick() {
        Rig rig = new Rig();
        rig.bots.throwAvailable = true;
        rig.run(5000);
        assertTrue(rig.engine.containedErrors() >= 5000);
        // (the throttle itself is covered by ThrottledLogTest; here we only prove the engine keeps working)
        rig.bots.throwAvailable = false;
        StructureRecord r = populate(rig, Rig.village(0, 0), 100);
        assertEquals(StructureStatus.POPULATED, r.status);
    }

    @Test
    void disabledOrUnavailableEngineStillFinishesTheBotsItAlreadyRequested() {
        Rig rig = new Rig();
        rig.bots.readyAfterPolls = 5;
        StructureSnapshot s = Rig.village(0, 0);
        rig.engine.submit(s);
        rig.run(2);
        assertEquals(3, rig.bots.requests.size());
        rig.cfg.enabled = false;
        rig.run(20);
        StructureRecord r = rig.record(s.key());
        assertEquals(3, r.spawnedCount(), "requested bots are seen through even if the addon is switched off");
        assertEquals(StructureStatus.POPULATED, r.status);
        assertEquals(3, rig.bots.requests.size());
    }

    @Test
    void theEngineDoesNotStopWhenTheConfigIsHalfBroken() {
        Rig rig = new Rig();
        InhabitantsConfig broken = rig.cfg;
        broken.processing = null;
        broken.deterministic = null;
        broken.spawning = null;
        broken.profiles = null;
        assertDoesNotThrow(() -> {
            rig.engine.submit(Rig.village(0, 0));
            rig.run(1400); // the shipped defaults include a 1200-tick settle period
        });
        assertEquals(StructureStatus.POPULATED, rig.record(Rig.village(0, 0).key()).status,
                "missing sections fall back to the shipped defaults");
    }
}
