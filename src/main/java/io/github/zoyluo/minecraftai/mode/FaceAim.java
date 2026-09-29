package io.github.zoyluo.minecraftai.mode;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Shape-aware face aim points (Baritone {@code VecUtils} / {@code RotationUtils.reachable} idea, kept
 * within the perception rules: every point is still proven by a ray from the bot's own eye).
 *
 * <p>A block face is not always the cell face. A chest is inset by 1/16 and 14/16 tall, farmland and a
 * dirt path are 15/16 tall, a bottom slab, bed or cake reach less than half a cell, a lever is a small
 * plate. A ray that ends 0.001 inside the <em>cell</em> face then stops short of the real shape and never
 * reports a hit, so such a block was never observable. Here the face plane is taken from the block's own
 * shape bounds (0.001 inside the shape) and the tangent grid is scaled to the shape's extent on the two
 * face axes.</p>
 */
public final class FaceAim {
    /** How far inside the shape an observation ray ends (blocks). */
    public static final double OBSERVE_DEPTH = 0.001D;

    private FaceAim() {
    }

    /** The shape box to aim at plus which shape a ray to it must be tested against. */
    public record Target(AABB box, ClipContext.Block clipShape) {
    }

    /** {@link #aim(BlockGetter, BlockPos, BlockState, ClipContext.Block, CollisionContext, boolean)} with the outline fallback. */
    public static Target aim(BlockGetter level, BlockPos pos, BlockState state,
                             ClipContext.Block mode, CollisionContext context) {
        return aim(level, pos, state, mode, context, true);
    }

    /**
     * Box (world coordinates, clamped to the cell) of the shape a {@code mode} ray meets in {@code pos}.
     * COLLIDER uses the collision shape and, with {@code outlineFallback}, for a block without one (snow layer,
     * rails, torch, cobweb, a plant) the selection outline with an OUTLINE ray; without the fallback such a block is
     * aimed at as a full cell, which a COLLIDER ray never strikes (it is not a collider). OUTLINE uses the outline
     * and falls back to the collision shape. A cell with neither (fluid, air) is aimed at as a full cell with the
     * requested mode.
     */
    public static Target aim(BlockGetter level, BlockPos pos, BlockState state,
                             ClipContext.Block mode, CollisionContext context, boolean outlineFallback) {
        VoxelShape collision = state.getCollisionShape(level, pos, context);
        VoxelShape outline = state.getShape(level, pos, context);
        VoxelShape shape;
        ClipContext.Block clip = mode;
        if (mode == ClipContext.Block.OUTLINE) {
            shape = !outline.isEmpty() ? outline : collision;
        } else if (!collision.isEmpty()) {
            shape = collision;
        } else if (outlineFallback && !outline.isEmpty() && state.getFluidState().isEmpty()) {
            shape = outline;
            clip = ClipContext.Block.OUTLINE;
        } else {
            shape = null;
        }
        if (shape == null || shape.isEmpty()) {
            return new Target(new AABB(pos), clip);
        }
        AABB b = shape.bounds();
        double x0 = Math.max(0.0D, b.minX);
        double y0 = Math.max(0.0D, b.minY);
        double z0 = Math.max(0.0D, b.minZ);
        double x1 = Math.min(1.0D, b.maxX);
        double y1 = Math.min(1.0D, b.maxY);
        double z1 = Math.min(1.0D, b.maxZ);
        if (x1 - x0 < 1.0E-3D || y1 - y0 < 1.0E-3D || z1 - z0 < 1.0E-3D) {
            return new Target(new AABB(pos), clip);
        }
        return new Target(new AABB(
                pos.getX() + x0, pos.getY() + y0, pos.getZ() + z0,
                pos.getX() + x1, pos.getY() + y1, pos.getZ() + z1), clip);
    }

    /**
     * Point on the {@code face} of {@code box}, {@code depth} inside it (a negative depth is outside),
     * moved from the face centre by {@code first}/{@code second} times the box extent along the two
     * tangent axes (X face: y,z; Y face: x,z; Z face: x,y). With offsets of a unit cell fraction such as
     * 0.375 the point stays inside the face for any extent.
     */
    public static Vec3 facePoint(AABB box, Direction face, double depth, double first, double second) {
        double cx = (box.minX + box.maxX) * 0.5D;
        double cy = (box.minY + box.maxY) * 0.5D;
        double cz = (box.minZ + box.maxZ) * 0.5D;
        double ex = box.maxX - box.minX;
        double ey = box.maxY - box.minY;
        double ez = box.maxZ - box.minZ;
        return switch (face) {
            case EAST -> new Vec3(box.maxX - depth, cy + first * ey, cz + second * ez);
            case WEST -> new Vec3(box.minX + depth, cy + first * ey, cz + second * ez);
            case UP -> new Vec3(cx + first * ex, box.maxY - depth, cz + second * ez);
            case DOWN -> new Vec3(cx + first * ex, box.minY + depth, cz + second * ez);
            case SOUTH -> new Vec3(cx + first * ex, cy + second * ey, box.maxZ - depth);
            case NORTH -> new Vec3(cx + first * ex, cy + second * ey, box.minZ + depth);
        };
    }
}
