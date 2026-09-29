package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogFields;
import io.github.zoyluo.minecraftai.mining.assist.BotEdits;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import io.github.zoyluo.minecraftai.pathfinding.AStarPathfinder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

public final class BuildAction {
    // Shared with ObservableWorldQuery.FACE_SAMPLE_INSET (both sample the same 3x3 inset grid on a face).
    private static final double FACE_SAMPLE_INSET = ObservableWorldQuery.FACE_SAMPLE_INSET;
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

    private BuildAction() {
    }

    public static ActionResult placeBlock(AIPlayerEntity player, BlockPos against, Direction face, InteractionHand hand) {
        double reach = player.blockInteractionRange();
        double sampleRange = exactPlacementSampleRange(
                MinecraftAiConfig.get().perception().radius(), reach);
        // Vanilla measures block interaction reach against the block's bounding box, not its
        // center. The center may be outside reach while a face inset is still a legal click.
        ItemStack stack = player.getItemInHand(hand);
        if (stack.isEmpty()) {
            BotLog.warn(io.github.zoyluo.minecraftai.log.LogCategory.ERROR, player, "place_failed", "reason", "empty_hand");
            return ActionResult.failed("empty_hand");
        }

        // Prove the exact support face inside both physical interaction reach and configured
        // perception before asking vanilla about that support or reading the destination.
        BlockHitResult hit = visibleSupportFaceHit(player, against, face, sampleRange);
        if (hit == null) {
            return ActionResult.failed("support_face_not_visible");
        }
        if (!player.isWithinBlockInteractionRange(against, 0.0D)) {
            return ActionResult.failed("support_out_of_reach_or_sight");
        }
        BlockPos destination = against.relative(face);
        Use use = useItemOnHit(player, hit, hand);
        net.minecraft.world.InteractionResult result = use.result();
        if (use.placed()) {
            player.swing(hand);
            player.resetLastActionTime();
            return ActionResult.SUCCESS;
        }
        String reason = result.consumesAction() ? "accepted_without_block_change" : result.getClass().getSimpleName();
        BotLog.warn(io.github.zoyluo.minecraftai.log.LogCategory.ERROR, player, "place_failed",
                "pos", LogFields.pos(destination), "reason", reason);
        return ActionResult.failed("interact_block_" + reason);
    }

    /**
     * Outcome of {@link #useItemOnHit}.
     *
     * @param result      what the vanilla interaction returned
     * @param placed      the interaction consumed the click and changed the block next to the clicked face (a block was placed)
     * @param destination the cell a placement would have filled
     */
    public record Use(net.minecraft.world.InteractionResult result, boolean placed, BlockPos destination) {
    }

    /**
     * Uses the held item of {@code hand} on the exact hit {@code hit}, the way a client's right click does, and keeps the mod's
     * books when that placed a block (path cache invalidation, bot-edit ledger, the {@code place} action log line). This is
     * the single place a block is put down by a click: {@link #placeBlock} finds a support face and aims for it, then ends
     * here; a caller that has already aimed (a movement driver such as Baritone, whose right click may just as well open a
     * door or a gate) hands over its own hit and ends here too. It does not aim, check reach or swing: the caller owns those.
     */
    public static Use useItemOnHit(AIPlayerEntity player, BlockHitResult hit, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        var item = stack.getItem();
        BlockPos destination = hit.getBlockPos().relative(hit.getDirection());
        var before = player.level().getBlockState(destination);
        net.minecraft.world.InteractionResult result = player.gameMode.useItemOn(
                player,
                player.level(),
                stack,
                hand,
                hit);
        boolean placed = result.consumesAction() && !player.level().getBlockState(destination).equals(before);
        if (placed) {
            AStarPathfinder.invalidateCache("block_place");
            BotEdits.notePlaced(player, destination);
            BotLog.action(player, "place", "pos", LogFields.pos(destination), "face", hit.getDirection(), "item", item);
        }
        return new Use(result, placed, destination);
    }

    /**
     * The perception proof {@link #placeBlock} demands, for a click a movement driver aimed itself (Baritone, whose right click
     * placing a block or opening a door arrives as a ready-made {@code hit}): the clicked face must be inside the interaction
     * reach and the configured perception radius, and a pure ray from the bot's eye to the hit point (no head turn, no state
     * change) must strike exactly that face of that block, so a face hidden behind another block is refused. Returns null when
     * the click is acceptable, otherwise the same failure reason {@link #placeBlock} would give
     * ({@code support_out_of_reach_or_sight} or {@code support_face_not_visible}).
     */
    public static String supportFaceRefusal(AIPlayerEntity player, BlockHitResult hit) {
        BlockPos against = hit.getBlockPos();
        Direction face = hit.getDirection();
        double sampleRange = exactPlacementSampleRange(
                MinecraftAiConfig.get().perception().radius(), player.blockInteractionRange());
        Vec3 eye = player.getEyePosition();
        Vec3 target = hit.getLocation();
        if (eye.distanceToSqr(target) > sampleRange * sampleRange || !player.isWithinBlockInteractionRange(against, 0.0D)) {
            return "support_out_of_reach_or_sight";
        }
        BlockHitResult seen = rayTo(player, eye, target, sampleRange);
        if (seen == null || seen.getType() != HitResult.Type.BLOCK || !against.equals(seen.getBlockPos()) || seen.getDirection() != face) {
            return "support_face_not_visible";
        }
        return null;
    }

