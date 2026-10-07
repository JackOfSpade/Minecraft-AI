package io.github.zoyluo.minecraftai.mode;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Eye rays for bots: the {@link SightClipContext} counterpart of {@code level.clip(new ClipContext(...))}. Use these wherever a
 * bot asks what it can <em>see</em>; a ray that decides what a hand may <em>reach</em> (a break, a placement, a click on a block
 * or an entity, a strike, a bucket) stays a plain vanilla {@code ClipContext}, because seeing through a leaf must never let a
 * bot mine, open or hit what the leaf is in front of.
 *
 * <p>The shape and fluid kinds are the call site's own, exactly as for a vanilla context, so what a COLLIDER or an OUTLINE ray
 * is blind to stays so. See {@link SightClipContext} for what is skipped, what is not (lava, the target cell) and how obstructions
 * of the pick ray are reported.</p>
 */
public final class SightClip {
    /** Vanilla's {@code LivingEntity.hasLineOfSight} gives up beyond this distance (blocks). */
    public static final double MAX_LINE_OF_SIGHT = 128.0D;

    private SightClip() {
    }

    /** The first thing an eye ray from {@code from} to {@code to} stops at; {@code target} (or {@code null}) is never skipped. */
    public static BlockHitResult clip(BlockGetter level, Vec3 from, Vec3 to, ClipContext.Block shape,
                                      ClipContext.Fluid fluid, CollisionContext collisionContext, BlockPos target) {
        return level.clip(new SightClipContext(from, to, shape, fluid, collisionContext, target, false));
    }

    public static BlockHitResult clip(BlockGetter level, Vec3 from, Vec3 to, ClipContext.Block shape,
                                      ClipContext.Fluid fluid, Entity observer, BlockPos target) {
        return level.clip(new SightClipContext(from, to, shape, fluid, observer, target, false));
    }

    /**
     * A fresh context for a ray whose caller also wants what it crossed: pass it to {@code level.clip(...)} and then read
     * {@link SightClipContext#crossed()} (with {@code recordCrossed}) or, after {@link SightClipContext#trackObstructions()},
     * {@link SightClipContext#reachObstructed()} and {@link SightClipContext#obstructions()}.
     */
    public static SightClipContext context(Vec3 from, Vec3 to, ClipContext.Block shape, ClipContext.Fluid fluid,
                                           CollisionContext collisionContext, BlockPos target, boolean recordCrossed) {
        return new SightClipContext(from, to, shape, fluid, collisionContext, target, recordCrossed);
    }

    public static SightClipContext context(Vec3 from, Vec3 to, ClipContext.Block shape, ClipContext.Fluid fluid,
                                           Entity observer, BlockPos target, boolean recordCrossed) {
        return new SightClipContext(from, to, shape, fluid, observer, target, recordCrossed);
    }

    /**
     * The line a hand follows to a point, as a context: a vanilla pick ray ({@code Entity.pick}, which every click goes by) is an
     * OUTLINE ray that ignores fluids, so water is passed and a see-through block whose outline the segment crosses is what the
     * click lands on, however a sight ray would treat it. After {@code level.clip(...)} the ray is clear exactly when it ended at
     * {@code target} and {@link SightClipContext#reachObstructed()} is false; otherwise
     * {@link SightClipContext#obstructions()} lists what must go first. Lava still stops it, as it stops the eyes: a hand does not
     * reach what the bot cannot see. {@code standing} is the cell the observer stands in, as for
     * {@link SightClipContext#SightClipContext(Vec3, Vec3, ClipContext.Block, ClipContext.Fluid, CollisionContext, BlockPos, boolean, BlockPos)}.
     */
    public static SightClipContext pick(Vec3 from, Vec3 to, CollisionContext collisionContext, BlockPos standing, BlockPos target) {
        return new SightClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, collisionContext, target, false,
                standing).trackObstructions();
    }

    /**
     * What a ray that recorded its crossings ({@link SightClipContext#crossed()}) saw in the cell {@code packedPos}
     * ({@link BlockPos#asLong()}): the real state of a leaf, a fence, glass or water it passed through, or {@code null} when
     * it did not skip that cell. A recorder that walks a ray's cells stores this instead of air, so foliage and water are
     * never remembered as free space. A list that came from a ray ({@link SightClipContext#crossed()}) answers from its index, so
     * a recorder that asks for every cell of a long underwater ray does not scan the whole list each time.
     */
    public static BlockState crossedState(List<SightClipContext.Crossing> crossed, long packedPos) {
        if (crossed instanceof SightClipContext.Crossings indexed) {
            return indexed.stateAt(packedPos);
        }
        for (SightClipContext.Crossing crossing : crossed) {
            if (crossing.pos().asLong() == packedPos) {
                return crossing.state();
            }
        }
        return null;
    }

    /** Whether nothing opaque lies between two points: a COLLIDER ray, blind to every fluid but lava, ends without a hit. */
    public static boolean clear(BlockGetter level, CollisionContext collisionContext, Vec3 from, Vec3 to) {
        return clip(level, from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, collisionContext, null)
                .getType() == HitResult.Type.MISS;
    }

    public static boolean clear(BlockGetter level, Entity observer, Vec3 from, Vec3 to) {
        return clip(level, from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, observer, null)
                .getType() == HitResult.Type.MISS;
    }

    /**
     * {@code LivingEntity.hasLineOfSight(Entity)} with eyes that see through foliage and water: the same level, the same two eye
     * heights, the same 128-block limit and the same COLLIDER, no-fluid ray, only without the see-through blocks in the way.
     */
    public static boolean hasLineOfSight(LivingEntity self, Entity other) {
        if (other.level() != self.level()) {
            return false;
        }
        Vec3 from = new Vec3(self.getX(), self.getEyeY(), self.getZ());
        Vec3 to = new Vec3(other.getX(), other.getEyeY(), other.getZ());
        return to.distanceTo(from) <= MAX_LINE_OF_SIGHT && clear(self.level(), self, from, to);
    }
}
