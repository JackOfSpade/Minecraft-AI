package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.collection.DefaultedList;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;

public final class InventoryAction {
    private InventoryAction() {
    }

    public static ActionResult selectHotbar(AIPlayerEntity player, int slot) {
        if (!PlayerInventory.isValidHotbarIndex(slot)) {
            return ActionResult.failed("slot_out_of_range");
        }
        player.getInventory().setSelectedSlot(slot);
        BotLog.action(player, "select_slot", "slot", slot);
        return ActionResult.SUCCESS;
    }

    public static OptionalInt findItem(AIPlayerEntity player, Item item) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getMainStacks().size(); slot++) {
            if (inventory.getMainStacks().get(slot).isOf(item)) {
                return OptionalInt.of(slot);
            }
        }
        if (player.getEquippedStack(EquipmentSlot.OFFHAND).isOf(item)) {
            return promoteOffhandSlot(player, 0);
        }
        return OptionalInt.empty();
    }

    /**
     * Moves one offhand stack into an addressable main-inventory slot without deleting the stack
     * displaced from a full inventory. Callers can then use the ordinary equip/select path.
     */
    public static OptionalInt promoteOffhandSlot(AIPlayerEntity player, int offhandSlot) {
        PlayerInventory inventory = player.getInventory();
        if (offhandSlot != 0) {
            return OptionalInt.empty();
        }
        ItemStack moving = player.getEquippedStack(EquipmentSlot.OFFHAND);
        if (moving.isEmpty()) {
            return OptionalInt.empty();
        }
        int destination = firstEmptyMain(inventory);
        if (destination < 0) {
            destination = inventory.getSelectedSlot();
        }
        ItemStack displaced = inventory.getMainStacks().get(destination);
        inventory.getMainStacks().set(destination, moving);
        player.equipStack(EquipmentSlot.OFFHAND, displaced);
        inventory.markDirty();
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
        for (ItemStack stack : inventory.getMainStacks()) {
            if (stack.isOf(item)) {
                count += stack.getCount();
            }
        }
        ItemStack offHandStack = player.getEquippedStack(EquipmentSlot.OFFHAND);
        if (offHandStack.isOf(item)) {
            count += offHandStack.getCount();
        }
        return count;
    }

    public static int equipFromSlot(AIPlayerEntity player, int sourceSlot) {
        PlayerInventory inventory = player.getInventory();
        if (sourceSlot < 0 || sourceSlot >= inventory.getMainStacks().size() || inventory.getMainStacks().get(sourceSlot).isEmpty()) {
            return -1;
        }
        if (PlayerInventory.isValidHotbarIndex(sourceSlot)) {
            if (inventory.getSelectedSlot() == sourceSlot) {
                return sourceSlot;
            }
            inventory.setSelectedSlot(sourceSlot);
            inventory.markDirty();
            BotLog.action(player, "equip_slot", "source_slot", sourceSlot, "hotbar_slot", sourceSlot);
            return sourceSlot;
        }
        int hotbar = firstEmptyHotbar(inventory);
        if (hotbar < 0) {
            hotbar = inventory.getSelectedSlot();
        }
        ItemStack moving = inventory.getMainStacks().get(sourceSlot);
        ItemStack inHotbar = inventory.getMainStacks().get(hotbar);
        inventory.getMainStacks().set(hotbar, moving);
        inventory.getMainStacks().set(sourceSlot, inHotbar);
        inventory.setSelectedSlot(hotbar);
        inventory.markDirty();
        BotLog.action(player, "equip_slot", "source_slot", sourceSlot, "hotbar_slot", hotbar);
        return hotbar;
    }

    public static int firstEmptyHotbar(PlayerInventory inventory) {
        for (int slot = 0; slot <= 8; slot++) {
            if (inventory.getMainStacks().get(slot).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    private static int firstEmptyMain(PlayerInventory inventory) {
        for (int slot = 0; slot < inventory.getMainStacks().size(); slot++) {
            if (inventory.getMainStacks().get(slot).isEmpty()) {
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
        PlayerInventory inventory = player.getInventory();
        int remaining = removeFromList(inventory.getMainStacks(), item, count);
        if (remaining > 0) {
            ItemStack offHandStack = player.getEquippedStack(EquipmentSlot.OFFHAND);
            if (offHandStack.isOf(item)) {
                int take = Math.min(remaining, offHandStack.getCount());
                offHandStack.decrement(take);
                remaining -= take;
            }
        }
        inventory.markDirty();
        BotLog.action(player, "remove_items", "item", item, "count", count);
        return remaining == 0;
    }

    public static int removeFromList(DefaultedList<ItemStack> list, Item item, int remaining) {
        for (int slot = 0; slot < list.size() && remaining > 0; slot++) {
            ItemStack stack = list.get(slot);
            if (!stack.isOf(item)) {
                continue;
            }
            int take = Math.min(remaining, stack.getCount());
            stack.decrement(take);
            remaining -= take;
        }
        return remaining;
    }

    // Harmful/side-effect foods: raw chicken (30% chance of poisoning), rotten flesh, pufferfish, spider eye, poisonous potato -- only fall back to these when nothing else is edible.
    private static final java.util.Set<Item> HARMFUL_FOODS = java.util.Set.of(
            Items.CHICKEN, Items.ROTTEN_FLESH, Items.PUFFERFISH, Items.SPIDER_EYE, Items.POISONOUS_POTATO);

    public static int findFoodSlot(AIPlayerEntity player) {
        PlayerInventory inventory = player.getInventory();
        int harmfulSlot = -1;
        for (int slot = 0; slot < inventory.getMainStacks().size(); slot++) {
            ItemStack stack = inventory.getMainStacks().get(slot);
            if (stack.isEmpty() || !stack.contains(DataComponentTypes.FOOD)) {
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
        ItemStack offHandStack = player.getEquippedStack(EquipmentSlot.OFFHAND);
        if (!offHandStack.isEmpty() && offHandStack.contains(DataComponentTypes.FOOD)) {
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

    public static Map<String, Integer> summarize(AIPlayerEntity player) {
        Map<String, Integer> summary = new LinkedHashMap<>();
        var inventory = player.getInventory();
        for (ItemStack stack : inventory.getMainStacks()) {
            addStack(summary, stack);
        }
        addStack(summary, player.getEquippedStack(EquipmentSlot.OFFHAND));
        return summary;
    }

    public static ActionResult giveItem(AIPlayerEntity player, ItemStack stack) {
        Item item = stack.getItem();
        int count = stack.getCount();
        boolean inserted = player.getInventory().insertStack(stack);
        player.getInventory().markDirty();
        BotLog.action(player, "give", "item", item, "count", count, "inserted_ok", inserted);
        return inserted ? ActionResult.SUCCESS : ActionResult.failed("inventory_full");
    }

    /**
     * Drops an exact total {@code count} of {@code item} through the ordinary vanilla player-drop
     * path ({@link AIPlayerEntity#dropItem}, the same thing a human player does with Q) -- main
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
        PlayerInventory inventory = player.getInventory();
        java.util.List<ItemStack> chunks = new java.util.ArrayList<>();
        int remaining = count;
        for (int slot = 0; slot < inventory.getMainStacks().size() && remaining > 0; slot++) {
            ItemStack stack = inventory.getMainStacks().get(slot);
            if (!stack.isOf(item)) {
                continue;
            }
            int take = Math.min(remaining, stack.getCount());
            chunks.add(stack.split(take));
            remaining -= take;
        }
        if (remaining > 0) {
            ItemStack offHandStack = player.getEquippedStack(EquipmentSlot.OFFHAND);
            if (offHandStack.isOf(item)) {
                int take = Math.min(remaining, offHandStack.getCount());
                chunks.add(offHandStack.split(take));
                remaining -= take;
            }
        }
        inventory.markDirty();
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
            if (player.dropItem(chunk, false, true) == null) {
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
        int count = slot >= 0 && slot < inventory.size()
                ? wholeStack ? inventory.getStack(slot).getCount() : 1 : 0;
        return dropSlotEntity(player, slot, count);
    }

    /** Drops an exact positive count from one slot through the same vanilla entity path. */
    public static java.util.Optional<ItemEntity> dropSlotEntity(
            AIPlayerEntity player, int slot, int requestedCount) {
        var inventory = player.getInventory();
        if (slot < 0 || slot >= inventory.size() || requestedCount <= 0) {
            return java.util.Optional.empty();
        }
        int count = Math.min(requestedCount, inventory.getStack(slot).getCount());
        ItemStack removed = inventory.removeStack(slot, count);
        if (removed.isEmpty()) {
            return java.util.Optional.empty();
        }
        ItemEntity entity = player.dropItem(removed, false, true);
        if (entity == null) {
            inventory.insertStack(removed);
            inventory.markDirty();
            return java.util.Optional.empty();
        }
        BotLog.action(player, "drop", "slot", slot, "count", count,
                "whole_stack", inventory.getStack(slot).isEmpty());
        return java.util.Optional.of(entity);
    }

    // P0 inventory-full self-rescue: drop low-value filler blocks (cobblestone/dirt/gravel family), keeping keepEach of each (still enough for bridging/stepping blocks).
    // Mining until the inventory is full means "block broken but can't be picked up -> count doesn't increase -> mining wasted until timeout" (the hidden killer of mining tasks).
    private static final net.minecraft.item.Item[] JUNK_ITEMS = {
            net.minecraft.item.Items.COBBLESTONE, net.minecraft.item.Items.COBBLED_DEEPSLATE,
            net.minecraft.item.Items.DIRT, net.minecraft.item.Items.GRAVEL, net.minecraft.item.Items.SAND,
            net.minecraft.item.Items.DIORITE, net.minecraft.item.Items.ANDESITE,
            net.minecraft.item.Items.GRANITE, net.minecraft.item.Items.TUFF};

    public static boolean dropJunk(AIPlayerEntity player, int keepEach) {
        boolean droppedAny = false;
        for (net.minecraft.item.Item junk : JUNK_ITEMS) {
            int have = countItem(player, junk);
            if (have > keepEach && removeItems(player, junk, have - keepEach)) {
                // Must split into chunks by max stack size when dropping: a single ItemStack/ItemEntity's count is capped at 99,
                // dropping 2232 at once -> ItemStack.toNbt throws "range [1;99]" on save -> server crash (confirmed root cause of the geo_flow server crash).
                int toDrop = have - keepEach;
                int max = Math.max(1, new ItemStack(junk).getMaxCount());
                while (toDrop > 0) {
                    int chunk = Math.min(toDrop, max);
                    player.dropItem(new ItemStack(junk, chunk), false, true);
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
                Math.min(requiredFreeSlots, player.getInventory().getMainStacks().size()));
        if (freeMainSlots(player) >= required) {
            return 0;
        }
        int stoneLike = stoneLikeCount(player);
        int protectedStoneLike = Math.min(stoneLike,
                Math.max(16, emergencyStoneLikeReserve));
        int droppedStacks = 0;
        for (int pass = 0; pass < 2 && freeMainSlots(player) < required; pass++) {
            for (int slot = 0;
                 slot < player.getInventory().getMainStacks().size() && freeMainSlots(player) < required;
                 slot++) {
                ItemStack stack = player.getInventory().getMainStacks().get(slot);
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
        for (ItemStack stack : player.getInventory().getMainStacks()) {
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
