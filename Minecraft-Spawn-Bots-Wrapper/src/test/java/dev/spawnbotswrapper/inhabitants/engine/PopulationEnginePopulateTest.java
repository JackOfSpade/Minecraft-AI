package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile;
import dev.spawnbotswrapper.inhabitants.sample.PersistentDeckStore;
import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.BotState;
import dev.spawnbotswrapper.inhabitants.store.StructureRecord;
import dev.spawnbotswrapper.inhabitants.store.StructureStatus;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;
import dev.spawnbotswrapper.inhabitants.util.SplitMix64;
import dev.spawnbotswrapper.inhabitants.util.StableHash;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** From "rolled occupied" to living, dressed inhabitants: positions, pacing, retries, write-ahead, failure. */
class PopulationEnginePopulateTest {

    private static final int MANY = Integer.MAX_VALUE;

    private static StructureRecord submitAndSettle(Rig rig, StructureSnapshot s) {
        rig.engine.submit(s);
        rig.runUntil(() -> rig.store.find(s.key()).map(r -> r.status.isTerminal()).orElse(false), 400);
        return rig.record(s.key());
    }

    // ------------------------------------------------------------------ the happy path

    @Test
    void everyPlannedBotIsSpawnedDressedAndRecorded() {
        Rig rig = new Rig();
        StructureSnapshot s = Rig.village(2, 2);
        StructureRecord r = submitAndSettle(rig, s);

        assertEquals(StructureStatus.POPULATED, r.status);
        assertNull(r.note);
        assertEquals(3, Rig.count(r, BotState.SPAWNED));
        Map<String, BotProfile> applied = new HashMap<>();
        rig.bots.applied.forEach(a -> applied.put(a.name(), a.profile()));
        for (BotRecord b : r.bots) {
            assertEquals(BotState.SPAWNED, b.state);
            assertNotNull(b.profile);
            assertEquals(b.seed, b.profile.seed(), "the profile must come from the bot's own seed");
            assertEquals(BotProfile.CURRENT_VERSION, b.profileVersion);
            assertTrue(b.profileApplied);
            assertEquals(FakeBots.uuidOf(b.name).toString(), b.uuid);
            assertEquals(rig.clock.nowMillis(), b.spawnedAtMillis, 60 * 50);
            assertEquals(1, b.spawnAttempts);
            assertNull(b.failure);
            assertEquals(b.profile, applied.get(b.name), "what was applied is exactly what was stored");
            assertTrue(rig.bots.isOnline(b.name));
        }
        assertEquals(3, rig.bots.applied.size());
        EngineControl.EngineStats st = rig.engine.stats();
        assertEquals(3, st.botsRequested());
        assertEquals(3, st.botsSpawned());
        assertEquals(0, st.botsFailed());
        assertEquals(3, st.liveBots());
        assertEquals(0, st.botsInFlight());
        assertEquals(0, st.queuedStructures());
    }

    @Test
    void positionsComeFromThePlannerWithTheDocumentedRandomStream() {
        Rig rig = new Rig();
        StructureSnapshot s = Rig.village(1, 1);
        StructureRecord r = submitAndSettle(rig, s);

        assertEquals(1, rig.planner.findCalls.size());
        FakePlanner.FindCall call = rig.planner.findCalls.get(0);
        assertEquals(3, call.count());
        assertTrue(call.alreadyTaken().isEmpty());
        assertFalse(call.probeWasNull());
        assertEquals(StructureRoll.positionRng(r.structureSeed, 0).nextLong(), call.firstDraw());

        for (FakeBots.Request req : rig.bots.requests) {
            BotRecord b = r.bots.stream().filter(x -> x.name.equals(req.request().name())).findFirst().orElseThrow();
            assertEquals(Rig.OVERWORLD, req.request().dimensionId());
            assertEquals(b.x, req.request().x());
            assertEquals(b.y, req.request().y());
            assertEquals(b.z, req.request().z());
            assertEquals(b.yaw, req.request().yaw());
            assertTrue(s.bounds().contains((int) b.x, (int) b.y, (int) b.z));
        }
        assertEquals(3, rig.bots.requests.size());
    }

    @Test
    void behaviourIsPlannedAroundTheRecordedHomeAndStoredWithTheProfile() {
        Rig rig = new Rig();
        StructureRecord r = submitAndSettle(rig, Rig.village(3, 3));
        assertEquals(3, rig.planner.behaviorCalls.size());
        Set<Long> streams = new HashSet<>();
        for (BotRecord b : r.bots) {
            FakePlanner.BehaviorCall call = rig.planner.behaviorCalls.stream()
                    .filter(c -> c.home().x() == b.x && c.home().z() == b.z).findFirst().orElseThrow();
            assertEquals(b.yaw, call.home().yaw());
            streams.add(call.firstDraw());
            if (call.requested().usesPath()) {
                assertEquals(List.of(new BotProfile.Waypoint(b.x, b.y, b.z)), b.profile.behavior().waypoints());
            } else {
                assertTrue(b.profile.behavior().waypoints().isEmpty());
            }
            assertEquals(call.requested().stance(), b.profile.behavior().stance());
        }
        assertEquals(3, streams.size(), "each bot plans with its own random stream");
    }

