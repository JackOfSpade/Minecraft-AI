package dev.spawnbotswrapper.inhabitants.engine;

import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.store.BotRecord;
import dev.spawnbotswrapper.inhabitants.store.BotState;
import dev.spawnbotswrapper.inhabitants.store.StructureRecord;
import dev.spawnbotswrapper.inhabitants.store.StructureStatus;
import dev.spawnbotswrapper.inhabitants.structure.IntBox;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;
import dev.spawnbotswrapper.inhabitants.util.SplitMix64;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * One engine wired to scriptable fakes. The "game" (bots, world, clock, decks) outlives the engine and the
 * storage, so {@link #restart} can replace those two the way a server restart would.
 * <p>
 * The default configuration is fast (no delays, no settle time) and makes every structure occupied with exactly
 * three bots, so a test only spells out what it is about.
 */
final class Rig {
    static final String OVERWORLD = "minecraft:overworld";
    static final String VILLAGE = "minecraft:village_plains";

    final InhabitantsConfig cfg = fastConfig();
    final FakeClock clock = new FakeClock();
    final FakeBots bots = new FakeBots(clock);
    final FakeWorld world = new FakeWorld();
    final FakeProfiles profiles = new FakeProfiles();
    final FakePlanner planner = new FakePlanner();
    private final SplitMix64 entropy;

    InMemoryStorage store;
    PopulationEngine engine;

    Rig() {
        this(new InMemoryStorage(), 42);
    }

    Rig(InMemoryStorage store, long entropySeed) {
        this.entropy = new SplitMix64(entropySeed);
        this.store = store;
        this.engine = newEngine(store);
    }

    static InhabitantsConfig fastConfig() {
        InhabitantsConfig c = new InhabitantsConfig();
        c.structures.clear();
        c.tags.clear();
        c.exclude = new ArrayList<>();
        c.defaults = new InhabitantsConfig.Rule(1.0, 3, 3);
        InhabitantsConfig.Processing p = c.processing;
        p.maxStructuresPerTick = 64;
        p.maxBotsPerTick = 4;
        p.spawnIntervalTicks = 0;
        p.initialDelayTicks = 0;
        p.retryIntervalTicks = 10;
        p.maxAttemptsPerStructure = 3;
        p.maxLiveBots = 0;
        p.appearTimeoutTicks = 50;
        p.saveIntervalTicks = 100;
        p.restoreSettleTicks = 0;
        p.goneConfirmTicks = 200;
        return c;
    }

    PopulationEngine newEngine(InMemoryStorage storage) {
        return new PopulationEngine(() -> cfg, storage, bots, world, clock, profiles, planner, entropy::nextLong);
    }

    /** Replaces engine and storage as a server restart would; {@code clean} flushes first, otherwise it is a crash. */
    void restart(boolean clean) {
        store = clean ? store.cleanRestartCopy() : store.crashCopy();
        engine = newEngine(store);
    }

    // ------------------------------------------------------------------ driving time

    /** Advances the clock one tick at a time, ticking the engine after each. */
    void run(int ticks) {
        for (int i = 0; i < ticks; i++) {
            clock.tick++;
            engine.tick();
        }
    }

    /** Ticks until {@code condition} holds; fails the test if it never does. */
    int runUntil(BooleanSupplier condition, int maxTicks) {
        for (int i = 0; i < maxTicks; i++) {
            if (condition.getAsBoolean()) {
                return i;
            }
            run(1);
        }
        if (condition.getAsBoolean()) {
            return maxTicks;
        }
        return fail("condition not reached within " + maxTicks + " ticks");
    }

    // ------------------------------------------------------------------ building input

    static StructureSnapshot snapshot(String dimension, String id, int chunkX, int chunkZ, Set<String> tags,
                                      int pieceCount, boolean newlyGenerated) {
        int x = chunkX * 16;
        int z = chunkZ * 16;
        IntBox bounds = new IntBox(x, 60, z, x + 47, 80, z + 47);
        List<IntBox> pieces = new ArrayList<>();
        for (int i = 0; i < pieceCount; i++) {
            int px = x + (i % 8) * 5;
            int pz = z + (i / 8) * 5;
            pieces.add(new IntBox(px, 64, pz, px + 4, 70, pz + 4));
        }
        return new StructureSnapshot(new StructureKey(dimension, id, chunkX, chunkZ), tags, bounds, pieces, newlyGenerated);
    }

    static StructureSnapshot village(int chunkX, int chunkZ) {
        return snapshot(OVERWORLD, VILLAGE, chunkX, chunkZ, Set.of("minecraft:village"), 40, true);
    }

    static StructureSnapshot structure(String id, int chunkX, int chunkZ) {
        return snapshot(OVERWORLD, id, chunkX, chunkZ, Set.of(), 3, true);
    }

    // ------------------------------------------------------------------ reading state

    StructureRecord record(StructureKey key) {
        return store.find(key).orElseThrow(() -> new AssertionError("no record for " + key));
    }

    /** True when the structure has a record with this status (false, not an error, before it is rolled). */
    boolean hasStatus(StructureKey key, StructureStatus status) {
        return store.find(key).map(r -> r.status == status).orElse(false);
    }

    /** Submits structures in bursts small enough for the roll queue and ticks until each burst is rolled. */
    void feed(int count, java.util.function.IntFunction<StructureSnapshot> maker) {
        int burst = 1000;
        for (int from = 0; from < count; from += burst) {
            for (int i = from; i < Math.min(count, from + burst); i++) {
                engine.submit(maker.apply(i));
            }
            run(burst / 64 + 2);
        }
    }

    static long count(StructureRecord r, BotState state) {
        return r.bots.stream().filter(b -> b.state == state).count();
    }

    static List<String> names(StructureRecord r) {
        List<String> out = new ArrayList<>();
        for (BotRecord b : r.bots) {
            out.add(b.name);
        }
        return out;
    }
}
