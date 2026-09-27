package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.spawn.Cell;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** {@link McBlockProbe} chunk lookup, height limits, border and classification with stand-in chunks, run inside {@link McSandbox}. */
public final class McBlockProbeMcCases {
    private McBlockProbeMcCases() {
    }

    /** A chunk whose blocks come from a map; everything else is air. Never touches a real world. */
    private static final class FakeChunk extends WorldChunk {
        Map<BlockPos, BlockState> states;
        int reads;

        // Never runs (allocated without a constructor); it only has to type-check.
        FakeChunk() {
            super(null, null);
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            reads++;
            BlockState state = states.get(pos.toImmutable());
            return state == null ? Blocks.AIR.getDefaultState() : state;
        }
    }

    private static FakeChunk chunk(Map<BlockPos, BlockState> states) {
        FakeChunk chunk = McObjects.opaque(FakeChunk.class);
        McObjects.setField(chunk, FakeChunk.class, "states", new HashMap<>(states));
        return chunk;
    }

    private static McBlockProbe probe(McBlockProbe.Chunks chunks) {
        return new McBlockProbe(chunks, -64, 319, () -> {
            throw new AssertionError("the border is not needed here");
        });
    }

    public static void blocksAreReadAtWorldCoordinatesAndClassified() {
        FakeChunk chunk = chunk(Map.of(
                new BlockPos(5, 64, 5), Blocks.STONE.getDefaultState(),
                new BlockPos(5, 63, 5), Blocks.WATER.getDefaultState(),
                new BlockPos(6, 64, 5), Blocks.LAVA.getDefaultState(),
                new BlockPos(7, 64, 5), Blocks.CACTUS.getDefaultState(),
                new BlockPos(8, 64, 5), Blocks.OAK_FENCE.getDefaultState()));
        McBlockProbe probe = probe((cx, cz) -> chunk);
        assertEquals(Cell.SOLID_STANDABLE, probe.cell(5, 64, 5));
        assertEquals(Cell.EMPTY, probe.cell(5, 65, 5));
        assertEquals(Cell.WATER, probe.cell(5, 63, 5));
        assertEquals(Cell.HAZARD, probe.cell(6, 64, 5));
        assertEquals(Cell.SOLID_HAZARD, probe.cell(7, 64, 5));
        assertEquals(Cell.SOLID_OTHER, probe.cell(8, 64, 5));
    }

    public static void anUnloadedChunkIsUnloadedAndNothingIsRead() {
        List<String> asked = new ArrayList<>();
        McBlockProbe probe = probe((cx, cz) -> {
            asked.add(cx + "," + cz);
            return null;
        });
        assertEquals(Cell.UNLOADED, probe.cell(100, 64, 100));
        assertEquals(List.of("6,6"), asked);
    }

    public static void chunkCoordinatesRoundTowardsMinusInfinityForNegativeBlocks() {
        List<String> asked = new ArrayList<>();
        FakeChunk chunk = chunk(Map.of());
        McBlockProbe probe = probe((cx, cz) -> {
            asked.add(cx + "," + cz);
            return chunk;
        });
        probe.cell(-1, 64, -1);
        probe.cell(-16, 64, -16);
        probe.cell(-17, 64, -17);
        probe.cell(0, 64, -1);
        probe.cell(15, 64, 15);
        probe.cell(16, 64, 16);
        assertEquals(List.of("-1,-1", "-1,-1", "-2,-2", "0,-1", "0,0", "1,1"), asked);
    }

    public static void positionsOutsideTheBuildHeightAreUnloadedWithoutAskingForAChunk() {
        AtomicInteger asked = new AtomicInteger();
        McBlockProbe probe = probe((cx, cz) -> {
            asked.incrementAndGet();
            return chunk(Map.of());
        });
        assertEquals(-64, probe.minY());
        assertEquals(319, probe.maxY());
        assertEquals(Cell.UNLOADED, probe.cell(0, -65, 0));
        assertEquals(Cell.UNLOADED, probe.cell(0, 320, 0));
        assertEquals(Cell.UNLOADED, probe.cell(0, Integer.MAX_VALUE, 0));
        assertEquals(Cell.UNLOADED, probe.cell(0, Integer.MIN_VALUE, 0));
        assertEquals(0, asked.get());
        assertEquals(Cell.EMPTY, probe.cell(0, -64, 0));
        assertEquals(Cell.EMPTY, probe.cell(0, 319, 0));
        assertEquals(2, asked.get());
    }

    public static void aNetherStyleHeightRangeIsHonoured() {
        McBlockProbe nether = new McBlockProbe((cx, cz) -> chunk(Map.of()), 0, 255, () -> null);
        assertEquals(0, nether.minY());
        assertEquals(255, nether.maxY());
        assertEquals(Cell.UNLOADED, nether.cell(0, -1, 0));
        assertEquals(Cell.UNLOADED, nether.cell(0, 256, 0));
        assertEquals(Cell.EMPTY, nether.cell(0, 255, 0));
    }

    public static void theSameStateIsClassifiedConsistentlyAcrossPositionsAndProbes() {
        FakeChunk chunk = chunk(Map.of(
                new BlockPos(1, 64, 1), Blocks.STONE.getDefaultState(),
                new BlockPos(2, 64, 2), Blocks.STONE.getDefaultState(),
                new BlockPos(3, 64, 3), Blocks.OAK_SLAB.getDefaultState()));
        McBlockProbe first = probe((cx, cz) -> chunk);
        McBlockProbe second = probe((cx, cz) -> chunk);
        for (McBlockProbe probe : List.of(first, second, first)) {
            assertEquals(Cell.SOLID_STANDABLE, probe.cell(1, 64, 1));
            assertEquals(Cell.SOLID_STANDABLE, probe.cell(2, 64, 2));
            assertEquals(Cell.SOLID_OTHER, probe.cell(3, 64, 3));
        }
    }

    public static void theBorderIsFetchedOnceAndUsesVanillasContainsRule() {
        WorldBorder border = new WorldBorder();
        border.setCenter(0, 0);
        border.setSize(100);
        AtomicInteger fetched = new AtomicInteger();
        McBlockProbe probe = new McBlockProbe((cx, cz) -> null, -64, 319, () -> {
            fetched.incrementAndGet();
            return border;
        });
        assertTrue(probe.insideBorder(0, 0));
        assertTrue(probe.insideBorder(-50, -50), "the west/north edge is inside");
        assertTrue(probe.insideBorder(49, 49));
        assertFalse(probe.insideBorder(50, 0), "the east/south edge is outside");
        assertFalse(probe.insideBorder(0, 50));
        assertFalse(probe.insideBorder(-51, 0));
        assertFalse(probe.insideBorder(1_000_000, 1_000_000));
        assertEquals(1, fetched.get());
    }
}
