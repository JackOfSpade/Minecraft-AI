package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.structure.StructureKey;
import dev.spawnbotswrapper.inhabitants.structure.StructureSnapshot;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureStart;

/**
 * What the detector and the locator need from the registry-aware half of structure handling: identify a
 * structure start cheaply, and turn it into a snapshot. {@link StructureSnapshotBuilder} is the real
 * implementation; the split keeps the event/queue/dedupe logic testable without a registry.
 */
public interface SnapshotSource {

    /** Identity of a start, or null when it is unusable or its structure is not registered. Cheap. */
    StructureKey keyOf(ServerWorld world, StructureStart start);

    /** Full snapshot of a start (piece boxes included), or null when it is unusable or not registered. */
    StructureSnapshot build(ServerWorld world, StructureStart start, boolean newlyGenerated);

    /** Forgets anything derived from the registries (data packs reloaded, or the server stopped). */
    void invalidate();
}
