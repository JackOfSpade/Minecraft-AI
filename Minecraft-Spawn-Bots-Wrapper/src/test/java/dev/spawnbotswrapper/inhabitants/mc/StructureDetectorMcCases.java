package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.structure.IntBox;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureStart;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.gen.structure.Structure;
import net.minecraft.world.gen.structure.StructureKeys;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** {@link StructureDetector} queueing, dedupe and failure handling with stand-in worlds, run inside {@link McSandbox}. */
public final class StructureDetectorMcCases {
    private StructureDetectorMcCases() {
    }

    private static final class Rig {
        final WorldFakes.Source source = new WorldFakes.Source();
        final List<String> failures = new ArrayList<>();
        final StepGuard guard = new StepGuard((message, cause) -> failures.add(message), 100);
        final StructureDetector detector = new StructureDetector(source, guard);
        final MinecraftServer server = WorldFakes.server();
        final ServerWorld world = WorldFakes.world(server, WorldFakes.chunkManager());
        final Structure structure = McBootstrap.registries().getOptionalEntry(StructureKeys.IGLOO).orElseThrow().value();
        final Structure other = McBootstrap.registries().getOptionalEntry(StructureKeys.DESERT_PYRAMID).orElseThrow().value();
        final List<StructureSnapshot> delivered = new ArrayList<>();

        StructureSnapshot snapshot(String id, int cx, int cz) {
            return WorldFakes.snapshot(id, cx, cz, new IntBox(cx * 16, 60, cz * 16, cx * 16 + 20, 80, cz * 16 + 20), false);
        }

        /** A chunk that carries one start for {@code snapshot}. */
        WorldChunk chunkWith(StructureSnapshot snapshot) {
            StructureStart start = source.add(snapshot);
            return WorldFakes.chunk(snapshot.key().chunkX(), snapshot.key().chunkZ(), Map.of(structure, start), Map.of());
        }

        int drain(int max) {
            return detector.drain(server, max, delivered::add);
        }
    }

    public static void aChunkWithoutStructureStartsIsNotEvenQueued() {
        Rig rig = new Rig();
        rig.detector.onChunkLoad(rig.world, WorldFakes.chunk(0, 0, Map.of(), Map.of()));
        rig.detector.onChunkGenerate(rig.world, WorldFakes.chunk(1, 1, Map.of(), Map.of()));
        assertEquals(0, rig.detector.pending());
        assertEquals(0, rig.drain(10));
    }

    public static void aLoadedChunkYieldsOneSnapshotPerStartItHolds() {
        Rig rig = new Rig();
        StructureSnapshot village = rig.snapshot("minecraft:village_plains", 3, 4);
        StructureSnapshot mineshaft = rig.snapshot("minecraft:mineshaft", 3, 4);
        StructureStart a = rig.source.add(village);
        StructureStart b = rig.source.add(mineshaft);
        WorldChunk chunk = WorldFakes.chunk(3, 4, Map.of(rig.structure, a, rig.other, b), Map.of());
        rig.detector.onChunkLoad(rig.world, chunk);
        assertEquals(1, rig.detector.pending());
        assertEquals(2, rig.drain(10));
        assertEquals(2, rig.delivered.size());
        assertEquals(0, rig.detector.pending());
    }

    public static void generateFlagsTheSnapshotAsNewlyGeneratedAndLoadAloneDoesNot() {
        Rig rig = new Rig();
        WorldChunk fresh = rig.chunkWith(rig.snapshot("minecraft:igloo", 0, 0));
        WorldChunk old = rig.chunkWith(rig.snapshot("minecraft:igloo", 9, 9));
        rig.detector.onChunkLoad(rig.world, fresh);
        rig.detector.onChunkGenerate(rig.world, fresh);
        rig.detector.onChunkLoad(rig.world, old);
        rig.drain(10);
        assertEquals(2, rig.delivered.size());
        assertTrue(rig.delivered.get(0).newlyGenerated(), "LOAD then GENERATE for the same chunk: newly generated");
        assertFalse(rig.delivered.get(1).newlyGenerated(), "LOAD only: it came from disk");
    }

