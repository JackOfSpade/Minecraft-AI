package io.github.zoyluo.minecraftai.navigation;

import java.util.UUID;
import net.minecraft.core.BlockPos;

/**
 * One walk request that the Baritone engine is executing for a bot: what was asked, what the bot may do to get there, and
 * the bookkeeping {@code ActionPack} needs to report how it ended. Pure data (no {@code baritone.*} types), so it lives in
 * {@code ActionPack} without loading Baritone.
 *
 * <p>{@link Shape#BLOCK} is "stand in this cell" (a {@code GoalBlock}, or a {@code GoalNear} of radius 1 when the cell itself
 * cannot be stood in, which is why Baritone resolves the request to a proven nearest standable cell);
 * {@link Shape#NEAR} is "get within {@code radius} blocks of this cell" (a {@code GoalNear}; follow, approach).
 * {@link Shape#DIRECTIONAL_PURSUIT} keeps an unobserved remote coordinate only as a heading: admission chooses a nearby,
 * observed stance as its actual {@code GoalBlock} and never makes the remote terrain part of the route. {@link Shape#OWNER_FOLLOW}
 * is the one deliberate exception: it may route toward the live coordinate of the bot's verified owner over a snapshot of currently
 * loaded chunks, but it is always walk-only and cannot name an arbitrary coordinate as an unrestricted goal.</p>
 */
public final class NavRoute {
    public enum Shape {
        BLOCK,
        NEAR,
        /**
         * A bounded follow/search hop toward a known remote coordinate. The remote target is not
         * a terrain goal: the observation fence resolves it to a nearby visible stance before
         * Baritone receives any path or movement authority.
         */
        DIRECTIONAL_PURSUIT,
        /**
         * A live owner-coordinate {@code GoalNear}. The navigator validates its immutable owner UUID before admission and exposes
         * only a server-captured snapshot of currently full chunks, never a cache or a force-loaded chunk.
         */
        OWNER_FOLLOW,
        /**
         * Get at least {@code radius} blocks (horizontally) away from the target/source cell: a
         * {@code GoalRunAway} (retreat, evade). The target is a threat reference, not an arrival
         * destination, so callers must not report it as an active path goal.
         */
        RUN_AWAY
    }

    /** Where a route stands: Baritone still drives it, it arrived, Baritone let go of the bot short of the goal, or it keeps being vetoed. */
    public enum Progress {
        RUNNING,
        ARRIVED,
        ENDED_SHORT,
        /** A target remembered from an earlier view could not be visibly re-proven on arrival. */
        OBSERVATION_LOST,
        /** Baritone still drives it, but the strict-survival rules have refused its breaks/placements too many times in a row. */
        POLICY_REFUSED
    }

    /**
     * What the bot may do on the way. Breaking and placing are Baritone's own last-resort moves (its cost model always prefers
     * walking around); they still go through the mod's {@code MiningController}/{@code BuildAction}. Water traversal is independent
     * from goal resolution: a route may cross water on the way to its normal observed dry stance, while only an explicit swim
     * request is satisfied in the actual observed water/shore cell and is leased against the drowning safety net.
     */
    public record Options(boolean allowBreak, boolean allowPlace, boolean allowWater, boolean exactWaterGoal) {
        /** Source-compatible ordinary route constructor: water remains a traversal permission, never an exact goal. */
        public Options(boolean allowBreak, boolean allowPlace, boolean allowWater) {
            this(allowBreak, allowPlace, allowWater, false);
        }

        /** An exact water/shore goal cannot be meaningful on a route forbidden from entering water. */
        public Options {
            if (exactWaterGoal && !allowWater) {
                throw new IllegalArgumentException("an exact water goal requires water traversal");
            }
        }

