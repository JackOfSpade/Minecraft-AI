package io.github.zoyluo.minecraftai.mode;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * What stands between a hand and a block the eyes can see: the {@linkplain SeeThrough see-through} blocks that a vanilla pick ray
 * meets first. A bot that sees a log through two leaves cannot break it until they are gone, and this says which ones, per line of
 * aim.
 *
 * <p>The lines are the ones the {@code Strict} proofs aim ({@link ObservableWorldQuery#canObserveBlockStrict} and its inset form):
 * nine points on each of the six faces of the target's own outline, {@link FaceAim} style. A line is traced with the very ray
 * those proofs cast ({@link SightClip#pick}): a click's own, an OUTLINE ray that ignores fluids. So a see-through block is an
 * obstruction exactly when the click would land on it: a leaf, a plant, a torch, an open gate or a pane's post on the line, but not
 * a fence's gap (the outline rails leave one even where its collision arms do not), and never water, which a hand passes. What is
 * left on a line is therefore exactly what must disappear for the strict proofs to pass along it, and a line without any
 * obstruction is one a hand already reaches.</p>
 *
 * <p>A line is dead, and not returned, when anything else stops its ray: an opaque block, lava, or the target itself on a face
 * other than the one aimed at. Each obstruction is a distinct cell, so the list is as long as the blocks the line crosses.</p>
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
     * @param obstructions the see-through blocks a pick ray to {@code aim} meets before the target, nearest the eye first; empty
     *                     when the line is already clear
     */
    public record Line(Direction face, Vec3 aim, List<Obstruction> obstructions) {
    }

    /**
     * Every line from {@code eye} to a face of the block at {@code target} that only see-through blocks hide, in the fixed order of
     * the strict proofs (faces, then the 3x3 grid). Lines whose ray ends at an opaque block or lava are left out.
     *
     * @param standing the cell the observer stands in, or {@code null}: the lava it wades in does not hide the lines (see
     *                 {@link SightClipContext})
     */
    public static List<Line> lines(BlockGetter level, CollisionContext context, BlockPos standing, Vec3 eye, BlockPos target) {
        BlockState state = level.getBlockState(target);
        FaceAim.Target aim = FaceAim.aim(level, target, state, ClipContext.Block.OUTLINE, context, true);
        List<Line> lines = new ArrayList<>();
        for (Direction face : Direction.values()) {
            for (double[] offset : ObservableWorldQuery.FACE_SAMPLE_OFFSETS) {
                Vec3 point = FaceAim.facePoint(aim.box(), face, FaceAim.OBSERVE_DEPTH, offset[0], offset[1]);
                Line line = trace(level, context, standing, eye, point, target, face);
                if (line != null) {
                    lines.add(line);
                }
            }
        }
        return lines;
    }

    private static Line trace(BlockGetter level, CollisionContext context, BlockPos standing, Vec3 eye, Vec3 point,
                              BlockPos target, Direction face) {
        SightClipContext ray = SightClip.pick(eye, point, context, standing, target);
        BlockHitResult hit = level.clip(ray);
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(target) || hit.getDirection() != face) {
            return null;
        }
        List<Obstruction> found = new ArrayList<>(ray.obstructions().size());
        for (SightClipContext.Crossing crossing : ray.obstructions()) {
            found.add(new Obstruction(crossing.pos(), crossing.state()));
        }
        return new Line(face, point, found);
    }
}
