package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * How valuable a piece of gear is, so that bots can use the CHEAPEST item that still does the job first ("worst-first"): a
 * wooden pickaxe before a stone one, a leather cap before a diamond one. Lower value goes first; at equal value the more worn
 * item goes first (see {@link Core#compare}). Enchantments add value, so an enchanted item is kept for last.
 *
 * <p>Pure: it reads item stacks only (no world, no player), because {@link ToolSelector#choose} also runs on Baritone's search
 * thread over copied stacks. The one piece of shared state it may read is the {@code behaviour.gear.worstFirst} switch through
 * {@link #worstFirstEnabled()}, an immutable config record behind a volatile reference (see {@link MinecraftAiConfig#get()}), so a
 * read from another thread sees a complete, current value. The numbers live in {@link Core}, which needs
 * no Minecraft bootstrap and is unit tested on its own.
 *
 * <p>There is no escalation of any kind: the same value order applies in danger, in missions and in dangerous places. The player
 * controls what a bot wears and wields by taking items out of its inventory. {@code behaviour.gear.worstFirst=false} restores the
 * best-first behaviour of earlier versions.
 */
public final class GearValue {
    private GearValue() {
    }

    /** True unless {@code behaviour.gear.worstFirst} is switched off. */
    public static boolean worstFirstEnabled() {
        MinecraftAiConfig config = MinecraftAiConfig.get();
        return config == null || config.behaviour() == null || config.behaviour().gearOrDefaults().worstFirstEnabled();
    }

    /** The numeric core: material values, enchantment values, caps and the comparator. Pure and bootstrap-free. */
    public static final class Core {
        /** Every enchantment together adds at most this much. */
        public static final double ENCHANT_TOTAL_CAP = 1.5D;
        /** No single enchantment adds more than this: Sharpness V on a stone tool must stay below a plain iron one. */
        public static final double ENCHANT_SINGLE_CAP = 0.95D;
        private static final double EPSILON = 1.0E-9D;

        private Core() {
        }

        /**
         * The value of an item's material from the path of its registry id (gold 1.0 &lt; wood 1.1 &lt; stone 2 &lt; copper 2.2 &lt;
         * iron 3 &lt; diamond 4 &lt; netherite 5). An item of no known material (a modded tool, shears) is bucketed by its maximum
         * durability, so a fragile one ranks with the cheap tiers and a sturdy one with the dear ones.
         */
        public static double materialValue(String path, int maxDurability) {
            if (path != null) {
                if (path.startsWith("golden_")) {
                    return 1.0D;
                }
                if (path.startsWith("wooden_")) {
                    return 1.1D;
                }
                if (path.startsWith("stone_")) {
                    return 2.0D;
                }
                if (path.startsWith("copper_")) {
                    return 2.2D;
                }
                if (path.startsWith("iron_")) {
                    return 3.0D;
                }
                if (path.startsWith("diamond_")) {
                    return 4.0D;
                }
                if (path.startsWith("netherite_")) {
                    return 5.0D;
                }
            }
            if (maxDurability <= 0) {
                return 1.5D;
            }
            if (maxDurability <= 100) {
                return 1.5D;
            }
            if (maxDurability <= 300) {
                return 2.5D;
            }
            if (maxDurability <= 1000) {
                return 3.5D;
            }
            return 4.5D;
        }

        /** True for the four protection enchantments, which are worth whole armor points per level. */
        public static boolean isProtection(String path) {
            return "protection".equals(path) || "fire_protection".equals(path)
                    || "blast_protection".equals(path) || "projectile_protection".equals(path);
        }

        /** What one enchantment adds to an item's value, before the per-enchantment cap. Curses and unknown enchantments add little. */
        public static double enchantValue(String path, int level) {
            if (path == null || level <= 0) {
                return 0.0D;
            }
            double value = switch (path) {
                case "efficiency" -> 0.12D * level;
                case "unbreaking" -> 0.15D * level;
                case "mending", "infinity" -> 0.4D;
                case "fortune", "fire_aspect" -> 0.2D * level;
                case "flame" -> 0.2D;
                case "sharpness", "knockback", "power", "punch", "feather_falling", "thorns" -> 0.1D * level;
                case "smite", "bane_of_arthropods", "looting" -> 0.05D * level;
                case "binding_curse", "vanishing_curse" -> 0.0D;
                default -> 0.1D;
            };
            return Math.min(ENCHANT_SINGLE_CAP, value);
        }

        /** The total of several enchantment values, capped. */
        public static double capTotal(double sum) {
            return Math.max(0.0D, Math.min(ENCHANT_TOTAL_CAP, sum));
        }

        /** An armor piece's value in armor points: armor + 0.25 * toughness + 2 * knockback resistance + protection levels. */
        public static double armorPoints(double armor, double toughness, double knockbackResistance, int protectionLevels) {
            return armor + 0.25D * toughness + 2.0D * knockbackResistance + Math.max(0, protectionLevels);
        }

        /** An armor piece is nearly broken when at most max(3, 5% of its maximum durability) uses remain. */
        public static boolean armorNearlyBroken(int remaining, int maxDurability) {
            return maxDurability > 0 && remaining <= Math.max(3, (int) Math.ceil(maxDurability * 0.05D));
        }

        /** Orders by value (lower first), then by remaining durability (more worn first). Negative: {@code a} goes first. */
        public static int compare(double valueA, int remainingA, double valueB, int remainingB) {
            if (Math.abs(valueA - valueB) > EPSILON) {
                return valueA < valueB ? -1 : 1;
            }
            return Integer.compare(remainingA, remainingB);
        }
    }

    /** Value of a tool or weapon: its material plus its (capped) enchantments. Silk Touch is a flag, see {@link #hasSilkTouch}. */
    public static double toolValue(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0.0D;
        }
        return Core.materialValue(path(stack), stack.getMaxDamage()) + enchantSum(stack, false);
    }

    /** Value of an armor piece in the slot it is worn in (protection enchantments are armor points, the others are capped). */
    public static double armorValue(ItemStack stack, EquipmentSlot slot) {
        if (stack.isEmpty()) {
            return 0.0D;
        }
        double armor = modifierSum(stack, slot, Attributes.ARMOR);
        double toughness = modifierSum(stack, slot, Attributes.ARMOR_TOUGHNESS);
        double knockback = modifierSum(stack, slot, Attributes.KNOCKBACK_RESISTANCE);
        return Core.armorPoints(armor, toughness, knockback, protectionLevels(stack)) + enchantSum(stack, true);
    }

    /** The armor points an item gives in {@code slot} (0 for an elytra, a carved pumpkin, a head). */
    public static double armorPointsOf(ItemStack stack, EquipmentSlot slot) {
        return stack.isEmpty() ? 0.0D : modifierSum(stack, slot, Attributes.ARMOR);
    }

    public static boolean hasSilkTouch(ItemStack stack) {
        return hasEnchantment(stack, "silk_touch");
    }

    public static boolean hasBindingCurse(ItemStack stack) {
        return hasEnchantment(stack, "binding_curse");
    }

    /** Remaining uses; a huge number for an item that cannot be damaged. */
    public static int remaining(ItemStack stack) {
        return stack.isDamageableItem() ? Math.max(0, stack.getMaxDamage() - stack.getDamageValue()) : Integer.MAX_VALUE;
    }

    public static boolean armorNearlyBroken(ItemStack stack) {
        return stack.isDamageableItem() && Core.armorNearlyBroken(remaining(stack), stack.getMaxDamage());
    }

    private static String path(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }

    private static boolean hasEnchantment(ItemStack stack, String path) {
        ItemEnchantments enchantments = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        for (Holder<Enchantment> holder : enchantments.keySet()) {
            if (path.equals(enchantPath(holder)) && enchantments.getLevel(holder) > 0) {
                return true;
            }
        }
        return false;
    }

    private static String enchantPath(Holder<Enchantment> holder) {
        return holder.unwrapKey().map(key -> key.identifier().getPath()).orElse(null);
    }

    private static int protectionLevels(ItemStack stack) {
        ItemEnchantments enchantments = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        int levels = 0;
        for (Holder<Enchantment> holder : enchantments.keySet()) {
            if (Core.isProtection(enchantPath(holder))) {
                levels += enchantments.getLevel(holder);
            }
        }
        return levels;
    }

    /** The capped total of the stack's enchantments; with {@code armor} the protection enchantments are left out (they are armor points). */
    private static double enchantSum(ItemStack stack, boolean armor) {
        ItemEnchantments enchantments = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        double sum = 0.0D;
        for (Holder<Enchantment> holder : enchantments.keySet()) {
            String path = enchantPath(holder);
            if (armor && Core.isProtection(path)) {
                continue;
            }
            sum += Core.enchantValue(path, enchantments.getLevel(holder));
        }
        return Core.capTotal(sum);
    }

    private static double modifierSum(ItemStack stack, EquipmentSlot slot, Holder<Attribute> attribute) {
        double[] value = {0.0D};
        stack.forEachModifier(slot, (entry, modifier) -> {
            if (entry.equals(attribute) && modifier.operation() == AttributeModifier.Operation.ADD_VALUE) {
                value[0] += modifier.amount();
            }
        });
        return value[0];
    }

}
