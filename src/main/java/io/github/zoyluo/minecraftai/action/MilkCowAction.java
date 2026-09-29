package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import java.util.Comparator;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Milk cow: use an empty bucket to milk one bucket of milk from the nearest adult cow. Simplified: does not simulate the right-click interaction; instead it swaps items directly + logs (same style as till/placeWater). */
public final class MilkCowAction {
    public static final double REACH = 4.0D;

    private MilkCowAction() {
    }

    public static Cow nearestCow(AIPlayerEntity bot, double radius) {
        ServerLevel world = bot.level();
        return world.getEntitiesOfClass(Cow.class, bot.getBoundingBox().inflate(radius),
                        cow -> cow.isAlive() && !cow.isBaby())
                .stream()
                .filter(cow -> io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.canObserveEntity(bot, cow))
                .min(Comparator.comparingDouble(bot::distanceToSqr))
                .orElse(null);
    }

    public static ActionResult milk(AIPlayerEntity bot) {
        if (InventoryAction.countItem(bot, Items.BUCKET) <= 0) {
            return ActionResult.failed("missing_bucket");
        }
        Cow cow = nearestCow(bot, REACH);
        if (cow == null) {
            return ActionResult.failed("no_cow_in_range");
        }
        if (!InventoryAction.removeItems(bot, Items.BUCKET, 1)) {
            return ActionResult.failed("missing_bucket");
        }
        InventoryAction.giveItem(bot, new ItemStack(Items.MILK_BUCKET, 1));
        BotLog.action(bot, "milk_cow", "pos", cow.blockPosition());
        return ActionResult.SUCCESS;
    }
}
