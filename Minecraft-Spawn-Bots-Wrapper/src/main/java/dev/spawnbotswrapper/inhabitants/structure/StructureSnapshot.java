package dev.spawnbotswrapper.inhabitants.structure;

import java.util.List;
import java.util.Set;

/**
 * Everything the addon needs to know about one detected structure instance, extracted from
 * Minecraft's StructureStart on the server thread and then handed around as plain immutable data.
 * <p>
 * Produced by the detector (Minecraft glue), consumed by the population engine (pure logic).
 *
 * @param key            identity: dimension + registry id + start chunk. One per structure instance.
 * @param tagIds         ids (WITHOUT '#', e.g. {@code minecraft:village}) of every structure tag the
 *                       structure belongs to; used for include/exclude and per-tag overrides
 * @param bounds         bounding box of the whole structure
 * @param pieces         bounding boxes of the individual pieces (a village has dozens); may be empty,
 *                       in which case {@code bounds} is used. Spawn positions are chosen inside pieces.
 * @param newlyGenerated true if the start chunk was generated in this session (saw CHUNK_GENERATE),
 *                       false if it was merely loaded from disk
 */
public record StructureSnapshot(
        StructureKey key,
        Set<String> tagIds,
        IntBox bounds,
        List<IntBox> pieces,
        boolean newlyGenerated) {

    public StructureSnapshot {
        tagIds = Set.copyOf(tagIds);
        pieces = List.copyOf(pieces);
    }

    /** Boxes to sample spawn columns from: the pieces, or the overall bounds when none are known. */
    public List<IntBox> sampleBoxes() {
        return pieces.isEmpty() ? List.of(bounds) : pieces;
    }
}
