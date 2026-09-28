package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;

import java.util.Comparator;

/** Milk cow: use an empty bucket to milk one bucket of milk from the nearest adult cow. Simplified: does not simulate the right-click interaction; instead it swaps items directly + logs (same style as till/placeWater). */
public final class MilkCowAction {
    public static final double REACH = 4.0D;

    private MilkCowAction() {
    }

    public static CowEntity nearestCow(AIPlayerEntity bot, double radius) {
        ServerWorld world = bot.getEntityWorld();
        return world.getEntitiesByClass(CowEntity.class, bot.getBoundingBox().expand(radius),
                        cow -> cow.isAlive() && !cow.isBaby())
                .stream()
                .filter(cow -> io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.canObserveEntity(bot, cow))
                .min(Comparator.comparingDouble(bot::squaredDistanceTo))
                .orElse(null);
    }

    public static ActionResult milk(AIPlayerEntity bot) {
        if (InventoryAction.countItem(bot, Items.BUCKET) <= 0) {
            return ActionResult.failed("missing_bucket");
        }
        CowEntity cow = nearestCow(bot, REACH);
        if (cow == null) {
            return ActionResult.failed("no_cow_in_range");
        }
        if (!InventoryAction.removeItems(bot, Items.BUCKET, 1)) {
            return ActionResult.failed("missing_bucket");
        }
        InventoryAction.giveItem(bot, new ItemStack(Items.MILK_BUCKET, 1));
        BotLog.action(bot, "milk_cow", "pos", cow.getBlockPos());
        return ActionResult.SUCCESS;
    }
}
