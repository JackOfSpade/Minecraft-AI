package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogFields;
import io.github.zoyluo.minecraftai.mining.assist.BotEdits;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import io.github.zoyluo.minecraftai.pathfinding.AStarPathfinder;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.block.ShapeContext;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

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

    public static ActionResult placeBlock(AIPlayerEntity player, BlockPos against, Direction face, Hand hand) {
        double reach = player.getBlockInteractionRange();
        double sampleRange = exactPlacementSampleRange(
                MinecraftAiConfig.get().perception().radius(), reach);
        // Vanilla measures block interaction reach against the block's bounding box, not its
        // center. The center may be outside reach while a face inset is still a legal click.
        ItemStack stack = player.getStackInHand(hand);
        if (stack.isEmpty()) {
            BotLog.warn(io.github.zoyluo.minecraftai.log.LogCategory.ERROR, player, "place_failed", "reason", "empty_hand");
            return ActionResult.failed("empty_hand");
        }
        var item = stack.getItem();

        // Prove the exact support face inside both physical interaction reach and configured
        // perception before asking vanilla about that support or reading the destination.
        BlockHitResult hit = visibleSupportFaceHit(player, against, face, sampleRange);
        if (hit == null) {
            return ActionResult.failed("support_face_not_visible");
        }
        if (!player.canInteractWithBlockAt(against, 0.0D)) {
            return ActionResult.failed("support_out_of_reach_or_sight");
        }
        BlockPos destination = against.offset(face);
        var before = player.getEntityWorld().getBlockState(destination);
        net.minecraft.util.ActionResult result = player.interactionManager.interactBlock(
                player,
                player.getEntityWorld(),
                stack,
                hand,
                hit);
        var after = player.getEntityWorld().getBlockState(destination);
        if (result.isAccepted() && !after.equals(before)) {
            player.swingHand(hand);
            player.updateLastActionTime();
            AStarPathfinder.invalidateCache("block_place");
            BotEdits.notePlaced(player, destination);
            BotLog.action(player, "place", "pos", LogFields.pos(destination), "face", face, "item", item);
            return ActionResult.SUCCESS;
        }
        String reason = result.isAccepted() ? "accepted_without_block_change" : result.getClass().getSimpleName();
        BotLog.warn(io.github.zoyluo.minecraftai.log.LogCategory.ERROR, player, "place_failed",
                "pos", LogFields.pos(destination), "reason", reason);
        return ActionResult.failed("interact_block_" + reason);
    }

    public static ActionResult placeBlockAt(AIPlayerEntity player, BlockPos pos) {
        ActionResult lastFailure = ActionResult.failed("no_adjacent_block");
        // Do not pre-filter supports through canObserveBlock's six face-center rays. A support
        // can expose only a clickable edge. placeBlock provides the strict observation proof by
        // requiring an exact vanilla ray hit before it reads or interacts with the destination.
        BlockPos below = pos.down();
        ActionResult belowResult = placeBlock(player, below, Direction.UP, Hand.MAIN_HAND);
        if (belowResult.isSuccess()) {
            return belowResult;
        }
        lastFailure = preferPlacementFailure(lastFailure, belowResult);

        for (Direction direction : Direction.values()) {
            BlockPos against = pos.offset(direction.getOpposite());
            if (against.equals(below)) {
                continue;
            }
            ActionResult result = placeBlock(player, against, direction, Hand.MAIN_HAND);
            if (result.isSuccess()) {
                return result;
            }
            lastFailure = preferPlacementFailure(lastFailure, result);
        }
        if (MinecraftAiConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL) {
            return lastFailure;
        }
        ActionResult fallback = directPlaceFallback(player, pos, Hand.MAIN_HAND);
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
     * visible/in-reach support-face predicate placeBlockAt itself uses ({@link
     * #visibleSupportFaceHit} + {@code canInteractWithBlockAt}), without any world mutation or
     * requiring an item in hand. Lets a caller (e.g. CraftTask's placement-candidate search) filter
     * candidate cells down to ones BuildAction would actually accept, instead of only checking
     * "open air" and then discovering support_face_not_visible after already committing to a cell.
     */
    public static boolean canAcceptPlacementAt(AIPlayerEntity player, BlockPos pos) {
        double reach = player.getBlockInteractionRange();
        double sampleRange = exactPlacementSampleRange(
                MinecraftAiConfig.get().perception().radius(), reach);
        BlockPos below = pos.down();
        if (hasAcceptableSupportFace(player, below, Direction.UP, sampleRange)) {
            return true;
        }
        for (Direction direction : Direction.values()) {
            BlockPos against = pos.offset(direction.getOpposite());
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
        return visibleSupportFaceHit(player, against, face, sampleRange) != null
                && player.canInteractWithBlockAt(against, 0.0D);
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
     * wall, so sample a small deterministic inset grid before declaring it inaccessible.
     */
    private static BlockHitResult visibleSupportFaceHit(AIPlayerEntity player,
                                                        BlockPos against,
                                                        Direction face,
                                                        double sampleRange) {
        double sampleRangeSquared = sampleRange * sampleRange;
        Vec3d eye = player.getEyePos();
        Vec3d center = Vec3d.ofCenter(against).add(
                face.getOffsetX() * 0.5D,
                face.getOffsetY() * 0.5D,
                face.getOffsetZ() * 0.5D);
        for (double[] offset : FACE_SAMPLE_OFFSETS) {
            Vec3d target = switch (face.getAxis()) {
                case X -> center.add(0.0D, offset[0], offset[1]);
                case Y -> center.add(offset[0], 0.0D, offset[1]);
                case Z -> center.add(offset[0], offset[1], 0.0D);
            };
            if (eye.squaredDistanceTo(target) > sampleRangeSquared) {
                continue;
            }
            LookAction.lookAt(player, target);
            var lookedAt = player.raycast(sampleRange, 1.0F, false);
            if (!(lookedAt instanceof BlockHitResult hit)
                    || hit.getBlockPos() == null
                    || !hit.getBlockPos().equals(against)
                    || hit.getSide() != face) {
                continue;
            }
            return hit;
        }
        return null;
    }

    private static ActionResult directPlaceFallback(AIPlayerEntity player, BlockPos pos, Hand hand) {
        double reach = player.getBlockInteractionRange();
        if (player.getEyePos().squaredDistanceTo(pos.toCenterPos()) > reach * reach) {
            return ActionResult.failed("target_out_of_reach");
        }
        if (!ObservableWorldQuery.canObserveCell(player, pos)) {
            return ActionResult.failed("target_not_visible");
        }
        ItemStack stack = player.getStackInHand(hand);
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return ActionResult.failed("not_block_item");
        }
        var item = stack.getItem();
        var existing = player.getEntityWorld().getBlockState(pos);
        // Allow replaceable cells (fluid source blocks, tall grass, etc.): capping lava is just
        // placing a block directly onto a fluid cell, a legal vanilla player action.
        if (!existing.isAir() && !existing.isReplaceable()) {
            return ActionResult.failed("target_not_air");
        }
        var placementState = blockItem.getBlock().getDefaultState();
        if (!placementState.canPlaceAt(player.getEntityWorld(), pos)
                || !player.getEntityWorld().canPlace(placementState, pos, ShapeContext.of(player))) {
            return ActionResult.failed("target_blocked_or_unsupported");
        }
        if (!player.getEntityWorld().setBlockState(pos, placementState, 3)) {
            return ActionResult.failed("world_mutation_rejected");
        }
        if (!player.getAbilities().creativeMode) {
            stack.decrement(1);
        }
        player.swingHand(hand);
        player.updateLastActionTime();
        AStarPathfinder.invalidateCache("block_place_fallback");
        BotEdits.notePlaced(player, pos);
        BotLog.action(player, "place_fallback", "pos", LogFields.pos(pos), "item", item);
        return ActionResult.SUCCESS;
    }
}