    @Test
    void theStoredProfileIsNeverRegeneratedLater() {
        Rig rig = new Rig();
        StructureRecord r = submitAndSettle(rig, Rig.village(0, 0));
        int created = rig.profiles.calls.size();
        BotProfile before = r.bots.get(0).profile;
        rig.run(2000);
        rig.engine.submit(Rig.village(0, 0));
        rig.run(200);
        assertEquals(created, rig.profiles.calls.size());
        assertSame(before, rig.record(Rig.village(0, 0).key()).bots.get(0).profile);
    }

    @Test
    void normalModeDrawsFromTheWorldWidePersistentDecksWithTheRecordedSeed() {
        Rig rig = new Rig();
        StructureRecord r = submitAndSettle(rig, Rig.village(0, 0));
        assertEquals(3, rig.profiles.calls.size());
        for (FakeProfiles.Call call : rig.profiles.calls) {
            assertSame(rig.store.decks(), call.decks());
            assertSame(rig.bots.capabilities, call.capabilities());
        }
        Set<Long> seeds = new HashSet<>();
        rig.profiles.calls.forEach(c -> seeds.add(c.seed()));
        Set<Long> expected = new HashSet<>();
        r.bots.forEach(b -> expected.add(b.seed));
        assertEquals(expected, seeds);
    }

    @Test
    void partialProfileApplicationIsRecordedHonestly() {
        Rig rig = new Rig();
        rig.bots.applyResult = new BotGateway.ApplyResult(true, false, true, List.of("vitals: attribute missing"));
        StructureRecord r = submitAndSettle(rig, Rig.village(0, 0));
        assertEquals(StructureStatus.POPULATED, r.status);
        for (BotRecord b : r.bots) {
            assertEquals(BotState.SPAWNED, b.state);
            assertFalse(b.profileApplied);
            assertNotNull(b.profile, "the profile is still authoritative");
        }
    }

    @Test
    void ifTheProbeIsGoneWhenTheBotAppearsTheRequestedBehaviourKeepsNoWaypoints() {
        Rig rig = new Rig();
        rig.bots.readyAfterPolls = 3;
        StructureSnapshot s = Rig.village(0, 0);
        rig.engine.submit(s);
        rig.run(1); // rolled, positions found, requests sent
        assertEquals(3, rig.bots.requests.size());
        rig.world.unloadedDimensions.add(Rig.OVERWORLD);
        rig.run(10);
        StructureRecord r = rig.record(s.key());
        assertEquals(StructureStatus.POPULATED, r.status);
        assertTrue(rig.planner.behaviorCalls.isEmpty());
        for (BotRecord b : r.bots) {
            assertTrue(b.profile.behavior().waypoints().isEmpty());
            assertEquals(rig.bots.applied.stream().filter(a -> a.name().equals(b.name)).findFirst().orElseThrow().profile(), b.profile);
        }
    }

    // ------------------------------------------------------------------ write-ahead

    @Test
    void theRequestedStateIsOnDiskAtTheMomentEachRequestIsSent() {
        Rig rig = new Rig();
        StructureSnapshot s = Rig.village(5, 5);
        List<String> problems = new ArrayList<>();
        rig.bots.onRequest = req -> {
            StructureRecord onDisk = rig.store.persisted(s.key()).orElse(null);
            if (onDisk == null) {
                problems.add("no record on disk when " + req.name() + " was requested");
                return;
            }
            BotRecord b = onDisk.bots.stream().filter(x -> req.name().equals(x.name)).findFirst().orElse(null);
            if (b == null) {
                problems.add("bot " + req.name() + " not on disk under that name");
            } else if (b.state != BotState.REQUESTED) {
                problems.add(req.name() + " was " + b.state + " on disk");
            } else if (b.x != req.x() || b.y != req.y() || b.z != req.z() || b.spawnAttempts != 1) {
                problems.add(req.name() + " position/attempt not persisted: " + b.x + "," + b.y + "," + b.z + " x" + b.spawnAttempts);
            }
        };
        submitAndSettle(rig, s);
        assertEquals(3, rig.bots.requests.size());
        assertEquals(List.of(), problems);
    }

    @Test
    void aFailedWriteAheadMeansNothingIsRequestedAndTheDiskIsProbedOnlyOccasionally() {
        Rig rig = new Rig();
        rig.store.failSaves = true;
        StructureSnapshot s = Rig.village(0, 0);
        rig.engine.submit(s);
        rig.run(1);
        int savesAfterFirstTick = rig.store.saveCalls;
        rig.run(95); // still inside the periodic-save interval and the retry interval multiples
        assertEquals(0, rig.bots.requests.size(), "never spawn what cannot be recorded");
        StructureRecord r = rig.record(s.key());
        assertEquals(StructureStatus.OCCUPIED_PENDING, r.status);
        for (BotRecord b : r.bots) {
            assertEquals(BotState.PLANNED, b.state);
            assertEquals(0, b.spawnAttempts, "a refused write must not use up a spawn attempt");
        }
        assertTrue(rig.store.saveCalls - savesAfterFirstTick <= 12, "hammered the disk: " + (rig.store.saveCalls - savesAfterFirstTick));

        rig.store.failSaves = false;
        rig.run(30);
        assertEquals(StructureStatus.POPULATED, rig.record(s.key()).status);
        assertEquals(3, rig.bots.requests.size());
    }

