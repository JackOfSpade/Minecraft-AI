package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.gen.structure.Structure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link StructureLocator} over structure data that is already in memory. It reads the structure starts and
 * structure references of LOADED chunks only and never loads or generates anything, so a structure whose
 * start chunk is not loaded is simply not reported (the admin commands say so instead of stalling the
 * server). The normal population flow does not use this; it is event driven through
 * {@link StructureDetector}.
 * <p>
 * Snapshots produced here report {@code newlyGenerated == false}: whether a chunk was generated this
 * session is only known from the load event.
 */
public final class McStructureLocator implements StructureLocator {
    /** Upper bound on the search radius in chunks, so a mistyped command cannot walk millions of chunks. */
    public static final int MAX_RADIUS_CHUNKS = 64;

    private final SnapshotSource source;

    public McStructureLocator(SnapshotSource source) {
        this.source = source;
    }

    @Override
    public List<StructureSnapshot> at(ServerWorld world, BlockPos pos) {
        ServerChunkManager chunks = world.getChunkManager();
        Map<StructureKey, StructureSnapshot> found = new LinkedHashMap<>();
        WorldChunk here = chunks.getWorldChunk(pos.getX() >> 4, pos.getZ() >> 4);
        if (here == null) {
            return List.of();
        }
        // Starts stored in this very chunk, plus every start whose box reaches this chunk (the references).
        collect(world, here, found);
        for (Map.Entry<Structure, LongSet> reference : here.getStructureReferences().entrySet()) {
            for (long startChunk : reference.getValue()) {
                WorldChunk origin = chunks.getWorldChunk(ChunkPos.getPackedX(startChunk), ChunkPos.getPackedZ(startChunk));
                if (origin != null) {
                    collect(world, origin, found);
                }
            }
        }
        List<StructureSnapshot> result = new ArrayList<>();
        for (StructureSnapshot s : found.values()) {
            if (s.bounds().contains(pos.getX(), pos.getY(), pos.getZ())) {
                result.add(s);
            }
        }
        result.sort(Comparator.comparingLong((StructureSnapshot s) -> s.bounds().volume())
                .thenComparing(s -> s.key().asString()));
        return result;
    }

    @Override
    public List<StructureSnapshot> near(ServerWorld world, BlockPos pos, int radiusChunks) {
        ServerChunkManager chunks = world.getChunkManager();
        int radius = Math.max(0, Math.min(radiusChunks, MAX_RADIUS_CHUNKS));
        int centerX = pos.getX() >> 4;
        int centerZ = pos.getZ() >> 4;
        Map<StructureKey, StructureSnapshot> found = new LinkedHashMap<>();
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                WorldChunk chunk = chunks.getWorldChunk(centerX + dx, centerZ + dz);
                if (chunk != null) {
                    collect(world, chunk, found);
                }
            }
        }
        List<StructureSnapshot> result = new ArrayList<>(found.values());
        result.sort(Comparator
                .comparingInt((StructureSnapshot s) -> chebyshev(s.key(), centerX, centerZ))
                .thenComparingDouble(s -> s.bounds().horizontalDistanceSq(pos.getX() + 0.5, pos.getZ() + 0.5))
                .thenComparing(s -> s.key().asString()));
        return result;
    }

    private static int chebyshev(StructureKey key, int chunkX, int chunkZ) {
        return Math.max(Math.abs(key.chunkX() - chunkX), Math.abs(key.chunkZ() - chunkZ));
    }

    /** Adds a snapshot for every usable structure start stored in {@code chunk}, once per structure instance. */
    private void collect(ServerWorld world, WorldChunk chunk, Map<StructureKey, StructureSnapshot> into) {
        for (StructureStart start : chunk.getStructureStarts().values()) {
            StructureKey key = source.keyOf(world, start);
            if (key == null || into.containsKey(key)) {
                continue;
            }
            StructureSnapshot snapshot = source.build(world, start, false);
            if (snapshot != null) {
                into.put(key, snapshot);
            }
        }
    }
}
