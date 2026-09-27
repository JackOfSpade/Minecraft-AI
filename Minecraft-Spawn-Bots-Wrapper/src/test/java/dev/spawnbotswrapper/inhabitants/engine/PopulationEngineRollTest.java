package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.BotState;
import dev.spawnbotswrapper.inhabitants.store.StructureRecord;
import dev.spawnbotswrapper.inhabitants.store.StructureStatus;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;
import dev.spawnbotswrapper.inhabitants.util.StableHash;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The decision itself: once per structure, with the right odds, count, persistence and filters. */
class PopulationEngineRollTest {

    /** Keeps spawning switched off so a test can look at the freshly rolled records. */
    private static void holdSpawns(Rig rig) {
        rig.cfg.processing.restoreSettleTicks = Integer.MAX_VALUE / 2;
    }

    // ------------------------------------------------------------------ exactly once

    @Test
    void exactlyOneRollHoweverOftenTheSameStructureIsSubmitted() {
        Rig rig = new Rig();
        StructureSnapshot village = Rig.village(3, 4);
        for (int i = 0; i < 100; i++) {
            rig.engine.submit(village);
            if (i % 7 == 0) {
                rig.run(1);
            }
        }
        rig.run(200);
        assertEquals(StructureStatus.POPULATED, rig.record(village.key()).status);

        // the chunk unloads and reloads over and over, long after the population finished
        for (int i = 0; i < 100; i++) {
            rig.engine.submit(village);
            rig.run(1);
        }
        assertEquals(1, rig.engine.stats().structuresRolled());
        assertEquals(1, rig.engine.stats().structuresSeen());
        assertEquals(1, rig.store.recordCount());
        assertEquals(3, rig.bots.requests.size());
    }

    @Test
    void aVillageWithFortyPiecesIsOneStructureOneRoll() {
        Rig rig = new Rig();
        StructureSnapshot village = Rig.village(10, 10);
        assertEquals(40, village.pieces().size());
        // the same structure is reported from several chunk loads, some generated and some merely loaded
        rig.engine.submit(village);
        rig.engine.submit(new StructureSnapshot(village.key(), village.tagIds(), village.bounds(), village.pieces(), false));
        rig.engine.submit(new StructureSnapshot(village.key(), village.tagIds(), village.bounds(), List.of(), false));
        rig.run(50);
        assertEquals(1, rig.engine.stats().structuresRolled());
        assertEquals(1, rig.store.recordCount());
    }

    @Test
    void aStructureAlreadyQueuedForRollingIsNotQueuedAgain() {
        Rig rig = new Rig();
        StructureSnapshot s = Rig.village(1, 1);
        rig.engine.submit(s);
        rig.engine.submit(s);
        assertEquals(1, rig.engine.rollQueueSize());
        assertEquals(1, rig.engine.stats().structuresSeen());
    }

    @Test
    void differentStartChunksOfTheSameStructureTypeAreDifferentInstances() {
        Rig rig = new Rig();
        rig.engine.submit(Rig.village(0, 0));
        rig.engine.submit(Rig.village(0, 1));
        rig.engine.submit(Rig.village(1, 0));
        rig.run(1);
        assertEquals(3, rig.engine.stats().structuresRolled());
    }

    // ------------------------------------------------------------------ odds and counts

    @Test
    void occupiedFractionOverTenThousandStructuresMatchesTheChance() {
        Rig rig = new Rig(new InMemoryStorage(false), 7);
        rig.cfg.defaults = new InhabitantsConfig.Rule(0.65, 1, 4);
        holdSpawns(rig);
        rig.feed(10_000, i -> Rig.structure("minecraft:desert_pyramid", i % 200, i / 200));
        assertEquals(10_000, rig.engine.stats().structuresRolled());
        assertEquals(10_000, rig.store.recordCount());
        double fraction = rig.store.counts().pending() / 10_000.0;
        assertEquals(0.65, fraction, 0.025, "occupied fraction " + fraction);
        assertEquals(10_000 - rig.store.counts().pending(), rig.store.counts().abandoned());
    }