    // ------------------------------------------------------------------ pacing

    @Test
    void requestsRespectMaxBotsPerTickAndTheSpawnInterval() {
        Rig rig = new Rig();
        rig.cfg.defaults = new InhabitantsConfig.Rule(1.0, 6);
        // 80 blocks/bot makes a Rig.village() (480 blocks of volume) size-cap at exactly 6.
        rig.cfg.processing.blocksPerBot = 80.0;
        rig.cfg.processing.maxBotsPerTick = 2;
        rig.cfg.processing.spawnIntervalTicks = 4;
        rig.engine.submit(Rig.village(0, 0));
        rig.engine.submit(Rig.village(9, 9));
        rig.run(200);

        assertEquals(12, rig.bots.requests.size());
        Map<Long, Integer> perTick = new HashMap<>();
        rig.bots.requests.forEach(r -> perTick.merge(r.tick(), 1, Integer::sum));
        perTick.values().forEach(n -> assertTrue(n <= 2, "more than maxBotsPerTick in one tick"));
        List<Long> ticks = new ArrayList<>(new java.util.TreeSet<>(perTick.keySet()));
        for (int i = 1; i < ticks.size(); i++) {
            assertTrue(ticks.get(i) - ticks.get(i - 1) >= 4, "spawn batches only " + (ticks.get(i) - ticks.get(i - 1)) + " ticks apart");
        }
        assertEquals(6, ticks.size());
        assertEquals(2, rig.store.counts().populated());
    }

    @Test
    void oneBotEveryFourTicksIsTheDefaultRhythm() {
        Rig rig = new Rig();
        rig.cfg.processing.maxBotsPerTick = 1;
        rig.cfg.processing.spawnIntervalTicks = 4;
        rig.engine.submit(Rig.village(0, 0));
        rig.run(60);
        List<Long> ticks = rig.bots.requests.stream().map(FakeBots.Request::tick).toList();
        assertEquals(3, ticks.size());
        assertEquals(4, ticks.get(1) - ticks.get(0));
        assertEquals(4, ticks.get(2) - ticks.get(1));
    }

    @Test
    void theInitialDelayLetsNeighbouringChunksLoadBeforeTheFirstAttempt() {
        Rig rig = new Rig();
        rig.cfg.processing.initialDelayTicks = 40;
        StructureSnapshot s = Rig.village(0, 0);
        rig.engine.submit(s);
        rig.run(38);
        assertEquals(StructureStatus.OCCUPIED_PENDING, rig.record(s.key()).status, "rolled at once");
        assertEquals(0, rig.bots.requests.size());
        assertEquals(0, rig.planner.findCalls.size());
        rig.run(10);
        assertEquals(3, rig.bots.requests.size());
        assertEquals(41, rig.bots.requests.get(0).tick(), "queued at tick 1, first attempt 40 ticks later");
    }

    @Test
    void theSettlePeriodHoldsEverySpawnRequestButNotTheRoll() {
        Rig rig = new Rig();
        rig.cfg.processing.restoreSettleTicks = 100;
        StructureSnapshot s = Rig.village(0, 0);
        rig.engine.submit(s);
        rig.run(99);
        StructureRecord r = rig.record(s.key());
        assertEquals(StructureStatus.OCCUPIED_PENDING, r.status, "the roll itself is not held");
        assertEquals(3, r.bots.size());
        assertEquals(0, rig.bots.requests.size(), "PvP BOT is still restoring its own bots");
        assertEquals(0, rig.planner.findCalls.size());
        rig.run(1);
        assertEquals(3, rig.bots.requests.size());
        assertEquals(100, rig.bots.requests.get(0).tick());
    }

    @Test
    void positionSearchesPerTickAreBoundedByMaxStructuresPerTick() {
        Rig rig = new Rig();
        rig.cfg.processing.maxStructuresPerTick = 2;
        rig.cfg.processing.maxBotsPerTick = 64;
        for (int i = 0; i < 6; i++) {
            rig.engine.submit(Rig.village(i * 4, 0));
        }
        rig.run(1);
        assertEquals(2, rig.planner.findCalls.size());
        rig.run(1);
        assertEquals(4, rig.planner.findCalls.size());
        rig.run(1);
        assertEquals(6, rig.planner.findCalls.size());
    }

    // ------------------------------------------------------------------ the live-bot cap

