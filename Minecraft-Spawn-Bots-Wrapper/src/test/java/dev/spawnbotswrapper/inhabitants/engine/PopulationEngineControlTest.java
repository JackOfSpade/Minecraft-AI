package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.BotState;
import dev.spawnbotswrapper.inhabitants.store.PopulationStorage;
import dev.spawnbotswrapper.inhabitants.store.StructureRecord;
import dev.spawnbotswrapper.inhabitants.store.StructureStatus;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;
import dev.spawnbotswrapper.inhabitants.util.SplitMix64;
import dev.spawnbotswrapper.inhabitants.util.StableHash;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static dev.spawnbotswrapper.inhabitants.engine.EngineControl.ProcessOutcome.Kind;
import static org.junit.jupiter.api.Assertions.*;

/** Admin/testing operations: forced processing, reset, statistics, the read-only view, configuration changes. */
class PopulationEngineControlTest {

    private static final int MANY = Integer.MAX_VALUE;

    // ------------------------------------------------------------------ process

    @Test
    void aForcedRollHappensImmediatelyAndSkipsTheInitialDelay() {
        Rig rig = new Rig();
        rig.cfg.processing.initialDelayTicks = 1000;
        StructureSnapshot forced = Rig.village(0, 0);
        StructureSnapshot detected = Rig.village(9, 9);
        rig.engine.submit(detected);

        EngineControl.ProcessOutcome out = rig.engine.process(forced, ForceMode.ROLL);
        assertEquals(Kind.OCCUPIED_QUEUED, out.kind());
        assertFalse(out.message().isBlank());
        StructureRecord r = rig.record(forced.key());
        assertEquals(StructureStatus.OCCUPIED_PENDING, r.status);
        assertEquals("RANDOM", r.source, "a plain forced ROLL is an ordinary roll");
        assertEquals(StructureStatus.OCCUPIED_PENDING, rig.store.persisted(forced.key()).orElseThrow().status,
                "an admin action is durable at once");

        rig.run(5);
        assertEquals(3, rig.bots.requests.size(), "the forced structure did not wait");
        assertEquals(StructureStatus.POPULATED, rig.record(forced.key()).status);
        assertEquals(StructureStatus.OCCUPIED_PENDING, rig.record(detected.key()).status);
    }

    @Test
    void aForcedRollCanComeOutAbandoned() {
        Rig rig = new Rig();
        rig.cfg.defaults = new InhabitantsConfig.Rule(0.0, 1, 3);
        StructureSnapshot s = Rig.village(0, 0);
        EngineControl.ProcessOutcome out = rig.engine.process(s, ForceMode.ROLL);
        assertEquals(Kind.ABANDONED, out.kind());
        assertEquals(StructureStatus.ABANDONED, rig.record(s.key()).status);
        assertEquals(StructureStatus.ABANDONED, rig.store.persisted(s.key()).orElseThrow().status);
    }

    @Test
    void anAlreadyProcessedStructureIsNeverChangedByProcess() {
        Rig rig = new Rig();
        StructureSnapshot populated = Rig.village(0, 0);
        rig.engine.submit(populated);
        rig.run(50);
        StructureSnapshot abandoned = Rig.structure("minecraft:igloo", 5, 5);
        rig.cfg.defaults = new InhabitantsConfig.Rule(0.0, 1, 1);
        rig.engine.submit(abandoned);
        rig.run(5);
        rig.cfg.defaults = new InhabitantsConfig.Rule(1.0, 3, 3);

        for (ForceMode mode : ForceMode.values()) {
            for (StructureSnapshot s : List.of(populated, abandoned)) {
                StructureRecord before = InMemoryStorage.copy(rig.record(s.key()));
                EngineControl.ProcessOutcome out = rig.engine.process(s, mode);
                assertEquals(Kind.ALREADY_PROCESSED, out.kind(), mode + " " + s.key());
                assertTrue(out.message().contains(before.status.name()), out.message());
                assertEquals(before.status, rig.record(s.key()).status);
                assertEquals(before.structureSeed, rig.record(s.key()).structureSeed);
                assertEquals(Rig.names(before), Rig.names(rig.record(s.key())));
            }
        }
        assertEquals(2, rig.engine.stats().structuresRolled());
    }

