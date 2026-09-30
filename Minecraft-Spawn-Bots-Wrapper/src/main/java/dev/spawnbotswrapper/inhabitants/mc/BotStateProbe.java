package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.combat.StateSnapshot;
import dev.spawnbotswrapper.inhabitants.profile.GlobalCapabilities;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * Reads the combat-relevant state of a player-like entity into a {@link StateSnapshot} for the diagnostics.
 * READ-ONLY by construction: it calls getters only (inventory, item use, position, one line-of-sight clip) and
 * never touches PvP BOT. Every method must be called on the server thread.
 */
final class BotStateProbe {
    private BotStateProbe() {
    }

    /** What an inventory carries, for ammo and weapon questions. */
    record Tally(int arrows, int rockets, boolean hasBow, boolean hasCrossbow, boolean hasMelee) {
    }

    /** Counts ammo and weapons over the given stacks (the whole inventory in production). */
    static Tally tally(List<ItemStack> stacks) {
        int arrows = 0;
        int rockets = 0;
        boolean bow = false;
        boolean crossbow = false;
        boolean melee = false;
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (stack.is(Items.ARROW) || stack.is(Items.SPECTRAL_ARROW) || stack.is(Items.TIPPED_ARROW)) {
                arrows += stack.getCount();
            } else if (stack.is(Items.FIREWORK_ROCKET)) {
                rockets += stack.getCount();
            } else if (stack.is(Items.BOW)) {
                bow = true;
            } else if (stack.is(Items.CROSSBOW)) {
                crossbow = true;
            } else if (isMelee(stack)) {
                melee = true;
            }
        }
        return new Tally(arrows, rockets, bow, crossbow, melee);
    }

    static boolean isMelee(ItemStack stack) {
        // Components rather than item tags: they are on the item itself, so they hold before any tag is bound.
        return stack.has(DataComponents.WEAPON) || stack.has(DataComponents.KINETIC_WEAPON)
                || stack.has(DataComponents.PIERCING_WEAPON)
                || stack.is(Items.MACE) || stack.is(Items.TRIDENT);
    }

    static String itemName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "empty";
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }

    /** The bow or crossbow in use, as {@code "bow"}/{@code "crossbow"}, or null when the bot uses something else. */
    static String usedRanged(Player bot) {
        if (!bot.isUsingItem()) {
            return null;
        }
        ItemStack use = bot.getUseItem();
        if (use.is(Items.BOW)) {
            return "bow";
        }
        if (use.is(Items.CROSSBOW)) {
            return "crossbow";
        }
        return null;
    }

    /** A crossbow in either hand is loaded. */
    static boolean anyCrossbowCharged(Player bot) {
        ItemStack main = bot.getMainHandItem();
        ItemStack off = bot.getOffhandItem();
        return (main.is(Items.CROSSBOW) && CrossbowItem.isCharged(main))
                || (off.is(Items.CROSSBOW) && CrossbowItem.isCharged(off));
    }

    static boolean inWeb(Entity e) {
        return e.level().getBlockState(e.blockPosition()).is(Blocks.COBWEB)
                || e.level().getBlockState(e.blockPosition().above()).is(Blocks.COBWEB);
    }

    /** PvP BOT's GLOBAL switches (its per-bot target is internal state the adapter does not read). */
    static String upstreamText(GlobalCapabilities caps) {
        if (caps == null) {
            return null;
        }
        return "global(combat=" + (caps.combatEnabled() ? 1 : 0) + ",autoTarget=" + (caps.autoTargetEnabled() ? 1 : 0)
                + ",ranged=" + (caps.rangedEnabled() ? 1 : 0) + ";target=not-readable)";
    }

    /**
     * @param other    the attacker or nearest real player to describe distance and line of sight to; may be null
     * @param upstream see {@link #upstreamText}; may be null
     */
    static StateSnapshot snapshot(Player bot, Entity other, String upstream) {
        Inventory inv = bot.getInventory();
        java.util.ArrayList<ItemStack> all = new java.util.ArrayList<>(inv.getContainerSize());
        for (int i = 0; i < inv.getContainerSize(); i++) {
            all.add(inv.getItem(i));
        }
        Tally tally = tally(all);
        ItemStack main = bot.getMainHandItem();
        boolean using = bot.isUsingItem();
        String name = null;
        double distance = -1;
        Boolean los = null;
        if (other != null) {
            name = other instanceof Player p ? p.getName().getString()
                    : BuiltInRegistries.ENTITY_TYPE.getKey(other.getType()).getPath();
            distance = bot.distanceTo(other);
            los = bot.hasLineOfSight(other);
        }
        return new StateSnapshot(inv.getSelectedSlot(), itemName(main),
                main.is(Items.CROSSBOW) ? CrossbowItem.isCharged(main) : null, itemName(bot.getOffhandItem()),
                using, using ? itemName(bot.getUseItem()) : "none", using ? bot.getTicksUsingItem() : 0,
                using ? bot.getUseItemRemainingTicks() : 0, tally.arrows(), tally.rockets(), tally.hasBow(),
                tally.hasCrossbow(), tally.hasMelee(), name, distance, los, bot.onGround(), bot.isInWater(),
                inWeb(bot), upstream);
    }
}
