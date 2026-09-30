package io.github.zoyluo.minecraftai.navigation;

import net.minecraft.core.BlockPos;

/**
 * One walk request that the Baritone engine is executing for a bot: what was asked, what the bot may do to get there, and
 * the bookkeeping {@code ActionPack} needs to report how it ended. Pure data (no {@code baritone.*} types), so it lives in
 * {@code ActionPack} without loading Baritone.
 *
 * <p>{@link Shape#BLOCK} is "stand in this cell" (a {@code GoalBlock}, or a {@code GoalNear} of radius 1 when the cell itself
 * cannot be stood in, which is what the legacy goal resolution did by moving the goal to the nearest standable cell);
 * {@link Shape#NEAR} is "get within {@code radius} blocks of this cell" (a {@code GoalNear}; follow, approach).</p>
 */
public final class NavRoute {
    public enum Shape {
        BLOCK,
        NEAR,
        /** Get at least {@code radius} blocks (horizontally) away from the target cell: a {@code GoalRunAway} (retreat, evade). */
        RUN_AWAY
    }

    /** Where a route stands: Baritone still drives it, it arrived, Baritone let go of the bot short of the goal, or it keeps being vetoed. */
    public enum Progress {
        RUNNING,
        ARRIVED,
        ENDED_SHORT,
        /** Baritone still drives it, but the strict-survival rules have refused its breaks/placements too many times in a row. */
        POLICY_REFUSED
    }

    /**
     * What the bot may do on the way. Breaking and placing are Baritone's own last-resort moves (its cost model always prefers
     * walking around); they still go through the mod's {@code MiningController}/{@code BuildAction}. Water: a dry route never
     * enters water (the follow rule "stay on the bank"); a swim route may, and is leased against the drowning safety net.
     */
    public record Options(boolean allowBreak, boolean allowPlace, boolean allowWater) {
        /** Walk, climb and open doors only, on dry ground. */
        public static final Options WALK_ONLY = new Options(false, false, false);
        /** Water crossing allowed, no breaking, no placing. */
        public static final Options SWIM = new Options(false, false, true);

        public Options withWater(boolean water) {
            return water == allowWater ? this : new Options(allowBreak, allowPlace, water);
        }
    }

    private final Shape shape;
    private final BlockPos target;
    private final int radius;
    private final Options options;
    private final String label;
    private final int startTick;
    private int deadlineTick;
    private BlockPos resolvedGoal;
    private Object goalHandle;

    public NavRoute(Shape shape, BlockPos target, int radius, Options options, String label, int startTick) {
        this.shape = shape;
        this.target = target.immutable();
        this.radius = Math.max(0, radius);
        this.options = options;
        this.label = label;
        this.startTick = startTick;
        this.deadlineTick = startTick + 600;
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
