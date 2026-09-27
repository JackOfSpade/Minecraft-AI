package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile;
import dev.spawnbotswrapper.inhabitants.profile.GlobalCapabilities;
import dev.spawnbotswrapper.inhabitants.sample.PersistentDeckStore;
import dev.spawnbotswrapper.inhabitants.sample.TransientDeckStore;
import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.BotState;
import dev.spawnbotswrapper.inhabitants.store.StructureRecord;
import dev.spawnbotswrapper.inhabitants.store.StructureStatus;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic mode: the same world seed, salt and structure identity give the same decisions, counts, names,
 * bot seeds and profiles - whatever order things happen in, and even across restarts.
 */
class PopulationEngineDeterminismTest {

    private static final int MANY = Integer.MAX_VALUE;

    private static Rig deterministicRig(long worldSeed, String salt, long entropySeed) {
        Rig rig = new Rig(new InMemoryStorage(false), entropySeed);
        rig.cfg.deterministic.enabled = true;
        rig.cfg.deterministic.salt = salt;
        rig.cfg.defaults = new InhabitantsConfig.Rule(0.6, 1, 5);
        rig.world.seed = worldSeed;
        return rig;
    }

    private static List<StructureSnapshot> manyStructures(int n) {
        List<StructureSnapshot> all = new ArrayList<>();
        String[] ids = {"minecraft:village_plains", "minecraft:desert_pyramid", "minecraft:igloo", "somemod:tower"};
        String[] dims = {Rig.OVERWORLD, "minecraft:the_nether", "minecraft:the_end"};
        for (int i = 0; i < n; i++) {
            all.add(Rig.snapshot(dims[i % 3], ids[i % 4], i - 100, 3 * i, Set.of(), 4, true));
        }
        return all;
    }

    /** What a correct deterministic profile for bot #index is, computed independently of the engine. */
    private static BotProfile expectedProfile(StructureRecord r, int index) {
        FakeProfiles oracle = new FakeProfiles();
        TransientDeckStore decks = new TransientDeckStore();
        for (int j = 0; j < index; j++) {
            oracle.create(r.bots.get(j).seed, GlobalCapabilities.upstreamDefaults(), decks);
        }
        return oracle.create(r.bots.get(index).seed, GlobalCapabilities.upstreamDefaults(), decks);
    }

    private static BotProfile withoutWaypoints(BotProfile p) {
        return p.withBehavior(p.behavior().withWaypoints(List.of()));
    }

    // ------------------------------------------------------------------ decisions

    @Test
    void twoFreshWorldsWithTheSameSeedMakeIdenticalDecisionsHoweverTheyAreFed() {
        Rig a = deterministicRig(31337, "s", 1);
        Rig b = deterministicRig(31337, "s", 2);
        List<StructureSnapshot> all = manyStructures(300);
        for (StructureSnapshot s : all) {
            a.engine.submit(s);
        }
        List<StructureSnapshot> reversed = new ArrayList<>(all);
        Collections.reverse(reversed);
        for (int i = 0; i < reversed.size(); i++) {
            b.engine.submit(reversed.get(i));
            if (i % 37 == 0) {
                b.run(3);
            }
        }
        a.run(800);
        b.run(800);

        int occupied = 0;
        for (StructureSnapshot s : all) {
            StructureRecord ra = a.record(s.key());
            StructureRecord rb = b.record(s.key());
            assertEquals("DETERMINISTIC", ra.source);
            assertEquals(ra.status, rb.status, s.key().toString());
            assertEquals(ra.roll, rb.roll);
            assertEquals(ra.occupiedChance, rb.occupiedChance);
            assertEquals(ra.structureSeed, rb.structureSeed);
            assertEquals(ra.plannedBots, rb.plannedBots);
            assertEquals(ra.bots.size(), rb.bots.size());
            for (int i = 0; i < ra.bots.size(); i++) {
                BotRecord x = ra.bots.get(i);
                BotRecord y = rb.bots.get(i);
                assertEquals(x.name, y.name);
                assertEquals(x.seed, y.seed);
                assertEquals(x.index, y.index);
                assertEquals(x.state, y.state);
                assertEquals(x.profile, y.profile, "profile of " + x.name);
                assertEquals(x.x, y.x);
                assertEquals(x.z, y.z);
                assertEquals(x.yaw, y.yaw);
            }
            if (ra.status != StructureStatus.ABANDONED) {
                occupied++;
            }
        }
        assertTrue(occupied > 100 && occupied < 260, "the test needs both outcomes: " + occupied);
    }

    @Test
    void theVerdictOfAStructureDoesNotDependOnWhatWasProcessedBeforeIt() {
        StructureSnapshot target = Rig.village(42, -17);
        Rig alone = deterministicRig(555, "", 1);
        alone.engine.submit(target);
        alone.run(200);

        Rig crowded = deterministicRig(555, "", 2);
        for (StructureSnapshot s : manyStructures(200)) {
            crowded.engine.submit(s);
        }
        crowded.run(50);
        crowded.engine.submit(target);
        crowded.run(400);

        StructureRecord x = alone.record(target.key());
        StructureRecord y = crowded.record(target.key());
        assertEquals(x.structureSeed, y.structureSeed);
        assertEquals(x.status, y.status);
        assertEquals(x.roll, y.roll);
        assertEquals(Rig.names(x), Rig.names(y));
        for (int i = 0; i < x.bots.size(); i++) {
            assertEquals(x.bots.get(i).profile, y.bots.get(i).profile);
        }
    }