    @Test
    void theLiveBotCapHoldsSpawnsWithoutRerollingAndResumesWhenABotDies() {
        Rig rig = new Rig();
        rig.cfg.defaults = new InhabitantsConfig.Rule(1.0, 4);
        // 120 blocks/bot makes a Rig.village() (480 blocks of volume) size-cap at exactly 4.
        rig.cfg.processing.blocksPerBot = 120.0;
        rig.cfg.processing.maxLiveBots = 2;
        StructureSnapshot s = Rig.village(0, 0);
        rig.engine.submit(s);
        rig.run(300);

        StructureRecord r = rig.record(s.key());
        assertEquals(StructureStatus.OCCUPIED_PENDING, r.status);
        assertEquals(2, rig.bots.requests.size());
        assertEquals(2, Rig.count(r, BotState.SPAWNED));
        assertEquals(2, Rig.count(r, BotState.PLANNED), "the rest simply wait, they are not dropped");
        assertEquals(0, r.attempts, "waiting for the cap is not a failed attempt");
        assertEquals(1, rig.engine.stats().structuresRolled());

        List<String> names = Rig.names(r);
        rig.bots.kill(rig.bots.requestedNames().get(0));
        rig.run(60);
        assertEquals(3, rig.bots.requests.size(), "a death frees one slot");
        rig.bots.kill(rig.bots.requestedNames().get(1));
        rig.run(60);
        assertEquals(4, rig.bots.requests.size());
        r = rig.record(s.key());
        assertEquals(StructureStatus.POPULATED, r.status);
        assertEquals(4, Rig.count(r, BotState.SPAWNED));
        assertEquals(names, Rig.names(r), "nobody was renamed or replaced");
        assertEquals(1, rig.engine.stats().structuresRolled());
        assertEquals(2, rig.engine.stats().liveBots());
    }

    @Test
    void raisingTheCapInTheConfigTakesEffectImmediately() {
        Rig rig = new Rig();
        rig.cfg.defaults = new InhabitantsConfig.Rule(1.0, 4);
        // 120 blocks/bot makes a Rig.village() (480 blocks of volume) size-cap at exactly 4.
        rig.cfg.processing.blocksPerBot = 120.0;
        rig.cfg.processing.maxLiveBots = 1;
        rig.engine.submit(Rig.village(0, 0));
        rig.run(100);
        assertEquals(1, rig.bots.requests.size());
        rig.cfg.processing.maxLiveBots = 0;
        rig.run(20);
        assertEquals(4, rig.bots.requests.size());
    }

    @Test
    void theCapCountsBotsStillInFlight() {
        Rig rig = new Rig();
        rig.cfg.defaults = new InhabitantsConfig.Rule(1.0, 4);
        // 120 blocks/bot makes a Rig.village() (480 blocks of volume) size-cap at exactly 4.
        rig.cfg.processing.blocksPerBot = 120.0;
        rig.cfg.processing.maxLiveBots = 2;
        rig.cfg.processing.appearTimeoutTicks = 1000;
        rig.bots.readyAfterPolls = MANY;
        rig.engine.submit(Rig.village(0, 0));
        rig.run(100);
        assertEquals(2, rig.bots.requests.size(), "two in flight already fill the cap");
        assertEquals(2, rig.engine.stats().botsInFlight());
    }

    /**
     * wrapperA-001: a bot whose spawn request timed out ({@code appearTimeoutTicks}) and then actually appeared
     * afterwards (a slow profile lookup upstream) must be adopted ({@link PopulationDriver#adoptLateArrivals})
     * regardless of the capacity/pacing gate. Before the fix, {@code driveOne()} returned at that gate before
     * ever reaching adoption, so a bot stuck this way was never dressed and the structure never left
     * OCCUPIED_PENDING -- even though adoption itself needs no capacity, since it does not create a new entity.
     * The TPS governor's hard block is used here rather than the live-bot cap because, unlike the cap, it never
     * frees a slot on its own when one bot's request times out, so it isolates the bug from ordinary pacing.
     */
    @Test
    void aLateArrivalIsAdoptedEvenWhileCapacityStaysExhausted() {
        Rig rig = new Rig();
        rig.bots.readyAfterPolls = MANY; // nobody naturally becomes Ready; every request times out instead
        rig.cfg.tpsThrottle.checkIntervalTicks = 1;
        StructureSnapshot s = Rig.structure("minecraft:pillager_outpost", 0, 0);
        rig.engine.submit(s);
        rig.run(5);
        StructureRecord r = rig.record(s.key());
        assertEquals(3, r.bots.size());
        assertEquals(3, rig.bots.requests.size(), "all three are requested before anything times out");
        String late = r.bots.get(0).name;

        rig.bots.throwRemove = true; // the governor may not take the adopted bot out again: this test is about adoption only
        rig.tps.millis = 150.0; // above degradedMillis: the TPS governor now hard-blocks every new spawn
        rig.run((int) rig.cfg.processing.appearTimeoutTicks + 10);
        r = rig.record(s.key());
        for (BotRecord b : r.bots) {
            assertEquals(BotState.PLANNED, b.state, "every request timed out and capacity is now degraded");
        }

        // The entity actually appears (the earlier profile lookup was just slow) while capacity is still shut.
        rig.bots.bringOnline(late);
        rig.run(5);

        r = rig.record(s.key());
        BotRecord adopted = r.bots.stream().filter(b -> late.equals(b.name)).findFirst().orElseThrow();
        assertEquals(BotState.SPAWNED, adopted.state, "adoption must not wait for capacity: it creates no new entity");
        assertNotNull(adopted.profile, "an adopted bot is dressed exactly like a normally spawned one");
        for (BotRecord b : r.bots) {
            if (!late.equals(b.name)) {
                assertEquals(BotState.PLANNED, b.state, "genuinely new spawns stay gated by capacity");
            }
        }
        assertEquals(StructureStatus.OCCUPIED_PENDING, r.status, "the other two bots are still unresolved");
    }