    @Test
    void occupiedFractionInDeterministicModeMatchesTheChanceToo() {
        Rig rig = new Rig(new InMemoryStorage(false), 7);
        rig.cfg.defaults = new InhabitantsConfig.Rule(0.30, 1, 2);
        rig.cfg.deterministic.enabled = true;
        holdSpawns(rig);
        rig.feed(10_000, i -> Rig.structure("minecraft:igloo", i % 200 - 100, i / 200 - 25));
        assertEquals(10_000, rig.store.recordCount());
        double fraction = rig.store.counts().pending() / 10_000.0;
        assertEquals(0.30, fraction, 0.025, "occupied fraction " + fraction);
    }

    @Test
    void chanceZeroNeverAndChanceOneAlwaysAreExact() {
        for (double chance : new double[]{0.0, 1.0}) {
            Rig rig = new Rig(new InMemoryStorage(false), 3);
            rig.cfg.defaults = new InhabitantsConfig.Rule(chance, 1, 3);
            holdSpawns(rig);
            for (int i = 0; i < 3000; i++) {
                rig.engine.submit(Rig.structure("minecraft:shipwreck", i, -i));
            }
            rig.run(100);
            assertEquals(3000, rig.store.recordCount());
            assertEquals(chance == 1.0 ? 3000 : 0, rig.store.counts().pending(), "chance " + chance);
            assertEquals(chance == 0.0 ? 3000 : 0, rig.store.counts().abandoned(), "chance " + chance);
        }
    }

    @Test
    void botCountCoversTheFullInclusiveRange() {
        Rig rig = new Rig(new InMemoryStorage(false), 5);
        rig.cfg.defaults = new InhabitantsConfig.Rule(1.0, 2, 6);
        holdSpawns(rig);
        for (int i = 0; i < 3000; i++) {
            rig.engine.submit(Rig.structure("minecraft:desert_pyramid", i, 0));
        }
        rig.run(100);
        int[] histogram = new int[8];
        for (StructureRecord r : rig.store.all().values()) {
            assertTrue(r.plannedBots >= 2 && r.plannedBots <= 6, "planned " + r.plannedBots);
            assertEquals(r.plannedBots, r.bots.size());
            histogram[r.plannedBots]++;
        }
        for (int n = 2; n <= 6; n++) {
            assertTrue(histogram[n] > 3000 / 5 * 0.8, "bot count " + n + " seen only " + histogram[n] + " times");
        }
    }

    @Test
    void perStructureAndPerTagRulesDecideTheOddsAndTheCount() {
        Rig rig = new Rig();
        rig.cfg.defaults = new InhabitantsConfig.Rule(0.0, 1, 1);
        rig.cfg.structures.put("minecraft:pillager_outpost", new InhabitantsConfig.RuleOverride(1.0, 5, 5));
        rig.cfg.tags.put("#minecraft:village", new InhabitantsConfig.RuleOverride(1.0, 2, 2));
        holdSpawns(rig);

        StructureSnapshot outpost = Rig.structure("minecraft:pillager_outpost", 0, 0);
        StructureSnapshot village = Rig.village(5, 5);
        StructureSnapshot other = Rig.structure("minecraft:igloo", 9, 9);
        rig.engine.submit(outpost);
        rig.engine.submit(village);
        rig.engine.submit(other);
        rig.run(1);

        assertEquals(5, rig.record(outpost.key()).bots.size());
        assertEquals(2, rig.record(village.key()).bots.size());
        assertEquals(StructureStatus.ABANDONED, rig.record(other.key()).status);
    }

    // ------------------------------------------------------------------ what is recorded

