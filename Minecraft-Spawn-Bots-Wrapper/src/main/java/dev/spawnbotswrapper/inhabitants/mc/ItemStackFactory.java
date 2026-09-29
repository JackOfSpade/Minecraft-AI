package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.profile.BotProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * Builds a real {@link ItemStack} from a {@link BotProfile.ItemSpec}, which only carries string ids.
 * <p>
 * Uses the vanilla API only. Items and potions come from the static registries; enchantments from the
 * supplied registry lookup (a world's registry manager in the game), because enchantments are data-driven.
 * Nothing here throws for bad data: an id that does not resolve is skipped with a warning, so a profile
 * written for a different mod set degrades to a poorer loadout instead of failing the whole spawn. An unknown
 * enchantment drops only that enchantment; an unknown potion drops the whole item, because a potion without
 * its effect is worse than no potion.
 */
public final class ItemStackFactory {
    private final HolderLookup.Provider registries;

    public ItemStackFactory(HolderLookup.Provider registries) {
        this.registries = registries;
    }

    /** Parses a registry id; null when the text is not a valid identifier. */
    public static Identifier parseId(String text) {
        return text == null ? null : Identifier.tryParse(text.trim());
    }

    /**
     * The stack for {@code spec}, or empty when the item cannot be built; the reason is appended to
     * {@code warnings}. The count is clamped to the item's maximum stack size, enchantment levels to 1..255
     * (vanilla's own bounds, deliberately not to the enchantment's normal maximum), and damage only applies to
     * damageable items.
     */
    public Optional<ItemStack> build(BotProfile.ItemSpec spec, List<String> warnings) {
        try {
            return buildChecked(spec, warnings);
        } catch (RuntimeException e) {
            warnings.add("could not build " + spec.item() + ": " + e);
            return Optional.empty();
        }
    }

    private Optional<ItemStack> buildChecked(BotProfile.ItemSpec spec, List<String> warnings) {
        Identifier itemId = parseId(spec.item());
        if (itemId == null) {
            warnings.add("'" + spec.item() + "' is not a valid item id; skipped");
            return Optional.empty();
        }
        Optional<Item> item = BuiltInRegistries.ITEM.getOptional(itemId);
        if (item.isEmpty() || item.get() == Items.AIR) {
            warnings.add("unknown item " + itemId + "; skipped");
            return Optional.empty();
        }

        int maxCount = new ItemStack(item.get()).getMaxStackSize();
        int count = Math.max(1, Math.min(spec.count(), maxCount));
        if (count != spec.count()) {
            warnings.add(itemId + " count " + spec.count() + " clamped to the stack limit " + count);
        }
        ItemStack stack = new ItemStack(item.get(), count);

        if (!applyPotion(stack, spec, warnings)) {
            return Optional.empty();
        }
        applyEnchantments(stack, spec, warnings);
        applyDamage(stack, spec);
        return Optional.of(stack);
    }

    /** Returns false when a potion was requested but does not exist (the item is then not worth giving). */
    private static boolean applyPotion(ItemStack stack, BotProfile.ItemSpec spec, List<String> warnings) {
        if (spec.potion() == null) {
            return true;
        }
        Identifier potionId = parseId(spec.potion());
        Optional<Holder.Reference<Potion>> potion = potionId == null
                ? Optional.empty()
                : BuiltInRegistries.POTION.get(potionId);
        if (potion.isEmpty()) {
            warnings.add("unknown potion '" + spec.potion() + "' on " + spec.item() + "; the item was skipped");
            return false;
        }
        stack.set(DataComponents.POTION_CONTENTS, new PotionContents(potion.get()));
        return true;
    }

    private void applyEnchantments(ItemStack stack, BotProfile.ItemSpec spec, List<String> warnings) {
        if (spec.enchantments().isEmpty()) {
            return;
        }
        // Enchanted books keep their enchantments in a different component from every other item.
        DataComponentType<ItemEnchantments> type = stack.is(Items.ENCHANTED_BOOK)
                ? DataComponents.STORED_ENCHANTMENTS
                : DataComponents.ENCHANTMENTS;
        ItemEnchantments.Mutable builder =
                new ItemEnchantments.Mutable(stack.getOrDefault(type, ItemEnchantments.EMPTY));
        boolean any = false;
        // Sorted so warnings (and the order they are applied in) do not depend on map iteration order.
        List<Map.Entry<String, Integer>> wanted = new ArrayList<>(spec.enchantments().entrySet());
        wanted.sort(Map.Entry.comparingByKey());
        for (Map.Entry<String, Integer> e : wanted) {
            Identifier id = parseId(e.getKey());
            Optional<Holder.Reference<Enchantment>> entry = id == null
                    ? Optional.empty()
                    : registries.get(ResourceKey.create(Registries.ENCHANTMENT, id));
            if (entry.isEmpty()) {
                warnings.add("unknown enchantment '" + e.getKey() + "' on " + spec.item() + "; skipped");
                continue;
            }
            builder.set(entry.get(), Math.max(1, Math.min(255, e.getValue())));
            any = true;
        }
        if (any) {
            stack.set(type, builder.toImmutable());
        }
    }

    private static void applyDamage(ItemStack stack, BotProfile.ItemSpec spec) {
        if (spec.damageFraction() <= 0.0 || !stack.isDamageableItem()) {
            return;
        }
        int max = stack.getMaxDamage();
        // Never reaches max: a stack that is exactly broken would vanish on its first use.
        int damage = (int) Math.round(max * spec.damageFraction());
        stack.setDamageValue(Math.max(0, Math.min(max - 1, damage)));
    }
}
