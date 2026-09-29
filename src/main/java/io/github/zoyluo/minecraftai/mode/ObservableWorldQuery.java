package io.github.zoyluo.minecraftai.mode;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Strict-survival perception filter: nearby, exposed, and actually on the Bot's line of sight. */
public final class ObservableWorldQuery {
    private static final double FACE_ENDPOINT_DEPTH = 0.499D;
    /** Shared inset (in blocks) used to sample points around a face center. Also used by BuildAction. */
    public static final double FACE_SAMPLE_INSET = 0.375D;
    private static final double[][] FACE_SAMPLE_OFFSETS = {
            {0.0D, 0.0D},
            {-FACE_SAMPLE_INSET, 0.0D},
            {FACE_SAMPLE_INSET, 0.0D},
            {0.0D, -FACE_SAMPLE_INSET},
            {0.0D, FACE_SAMPLE_INSET},
            {-FACE_SAMPLE_INSET, -FACE_SAMPLE_INSET},
            {-FACE_SAMPLE_INSET, FACE_SAMPLE_INSET},
            {FACE_SAMPLE_INSET, -FACE_SAMPLE_INSET},
            {FACE_SAMPLE_INSET, FACE_SAMPLE_INSET}
    };

    private ObservableWorldQuery() {
    }

    public static boolean canObserveBlock(AIPlayerEntity bot, BlockPos pos) {
        return canObserveBlockWithin(bot, pos, 0);
    }

    /**
     * Prey-grounding observation at surface-search range: the specific cells under a visible
     * animal. Same fairness class as {@link #canObserveEntityWithin(AIPlayerEntity, Entity, int)}
     * (a player looking at a distant cow sees the ground it stands on): the per-face raycasts
     * and the strict capability bypass are unchanged, only the distance bound widens, and never
     * below the configured perception radius. Ordinary block scans keep the base radius.
     */
    public static boolean canObserveBlockWithin(AIPlayerEntity bot, BlockPos pos, int range) {
        if (CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                "observable_block_query").allowed()) {
            return true;
        }
        for (Direction direction : Direction.values()) {
            // Aim at the exposed face, not the block center. A center ray to distant flat ground
            // intersects a nearer ground block first and incorrectly reports the target as hidden.
            // Keep the endpoint just inside the target block. Stopping just outside the face
            // lets the ray end before entering the collision shape and produces MISS for an
            // otherwise visible floor block.
            var face = pos.getCenter().add(
                    direction.getStepX() * 0.499D,
                    direction.getStepY() * 0.499D,
                    direction.getStepZ() * 0.499D);
            if (canObserveFaceAfterPolicy(bot, pos, direction, face, range)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Explicit short-range observation for a block whose exposed area may not include any face
     * center. This is intentionally separate from {@link #canObserveBlock(AIPlayerEntity,
     * BlockPos)} so ordinary scans keep their six-ray cost. Callers opt into a deterministic 3x3
     * inset grid on each face and still need an exact fluid-aware world ray hit.
     */
    public static boolean canObserveBlockWithInsetFaces(AIPlayerEntity bot, BlockPos pos) {
        if (CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                "observable_block_inset_face_query").allowed()) {
            return true;
        }
        double observationRange = Math.min(
                Math.max(1, MinecraftAiConfig.get().perception().radius()),
                bot.blockInteractionRange());
        double observationRangeSquared = observationRange * observationRange;
        Vec3 eye = bot.getEyePosition();
        for (Direction direction : Direction.values()) {
            for (double[] offset : FACE_SAMPLE_OFFSETS) {
                Vec3 endpoint = insetFaceEndpoint(pos, direction, offset[0], offset[1]);
                if (eye.distanceToSqr(endpoint) > observationRangeSquared) {
                    continue;
                }
                BlockHitResult hit = bot.level().clip(new ClipContext(
                        eye, endpoint,
                        ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.ANY,
                        bot));
                if (hit.getType() == HitResult.Type.BLOCK
                        && hit.getBlockPos().equals(pos)
                        && hit.getDirection() == direction) {
                    return true;
                }
            }
        }
        return false;
    }

    static Vec3 insetFaceEndpoint(BlockPos pos,
                                   Direction face,
                                   double firstTangent,
                                   double secondTangent) {
        Vec3 center = pos.getCenter().add(
                face.getStepX() * FACE_ENDPOINT_DEPTH,
                face.getStepY() * FACE_ENDPOINT_DEPTH,
                face.getStepZ() * FACE_ENDPOINT_DEPTH);
        return switch (face.getAxis()) {
            case X -> center.add(0.0D, firstTangent, secondTangent);
            case Y -> center.add(firstTangent, 0.0D, secondTangent);
            case Z -> center.add(firstTangent, secondTangent, 0.0D);
        };
    }

