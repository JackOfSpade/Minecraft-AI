package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.structure.IntBox;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.structure.StructureContext;
import net.minecraft.structure.StructurePiece;
import net.minecraft.structure.StructurePieceType;
import net.minecraft.structure.StructurePiecesList;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.structure.Structure;
import net.minecraft.world.gen.structure.StructureKeys;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Snapshot building against real vanilla structures and a stand-in piece, run inside {@link McSandbox}. */
public final class StructureSnapshotBuilderMcCases {
    private StructureSnapshotBuilderMcCases() {
    }

    private static StructurePiece piece(int x1, int y1, int z1, int x2, int y2, int z2) {
        return new StructurePiece(StructurePieceType.JIGSAW, 0, new BlockBox(x1, y1, z1, x2, y2, z2)) {
            @Override
            protected void writeNbt(StructureContext context, NbtCompound nbt) {
            }

            @Override
            public void generate(StructureWorldAccess world, StructureAccessor accessor, ChunkGenerator generator,
                                 Random random, BlockBox chunkBox, ChunkPos chunkPos, BlockPos pivot) {
            }
        };
    }

    private static RegistryEntry.Reference<Structure> vanilla(RegistryKey<Structure> key) {
        return McBootstrap.registries().getOptionalEntry(key)
                .orElseThrow(() -> new AssertionError("vanilla structure " + key.getValue() + " is missing"));
    }

    private static StructureStart start(Structure structure, int chunkX, int chunkZ, StructurePiece... pieces) {
        return new StructureStart(structure, new ChunkPos(chunkX, chunkZ), 0, new StructurePiecesList(List.of(pieces)));
    }

    public static void everyPieceAndTheStartBoxAreCarriedOver() {
        RegistryEntry.Reference<Structure> pyramid = vanilla(StructureKeys.DESERT_PYRAMID);
        StructureStart start = start(pyramid.value(), -3, 7,
                piece(10, 60, 20, 20, 70, 30), piece(-5, 62, 22, 4, 66, 40));
        StructureSnapshotBuilder.Info info = StructureSnapshotBuilder.describe(pyramid);
        StructureSnapshot snapshot = StructureSnapshotBuilder.snapshot("minecraft:overworld", info, start, true);

        assertEquals(new StructureKey("minecraft:overworld", "minecraft:desert_pyramid", -3, 7), snapshot.key());
        assertEquals(List.of(new IntBox(10, 60, 20, 20, 70, 30), new IntBox(-5, 62, 22, 4, 66, 40)), snapshot.pieces());
        assertEquals(new IntBox(-5, 60, 20, 20, 70, 40), snapshot.bounds(),
                "a structure without terrain adaptation has exactly the union of its pieces as its box");
        assertTrue(snapshot.newlyGenerated());
    }

    public static void aTerrainAdaptingStructureHasTheExpandedStartBoxAndEveryPieceInsideIt() {
        RegistryEntry.Reference<Structure> village = vanilla(StructureKeys.VILLAGE_PLAINS);
        StructureStart start = start(village.value(), 0, 0, piece(0, 64, 0, 10, 70, 10), piece(20, 63, 5, 30, 69, 15));
        StructureSnapshot snapshot = StructureSnapshotBuilder.snapshot("minecraft:overworld",
                StructureSnapshotBuilder.describe(village), start, false);
        assertFalse(snapshot.newlyGenerated());
        assertEquals(new IntBox(-12, 51, -12, 42, 82, 27), snapshot.bounds(), "village boxes are grown by 12 in every direction");
        for (IntBox piece : snapshot.pieces()) {
            assertTrue(snapshot.bounds().contains(piece.minX(), piece.minY(), piece.minZ()));
            assertTrue(snapshot.bounds().contains(piece.maxX(), piece.maxY(), piece.maxZ()));
        }
    }

    public static void theRegistryIdComesFromTheRuntimeRegistryNotFromAList() {
        int[] seen = {0};
        McBootstrap.registries().<Structure>getOrThrow(RegistryKeys.STRUCTURE).streamEntries().forEach(entry -> {
            StructureSnapshotBuilder.Info info = StructureSnapshotBuilder.describe(entry);
            assertNotNull(info);
            assertEquals(entry.registryKey().getValue().toString(), info.id());
            assertTrue(info.id().startsWith("minecraft:"), info.id());
            seen[0]++;
        });
        assertTrue(seen[0] >= 30, "expected the vanilla structures, saw " + seen[0]);
    }

