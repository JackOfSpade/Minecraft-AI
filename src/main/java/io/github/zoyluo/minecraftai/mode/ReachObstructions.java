package io.github.zoyluo.minecraftai.mode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * What stands between a hand and a block the eyes can see: the {@linkplain SeeThrough see-through} blocks that the strict (plain
 * vanilla) proof of a break meets first. A bot that sees a log through two leaves cannot break it until they are gone, and this
 * says which ones, per line of aim.
 *
 * <p>The lines are the ones the strict proofs aim ({@link ObservableWorldQuery#canObserveBlockStrict} and its inset form): nine
 * points on each of the six faces of the target's own shape, {@link FaceAim} style, each tested with the shape kind that proof
 * tests it with. A line is traced with the same plain vanilla ray as the strict gate: wherever the gate would stop at a leaf, a
 * fence or a pane, the stopping block is recorded and the trace goes on as if it were broken, so that what is left on a line is
 * exactly what must disappear for the strict gate to pass along it. That is why this is not the obstruction list of the sight
 * context, which models the player's crosshair ray (an {@code OUTLINE} ray) and so lists a flower the strict gate walks through
 * and misses a fence whose collision arms stop the strict ray above and between the rails.</p>
 *
 * <p>A line is dead, and not returned, when anything else stops its ray: an opaque block, lava, any fluid (breaking a block never
 * removes water, and a waterlogged block leaves its water behind), or the target itself on a face other than the one aimed at.
 * Each recorded block is a distinct cell, so a trace always ends.</p>
 */
public final class ReachObstructions {
    private ReachObstructions() {
    }

    /** A see-through block in front of the target, with the state it holds. */
    public record Obstruction(BlockPos pos, BlockState state) {
    }

    /**
     * One aim point on a face of the target.
     *
     * @param obstructions the see-through blocks a strict ray to {@code aim} meets before the target, nearest the eye first; empty
     *                     when the line is already clear
     */
    public record Line(Direction face, Vec3 aim, List<Obstruction> obstructions) {
    }

    /**
     * Every line from {@code eye} to a face of the block at {@code target} that only see-through blocks hide, in the fixed order of
     * the strict proofs (faces, then the 3x3 grid). Lines whose ray ends at an opaque block or a fluid are left out.
     */
    public static List<Line> lines(BlockGetter level, CollisionContext context, Vec3 eye, BlockPos target) {
        BlockState state = level.getBlockState(target);
        FaceAim.Target aim = FaceAim.aim(level, target, state, ClipContext.Block.COLLIDER, context, true);
        List<Line> lines = new ArrayList<>();
        for (Direction face : Direction.values()) {
            for (double[] offset : ObservableWorldQuery.FACE_SAMPLE_OFFSETS) {
                Vec3 point = FaceAim.facePoint(aim.box(), face, FaceAim.OBSERVE_DEPTH, offset[0], offset[1]);
                Line line = trace(level, context, eye, point, aim.clipShape(), target, face);
                if (line != null) {
                    lines.add(line);
                }
            }
        }
        return lines;
    }

    private static Line trace(BlockGetter level, CollisionContext context, Vec3 eye, Vec3 point, ClipContext.Block shape,
                              BlockPos target, Direction face) {
        Set<Long> skipped = new HashSet<>();
        List<Obstruction> found = new ArrayList<>(2);
        while (true) {
            BlockHitResult hit = level.clip(new SkippingClipContext(eye, point, shape, context, skipped));
            if (hit.getType() != HitResult.Type.BLOCK) {
                return null;
            }
            BlockPos pos = hit.getBlockPos();
            if (pos.equals(target)) {
                return hit.getDirection() == face ? new Line(face, point, found) : null;
            }
            BlockState state = level.getBlockState(pos);
            if (!SeeThrough.cell(state) || !state.getFluidState().isEmpty() || !skipped.add(pos.asLong())) {
                return null;
            }
            found.add(new Obstruction(pos.immutable(), state));
        }
    }

    /** The strict gate's own ray (plain vanilla, every fluid hit) that treats the blocks found so far as already broken. */
    private static final class SkippingClipContext extends ClipContext {
        private final Set<Long> skipped;

        SkippingClipContext(Vec3 from, Vec3 to, ClipContext.Block shape, CollisionContext context, Set<Long> skipped) {
            super(from, to, shape, ClipContext.Fluid.ANY, context);
            this.skipped = skipped;
        }

        @Override
        public VoxelShape getBlockShape(BlockState state, BlockGetter level, BlockPos pos) {
            return skipped.contains(pos.asLong()) ? Shapes.empty() : super.getBlockShape(state, level, pos);
        }
    }
}
