package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.LookAction;
import io.github.zoyluo.minecraftai.action.StrikeLegality;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mixin.VillagerInvokerMixin;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.ItemCost;
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
        if (inTradeReach(bot)) {
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

    /**
     * Close enough and along a plain vanilla line. Opening a trade sends no pick ray of its own, and the eyes that chose this
     * villager see through glass, fences and leaves, which a hand cannot reach across: a villager behind a pane is walked to, not
     * traded with.
     */
    private boolean inTradeReach(AIPlayerEntity bot) {
        return bot.distanceTo(villager) <= TRADE_RANGE && StrikeLegality.hasStrikeLineOfSight(bot, villager);
    }

    private void moveToVillager(AIPlayerEntity bot) {
        if (villager == null || !villager.isAlive()) {
            transition(Phase.FIND_VILLAGER);
            return;
        }
        LookAction.lookAt(bot, villager.position().add(0.0D, villager.getBbHeight() * 0.5D, 0.0D));
        if (inTradeReach(bot)) {
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
        if (!inTradeReach(bot)) {
            transition(Phase.MOVE_TO_VILLAGER); // it walked off, or a pane or a leaf came between: the trade itself has no pick ray
            return;
        }
        // Villager#mobInteract's own gate: a sleeping, baby or busy villager (or one with nothing to sell) does not trade.
        String refusal = TradeRules.refusal(villager.isAlive(), villager.isBaby(), villager.isSleeping(),
                villager.isTrading() && villager.getTradingPlayer() != bot, !villager.getOffers().isEmpty());
        if (refusal != null) {
            fail(refusal);
            return;
        }
        LookAction.lookAt(bot, villager.position().add(0.0D, villager.getBbHeight() * 0.5D, 0.0D));
        // Opening the trade screen prices the offers for this player (reputation, hero of the village) and makes them the
        // trading player; closing it resets the prices. Everything below happens inside that window, as it does for a client.
        ((VillagerInvokerMixin) villager).minecraftai$invokeUpdateSpecialPrices(bot);
        villager.setTradingPlayer(bot);
        // Villager#mobInteract awards this as soon as its trade screen opens, even if the player
        // then closes it or cannot afford an offer. The completed-sale statistic remains below.
        bot.awardStat(Stats.TALKED_TO_VILLAGER);
        try {
            completeTrade(bot);
        } finally {
            villager.setTradingPlayer(null);
        }
    }

    private void completeTrade(AIPlayerEntity bot) {
        MerchantOffer offer = selectOffer(bot).orElse(null);
        if (offer == null) {
            fail("no_affordable_offer");
            return;
        }
        ItemStack firstBuy = offer.getCostA();
        ItemStack sell = offer.assemble();
        List<Paid> paid = takePayment(bot, offer.getItemCostA(), firstBuy.getCount());
        if (paid == null) {
            fail("missing_buy_item");
            return;
        }
        // The WHOLE result has to fit before the payment is kept: a partial insert would lose the rest of the stack.
        if (insertable(bot, sell) < sell.getCount()) {
            refund(bot, paid);
            fail("inventory_full");
            return;
        }
        ItemStack delivered = sell.copy();
        ActionResult give = InventoryAction.giveItem(bot, delivered);
        if (give.isFailed() || !delivered.isEmpty()) {
            // Not reachable after the room check above; undo instead of keeping a half trade.
            int inserted = sell.getCount() - delivered.getCount();
            refund(bot, paid);
            fail("give_failed:" + (give.isFailed() ? give.reason() : "partial_insert_" + inserted));
            return;
        }
        // The villager's own bookkeeping for a completed trade: uses, trader XP (and its orb), the level-up and the
        // reputation event that goes with it, the ambient sound timer and the trade criterion.
        villager.notifyTrade(offer);
        bot.awardStat(Stats.TRADED_WITH_VILLAGER);
        // task_completed only carries elapsed_ticks; without this, what was actually bought/sold
        // (the whole point of this task) leaves no trace at all once it succeeds.
        BotLog.action(bot, "trade_completed",
                "received", BuiltInRegistries.ITEM.getKey(sell.getItem()), "received_count", sell.getCount(),
                "paid", BuiltInRegistries.ITEM.getKey(firstBuy.getItem()), "paid_count", firstBuy.getCount());
        complete();
    }

    /**
     * The nearest villager the bot sees AND could trade with from where it stands: the eyes pass through glass and fences, but a
     * trade needs the plain vanilla line ({@link #inTradeReach}), so a villager behind a pane that is nearer than one in the open
     * would be walked to forever while the reachable one is never chosen.
     */
    private Optional<Villager> nearestVillager(AIPlayerEntity bot) {
        double range = Math.min(maxDistance, SEARCH_RANGE);
        AABB box = bot.getBoundingBox().inflate(range);
        return bot.level()
                .getEntitiesOfClass(Villager.class, box,
                        entity -> entity.isAlive() && !entity.isBaby() && !entity.isSleeping() && !entity.isTrading())
                .stream()
                .filter(entity -> io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.canObserveEntity(bot, entity)
                        && StrikeLegality.hasStrikeLineOfSight(bot, entity))
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
        if (firstBuy.isEmpty()) {
            return false;
        }
        ItemCost cost = offer.getItemCostA();
        int have = 0;
        for (ItemStack stack : bot.getInventory().getNonEquipmentItems()) {
            if (cost.test(stack)) {
                have += stack.getCount();
            }
        }
        return have >= firstBuy.getCount();
    }

    /** A stack taken out of an inventory slot as payment, kept so the trade can be undone exactly. */
    private record Paid(int slot, ItemStack stack) {
    }

    /** Takes {@code count} matching items (real stacks, whatever their components) from the main inventory, or nothing. */
    private static List<Paid> takePayment(AIPlayerEntity bot, ItemCost cost, int count) {
        List<Paid> taken = new ArrayList<>();
        List<ItemStack> main = bot.getInventory().getNonEquipmentItems();
        int remaining = count;
        for (int slot = 0; slot < main.size() && remaining > 0; slot++) {
            ItemStack stack = main.get(slot);
            if (!cost.test(stack)) {
                continue;
            }
            int take = Math.min(remaining, stack.getCount());
            taken.add(new Paid(slot, stack.split(take)));
            remaining -= take;
        }
        if (remaining > 0) {
            refund(bot, taken);
            return null;
        }
        bot.getInventory().setChanged();
        return taken;
    }

    private static void refund(AIPlayerEntity bot, List<Paid> paid) {
        List<ItemStack> main = bot.getInventory().getNonEquipmentItems();
        for (Paid piece : paid) {
            ItemStack slotStack = main.get(piece.slot());
            if (slotStack.isEmpty()) {
                main.set(piece.slot(), piece.stack());
            } else {
                slotStack.grow(piece.stack().getCount());
            }
        }
        bot.getInventory().setChanged();
    }

    /** How many of {@code output} the main inventory can take right now, counting every partly filled and empty slot. */
    private static int insertable(AIPlayerEntity bot, ItemStack output) {
        List<Integer> matching = new ArrayList<>();
        int empty = 0;
        for (ItemStack stack : bot.getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty()) {
                empty++;
            } else if (ItemStack.isSameItemSameComponents(stack, output)) {
                matching.add(stack.getCount());
            }
        }
        return TradeRules.insertable(output.getMaxStackSize(), matching, empty);
    }

    private void transition(Phase next) {
        phase = next;
        phaseTicks = 0;
    }
}
