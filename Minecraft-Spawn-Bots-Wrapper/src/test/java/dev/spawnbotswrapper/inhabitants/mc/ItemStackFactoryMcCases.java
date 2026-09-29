package dev.spawnbotswrapper.inhabitants.mc;

import dev.spawnbotswrapper.inhabitants.profile.BotProfile;
import dev.spawnbotswrapper.inhabitants.profile.BotProfile.ItemSpec;
import dev.spawnbotswrapper.inhabitants.profile.ProfileVocabulary;
import org.junit.jupiter.api.Assumptions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import static org.junit.jupiter.api.Assertions.*;

/** {@link ItemStackFactory} against the real item, enchantment and potion registries, run inside {@link McSandbox}. */
public final class ItemStackFactoryMcCases {
    private ItemStackFactoryMcCases() {
    }

    private static ItemStackFactory factory() {
        return new ItemStackFactory(McBootstrap.registries());
    }

    private static ItemStack build(ItemSpec spec, List<String> warnings) {
        return factory().build(spec, warnings).orElse(null);
    }

    private static Holder<Enchantment> enchantment(String id) {
        return McBootstrap.registries()
                .get(ResourceKey.create(Registries.ENCHANTMENT, Identifier.parse(id)))
                .orElseThrow(() -> new AssertionError("enchantment " + id + " is missing from the registry"));
    }

    public static void plainItemsAreBuilt() {
        List<String> warnings = new ArrayList<>();
        ItemStack stack = build(ItemSpec.of("minecraft:diamond_sword"), warnings);
        assertNotNull(stack);
        assertTrue(stack.is(Items.DIAMOND_SWORD));
        assertEquals(1, stack.getCount());
        assertEquals(0, stack.getDamageValue());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    public static void enchantmentsAreAppliedWithTheirLevels() {
        List<String> warnings = new ArrayList<>();
        Map<String, Integer> enchants = new LinkedHashMap<>();
        enchants.put("minecraft:sharpness", 5);
        enchants.put("minecraft:unbreaking", 3);
        ItemStack stack = build(new ItemSpec("minecraft:diamond_sword", 1, enchants, 0.0, null), warnings);
        assertNotNull(stack);
        ItemEnchantments applied = stack.getEnchantments();
        assertEquals(2, applied.keySet().size());
        assertEquals(5, applied.getLevel(enchantment("minecraft:sharpness")));
        assertEquals(3, applied.getLevel(enchantment("minecraft:unbreaking")));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    public static void enchantmentApplicationDoesNotDependOnMapOrder() {
        Map<String, Integer> a = new LinkedHashMap<>();
        a.put("minecraft:protection", 4);
        a.put("minecraft:unbreaking", 3);
        a.put("minecraft:mending", 1);
        Map<String, Integer> b = new LinkedHashMap<>();
        b.put("minecraft:mending", 1);
        b.put("minecraft:unbreaking", 3);
        b.put("minecraft:protection", 4);
        ItemStack first = build(new ItemSpec("minecraft:netherite_helmet", 1, a, 0.0, null), new ArrayList<>());
        ItemStack second = build(new ItemSpec("minecraft:netherite_helmet", 1, b, 0.0, null), new ArrayList<>());
        assertTrue(ItemStack.isSameItemSameComponents(first, second));
    }

    public static void levelsAreClampedToVanillasBounds() {
        Map<String, Integer> enchants = new LinkedHashMap<>();
        enchants.put("minecraft:sharpness", 0);
        enchants.put("minecraft:unbreaking", 100_000);
        ItemStack stack = build(new ItemSpec("minecraft:iron_sword", 1, enchants, 0.0, null), new ArrayList<>());
        assertEquals(1, stack.getEnchantments().getLevel(enchantment("minecraft:sharpness")));
        assertEquals(255, stack.getEnchantments().getLevel(enchantment("minecraft:unbreaking")));
    }

    public static void anUnknownEnchantmentIsSkippedWithAWarningAndTheRestStay() {
        List<String> warnings = new ArrayList<>();
        Map<String, Integer> enchants = new LinkedHashMap<>();
        enchants.put("somemod:frostbite", 2);
        enchants.put("minecraft:sharpness", 4);
        enchants.put("Not An Id", 1);
        ItemStack stack = build(new ItemSpec("minecraft:iron_sword", 1, enchants, 0.0, null), warnings);
        assertNotNull(stack, "the item itself survives");
        assertEquals(1, stack.getEnchantments().keySet().size());
        assertEquals(4, stack.getEnchantments().getLevel(enchantment("minecraft:sharpness")));
        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("somemod:frostbite")));
    }

