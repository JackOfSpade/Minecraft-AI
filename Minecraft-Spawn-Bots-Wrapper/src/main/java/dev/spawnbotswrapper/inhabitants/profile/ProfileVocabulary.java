package dev.spawnbotswrapper.inhabitants.profile;

import java.util.List;

/**
 * The complete closed set of registry ids the generator can ever emit. The Minecraft-side applier is
 * tested against it: every id here must resolve in the real 1.21.11 registries, so a wrong or renamed id
 * is caught by a unit test rather than by a bot spawning naked in production.
 * <p>
 * "Can emit" means reachable with every option and capability switched on (explosive kits, elytra, scale
 * variation, all PvP BOT behaviours enabled); a test proves each listed id is actually produced, and that
 * nothing outside these lists is. The lists are sorted and immutable.
 */
public final class ProfileVocabulary {
    private static final List<String> ITEMS = ItemIds.allItems();
    private static final List<String> ENCHANTMENTS = ItemIds.allEnchantments();
    private static final List<String> POTIONS = ItemIds.allPotions();
    private static final List<String> ATTRIBUTES = ItemIds.allAttributes();

    private ProfileVocabulary() {
    }

    /** Every item id (e.g. {@code minecraft:diamond_sword}) that may appear in a loadout. */
    public static List<String> items() {
        return ITEMS;
    }

    /** Every enchantment id that may appear on a generated item. */
    public static List<String> enchantments() {
        return ENCHANTMENTS;
    }

    /** Every potion id (e.g. {@code minecraft:strong_healing}) used by generated potion items. */
    public static List<String> potions() {
        return POTIONS;
    }

    /** Every attribute id (e.g. {@code minecraft:max_health}) that may appear in vitals. */
    public static List<String> attributes() {
        return ATTRIBUTES;
    }

    /**
     * Vanilla 1.21.11 max stack size of a vocabulary item; the applier uses it to refuse an oversized stack
     * and the tests use it to check the generator never emits one.
     */
    public static int maxStackSize(String item) {
        return ItemIds.maxStack(item);
    }

    /** Vanilla maximum level of a vocabulary enchantment, or 0 for an id outside the vocabulary. */
    public static int maxEnchantmentLevel(String enchantment) {
        return ItemIds.maxEnchantmentLevel(enchantment);
    }
}
