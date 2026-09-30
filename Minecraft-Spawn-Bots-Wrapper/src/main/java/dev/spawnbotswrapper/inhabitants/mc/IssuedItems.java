package dev.spawnbotswrapper.inhabitants.mc;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * The marker on every stack this addon itself puts into an inhabitant's inventory (dressing, rolling): a boolean in the
 * stack's vanilla custom data ({@link DataComponents#CUSTOM_DATA}, no tooltip line of its own). The rules that shape what the
 * wrapper hands out (profiles.disabledEnchantments, no ender pearls) touch MARKED stacks only, so whatever a bot picks up in the
 * world, a player's Mending armor or the pearls a player dropped, is never modified or deleted.
 * <p>
 * A marked stack never leaves the bot marked: {@link IssuedItemGuard} removes the marker from an item entity or an arrow the
 * moment it enters the world (a drop, a death, a shot). The marker rides on vanilla item data, so it survives the state
 * snapshot (which encodes stacks with the item codec), dormancy and a restart without any bookkeeping of its own.
 * Stacks with and without the marker do not merge (vanilla compares components), so an arrow the bot picked up sits in a slot
 * of its own beside the issued quiver.
 */
public final class IssuedItems {
    /** The custom-data key of the marker. */
    public static final String KEY = "pvpbot_inhabitants:issued";

    private IssuedItems() {
    }

    /** True when the wrapper issued this stack to an inhabitant and it has not left the inhabitant since. */
    public static boolean isIssued(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && !data.isEmpty() && data.copyTag().getBooleanOr(KEY, false);
    }

    /** Marks the stack as issued by the wrapper (other custom data on it is kept). */
    public static void mark(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.putBoolean(KEY, true);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    /**
     * Removes the marker (and the custom-data component itself when nothing else is left in it, so the stack stacks with
     * vanilla ones again). Returns whether the stack was marked.
     */
    public static boolean unmark(ItemStack stack) {
        if (!isIssued(stack)) {
            return false;
        }
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.remove(KEY);
        if (tag.isEmpty()) {
            stack.remove(DataComponents.CUSTOM_DATA);
        } else {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
        return true;
    }

    /** Removes the marker from every stack of the inventory; returns how many stacks were marked. */
    public static int unmarkAll(Inventory inventory) {
        int n = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (unmark(inventory.getItem(slot))) {
                n++;
            }
        }
        return n;
    }
}
