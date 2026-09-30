package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.brain.BrainCoordinator;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.enchantment.Repairable;

/**
 * Chat warning for nearly broken gear (Minecraft-AI companions only). When an eligible item drops below
 * {@code behaviour.gear.durabilityWarnings.thresholdPercent} (default 10) of its durability, the bot says so in chat, at once,
 * once per item and crossing. It is a chat line and nothing else: no task change, no pause, no equip change, no LLM turn.
 *
 * <p><b>Eligible items</b> are the ones with no ore tier to fall back on or with a costly one: everything damageable that vanilla
 * repairs with a diamond or a netherite ingot (the {@code repairable} item component: diamond and netherite armor, swords, axes,
 * pickaxes, shovels, hoes, spears), plus the damageable items that have no such repair ingredient of their own: shield, bow,
 * crossbow, trident, mace, elytra and fishing rod. The component is the vanilla item data, so a modded diamond-class item is
 * covered too and no item name is ever string-matched for the material.
 *
 * <p><b>Once per crossing</b> is remembered on the stack itself (a small marker in its custom data), so it follows the item through
 * the inventory, the armor slots and the offhand and survives a save. The marker is removed as soon as the item is at or above the
 * threshold again (an anvil, a grindstone, Mending), which re-arms the warning. Several items that cross in the same tick all
 * speak in that tick, one line each: there is no rate limit and no queue.
 */
public final class DurabilityWarnings {
    /** The custom-data key of the "already warned" marker. */
    static final String MARKER = "minecraftai_low_durability_warned";

    private static final EquipmentSlot[] WORN = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND
    };

    private DurabilityWarnings() {
    }

    /** The pure decisions: threshold arithmetic, eligibility by id, the once-per-crossing step and the message. Bootstrap-free. */
    public static final class Core {
        private Core() {
        }

        /** What one scan does with one stack. */
        public enum Step {
            /** Nothing to do. */
            NONE,
            /** Warn and set the marker. */
            WARN,
            /** The item is healthy again: clear the marker so the next crossing warns. */
            REARM
        }

        /** True when {@code (max - damage) / max < thresholdPercent / 100}; an item that cannot break (max 0) never is. */
        public static boolean isLow(int remaining, int maxDurability, double thresholdPercent) {
            return maxDurability > 0 && remaining >= 0 && (double) remaining * 100.0D < thresholdPercent * maxDurability;
        }

        /**
         * The registry paths of the damageable items that carry no diamond or netherite repair ingredient but are still covered:
         * shield, bow, crossbow, trident, mace, elytra, fishing rod.
         */
        public static boolean isCoveredByName(String path) {
            return switch (path == null ? "" : path) {
                case "shield", "bow", "crossbow", "trident", "mace", "elytra", "fishing_rod" -> true;
                default -> false;
            };
        }

        /** Eligibility: a covered item, or one that vanilla repairs with a diamond or a netherite ingot. */
        public static boolean isEligible(String path, boolean repairedWithDiamondOrNetherite) {
            return repairedWithDiamondOrNetherite || isCoveredByName(path);
        }

        /** The once-per-crossing state machine: warn on the first scan below the threshold, re-arm on the first scan at or above it. */
        public static Step step(boolean low, boolean marked) {
            if (low && !marked) {
                return Step.WARN;
            }
            if (!low && marked) {
                return Step.REARM;
            }
            return Step.NONE;
        }

        /** The message, for example: My diamond pickaxe is about to break (14/1561 left). The item's registry path, underscores as spaces. */
        public static String message(String path, int remaining, int maxDurability) {
            return "My " + (path == null ? "item" : path.replace('_', ' ')) + " is about to break (" + remaining + "/"
                    + maxDurability + " left).";
        }
    }

    /**
     * Scans the bot's carried, worn and offhand items and speaks for every one that just crossed the threshold. Called once per
     * server tick per bot; it reads the config on each call, never throws and never touches anything but the marker.
     *
     * @return how many warnings were sent
     */
    public static int tickBot(AIPlayerEntity bot) {
        MinecraftAiConfig config = MinecraftAiConfig.get();
        if (config == null || config.behaviour() == null) {
            return 0;
        }
        MinecraftAiConfig.DurabilityWarnings settings = config.behaviour().gearOrDefaults().durabilityWarningsOrDefaults();
        if (!settings.enabledOn()) {
            return 0;
        }
        double threshold = settings.thresholdOrDefault();
        int sent = 0;
        try {
            Inventory inventory = bot.getInventory();
            for (int slot = 0; slot < inventory.getNonEquipmentItems().size(); slot++) {
                sent += scan(bot, inventory.getNonEquipmentItems().get(slot), threshold);
            }
            for (EquipmentSlot slot : WORN) {
                sent += scan(bot, bot.getItemBySlot(slot), threshold);
            }
        } catch (RuntimeException exception) {
            BotLog.action(bot, "durability_warning_error", "error", String.valueOf(exception));
        }
        return sent;
    }

    private static int scan(AIPlayerEntity bot, ItemStack stack, double threshold) {
        if (stack.isEmpty() || !stack.isDamageableItem()) {
            return 0;
        }
        int max = stack.getMaxDamage();
        int remaining = max - stack.getDamageValue();
        boolean low = Core.isLow(remaining, max, threshold);
        boolean marked = isMarked(stack);
        if (!low && !marked) {
            return 0;
        }
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        switch (Core.step(low, marked)) {
            case WARN -> {
                if (!Core.isEligible(path, repairedWithDiamondOrNetherite(stack))) {
                    return 0;
                }
                setMarker(stack, true);
                BotLog.action(bot, "durability_warning", "item", path, "remaining", remaining, "max", max);
                BrainCoordinator.INSTANCE.sendBotReply(bot, Core.message(path, remaining, max));
                return 1;
            }
            case REARM -> setMarker(stack, false);
            default -> {
            }
        }
        return 0;
    }

    /** True when vanilla's repairable component of the stack accepts a diamond or a netherite ingot. */
    static boolean repairedWithDiamondOrNetherite(ItemStack stack) {
        Repairable repairable = stack.get(DataComponents.REPAIRABLE);
        if (repairable == null) {
            return false;
        }
        return repairable.isValidRepairItem(new ItemStack(Items.DIAMOND))
                || repairable.isValidRepairItem(new ItemStack(Items.NETHERITE_INGOT));
    }

    static boolean isMarked(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().contains(MARKER);
    }

    private static void setMarker(ItemStack stack, boolean on) {
        if (on) {
            stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY, data -> data.update(tag -> tag.putBoolean(MARKER, true)));
            return;
        }
        CustomData cleared = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).update(tag -> tag.remove(MARKER));
        if (cleared.isEmpty()) {
            stack.remove(DataComponents.CUSTOM_DATA); // an item with no other custom data goes back to being an ordinary stack
        } else {
            stack.set(DataComponents.CUSTOM_DATA, cleared);
        }
    }
}
