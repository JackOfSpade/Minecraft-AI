package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Whether a pillar head will still be able to mine its target. The pillar's own column is proven air,
 * but beside the target a ledge, a wall or a fence between the head and the block makes the break controller
 * refuse the mine once the pillar stands, after the throwaway blocks are spent (a leaf does not: the controller
 * breaks it first, and the caller's {@code shut} test leaves it out). The question is asked of
 * what the bot has already seen: a cell it cannot see proves nothing either way (and reading it would be
 * looking through a wall), so the caller's {@code shut} test answers false for it and it never rules a
 * column out. The geometry is the observer's: the center and the inset corners of each face of the
 * target that turns toward the eye ({@link ObservableWorldQuery#FACE_SAMPLE_INSET}).
 */
final class PillarSightline {
    private PillarSightline() {
    }

    /**
     * True when every line from {@code eye} to every point the observer aims at on {@code target} is shut
     * by a cell for which {@code shut} answers true. Cells of the pillar's own column and the target itself
     * are never asked about.
     */
    static boolean isBlocked(Vec3 eye, BlockPos target, BlockPos goal, Predicate<BlockPos> shut) {
        Map<BlockPos, Boolean> answers = new HashMap<>();
        for (Direction face : Direction.values()) {
            Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
            Vec3 center = target.getCenter().add(normal.scale(0.5D));
            if (eye.subtract(center).dot(normal) <= 0.0D) {
                continue; // a face turned away from the eye is behind the block, never in its first hit
            }
            Vec3 first = face.getAxis() == Direction.Axis.X ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
            Vec3 second = face.getAxis() == Direction.Axis.Z ? new Vec3(0, 1, 0) : new Vec3(0, 0, 1);
            double inset = ObservableWorldQuery.FACE_SAMPLE_INSET;
            Vec3[] aims = {center,
                    center.add(first.scale(inset)).add(second.scale(inset)),
                    center.add(first.scale(inset)).add(second.scale(-inset)),
                    center.add(first.scale(-inset)).add(second.scale(inset)),
                    center.add(first.scale(-inset)).add(second.scale(-inset))};
            for (Vec3 aim : aims) {
                if (!isLineShut(eye, aim, goal, target, shut, answers)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isLineShut(Vec3 from, Vec3 to, BlockPos goal, BlockPos target,
                                      Predicate<BlockPos> shut, Map<BlockPos, Boolean> answers) {
        double length = from.distanceTo(to);
        // A quarter block is finer than the thinnest step a line makes through a cell corner that still blocks a ray.
        for (double along = 0.0D; along < length; along += 0.25D) {
            BlockPos cell = BlockPos.containing(from.lerp(to, along / length));
            if (cell.equals(target) || cell.getX() == goal.getX() && cell.getZ() == goal.getZ()) {
                continue;
            }
            if (answers.computeIfAbsent(cell.immutable(), shut::test)) {
                return true;
            }
        }
        return false;
    }
}