        /** Walk, climb and open doors only, on dry ground. */
        public static final Options WALK_ONLY = new Options(false, false, false, false);
        /** Water crossing allowed, no breaking or placing, while resolving an ordinary observed dry stance at the destination. */
        public static final Options SWIM = new Options(false, false, true, false);
        /** An internal/exact-water form for a route whose destination itself is an observed water or shore cell. */
        public static final Options EXACT_SWIM = new Options(false, false, true, true);

        public Options withWater(boolean water) {
            return water == allowWater ? this : new Options(allowBreak, allowPlace, water, exactWaterGoal && water);
        }
    }

    private final Shape shape;
    private final BlockPos target;
    private final int radius;
    private final Options options;
    private final String label;
    private final int startTick;
    /** The only player whose live coordinate may use {@link Shape#OWNER_FOLLOW}; null for every ordinary route. */
    private final UUID ownerUuid;
    /** A constrained surface route may never use a cell below this floor. */
    private final int minimumY;
    /** Optional observed-only return proof anchor for a constrained route. */
    private final BlockPos returnAnchor;
    /** Set by the observation admission boundary; remembered targets require a live proof before arrival. */
    private boolean revalidateRememberedTarget;
    /**
     * Set only by observation admission for a visibly proven vertical placement column. The
     * ordinary BLOCK form resolves to an already standable cell; this narrowly permits a
     * Baritone pillar plan to create that footing from cells the bot has actually seen.
     */
    private boolean observedPillarGoal;
    /**
     * True only for the explicit, no-dig pillar entry point.  Ordinary placement-capable routes
     * deliberately leave this false: they retain their existing bridge/terrain behaviour.
     */
    private boolean pillarPlacementColumnRequired;
    /**
     * The exact range a no-dig pillar route may fill. It stays null until admission has proved
     * the column from a real base through the requested goal; null is therefore fail-closed for
     * an explicit pillar request.
     */
    private PillarPlacementColumn pillarPlacementColumn;
    private int deadlineTick;
    private BlockPos resolvedGoal;
    private Object goalHandle;

    public NavRoute(Shape shape, BlockPos target, int radius, Options options, String label, int startTick) {
        this(shape, target, radius, options, label, startTick, Integer.MIN_VALUE, null, null);
    }

    public NavRoute(Shape shape, BlockPos target, int radius, Options options, String label, int startTick,
                    int minimumY, BlockPos returnAnchor) {
        this(shape, target, radius, options, label, startTick, minimumY, returnAnchor, null);
    }

    private NavRoute(Shape shape, BlockPos target, int radius, Options options, String label, int startTick,
                     int minimumY, BlockPos returnAnchor, UUID ownerUuid) {
        this.shape = shape;
        this.target = target.immutable();
        this.radius = Math.max(0, radius);
        this.options = options;
        this.label = label;
        this.startTick = startTick;
        this.minimumY = minimumY;
        this.returnAnchor = returnAnchor == null ? null : returnAnchor.immutable();
        this.ownerUuid = ownerUuid;
        this.deadlineTick = startTick + 600;
    }

    /**
     * Creates the sole route form that can use a known-but-not-visible coordinate. Callers still cannot bypass the navigator's
     * owner check: {@code BaritoneNavigator} compares this UUID against the bot's current owner before it exposes a chunk snapshot.
     */
    public static NavRoute ownerFollow(UUID ownerUuid, BlockPos target, int radius, String label, int startTick) {
        if (ownerUuid == null) {
            throw new IllegalArgumentException("ownerUuid is required for owner follow");
        }
        return new NavRoute(Shape.OWNER_FOLLOW, target, radius, Options.WALK_ONLY, label, startTick,
                Integer.MIN_VALUE, null, ownerUuid);
    }

    public Shape shape() {
        return shape;
    }

    public BlockPos target() {
        return target;
    }

    public int radius() {
        return radius;
    }

    public Options options() {
        return options;
    }

    /** The request kind for the logs: {@code path_to}, {@code surface_path_to}, {@code approach}, ... */
    public String label() {
        return label;
    }

    public int startTick() {
        return startTick;
    }