    @Test
    void processIsRejectedWhenTheAddonCannotWork() {
        Rig rig = new Rig();
        StructureSnapshot s = Rig.village(0, 0);

        rig.cfg.enabled = false;
        assertEquals(Kind.REJECTED, rig.engine.process(s, ForceMode.OCCUPIED).kind());
        rig.cfg.enabled = true;

        rig.bots.available = false;
        EngineControl.ProcessOutcome out = rig.engine.process(s, ForceMode.OCCUPIED);
        assertEquals(Kind.REJECTED, out.kind());
        assertTrue(out.message().contains(rig.bots.reason), out.message());
        rig.bots.available = true;

        rig.store.usable = false;
        assertEquals(Kind.REJECTED, rig.engine.process(s, ForceMode.OCCUPIED).kind());
        rig.store.usable = true;

        assertEquals(0, rig.store.puts, "nothing was recorded by any rejected call");
        assertEquals(Kind.OCCUPIED_QUEUED, rig.engine.process(s, ForceMode.OCCUPIED).kind());
    }

    @Test
    void theExcludeListRejectsAPlainRollButYieldsToAnExplicitForce() {
        Rig rig = new Rig();
        rig.cfg.exclude = new ArrayList<>(List.of("minecraft:buried_treasure", "#minecraft:ocean_ruin"));
        StructureSnapshot treasure = Rig.structure("minecraft:buried_treasure", 0, 0);
        StructureSnapshot ruin = Rig.snapshot(Rig.OVERWORLD, "minecraft:ocean_ruin_cold", 1, 1,
                Set.of("minecraft:ocean_ruin"), 2, true);

        assertEquals(Kind.REJECTED, rig.engine.process(treasure, ForceMode.ROLL).kind());
        assertEquals(Kind.REJECTED, rig.engine.process(ruin, ForceMode.ROLL).kind());
        assertEquals(0, rig.store.recordCount());

        assertEquals(Kind.OCCUPIED_QUEUED, rig.engine.process(treasure, ForceMode.OCCUPIED).kind());
        assertEquals(Kind.ABANDONED, rig.engine.process(ruin, ForceMode.ABANDONED).kind());
        assertEquals("ADMIN_FORCED", rig.record(treasure.key()).source);
        assertEquals("ADMIN_FORCED", rig.record(ruin.key()).source);
    }

    @Test
    void forcedOutcomesIgnoreTheOddsButKeepTheBotCountRangeAndRecordTheRoll() {
        Rig rig = new Rig();
        rig.cfg.deterministic.enabled = true;
        rig.cfg.defaults = new InhabitantsConfig.Rule(0.0, 2, 5);
        rig.cfg.processing.restoreSettleTicks = MANY / 2;
        StructureSnapshot occ = Rig.structure("minecraft:igloo", 1, 1);
        assertEquals(Kind.OCCUPIED_QUEUED, rig.engine.process(occ, ForceMode.OCCUPIED).kind());
        StructureRecord r = rig.record(occ.key());
        assertEquals("ADMIN_FORCED", r.source);
        assertTrue(r.plannedBots >= 2 && r.plannedBots <= 5);
        assertEquals(r.plannedBots, r.bots.size());
        assertTrue(r.roll >= 0.0 && r.roll < 1.0);
        assertEquals(0.0, r.occupiedChance);

        rig.cfg.defaults = new InhabitantsConfig.Rule(1.0, 2, 5);
        StructureSnapshot abd = Rig.structure("minecraft:igloo", 2, 2);
        assertEquals(Kind.ABANDONED, rig.engine.process(abd, ForceMode.ABANDONED).kind());
        assertEquals("ADMIN_FORCED", rig.record(abd.key()).source);
        assertEquals(StructureStatus.ABANDONED, rig.record(abd.key()).status);
    }

    @Test
    void processBypassesTheDimensionIncludeAndNewlyGeneratedFilters() {
        Rig rig = new Rig();
        rig.cfg.dimensions.exclude = new ArrayList<>(List.of("minecraft:the_nether"));
        rig.cfg.include = new ArrayList<>(List.of("minecraft:village_plains"));
        rig.cfg.processing.onlyNewlyGenerated = true;
        StructureSnapshot s = Rig.snapshot("minecraft:the_nether", "minecraft:bastion_remnant", 3, 3, Set.of(), 4, false);
        rig.engine.submit(s);
        rig.run(10);
        assertTrue(rig.store.find(s.key()).isEmpty(), "detection respects the filters");
        EngineControl.ProcessOutcome out = rig.engine.process(s, ForceMode.ROLL);
        assertTrue(out.kind() == Kind.OCCUPIED_QUEUED || out.kind() == Kind.ABANDONED, out.toString());
        assertTrue(rig.store.find(s.key()).isPresent());
    }

