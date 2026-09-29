package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.LookAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mixin.MerchantEntityInvokerMixin;
import java.util.Comparator;
import java.lang.reflect.Method;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.phys.AABB;

public final class TradeTask extends AbstractTask {
    private enum Phase {
        FIND_VILLAGER,
        MOVE_TO_VILLAGER,
        TRADE
    }

    private static final double SEARCH_RANGE = 16.0D;
    private static final double TRADE_RANGE = 4.0D;

    private final Item targetItem;
    private final int maxDistance;

    private Phase phase = Phase.FIND_VILLAGER;
    private Villager villager;
    private int phaseTicks;

    public TradeTask(Item targetItem, int maxDistance) {
        this.targetItem = targetItem;
        this.maxDistance = Math.max(4, maxDistance);
    }

    @Override
    public String name() {
        return "trade";
    }

    @Override
    public String describe() {
        return targetItem == null
                ? "Trading with nearby villager"
                : "Trading for " + BuiltInRegistries.ITEM.getKey(targetItem);
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return switch (phase) {
            case FIND_VILLAGER -> 0.1D;
            case MOVE_TO_VILLAGER -> 0.45D;
            case TRADE -> 0.8D;
        };
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.FIND_VILLAGER;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > 1200) {
            fail("trade_timeout");
            return;
        }
        switch (phase) {
            case FIND_VILLAGER -> findVillager(bot);
            case MOVE_TO_VILLAGER -> moveToVillager(bot);
            case TRADE -> trade(bot);
        }
    }

    private void findVillager(AIPlayerEntity bot) {
        villager = nearestVillager(bot).orElse(null);
        if (villager == null) {
            fail("no_villager_nearby");
            return;
        }
        if (bot.distanceTo(villager) <= TRADE_RANGE) {
            bot.getActionPack().stopAll();
            transition(Phase.TRADE);
            return;
        }
        ActionResult result = bot.getActionPack().startPathTo(villager.blockPosition());
        if (result.isFailed()) {
            bot.getActionPack().startWalkTo(villager.position());
        }
        transition(Phase.MOVE_TO_VILLAGER);
    }

    private void moveToVillager(AIPlayerEntity bot) {
        if (villager == null || !villager.isAlive()) {
            transition(Phase.FIND_VILLAGER);
            return;
        }
        LookAction.lookAt(bot, villager.position().add(0.0D, villager.getBbHeight() * 0.5D, 0.0D));
        if (bot.distanceTo(villager) <= TRADE_RANGE) {
            bot.getActionPack().stopAll();
            transition(Phase.TRADE);
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle() && phaseTicks > 20) {
            ActionResult result = bot.getActionPack().startPathTo(villager.blockPosition());
            if (result.isFailed()) {
                bot.getActionPack().startWalkTo(villager.position());
            }
        }
        phaseTicks++;
    }

    private void trade(AIPlayerEntity bot) {
        if (villager == null || !villager.isAlive()) {
            fail("villager_lost");
            return;
        }
        LookAction.lookAt(bot, villager.position().add(0.0D, villager.getBbHeight() * 0.5D, 0.0D));
        MerchantOffer offer = selectOffer(bot).orElse(null);
        if (offer == null) {
            fail("no_affordable_offer");
            return;
        }
        ItemStack firstBuy = offer.getCostA();
        ItemStack sell = offer.assemble();
        if (!canFit(bot, sell)) {
            fail("inventory_full");
            return;
        }
        if (!InventoryAction.removeItems(bot, firstBuy.getItem(), firstBuy.getCount())) {
            fail("missing_buy_item");
            return;
        }
        ActionResult give = InventoryAction.giveItem(bot, sell.copy());
        if (give.isFailed()) {
            fail("give_failed:" + give.reason());
            return;
        }
        offer.increaseUses();
        if (!afterUsing(villager, offer)) {
            fail("after_using_failed");
            return;
        }
        // task_completed only carries elapsed_ticks; without this, what was actually bought/sold
        // (the whole point of this task) leaves no trace at all once it succeeds.
        BotLog.action(bot, "trade_completed",
                "received", BuiltInRegistries.ITEM.getKey(sell.getItem()), "received_count", sell.getCount(),
                "paid", BuiltInRegistries.ITEM.getKey(firstBuy.getItem()), "paid_count", firstBuy.getCount());
        complete();
    }

    private Optional<Villager> nearestVillager(AIPlayerEntity bot) {
        double range = Math.min(maxDistance, SEARCH_RANGE);
        AABB box = bot.getBoundingBox().inflate(range);
        return bot.level()
                .getEntitiesOfClass(Villager.class, box, entity -> entity.isAlive() && !entity.isBaby())
                .stream()
                .filter(entity -> io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.canObserveEntity(bot, entity))
                .min(Comparator.comparingDouble(bot::distanceTo));
    }

    private Optional<MerchantOffer> selectOffer(AIPlayerEntity bot) {
        return villager.getOffers().stream()
                .filter(offer -> !offer.isOutOfStock())
                .filter(this::isSimpleOneInputOffer)
                .filter(offer -> targetItem == null || offer.getResult().is(targetItem))
                .filter(offer -> canAfford(bot, offer))
                .findFirst();
    }

    private boolean isSimpleOneInputOffer(MerchantOffer offer) {
        return offer.getCostB().isEmpty();
    }

    private boolean canAfford(AIPlayerEntity bot, MerchantOffer offer) {
        ItemStack firstBuy = offer.getCostA();
        return !firstBuy.isEmpty()
                && InventoryAction.countItem(bot, firstBuy.getItem()) >= firstBuy.getCount();
    }

    private boolean canFit(AIPlayerEntity bot, ItemStack output) {
        Inventory inventory = bot.getInventory();
        for (ItemStack stack : inventory.getNonEquipmentItems()) {
            if (stack.isEmpty()) {
                return true;
            }
            if (stack.is(output.getItem()) && stack.getCount() < Math.min(stack.getMaxStackSize(), output.getMaxStackSize())) {
                return true;
            }
        }
        return false;
    }

    private boolean afterUsing(Villager villager, MerchantOffer offer) {
        try {
            ((MerchantEntityInvokerMixin) villager).minecraftai$invokeAfterUsing(offer);
            return true;
        } catch (LinkageError | RuntimeException ignored) {
            // Keep the reflection path as a runtime fallback for loader or mapping edge cases.
        }
        for (String methodName : new String[]{"rewardTradeXp", "method_18008"}) {
            Class<?> type = villager.getClass();
            while (type != null) {
                try {
                    Method method = type.getDeclaredMethod(methodName, MerchantOffer.class);
                    method.setAccessible(true);
                    method.invoke(villager, offer);
                    return true;
                } catch (ReflectiveOperationException ignored) {
                    type = type.getSuperclass();
                }
            }
        }
        return false;
    }

    private void transition(Phase next) {
        phase = next;
        phaseTicks = 0;
    }
}