    /** The verified-owner identity carried by an {@link Shape#OWNER_FOLLOW} request, otherwise null. */
    public UUID ownerUuid() {
        return ownerUuid;
    }

    /** The inclusive Y floor enforced by the observation fence, or {@link Integer#MIN_VALUE} when unconstrained. */
    public int minimumY() {
        return minimumY;
    }

    /** The optional surface-route anchor that must have an all-observed return corridor before admission. */
    public BlockPos returnAnchor() {
        return returnAnchor;
    }

    public boolean revalidateRememberedTarget() {
        return revalidateRememberedTarget;
    }

    public void setRevalidateRememberedTarget(boolean revalidateRememberedTarget) {
        this.revalidateRememberedTarget = revalidateRememberedTarget;
    }

    /** Whether admission proved an all-observed, vertical placement column for this BLOCK goal. */
    public boolean observedPillarGoal() {
        return observedPillarGoal;
    }

    public void setObservedPillarGoal(boolean observedPillarGoal) {
        this.observedPillarGoal = observedPillarGoal;
    }

    /** Marks this route as the one explicit construction form that may use only its proven pillar column. */
    public void requirePillarPlacementColumn() {
        pillarPlacementColumnRequired = true;
    }

    /** Whether execution must reject any placement outside the admitted vertical pillar column. */
    public boolean requiresPillarPlacementColumn() {
        return pillarPlacementColumnRequired;
    }

    /** The admitted column, or null when a no-dig pillar route has no current column proof. */
    public PillarPlacementColumn pillarPlacementColumn() {
        return pillarPlacementColumn;
    }

    /**
     * Publishes the bottom of the exact column admission proved. The only legal destinations
     * are the initially empty cells from that base through the cell immediately below this
     * route's requested feet goal; the goal itself and its headroom stay clear for the body.
     */
    public void setPillarPlacementColumn(BlockPos base) {
        if (!pillarPlacementColumnRequired || base == null || target.getY() <= base.getY()) {
            pillarPlacementColumn = null;
            return;
        }
        pillarPlacementColumn = new PillarPlacementColumn(base, target.getY() - 1);
    }

    /** Immutable, exact X/Z and Y-range permit for one visibly proven pillar. */
    public record PillarPlacementColumn(BlockPos base, int lastPlacementY) {
        public PillarPlacementColumn {
            base = java.util.Objects.requireNonNull(base, "base").immutable();
            if (lastPlacementY < base.getY()) {
                throw new IllegalArgumentException("pillar placement range is empty");
            }
        }

        /** Whether this exact placement destination belongs to the vertical column that was proved on admission. */
        public boolean allows(BlockPos destination) {
            return destination != null
                    && destination.getX() == base.getX()
                    && destination.getZ() == base.getZ()
                    && destination.getY() >= base.getY()
                    && destination.getY() <= lastPlacementY;
        }
    }

    /** The tick after which a route that is still running is abandoned with {@code path_timeout}. */
    public int deadlineTick() {
        return deadlineTick;
    }

    public void setDeadlineTick(int deadlineTick) {
        this.deadlineTick = deadlineTick;
    }

    /** The cell the route ends in when the search reached the goal ({@code null} until then, or for a partial path). */
    public BlockPos resolvedGoal() {
        return resolvedGoal;
    }

    public void setResolvedGoal(BlockPos resolvedGoal) {
        this.resolvedGoal = resolvedGoal == null ? null : resolvedGoal.immutable();
    }

    /** Opaque slot for the engine (the Baritone goal object); the seam in {@code ActionPack} never looks inside. */
    public Object goalHandle() {
        return goalHandle;
    }

    public void setGoalHandle(Object goalHandle) {
        this.goalHandle = goalHandle;
    }

    @Override
    public String toString() {
        return label + "[" + shape + " " + target.toShortString() + (shape == Shape.BLOCK ? "" : " r=" + radius) + "]";
    }
}