    @Test
    void processingAStructureThatIsWaitingForItsRollRollsItNowAndOnlyOnce() {
        Rig rig = new Rig();
        StructureSnapshot s = Rig.village(0, 0);
        rig.engine.submit(s);
        assertEquals(1, rig.engine.rollQueueSize());
        assertEquals(Kind.ABANDONED, rig.engine.process(s, ForceMode.ABANDONED).kind());
        assertEquals(0, rig.engine.rollQueueSize());
        rig.run(50);
        assertEquals(1, rig.engine.stats().structuresRolled());
        assertEquals(1, rig.engine.stats().structuresSeen(), "counted when queued, not again when forced");
        assertEquals(StructureStatus.ABANDONED, rig.record(s.key()).status);
        assertEquals(0, rig.bots.requests.size());
    }

    // ------------------------------------------------------------------ reset

    @Test
    void resetOfAnUnknownStructureIsFalseAndChangesNothing() {
        Rig rig = new Rig();
        assertFalse(rig.engine.reset(Rig.village(0, 0).key(), true));
        assertFalse(rig.engine.reset(Rig.village(0, 0).key(), false));
        assertTrue(rig.bots.removes.isEmpty());
        assertTrue(rig.bots.forgets.isEmpty());
    }

    @Test
    void resetForgetsTheRecordSoTheStructureIsRolledAgain() {
        Rig rig = new Rig();
        rig.cfg.defaults = new InhabitantsConfig.Rule(0.0, 1, 1);
        StructureSnapshot s = Rig.structure("minecraft:igloo", 4, 4);
        rig.engine.submit(s);
        rig.run(3);
        assertEquals(StructureStatus.ABANDONED, rig.record(s.key()).status);

        assertTrue(rig.engine.reset(s.key(), false));
        assertTrue(rig.store.find(s.key()).isEmpty());
        assertTrue(rig.store.persisted(s.key()).isEmpty(), "and durably so");

        rig.cfg.defaults = new InhabitantsConfig.Rule(1.0, 1, 1);
        rig.engine.submit(s);
        rig.run(30);
        assertEquals(StructureStatus.POPULATED, rig.record(s.key()).status);
    }

    @Test
    void inDeterministicModeAResetStructureIsRolledIdenticallyAgain() {
        Rig rig = new Rig();
        rig.cfg.deterministic.enabled = true;
        rig.cfg.defaults = new InhabitantsConfig.Rule(1.0, 1, 5);
        rig.cfg.processing.restoreSettleTicks = MANY / 2;
        StructureSnapshot s = Rig.village(6, 7);
        rig.engine.submit(s);
        rig.run(3);
        StructureRecord first = InMemoryStorage.copy(rig.record(s.key()));

        assertTrue(rig.engine.reset(s.key(), false));
        rig.engine.submit(s);
        rig.run(3);
        StructureRecord second = rig.record(s.key());
        assertEquals(first.structureSeed, second.structureSeed);
        assertEquals(first.roll, second.roll);
        assertEquals(first.status, second.status);
        assertEquals(Rig.names(first), Rig.names(second));
    }

    @Test
    void inRandomModeAResetStructureGetsAFreshRoll() {
        Rig rig = new Rig();
        rig.cfg.processing.restoreSettleTicks = MANY / 2;
        StructureSnapshot s = Rig.village(6, 7);
        rig.engine.submit(s);
        rig.run(3);
        long firstSeed = rig.record(s.key()).structureSeed;
        assertTrue(rig.engine.reset(s.key(), false));
        rig.engine.submit(s);
        rig.run(3);
        assertNotEquals(firstSeed, rig.record(s.key()).structureSeed);
    }

