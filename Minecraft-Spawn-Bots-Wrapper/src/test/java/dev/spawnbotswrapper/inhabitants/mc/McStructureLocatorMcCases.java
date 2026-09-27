package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.structure.IntBox;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.gen.structure.Structure;
import net.minecraft.world.gen.structure.StructureKeys;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** {@link McStructureLocator} over stand-in chunks that are or are not loaded, run inside {@link McSandbox}. */
public final class McStructureLocatorMcCases {
    private McStructureLocatorMcCases() {
    }

    private static final class Rig {
        final WorldFakes.Source source = new WorldFakes.Source();
        final WorldFakes.ChunkManager chunks = WorldFakes.chunkManager();
        final MinecraftServer server = WorldFakes.server();
        final ServerWorld world = WorldFakes.world(server, chunks);
        final McStructureLocator locator = new McStructureLocator(source);
        final Structure igloo = McBootstrap.registries().getOptionalEntry(StructureKeys.IGLOO).orElseThrow().value();
        final Structure village = McBootstrap.registries().getOptionalEntry(StructureKeys.VILLAGE_PLAINS).orElseThrow().value();
        final Structure pyramid = McBootstrap.registries().getOptionalEntry(StructureKeys.DESERT_PYRAMID).orElseThrow().value();

        /** Loads a chunk holding one start with this box; returns the start so others can reference it. */
        StructureStart loadStart(Structure structure, String id, int cx, int cz, IntBox box, Map<Structure, LongSet> references) {
            StructureSnapshot snapshot = WorldFakes.snapshot(id, cx, cz, box, false);
            StructureStart start = source.add(snapshot);
            chunks.load(WorldFakes.chunk(cx, cz, Map.of(structure, start), references));
            return start;
        }

        void loadEmpty(int cx, int cz, Map<Structure, LongSet> references) {
            chunks.load(WorldFakes.chunk(cx, cz, Map.of(), references));
        }
    }

    private static List<String> ids(List<StructureSnapshot> found) {
        return found.stream().map(s -> s.key().structureId()).toList();
    }

    public static void atFindsTheStructureAroundAPositionAndNothingElse() {
        Rig rig = new Rig();
        rig.loadStart(rig.igloo, "minecraft:igloo", 0, 0, new IntBox(0, 60, 0, 20, 70, 20), Map.of());
        assertEquals(List.of("minecraft:igloo"), ids(rig.locator.at(rig.world, new BlockPos(10, 64, 10))));
        assertEquals(List.of(), ids(rig.locator.at(rig.world, new BlockPos(10, 90, 10))), "above the box");
        assertEquals(List.of(), ids(rig.locator.at(rig.world, new BlockPos(30, 64, 10))), "beside the box, but in another (unloaded) chunk");
    }

    public static void atUsesTheBoxNotTheWholeChunk() {
        Rig rig = new Rig();
        rig.loadStart(rig.igloo, "minecraft:igloo", 0, 0, new IntBox(0, 60, 0, 5, 70, 5), Map.of());
        assertEquals(List.of(), ids(rig.locator.at(rig.world, new BlockPos(12, 64, 12))), "same chunk, outside the box");
    }

    public static void atFindsSeveralOverlappingStructuresSmallestFirst() {
        Rig rig = new Rig();
        StructureStart big = rig.loadStart(rig.village, "minecraft:village_plains", 1, 0, new IntBox(-40, 50, -40, 60, 90, 60), Map.of());
        assertNotNull(big);
        // Chunk (0,0) holds a small outpost of its own and has a reference to the big start in chunk (1,0).
        StructureStart small = rig.source.add(WorldFakes.snapshot("minecraft:igloo", 0, 0, new IntBox(2, 60, 2, 12, 70, 12), false));
        rig.chunks.load(WorldFakes.chunk(0, 0, Map.of(rig.igloo, small), Map.of(rig.village, WorldFakes.chunks(1, 0))));
        assertEquals(List.of("minecraft:igloo", "minecraft:village_plains"), ids(rig.locator.at(rig.world, new BlockPos(5, 64, 5))));
        assertEquals(List.of("minecraft:village_plains"), ids(rig.locator.at(rig.world, new BlockPos(14, 64, 14))),
                "inside the village box but outside the igloo");
    }

    public static void aStartReachableByReferenceAndByItsOwnChunkIsReportedOnce() {
        Rig rig = new Rig();
        StructureStart start = rig.source.add(WorldFakes.snapshot("minecraft:igloo", 0, 0, new IntBox(0, 60, 0, 15, 70, 15), false));
        rig.chunks.load(WorldFakes.chunk(0, 0, Map.of(rig.igloo, start), Map.of(rig.igloo, WorldFakes.chunks(0, 0))));
        assertEquals(List.of("minecraft:igloo"), ids(rig.locator.at(rig.world, new BlockPos(3, 64, 3))));
    }