    @Test
    void positionsHeldBackByTheCapAreSearchedAgainOnceTheyAreStale() {
        Rig rig = new Rig();
        rig.cfg.defaults = new InhabitantsConfig.Rule(1.0, 3);
        rig.cfg.processing.maxLiveBots = 1;
        StructureSnapshot s = Rig.village(0, 0);
        rig.engine.submit(s);
        rig.run(100);
        assertEquals(1, rig.planner.findCalls.size());
        rig.run((int) PopulationDriver.ASSIGNMENT_TTL_TICKS + 50);
        rig.bots.kill(rig.bots.requestedNames().get(0));
        rig.run(60);
        assertEquals(2, rig.bots.requests.size());
        assertEquals(2, rig.planner.findCalls.size(), "blocks may have changed while the cap held the structure back");
        FakePlanner.FindCall second = rig.planner.findCalls.get(1);
        assertEquals(2, second.count());
        assertEquals(1, second.alreadyTaken().size(), "the position of the bot that did spawn is off limits");
    }

    // ------------------------------------------------------------------ positions that cannot be found (yet)

    @Test
    void anUnloadedDimensionConsumesNoAttemptAndTheStructureSucceedsLater() {
        Rig rig = new Rig();
        rig.world.unloadedDimensions.add(Rig.OVERWORLD);
        StructureSnapshot s = Rig.village(0, 0);
        rig.engine.submit(s);
        rig.run(600); // far more than maxAttemptsPerStructure * retryIntervalTicks
        StructureRecord r = rig.record(s.key());
        assertEquals(StructureStatus.OCCUPIED_PENDING, r.status);
        assertEquals(0, r.attempts);
        assertEquals(0, rig.bots.requests.size());
        assertEquals(0, rig.planner.findCalls.size(), "no probe, no search");
        assertTrue(rig.world.probeCalls >= 10 && rig.world.probeCalls <= 100, "polled at the retry interval, not every tick: " + rig.world.probeCalls);

        rig.world.unloadedDimensions.clear();
        rig.run(40);
        assertEquals(StructureStatus.POPULATED, rig.record(s.key()).status);
        assertEquals(0, rig.record(s.key()).attempts);
    }

    @Test
    void chunksReportedUnloadedByThePlannerConsumeNoAttemptEither() {
        Rig rig = new Rig();
        rig.planner.mode = FakePlanner.Mode.UNLOADED;
        StructureSnapshot s = Rig.village(0, 0);
        rig.engine.submit(s);
        rig.run(600);
        assertEquals(0, rig.record(s.key()).attempts);
        assertEquals(StructureStatus.OCCUPIED_PENDING, rig.record(s.key()).status);
        assertTrue(rig.planner.findCalls.size() >= 30, "keeps retrying: " + rig.planner.findCalls.size());
        assertEquals(0, rig.bots.requests.size());

        rig.planner.mode = FakePlanner.Mode.NORMAL;
        rig.run(30);
        assertEquals(StructureStatus.POPULATED, rig.record(s.key()).status);
    }

    @Test
    void somePositionsFoundWhileOthersAreUnloadedSpawnWhatCanBeAndKeepTheRestPending() {
        Rig rig = new Rig();
        rig.planner.mode = FakePlanner.Mode.LIMITED_UNLOADED;
        rig.planner.capacity = 1;
        StructureSnapshot s = Rig.village(0, 0);
        rig.engine.submit(s);
        rig.run(200);
        StructureRecord r = rig.record(s.key());
        assertEquals(1, Rig.count(r, BotState.SPAWNED));
        assertEquals(2, Rig.count(r, BotState.PLANNED));
        assertEquals(0, r.attempts);
        FakePlanner.FindCall later = rig.planner.findCalls.get(rig.planner.findCalls.size() - 1);
        assertEquals(2, later.count(), "only the bots still without a position are searched for");
        assertEquals(1, later.alreadyTaken().size(), "and the placed bot's spot is off limits");

        rig.planner.mode = FakePlanner.Mode.NORMAL;
        rig.run(30);
        assertEquals(StructureStatus.POPULATED, rig.record(s.key()).status);
        assertEquals(3, rig.record(s.key()).spawnedCount());
    }