    @Test
    void resetWithRemoveBotsRemovesTheLivingAndForgetsEveryName() {
        Rig rig = new Rig();
        rig.cfg.processing.maxLiveBots = 1; // one bot spawns, two stay planned
        StructureSnapshot s = Rig.village(0, 0);
        rig.engine.submit(s);
        rig.run(60);
        StructureRecord r = rig.record(s.key());
        assertEquals(1, Rig.count(r, BotState.SPAWNED));
        List<String> all = Rig.names(r);
        String living = r.bots.stream().filter(b -> b.state == BotState.SPAWNED).findFirst().orElseThrow().name;

        assertTrue(rig.engine.reset(s.key(), true));
        assertEquals(List.of(living), rig.bots.removes, "only a spawned bot is removed");
        assertEquals(new HashSet<>(all), new HashSet<>(rig.bots.forgets), "every name is released upstream");
        assertFalse(rig.bots.isOnline(living));
        assertTrue(rig.store.find(s.key()).isEmpty());
        assertEquals(0, rig.engine.stats().liveBots());
        assertEquals(0, rig.engine.stats().queuedStructures());

        int calls = rig.bots.forgets.size() + rig.bots.restores.size();
        rig.run(3000);
        assertEquals(calls, rig.bots.forgets.size() + rig.bots.restores.size(), "the reset bots are no longer tracked");
    }

    @Test
    void resetWithoutRemoveBotsLeavesTheBotsAloneAndUntracked() {
        Rig rig = new Rig();
        StructureSnapshot s = Rig.village(0, 0);
        rig.engine.submit(s);
        rig.run(40);
        List<String> names = Rig.names(rig.record(s.key()));

        assertTrue(rig.engine.reset(s.key(), false));
        assertTrue(rig.bots.removes.isEmpty());
        assertTrue(rig.bots.forgets.isEmpty());
        for (String n : names) {
            assertTrue(rig.bots.isOnline(n));
            rig.bots.kill(n);
        }
        rig.run(3000);
        assertTrue(rig.bots.forgets.isEmpty(), "nothing tracks them any more");
    }

    @Test
    void resetAlsoRemovesBotsStillInFlightAndLeavesThemAloneIfTheyAppearLater() {
        Rig rig = new Rig();
        rig.cfg.processing.appearTimeoutTicks = 100_000;
        rig.bots.readyAfterPolls = MANY;
        StructureSnapshot s = Rig.village(0, 0);
        rig.engine.submit(s);
        rig.run(20);
        assertEquals(3, rig.engine.stats().botsInFlight());
        List<String> names = Rig.names(rig.record(s.key()));

        assertTrue(rig.engine.reset(s.key(), true));
        assertEquals(0, rig.engine.stats().botsInFlight());
        // A REQUESTED bot's entity may already exist upstream even though the engine has not polled it yet,
        // so reset defensively asks for removal too, not only for bots it had already promoted to SPAWNED.
        // bots.remove() is documented to no-op when there is no online bot entity with that name.
        assertEquals(new HashSet<>(names), new HashSet<>(rig.bots.removes));
        int polls = rig.bots.pollCalls;
        rig.run(50);
        assertEquals(polls, rig.bots.pollCalls, "no longer polled");

        for (String n : names) {
            rig.bots.bringOnline(n); // they appear after all
        }
        rig.run(200);
        assertTrue(rig.bots.applied.isEmpty(), "an abandoned request is never dressed");
        assertTrue(rig.store.find(s.key()).isEmpty());
    }

    @Test
    void resetWhilePendingDropsTheStructureFromTheQueue() {
        Rig rig = new Rig();
        rig.cfg.processing.restoreSettleTicks = MANY / 2;
        StructureSnapshot s = Rig.village(0, 0);
        rig.engine.submit(s);
        rig.run(3);
        assertEquals(1, rig.engine.stats().queuedStructures());
        assertTrue(rig.engine.reset(s.key(), true));
        assertEquals(0, rig.engine.stats().queuedStructures());
        assertEquals(0, rig.engine.pendingStructures());
    }

    @Test
    void aStructureResetAndRolledAgainDoesNotMixUpWithItsPreviousLife() {
        Rig rig = new Rig();
        rig.cfg.processing.appearTimeoutTicks = 100_000;
        rig.bots.readyAfterPolls = MANY;
        StructureSnapshot s = Rig.village(0, 0);
        rig.engine.submit(s);
        rig.run(10);
        List<String> firstNames = Rig.names(rig.record(s.key()));
        rig.engine.reset(s.key(), true);

        rig.bots.readyAfterPolls = 1;
        rig.engine.submit(s);
        rig.run(60);
        StructureRecord r = rig.record(s.key());
        assertEquals(StructureStatus.POPULATED, r.status);
        Set<String> second = new HashSet<>(Rig.names(r));
        for (String old : firstNames) {
            assertFalse(second.contains(old), "the old names are not reused: " + old);
        }
        assertEquals(3, rig.bots.applied.size());
    }