    public static ActionResult placeBlockAt(AIPlayerEntity player, BlockPos pos) {
        ActionResult lastFailure = ActionResult.failed("no_adjacent_block");
        // Do not pre-filter supports through canObserveBlock's six face-center rays. A support
        // can expose only a clickable edge. placeBlock provides the strict observation proof by
        // requiring an exact vanilla ray hit before it reads or interacts with the destination.
        BlockPos below = pos.below();
        ActionResult belowResult = placeBlock(player, below, Direction.UP, InteractionHand.MAIN_HAND);
        if (belowResult.isSuccess()) {
            return belowResult;
        }
        lastFailure = preferPlacementFailure(lastFailure, belowResult);

        for (Direction direction : Direction.values()) {
            BlockPos against = pos.relative(direction.getOpposite());
            if (against.equals(below)) {
                continue;
            }
            ActionResult result = placeBlock(player, against, direction, InteractionHand.MAIN_HAND);
            if (result.isSuccess()) {
                return result;
            }
            lastFailure = preferPlacementFailure(lastFailure, result);
        }
        if (MinecraftAiConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL) {
            return lastFailure;
        }
        ActionResult fallback = directPlaceFallback(player, pos, InteractionHand.MAIN_HAND);
        if (fallback.isSuccess()) {
            return fallback;
        }
        return lastFailure;
    }

    static double exactPlacementSampleRange(int perceptionRadius, double interactionRange) {
        return Math.min(Math.max(1, perceptionRadius), interactionRange);
    }

