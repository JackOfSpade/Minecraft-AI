package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class InventoryAction {
    private InventoryAction() {
    }

    public static ActionResult selectHotbar(AIPlayerEntity player, int slot) {
        if (!Inventory.isHotbarSlot(slot)) {
            return ActionResult.failed("slot_out_of_range");
        }
        player.getInventory().setSelectedSlot(slot);
        BotLog.action(player, "select_slot", "slot", slot);
        return ActionResult.SUCCESS;
    }

    public static OptionalInt findItem(AIPlayerEntity player, Item item) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getNonEquipmentItems().size(); slot++) {
            if (inventory.getNonEquipmentItems().get(slot).is(item)) {
                return OptionalInt.of(slot);
            }
        }
        if (player.getItemBySlot(EquipmentSlot.OFFHAND).is(item)) {
            return promoteOffhandSlot(player, 0);
        }
        return OptionalInt.empty();
    }

    /**
     * Moves one offhand stack into an addressable main-inventory slot without deleting the stack
     * displaced from a full inventory. Callers can then use the ordinary equip/select path.
     */
    public static OptionalInt promoteOffhandSlot(AIPlayerEntity player, int offhandSlot) {
        Inventory inventory = player.getInventory();
        if (offhandSlot != 0) {
            return OptionalInt.empty();
        }
        ItemStack moving = player.getItemBySlot(EquipmentSlot.OFFHAND);
        if (moving.isEmpty()) {
            return OptionalInt.empty();
        }
        int destination = firstEmptyMain(inventory);
        if (destination < 0) {
            destination = inventory.getSelectedSlot();
        }
        ItemStack displaced = inventory.getNonEquipmentItems().get(destination);
        inventory.getNonEquipmentItems().set(destination, moving);
        player.setItemSlot(EquipmentSlot.OFFHAND, displaced);
        inventory.setChanged();
        BotLog.action(player, "promote_offhand",
                "offhand_slot", offhandSlot,
                "main_slot", destination,
                "item", moving.getItem(),
                "swapped", !displaced.isEmpty());
        return OptionalInt.of(destination);
    }

    public static int countItem(AIPlayerEntity player, Item item) {
        int count = 0;
        var inventory = player.getInventory();
        for (ItemStack stack : inventory.getNonEquipmentItems()) {
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        ItemStack offHandStack = player.getItemBySlot(EquipmentSlot.OFFHAND);
        if (offHandStack.is(item)) {
            count += offHandStack.getCount();
        }
        return count;
    }

    public static int equipFromSlot(AIPlayerEntity player, int sourceSlot) {
        Inventory inventory = player.getInventory();
        if (sourceSlot < 0 || sourceSlot >= inventory.getNonEquipmentItems().size() || inventory.getNonEquipmentItems().get(sourceSlot).isEmpty()) {
            return -1;
        }
        if (Inventory.isHotbarSlot(sourceSlot)) {
            if (inventory.getSelectedSlot() == sourceSlot) {
                return sourceSlot;
            }
            inventory.setSelectedSlot(sourceSlot);
            inventory.setChanged();
            BotLog.action(player, "equip_slot", "source_slot", sourceSlot, "hotbar_slot", sourceSlot);
            return sourceSlot;
        }
        int hotbar = firstEmptyHotbar(inventory);
        if (hotbar < 0) {
            hotbar = inventory.getSelectedSlot();
        }
        ItemStack moving = inventory.getNonEquipmentItems().get(sourceSlot);
        ItemStack inHotbar = inventory.getNonEquipmentItems().get(hotbar);
        inventory.getNonEquipmentItems().set(hotbar, moving);
        inventory.getNonEquipmentItems().set(sourceSlot, inHotbar);
        inventory.setSelectedSlot(hotbar);
        inventory.setChanged();
        BotLog.action(player, "equip_slot", "source_slot", sourceSlot, "hotbar_slot", hotbar);
        return hotbar;
    }

    public static int firstEmptyHotbar(Inventory inventory) {
        for (int slot = 0; slot <= 8; slot++) {
            if (inventory.getNonEquipmentItems().get(slot).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    private static int firstEmptyMain(Inventory inventory) {
        for (int slot = 0; slot < inventory.getNonEquipmentItems().size(); slot++) {
            if (inventory.getNonEquipmentItems().get(slot).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    public static boolean hasItems(AIPlayerEntity player, Item item, int count) {
        return countItem(player, item) >= count;
    }

    public static boolean removeItems(AIPlayerEntity player, Item item, int count) {
        if (count <= 0) {
            return true;
        }
        if (countItem(player, item) < count) {
            return false;
        }
        Inventory inventory = player.getInventory();
        int remaining = removeFromList(inventory.getNonEquipmentItems(), item, count);
        if (remaining > 0) {
            ItemStack offHandStack = player.getItemBySlot(EquipmentSlot.OFFHAND);
            if (offHandStack.is(item)) {
                int take = Math.min(remaining, offHandStack.getCount());
                offHandStack.shrink(take);
                remaining -= take;
            }
        }
        inventory.setChanged();
        BotLog.action(player, "remove_items", "item", item, "count", count);
        return remaining == 0;
    }

    public static int removeFromList(NonNullList<ItemStack> list, Item item, int remaining) {
        for (int slot = 0; slot < list.size() && remaining > 0; slot++) {
            ItemStack stack = list.get(slot);
            if (!stack.is(item)) {
                continue;
            }
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            remaining -= take;
        }
        return remaining;
    }

    // Harmful/side-effect foods: raw chicken (30% chance of hunger), rotten flesh, pufferfish, spider eye, poisonous potato -- only fall back to these when nothing else is edible. Poison-inflicting ones are never eaten at all, see isPoisonFood.
    private static final java.util.Set<Item> HARMFUL_FOODS = java.util.Set.of(
            Items.CHICKEN, Items.ROTTEN_FLESH, Items.PUFFERFISH, Items.SPIDER_EYE, Items.POISONOUS_POTATO);

    /**
     * Whether eating {@code stack} can inflict poison or wither (pufferfish, spider eye, poisonous
     * potato, or any modded/component food whose consume effects apply them). Read from the item's own
     * consume effects instead of a hard-coded list. Poison is never worth eating automatically, even as
     * a last resort at low health; hunger-only foods (rotten flesh, raw chicken) are not poison.
     */
    public static boolean isPoisonFood(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        net.minecraft.world.item.component.Consumable consumable = stack.get(DataComponents.CONSUMABLE);
        if (consumable == null) {
            return false;
        }
        for (net.minecraft.world.item.consume_effects.ConsumeEffect effect : consumable.onConsumeEffects()) {
            if (effect instanceof net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect apply) {
                for (net.minecraft.world.effect.MobEffectInstance instance : apply.effects()) {
                    if (instance.getEffect().is(net.minecraft.world.effect.MobEffects.POISON)
                            || instance.getEffect().is(net.minecraft.world.effect.MobEffects.WITHER)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Food the bot may ever eat automatically: it has a food value and never inflicts poison/wither. */
    public static boolean isEatableFood(ItemStack stack) {
        return !stack.isEmpty() && stack.has(DataComponents.FOOD) && !isPoisonFood(stack);
    }

    public static int findFoodSlot(AIPlayerEntity player) {
        Inventory inventory = player.getInventory();
        int harmfulSlot = -1;
        for (int slot = 0; slot < inventory.getNonEquipmentItems().size(); slot++) {
            ItemStack stack = inventory.getNonEquipmentItems().get(slot);
            if (!isEatableFood(stack)) {
                continue;
            }
            if (HARMFUL_FOODS.contains(stack.getItem())) {
                if (harmfulSlot < 0) {
                    harmfulSlot = slot; // record it as the last-resort fallback
                }
                continue;
            }
            return slot; // prefer safe food first (cooked meat/bread/raw beef/pork/mutton)
        }
        boolean harmfulOffhand = false;
        ItemStack offHandStack = player.getItemBySlot(EquipmentSlot.OFFHAND);
        if (isEatableFood(offHandStack)) {
            if (HARMFUL_FOODS.contains(offHandStack.getItem())) {
                harmfulOffhand = true;
            } else {
                return promoteOffhandSlot(player, 0).orElse(-1);
            }
        }
        if (harmfulSlot >= 0) {
            return harmfulSlot;
        }
        return harmfulOffhand ? promoteOffhandSlot(player, 0).orElse(-1) : -1;
    }

    /**
     * Whether the bot carries any food that is not on the {@link #HARMFUL_FOODS} list, in the main
     * inventory or the offhand. Side-effect free: unlike {@link #findFoodSlot} it never promotes
     * the offhand stack and never logs, so it is safe to call from pure predicates every scan.
     */
    public static boolean hasSafeFood(AIPlayerEntity player) {
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (isSafeFood(stack)) {
                return true;
            }
        }
        return isSafeFood(player.getItemBySlot(EquipmentSlot.OFFHAND));
    }

    /** Like {@link #findFoodSlot} but never falls back to harmful food (returns -1 instead). */
    public static int findSafeFoodSlot(AIPlayerEntity player) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getNonEquipmentItems().size(); slot++) {
            if (isSafeFood(inventory.getNonEquipmentItems().get(slot))) {
                return slot;
            }
        }
        if (isSafeFood(player.getItemBySlot(EquipmentSlot.OFFHAND))) {
            return promoteOffhandSlot(player, 0).orElse(-1);
        }
        return -1;
    }

    private static boolean isSafeFood(ItemStack stack) {
        return isEatableFood(stack) && !HARMFUL_FOODS.contains(stack.getItem());
    }

    public static Map<String, Integer> summarize(AIPlayerEntity player) {
        Map<String, Integer> summary = new LinkedHashMap<>();
        var inventory = player.getInventory();
        for (ItemStack stack : inventory.getNonEquipmentItems()) {
            addStack(summary, stack);
        }
        addStack(summary, player.getItemBySlot(EquipmentSlot.OFFHAND));
        return summary;
    }

    public static ActionResult giveItem(AIPlayerEntity player, ItemStack stack) {
        Item item = stack.getItem();
        int count = stack.getCount();
        boolean inserted = player.getInventory().add(stack);
        player.getInventory().setChanged();
        BotLog.action(player, "give", "item", item, "count", count, "inserted_ok", inserted);
        return inserted ? ActionResult.SUCCESS : ActionResult.failed("inventory_full");
    }

    /**
     * Drops an exact total {@code count} of {@code item} through the ordinary vanilla player-drop
     * path ({@link AIPlayerEntity#drop}, the same thing a human player does with Q) -- main
     * inventory first, then offhand, mirroring {@link #removeItems}'s counting/consumption order,
     * but spawning real world {@link ItemEntity} drops instead of deleting the stacks. Used by
     * give_item/{@code GiveItemTask} to hand items to a player without any forced-pickup or
     * teleport shortcut. Fails (and restocks anything already split off) if the full count is not
     * actually present, so a caller can trust a {@code true} result means exactly {@code count}
     * left the bot's inventory as real, pickup-able drops.
     */
    public static boolean dropItems(AIPlayerEntity player, Item item, int count) {
        if (count <= 0) {
            return true;
        }
        if (countItem(player, item) < count) {
            return false;
        }
        Inventory inventory = player.getInventory();
        java.util.List<ItemStack> chunks = new java.util.ArrayList<>();
        int remaining = count;
        for (int slot = 0; slot < inventory.getNonEquipmentItems().size() && remaining > 0; slot++) {
            ItemStack stack = inventory.getNonEquipmentItems().get(slot);
            if (!stack.is(item)) {
                continue;
            }
            int take = Math.min(remaining, stack.getCount());
            chunks.add(stack.split(take));
            remaining -= take;
        }
        if (remaining > 0) {
            ItemStack offHandStack = player.getItemBySlot(EquipmentSlot.OFFHAND);
            if (offHandStack.is(item)) {
                int take = Math.min(remaining, offHandStack.getCount());
                chunks.add(offHandStack.split(take));
                remaining -= take;
            }
        }
        inventory.setChanged();
        if (remaining > 0) {
            // Unreachable given the countItem check above (no other code runs mid-tick to change
            // the inventory), but never silently vanish a partially-split remainder.
            for (ItemStack chunk : chunks) {
                giveItem(player, chunk);
            }
            return false;
        }
        boolean allDropped = true;
        for (ItemStack chunk : chunks) {
            if (player.drop(chunk, false, true) == null) {
                // Extremely rare (event-cancelled spawn): restock this chunk rather than lose it.
                giveItem(player, chunk);
                allDropped = false;
            }
        }
        if (allDropped) {
            BotLog.action(player, "give_drop", "item", item, "count", count);
        }
        return allDropped;
    }

    public static ActionResult dropSlot(AIPlayerEntity player, int slot, boolean wholeStack) {
        return dropSlotEntity(player, slot, wholeStack).isPresent()
                ? ActionResult.SUCCESS : ActionResult.failed("drop_entity_not_created");
    }

    /**
     * Drops through the ordinary survival-player path and returns the actual world entity.  Callers
     * that must prove physical containment (rather than merely freeing an inventory slot) can keep
     * the UUID and wait until the entity reaches its factual destination.  A rejected spawn is
     * rolled back into the inventory instead of turning a failed drop into direct item deletion.
     */
    public static java.util.Optional<ItemEntity> dropSlotEntity(
            AIPlayerEntity player, int slot, boolean wholeStack) {
        var inventory = player.getInventory();
        int count = slot >= 0 && slot < inventory.getContainerSize()
                ? wholeStack ? inventory.getItem(slot).getCount() : 1 : 0;
        return dropSlotEntity(player, slot, count);
    }

    /** Drops an exact positive count from one slot through the same vanilla entity path. */
    public static java.util.Optional<ItemEntity> dropSlotEntity(
            AIPlayerEntity player, int slot, int requestedCount) {
        var inventory = player.getInventory();
        if (slot < 0 || slot >= inventory.getContainerSize() || requestedCount <= 0) {
            return java.util.Optional.empty();
        }
        int count = Math.min(requestedCount, inventory.getItem(slot).getCount());
        ItemStack removed = inventory.removeItem(slot, count);
        if (removed.isEmpty()) {
            return java.util.Optional.empty();
        }
        ItemEntity entity = player.drop(removed, false, true);
        if (entity == null) {
            inventory.add(removed);
            inventory.setChanged();
            return java.util.Optional.empty();
        }
        BotLog.action(player, "drop", "slot", slot, "count", count,
                "whole_stack", inventory.getItem(slot).isEmpty());
        return java.util.Optional.of(entity);
    }

    // P0 inventory-full self-rescue: drop low-value filler blocks (cobblestone/dirt/gravel family), keeping keepEach of each (still enough for bridging/stepping blocks).
    // Mining until the inventory is full means "block broken but can't be picked up -> count doesn't increase -> mining wasted until timeout" (the hidden killer of mining tasks).
    private static final net.minecraft.world.item.Item[] JUNK_ITEMS = {
            net.minecraft.world.item.Items.COBBLESTONE, net.minecraft.world.item.Items.COBBLED_DEEPSLATE,
            net.minecraft.world.item.Items.DIRT, net.minecraft.world.item.Items.GRAVEL, net.minecraft.world.item.Items.SAND,
            net.minecraft.world.item.Items.DIORITE, net.minecraft.world.item.Items.ANDESITE,
            net.minecraft.world.item.Items.GRANITE, net.minecraft.world.item.Items.TUFF};

    public static boolean dropJunk(AIPlayerEntity player, int keepEach) {
        boolean droppedAny = false;
        for (net.minecraft.world.item.Item junk : JUNK_ITEMS) {
            int have = countItem(player, junk);
            if (have > keepEach && removeItems(player, junk, have - keepEach)) {
                // Must split into chunks by max stack size when dropping: a single ItemStack/ItemEntity's count is capped at 99,
                // dropping 2232 at once -> ItemStack.toNbt throws "range [1;99]" on save -> server crash (confirmed root cause of the geo_flow server crash).
                int toDrop = have - keepEach;
                int max = Math.max(1, new ItemStack(junk).getMaxStackSize());
                while (toDrop > 0) {
                    int chunk = Math.min(toDrop, max);
                    player.drop(new ItemStack(junk, chunk), false, true);
                    toDrop -= chunk;
                }
                BotLog.action(player, "drop_junk", "item", junk, "count", have - keepEach);
                droppedAny = true;
            }
        }
        return droppedAny;
    }

    /**
     * Drops whole low-value stacks until the requested number of main-inventory slots is free.
     * Stone-like stacks are considered only after other junk and never reduce the carried
     * emergency pool below the requested reserve (or sixteen blocks, whichever is greater).
     * Every removal goes through {@link #dropSlot}, so the items remain ordinary world drops with
     * vanilla pickup delay instead of being deleted.
     *
     * @return number of inventory stacks dropped
     */
    public static int dropJunkUntilFreeSlots(AIPlayerEntity player,
                                             int requiredFreeSlots,
                                             int emergencyStoneLikeReserve) {
        int required = Math.max(0,
                Math.min(requiredFreeSlots, player.getInventory().getNonEquipmentItems().size()));
        if (freeMainSlots(player) >= required) {
            return 0;
        }
        int stoneLike = stoneLikeCount(player);
        int protectedStoneLike = Math.min(stoneLike,
                Math.max(16, emergencyStoneLikeReserve));
        int droppedStacks = 0;
        for (int pass = 0; pass < 2 && freeMainSlots(player) < required; pass++) {
            for (int slot = 0;
                 slot < player.getInventory().getNonEquipmentItems().size() && freeMainSlots(player) < required;
                 slot++) {
                ItemStack stack = player.getInventory().getNonEquipmentItems().get(slot);
                if (stack.isEmpty() || !isJunk(stack.getItem())) {
                    continue;
                }
                boolean emergencyBlock = isStoneLike(stack.getItem());
                if (emergencyBlock != (pass == 1)) {
                    continue;
                }
                if (emergencyBlock && stoneLike - stack.getCount() < protectedStoneLike) {
                    continue;
                }
                int count = stack.getCount();
                if (!dropSlot(player, slot, true).isFailed()) {
                    droppedStacks++;
                    if (emergencyBlock) {
                        stoneLike -= count;
                    }
                }
            }
        }
        if (droppedStacks > 0) {
            BotLog.action(player, "drop_junk_for_slots",
                    "stacks", droppedStacks,
                    "free", freeMainSlots(player),
                    "required", required,
                    "stone_like", stoneLike,
                    "stone_reserve", protectedStoneLike);
        }
        return droppedStacks;
    }

    private static boolean isJunk(Item item) {
        if (isStoneLike(item)) {
            return true;
        }
        for (Item junk : JUNK_ITEMS) {
            if (item == junk) {
                return true;
            }
        }
        return false;
    }

    private static boolean isStoneLike(Item item) {
        return item == Items.COBBLESTONE
                || item == Items.COBBLED_DEEPSLATE
                || item == Items.BLACKSTONE;
    }

    private static int stoneLikeCount(AIPlayerEntity player) {
        return countItem(player, Items.COBBLESTONE)
                + countItem(player, Items.COBBLED_DEEPSLATE)
                + countItem(player, Items.BLACKSTONE);
    }

    private static int freeMainSlots(AIPlayerEntity player) {
        int free = 0;
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty()) {
                free++;
            }
        }
        return free;
    }

    private static void addStack(Map<String, Integer> summary, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        String key = stack.getItem().toString();
        summary.merge(key, stack.getCount(), Integer::sum);
    }
}