    // ------------------------------------------------------------------ stats

    @Test
    void statsCountEverythingThatHappens() {
        Rig rig = new Rig();
        rig.cfg.defaults = new InhabitantsConfig.Rule(1.0, 2, 2);
        rig.cfg.processing.maxLiveBots = 3;
        assertEquals(new EngineControl.EngineStats(0, 0, 0, 0, 0, 0, 0, 0), rig.engine.stats());

        rig.engine.submit(Rig.village(0, 0));
        rig.engine.submit(Rig.village(9, 0));
        EngineControl.EngineStats queued = rig.engine.stats();
        assertEquals(2, queued.structuresSeen());
        assertEquals(0, queued.structuresRolled());
        assertEquals(2, queued.queuedStructures());

        rig.run(1);
        EngineControl.EngineStats afterOne = rig.engine.stats();
        assertEquals(2, afterOne.structuresRolled());
        assertEquals(3, afterOne.botsRequested(), "the cap of 3 holds the fourth");
        assertEquals(3, afterOne.botsInFlight());
        assertEquals(2, afterOne.queuedStructures());

        rig.run(5);
        EngineControl.EngineStats settled = rig.engine.stats();
        assertEquals(3, settled.botsSpawned());
        assertEquals(3, settled.liveBots());
        assertEquals(0, settled.botsInFlight());
        assertEquals(1, settled.queuedStructures(), "the second village waits for a free slot");

        rig.bots.refuseAll = true;
        rig.bots.kill(rig.bots.requestedNames().get(0));
        rig.run(200);
        assertTrue(rig.engine.stats().botsFailed() >= 1);
    }

    // ------------------------------------------------------------------ view

    @Test
    void theViewReflectsTheStoreAndCannotWriteToIt() {
        Rig rig = new Rig();
        StructureSnapshot s = Rig.village(3, 4);
        rig.engine.submit(s);
        rig.run(40);
        PopulationView view = rig.engine.view();

        assertFalse(view instanceof PopulationStorage, "the view must not leak the writable store");
        Optional<StructureRecord> found = view.find(s.key());
        assertTrue(found.isPresent());
        assertSame(rig.record(s.key()), found.get());
        String name = found.get().bots.get(1).name;
        assertEquals(s.key(), view.findBot(name.toUpperCase(Locale.ROOT)).orElseThrow().structure());
        assertEquals(1, view.counts().populated());
        assertEquals(3, view.counts().botsSpawned());
        assertEquals(1, view.nearby(Rig.OVERWORLD, 3, 4, 2).size());
        assertTrue(view.nearby(Rig.OVERWORLD, 30, 40, 2).isEmpty());
        assertTrue(view.find(Rig.village(99, 99).key()).isEmpty());
    }

    // ------------------------------------------------------------------ configuration

    @Test
    void aReplacedConfigObjectIsPickedUpWithoutAnyNotification() {
        AtomicReference<InhabitantsConfig> ref = new AtomicReference<>(Rig.fastConfig());
        Rig helper = new Rig();
        PopulationEngine engine = new PopulationEngine(ref::get, helper.store, helper.bots, helper.world,
                helper.clock, helper.profiles, helper.planner, new SplitMix64(1)::nextLong);
        InhabitantsConfig off = Rig.fastConfig();
        off.enabled = false;

        ref.set(off);
        engine.submit(Rig.village(0, 0));
        assertEquals(0, engine.stats().structuresSeen());

        ref.set(Rig.fastConfig());
        engine.submit(Rig.village(0, 0));
        assertEquals(1, engine.stats().structuresSeen());
        for (int i = 0; i < 20; i++) {
            helper.clock.tick++;
            engine.tick();
        }
        assertEquals(1, helper.store.counts().populated());

        InhabitantsConfig never = Rig.fastConfig();
        never.defaults = new InhabitantsConfig.Rule(0.0, 1, 1);
        ref.set(never);
        engine.onConfigReloaded();
        engine.submit(Rig.village(20, 20));
        for (int i = 0; i < 5; i++) {
            helper.clock.tick++;
            engine.tick();
        }
        assertEquals(StructureStatus.ABANDONED, helper.record(Rig.village(20, 20).key()).status);
    }

