package io.github.zoyluo.aibot.mining.assist;

import java.util.Collection;
import java.util.Objects;

/**
 * Builds the scorer's {@link PoiSignals} snapshot from what the sensor has remembered (mining-assist
 * design 6.2 and 6.3). Pure: a {@link PoiEvidenceWindow}, the entity evidence, the openness estimate
 * and a few scalars in, an immutable snapshot out. Reads nothing from the world.
 *
 * <p>Sculk-family cells are counted within {@value #SCULK_RADIUS} blocks of the bot (the design's
 * "at least 6 sculk-family cells within 12 blocks"), taken from both sub-windows because plain sculk
 * and sculk veins are natural and only live in the flag-only window. The specific-block counts, the
 * habitation set and the labeler flags come from the flags of the remembered cells, which never include
 * bot-placed cells (the sensor excludes those before they enter the window).</p>
 */
public final class PoiAssembler {
    /** Radius, in blocks, of the mandatory sculk-family count. */
    public static final int SCULK_RADIUS = 12;

    private PoiAssembler() {
    }

    /**
     * @param window          the bot's POI evidence window (not modified)
     * @param botX            bot position used for the sculk radius (feet or eye, the caller's choice)
     * @param entities        entity evidence of the latest scan, may be null for none
     * @param openness        ring openness at the live perception radius, may be null for unavailable
     * @param perceptionRadius the LIVE perception radius the rays were clamped to
     * @param dimensionId     {@code world.getRegistryKey().getValue().toString()}
     * @param cavernDimensions {@code poi.cavernDimensions}
     */
    public static PoiSignals assemble(PoiEvidenceWindow window,
                                      double botX, double botY, double botZ,
                                      EntityEvidence entities,
                                      FreeRunStats.Openness openness,
                                      double perceptionRadius,
                                      String dimensionId,
                                      Collection<String> cavernDimensions) {
        Objects.requireNonNull(window, "window");
        PoiSignals.Builder b = PoiSignals.builder();
        int reinforced = 0;
        int shrieker = 0;
        int catalyst = 0;
        int sensors = 0;
        int sculkNear = 0;
        int flagsSeen = 0;
        double radiusSq = (double) SCULK_RADIUS * SCULK_RADIUS;
        for (PoiEvidenceWindow.Entry e : window.structuralEntries()) {
            b.cell(e.bucket(), e.pos());
            int flags = e.flags();
            flagsSeen |= flags;
            if (PoiEvidenceFlags.has(flags, PoiEvidenceFlags.REINFORCED_DEEPSLATE)) {
                reinforced++;
            }
            if (PoiEvidenceFlags.has(flags, PoiEvidenceFlags.SCULK_SHRIEKER)) {
                shrieker++;
            }
            if (PoiEvidenceFlags.has(flags, PoiEvidenceFlags.SCULK_CATALYST)) {
                catalyst++;
            }
            if (PoiEvidenceFlags.has(flags, PoiEvidenceFlags.SCULK_SENSOR)) {
                sensors++;
            }
            PoiSignals.Habitation habitation = PoiEvidenceFlags.habitation(flags);
            if (habitation != null) {
                b.habitation(habitation);
            }
            if (PoiEvidenceFlags.has(flags, PoiEvidenceFlags.SCULK_FAMILY)
                    && withinSquared(e, botX, botY, botZ, radiusSq)) {
                sculkNear++;
            }
        }
        for (PoiEvidenceWindow.Entry e : window.flagOnlyEntries()) {
            int flags = e.flags();
            flagsSeen |= flags;
            if (PoiEvidenceFlags.has(flags, PoiEvidenceFlags.SCULK_FAMILY)
                    && withinSquared(e, botX, botY, botZ, radiusSq)) {
                sculkNear++;
            }
        }
        b.reinforcedDeepslate(reinforced)
                .sculkShrieker(shrieker)
                .sculkCatalyst(catalyst)
                .sculkSensors(sensors)
                .sculkFamilyWithin12(sculkNear)
                .mossyStone(PoiEvidenceFlags.has(flagsSeen, PoiEvidenceFlags.MOSSY_STONE))
                .vault(PoiEvidenceFlags.has(flagsSeen, PoiEvidenceFlags.VAULT))
                .ironBars(PoiEvidenceFlags.has(flagsSeen, PoiEvidenceFlags.IRON_BARS))
                .stoneBricks(PoiEvidenceFlags.has(flagsSeen, PoiEvidenceFlags.STONE_BRICKS))
                .netherBricks(PoiEvidenceFlags.has(flagsSeen, PoiEvidenceFlags.NETHER_BRICKS))
                .blackstone(PoiEvidenceFlags.has(flagsSeen, PoiEvidenceFlags.BLACKSTONE));
        if (entities != null) {
            for (PoiSignals.Habitation habitation : entities.habitation()) {
                b.habitation(habitation);
            }
            b.entityScore(entities.rawScore()).wardenVisible(entities.wardenVisible());
        }
        if (openness != null && openness.valid()) {
            b.openness(openness.fraction(), openness.upFree());
        } else {
            b.opennessUnavailable();
        }
        b.perceptionRadius(perceptionRadius);
        if (dimensionId != null && !dimensionId.isBlank()) {
            b.dimensionId(dimensionId);
        }
        if (cavernDimensions != null) {
            b.cavernDimensions(cavernDimensions);
        }
        return b.build();
    }

    private static boolean withinSquared(PoiEvidenceWindow.Entry e, double x, double y, double z, double radiusSq) {
        double dx = e.pos().getX() + 0.5D - x;
        double dy = e.pos().getY() + 0.5D - y;
        double dz = e.pos().getZ() + 0.5D - z;
        return dx * dx + dy * dy + dz * dz <= radiusSq;
    }
}