    public static void theSameStructureIsNotDeliveredAgainWhenItsStartChunkReloads() {
        Rig rig = new Rig();
        StructureSnapshot snapshot = rig.snapshot("minecraft:igloo", 5, 5);
        StructureStart start = rig.source.add(snapshot);
        Map<Structure, StructureStart> starts = Map.of(rig.structure, start);
        rig.detector.onChunkLoad(rig.world, WorldFakes.chunk(5, 5, starts, Map.of()));
        assertEquals(1, rig.drain(10));
        int buildsAfterFirst = rig.source.builds;

        // The chunk unloads and loads again: a NEW chunk object with the same start.
        rig.detector.onChunkLoad(rig.world, WorldFakes.chunk(5, 5, starts, Map.of()));
        assertEquals(0, rig.drain(10));
        assertEquals(buildsAfterFirst, rig.source.builds, "the piece list is not rebuilt for a structure just handled");
        assertEquals(1, rig.delivered.size());
    }

    public static void forgettingRecentStructuresOffersThemAgain() {
        Rig rig = new Rig();
        StructureStart start = rig.source.add(rig.snapshot("minecraft:igloo", 1, 1));
        Map<Structure, StructureStart> starts = Map.of(rig.structure, start);
        rig.detector.onChunkLoad(rig.world, WorldFakes.chunk(1, 1, starts, Map.of()));
        rig.drain(10);
        rig.detector.forgetRecent();
        rig.detector.onChunkLoad(rig.world, WorldFakes.chunk(1, 1, starts, Map.of()));
        assertEquals(1, rig.drain(10));
        assertEquals(2, rig.delivered.size(), "e.g. after a config reload changed which structures are eligible");
    }

    public static void theDrainBudgetLimitsChunksPerCallAndTheRestWaitsForTheNextOne() {
        Rig rig = new Rig();
        for (int i = 0; i < 5; i++) {
            rig.detector.onChunkLoad(rig.world, rig.chunkWith(rig.snapshot("minecraft:igloo", i, 0)));
        }
        assertEquals(2, rig.drain(2));
        assertEquals(3, rig.detector.pending());
        assertEquals(3, rig.drain(100));
        assertEquals(5, rig.delivered.size());
        assertEquals(List.of(0, 1, 2, 3, 4), rig.delivered.stream().map(s -> s.key().chunkX()).toList(), "oldest first");
    }

    public static void chunksQueuedByAnotherServerAreDiscarded() {
        Rig rig = new Rig();
        MinecraftServer previous = WorldFakes.server();
        ServerWorld oldWorld = WorldFakes.world(previous, WorldFakes.chunkManager());
        StructureStart start = rig.source.add(rig.snapshot("minecraft:igloo", 7, 7));
        rig.detector.onChunkLoad(oldWorld, WorldFakes.chunk(7, 7, Map.of(rig.structure, start), Map.of()));
        assertEquals(0, rig.drain(10), "a world from a previous server session must never reach the new engine");
        assertEquals(0, rig.detector.pending());
        assertEquals(0, rig.source.builds);
    }

    public static void anUnusableOrUnregisteredStartIsSkippedAndNotRemembered() {
        Rig rig = new Rig();
        StructureStart unknown = McObjects.opaque(StructureStart.class); // the source has no key for it
        rig.detector.onChunkLoad(rig.world, WorldFakes.chunk(2, 2, Map.of(rig.structure, unknown), Map.of()));
        assertEquals(0, rig.drain(10));
        assertEquals(0, rig.source.builds);
        assertTrue(rig.failures.isEmpty());
    }

