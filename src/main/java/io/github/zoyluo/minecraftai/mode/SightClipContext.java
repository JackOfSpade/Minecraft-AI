package io.github.zoyluo.minecraftai.mode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A vanilla {@link ClipContext} whose rays pass through {@linkplain SeeThrough see-through blocks} and water, so
 * {@code level.clip(context)} returns the first thing a bot's <em>eyes</em> stop at. Vanilla's own traversal, nearest-hit rule,
 * interaction-shape override and miss result do all the work: the only two per-cell hooks it offers, {@link #getBlockShape} and
 * {@link #getFluidShape}, answer an empty shape for what is skipped and the unchanged vanilla shape for everything else. A ray
 * that crosses no see-through block, no water and no lava is therefore identical to the plain {@code ClipContext} with the same
 * arguments.
 *
 * <p><b>What is skipped.</b> A block {@link SeeThrough#cell(BlockState) the predicate} accepts, in the shape kind the ray was
 * built with, and water. Lava (and any other non-water fluid) is never skipped and keeps its real fluid shape even when the ray
 * asked for {@link ClipContext.Fluid#NONE}: eyes do not see through lava. The one exception is the cell the observer stands in:
 * its eye is above the surface of the lava it wades in, and that lava must not hide the ground around it from a bot that is
 * trying to climb out (an eye inside the lava itself stays blind). A waterlogged slab keeps its slab shape, only its water is
 * skipped.</p>
 *
 * <p><b>The target.</b> The cell being observed (a block, or a water cell for a water proof) is never skipped, so asking whether
 * a leaf, a fence or a water cell itself is visible still returns that cell (a water cell only when the ray's fluid kind picks
 * water, as in vanilla). Entity and point rays pass no target. If the eye is inside a see-through cell that cell is skipped as
 * well, so there is no {@code inside} hit at the start.</p>
 *
 * <p><b>Sight is not reach.</b> Seeing a block behind a leaf does not let a hand reach it. The context therefore also reports
 * what a vanilla pick ray along the same segment would hit among the skipped blocks: {@link #obstructions()}. That is modelled
 * with the block's {@link ClipContext.Block#OUTLINE outline} shape, whatever shape kind the sight ray uses, because
 * {@code Entity.pick} (the player's crosshair, and so every click) is an OUTLINE ray with {@link ClipContext.Fluid#NONE}: a
 * fence's collision arms are solid from the ground up but its outline rails are not, so a click line can slip through the gaps of
 * a fence whose collision a sight ray cannot. Water is never an obstruction because the pick ray ignores fluids. Blocks the
 * pick ray hits but this ray never needed to skip (a flower under a COLLIDER ray) are listed too: the click would hit them.</p>
 *
 * <p>One context per ray: it records what the ray crossed and is not reusable.</p>
 */
public final class SightClipContext extends ClipContext {
    /** A cell a ray crossed, with the state it really holds (never air for a leaf, a fence or water). */
    public record Crossing(BlockPos pos, BlockState state) {
    }

    private final ClipContext.Block shapeKind;
    private final CollisionContext collisionContext;
    private final boolean hasTarget;
    private final long target;
    private final boolean hasStanding;
    private final long standing;
    private final boolean recordCrossed;
    private List<Crossing> obstructions;
    private List<Crossing> crossed;

    /**
     * @param target the cell being observed, never skipped, or {@code null} for an entity or point ray
     * @param recordCrossed also record every skipped cell with its real state ({@link #crossed()}), for a caller that stores
     *                      what the ray passed through
     */
    public SightClipContext(Vec3 from, Vec3 to, ClipContext.Block shape, ClipContext.Fluid fluid,
                            CollisionContext collisionContext, BlockPos target, boolean recordCrossed) {
        this(from, to, shape, fluid, collisionContext, target, recordCrossed, null);
    }

    public SightClipContext(Vec3 from, Vec3 to, ClipContext.Block shape, ClipContext.Fluid fluid, Entity observer,
                            BlockPos target, boolean recordCrossed) {
        this(from, to, shape, fluid, CollisionContext.of(observer), target, recordCrossed, observer.blockPosition());
    }

    /**
     * @param standing the cell the observer stands in, or {@code null}: the lava it wades in does not hide the ground around
     *                 it (see the class comment), unless its eye is in that very cell
     */
    public SightClipContext(Vec3 from, Vec3 to, ClipContext.Block shape, ClipContext.Fluid fluid,
                            CollisionContext collisionContext, BlockPos target, boolean recordCrossed, BlockPos standing) {
        super(from, to, shape, fluid, collisionContext);
        this.shapeKind = shape;
        this.collisionContext = collisionContext;
        this.hasTarget = target != null;
        this.target = target == null ? 0L : target.asLong();
        this.hasStanding = standing != null && !BlockPos.containing(from).equals(standing);
        this.standing = standing == null ? 0L : standing.asLong();
        this.recordCrossed = recordCrossed;
    }

    @Override
    public VoxelShape getBlockShape(BlockState state, BlockGetter level, BlockPos pos) {
        VoxelShape shape = super.getBlockShape(state, level, pos);
        if (state.isAir() || isTarget(pos) || !SeeThrough.cell(state)) {
            return shape;
        }
        VoxelShape pick = shapeKind == ClipContext.Block.OUTLINE
                ? shape : ClipContext.Block.OUTLINE.get(state, level, pos, collisionContext);
        if (!pick.isEmpty() && pick.clip(getFrom(), getTo(), pos) != null) {
            if (obstructions == null) {
                obstructions = new ArrayList<>(2);
            }
            obstructions.add(new Crossing(pos.immutable(), state));
        }
        if (shape.isEmpty()) {
            return shape;
        }
        recordCrossed(pos, state);
        return Shapes.empty();
    }

    @Override
    public VoxelShape getFluidShape(FluidState fluid, BlockGetter level, BlockPos pos) {
        if (fluid.isEmpty()) {
            return Shapes.empty();
        }
        if (!fluid.is(FluidTags.WATER)) {
            return hasStanding && pos.asLong() == standing && !isTarget(pos) ? Shapes.empty() : fluid.getShape(level, pos);
        }
        if (isTarget(pos)) {
            return super.getFluidShape(fluid, level, pos);
        }
        if (recordCrossed) {
            recordCrossed(pos, level.getBlockState(pos));
        }
        return Shapes.empty();
    }

    /** Whether a skipped see-through block's pick-ray shape lies on the segment: a hand could not reach past it. */
    public boolean reachObstructed() {
        return obstructions != null;
    }

    /**
     * The see-through blocks a vanilla pick ray along this segment would hit, nearest the eye first, with their real states:
     * only blocks whose outline the segment actually crosses (a ray slipping through a fence gap crosses none), never water.
     * Empty for a ray that met none. Only blocks before the ray's hit are listed, because the traversal stops there.
     */
    public List<Crossing> obstructions() {
        return obstructions == null ? List.of() : Collections.unmodifiableList(obstructions);
    }

    /**
     * Every cell the ray skipped, nearest the eye first, with the state it really holds, so a recorder can remember a leaf, a
     * fence or water as what it is and never as air. Cells whose block shape the segment merely passes beside are included:
     * the cell is still foliage. Empty unless the context was built with {@code recordCrossed}.
     */
    public List<Crossing> crossed() {
        return crossed == null ? List.of() : Collections.unmodifiableList(crossed);
    }

    private boolean isTarget(BlockPos pos) {
        return hasTarget && pos.asLong() == target;
    }

    private void recordCrossed(BlockPos pos, BlockState state) {
        if (!recordCrossed) {
            return;
        }
        if (crossed == null) {
            crossed = new ArrayList<>();
        } else if (crossed.get(crossed.size() - 1).pos().asLong() == pos.asLong()) {
            return; // the block hook already recorded this cell (a waterlogged fence): the fluid hook runs right after it
        }
        crossed.add(new Crossing(pos.immutable(), state));
    }
}
