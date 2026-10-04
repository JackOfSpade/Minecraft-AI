package io.github.zoyluo.minecraftai.mining.assist;

import java.util.Collection;
import java.util.Objects;

/**
 * Builds the scorer's {@link PoiSignals} snapshot from what the sensor has remembered (mining-assist
 * design 6.2 and 6.3). Pure: a {@link PoiEvidenceWindow}, the entity evidence, the openness estimate
 * and a few scalars in, an immutable snapshot out. Reads nothing from the world.
 *
 * <p>The habitation set and labeler flags come from the flags of the remembered cells, which never include
 * bot-placed cells (the sensor excludes those before they enter the window).</p>
 */
public final class PoiAssembler {
    private PoiAssembler() {
    }

    /**
     * @param window          the bot's POI evidence window (not modified)
     * @param botX            bot position (feet or eye, the caller's choice)
     * @param entities        entity evidence of the latest scan, may be null for none
     * @param openness        ring openness at the live perception radius, may be null for unavailable
     * @param perceptionRadius the LIVE perception radius the rays were clamped to
     * @param dimensionId     {@code world.dimension().getValue().toString()}
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
        int flagsSeen = 0;
        for (PoiEvidenceWindow.Entry e : window.structuralEntries()) {
            b.cell(e.bucket(), e.pos());
            int flags = e.flags();
            flagsSeen |= flags;
            PoiSignals.Habitation habitation = PoiEvidenceFlags.habitation(flags);
            if (habitation != null) {
                b.habitation(habitation);
            }
        }
        for (PoiEvidenceWindow.Entry e : window.flagOnlyEntries()) {
            int flags = e.flags();
            flagsSeen |= flags;
        }
        b.mossyStone(PoiEvidenceFlags.has(flagsSeen, PoiEvidenceFlags.MOSSY_STONE))
                .vault(PoiEvidenceFlags.has(flagsSeen, PoiEvidenceFlags.VAULT))
                .ironBars(PoiEvidenceFlags.has(flagsSeen, PoiEvidenceFlags.IRON_BARS))
                .stoneBricks(PoiEvidenceFlags.has(flagsSeen, PoiEvidenceFlags.STONE_BRICKS))
                .netherBricks(PoiEvidenceFlags.has(flagsSeen, PoiEvidenceFlags.NETHER_BRICKS))
                .blackstone(PoiEvidenceFlags.has(flagsSeen, PoiEvidenceFlags.BLACKSTONE));
        if (entities != null) {
            for (PoiSignals.Habitation habitation : entities.habitation()) {
                b.habitation(habitation);
            }
            b.entityScore(entities.rawScore());
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

}