    @Test
    void theRollRecordsItsInputsAndPlansEveryBotBeforeAnythingHappens() {
        Rig rig = new Rig();
        rig.cfg.defaults = new InhabitantsConfig.Rule(1.0, 4, 4);
        holdSpawns(rig);
        StructureSnapshot s = Rig.village(2, 3);
        rig.engine.submit(s);
        rig.run(1);

        StructureRecord r = rig.record(s.key());
        assertEquals(StructureStatus.OCCUPIED_PENDING, r.status);
        assertEquals("RANDOM", r.source);
        assertEquals(1.0, r.occupiedChance);
        assertTrue(r.roll >= 0.0 && r.roll < 1.0);
        assertEquals(4, r.plannedBots);
        assertEquals(rig.clock.nowMillis(), r.rolledAtMillis);
        assertArrayEquals(new int[]{32, 60, 48, 32 + 47, 80, 48 + 47}, r.bounds);
        assertEquals(0, r.attempts);

        Set<String> names = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            BotRecord b = r.bots.get(i);
            assertEquals(i, b.index);
            assertEquals(StableHash.combine(r.structureSeed, 0x100 + i), b.seed);
            assertEquals(BotState.PLANNED, b.state);
            assertEquals(0, b.spawnAttempts);
            assertNull(b.profile);
            assertTrue(NameGenerator.isValid(b.name), b.name);
            assertTrue(names.add(b.name.toLowerCase(Locale.ROOT)), "duplicate name " + b.name);
        }
        // and all of it is already on "disk"
        StructureRecord onDisk = rig.store.persisted(s.key()).orElseThrow();
        assertEquals(Rig.names(r), Rig.names(onDisk));
        assertEquals(r.structureSeed, onDisk.structureSeed);
    }

    @Test
    void deterministicRollIsRecordedAsSuchAndUsesTheDocumentedSeedFormula() {
        Rig rig = new Rig();
        rig.cfg.deterministic.enabled = true;
        rig.cfg.deterministic.salt = "pepper";
        rig.world.seed = 4711;
        holdSpawns(rig);
        StructureSnapshot s = Rig.village(-7, 9);
        rig.engine.submit(s);
        rig.run(1);
        StructureRecord r = rig.record(s.key());
        assertEquals("DETERMINISTIC", r.source);
        assertEquals(StableHash.of(4711, StableHash.ofString("pepper"), s.key().stableHash()), r.structureSeed);
    }

    @Test
    void theRollIsDurableBeforeTheFirstSpawnIsRequested() {
        Rig rig = new Rig();
        List<StructureRecord> seenOnDisk = new ArrayList<>();
        StructureSnapshot s = Rig.village(0, 0);
        rig.bots.onRequest = req -> seenOnDisk.add(rig.store.persisted(s.key()).orElse(null));
        rig.engine.submit(s);
        rig.run(30);

        assertEquals(3, seenOnDisk.size());
        for (StructureRecord onDisk : seenOnDisk) {
            assertNotNull(onDisk, "the roll was not persisted before a spawn request");
            assertEquals(StructureStatus.OCCUPIED_PENDING, onDisk.status);
            assertEquals(3, onDisk.bots.size());
            assertEquals(3, onDisk.plannedBots);
        }
    }

    @Test
    void namesAreUniqueAcrossStructuresAndAlwaysValid() {
        Rig rig = new Rig(new InMemoryStorage(false), 11);
        rig.cfg.defaults = new InhabitantsConfig.Rule(1.0, 3, 3);
        rig.cfg.spawning.namePrefix = "Inh"; // this test's own "Inh_" check wants an explicit prefix, not the shipped default
        holdSpawns(rig);
        for (int i = 0; i < 1500; i++) {
            rig.engine.submit(Rig.structure("minecraft:desert_pyramid", i, i * 3));
        }
        rig.run(100);
        Set<String> names = new HashSet<>();
        for (BotRecord b : rig.store.allBots()) {
            assertTrue(NameGenerator.isValid(b.name), b.name);
            assertTrue(b.name.startsWith("Inh_"), b.name);
            assertTrue(names.add(b.name.toLowerCase(Locale.ROOT)), "duplicate " + b.name);
        }
        assertEquals(4500, names.size());
    }

    @Test
    void aNameAlreadyInTheStoreIsSkippedCaseInsensitively() {
        Rig rig = new Rig();
        rig.cfg.deterministic.enabled = true;
        rig.cfg.defaults = new InhabitantsConfig.Rule(1.0, 1, 1);
        holdSpawns(rig);
        StructureSnapshot s = Rig.village(4, 4);

        long structureSeed = StableHash.of(rig.world.seed, StableHash.ofString(""), s.key().stableHash());
        long botSeed = StructureRoll.botSeed(structureSeed, 0);
        NameGenerator gen = new NameGenerator("");
        String first = gen.candidate(botSeed, 0);

        // an older, unrelated structure already owns that name (in different letter case)
        StructureRecord old = new StructureRecord();
        old.bots.add(new BotRecord(0, first.toUpperCase(Locale.ROOT), 1));
        rig.store.put(new StructureKey(Rig.OVERWORLD, "minecraft:igloo", 50, 50), old);

        rig.engine.submit(s);
        rig.run(1);
        assertEquals(gen.candidate(botSeed, 1), rig.record(s.key()).bots.get(0).name);
    }

    @Test
    void thePrefixFromTheConfigIsUsed() {
        Rig rig = new Rig();
        rig.cfg.spawning.namePrefix = "Zed";
        holdSpawns(rig);
        rig.engine.submit(Rig.village(0, 0));
        rig.run(1);
        for (BotRecord b : rig.store.allBots()) {
            assertTrue(b.name.startsWith("Zed_"), b.name);
        }
    }

    // ------------------------------------------------------------------ abandoned is forever

    @Test
    void abandonedStructuresArePersistedAndNeverRerolledByAFreshEngine() {
        Rig rig = new Rig();
        rig.cfg.defaults = new InhabitantsConfig.Rule(0.0, 1, 3);
        List<StructureSnapshot> all = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            all.add(Rig.structure("minecraft:desert_pyramid", i, i));
            rig.engine.submit(all.get(i));
        }
        rig.run(5);
        for (StructureSnapshot s : all) {
            assertEquals(StructureStatus.ABANDONED, rig.record(s.key()).status);
            assertEquals(StructureStatus.ABANDONED, rig.store.persisted(s.key()).orElseThrow().status,
                    "an abandoned verdict must survive a crash immediately");
            assertTrue(rig.record(s.key()).bots.isEmpty());
        }

        // crash, restart, and the odds are now "always occupied": nothing may be rolled again
        rig.cfg.defaults = new InhabitantsConfig.Rule(1.0, 3, 3);
        rig.restart(false);
        for (StructureSnapshot s : all) {
            rig.engine.submit(s);
        }
        rig.run(50);
        assertEquals(0, rig.engine.stats().structuresRolled());
        assertEquals(0, rig.engine.stats().structuresSeen());
        assertEquals(50, rig.store.counts().abandoned());
        assertEquals(0, rig.bots.requests.size());
    }

    @Test
    void abandonedRecordCarriesTheRollItWasDecidedBy() {
        Rig rig = new Rig();
        rig.cfg.defaults = new InhabitantsConfig.Rule(0.0, 1, 3);
        StructureSnapshot s = Rig.structure("minecraft:igloo", 1, 1);
        rig.engine.submit(s);
        rig.run(1);
        StructureRecord r = rig.record(s.key());
        assertEquals(0.0, r.occupiedChance);
        assertTrue(r.roll >= 0.0 && r.roll < 1.0);
        assertEquals("RANDOM", r.source);
        assertNotEquals(0L, r.structureSeed);
        assertEquals(0, r.plannedBots);
    }

    // ------------------------------------------------------------------ nothing is rolled when it must not be

    @Test
    void disabledAddonRollsNothing() {
        Rig rig = new Rig();
        rig.cfg.enabled = false;
        rig.engine.submit(Rig.village(0, 0));
        rig.run(50);
        assertEquals(0, rig.store.puts);
        assertEquals(0, rig.engine.stats().structuresSeen());
        assertEquals(0, rig.bots.requests.size());

        rig.cfg.enabled = true;
        rig.engine.submit(Rig.village(0, 0));
        rig.run(20);
        assertEquals(1, rig.engine.stats().structuresRolled());
        assertEquals(3, rig.bots.requests.size());
    }

    @Test
    void disablingWhileAStructureIsQueuedHoldsItUntilReenabled() {
        Rig rig = new Rig();
        StructureSnapshot s = Rig.village(0, 0);
        rig.engine.submit(s);
        rig.cfg.enabled = false;
        rig.run(30);
        assertEquals(0, rig.store.puts, "must not roll while disabled");
        rig.cfg.enabled = true;
        rig.run(30);
        assertEquals(StructureStatus.POPULATED, rig.record(s.key()).status);
    }

    @Test
    void unusableStoreMeansNothingHappens() {
        Rig rig = new Rig();
        rig.store.usable = false;
        rig.engine.submit(Rig.village(0, 0));
        rig.run(100);
        assertEquals(0, rig.store.puts);
        assertEquals(0, rig.store.saveCalls);
        assertEquals(0, rig.bots.requests.size());
        assertEquals(0, rig.engine.stats().structuresSeen());
    }

    @Test
    void unavailableIntegrationNeverRollsOrPersistsAndLaterWorks() {
        Rig rig = new Rig();
        rig.bots.available = false;
        for (int i = 0; i < 20; i++) {
            rig.engine.submit(Rig.village(i, 0));
        }
        rig.run(200);
        assertEquals(0, rig.store.puts, "an unavailable integration must never mark structures processed");
        assertEquals(0, rig.store.writes);
        assertEquals(0, rig.store.recordCount());
        assertEquals(0, rig.engine.stats().structuresRolled());
        assertEquals(0, rig.engine.stats().structuresSeen(), "not even accepted while the integration is down");
        assertEquals(0, rig.engine.rollQueueSize());

        rig.bots.available = true;
        for (int i = 0; i < 20; i++) {
            rig.engine.submit(Rig.village(i, 0));
        }
        rig.run(100);
        assertEquals(20, rig.store.counts().populated());
        assertEquals(60, rig.bots.requests.size());
    }

    @Test
    void integrationGoingDownAfterDetectionHoldsTheRoll() {
        Rig rig = new Rig();
        StructureSnapshot s = Rig.village(0, 0);
        rig.engine.submit(s);
        rig.bots.available = false;
        rig.run(60);
        assertEquals(0, rig.store.puts);
        rig.bots.available = true;
        rig.run(30);
        assertEquals(StructureStatus.POPULATED, rig.record(s.key()).status);
    }

    // ------------------------------------------------------------------ filters

    @Test
    void dimensionFilterKeepsExcludedDimensionsCompletelyOutOfTheStore() {
        Rig rig = new Rig();
        rig.cfg.dimensions.exclude = new ArrayList<>(List.of("minecraft:the_nether"));
        StructureSnapshot nether = Rig.snapshot("minecraft:the_nether", "minecraft:fortress", 0, 0, Set.of(), 3, true);
        StructureSnapshot over = Rig.snapshot(Rig.OVERWORLD, "minecraft:fortress", 0, 0, Set.of(), 3, true);
        rig.engine.submit(nether);
        rig.engine.submit(over);
        rig.run(20);
        assertTrue(rig.store.find(nether.key()).isEmpty());
        assertTrue(rig.store.find(over.key()).isPresent());

        rig.cfg.dimensions.exclude = new ArrayList<>();
        rig.cfg.dimensions.include = new ArrayList<>(List.of("minecraft:the_end"));
        rig.engine.submit(Rig.snapshot(Rig.OVERWORLD, "minecraft:fortress", 9, 9, Set.of(), 3, true));
        rig.run(5);
        assertEquals(1, rig.store.recordCount(), "overworld is no longer an included dimension");
    }

    @Test
    void excludedAndNonIncludedStructuresNeverRollAndLeaveNoRecord() {
        Rig rig = new Rig();
        rig.cfg.exclude = new ArrayList<>(List.of("minecraft:buried_treasure", "#minecraft:ocean_ruin"));
        StructureSnapshot treasure = Rig.structure("minecraft:buried_treasure", 0, 0);
        StructureSnapshot ruin = Rig.snapshot(Rig.OVERWORLD, "minecraft:ocean_ruin_warm", 1, 1,
                Set.of("minecraft:ocean_ruin"), 2, true);
        rig.engine.submit(treasure);
        rig.engine.submit(ruin);
        rig.run(20);
        assertEquals(0, rig.store.recordCount());
        assertEquals(0, rig.engine.stats().structuresSeen());

        rig.cfg.exclude = new ArrayList<>();
        rig.cfg.include = new ArrayList<>(List.of("minecraft:village_plains", "#minecraft:mineshaft"));
        rig.engine.submit(Rig.structure("minecraft:igloo", 5, 5));
        rig.engine.submit(Rig.village(6, 6));
        rig.engine.submit(Rig.snapshot(Rig.OVERWORLD, "minecraft:mineshaft_mesa", 7, 7,
                Set.of("minecraft:mineshaft"), 2, true));
        rig.run(20);
        assertEquals(2, rig.store.recordCount(), "only the included structures are rolled");
        assertTrue(rig.store.find(Rig.structure("minecraft:igloo", 5, 5).key()).isEmpty());
    }

    @Test
    void anExcludeThatAppearsAfterDetectionStopsTheStructureBeingRolled() {
        Rig rig = new Rig();
        StructureSnapshot s = Rig.structure("minecraft:igloo", 0, 0);
        rig.engine.submit(s);
        rig.cfg.exclude = new ArrayList<>(List.of("minecraft:igloo"));
        rig.run(20);
        assertEquals(0, rig.store.recordCount());
    }

    @Test
    void onlyNewlyGeneratedIgnoresStructuresThatWereMerelyLoaded() {
        Rig rig = new Rig();
        rig.cfg.processing.onlyNewlyGenerated = true;
        StructureSnapshot loaded = Rig.snapshot(Rig.OVERWORLD, Rig.VILLAGE, 0, 0, Set.of(), 3, false);
        StructureSnapshot fresh = Rig.snapshot(Rig.OVERWORLD, Rig.VILLAGE, 1, 1, Set.of(), 3, true);
        rig.engine.submit(loaded);
        rig.engine.submit(fresh);
        rig.run(20);
        assertTrue(rig.store.find(loaded.key()).isEmpty());
        assertTrue(rig.store.find(fresh.key()).isPresent());
        assertEquals(1, rig.engine.stats().structuresSeen(), "the merely-loaded one is ignored, not just left unrolled");

        rig.cfg.processing.onlyNewlyGenerated = false;
        rig.engine.submit(loaded);
        rig.run(20);
        assertTrue(rig.store.find(loaded.key()).isPresent(), "existing worlds get inhabitants when the filter is off");
    }

    // ------------------------------------------------------------------ pacing of the rolls themselves

    @Test
    void rollsAreSpreadOverTicksByMaxStructuresPerTick() {
        Rig rig = new Rig();
        rig.cfg.processing.maxStructuresPerTick = 4;
        holdSpawns(rig);
        for (int i = 0; i < 10; i++) {
            rig.engine.submit(Rig.structure("minecraft:igloo", i, 0));
        }
        assertEquals(10, rig.engine.rollQueueSize());
        rig.run(1);
        assertEquals(4, rig.store.recordCount());
        rig.run(1);
        assertEquals(8, rig.store.recordCount());
        rig.run(1);
        assertEquals(10, rig.store.recordCount());
        assertEquals(0, rig.engine.rollQueueSize());
    }

    @Test
    void theRollQueueIsBoundedAndDroppedStructuresAreSimplyDetectedAgain() {
        Rig rig = new Rig(new InMemoryStorage(false), 1);
        holdSpawns(rig);
        int detected = PopulationEngine.MAX_ROLL_QUEUE + 500;
        // a burst of chunk loads arrives before the first tick can drain the queue
        for (int i = 0; i < detected; i++) {
            rig.engine.submit(Rig.structure("minecraft:igloo", i, 1));
        }
        assertEquals(PopulationEngine.MAX_ROLL_QUEUE, rig.engine.rollQueueSize());

        rig.run(PopulationEngine.MAX_ROLL_QUEUE / 64 + 5);
        assertEquals(PopulationEngine.MAX_ROLL_QUEUE, rig.store.recordCount());

        // the structures that did not fit are not lost: their next detection is accepted normally
        for (int i = 0; i < detected; i++) {
            rig.engine.submit(Rig.structure("minecraft:igloo", i, 1));
        }
        rig.run(20);
        assertEquals(detected, rig.store.recordCount());
        assertEquals(detected, rig.engine.stats().structuresRolled());
    }
}
