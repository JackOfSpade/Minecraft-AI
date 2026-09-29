package io.github.zoyluo.minecraftai.baritone;

import baritone.api.IBaritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.pathing.movement.movements.MovementFall;
import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.LookAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogFields;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The water-bucket fall (a player's "MLG"): the one item use Baritone's {@code processRightClick} may make, and the safety net
 * that puts the water back into the bucket.
 *
 * <p><b>What is allowed.</b> Only while the bot is executing a Baritone {@code MovementFall} (the movement that plans the bucket
 * placement: {@code nav.baritone.waterBucketFall}, {@code allowWaterBucketFall}), only in a dimension where water does not
 * evaporate, and only for the two buckets of that movement: the water bucket, aimed (by the bot's real look direction, the same
 * ray vanilla's {@code BucketItem} traces) at a block of the movement's landing column, and the empty bucket that picks the
 * water up again, aimed at the landing column. Nothing else is ever used without a block ({@link BaritoneBreakPlacePolicy}).</p>
 *
 * <p><b>Never leave water.</b> A placed source is remembered ({@link BaritoneRegistry.Entry#placedWater}). Baritone's own movement
 * picks it up as soon as it lands in it; when the movement ends some other way (the bot drifted, the route was cancelled) {@link #recover}
 * takes the water back with the empty bucket through the same item use once the bot is within reach, and logs
 * {@code baritone_water_left} if it cannot within {@link #GIVE_UP_TICKS} ticks.</p>
 */
final class BaritoneWaterFall {
    /** Server ticks Baritone's own pickup gets before the safety net steps in. */
    static final int RECOVER_AFTER_TICKS = 8;
    /** Server ticks after which a water source that could not be taken back is reported and forgotten. */
    static final int GIVE_UP_TICKS = 400;

    private BaritoneWaterFall() {
    }

    /** Whether the config and the dimension allow a bucket fall for this bot at all. */
    static boolean enabled(AIPlayerEntity bot) {
        return MinecraftAiConfig.get().nav().baritoneCaps().waterBucketFallEnabled() && !waterEvaporates(bot, bot.blockPosition());
    }

    static boolean waterEvaporates(AIPlayerEntity bot, BlockPos pos) {
        return Boolean.TRUE.equals(bot.level().environmentAttributes().getValue(EnvironmentAttributes.WATER_EVAPORATES, pos));
    }

    /** The fall movement the bot's Baritone is executing right now, or null. Server thread. */
    static MovementFall runningFall(AIPlayerEntity bot) {
        IBaritone baritone = BaritoneRegistry.INSTANCE.find(bot.getUUID());
        if (baritone == null) {
            return null;
        }
        IPathExecutor current = baritone.getPathingBehavior().getCurrent();
        if (current == null) {
            return null;
        }
        IPath path = current.getPath();
        List<IMovement> movements = path.movements();
        int position = current.getPosition();
        if (position < 0 || position >= movements.size()) {
            return null;
        }
        return movements.get(position) instanceof MovementFall fall ? fall : null;
    }

    /** Whether {@code item} is one of the two buckets of the fall movement. */
    static boolean isFallBucket(Item item) {
        return item == Items.WATER_BUCKET || item == Items.BUCKET;
    }

    /**
     * Why the bucket in hand may not be used right now, or null when it may. The reasons are the refusal codes of
     * {@link BaritoneRefusals} ({@code item_not_allowed} is the answer for everything that is not a fall bucket).
     */
    static String refusalOf(AIPlayerEntity bot, Item item) {
        if (!isFallBucket(item)) {
            return "item_not_allowed";
        }
        if (!MinecraftAiConfig.get().nav().baritoneCaps().waterBucketFallEnabled()) {
            return "water_fall_off";
        }
        MovementFall fall = runningFall(bot);
        if (fall == null) {
            return "not_in_fall_movement";
        }
        BetterBlockPos dest = fall.getDest();
        if (waterEvaporates(bot, dest)) {
            return "water_evaporates";
        }
        HitResult hit = bot.pick(bot.blockInteractionRange(), 1.0F, item == Items.BUCKET);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return "no_target";
        }
        BlockPos target = ((BlockHitResult) hit).getBlockPos();
        boolean inColumn = target.getX() == dest.getX() && target.getZ() == dest.getZ()
                && target.getY() >= dest.getY() - 1 && target.getY() <= dest.getY() + 1;
        return inColumn ? null : "target_outside_landing_column";
    }

    /** Called after the bot used the water bucket: remembers the water source the use made, if any. */
    static void afterWaterBucketUse(AIPlayerEntity bot, BlockHitResult ray, boolean[] wasSource) {
        BlockPos first = ray.getBlockPos();
        BlockPos second = first.relative(ray.getDirection());
        BlockPos placed = null;
        if (!wasSource[0] && bot.level().getFluidState(first).isSource()) {
            placed = first;
        } else if (!wasSource[1] && bot.level().getFluidState(second).isSource()) {
            placed = second;
        }
        BaritoneRegistry.Entry entry = BaritoneRegistry.INSTANCE.entry(bot.getUUID());
        if (placed != null && entry != null) {
            entry.placedWater = placed.immutable();
            entry.placedWaterTick = bot.getServer().getTickCount();
            BotLog.action(bot, "baritone_water_placed", "pos", LogFields.pos(placed));
        }
    }

    /** Called after the bot used the empty bucket: forgets the water source when it is gone. */
    static void afterEmptyBucketUse(AIPlayerEntity bot) {
        BaritoneRegistry.Entry entry = BaritoneRegistry.INSTANCE.entry(bot.getUUID());
        if (entry != null && entry.placedWater != null && !bot.level().getFluidState(entry.placedWater).isSource()) {
            BotLog.action(bot, "baritone_water_picked_up", "pos", LogFields.pos(entry.placedWater));
            entry.placedWater = null;
        }
    }

    /**
     * The safety net, once per bot tick from {@link BaritoneDriver#beforePhysics} (also for a bot Baritone no longer drives): a water
     * source the bucket fall left behind is taken back with the empty bucket once Baritone's own movement is done with it.
     */
    static void recover(AIPlayerEntity bot, BaritoneRegistry.Entry entry) {
        BlockPos water = entry.placedWater;
        if (water == null) {
            return;
        }
        int now = bot.getServer().getTickCount();
        FluidState fluid = bot.level().getFluidState(water);
        if (!fluid.isSource()) {
            BotLog.action(bot, "baritone_water_picked_up", "pos", LogFields.pos(water), "by", "baritone");
            entry.placedWater = null;
            return;
        }
        if (now - entry.placedWaterTick >= GIVE_UP_TICKS) {
            BotLog.warn(io.github.zoyluo.minecraftai.log.LogCategory.ACTION, bot, "baritone_water_left", "pos", LogFields.pos(water), "ticks", now - entry.placedWaterTick);
            entry.placedWater = null;
            return;
        }
        if (now - entry.placedWaterTick < RECOVER_AFTER_TICKS || runningFall(bot) != null) {
            return;
        }
        Inventory inventory = bot.getInventory();
        int slot = inventory.findSlotMatchingItem(new ItemStack(Items.BUCKET));
        if (!Inventory.isHotbarSlot(slot)) {
            return; // no empty bucket at hand: keep trying until the give-up tick (it may be picked up or crafted meanwhile)
        }
        Vec3 target = Vec3.atCenterOf(water);
        Vec3 eye = bot.getEyePosition();
        if (eye.distanceTo(target) > bot.blockInteractionRange() - 0.5D) {
            return; // out of reach: the bot is elsewhere now, this is retried while it stays within the give-up window
        }
        int previous = inventory.getSelectedSlot();
        inventory.setSelectedSlot(slot);
        Rotation aim = RotationUtils.calcRotationFromVec3d(eye, target, new Rotation(bot.getYRot(), bot.getXRot()));
        LookAction.setYawPitch(bot, aim.getYaw(), aim.getPitch());
        var result = bot.gameMode.useItem(bot, bot.level(), bot.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND);
        BotLog.action(bot, "baritone_water_recover", "pos", LogFields.pos(water), "result", result);
        afterEmptyBucketUse(bot);
        if (entry.placedWater != null) {
            inventory.setSelectedSlot(previous);
        }
    }
}