    @Test
    void aSupplierThatReturnsNothingMakesTheEngineIdleInsteadOfCrashing() {
        Rig helper = new Rig();
        PopulationEngine engine = new PopulationEngine(() -> null, helper.store, helper.bots, helper.world,
                helper.clock, helper.profiles, helper.planner);
        assertDoesNotThrow(() -> {
            engine.submit(Rig.village(0, 0));
            engine.tick();
            engine.tick();
            engine.onConfigReloaded();
            assertEquals(Kind.REJECTED, engine.process(Rig.village(1, 1), ForceMode.OCCUPIED).kind());
            engine.stats();
        });
        assertEquals(0, helper.store.puts);
    }

    @Test
    void aSupplierThatThrowsCannotCrashTheServerTick() {
        Rig helper = new Rig();
        PopulationEngine engine = new PopulationEngine(() -> {
            throw new IllegalStateException("config file locked");
        }, helper.store, helper.bots, helper.world, helper.clock, helper.profiles, helper.planner);
        assertDoesNotThrow(() -> {
            engine.submit(Rig.village(0, 0));
            for (int i = 0; i < 5; i++) {
                helper.clock.tick++;
                engine.tick();
            }
            engine.onConfigReloaded();
            assertEquals(Kind.REJECTED, engine.process(Rig.village(1, 1), ForceMode.OCCUPIED).kind());
            engine.stats();
            engine.reset(Rig.village(2, 2).key(), true);
        });
        assertEquals(0, helper.store.puts);
        assertTrue(engine.containedErrors() > 0);
    }

    @Test
    void tighteningTheProcessingLimitsAppliesToTheNextTick() {
        Rig rig = new Rig();
        rig.cfg.defaults = new InhabitantsConfig.Rule(1.0, 4, 4);
        rig.cfg.processing.maxBotsPerTick = 4;
        rig.cfg.processing.spawnIntervalTicks = 0;
        rig.engine.submit(Rig.village(0, 0));
        rig.run(1);
        assertEquals(4, rig.bots.requests.size());

        rig.engine.submit(Rig.village(9, 0));
        rig.cfg.processing.maxBotsPerTick = 1;
        rig.cfg.processing.spawnIntervalTicks = 10;
        rig.run(9); // ticks 2..10
        assertEquals(4, rig.bots.requests.size(), "the new interval counts from the last request, at tick 1");
        rig.run(1); // tick 11
        assertEquals(5, rig.bots.requests.size(), "and only one bot per batch under the new limit");
        rig.run(9);
        assertEquals(5, rig.bots.requests.size());
        rig.run(1); // tick 21
        assertEquals(6, rig.bots.requests.size());
    }

    @Test
    void realEntropyMakesTwoRunsDiffer() {
        Set<Long> seeds = new HashSet<>();
        for (int run = 0; run < 4; run++) {
            Rig rig = new Rig();
            PopulationEngine engine = new PopulationEngine(() -> rig.cfg, rig.store, rig.bots, rig.world, rig.clock,
                    rig.profiles, rig.planner);
            engine.submit(Rig.village(0, 0));
            rig.clock.tick++;
            engine.tick();
            seeds.add(rig.record(Rig.village(0, 0).key()).structureSeed);
        }
        assertEquals(4, seeds.size(), "structure seeds must come from real entropy in normal mode");
    }

    @Test
    void theKeyOfARecordCanBeUsedToFindItsBotsByName() {
        Rig rig = new Rig();
        StructureSnapshot s = Rig.village(1, 2);
        rig.engine.submit(s);
        rig.run(40);
        for (BotRecord b : rig.record(s.key()).bots) {
            PopulationView.BotLocation loc = rig.engine.view().findBot(b.name).orElseThrow();
            assertEquals(new StructureKey(Rig.OVERWORLD, Rig.VILLAGE, 1, 2), loc.structure());
            assertEquals(b.name, loc.bot().name);
        }
        assertEquals(StableHash.combine(rig.record(s.key()).structureSeed, 0x100), rig.record(s.key()).bots.get(0).seed);
    }
}
