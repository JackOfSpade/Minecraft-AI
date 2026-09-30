package dev.spawnbotswrapper.inhabitants.mc;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

/**
 * Keeps {@link IssuedItems} markers inside inhabitants' inventories. Everything that leaves a bot passes through an entity
 * being added to the world: a dropped item and a death drop are item entities, a shot arrow or a thrown trident carries a
 * pickup stack copied from the marked stack. The marker is removed from those at once, so a player who picks the item up
 * gets an ordinary item (it stacks with theirs) and a bot that picks it up later holds an unmarked one that no rule touches.
 * Nothing but this addon ever marks a stack, so any marked stack found outside an inventory has left an inhabitant.
 */
public final class IssuedItemGuard {
    private IssuedItemGuard() {
    }

    /** Registers the entity-load hook; call once from the mod entrypoint. */
    public static void register(Logger log) {
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            try {
                if (entity instanceof ItemEntity item) {
                    strip(item);
                } else if (entity instanceof AbstractArrow arrow) {
                    IssuedItems.unmark(arrow.getPickupItemStackOrigin());
                }
            } catch (RuntimeException e) {
                log.warn("could not remove the issued-item marker from {}: {}", entity, e.toString());
            }
        });
    }

    /** Replaces a marked stack of an item entity with an unmarked copy (through setItem so the entity data is updated). */
    static void strip(ItemEntity item) {
        ItemStack stack = item.getItem();
        if (IssuedItems.isIssued(stack)) {
            ItemStack copy = stack.copy();
            IssuedItems.unmark(copy);
            item.setItem(copy);
        }
    }
}
