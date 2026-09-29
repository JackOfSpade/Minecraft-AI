package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogFields;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.pathfinding.AStarPathfinder;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Real survival bucket interactions.
 *
 * <p>The bucket item owns Minecraft's fluid rules, sounds, inventory exchange and game events.
 * These adapters therefore aim the bot and invoke the same item-use entry point as a player;
 * they never synthesize fluids or replace inventory stacks directly.</p>
 */
public final class BucketAction {
    private BucketAction() {
    }

    /** Fill one empty bucket from an observable still-water source. */
    public static ActionResult fillWaterSource(AIPlayerEntity bot, BlockPos source) {
        var world = bot.level();
        if (!withinReach(bot, source) || !bot.isWithinBlockInteractionRange(source, 0.0D)) {
            return ActionResult.failed("water_source_out_of_reach");
        }
        OptionalInt slot = InventoryAction.findItem(bot, Items.BUCKET);
        if (slot.isEmpty()) {
            return ActionResult.failed("missing_bucket");
        }
        InventoryAction.equipFromSlot(bot, slot.getAsInt());
        LookAction.lookAt(bot, source.getCenter());

        var lookedAt = raycastWaterSource(bot);
        if (!(lookedAt instanceof BlockHitResult hit) || !hit.getBlockPos().equals(source)) {
            return ActionResult.failed("water_source_face_not_visible");
        }
        // The source-only bucket ray is the perception boundary. Read the fluid only after the
        // same ray a player uses has actually hit this remembered source cell.
        var fluid = world.getFluidState(source);
        if (!fluid.is(FluidTags.WATER) || !fluid.isSource()) {
            return ActionResult.failed("not_water_source");
        }

        int waterBefore = InventoryAction.countItem(bot, Items.WATER_BUCKET);
        ActionResult result = InteractAction.useItemInAir(bot, InteractionHand.MAIN_HAND);
        int waterAfter = InventoryAction.countItem(bot, Items.WATER_BUCKET);
        if (result.isSuccess() && waterAfter > waterBefore) {
            finish(bot, "fill_water_bucket", source);
            return ActionResult.SUCCESS;
        }
        return ActionResult.failed(result.isFailed()
                ? result.reason()
                : "bucket_fill_without_inventory_change");
    }

    /**
     * Place water in {@code support.offset(face)} by aiming a water bucket at a real support face.
     */
    public static ActionResult placeWater(AIPlayerEntity bot, BlockPos support, Direction face) {
        BlockPos destination = support.relative(face);
        var world = bot.level();
        if (!ObservableWorldQuery.canObserveCell(bot, destination)) {
            return ActionResult.failed("water_placement_not_visible");
        }
        if (!withinReach(bot, support.getCenter().add(
                face.getStepX() * 0.5D,
                face.getStepY() * 0.5D,
                face.getStepZ() * 0.5D))
                || !bot.isWithinBlockInteractionRange(support, 0.0D)) {
            return ActionResult.failed("water_support_out_of_reach");
        }
        OptionalInt slot = InventoryAction.findItem(bot, Items.WATER_BUCKET);
        if (slot.isEmpty()) {
            return ActionResult.failed("missing_water_bucket");
        }
        InventoryAction.equipFromSlot(bot, slot.getAsInt());
        LookAction.lookAtBlock(bot, support, face);

        double reach = bot.blockInteractionRange();
        var lookedAt = bot.pick(reach, 1.0F, false);
        if (!(lookedAt instanceof BlockHitResult hit)
                || !hit.getBlockPos().equals(support)
                || hit.getDirection() != face) {
            return ActionResult.failed("water_support_face_not_visible");
        }
        // The real no-fluid ray above is the visibility boundary for supports underneath fluid.
        if (world.getBlockState(support).isAir()) {
            return ActionResult.failed("missing_water_support");
        }
        var destinationState = world.getBlockState(destination);
        if (!destinationState.isAir() && !destinationState.canBeReplaced(net.minecraft.world.level.material.Fluids.WATER)) {
            return ActionResult.failed("water_destination_blocked");
        }

        int waterBefore = InventoryAction.countItem(bot, Items.WATER_BUCKET);
        int emptyBefore = InventoryAction.countItem(bot, Items.BUCKET);
        ActionResult result = InteractAction.useItemInAir(bot, InteractionHand.MAIN_HAND);
        int waterAfter = InventoryAction.countItem(bot, Items.WATER_BUCKET);
        int emptyAfter = InventoryAction.countItem(bot, Items.BUCKET);
        if (result.isSuccess() && waterAfter < waterBefore && emptyAfter > emptyBefore) {
            finish(bot, "place_water_bucket", destination);
            return ActionResult.SUCCESS;
        }
        return ActionResult.failed(result.isFailed()
                ? result.reason()
                : "bucket_place_without_inventory_change");
    }

    private static boolean withinReach(AIPlayerEntity bot, BlockPos pos) {
        return withinReach(bot, pos.getCenter());
    }

    private static boolean withinReach(AIPlayerEntity bot, net.minecraft.world.phys.Vec3 pos) {
        double reach = bot.blockInteractionRange();
        return bot.getEyePosition().distanceToSqr(pos) <= reach * reach;
    }

    /** Matches BucketItem's SOURCE_ONLY ray so nearer flowing water does not hide its source. */
    private static HitResult raycastWaterSource(AIPlayerEntity bot) {
        double reach = bot.blockInteractionRange();
        Vec3 start = bot.getEyePosition();
        Vec3 end = start.add(bot.getViewVector(1.0F).scale(reach));
        return bot.level().clip(new ClipContext(
                start,
                end,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.SOURCE_ONLY,
                bot));
    }

    private static void finish(AIPlayerEntity bot, String action, BlockPos pos) {
        bot.swing(InteractionHand.MAIN_HAND);
        bot.resetLastActionTime();
        AStarPathfinder.invalidateCache("bucket_fluid_change");
        BotLog.action(bot, action, "pos", LogFields.pos(pos));
    }
}
