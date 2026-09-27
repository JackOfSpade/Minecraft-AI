package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.structure.IntBox;
import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.MinecraftDedicatedServer;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.gen.structure.Structure;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stand-ins for a world, its chunk manager and its chunks, built without a running server. Only the members
 * the detector and locator touch are backed by data: a chunk's start and reference maps and position, a
 * world's server and chunk manager, and which chunks the manager reports as loaded. Run inside {@link McSandbox}.
 */
final class WorldFakes {
    private WorldFakes() {
    }

    /** A chunk manager that answers {@code getWorldChunk} from a map and counts the lookups. */
    static final class ChunkManager extends ServerChunkManager {
        final Map<Long, WorldChunk> loaded = new HashMap<>();
        int lookups;

        // Never runs (instances are allocated without a constructor); it only has to type-check.
        ChunkManager() {
            super(null, null, null, null, null, null, 0, 0, false, null, null);
        }

        @Override
        public WorldChunk getWorldChunk(int chunkX, int chunkZ) {
            lookups++;
            return loaded.get(ChunkPos.toLong(chunkX, chunkZ));
        }

        void load(WorldChunk chunk) {
            loaded.put(chunk.getPos().toLong(), chunk);
        }
    }

    static MinecraftServer server() {
        return McObjects.opaque(MinecraftDedicatedServer.class);
    }

    static ChunkManager chunkManager() {
        ChunkManager manager = McObjects.opaque(ChunkManager.class);
        // Allocated without a constructor, so field initialisers did not run.
        McObjects.setField(manager, ChunkManager.class, "loaded", new HashMap<Long, WorldChunk>());
        return manager;
    }

    static ServerWorld world(MinecraftServer server, ChunkManager chunks) {
        ServerWorld world = McObjects.opaque(ServerWorld.class);
        McObjects.setField(world, ServerWorld.class, "server", server);
        McObjects.setField(world, ServerWorld.class, "chunkManager", chunks);
        return world;
    }

    /** A loaded chunk at (x, z) holding the given starts and structure references. */
    static WorldChunk chunk(int x, int z, Map<Structure, StructureStart> starts, Map<Structure, LongSet> references) {
        WorldChunk chunk = McObjects.opaque(WorldChunk.class);
        McObjects.setField(chunk, Chunk.class, "pos", new ChunkPos(x, z));
        McObjects.setField(chunk, Chunk.class, "structureStarts", new HashMap<>(starts));
        McObjects.setField(chunk, Chunk.class, "structureReferences", new HashMap<>(references));
        return chunk;
    }

    static LongSet chunks(int... xz) {
        LongSet set = new LongOpenHashSet();
        for (int i = 0; i < xz.length; i += 2) {
            set.add(ChunkPos.toLong(xz[i], xz[i + 1]));
        }
        return set;
    }

    /** A snapshot with the given key parts and box, no pieces. */
    static StructureSnapshot snapshot(String id, int chunkX, int chunkZ, IntBox box, boolean generated) {
        return new StructureSnapshot(new StructureKey("minecraft:overworld", id, chunkX, chunkZ), Set.of(), box, List.of(), generated);
    }

    /** A {@link SnapshotSource} with canned answers per start, recording what it was asked. */
    static final class Source implements SnapshotSource {
        final Map<StructureStart, StructureSnapshot> snapshots = new IdentityHashMap<>();
        final Set<StructureStart> failing = new HashSet<>();
        final List<Boolean> generatedFlags = new java.util.ArrayList<>();
        int builds;
        int invalidations;

        StructureStart add(StructureSnapshot snapshot) {
            StructureStart start = McObjects.opaque(StructureStart.class);
            snapshots.put(start, snapshot);
            return start;
        }

        @Override
        public StructureKey keyOf(ServerWorld world, StructureStart start) {
            StructureSnapshot s = snapshots.get(start);
            return s == null ? null : s.key();
        }

        @Override
        public StructureSnapshot build(ServerWorld world, StructureStart start, boolean newlyGenerated) {
            builds++;
            generatedFlags.add(newlyGenerated);
            if (failing.contains(start)) {
                throw new IllegalStateException("cannot build this one");
            }
            StructureSnapshot s = snapshots.get(start);
            return s == null ? null : new StructureSnapshot(s.key(), s.tagIds(), s.bounds(), s.pieces(), newlyGenerated);
        }

        @Override
        public void invalidate() {
            invalidations++;
        }
    }
}