    public static void onlyUnknownEnchantmentsLeaveTheItemUnenchanted() {
        List<String> warnings = new ArrayList<>();
        ItemStack stack = build(new ItemSpec("minecraft:bow", 1, Map.of("nope:nothing", 1), 0.0, null), warnings);
        assertNotNull(stack);
        assertFalse(stack.isEnchanted());
        assertEquals(1, warnings.size());
    }

    public static void enchantedBooksUseTheStoredEnchantmentComponent() {
        ItemStack book = build(new ItemSpec("minecraft:enchanted_book", 1, Map.of("minecraft:mending", 1), 0.0, null),
                new ArrayList<>());
        assertNotNull(book);
        ItemEnchantments stored = book.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
        assertEquals(1, stored.getLevel(enchantment("minecraft:mending")));
        assertFalse(book.isEnchanted(), "a book's own ENCHANTMENTS component stays empty");
    }

    public static void countsAreClampedToTheStackLimit() {
        List<String> warnings = new ArrayList<>();
        ItemStack pearls = build(new ItemSpec("minecraft:ender_pearl", 64, Map.of(), 0.0, null), warnings);
        assertEquals(16, pearls.getCount());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("clamped"), warnings.get(0));

        warnings.clear();
        ItemStack sword = build(new ItemSpec("minecraft:iron_sword", 5, Map.of(), 0.0, null), warnings);
        assertEquals(1, sword.getCount(), "unstackable items are single");

        warnings.clear();
        ItemStack arrows = build(new ItemSpec("minecraft:arrow", 32, Map.of(), 0.0, null), warnings);
        assertEquals(32, arrows.getCount());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    public static void damageFractionOnlyAppliesToDamageableItemsAndNeverBreaksThem() {
        ItemStack chest = build(new ItemSpec("minecraft:diamond_chestplate", 1, Map.of(), 0.5, null), new ArrayList<>());
        assertEquals(Math.round(chest.getMaxDamage() * 0.5), chest.getDamageValue());

        ItemStack worn = build(new ItemSpec("minecraft:diamond_chestplate", 1, Map.of(), 0.95, null), new ArrayList<>());
        assertTrue(worn.getDamageValue() > 0 && worn.getDamageValue() < worn.getMaxDamage(), "damaged but not broken: " + worn.getDamageValue());

        ItemStack pristine = build(new ItemSpec("minecraft:diamond_chestplate", 1, Map.of(), 0.0, null), new ArrayList<>());
        assertEquals(0, pristine.getDamageValue());

        ItemStack apple = build(new ItemSpec("minecraft:golden_apple", 1, Map.of(), 0.9, null), new ArrayList<>());
        assertFalse(apple.isDamageableItem());
        assertEquals(0, apple.getDamageValue());
    }

    public static void potionItemsCarryTheirPotion() {
        List<String> warnings = new ArrayList<>();
        ItemStack splash = build(new ItemSpec("minecraft:splash_potion", 1, Map.of(), 0.0, "minecraft:strong_healing"), warnings);
        assertNotNull(splash);
        PotionContents contents = splash.get(DataComponents.POTION_CONTENTS);
        assertNotNull(contents);
        Optional<Holder<Potion>> potion = contents.potion();
        assertTrue(potion.isPresent());
        assertEquals(Identifier.parse("minecraft:strong_healing"), potion.get().unwrapKey().orElseThrow().identifier());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    public static void anUnknownPotionDropsTheWholeItem() {
        List<String> warnings = new ArrayList<>();
        assertNull(build(new ItemSpec("minecraft:potion", 1, Map.of(), 0.0, "minecraft:mega_brew"), warnings));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("mega_brew"), warnings.get(0));
        assertNull(build(new ItemSpec("minecraft:potion", 1, Map.of(), 0.0, "Bad Id!"), new ArrayList<>()));
    }

