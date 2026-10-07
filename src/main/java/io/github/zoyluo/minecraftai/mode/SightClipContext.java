package io.github.zoyluo.minecraftai.mode;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.RandomAccess;
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
 * <p><b>Sight is not reach.</b> Seeing a block behind a leaf does not let a hand reach it. A context built with
 * {@link #trackObstructions()} therefore also reports what a vanilla pick ray along the same segment would hit among the skipped
 * blocks: {@link #obstructions()}. That is modelled with the block's {@link ClipContext.Block#OUTLINE outline} shape, whatever
 * shape kind the sight ray uses, because {@code Entity.pick} (the player's crosshair, and so every click) is an OUTLINE ray with
 * {@link ClipContext.Fluid#NONE}: a fence's collision arms are solid from the ground up but its outline rails are not, so a click
 * line can slip through the gaps of a fence whose collision a sight ray cannot. Water is never an obstruction because the pick
 * ray ignores fluids. Blocks the pick ray hits but this ray never needed to skip (a flower under a COLLIDER ray) are listed too:
 * the click would hit them. Tracking costs a shape lookup and a clip per skipped block, so only the reach proofs
 * ({@link ReachObstructions}) ask for it.</p>
 *
 * <p>One context per ray: it records what the ray crossed and is not reusable.</p>
 */
public final class SightClipContext extends ClipContext {
    /** A cell a ray crossed, with the state it really holds (never air for a leaf, a fence or water). */
    public record Crossing(BlockPos pos, BlockState state) {
    }

    /**
     * The cells a ray skipped, nearest the eye first, read-only. A recorder asks it for the state of every cell it walks, so a
     * long ray through water (a cell each) is answered from an index instead of a scan per cell.
     */
    public static final class Crossings extends AbstractList<Crossing> implements RandomAccess {
        /** Below this many cells a scan is cheaper than building the index. */
        private static final int INDEXED_FROM = 8;

        private final ArrayList<Crossing> cells = new ArrayList<>();
        private Long2ObjectOpenHashMap<BlockState> index;

        @Override
        public Crossing get(int i) {
            return cells.get(i);
        }

        @Override
        public int size() {
            return cells.size();
        }

        /** The state recorded for {@code packedPos} ({@link BlockPos#asLong()}), or {@code null} when the ray did not skip it. */
        public BlockState stateAt(long packedPos) {
            if (cells.size() < INDEXED_FROM) {
                for (Crossing crossing : cells) {
                    if (crossing.pos().asLong() == packedPos) {
                        return crossing.state();
                    }
                }
                return null;
            }
            if (index == null) {
                index = new Long2ObjectOpenHashMap<>(cells.size() * 2);
                for (Crossing crossing : cells) {
                    index.put(crossing.pos().asLong(), crossing.state());
                }
            }
            return index.get(packedPos);
        }

        private boolean endsWith(long packedPos) {
            return !cells.isEmpty() && cells.get(cells.size() - 1).pos().asLong() == packedPos;
        }

        private void append(Crossing crossing) {
            cells.add(crossing);
            if (index != null) {
                index.put(crossing.pos().asLong(), crossing.state());
            }
        }
    }

    private static final Crossings NO_CROSSINGS = new Crossings();

    private final ClipContext.Block shapeKind;
    private final CollisionContext collisionContext;
    private final boolean hasTarget;
    private final long target;
    private final boolean hasStanding;
    private final long standing;
    private final boolean recordCrossed;
    private boolean trackObstructions;
    private List<Crossing> obstructions;
    private Crossings crossed;

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

    /**
     * Asks this ray to report {@link #obstructions()} and {@link #reachObstructed()}. Off by default: it is a pick-shape lookup
     * and clip per skipped block, which only a proof that a hand can reach something needs.
     */
    public SightClipContext trackObstructions() {
        this.trackObstructions = true;
        return this;
    }

    @Override
    public VoxelShape getBlockShape(BlockState state, BlockGetter level, BlockPos pos) {
        VoxelShape shape = super.getBlockShape(state, level, pos);
        if (state.isAir() || isTarget(pos) || !SeeThrough.cell(state)) {
            return shape;
        }
        if (trackObstructions) {
            VoxelShape pick = shapeKind == ClipContext.Block.OUTLINE
                    ? shape : ClipContext.Block.OUTLINE.get(state, level, pos, collisionContext);
            if (!pick.isEmpty() && pick.clip(getFrom(), getTo(), pos) != null) {
                if (obstructions == null) {
                    obstructions = new ArrayList<>(2);
                }
                obstructions.add(new Crossing(pos.immutable(), state));
            }
        }
        // Recorded whether or not this ray's own shape of the cell is empty: a collider ray walks through fire, a cobweb or a
        // berry bush unaided, yet the cell holds them and a recorder must not store air there.
        recordCrossed(pos, state);
        return Shapes.empty();
    }

    @Override
    public VoxelShape getFluidShape(FluidState fluid, BlockGetter level, BlockPos pos) {
        if (fluid.isEmpty()) {
            return Shapes.empty();
        }
        if (!fluid.is(FluidTags.WATER)) {
            if (hasStanding && pos.asLong() == standing && !isTarget(pos)) {
                // Skipped, but still the lava it is: a recorder keeps the hazard under the bot's own feet.
                if (recordCrossed) {
                    recordCrossed(pos, level.getBlockState(pos));
                }
                return Shapes.empty();
            }
            return fluid.getShape(level, pos);
        }
        if (isTarget(pos)) {
            return super.getFluidShape(fluid, level, pos);
        }
        if (recordCrossed) {
            recordCrossed(pos, level.getBlockState(pos));
        }
        return Shapes.empty();
    }

    /** Whether a skipped see-through block's pick-ray shape lies on the segment: a hand could not reach past it. Needs {@link #trackObstructions()}. */
    public boolean reachObstructed() {
        return obstructions != null;
    }

    /**
     * The see-through blocks a vanilla pick ray along this segment would hit, nearest the eye first, with their real states:
     * only blocks whose outline the segment actually crosses (a ray slipping through a fence gap crosses none), never water.
     * Empty for a ray that met none, and for a context that was not asked to {@link #trackObstructions()}. Only blocks before
     * the ray's hit are listed, because the traversal stops there.
     */
    public List<Crossing> obstructions() {
        return obstructions == null ? List.of() : Collections.unmodifiableList(obstructions);
    }

    /**
     * Every cell the ray skipped, nearest the eye first, with the state it really holds, so a recorder can remember a leaf, a
     * fence, a plant or water as what it is and never as air. Cells whose block shape the segment merely passes beside are
     * included: the cell is still foliage. Empty unless the context was built with {@code recordCrossed}.
     */
    public Crossings crossed() {
        return crossed == null ? NO_CROSSINGS : crossed;
    }

    private boolean isTarget(BlockPos pos) {
        return hasTarget && pos.asLong() == target;
    }

    private void recordCrossed(BlockPos pos, BlockState state) {
        if (!recordCrossed) {
            return;
        }
        if (crossed == null) {
            crossed = new Crossings();
        } else if (crossed.endsWith(pos.asLong())) {
            return; // the block hook already recorded this cell (a waterlogged fence): the fluid hook runs right after it
        }
        crossed.append(new Crossing(pos.immutable(), state));
    }
}
