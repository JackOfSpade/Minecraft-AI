package io.github.zoyluo.minecraftai.mining.assist;

/**
 * Evidence buckets for point-of-interest scoring. One bucket per block class that hints at a
 * man-made or structure-generated place. Weights, per-bucket cell caps and strength come from the
 * mining-assist design (section 6.2). Pure data: no Minecraft registry access.
 *
 * <p>{@link #NATURAL} is a sentinel for "ordinary cave/terrain material": it carries no weight and
 * is never counted as evidence.</p>
 */
public enum PoiBucket {
    SPAWNER(Strength.STRONG, 2.0D, 2, 1),
    /** Strong once at least two container cells are seen. */
    CONTAINER(Strength.STRONG, 1.0D, 3, 2),
    SCULK_STRUCT(Strength.STRONG, 2.5D, 3, 1),
    /** Strong once at least four cells are seen. */
    DEEPSLATE_BUILD(Strength.STRONG, 1.0D, 6, 4),
    /** Strong once at least two rail cells are seen. */
    RAIL(Strength.STRONG, 0.7D, 4, 2),
    WEB(Strength.STRONG, 0.4D, 4, 1),
    STONE_BUILD(Strength.STRONG, 0.5D, 8, 1),
    COPPER_TUFF_BUILD(Strength.STRONG, 0.5D, 6, 1),
    /** Any non-vanilla, non-natural block. Strong once at least three cells are seen. */
    MODDED(Strength.STRONG, 0.4D, 4, 3),
    WOOD_BUILD(Strength.NORMAL, 0.5D, 8, 1),
    FURNISHING(Strength.NORMAL, 0.4D, 4, 1),
    FOSSIL_GEODE(Strength.NORMAL, 0.25D, 4, 1),
    UNCLASSIFIED(Strength.NORMAL, 0.25D, 4, 1),
    /** Typical residue of the bot's own mining; counts only beside other evidence. */
    LIGHT_DRESSING(Strength.WEAK, 0.5D, 3, 1),
    /** Typical residue of the bot's own mining; counts only beside other evidence. */
    COBBLE(Strength.WEAK, 0.1D, 10, 1),
    NATURAL(Strength.NATURAL, 0.0D, 0, 1);

    public enum Strength { STRONG, NORMAL, WEAK, NATURAL }

    private final Strength strength;
    private final double weight;
    private final int cap;
    private final int strongMinCells;

    PoiBucket(Strength strength, double weight, int cap, int strongMinCells) {
        this.strength = strength;
        this.weight = weight;
        this.cap = cap;
        this.strongMinCells = strongMinCells;
    }

    public Strength strength() {
        return strength;
    }

    /** Weight contributed per distinct cell (before the cap). */
    public double weight() {
        return weight;
    }

    /** At most this many cells of the bucket contribute to the score. */
    public int cap() {
        return cap;
    }

    /** A STRONG bucket only counts as strong evidence once it has this many distinct cells. */
    public int strongMinCells() {
        return strongMinCells;
    }

    public boolean isWeak() {
        return strength == Strength.WEAK;
    }

    public boolean isNatural() {
        return strength == Strength.NATURAL;
    }
}
