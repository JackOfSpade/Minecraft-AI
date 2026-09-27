package dev.spawnbotswrapper.inhabitants.structure;

import dev.spawnbotswrapper.inhabitants.util.StableHash;

import java.util.Objects;

/**
 * Identity of one structure INSTANCE: which dimension, which registered structure, and the chunk its
 * {@code StructureStart} lives in. A structure has exactly one start chunk, so a village with fifty
 * houses is one key and therefore one roll - however many of its chunks load.
 * <p>
 * The string form ({@code dimension|structureId|chunkX,chunkZ}) is used as the persisted map key;
 * '|' cannot occur in a namespaced identifier, so parsing is unambiguous.
 */
public record StructureKey(String dimension, String structureId, int chunkX, int chunkZ) {

    public StructureKey {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(structureId, "structureId");
    }

    public String asString() {
        return dimension + "|" + structureId + "|" + chunkX + "," + chunkZ;
    }

    /** Inverse of {@link #asString()}; returns null for malformed input. */
    public static StructureKey parse(String s) {
        if (s == null) {
            return null;
        }
        String[] parts = s.split("\\|", -1);
        if (parts.length != 3) {
            return null;
        }
        String[] xz = parts[2].split(",", -1);
        if (xz.length != 2) {
            return null;
        }
        try {
            return new StructureKey(parts[0], parts[1], Integer.parseInt(xz[0]), Integer.parseInt(xz[1]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** JDK-independent hash of the identity, the basis of deterministic rolls. */
    public long stableHash() {
        return StableHash.of(
                StableHash.ofString(dimension),
                StableHash.ofString(structureId),
                StableHash.pack(chunkX, chunkZ));
    }

    @Override
    public String toString() {
        return asString();
    }
}