    @Test
    void anotherWorldSeedOrSaltGivesAnotherRollForTheSameStructures() {
        List<StructureSnapshot> all = manyStructures(60);
        Rig base = deterministicRig(1000, "a", 1);
        Rig otherSeed = deterministicRig(1001, "a", 1);
        Rig otherSalt = deterministicRig(1000, "b", 1);
        for (Rig r : List.of(base, otherSeed, otherSalt)) {
            all.forEach(r.engine::submit);
            r.cfg.processing.restoreSettleTicks = MANY / 2;
            r.run(5);
        }
        for (StructureSnapshot s : all) {
            long seed = base.record(s.key()).structureSeed;
            assertNotEquals(seed, otherSeed.record(s.key()).structureSeed, s.key().toString());
            assertNotEquals(seed, otherSalt.record(s.key()).structureSeed, s.key().toString());
        }
    }

    // ------------------------------------------------------------------ profiles

    @Test
    void profilesAreIdenticalWhateverOrderTheBotsAppearInAndAfterACrashInTheMiddle() {
        Rig plain = deterministicRig(9, "x", 1);
        plain.cfg.defaults = new InhabitantsConfig.Rule(1.0, 5, 5);
        StructureSnapshot s = Rig.village(1, 1);
        plain.engine.submit(s);
        plain.run(60);
        StructureRecord reference = plain.record(s.key());
        assertEquals(5, reference.spawnedCount());

        // bots appear in the reverse order
        Rig reversed = deterministicRig(9, "x", 2);
        reversed.cfg.defaults = new InhabitantsConfig.Rule(1.0, 5, 5);
        reversed.cfg.processing.appearTimeoutTicks = 100_000;
        reversed.bots.readyAfterPolls = MANY;
        reversed.engine.submit(s);
        reversed.run(1);
        for (int i = 0; i < 5; i++) {
            reversed.bots.readyAfterByName.put(reversed.record(s.key()).bots.get(i).name.toLowerCase(java.util.Locale.ROOT),
                    100 - i * 10);
        }
        reversed.run(300);
        StructureRecord reverse = reversed.record(s.key());
        assertEquals(5, reverse.spawnedCount());
        assertEquals(List.of(4, 3, 2, 1, 0), spawnOrder(reversed, reverse), "the test must actually interleave differently");

        // two bots appear, then the server crashes; the rest are adopted or retried after the restart
        Rig crashed = new Rig(new InMemoryStorage(), 3);
        crashed.cfg.deterministic.enabled = true;
        crashed.cfg.deterministic.salt = "x";
        crashed.cfg.defaults = new InhabitantsConfig.Rule(1.0, 5, 5);
        crashed.cfg.processing.appearTimeoutTicks = 100_000;
        crashed.world.seed = 9;
        crashed.bots.readyAfterPolls = MANY;
        crashed.engine.submit(s);
        crashed.run(1);
        List<String> names = Rig.names(crashed.record(s.key()));
        crashed.bots.readyAfterByName.put(names.get(3).toLowerCase(java.util.Locale.ROOT), 1);
        crashed.bots.readyAfterByName.put(names.get(1).toLowerCase(java.util.Locale.ROOT), 1);
        crashed.run(10);
        assertEquals(2, crashed.record(s.key()).spawnedCount());
        crashed.restart(false);
        crashed.bots.readyAfterPolls = 1;
        crashed.bots.readyAfterByName.clear();
        crashed.engine.submit(s);
        crashed.run(100);
        StructureRecord afterCrash = crashed.record(s.key());
        assertEquals(5, afterCrash.spawnedCount());

        for (int i = 0; i < 5; i++) {
            BotProfile want = expectedProfile(reference, i);
            assertEquals(want, withoutWaypoints(reference.bots.get(i).profile), "reference bot " + i);
            assertEquals(want, withoutWaypoints(reverse.bots.get(i).profile), "reverse-order bot " + i);
            assertEquals(want, withoutWaypoints(afterCrash.bots.get(i).profile), "post-crash bot " + i);
            assertEquals(reference.bots.get(i).name, afterCrash.bots.get(i).name);
        }
    }

    /** Indices of the bots in the order they were dressed. */
    private static List<Integer> spawnOrder(Rig rig, StructureRecord r) {
        List<Integer> order = new ArrayList<>();
        for (FakeBots.Applied a : rig.bots.applied) {
            for (BotRecord b : r.bots) {
                if (b.name.equals(a.name())) {
                    order.add(b.index);
                }
            }
        }
        return order;
    }