    public static void atNeverLoadsAnythingAndSkipsWhatIsNotLoaded() {
        Rig rig = new Rig();
        // Chunk (0,0) references a start in chunk (9,9), which is not loaded: it is skipped, not loaded.
        rig.loadEmpty(0, 0, Map.of(rig.village, WorldFakes.chunks(9, 9)));
        assertEquals(List.of(), rig.locator.at(rig.world, new BlockPos(3, 64, 3)));
        // The position's own chunk is not loaded at all.
        assertEquals(List.of(), rig.locator.at(rig.world, new BlockPos(500, 64, 500)));
        assertFalse(rig.chunks.loaded.containsKey(net.minecraft.util.math.ChunkPos.toLong(9, 9)), "the fake would have loaded it if asked to");
    }

    public static void nearListsNearestStructuresFirstWithinTheRadius() {
        Rig rig = new Rig();
        rig.loadStart(rig.igloo, "minecraft:igloo", 0, 0, new IntBox(0, 60, 0, 10, 70, 10), Map.of());
        rig.loadStart(rig.village, "minecraft:village_plains", 2, 0, new IntBox(32, 60, 0, 60, 70, 20), Map.of());
        rig.loadStart(rig.pyramid, "minecraft:desert_pyramid", -3, 1, new IntBox(-48, 60, 16, -30, 70, 30), Map.of());
        rig.loadStart(rig.igloo, "minecraft:igloo", 8, 8, new IntBox(128, 60, 128, 140, 70, 140), Map.of());

        assertEquals(List.of("minecraft:igloo", "minecraft:village_plains", "minecraft:desert_pyramid"),
                ids(rig.locator.near(rig.world, new BlockPos(4, 64, 4), 3)));
        assertEquals(List.of("minecraft:igloo"), ids(rig.locator.near(rig.world, new BlockPos(4, 64, 4), 0)));
        assertEquals(4, rig.locator.near(rig.world, new BlockPos(4, 64, 4), 8).size());
        assertEquals(List.of("minecraft:igloo"), ids(rig.locator.near(rig.world, new BlockPos(4, 64, 4), -5)), "a negative radius means just this chunk");
    }

    public static void nearBreaksTiesByHorizontalDistanceToTheBox() {
        Rig rig = new Rig();
        // Both start chunks are one chunk away from the centre chunk (0,0); the second box is closer to (4,4).
        rig.loadStart(rig.igloo, "minecraft:igloo", 1, 0, new IntBox(31, 60, 0, 40, 70, 10), Map.of());
        rig.loadStart(rig.pyramid, "minecraft:desert_pyramid", 0, 1, new IntBox(0, 60, 17, 10, 70, 30), Map.of());
        assertEquals(List.of("minecraft:desert_pyramid", "minecraft:igloo"), ids(rig.locator.near(rig.world, new BlockPos(4, 64, 4), 1)));
    }

    public static void nearCapsTheRadiusSoAMistypedCommandCannotWalkMillionsOfChunks() {
        Rig rig = new Rig();
        rig.locator.near(rig.world, new BlockPos(0, 64, 0), Integer.MAX_VALUE);
        int side = 2 * McStructureLocator.MAX_RADIUS_CHUNKS + 1;
        assertEquals(side * side, rig.chunks.lookups);
    }

    public static void nearReportsEachStructureOnceAndIgnoresUnusableStarts() {
        Rig rig = new Rig();
        StructureStart usable = rig.source.add(WorldFakes.snapshot("minecraft:igloo", 0, 0, new IntBox(0, 60, 0, 10, 70, 10), false));
        StructureStart unusable = McObjects.opaque(StructureStart.class);
        Map<Structure, StructureStart> starts = new HashMap<>();
        starts.put(rig.igloo, usable);
        starts.put(rig.pyramid, unusable);
        rig.chunks.load(WorldFakes.chunk(0, 0, starts, Map.of()));
        assertEquals(List.of("minecraft:igloo"), ids(rig.locator.near(rig.world, new BlockPos(1, 64, 1), 2)));
    }

    public static void locatorSnapshotsAreNeverMarkedNewlyGenerated() {
        Rig rig = new Rig();
        rig.loadStart(rig.igloo, "minecraft:igloo", 0, 0, new IntBox(0, 60, 0, 10, 70, 10), Map.of());
        assertFalse(rig.locator.near(rig.world, new BlockPos(1, 64, 1), 1).get(0).newlyGenerated());
        assertFalse(rig.locator.at(rig.world, new BlockPos(1, 64, 1)).get(0).newlyGenerated());
    }
}