    @Test
    void genuinelyNoValidPositionsUseUpTheAttemptsThenGiveUpPermanently() {
        Rig rig = new Rig();
        rig.planner.mode = FakePlanner.Mode.NONE;
        StructureSnapshot s = Rig.village(0, 0);
        rig.engine.submit(s);
        int ticks = rig.runUntil(() -> rig.hasStatus(s.key(), StructureStatus.GAVE_UP), 100);

        StructureRecord r = rig.record(s.key());
        assertEquals(3, r.attempts, "maxAttemptsPerStructure");
        assertEquals(3, rig.planner.findCalls.size());
        assertTrue(ticks >= 20, "attempts are a retry interval apart: " + ticks);
        assertEquals(3, Rig.count(r, BotState.FAILED));
        for (BotRecord b : r.bots) {
            assertTrue(b.failure.contains("no valid position"), b.failure);
        }
        assertNotNull(r.note);
        assertTrue(r.note.startsWith("no inhabitant could be placed"), r.note);
        assertEquals(0, rig.bots.requests.size());
        assertEquals(3, rig.engine.stats().botsFailed());
        assertEquals(0, rig.engine.stats().queuedStructures());

        // permanent: never tried again, however often it is seen
        rig.run(1000);
        rig.engine.submit(s);
        rig.run(1000);
        assertEquals(3, rig.planner.findCalls.size());
        assertEquals(StructureStatus.GAVE_UP, rig.record(s.key()).status);
        assertEquals(StructureStatus.GAVE_UP, rig.store.persisted(s.key()).orElseThrow().status);
    }

    @Test
    void aStructureWithRoomForOnlySomeBotsIsPopulatedByThoseAndNotesTheRest() {
        Rig rig = new Rig();
        rig.cfg.defaults = new InhabitantsConfig.Rule(1.0, 4);
        // 120 blocks/bot makes a Rig.village() (480 blocks of volume) size-cap at exactly 4.
        rig.cfg.processing.blocksPerBot = 120.0;
        rig.planner.mode = FakePlanner.Mode.LIMITED;
        rig.planner.capacity = 2;
        StructureSnapshot s = Rig.village(0, 0);
        StructureRecord r = submitAndSettle(rig, s);
        assertEquals(StructureStatus.POPULATED, r.status);
        assertEquals(2, Rig.count(r, BotState.SPAWNED));
        assertEquals(2, Rig.count(r, BotState.FAILED));
        assertEquals(3, r.attempts);
        assertNotNull(r.note);
        assertTrue(r.note.startsWith("2 of 4 planned inhabitants could not be placed"), r.note);
    }

    // ------------------------------------------------------------------ spawn failures

    @Test
    void aRefusedRequestGoesBackToPlannedAndIsRetriedWithAFreshPosition() {
        Rig rig = new Rig();
        rig.bots.refuseFirst = 3;
        StructureSnapshot s = Rig.village(0, 0);
        StructureRecord r = submitAndSettle(rig, s);
        assertEquals(StructureStatus.POPULATED, r.status);
        assertEquals(6, rig.bots.requests.size());
        for (BotRecord b : r.bots) {
            assertEquals(2, b.spawnAttempts);
            assertEquals(BotState.SPAWNED, b.state);
            assertEquals(2, rig.bots.requestsFor(b.name), "same name every time: the bot was never spawned");
        }
        assertEquals(2, rig.planner.findCalls.size(), "the discarded positions are searched anew");
        assertTrue(rig.planner.findCalls.get(1).alreadyTaken().isEmpty());
        long gap = rig.bots.requests.get(3).tick() - rig.bots.requests.get(0).tick();
        assertTrue(gap >= 10, "retries wait the retry interval: " + gap);
    }

    @Test
    void aBotThatIsRefusedThreeTimesFailsForGoodWithTheReason() {
        Rig rig = new Rig();
        rig.bots.refuseAll = true;
        rig.bots.refuseReason = "name is banned";
        StructureSnapshot s = Rig.village(0, 0);
        StructureRecord r = submitAndSettle(rig, s);
        assertEquals(StructureStatus.GAVE_UP, r.status);
        assertEquals(9, rig.bots.requests.size());
        for (BotRecord b : r.bots) {
            assertEquals(BotState.FAILED, b.state);
            assertEquals(3, b.spawnAttempts);
            assertEquals("name is banned", b.failure);
        }
        assertEquals("no inhabitant could be placed: name is banned", r.note);
        assertEquals(3, rig.engine.stats().botsFailed());
        assertEquals(9, rig.engine.stats().botsRequested());
        assertEquals(0, rig.engine.stats().botsInFlight());
    }

