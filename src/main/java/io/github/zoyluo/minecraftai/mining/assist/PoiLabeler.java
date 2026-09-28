package io.github.zoyluo.minecraftai.mining.assist;

import java.util.List;
import java.util.Objects;

/**
 * Deterministic structure label for a POI candidate (design 6.3), used for the notice text, the
 * {@code poi_hold_<label>} marker and the same-label suppression radius. Pure: it reads only bucket
 * presence and the boolean flags on {@link PoiSignals}.
 *
 * <p>The design lists the mappings without a precedence. Where signatures overlap the labeler picks
 * the more specific structure first, because the generic mineshaft materials (cobweb, planks, fences)
 * also occur in trial chambers and strongholds. The {@code blackstone} flag is the weakest signal: it is
 * presence-only and blackstone is a natural block (Nether, Terralith caves), so it only decides when
 * nothing more specific matched:</p>
 * <ol>
 *   <li>{@code ancient_city}: DEEPSLATE_BUILD plus any sculk;</li>
 *   <li>{@code trial_chamber}: COPPER_TUFF_BUILD or a vault;</li>
 *   <li>{@code fortress_bastion}: nether bricks;</li>
 *   <li>{@code dungeon}: SPAWNER plus mossy cobblestone;</li>
 *   <li>{@code stronghold}: iron bars plus stone bricks;</li>
 *   <li>{@code mineshaft}: rails, cobweb or wood build (planks, fences);</li>
 *   <li>{@code fortress_bastion}: blackstone, when none of the above matched;</li>
 *   <li>{@code structure_unknown} otherwise.</li>
 * </ol>
 */
public final class PoiLabeler {
    public static final String ANCIENT_CITY = "ancient_city";
    public static final String TRIAL_CHAMBER = "trial_chamber";
    /** The design's "fortress/bastion", written with an underscore so it is safe inside place names. */
    public static final String FORTRESS_BASTION = "fortress_bastion";
    public static final String DUNGEON = "dungeon";
    public static final String STRONGHOLD = "stronghold";
    public static final String MINESHAFT = "mineshaft";
    public static final String STRUCTURE_UNKNOWN = "structure_unknown";

    /** Every label {@link #label} can return. */
    public static final List<String> ALL_LABELS = List.of(
            ANCIENT_CITY, TRIAL_CHAMBER, FORTRESS_BASTION, DUNGEON, STRONGHOLD, MINESHAFT, STRUCTURE_UNKNOWN);

    private PoiLabeler() {
    }

    public static String label(PoiSignals s) {
        Objects.requireNonNull(s, "signals");
        boolean sculk = s.cellCount(PoiBucket.SCULK_STRUCT) > 0 || s.sculkFamilyWithin12() > 0
                || s.reinforcedDeepslateCount() > 0 || s.sculkShriekerCount() > 0
                || s.sculkCatalystCount() > 0 || s.sculkSensorCount() > 0;
        if (s.cellCount(PoiBucket.DEEPSLATE_BUILD) > 0 && sculk) {
            return ANCIENT_CITY;
        }
        if (s.vault() || s.cellCount(PoiBucket.COPPER_TUFF_BUILD) > 0) {
            return TRIAL_CHAMBER;
        }
        if (s.netherBricks()) {
            return FORTRESS_BASTION;
        }
        if (s.cellCount(PoiBucket.SPAWNER) > 0 && s.mossyStone()) {
            return DUNGEON;
        }
        if (s.ironBars() && s.stoneBricks()) {
            return STRONGHOLD;
        }
        if (s.cellCount(PoiBucket.RAIL) > 0 || s.cellCount(PoiBucket.WEB) > 0
                || s.cellCount(PoiBucket.WOOD_BUILD) > 0) {
            return MINESHAFT;
        }
        if (s.blackstone()) {
            return FORTRESS_BASTION;
        }
        return STRUCTURE_UNKNOWN;
    }
}