    public static void tagsThatAreNotBoundYieldAnEmptySetInsteadOfAFailure() {
        StructureSnapshotBuilder.Info info = StructureSnapshotBuilder.describe(vanilla(StructureKeys.VILLAGE_PLAINS));
        assertEquals("minecraft:village_plains", info.id());
        assertTrue(info.tagIds().isEmpty());
    }

    public static void anUnregisteredStructureHasNoInfo() {
        Structure structure = vanilla(StructureKeys.IGLOO).value();
        assertNull(StructureSnapshotBuilder.describe(RegistryEntry.of(structure)), "a direct entry has no id");
    }

    public static void tagIdsAreWrittenWithoutAHash() {
        TagKey<Structure> village = TagKey.of(RegistryKeys.STRUCTURE, Identifier.of("minecraft", "village"));
        assertEquals("minecraft:village", StructureSnapshotBuilder.tagId(village));
        TagKey<Structure> modded = TagKey.of(RegistryKeys.STRUCTURE, Identifier.of("somemod", "ruins/big"));
        assertEquals("somemod:ruins/big", StructureSnapshotBuilder.tagId(modded));
    }

    public static void tagsPassThroughToTheSnapshot() {
        RegistryEntry.Reference<Structure> igloo = vanilla(StructureKeys.IGLOO);
        StructureSnapshotBuilder.Info info = new StructureSnapshotBuilder.Info("minecraft:igloo", Set.of("minecraft:village", "somemod:cold"));
        StructureSnapshot snapshot = StructureSnapshotBuilder.snapshot("minecraft:the_nether", info,
                start(igloo.value(), 1, 2, piece(0, 0, 0, 3, 3, 3)), false);
        assertEquals(Set.of("minecraft:village", "somemod:cold"), snapshot.tagIds());
        assertEquals("minecraft:the_nether", snapshot.key().dimension());
    }

    public static void blockBoxesConvertInclusively() {
        assertEquals(new IntBox(1, 2, 3, 4, 5, 6), StructureSnapshotBuilder.box(new BlockBox(1, 2, 3, 4, 5, 6)));
        assertEquals(new IntBox(7, 8, 9, 7, 8, 9), StructureSnapshotBuilder.box(new BlockBox(new BlockPos(7, 8, 9))));
        IntBox negative = StructureSnapshotBuilder.box(new BlockBox(-20, -64, -30, -11, -60, -21));
        assertEquals(10, negative.sizeX());
        assertEquals(5, negative.sizeY());
        assertTrue(negative.contains(-20, -64, -30));
        assertTrue(negative.contains(-11, -60, -21));
    }

    public static void onlyRealStartsAreUsable() {
        Structure structure = vanilla(StructureKeys.IGLOO).value();
        assertFalse(StructureSnapshotBuilder.usable(null));
        assertFalse(StructureSnapshotBuilder.usable(StructureStart.DEFAULT), "the legacy INVALID sentinel has no structure");
        assertFalse(StructureSnapshotBuilder.usable(start(structure, 0, 0)), "no pieces, no structure");
        assertTrue(StructureSnapshotBuilder.usable(start(structure, 0, 0, piece(0, 0, 0, 1, 1, 1))));
    }

    public static void aStructureWithManyPiecesIsOneSnapshotWithAllOfThem() {
        Structure structure = vanilla(StructureKeys.VILLAGE_PLAINS).value();
        StructurePiece[] pieces = new StructurePiece[300];
        for (int i = 0; i < pieces.length; i++) {
            pieces[i] = piece(i * 4, 60, 0, i * 4 + 3, 64, 3);
        }
        StructureSnapshot snapshot = StructureSnapshotBuilder.snapshot("minecraft:overworld",
                StructureSnapshotBuilder.describe(vanilla(StructureKeys.VILLAGE_PLAINS)), start(structure, 0, 0, pieces), true);
        assertEquals(300, snapshot.pieces().size());
        assertEquals(300, snapshot.sampleBoxes().size());
    }
}