    @Test
    void oneBotFailingForGoodStillLeavesTheStructurePopulated() {
        Rig rig = new Rig();
        rig.cfg.processing.restoreSettleTicks = 3;
        StructureSnapshot s = Rig.village(0, 0);
        rig.engine.submit(s);
        rig.run(2);
        String doomed = rig.record(s.key()).bots.get(1).name;
        rig.bots.refuse.add(doomed.toLowerCase(Locale.ROOT));
        StructureRecord r = submitAndSettle(rig, s);

        assertEquals(StructureStatus.POPULATED, r.status);
        assertEquals(BotState.FAILED, r.bots.get(1).state);
        assertEquals(BotState.SPAWNED, r.bots.get(0).state);
        assertEquals(BotState.SPAWNED, r.bots.get(2).state);
        assertEquals("1 of 3 planned inhabitants could not be placed: refused by test", r.note);
    }

    @Test
    void aRequestThatNeverAppearsTimesOutIsRetriedAndFinallyFails() {
        Rig rig = new Rig();
        rig.bots.readyAfterPolls = MANY;
        StructureSnapshot s = Rig.village(0, 0);
        rig.engine.submit(s);
        rig.run(49);
        assertEquals(3, rig.engine.stats().botsInFlight(), "still waiting before the deadline");
        assertEquals(3, rig.bots.requests.size());
        rig.run(2);
        assertEquals(0, rig.engine.stats().botsInFlight());
        assertEquals(BotState.PLANNED, rig.record(s.key()).bots.get(0).state);
        assertTrue(rig.record(s.key()).bots.get(0).failure.contains("did not appear within 50 ticks"));

        rig.runUntil(() -> rig.record(s.key()).status.isTerminal(), 400);
        StructureRecord r = rig.record(s.key());
        assertEquals(StructureStatus.GAVE_UP, r.status);
        assertEquals(9, rig.bots.requests.size());
        for (BotRecord b : r.bots) {
            assertEquals(3, b.spawnAttempts);
            assertEquals(BotState.FAILED, b.state);
            assertEquals(3, rig.bots.requestsFor(b.name));
        }
    }

    @Test
    void aBotThatAppearsAfterItsRequestTimedOutIsAdoptedNotRenamedOrDuplicated() {
        Rig rig = new Rig();
        rig.bots.readyAfterPolls = MANY;
        StructureSnapshot s = Rig.village(0, 0);
        rig.engine.submit(s);
        rig.run(52);
        StructureRecord r = rig.record(s.key());
        assertEquals(3, Rig.count(r, BotState.PLANNED));
        String late = r.bots.get(0).name;
        double x = r.bots.get(0).x;

        rig.bots.bringOnline(late); // upstream finally placed it
        rig.run(15);
        r = rig.record(s.key());
        BotRecord adopted = r.bots.get(0);
        assertEquals(BotState.SPAWNED, adopted.state);
        assertEquals(late, adopted.name, "not renamed");
        assertEquals(x, adopted.x, "it stands where it was requested");
        assertNotNull(adopted.profile);
        assertEquals(1, rig.bots.requestsFor(late), "not requested a second time");
        assertEquals(2, rig.bots.requestsFor(r.bots.get(1).name), "the others are retried as usual");
        assertTrue(rig.bots.applied.stream().anyMatch(a -> a.name().equals(late)));
    }

    @Test
    void aBotIsNeverRequestedTwiceConcurrently() {
        Rig rig = new Rig();
        rig.cfg.processing.appearTimeoutTicks = 100_000;
        rig.bots.readyAfterPolls = MANY;
        StructureSnapshot s = Rig.village(0, 0);
        rig.engine.submit(s);
        rig.engine.submit(s);
        rig.run(500);
        assertEquals(3, rig.bots.requests.size());
        assertEquals(3, new HashSet<>(rig.bots.requestedNames()).size());
        assertEquals(3, rig.engine.stats().botsInFlight());
    }

    // ------------------------------------------------------------------ names at spawn time

    @Test
    void aNameTakenSinceTheRollIsReplacedByTheNextDeterministicCandidateBeforeRequesting() {
        Rig rig = new Rig();
        rig.cfg.processing.restoreSettleTicks = 5;
        StructureSnapshot s = Rig.village(0, 0);
        rig.engine.submit(s);
        rig.run(2);
        StructureRecord planned = rig.record(s.key());
        String original = planned.bots.get(0).name;
        long seed = planned.bots.get(0).seed;
        rig.bots.taken.add(original.toLowerCase(Locale.ROOT)); // e.g. a real player joined with that name

        StructureRecord r = submitAndSettle(rig, s);
        String renamed = r.bots.get(0).name;
        assertNotEquals(original, renamed);
        assertEquals(new NameGenerator("").candidate(seed, 1), renamed, "the next deterministic candidate");
        assertTrue(NameGenerator.isValid(renamed));
        assertEquals(0, rig.bots.requestsFor(original), "the taken name was never requested");
        assertEquals(1, rig.bots.requestsFor(renamed));
        Set<String> all = new HashSet<>();
        for (BotRecord b : r.bots) {
            assertTrue(all.add(b.name.toLowerCase(Locale.ROOT)));
        }

        // The rename must reindex, not just persist: findBot has to answer for the name the bot actually
        // uses, and must stop answering for the abandoned one, or a later structure's uniqueness check
        // (which goes through the very same findBot) could hand out that name a second time.
        assertTrue(rig.store.findBot(renamed).isPresent(), "the bot must be findable under its new name");
        assertEquals(renamed, rig.store.findBot(renamed).orElseThrow().bot().name);
        assertTrue(rig.store.findBot(original).isEmpty(), "the old name must no longer resolve to this bot");
    }

