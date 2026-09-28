package io.github.zoyluo.minecraftai.mining.assist;

import java.util.Locale;

/**
 * Bit flags describing what one observed block id contributes to POI scoring beyond its bucket
 * (mining-assist design 6.2 and 6.3). The scorer's specific-block counts, its habitation set and the
 * labeler's presence flags all come from raw ids, not from buckets, and several of them sit on
 * natural blocks (plain sculk, sculk veins, natural blackstone). Pure: ids in, an int out.
 */
public final class PoiEvidenceFlags {
    /** Any sculk-family block, including plain sculk and sculk_vein. */
    public static final int SCULK_FAMILY = 1;
    public static final int REINFORCED_DEEPSLATE = 1 << 1;
    public static final int SCULK_SHRIEKER = 1 << 2;
    public static final int SCULK_CATALYST = 1 << 3;
    /** sculk_sensor or calibrated_sculk_sensor. */
    public static final int SCULK_SENSOR = 1 << 4;
    public static final int MOSSY_STONE = 1 << 5;
    public static final int VAULT = 1 << 6;
    public static final int IRON_BARS = 1 << 7;
    public static final int STONE_BRICKS = 1 << 8;
    public static final int NETHER_BRICKS = 1 << 9;
    public static final int BLACKSTONE = 1 << 10;

    /** Mask of every non-habitation flag bit. */
    public static final int FLAG_MASK = (1 << 11) - 1;

    private static final int HABITATION_SHIFT = 16;
    private static final int HABITATION_MASK = 0xF << HABITATION_SHIFT;
    private static final PoiSignals.Habitation[] HABITATIONS = PoiSignals.Habitation.values();

    private PoiEvidenceFlags() {
    }

    /** Flags of a block id: every lexicon marker that applies. Never throws. */
    public static int compute(String namespace, String path) {
        int flags = 0;
        if (PoiLexicon.isSculkFamily(namespace, path)) {
            flags |= SCULK_FAMILY;
        }
        if (AssistRules.isVanilla(namespace) && path != null) {
            switch (path.trim().toLowerCase(Locale.ROOT)) {
                case "reinforced_deepslate" -> flags |= REINFORCED_DEEPSLATE;
                case "sculk_shrieker" -> flags |= SCULK_SHRIEKER;
                case "sculk_catalyst" -> flags |= SCULK_CATALYST;
                default -> {
                }
            }
        }
        if (PoiLexicon.isSculkSensor(namespace, path)) {
            flags |= SCULK_SENSOR;
        }
        if (PoiLexicon.isMossyStone(namespace, path)) {
            flags |= MOSSY_STONE;
        }
        if (PoiLexicon.isVault(namespace, path)) {
            flags |= VAULT;
        }
        if (PoiLexicon.isIronBars(namespace, path)) {
            flags |= IRON_BARS;
        }
        if (PoiLexicon.isStoneBricks(namespace, path)) {
            flags |= STONE_BRICKS;
        }
        if (PoiLexicon.isNetherBricks(namespace, path)) {
            flags |= NETHER_BRICKS;
        }
        if (PoiLexicon.isBlackstoneFamily(namespace, path)) {
            flags |= BLACKSTONE;
        }
        String habitationKey = PoiLexicon.habitationKey(namespace, path);
        if (habitationKey != null) {
            try {
                flags = withHabitation(flags, PoiSignals.Habitation.valueOf(habitationKey.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // A lexicon key without an enum constant is simply not a habitation marker here.
            }
        }
        return flags;
    }

    public static int withHabitation(int flags, PoiSignals.Habitation habitation) {
        int cleared = flags & ~HABITATION_MASK;
        return habitation == null ? cleared : cleared | ((habitation.ordinal() + 1) << HABITATION_SHIFT);
    }

    /** The habitation marker packed into {@code flags}, or null. */
    public static PoiSignals.Habitation habitation(int flags) {
        int code = (flags & HABITATION_MASK) >>> HABITATION_SHIFT;
        return code <= 0 || code > HABITATIONS.length ? null : HABITATIONS[code - 1];
    }

    public static boolean has(int flags, int flag) {
        return (flags & flag) != 0;
    }
}
