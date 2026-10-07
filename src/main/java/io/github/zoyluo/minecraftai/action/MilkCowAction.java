package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import java.util.Comparator;
import java.util.OptionalInt;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.item.Items;

/**
 * Milk cow: use an empty bucket on the nearest observed adult cow. This is the vanilla entity interaction
 * (the cow itself swaps one bucket for a milk bucket, keeps the rest of a bucket stack, and plays its
 * sound), after the same reach proof a player's right click needs; the inventory is never edited here.
 */
public final class MilkCowAction {
    public static final double REACH = 4.0D;

    private MilkCowAction() {
    }

    /**
     * The nearest adult cow the bot sees and can milk from where it stands: its eyes pass through glass and fences, but the
     * click needs the plain vanilla line ({@link InteractAction#useItemOnEntity}), so a cow behind a pane must not hide the one
     * in the open that is a little farther.
     */
    public static Cow nearestCow(AIPlayerEntity bot, double radius) {
        ServerLevel world = bot.level();
        return world.getEntitiesOfClass(Cow.class, bot.getBoundingBox().inflate(radius),
                        cow -> cow.isAlive() && !cow.isBaby())
                .stream()
                .filter(cow -> io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.canObserveEntity(bot, cow)
                        && StrikeLegality.hasStrikeLineOfSight(bot, cow))
                .min(Comparator.comparingDouble(bot::distanceToSqr))
                .orElse(null);
    }

    public static ActionResult milk(AIPlayerEntity bot) {
        OptionalInt bucketSlot = InventoryAction.findItem(bot, Items.BUCKET);
        if (bucketSlot.isEmpty()) {
            return ActionResult.failed("missing_bucket");
        }
        Cow cow = nearestCow(bot, REACH);
        if (cow == null) {
            return ActionResult.failed("no_cow_in_range");
        }
        if (!bot.isWithinEntityInteractionRange(cow, 0.0D)) {
            return ActionResult.failed("cow_out_of_reach");
        }
        InventoryAction.equipFromSlot(bot, bucketSlot.getAsInt());
        LookAction.lookAt(bot, cow.getBoundingBox().getCenter());
        int milkBefore = InventoryAction.countItem(bot, Items.MILK_BUCKET);
        ActionResult used = InteractAction.useItemOnEntity(bot, cow, InteractionHand.MAIN_HAND);
        if (used.isFailed()) {
            return used;
        }
        if (InventoryAction.countItem(bot, Items.MILK_BUCKET) <= milkBefore) {
            return ActionResult.failed("milk_without_inventory_change");
        }
        bot.swing(InteractionHand.MAIN_HAND);
        bot.resetLastActionTime();
        BotLog.action(bot, "milk_cow", "pos", cow.blockPosition());
        return ActionResult.SUCCESS;
    }
}