    public static void aFailureOnOneStartDoesNotStopTheOthersAndIsRetriedNextTime() {
        Rig rig = new Rig();
        StructureStart bad = rig.source.add(rig.snapshot("minecraft:igloo", 4, 4));
        StructureStart good = rig.source.add(rig.snapshot("minecraft:desert_pyramid", 4, 4));
        rig.source.failing.add(bad);
        Map<Structure, StructureStart> starts = Map.of(rig.structure, bad, rig.other, good);
        rig.detector.onChunkLoad(rig.world, WorldFakes.chunk(4, 4, starts, Map.of()));

        assertEquals(1, rig.drain(10));
        assertEquals("minecraft:desert_pyramid", rig.delivered.get(0).key().structureId());
        assertEquals(1, rig.failures.size(), "reported through the guard, not thrown");
        assertEquals(1, rig.guard.failures("structure detection"));

        rig.source.failing.clear();
        rig.detector.onChunkLoad(rig.world, WorldFakes.chunk(4, 4, starts, Map.of()));
        assertEquals(1, rig.drain(10), "the failed one was not remembered as handled, so it is retried; the good one is not repeated");
        assertEquals("minecraft:igloo", rig.delivered.get(1).key().structureId());
    }

    public static void aConsumerThatThrowsDoesNotLoseTheStructureOrTheRest() {
        Rig rig = new Rig();
        rig.detector.onChunkLoad(rig.world, rig.chunkWith(rig.snapshot("minecraft:igloo", 0, 0)));
        rig.detector.onChunkLoad(rig.world, rig.chunkWith(rig.snapshot("minecraft:igloo", 1, 0)));
        boolean[] first = {true};
        int delivered = rig.detector.drain(rig.server, 10, snapshot -> {
            if (first[0]) {
                first[0] = false;
                throw new IllegalStateException("engine rejected it");
            }
            rig.delivered.add(snapshot);
        });
        assertEquals(1, delivered);
        assertEquals(1, rig.delivered.size());
        assertEquals(1, rig.guard.failures("structure detection"));
    }

    public static void discardingPendingChunksDropsThemWithoutLookingAtThem() {
        Rig rig = new Rig();
        rig.detector.onChunkLoad(rig.world, rig.chunkWith(rig.snapshot("minecraft:igloo", 0, 0)));
        rig.detector.discardPending();
        assertEquals(0, rig.detector.pending());
        assertEquals(0, rig.drain(10));
        assertEquals(0, rig.source.builds);
    }

    public static void resetForgetsQueueRecentStructuresAndCachedRegistryData() {
        Rig rig = new Rig();
        StructureStart start = rig.source.add(rig.snapshot("minecraft:igloo", 0, 0));
        Map<Structure, StructureStart> starts = Map.of(rig.structure, start);
        rig.detector.onChunkLoad(rig.world, WorldFakes.chunk(0, 0, starts, Map.of()));
        rig.drain(10);
        rig.detector.onChunkLoad(rig.world, WorldFakes.chunk(9, 9, starts, Map.of()));
        rig.detector.reset();
        assertEquals(0, rig.detector.pending());
        assertEquals(1, rig.source.invalidations, "cached registry data belongs to the stopped server");
        rig.detector.onChunkLoad(rig.world, WorldFakes.chunk(0, 0, starts, Map.of()));
        assertEquals(1, rig.drain(10), "after a reset the structure is new again");
    }

    public static void theQueueIsBoundedAndCountsWhatItDropped() {
        Rig rig = new Rig();
        for (int i = 0; i < StructureDetector.QUEUE_CAPACITY + 10; i++) {
            // A distinct chunk object each time, as the game would deliver them.
            rig.detector.onChunkLoad(rig.world, WorldFakes.chunk(i, 0, Map.of(rig.structure, McObjects.opaque(StructureStart.class)), Map.of()));
        }
        assertEquals(StructureDetector.QUEUE_CAPACITY, rig.detector.pending());
        assertEquals(10, rig.detector.dropped());
    }

    public static void everySnapshotKeepsItsOwnKey() {
        Rig rig = new Rig();
        StructureSnapshot a = rig.snapshot("minecraft:igloo", 1, 2);
        rig.detector.onChunkLoad(rig.world, rig.chunkWith(a));
        rig.drain(10);
        StructureKey key = rig.delivered.get(0).key();
        assertEquals(new StructureKey("minecraft:overworld", "minecraft:igloo", 1, 2), key);
    }
}
