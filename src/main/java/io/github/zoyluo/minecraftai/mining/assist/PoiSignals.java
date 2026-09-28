package io.github.zoyluo.aibot.mining.assist;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable input snapshot for {@link PoiScorer} and {@link PoiLabeler}. Everything the scorer
 * needs arrives pre-digested by the caller (the POI window, the entity scan and the openness
 * ring); this class does no Minecraft registry access and holds no clock.
 *
 * <p>Contract for the caller:</p>
 * <ul>
 *   <li>Cells are <em>distinct</em> block positions already filtered of bot-placed edits (the
 *       {@code BotEdits} ledger) and of expired window entries. A position added twice, or under
 *       two buckets, is kept once under the first bucket. {@link PoiBucket#NATURAL} cells are
 *       ignored.</li>
 *   <li>The specific-block counts ({@link Builder#reinforcedDeepslate}, {@link Builder#sculkShrieker},
 *       {@link Builder#sculkCatalyst}, {@link Builder#sculkSensors}) exclude bot-placed blocks too. They
 *       are independent of the cell lists (plain {@code sculk} and {@code sculk_vein} are natural and
 *       never appear as cells, they are only counted in {@link Builder#sculkFamilyWithin12}).</li>
 *   <li>The {@link Habitation} flags and the labeler flags ({@link Builder#mossyStone} ...
 *       {@link Builder#blackstone}) also exclude bot-placed blocks, except {@code blackstone}, which is
 *       presence-only and may come from natural blocks. A bot-placed crafting table or furnace that
 *       reaches {@link Habitation} would wrongly downgrade a real structure to a "player base".</li>
 *   <li>Openness is passed as the finished cavern channel value {@code C} plus its validity, so the
 *       scorer is independent of the ring. {@link PoiScorer#cavernChannel} turns {@code f} and
 *       {@code upFree} into {@code C}.</li>
 * </ul>
 */
public final class PoiSignals {
    public static final String OVERWORLD = "minecraft:overworld";
    /** Live perception radius assumed when the caller does not say. */
    public static final double DEFAULT_PERCEPTION_RADIUS = 16.0D;

    /**
     * Player-base items for the habitation downgrade (design 6.3). The block ones are a subset of the
     * FURNISHING bucket, so they cannot be derived from bucket counts and must be flagged by the
     * caller. {@code ITEM_FRAME} and {@code ARMOR_STAND} come from the entity scan.
     */
    public enum Habitation {
        BED, CRAFTING_TABLE, FURNACE, BREWING_STAND, ENCHANTING_TABLE, BOOKSHELF, ANVIL,
        ITEM_FRAME, ARMOR_STAND
    }

    private static final PoiSignals EMPTY = builder().build();

    private final Map<PoiBucket, List<BlockPos>> cells;
    private final int totalCells;
    private final int reinforcedDeepslate;
    private final int sculkShrieker;
    private final int sculkCatalyst;
    private final int sculkSensors;
    private final int sculkFamilyWithin12;
    private final Set<Habitation> habitation;
    private final boolean mossyStone;
    private final boolean vault;
    private final boolean ironBars;
    private final boolean stoneBricks;
    private final boolean netherBricks;
    private final boolean blackstone;
    private final double entityScore;
    private final boolean wardenVisible;
    private final boolean opennessValid;
    private final double opennessC;
    private final double perceptionRadius;
    private final String dimensionId;
    private final boolean cavernDimensionEnabled;

    private PoiSignals(Builder b) {
        EnumMap<PoiBucket, List<BlockPos>> grouped = new EnumMap<>(PoiBucket.class);
        for (Map.Entry<Long, PoiBucket> e : b.owner.entrySet()) {
            grouped.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(BlockPos.fromLong(e.getKey()));
        }
        EnumMap<PoiBucket, List<BlockPos>> frozen = new EnumMap<>(PoiBucket.class);
        int total = 0;
        for (Map.Entry<PoiBucket, List<BlockPos>> e : grouped.entrySet()) {
            frozen.put(e.getKey(), Collections.unmodifiableList(e.getValue()));
            total += e.getValue().size();
        }
        this.cells = Collections.unmodifiableMap(frozen);
        this.totalCells = total;
        this.reinforcedDeepslate = b.reinforcedDeepslate;
        this.sculkShrieker = b.sculkShrieker;
        this.sculkCatalyst = b.sculkCatalyst;
        this.sculkSensors = b.sculkSensors;
        this.sculkFamilyWithin12 = b.sculkFamilyWithin12;
        this.habitation = Collections.unmodifiableSet(b.habitation.isEmpty()
                ? EnumSet.noneOf(Habitation.class) : EnumSet.copyOf(b.habitation));
        this.mossyStone = b.mossyStone;
        this.vault = b.vault;
        this.ironBars = b.ironBars;
        this.stoneBricks = b.stoneBricks;
        this.netherBricks = b.netherBricks;
        this.blackstone = b.blackstone;
        this.entityScore = b.entityScore;
        this.wardenVisible = b.wardenVisible;
        this.opennessValid = b.opennessValid;
        this.opennessC = b.opennessC;
        this.perceptionRadius = b.perceptionRadius;
        this.dimensionId = b.dimensionId;
        this.cavernDimensionEnabled = b.cavernDimensionOverride != null
                ? b.cavernDimensionOverride
                : b.cavernDimensions.contains(b.dimensionId);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** No evidence, no openness, overworld, default radius. */
    public static PoiSignals empty() {
        return EMPTY;
    }

    /** Distinct cells of the bucket, in insertion order; empty for an absent or NATURAL bucket. */
    public List<BlockPos> cells(PoiBucket bucket) {
        List<BlockPos> list = cells.get(bucket);
        return list == null ? List.of() : list;
    }

    public int cellCount(PoiBucket bucket) {
        List<BlockPos> list = cells.get(bucket);
        return list == null ? 0 : list.size();
    }

    /** Distinct cells over all buckets, weak ones included. */
    public int totalCells() {
        return totalCells;
    }

    /** Buckets that have at least one cell, weak ones included. */
    public Set<PoiBucket> presentBuckets() {
        return cells.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(EnumSet.copyOf(cells.keySet()));
    }

    public int reinforcedDeepslateCount() {
        return reinforcedDeepslate;
    }

    public int sculkShriekerCount() {
        return sculkShrieker;
    }

    public int sculkCatalystCount() {
        return sculkCatalyst;
    }

    /** {@code sculk_sensor} plus {@code calibrated_sculk_sensor}. */
    public int sculkSensorCount() {
        return sculkSensors;
    }

    /** Sculk-family cells (any sculk block, plain sculk and veins included) within 12 blocks. */
    public int sculkFamilyWithin12() {
        return sculkFamilyWithin12;
    }

    public Set<Habitation> habitation() {
        return habitation;
    }

    public boolean hasHabitationItem() {
        return !habitation.isEmpty();
    }

    /** {@code mossy_cobblestone} family (not mossy stone bricks, which stay in the stronghold). */
    public boolean mossyStone() {
        return mossyStone;
    }

    /** A {@code vault} block was seen. */
    public boolean vault() {
        return vault;
    }

    public boolean ironBars() {
        return ironBars;
    }

    /** The {@code stone_bricks} family (mossy, cracked and chiseled variants included). */
    public boolean stoneBricks() {
        return stoneBricks;
    }

    public boolean netherBricks() {
        return netherBricks;
    }

    /** Any blackstone-family block, natural or polished; presence only, so natural cells may set it. */
    public boolean blackstone() {
        return blackstone;
    }

    /** Raw entity score as supplied (non-negative); {@link PoiScorer} applies the 0.6 cap. */
    public double entityScore() {
        return entityScore;
    }

    public boolean wardenVisible() {
        return wardenVisible;
    }

    public boolean opennessValid() {
        return opennessValid;
    }

    /** Cavern channel {@code C} in [0, 1] before dimension and radius gating; 0 when invalid. */
    public double opennessC() {
        return opennessC;
    }

    public double perceptionRadius() {
        return perceptionRadius;
    }

    public String dimensionId() {
        return dimensionId;
    }

    /** True when the current dimension is one of {@code poi.cavernDimensions}. */
    public boolean cavernDimensionEnabled() {
        return cavernDimensionEnabled;
    }

    /** Mutable builder; not thread-safe, single use per snapshot. */
    public static final class Builder {
        private final Map<Long, PoiBucket> owner = new LinkedHashMap<>();
        private int reinforcedDeepslate;
        private int sculkShrieker;
        private int sculkCatalyst;
        private int sculkSensors;
        private int sculkFamilyWithin12;
        private final Set<Habitation> habitation = EnumSet.noneOf(Habitation.class);
        private boolean mossyStone;
        private boolean vault;
        private boolean ironBars;
        private boolean stoneBricks;
        private boolean netherBricks;
        private boolean blackstone;
        private double entityScore;
        private boolean wardenVisible;
        private boolean opennessValid;
        private double opennessC;
        private double perceptionRadius = DEFAULT_PERCEPTION_RADIUS;
        private String dimensionId = OVERWORLD;
        private Set<String> cavernDimensions = Set.of(OVERWORLD);
        private Boolean cavernDimensionOverride;

        private Builder() {
        }

        /** Adds one evidence cell. First bucket wins for a repeated position; NATURAL is ignored. */
        public Builder cell(PoiBucket bucket, BlockPos pos) {
            Objects.requireNonNull(bucket, "bucket");
            Objects.requireNonNull(pos, "pos");
            if (!bucket.isNatural()) {
                owner.putIfAbsent(pos.asLong(), bucket);
            }
            return this;
        }

        public Builder cells(PoiBucket bucket, Iterable<BlockPos> positions) {
            Objects.requireNonNull(positions, "positions");
            for (BlockPos pos : positions) {
                cell(bucket, pos);
            }
            return this;
        }

        public Builder reinforcedDeepslate(int count) {
            this.reinforcedDeepslate = Math.max(0, count);
            return this;
        }

        public Builder sculkShrieker(int count) {
            this.sculkShrieker = Math.max(0, count);
            return this;
        }

        public Builder sculkCatalyst(int count) {
            this.sculkCatalyst = Math.max(0, count);
            return this;
        }

        /** Combined count of {@code sculk_sensor} and {@code calibrated_sculk_sensor}. */
        public Builder sculkSensors(int count) {
            this.sculkSensors = Math.max(0, count);
            return this;
        }

        public Builder sculkFamilyWithin12(int count) {
            this.sculkFamilyWithin12 = Math.max(0, count);
            return this;
        }

        public Builder habitation(Habitation item) {
            habitation.add(Objects.requireNonNull(item, "item"));
            return this;
        }

        public Builder mossyStone(boolean value) {
            this.mossyStone = value;
            return this;
        }

        public Builder vault(boolean value) {
            this.vault = value;
            return this;
        }

        public Builder ironBars(boolean value) {
            this.ironBars = value;
            return this;
        }

        public Builder stoneBricks(boolean value) {
            this.stoneBricks = value;
            return this;
        }

        public Builder netherBricks(boolean value) {
            this.netherBricks = value;
            return this;
        }

        public Builder blackstone(boolean value) {
            this.blackstone = value;
            return this;
        }

        /** Entity score E; NaN and negatives become 0. The scorer caps it at 0.6. */
        public Builder entityScore(double value) {
            this.entityScore = Double.isNaN(value) ? 0.0D : Math.max(0.0D, value);
            return this;
        }

        public Builder wardenVisible(boolean value) {
            this.wardenVisible = value;
            return this;
        }

        /** Precomputed cavern channel {@code C}; marks openness valid. Clamped to [0, 1]. */
        public Builder openness(double c) {
            this.opennessValid = true;
            this.opennessC = clamp01(c);
            return this;
        }

        /** Convenience: derives {@code C} from the free fraction {@code f} and {@code upFree}. */
        public Builder openness(double f, double upFree) {
            return openness(PoiScorer.cavernChannel(f, upFree));
        }

        /** Fewer than 640 valid ring entries: no cavern signal. This is the default. */
        public Builder opennessUnavailable() {
            this.opennessValid = false;
            this.opennessC = 0.0D;
            return this;
        }

        public Builder perceptionRadius(double radius) {
            this.perceptionRadius = Double.isNaN(radius) ? 0.0D : radius;
            return this;
        }

        public Builder dimensionId(String id) {
            this.dimensionId = Objects.requireNonNull(id, "id");
            return this;
        }

        /** The {@code poi.cavernDimensions} config; the channel is enabled iff the dimension is listed. */
        public Builder cavernDimensions(Collection<String> ids) {
            this.cavernDimensions = new HashSet<>(Objects.requireNonNull(ids, "ids"));
            return this;
        }

        /** Explicit override of the derived dimension gate; wins over {@link #cavernDimensions}. */
        public Builder cavernDimensionEnabled(boolean enabled) {
            this.cavernDimensionOverride = enabled;
            return this;
        }

        public PoiSignals build() {
            return new PoiSignals(this);
        }

        private static double clamp01(double v) {
            if (Double.isNaN(v)) {
                return 0.0D;
            }
            return Math.max(0.0D, Math.min(1.0D, v));
        }
    }
}
