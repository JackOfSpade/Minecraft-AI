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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Strict-survival perception filter: nearby, exposed, and actually on the Bot's line of sight. */
public final class ObservableWorldQuery {
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

    /**
     * Whether the bot's eye can see a face of the block at {@code pos}: a block in plain view, as a player
     * sees it. The ray aims at the block's own shape ({@link FaceAim}): its collision shape, or for a block that
     * has none (torch, rail, cobweb, plant, crop, banner, snow layer) its selection outline with an OUTLINE ray. This
     * is a <em>visibility</em> proof, not a solidity proof: a support or standability decision must use
     * {@link #canObserveCollider}, or read the state and check the collision shape itself, so that
     * "I can see a torch there" never turns into "I can stand on it".
     */
    public static boolean canObserveBlock(AIPlayerEntity bot, BlockPos pos) {
        return canObserveBlockWithin(bot, pos, 0);
    }

    /**
     * State-free preliminary proof that the eye ray first strikes a face of {@code pos}'s unit
     * cell. This deliberately does not inspect the target's state or shape: callers that must
     * earn a later shape-aware query (for example direct mining) use it first, then confirm the
     * real outline with {@link #canObserveBlock} or {@link #canObserveBlockWithInsetFaces}.
     *
     * <p>The unit-cell face is intentionally conservative for a partial block whose real outline
     * does not meet that face. It never turns a hidden target into a visible one; it merely says
     * that a player-facing ray reached this cell before any target-state read.</p>
     */
    public static boolean canObserveBlockCellFace(AIPlayerEntity bot, BlockPos pos) {
        if (CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                "observable_block_cell_face_query").allowed()) {
            return true;
        }
        int radius = Math.max(1, MinecraftAiConfig.get().perception().radius());
        double radiusSquared = (double) radius * radius;
        Vec3 eye = bot.getEyePosition();
        AABB cell = new AABB(pos);
        for (Direction face : Direction.values()) {
            for (double[] offset : FACE_SAMPLE_OFFSETS) {
                Vec3 endpoint = FaceAim.facePoint(cell, face, FaceAim.OBSERVE_DEPTH, offset[0], offset[1]);
                if (eye.distanceToSqr(endpoint) > radiusSquared) {
                    continue;
                }
                BlockHitResult hit = bot.level().clip(new ClipContext(
                        eye, endpoint, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, bot));
                if (hit.getType() == HitResult.Type.BLOCK
                        && pos.equals(hit.getBlockPos())
                        && hit.getDirection() == face) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Prey-grounding observation at surface-search range: the specific cells under a visible
     * animal. Same fairness class as {@link #canObserveEntityWithin(AIPlayerEntity, Entity, int)}
     * (a player looking at a distant cow sees the ground it stands on): the per-face raycasts
     * and the strict capability bypass are unchanged, only the distance bound widens, and never
     * below the configured perception radius. Ordinary block scans keep the base radius.
     */
    public static boolean canObserveBlockWithin(AIPlayerEntity bot, BlockPos pos, int range) {
        return observeShapeFaces(bot, pos, range, true, "observable_block_query", ClipContext.Fluid.ANY);
    }

    /**
     * {@link #canObserveBlock} for a proof that the block is a real collider: the same shape-aware six-ray
     * test, but a block without a collision shape (torch, rail, cobweb, plant) is never accepted, however
     * plainly it is in view. Support, ground and standability proofs use this one.
     */
    public static boolean canObserveCollider(AIPlayerEntity bot, BlockPos pos) {
        return canObserveColliderWithin(bot, pos, 0);
    }

    /** {@link #canObserveCollider} at prey-grounding range ({@link #canObserveBlockWithin}). */
    public static boolean canObserveColliderWithin(AIPlayerEntity bot, BlockPos pos, int range) {
        return observeShapeFaces(bot, pos, range, false, "observable_block_query", ClipContext.Fluid.ANY);
    }

    /**
     * A real collider visible on vanilla entity line of sight, which deliberately does not let
     * water (the medium an underwater player is looking through) block the ray. This is scoped to
     * water navigation; placement and ordinary block-interaction proofs keep their fluid-aware
     * observation policy above.
     */
    public static boolean canObserveColliderThroughFluids(AIPlayerEntity bot, BlockPos pos) {
        if (CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                "observable_water_collider_query").allowed()) {
            return true;
        }
        int radius = Math.max(1, MinecraftAiConfig.get().perception().radius());
        Vec3 eye = bot.getEyePosition();
        Vec3 target = pos.getCenter();
        if (eye.distanceToSqr(target) > (double) radius * radius) {
            return false;
        }
        // This first-hit collider ray proves both visibility through water and that the target is
        // a real collider, without reading the target BlockState to derive a shape before it has
        // crossed the transparent-water observation boundary. A conservative center sample may
        // reject an unusually shaped exposed collider, but can never invent one behind terrain.
        BlockHitResult hit = bot.level().clip(new ClipContext(
                eye, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
    }

    private static boolean observeShapeFaces(AIPlayerEntity bot, BlockPos pos, int range,
                                             boolean outlineFallback, String reason, ClipContext.Fluid fluid) {
        if (CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN, reason).allowed()) {
            return true;
        }
        // Aim at the exposed face of the block's real shape, not the block center or the cell face. A
        // center ray to distant flat ground intersects a nearer ground block first and incorrectly
        // reports the target as hidden, and a chest, bottom slab, farmland or bed does not reach the
        // cell face at all (see FaceAim). Keep the endpoint just inside the shape: stopping just
        // outside lets the ray end before entering it and produces MISS for an otherwise visible block.
        FaceAim.Target aim = FaceAim.aim(bot.level(), pos, bot.level().getBlockState(pos),
                ClipContext.Block.COLLIDER, CollisionContext.of(bot), outlineFallback);
        for (Direction direction : Direction.values()) {
            var face = FaceAim.facePoint(aim.box(), direction, FaceAim.OBSERVE_DEPTH, 0.0D, 0.0D);
            if (canObserveFaceAfterPolicy(bot, pos, direction, face, range, aim.clipShape(), fluid)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Explicit short-range observation for a block whose exposed area may not include any face
     * center. This is intentionally separate from {@link #canObserveBlock(AIPlayerEntity,
     * BlockPos)} so ordinary scans keep their six-ray cost. Callers opt into a deterministic 3x3
     * inset grid on each face and still need an exact fluid-aware world ray hit. Same shape rule as
     * {@link #canObserveBlock}; {@link #canObserveColliderWithInsetFaces} is the collider-only form.
     */
    public static boolean canObserveBlockWithInsetFaces(AIPlayerEntity bot, BlockPos pos) {
        return observeShapeInsetFaces(bot, pos, true);
    }

    /** {@link #canObserveBlockWithInsetFaces} for support and standability proofs: a collision shape is required. */
    public static boolean canObserveColliderWithInsetFaces(AIPlayerEntity bot, BlockPos pos) {
        return observeShapeInsetFaces(bot, pos, false, bot.blockInteractionRange());
    }

    /**
     * Collision-shape observation for a caller that needs a factual movement stance rather than
     * an interaction target. The supplied range remains capped by ordinary perception; it only
     * avoids treating a visible destination as unreachable because it cannot yet be clicked.
     */
    public static boolean canObserveColliderWithInsetFacesWithin(AIPlayerEntity bot,
                                                                  BlockPos pos,
                                                                  int range) {
        return observeShapeInsetFaces(bot, pos, false, Math.max(1, range));
    }

    private static boolean observeShapeInsetFaces(AIPlayerEntity bot, BlockPos pos, boolean outlineFallback) {
        return observeShapeInsetFaces(bot, pos, outlineFallback, bot.blockInteractionRange());
    }

    private static boolean observeShapeInsetFaces(AIPlayerEntity bot,
                                                  BlockPos pos,
                                                  boolean outlineFallback,
                                                  double rangeLimit) {
        if (CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                "observable_block_inset_face_query").allowed()) {
            return true;
        }
        double observationRange = Math.min(
                Math.max(1, MinecraftAiConfig.get().perception().radius()),
                Math.max(1.0D, rangeLimit));
        double observationRangeSquared = observationRange * observationRange;
        Vec3 eye = bot.getEyePosition();
        FaceAim.Target aim = FaceAim.aim(bot.level(), pos, bot.level().getBlockState(pos),
                ClipContext.Block.COLLIDER, CollisionContext.of(bot), outlineFallback);
        for (Direction direction : Direction.values()) {
            for (double[] offset : FACE_SAMPLE_OFFSETS) {
                Vec3 endpoint = FaceAim.facePoint(
                        aim.box(), direction, FaceAim.OBSERVE_DEPTH, offset[0], offset[1]);
                if (eye.distanceToSqr(endpoint) > observationRangeSquared) {
                    continue;
                }
                BlockHitResult hit = bot.level().clip(new ClipContext(
                        eye, endpoint,
                        aim.clipShape(),
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

    private static boolean canObserveFaceAfterPolicy(AIPlayerEntity bot,
                                                      BlockPos pos,
                                                      Direction face,
                                                      net.minecraft.world.phys.Vec3 endpoint,
                                                      int range,
                                                      ClipContext.Block clipShape,
                                                      ClipContext.Fluid fluid) {
        int radius = Math.max(Math.max(1, MinecraftAiConfig.get().perception().radius()), range);
        if (bot.getEyePosition().distanceToSqr(endpoint) > (double) radius * radius) {
            return false;
        }
        BlockHitResult hit = bot.level().clip(new ClipContext(
                bot.getEyePosition(), endpoint,
                clipShape, fluid, bot));
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
     * Cell observation for underwater movement. It uses the same eye/range/solid-terrain ray as
     * {@link net.minecraft.world.entity.Entity#hasLineOfSight(Entity)}, whose fluid mode is
     * {@link ClipContext.Fluid#NONE}: water does not make a nearby visible shore or water column
     * into hidden-world knowledge. Callers remain responsible for rejecting hazardous fluids.
     */
    public static boolean canObserveCellThroughFluids(AIPlayerEntity bot, BlockPos pos) {
        if (CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                "observable_water_cell_query").allowed()) {
            return true;
        }
        return canObserveCellWithinAfterPolicy(bot, pos, 0, ClipContext.Fluid.NONE);
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
        return canObserveCellWithinAfterPolicy(bot, pos, range, ClipContext.Fluid.ANY);
    }

    private static boolean canObserveCellWithinAfterPolicy(AIPlayerEntity bot, BlockPos pos, int range,
                                                            ClipContext.Fluid fluid) {
        int radius = Math.max(Math.max(1, MinecraftAiConfig.get().perception().radius()), range);
        if (bot.getEyePosition().distanceToSqr(pos.getCenter()) > (double) radius * radius) {
            return false;
        }
        BlockHitResult hit = bot.level().clip(new ClipContext(
                bot.getEyePosition(), pos.getCenter(),
                ClipContext.Block.COLLIDER, fluid, bot));
        return hit.getType() == HitResult.Type.MISS
                || (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos));
    }

    /**
     * Farm-cell observation: whether the bot's own eye can see the real outline of a crop or farmland
     * cell, or the empty cell above a field. {@link #canObserveBlock} is shape-aware too (a crop, being
     * outline-only, is visible to it), but it proves that one of the six <em>face centres</em> of a
     * <em>block</em> is struck. A farm caller asks a different question over cells that may be crop,
     * farmland or plain air (the cell a seed goes into): can the eye see the cell's top, where a hoe or
     * seed click lands, and does an empty cell count as seen (the {@link #canObserveCell} policy)? This
     * aims OUTLINE rays at points inside the state's real shape (its top centre and four inset top
     * points, so a crop packed between neighbours is still seen from above) and needs the exact ray to
     * strike this cell, so a crop hidden behind a wall or a taller crop stays hidden. Same perception
     * radius and capability gate as the other observation predicates; it sees nothing a player standing
     * at the bot's eye could not see. It is the one farm-specific query: farm code needs nothing else.
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
     * Whether the bot has NOTICED a creature (a mob, a player, another bot): the realistic perception shared with the PvP BOT
     * wrapper (see {@code docs/PERCEPTION.md}). Unlike {@link #canObserveEntity} it is not omnidirectional: the creature must be in the
     * view cone of the bot's real look vector with a clear line for the reaction time, or be heard (vanilla vibrations) and in clear
     * view, or have struck the bot; once noticed it is tracked by plain line of sight. It answers from the state kept by
     * {@link io.github.zoyluo.minecraftai.perception.CreatureSenses} (one lookup, no ray). With
     * {@code behaviour.perception.enabled=false}, for a non-creature, and under the strict capability bypass it is exactly
     * {@link #canObserveEntity}. Objects (items, containers, crops, boats) and deliberate searches for animals and villagers keep
     * {@link #canObserveEntity}: a bot glances around while it searches, and a cone would only make chores dumber.
     */
    public static boolean canNoticeCreature(AIPlayerEntity bot, Entity entity) {
        if (!(entity instanceof net.minecraft.world.entity.LivingEntity living)
                || !io.github.zoyluo.minecraftai.perception.CreatureSenses.enabled()) {
            return canObserveEntity(bot, entity);
        }
        if (CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                "observable_entity_query").allowed()) {
            return true;
        }
        return io.github.zoyluo.minecraftai.perception.CreatureSenses.INSTANCE.noticed(bot, living);
    }

    /**
     * {@link #canNoticeCreature} bounded by an explicit caller-provided range. With perception off it is
     * {@link #canObserveEntityWithin}.
     */
    public static boolean canNoticeCreatureWithin(AIPlayerEntity bot, Entity entity, int range) {
        if (!(entity instanceof net.minecraft.world.entity.LivingEntity living)
                || !io.github.zoyluo.minecraftai.perception.CreatureSenses.enabled()) {
            return canObserveEntityWithin(bot, entity, range);
        }
        if (CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                "observable_entity_query").allowed()) {
            return true;
        }
        int radius = Math.max(Math.max(1, MinecraftAiConfig.get().perception().radius()), range);
        return bot.distanceToSqr(entity) <= (double) radius * radius
                && io.github.zoyluo.minecraftai.perception.CreatureSenses.INSTANCE.noticed(bot, living);
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
        return castViewRay(bot, dx, dy, dz, range, shape, ClipContext.Fluid.ANY);
    }

    /**
     * As {@link #castViewRay(AIPlayerEntity, double, double, double, double, ViewShape)}, but
     * treats water as transparent. This is only appropriate for a route whose policy explicitly
     * permits swimming: a player underwater can see a nearby shore through water, while solid
     * terrain, range and unloaded chunks remain exactly as restrictive as the ordinary view ray.
     */
    public static ViewHit castViewRayThroughFluids(AIPlayerEntity bot, double dx, double dy, double dz,
                                                   double range, ViewShape shape) {
        return castViewRay(bot, dx, dy, dz, range, shape, ClipContext.Fluid.NONE);
    }

    private static ViewHit castViewRay(AIPlayerEntity bot, double dx, double dy, double dz,
                                        double range, ViewShape shape, ClipContext.Fluid fluid) {
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
                fluid,
                bot));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return new ViewHit(false, null, null, limit, null);
        }
        BlockPos pos = hit.getBlockPos();
        return new ViewHit(true, pos, hit.getDirection(), eye.distanceTo(hit.getLocation()), world.getBlockState(pos));
    }
}