    public static void unknownAndInvalidItemsAreSkippedWithAWarningNeverAnException() {
        for (String id : new String[]{"somemod:laser_sword", "minecraft:not_an_item", "Minecraft:Sword", "", "  ", "minecraft:air", null}) {
            List<String> warnings = new ArrayList<>();
            assertNull(build(new ItemSpec(id, 1, Map.of(), 0.0, null), warnings), "for " + id);
            assertEquals(1, warnings.size(), "for " + id + ": " + warnings);
        }
    }

    public static void surroundingWhitespaceInIdsIsTolerated() {
        ItemStack stack = build(ItemSpec.of("  minecraft:bow "), new ArrayList<>());
        assertNotNull(stack);
        assertTrue(stack.is(Items.BOW));
    }

    public static void parseIdRejectsGarbage() {
        assertNull(ItemStackFactory.parseId(null));
        assertNull(ItemStackFactory.parseId("UPPER:case"));
        assertNull(ItemStackFactory.parseId("has space"));
        assertEquals(Identifier.fromNamespaceAndPath("minecraft", "bow"), ItemStackFactory.parseId("bow"));
        assertEquals(Identifier.fromNamespaceAndPath("mod", "a/b"), ItemStackFactory.parseId("mod:a/b"));
    }

    /**
     * Every registry id the profile generator can ever emit must exist in the real 1.21.11 registries; a wrong
     * or renamed id is caught here rather than by a bot spawning naked. Skipped while the vocabulary is still
     * the frozen skeleton (the integrator runs it for real).
     */
    public static void everyIdInTheProfileVocabularyResolvesAndBuilds() {
        List<String> items;
        List<String> enchantments;
        List<String> potions;
        List<String> attributes;
        try {
            items = ProfileVocabulary.items();
            enchantments = ProfileVocabulary.enchantments();
            potions = ProfileVocabulary.potions();
            attributes = ProfileVocabulary.attributes();
        } catch (UnsupportedOperationException skeleton) {
            Assumptions.abort("ProfileVocabulary is still a skeleton in this sandbox");
            return;
        }
        List<String> problems = new ArrayList<>();
        for (String id : items) {
            Identifier parsed = ItemStackFactory.parseId(id);
            Optional<Item> item = parsed == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(parsed);
            if (item.isEmpty() || item.get() == Items.AIR) {
                problems.add("item " + id);
            } else {
                List<String> warnings = new ArrayList<>();
                if (build(ItemSpec.of(id), warnings) == null || !warnings.isEmpty()) {
                    problems.add("item " + id + " did not build cleanly: " + warnings);
                }
            }
        }
        for (String id : enchantments) {
            Identifier parsed = ItemStackFactory.parseId(id);
            if (parsed == null || McBootstrap.registries().get(ResourceKey.create(Registries.ENCHANTMENT, parsed)).isEmpty()) {
                problems.add("enchantment " + id);
            }
        }
        for (String id : potions) {
            Identifier parsed = ItemStackFactory.parseId(id);
            if (parsed == null || BuiltInRegistries.POTION.get(parsed).isEmpty()) {
                problems.add("potion " + id);
            }
        }
        for (String id : attributes) {
            Identifier parsed = ItemStackFactory.parseId(id);
            Optional<Holder.Reference<Attribute>> attribute = parsed == null ? Optional.empty() : BuiltInRegistries.ATTRIBUTE.get(parsed);
            if (attribute.isEmpty()) {
                problems.add("attribute " + id);
            } else {
                try {
                    Identifier.fromNamespaceAndPath(ModifierIds.NAMESPACE, ModifierIds.pathFor(parsed.getNamespace(), parsed.getPath()));
                } catch (RuntimeException e) {
                    problems.add("modifier id for attribute " + id + ": " + e);
                }
            }
        }
        assertTrue(problems.isEmpty(), "ids in ProfileVocabulary that do not resolve in Minecraft 1.21.11: " + problems);
        assertFalse(items.isEmpty(), "the vocabulary lists no items");
    }
}