    @Test
    void aNameOfAnOnlinePlayerCountsAsTaken() {
        Rig rig = new Rig();
        rig.cfg.processing.restoreSettleTicks = 5;
        StructureSnapshot s = Rig.village(0, 0);
        rig.engine.submit(s);
        rig.run(2);
        String original = rig.record(s.key()).bots.get(2).name;
        rig.bots.bringOnline(original.toUpperCase(Locale.ROOT)); // someone else is online under that name

        StructureRecord r = submitAndSettle(rig, s);
        assertNotEquals(original, r.bots.get(2).name);
        assertEquals(0, rig.bots.requestsFor(original));
        assertEquals(StructureStatus.POPULATED, r.status);
    }

    @Test
    void theRenameIsOnDiskBeforeTheRequestGoesOut() {
        Rig rig = new Rig();
        rig.cfg.processing.restoreSettleTicks = 5;
        StructureSnapshot s = Rig.village(0, 0);
        rig.engine.submit(s);
        rig.run(2);
        String original = rig.record(s.key()).bots.get(0).name;
        rig.bots.taken.add(original.toLowerCase(Locale.ROOT));
        List<String> onDiskNames = new ArrayList<>();
        rig.bots.onRequest = req -> rig.store.persisted(s.key()).ifPresent(d -> d.bots.forEach(b -> {
            if (req.name().equals(b.name)) {
                onDiskNames.add(b.name);
            }
        }));
        submitAndSettle(rig, s);
        assertEquals(3, onDiskNames.size(), "every requested name was already persisted under exactly that name");
    }

    @Test
    void aStructureIsNeverRepopulatedOnceSettled() {
        Rig rig = new Rig();
        StructureSnapshot s = Rig.village(0, 0);
        StructureRecord r = submitAndSettle(rig, s);
        assertEquals(StructureStatus.POPULATED, r.status);
        int requests = rig.bots.requests.size();
        for (BotRecord b : r.bots) {
            rig.bots.kill(b.name);
        }
        rig.run(3000);
        rig.engine.submit(s);
        rig.run(500);
        assertEquals(requests, rig.bots.requests.size(), "killed inhabitants are never replaced");
        assertEquals(StructureStatus.POPULATED, rig.record(s.key()).status);
        assertEquals(0, rig.record(s.key()).spawnedCount(), "they are dead, not alive");
        assertEquals(3, rig.record(s.key()).deadCount(), "a bot that is gone for good died: its slot is spent for ever");
    }

    @Test
    void manyStructuresPopulateIndependentlyAndCompletely() {
        Rig rig = new Rig(new InMemoryStorage(false), 9);
        rig.cfg.defaults = new InhabitantsConfig.Rule(1.0, 1);
        List<StructureKey> keys = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            StructureSnapshot s = Rig.structure("minecraft:desert_pyramid", i, 2 * i);
            keys.add(s.key());
            rig.engine.submit(s);
        }
        rig.run(600);
        Set<String> names = new HashSet<>();
        int planned = 0;
        for (StructureKey k : keys) {
            StructureRecord r = rig.record(k);
            assertEquals(StructureStatus.POPULATED, r.status, k.toString());
            assertEquals(r.plannedBots, r.spawnedCount());
            planned += r.plannedBots;
            r.bots.forEach(b -> assertTrue(names.add(b.name.toLowerCase(Locale.ROOT))));
        }
        assertEquals(planned, rig.bots.requests.size());
        assertEquals(planned, rig.engine.stats().botsSpawned());
        assertEquals(0, rig.engine.stats().queuedStructures());
    }

    @Test
    void theStreamOfPositionsDiffersPerAttemptSoRetriesTryOtherColumns() {
        Rig rig = new Rig();
        rig.planner.mode = FakePlanner.Mode.NONE;
        StructureSnapshot s = Rig.village(0, 0);
        rig.engine.submit(s);
        rig.runUntil(() -> rig.hasStatus(s.key(), StructureStatus.GAVE_UP), 100);
        long seed = rig.record(s.key()).structureSeed;
        for (int attempt = 0; attempt < 3; attempt++) {
            assertEquals(new SplitMix64(StableHash.combine(seed, 2 + attempt)).nextLong(),
                    rig.planner.findCalls.get(attempt).firstDraw(), "attempt " + attempt);
        }
    }

    @Test
    void decksAreThePersistentStoreDecksNotACopy() {
        Rig rig = new Rig();
        submitAndSettle(rig, Rig.village(0, 0));
        assertTrue(rig.profiles.calls.stream().allMatch(c -> c.decks() instanceof PersistentDeckStore));
    }
}
