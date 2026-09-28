package io.github.zoyluo.minecraftai.util;

import net.minecraft.item.ItemStack;

public final class ItemStackUtil {
    private ItemStackUtil() {
    }

    /**
     * Whether a stack is damageable and one more use would break it (or has already reached its
     * max damage). Shared by every "don't select/keep using this tool, it's about to break"
     * check -- previously duplicated identically between {@code ToolSelector} and
     * {@code CreateObsidianTask}.
     */
    public static boolean isNearlyBroken(ItemStack stack) {
        return stack.isDamageable() && stack.getDamage() >= stack.getMaxDamage() - 1;
    }
}
