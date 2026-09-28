package io.github.zoyluo.minecraftai.mining.assist;

import java.util.Objects;

/**
 * What the sensor knows about a block <em>type</em> (mining-assist design 3.3): a plain value
 * record with no Minecraft classes, produced once per block by {@code BlockFactsAdapter} and consumed
 * by the pure folds. Fluid state is deliberately not here: it depends on the individual state
 * (waterlogging), so the adapter reports it per hit.
 *
 * @param namespace      registry namespace, lower case
 * @param path           registry path, lower case
 * @param rawValue       {@link ValueTable} value, 0 when the block is not a valuable
 * @param bucket         {@link PoiLexicon#classify} bucket (NATURAL means no POI weight)
 * @param trap           pressure plate, tripwire, tripwire hook, TNT or dispenser
 * @param hasBlockEntity whether the block carries a block entity
 * @param falling        whether the block falls (sand, gravel, anvils ...)
 * @param poiFlags       {@link PoiEvidenceFlags} bits, including the packed habitation marker
 */
public record BlockFacts(
        String namespace,
        String path,
        int rawValue,
        PoiBucket bucket,
        boolean trap,
        boolean hasBlockEntity,
        boolean falling,
        int poiFlags) {

    public BlockFacts {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(bucket, "bucket");
        rawValue = Math.max(0, rawValue);
    }

    /** Facts for a block that contributes nothing: natural, valueless, harmless. */
    public static BlockFacts plain(String path) {
        return new BlockFacts("minecraft", path, 0, PoiBucket.NATURAL, false, false, false, 0);
    }

    /**
     * Derives the facts of a registry id through the pure rules ({@link ValueTable}, {@link
     * PoiLexicon#classify}, {@link AssistRules#isTrap}, {@link PoiEvidenceFlags#compute}). This is the whole
     * of what the Minecraft adapter adds on top of the registry lookup, so it is unit-testable without one.
     *
     * @param naturalTag the lexicon's naturalTag input (lush caves for vanilla logs and leaves, a
     *                   natural-terrain tag for modded blocks)
     */
    public static BlockFacts derive(String namespace, String path, boolean hasBlockEntity,
                                    boolean falling, boolean naturalTag) {
        return new BlockFacts(
                namespace,
                path,
                ValueTable.valueOf(namespace, path),
                PoiLexicon.classify(namespace, path, hasBlockEntity, naturalTag),
                AssistRules.isTrap(namespace, path),
                hasBlockEntity,
                falling,
                PoiEvidenceFlags.compute(namespace, path));
    }

    public boolean natural() {
        return bucket.isNatural();
    }

    public boolean valuable() {
        return rawValue > 0;
    }

    /** True when the block belongs in the POI evidence window: a non-natural bucket or a presence flag. */
    public boolean evidence() {
        return !bucket.isNatural() || poiFlags != 0;
    }

    /** Full registry id, {@code namespace:path}. */
    public String id() {
        return namespace + ':' + path;
    }

    /**
     * The id the sighting ledger stores: the bare registry path for vanilla blocks (as the ledger
     * documents) and the full id for modded ones, so two mods' {@code zinc_ore} cannot collide.
     */
    public String ledgerId() {
        return AssistRules.isVanilla(namespace) ? path : id();
    }
}