    @Test
    void eachDeterministicProfileUsesItsOwnFreshTransientDecksAndReplaysTheSiblingsBeforeIt() {
        Rig rig = deterministicRig(77, "", 1);
        rig.cfg.defaults = new InhabitantsConfig.Rule(1.0, 4, 4);
        rig.cfg.processing.restoreSettleTicks = 3;
        StructureSnapshot s = Rig.village(0, 0);
        rig.engine.submit(s);
        rig.run(2);
        StructureRecord planned = rig.record(s.key());
        rig.bots.refuse.add(planned.bots.get(1).name.toLowerCase(java.util.Locale.ROOT)); // bot 1 will never spawn
        rig.run(300);
        StructureRecord r = rig.record(s.key());
        assertEquals(BotState.FAILED, r.bots.get(1).state);
        assertEquals(StructureStatus.POPULATED, r.status);

        // group the factory calls by the deck store instance they used: one group per dressed bot
        Map<Object, List<Long>> byDeck = new IdentityHashMap<>();
        List<Object> order = new ArrayList<>();
        for (FakeProfiles.Call c : rig.profiles.calls) {
            assertFalse(c.decks() instanceof PersistentDeckStore, "deterministic mode must not touch the world-wide decks");
            assertInstanceOf(TransientDeckStore.class, c.decks());
            if (!byDeck.containsKey(c.decks())) {
                order.add(c.decks());
            }
            byDeck.computeIfAbsent(c.decks(), k -> new ArrayList<>()).add(c.seed());
        }
        assertEquals(3, order.size(), "bots 0, 2 and 3 were dressed, each with a fresh deck store");
        List<List<Long>> groups = new ArrayList<>();
        for (Object deck : order) {
            groups.add(byDeck.get(deck));
        }
        groups.sort((g1, g2) -> Integer.compare(g1.size(), g2.size()));
        long s0 = r.bots.get(0).seed;
        long s1 = r.bots.get(1).seed;
        long s2 = r.bots.get(2).seed;
        long s3 = r.bots.get(3).seed;
        assertEquals(List.of(s0), groups.get(0));
        assertEquals(List.of(s0, s1, s2), groups.get(1), "bot 2 replays bots 0 and 1 (even the one that failed) first");
        assertEquals(List.of(s0, s1, s2, s3), groups.get(2));
    }

    @Test
    void normalModeIsNotDeterministicEvenForTheSameWorld() {
        Set<Long> seeds = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (int run = 0; run < 3; run++) {
            Rig rig = new Rig();
            rig.world.seed = 4242; // same world every time
            PopulationEngine engine = new PopulationEngine(() -> rig.cfg, rig.store, rig.bots, rig.world, rig.clock,
                    rig.profiles, rig.planner);
            engine.submit(Rig.village(0, 0));
            rig.clock.tick++;
            engine.tick();
            StructureRecord r = rig.record(Rig.village(0, 0).key());
            assertEquals("RANDOM", r.source);
            seeds.add(r.structureSeed);
            names.addAll(Rig.names(r));
        }
        assertEquals(3, seeds.size());
        assertEquals(9, names.size());
    }

    @Test
    void normalModeSharesTheWorldWideDecksAcrossStructures() {
        Rig rig = new Rig();
        for (int i = 0; i < 5; i++) {
            rig.engine.submit(Rig.village(i * 4, 0));
        }
        rig.run(100);
        assertEquals(15, rig.profiles.calls.size());
        Set<Object> decks = Collections.newSetFromMap(new IdentityHashMap<>());
        rig.profiles.calls.forEach(c -> decks.add(c.decks()));
        assertEquals(1, decks.size());
        assertSame(rig.store.decks(), decks.iterator().next());
    }

    @Test
    void positionsAreReproducibleForTheSameStructureInDeterministicMode() {
        StructureSnapshot s = Rig.village(5, 5);
        Rig a = deterministicRig(8, "", 1);
        Rig b = deterministicRig(8, "", 2);
        for (Rig r : List.of(a, b)) {
            r.cfg.defaults = new InhabitantsConfig.Rule(1.0, 3, 3);
            r.engine.submit(s);
            r.run(60);
        }
        assertEquals(a.planner.findCalls.get(0).firstDraw(), b.planner.findCalls.get(0).firstDraw());
        StructureRecord x = a.record(s.key());
        StructureRecord y = b.record(s.key());
        for (int i = 0; i < 3; i++) {
            assertEquals(x.bots.get(i).x, y.bots.get(i).x);
            assertEquals(x.bots.get(i).z, y.bots.get(i).z);
        }
    }

    @Test
    void theDeterministicFlagOfTheConfigIsReadPerRoll() {
        Rig rig = new Rig(new InMemoryStorage(false), 5);
        rig.cfg.processing.restoreSettleTicks = MANY / 2;
        StructureKey k1 = Rig.village(1, 1).key();
        StructureKey k2 = Rig.village(2, 2).key();
        rig.engine.submit(Rig.village(1, 1));
        rig.run(1);
        rig.cfg.deterministic.enabled = true;
        rig.engine.submit(Rig.village(2, 2));
        rig.run(1);
        assertEquals("RANDOM", rig.record(k1).source);
        assertEquals("DETERMINISTIC", rig.record(k2).source);
    }
}
