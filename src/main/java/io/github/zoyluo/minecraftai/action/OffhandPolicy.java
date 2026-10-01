package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The offhand rule (O1) of a Minecraft-AI companion: the BEST shield it carries, else a totem of undying, else whatever it is.
 *
 * <ul>
 *   <li>Empty offhand: the best carried shield (highest {@link GearValue}, enchantments counted), else a carried totem, else nothing.
 *       So when the offhand item breaks (a shield) or is used up (a totem pops) the best replacement of the same kind takes its
 *       place at the next pass, and only when there is none the next rung of the ladder does: after the last shield a totem,
 *       after the last totem nothing.</li>
 *   <li>A totem in the offhand gives way to a carried shield at once (the totem goes back to the shield's slot): a bot that holds a
 *       totem only because it had no shield switches the moment it gets one.</li>
 *   <li>A shield in the offhand is never swapped for another one, however worn it is or whatever is carried: it is used until it
 *       breaks.</li>
 *   <li>Any other offhand item (a torch, a map, arrows, food the bot or its owner put there) is left alone.</li>
 * </ul>
 *
 * It only moves stacks between the inventory and the offhand (no sound, no teleport, nothing else), and it is part of the
 * background auto-equip pass next to the armor, so it runs whenever the bot is not busy with its own decision and at every combat
 * boundary. Shield use in combat is a separate matter ({@code CombatTask}).
 */
public final class OffhandPolicy {
    private OffhandPolicy() {
    }

    /** The pure decision, bootstrap-free. */
    public static final class Core {
        private Core() {
        }

        /** What the offhand holds, as far as the policy cares. */
        public enum Held {
            EMPTY, SHIELD, TOTEM, OTHER
        }

        /** What to put into the offhand. */
        public enum Move {
            NONE, SHIELD, TOTEM
        }

        public static Move decide(Held offhand, boolean shieldCarried, boolean totemCarried) {
            return switch (offhand) {
                case OTHER, SHIELD -> Move.NONE;
                case TOTEM -> shieldCarried ? Move.SHIELD : Move.NONE;
                case EMPTY -> shieldCarried ? Move.SHIELD : totemCarried ? Move.TOTEM : Move.NONE;
            };
        }
    }

    /**
     * Applies the rule once.
     *
     * @return true when the offhand changed
     */
    public static boolean apply(AIPlayerEntity bot) {
        Inventory inventory = bot.getInventory();
        ItemStack offhand = bot.getOffhandItem();
        Core.Held held = offhand.isEmpty() ? Core.Held.EMPTY
                : ShieldBlockability.isShield(offhand) ? Core.Held.SHIELD
                : offhand.is(Items.TOTEM_OF_UNDYING) ? Core.Held.TOTEM : Core.Held.OTHER;
        if (held == Core.Held.SHIELD || held == Core.Held.OTHER) {
            return false;
        }
        int shieldSlot = EquipAction.bestShieldSlot(inventory);
        int totemSlot = -1;
        if (shieldSlot < 0 && held == Core.Held.EMPTY) {
            for (int slot = 0; slot < inventory.getNonEquipmentItems().size() && totemSlot < 0; slot++) {
                if (inventory.getNonEquipmentItems().get(slot).is(Items.TOTEM_OF_UNDYING)) {
                    totemSlot = slot;
                }
            }
        }
        Core.Move move = Core.decide(held, shieldSlot >= 0, totemSlot >= 0);
        int source = move == Core.Move.SHIELD ? shieldSlot : move == Core.Move.TOTEM ? totemSlot : -1;
        if (source < 0) {
            return false;
        }
        ItemStack moving = inventory.getNonEquipmentItems().get(source).copy();
        ItemStack displaced = offhand.copy(); // empty or the totem: it takes the place the shield had
        bot.setItemSlot(EquipmentSlot.OFFHAND, moving);
        inventory.getNonEquipmentItems().set(source, displaced);
        inventory.setChanged();
        BotLog.action(bot, "offhand_policy", "put", move.name().toLowerCase(java.util.Locale.ROOT), "source_slot", source,
                "displaced", displaced.isEmpty() ? "none" : "totem");
        return true;
    }
}
