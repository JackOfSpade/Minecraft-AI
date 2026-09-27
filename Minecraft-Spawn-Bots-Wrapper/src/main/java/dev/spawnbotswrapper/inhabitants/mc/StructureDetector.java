package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureStart;
import net.minecraft.world.chunk.WorldChunk;

import java.util.function.Consumer;

/**
 * Finds structures as their chunks come into existence, with no scanning and no chunk loading of its own.
 * <p>
 * Two Fabric callbacks fire for every chunk that reaches FULL status: {@code CHUNK_LOAD} always and
 * {@code CHUNK_GENERATE} additionally for chunks generated in this session. They run inside the chunk task,
 * where the chunk's own future is still incomplete, so asking the world for any block or chunk of the chunk
 * being loaded would wait for the very task doing the asking. The callbacks therefore do the bare minimum:
 * if the chunk carries any structure start (a field read on the chunk they were handed) they queue it, and
 * flag whether it was generated. All real work happens in {@link #drain}, called from the server tick.
 * <p>
 * Chunk callbacks for the spawn area fire BEFORE the server has finished starting, so {@link #register()}
 * must run at mod initialisation; whatever arrives before the engine exists simply waits in the queue.
 * <p>
 * A structure start lives in exactly one chunk (its start chunk) and there is exactly one per structure
 * instance, so a village spanning fifty chunks yields one snapshot, when its start chunk loads.
 */
public final class StructureDetector {
    /** Upper bound on queued chunks; beyond it the oldest are dropped (their structures reappear on reload). */
    public static final int QUEUE_CAPACITY = 16384;
    /** How many recently handed-over structures are remembered to skip start chunks that reload. */
    public static final int RECENT_CAPACITY = 4096;

    private final SnapshotSource source;
    private final StepGuard guard;
    private final ChunkEventQueue<ChunkRef> queue = new ChunkEventQueue<>(QUEUE_CAPACITY);
    private final RecentKeys<StructureKey> recent = new RecentKeys<>(RECENT_CAPACITY);

    /** A chunk together with its world; equality is identity of both, i.e. "the same chunk object". */
    record ChunkRef(ServerWorld world, WorldChunk chunk) {
    }

    public StructureDetector(SnapshotSource source, StepGuard guard) {
        this.source = source;
        this.guard = guard;
    }

    /** Hooks the Fabric events. Call once, from the mod initialiser (events cannot be unregistered). */
    public void register() {
        ServerChunkEvents.CHUNK_LOAD.register(this::onChunkLoad);
        ServerChunkEvents.CHUNK_GENERATE.register(this::onChunkGenerate);
        // Tag membership can change on /reload, so anything derived from the registries must be rebuilt.
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> source.invalidate());
    }

    void onChunkLoad(ServerWorld world, WorldChunk chunk) {
        if (!chunk.getStructureStarts().isEmpty()) {
            queue.loaded(new ChunkRef(world, chunk));
        }
    }

    void onChunkGenerate(ServerWorld world, WorldChunk chunk) {
        if (!chunk.getStructureStarts().isEmpty()) {
            queue.generated(new ChunkRef(world, chunk));
        }
    }

    /**
     * Turns queued chunks into snapshots and hands each to {@code sink}. Processes at most {@code maxChunks}
     * chunks (the rest wait for the next tick) and returns the number of snapshots delivered. Chunks queued
     * by a different server (a previous world in the same JVM) are discarded. A failure while handling one
     * structure is reported through the guard and never prevents the others from being handled.
     */
    public int drain(MinecraftServer server, int maxChunks, Consumer<StructureSnapshot> sink) {
        int[] delivered = {0};
        queue.drain(maxChunks, entry -> {
            ChunkRef ref = entry.chunk();
            if (ref.world().getServer() != server) {
                return;
            }
            for (StructureStart start : ref.chunk().getStructureStarts().values()) {
                guard.run("structure detection", () -> {
                    StructureKey key = source.keyOf(ref.world(), start);
                    if (key == null || recent.contains(key)) {
                        return;
                    }
                    StructureSnapshot snapshot = source.build(ref.world(), start, entry.generated());
                    if (snapshot != null) {
                        sink.accept(snapshot);
                        recent.add(key);
                        delivered[0]++;
                    }
                });
            }
        });
        return delivered[0];
    }

    /** Drops queued chunks without looking at them (the addon is switched off; nothing may pile up). */
    public void discardPending() {
        queue.clear();
    }

    /** Forgets which structures were handed over recently, so they are offered again when their chunk next loads. */
    public void forgetRecent() {
        recent.clear();
    }

    /** Full reset for a server that has stopped, so the next world in this JVM starts clean. */
    public void reset() {
        queue.clear();
        recent.clear();
        source.invalidate();
    }

    /** Chunks waiting to be drained. */
    public int pending() {
        return queue.size();
    }

    /** Chunks discarded because the queue overflowed. */
    public long dropped() {
        return queue.dropped();
    }
}