    /**
     * Whether {@link #placeBlockAt} would find an acceptable support at {@code pos} -- the SAME
     * reach and obstruction test placeBlockAt itself uses ({@link #probeSupportFaceHit} +
     * {@code isWithinBlockInteractionRange}), without any world mutation, requiring an item in hand, or
     * turning the bot's head (a candidate probe must not have that side effect; see
     * {@link #probeSupportFaceHit}). Lets a caller (e.g. CraftTask's placement-candidate search)
     * filter candidate cells down to ones BuildAction would actually accept, instead of only
     * checking "open air" and then discovering support_face_not_visible after already committing
     * to a cell.
     */
    public static boolean canAcceptPlacementAt(AIPlayerEntity player, BlockPos pos) {
        double reach = player.blockInteractionRange();
        double sampleRange = exactPlacementSampleRange(
                MinecraftAiConfig.get().perception().radius(), reach);
        BlockPos below = pos.below();
        if (hasAcceptableSupportFace(player, below, Direction.UP, sampleRange)) {
            return true;
        }
        for (Direction direction : Direction.values()) {
            BlockPos against = pos.relative(direction.getOpposite());
            if (against.equals(below)) {
                continue;
            }
            if (hasAcceptableSupportFace(player, against, direction, sampleRange)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAcceptableSupportFace(AIPlayerEntity player,
                                                     BlockPos against,
                                                     Direction face,
                                                     double sampleRange) {
        return probeSupportFaceHit(player, against, face, sampleRange) != null
                && player.isWithinBlockInteractionRange(against, 0.0D);
    }

    static ActionResult preferPlacementFailure(ActionResult current, ActionResult candidate) {
        if (candidate == null || !candidate.isFailed()) {
            return current;
        }
        if (current == null || !current.isFailed()
                || placementFailurePriority(candidate.reason())
                > placementFailurePriority(current.reason())) {
            return candidate;
        }
        return current;
    }

    private static int placementFailurePriority(String reason) {
        if (reason != null && reason.startsWith("interact_block_")) {
            return 4;
        }
        if ("empty_hand".equals(reason)) {
            return 3;
        }
        if ("support_out_of_reach_or_sight".equals(reason)) {
            return 2;
        }
        if ("support_face_not_visible".equals(reason)) {
            return 1;
        }
        return 0;
    }

    /**
     * Returns a vanilla ray-proven hit on the requested support face. A face can be physically
     * clickable at an exposed edge even when its center is hidden by the neighbouring mining
     * wall, so sample a small deterministic inset grid before declaring it inaccessible. This is
     * the real placement's own proof: it physically turns the bot's head to look at each sampled
     * point, exactly as a player would, before asking vanilla what that look direction hits.
     */
    private static BlockHitResult visibleSupportFaceHit(AIPlayerEntity player,
                                                        BlockPos against,
                                                        Direction face,
                                                        double sampleRange) {
        return supportFaceHit(player, against, face, sampleRange, true);
    }

    /**
     * {@link #canAcceptPlacementAt}'s query form of {@link #visibleSupportFaceHit}: the identical
     * exact-edge search and the identical reach/obstruction result, but side-effect free. Turning
     * the head is an action a real placement performs; a mere candidate probe must not perform it
     * (it would jitter the bot's look direction once per sampled cell while it is only evaluating
     * candidates). Instead each candidate ray is cast directly from the eye toward the sampled
     * point with {@link #rayTo}, the pure equivalent of what {@code Entity.raycast} computes once
     * the head is turned to face that exact point.
     */
    private static BlockHitResult probeSupportFaceHit(AIPlayerEntity player,
                                                        BlockPos against,
                                                        Direction face,
                                                        double sampleRange) {
        return supportFaceHit(player, against, face, sampleRange, false);
    }

    /** Shared exact-edge sampler behind {@link #visibleSupportFaceHit} and {@link #probeSupportFaceHit}. */
    private static BlockHitResult supportFaceHit(AIPlayerEntity player,
                                                   BlockPos against,
                                                   Direction face,
                                                   double sampleRange,
                                                   boolean rotate) {
        double sampleRangeSquared = sampleRange * sampleRange;
        Vec3 eye = player.getEyePosition();
        Vec3 center = Vec3.atCenterOf(against).add(
                face.getStepX() * 0.5D,
                face.getStepY() * 0.5D,
                face.getStepZ() * 0.5D);
        for (double[] offset : FACE_SAMPLE_OFFSETS) {
            Vec3 target = switch (face.getAxis()) {
                case X -> center.add(0.0D, offset[0], offset[1]);
                case Y -> center.add(offset[0], 0.0D, offset[1]);
                case Z -> center.add(offset[0], offset[1], 0.0D);
            };
            if (eye.distanceToSqr(target) > sampleRangeSquared) {
                continue;
            }
            BlockHitResult hit = rotate
                    ? rotateAndRaycast(player, target, sampleRange)
                    : rayTo(player, eye, target, sampleRange);
            if (hit == null
                    || hit.getType() != HitResult.Type.BLOCK
                    || hit.getBlockPos() == null
                    || !hit.getBlockPos().equals(against)
                    || hit.getDirection() != face) {
                continue;
            }
            return hit;
        }
        return null;
    }

    /** The real placement's ray: turn the head to {@code target}, then vanilla's own look-direction raycast. */
    private static BlockHitResult rotateAndRaycast(AIPlayerEntity player, Vec3 target, double sampleRange) {
        LookAction.lookAt(player, target);
        var lookedAt = player.pick(sampleRange, 1.0F, false);
        return lookedAt instanceof BlockHitResult hit ? hit : null;
    }

    /**
     * The pure equivalent of {@code Entity.pick(sampleRange, 1.0F, false)} after aiming exactly at
     * {@code target}: same start (the eye), same end (eye plus that direction times {@code sampleRange}),
     * same shape and fluid handling (OUTLINE, no fluids) -- built directly from the two points instead of
     * through the entity's own look vector, so it never reads or writes yaw or pitch.
     */
    private static BlockHitResult rayTo(AIPlayerEntity player, Vec3 eye, Vec3 target, double sampleRange) {
        Vec3 toTarget = target.subtract(eye);
        Vec3 direction = toTarget.lengthSqr() < 1.0E-9D ? new Vec3(0.0D, -1.0D, 0.0D) : toTarget.normalize();
        Vec3 end = eye.add(direction.scale(sampleRange));
        return player.level().clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
    }

    private static ActionResult directPlaceFallback(AIPlayerEntity player, BlockPos pos, InteractionHand hand) {
        double reach = player.blockInteractionRange();
        if (player.getEyePosition().distanceToSqr(pos.getCenter()) > reach * reach) {
            return ActionResult.failed("target_out_of_reach");
        }
        if (!ObservableWorldQuery.canObserveCell(player, pos)) {
            return ActionResult.failed("target_not_visible");
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return ActionResult.failed("not_block_item");
        }
        var item = stack.getItem();
        var existing = player.level().getBlockState(pos);
        // Allow replaceable cells (fluid source blocks, tall grass, etc.): capping lava is just
        // placing a block directly onto a fluid cell, a legal vanilla player action.
        if (!existing.isAir() && !existing.canBeReplaced()) {
            return ActionResult.failed("target_not_air");
        }
        var placementState = blockItem.getBlock().defaultBlockState();
        if (!placementState.canSurvive(player.level(), pos)
                || !player.level().isUnobstructed(placementState, pos, CollisionContext.of(player))) {
            return ActionResult.failed("target_blocked_or_unsupported");
        }
        if (!player.level().setBlock(pos, placementState, 3)) {
            return ActionResult.failed("world_mutation_rejected");
        }
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        player.swing(hand);
        player.resetLastActionTime();
        AStarPathfinder.invalidateCache("block_place_fallback");
        BotEdits.notePlaced(player, pos);
        BotLog.action(player, "place_fallback", "pos", LogFields.pos(pos), "item", item);
        return ActionResult.SUCCESS;
    }
}