    private static boolean canObserveFaceAfterPolicy(AIPlayerEntity bot,
                                                      BlockPos pos,
                                                      Direction face,
                                                      net.minecraft.world.phys.Vec3 endpoint,
                                                      int range) {
        int radius = Math.max(Math.max(1, MinecraftAiConfig.get().perception().radius()), range);
        if (bot.getEyePosition().distanceToSqr(endpoint) > (double) radius * radius) {
            return false;
        }
        BlockHitResult hit = bot.level().clip(new ClipContext(
                bot.getEyePosition(), endpoint,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, bot));
        return hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(pos)
                && hit.getDirection() == face;
    }

    /**
     * Returns whether the bot has an unobstructed view into a nearby world cell. Unlike
     * {@link #canObserveBlock(AIPlayerEntity, BlockPos)}, an empty/non-colliding target is a valid
     * result, so callers can gate feet/head reads before asking whether a position is standable.
     */
    public static boolean canObserveCell(AIPlayerEntity bot, BlockPos pos) {
        if (CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                "observable_cell_query").allowed()) {
            return true;
        }
        return canObserveCellWithinAfterPolicy(bot, pos, 0);
    }

    /**
     * Prey-grounding cell observation at surface-search range; see
     * {@link #canObserveBlockWithin(AIPlayerEntity, BlockPos, int)} for the fairness rationale.
     */
    public static boolean canObserveCellWithin(AIPlayerEntity bot, BlockPos pos, int range) {
        if (CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                "observable_cell_query").allowed()) {
            return true;
        }
        return canObserveCellWithinAfterPolicy(bot, pos, range);
    }

    private static boolean canObserveCellWithinAfterPolicy(AIPlayerEntity bot, BlockPos pos, int range) {
        int radius = Math.max(Math.max(1, MinecraftAiConfig.get().perception().radius()), range);
        if (bot.getEyePosition().distanceToSqr(pos.getCenter()) > (double) radius * radius) {
            return false;
        }
        BlockHitResult hit = bot.level().clip(new ClipContext(
                bot.getEyePosition(), pos.getCenter(),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, bot));
        return hit.getType() == HitResult.Type.MISS
                || (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos));
    }

    /**
     * Farm-cell observation: whether the bot's own eye can see the real outline of a crop or farmland
     * cell. The generic block query aims collider rays at fixed full-cube face points, which misses
     * crops (they have an outline but no collider) and the top of farmland (15/16 high, so the fixed
     * point at y+0.999 lies above the shape). This aims OUTLINE rays at points inside the state's real
     * shape (its top centre and four inset top points) and needs the exact ray to strike this cell, so
     * a crop hidden behind a wall or a taller crop stays hidden. An empty (non-shaped) cell falls back
     * to {@link #canObserveCell}'s policy. Same perception radius and capability gate as the other
     * observation predicates; it sees nothing a player standing at the bot's eye could not see.
     */
    public static boolean canObserveFarmCell(AIPlayerEntity bot, BlockPos pos) {
        if (CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                "observable_farm_cell_query").allowed()) {
            return true;
        }
        int radius = Math.max(1, MinecraftAiConfig.get().perception().radius());
        double radiusSquared = (double) radius * radius;
        Vec3 eye = bot.getEyePosition();
        if (eye.distanceToSqr(pos.getCenter()) > (radius + 1.0D) * (radius + 1.0D)) {
            return false;
        }
        var world = bot.level();
        java.util.List<Vec3> samples = shapeTopSamples(world, pos);
        if (samples.isEmpty()) {
            return canObserveCellWithinAfterPolicy(bot, pos, 0);
        }
        for (Vec3 endpoint : samples) {
            if (eye.distanceToSqr(endpoint) > radiusSquared) {
                continue;
            }
            BlockHitResult hit = world.clip(new ClipContext(
                    eye, endpoint, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, bot));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Aim points inside a cell's real outline shape: the top centre and four points inset by a quarter
     * of the shape's span, 0.01 below its top face (an inside point, so a ray to it strikes the shape
     * itself). Empty when the cell has no outline (air). Crops and farmland have different heights, so
     * the points follow the actual shape rather than a fixed unit-cube face.
     */
    public static java.util.List<Vec3> shapeTopSamples(net.minecraft.world.level.BlockGetter world, BlockPos pos) {
        var shape = world.getBlockState(pos).getShape(world, pos);
        if (shape.isEmpty()) {
            return java.util.List.of();
        }
        double minX = pos.getX() + shape.min(net.minecraft.core.Direction.Axis.X);
        double maxX = pos.getX() + shape.max(net.minecraft.core.Direction.Axis.X);
        double minZ = pos.getZ() + shape.min(net.minecraft.core.Direction.Axis.Z);
        double maxZ = pos.getZ() + shape.max(net.minecraft.core.Direction.Axis.Z);
        double topY = pos.getY() + shape.max(net.minecraft.core.Direction.Axis.Y) - 0.01D;
        double centerX = (minX + maxX) * 0.5D;
        double centerZ = (minZ + maxZ) * 0.5D;
        double spanX = (maxX - minX) * 0.25D;
        double spanZ = (maxZ - minZ) * 0.25D;
        return java.util.List.of(
                new Vec3(centerX, topY, centerZ),
                new Vec3(centerX - spanX, topY, centerZ - spanZ),
                new Vec3(centerX + spanX, topY, centerZ - spanZ),
                new Vec3(centerX - spanX, topY, centerZ + spanZ),
                new Vec3(centerX + spanX, topY, centerZ + spanZ));
    }

    public static boolean canObserveEntity(AIPlayerEntity bot, Entity entity) {
        if (CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                "observable_entity_query").allowed()) {
            return true;
        }
        int radius = Math.max(1, MinecraftAiConfig.get().perception().radius());
        return bot.distanceToSqr(entity) <= (double) radius * radius && bot.hasLineOfSight(entity);
    }

    /**
     * Live-fauna observation at surface-search range: a real player sees animals at render
     * distance whenever line of sight holds, far beyond the interaction-scale radius that
     * bounds block reads. The raycast stays, so terrain still hides herds; only the distance
     * bound widens, and never below the configured perception radius. Block queries are
     * unaffected, and the strict capability bypass stays exactly as it is.
     */
    public static boolean canObserveEntityWithin(AIPlayerEntity bot, Entity entity, int range) {
        if (CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                "observable_entity_query").allowed()) {
            return true;
        }
        int radius = Math.max(Math.max(1, MinecraftAiConfig.get().perception().radius()), range);
        return bot.distanceToSqr(entity) <= (double) radius * radius && bot.hasLineOfSight(entity);
    }

    /** Which shape a view ray tests against. */
    public enum ViewShape {
        /** Collision shapes: the same surfaces every block observation predicate above accepts. */
        COLLIDER,
        /** Selection outlines: also meets rails, cobweb, torches, banners and sculk veins. */
        OUTLINE
    }

    /**
     * First-hit answer of {@link #castViewRay}. A miss has {@code hit == false} and
     * {@code distance ==} the clamped range; a hit carries the exact hit cell, the struck face and the
     * state of that one cell. A ray that was not cast at all (its end chunk is not loaded) is
     * {@link #unknown()}: it says nothing about the world and must not be recorded as free space.
     */
    public record ViewHit(boolean hit, BlockPos pos, Direction side, double distance, BlockState state) {
        public static ViewHit unknown() {
            return new ViewHit(false, null, null, -1.0D, null);
        }

        public boolean isUnknown() {
            return !hit && distance < 0.0D;
        }
    }

    /**
     * One honest view ray from the bot's own eye (mining-assist design 3.1): the first surface a real
     * player would see along the direction, within the perception radius. The result is only a
     * nomination; any action still has to re-prove the exact cell. The direction need not be a unit
     * vector. There is deliberately no origin parameter, so a ray can only start at the eye.
     *
     * <p>The length is {@code min(range, max(1, perception radius))}. The state is read only for the
     * single first-hit cell, after the ray has reported a block hit; a miss reads nothing. If the chunk
     * holding the ray's end point is not loaded the ray is skipped and reported {@link ViewHit#unknown()}.
     * This is a plain view query with no capability lookup: it sees nothing a player standing at the
     * bot's eye could not see.</p>
     */
    public static ViewHit castViewRay(AIPlayerEntity bot, double dx, double dy, double dz,
                                      double range, ViewShape shape) {
        double limit = Math.min(range, Math.max(1, MinecraftAiConfig.get().perception().radius()));
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(limit > 0.0D) || !(length > 1.0E-9D)) {
            return ViewHit.unknown();
        }
        Vec3 eye = bot.getEyePosition();
        Vec3 end = eye.add(dx / length * limit, dy / length * limit, dz / length * limit);
        var world = bot.level();
        // Chunk coordinate is the block coordinate shifted right by four bits.
        if (!world.getChunkSource().hasChunk(
                (int) Math.floor(end.x) >> 4, (int) Math.floor(end.z) >> 4)) {
            return ViewHit.unknown();
        }
        BlockHitResult hit = world.clip(new ClipContext(
                eye, end,
                shape == ViewShape.OUTLINE
                        ? ClipContext.Block.OUTLINE : ClipContext.Block.COLLIDER,
                ClipContext.Fluid.ANY,
                bot));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return new ViewHit(false, null, null, limit, null);
        }
        BlockPos pos = hit.getBlockPos();
        return new ViewHit(true, pos, hit.getDirection(), eye.distanceTo(hit.getLocation()), world.getBlockState(pos));
    }
}
