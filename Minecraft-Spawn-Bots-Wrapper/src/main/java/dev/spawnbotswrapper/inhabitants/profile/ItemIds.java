package dev.spawnbotswrapper.inhabitants.profile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Every registry id the profile generator can emit, in one place, together with the vanilla 1.21.11
 * facts a profile must respect (stack sizes, enchantment level caps, which slot an item is worn in).
 * <p>
 * The generator, {@link ProfileVocabulary} and {@link ProfileFormatter} all read from here, so the
 * closed vocabulary cannot drift from what the generator actually rolls. Ids are plain strings on
 * purpose: this package must stay free of Minecraft classes so it can be unit-tested without the game.
 * Each id was checked against the 1.21.11 registries; the Minecraft-side applier re-checks the whole
 * vocabulary against the live registries.
 */
final class ItemIds {
    private ItemIds() {
    }

    static final String NS = "minecraft:";

    /** The four worn armor slots, with the item-name suffix of the piece that goes there. */
    enum ArmorSlot {
        HEAD(BotProfile.Slot.HEAD, "helmet"),
        CHEST(BotProfile.Slot.CHEST, "chestplate"),
        LEGS(BotProfile.Slot.LEGS, "leggings"),
        FEET(BotProfile.Slot.FEET, "boots");

        final String slot;
        final String piece;

        ArmorSlot(String slot, String piece) {
            this.slot = slot;
            this.piece = piece;
        }
    }

    /** Armor materials PvP BOT's auto-equip ranks (netherite > diamond > iron > chainmail > golden > leather). */
    enum ArmorTier {
        LEATHER("leather"), CHAINMAIL("chainmail"), GOLDEN("golden"), IRON("iron"), DIAMOND("diamond"), NETHERITE("netherite");

        final String material;

        ArmorTier(String material) {
            this.material = material;
        }
    }

    /** Tool materials of the swords, axes and spears PvP BOT's weapon tables know. */
    enum ToolTier {
        WOODEN("wooden"), STONE("stone"), GOLDEN("golden"), IRON("iron"), DIAMOND("diamond"), NETHERITE("netherite");

        final String material;

        ToolTier(String material) {
            this.material = material;
        }
    }

    /** The three buff potions PvP BOT throws at its own feet, each with the vanilla potion ids that match. */
    enum Buff {
        STRENGTH("strength", List.of(NS + "strength", NS + "long_strength", NS + "strong_strength")),
        SWIFTNESS("swiftness", List.of(NS + "swiftness", NS + "long_swiftness", NS + "strong_swiftness")),
        FIRE_RESISTANCE("fire_resistance", List.of(NS + "fire_resistance", NS + "long_fire_resistance"));

        final String key;
        final List<String> potions;

        Buff(String key, List<String> potions) {
            this.key = key;
            this.potions = potions;
        }
    }

    static String armor(ArmorTier tier, ArmorSlot slot) {
        return NS + tier.material + "_" + slot.piece;
    }

    static String sword(ToolTier tier) {
        return NS + tier.material + "_sword";
    }

    static String axe(ToolTier tier) {
        return NS + tier.material + "_axe";
    }

    static String spear(ToolTier tier) {
        return NS + tier.material + "_spear";
    }

    static final String TURTLE_HELMET = NS + "turtle_helmet";
    static final String ELYTRA = NS + "elytra";
    static final String MACE = NS + "mace";
    static final String TRIDENT = NS + "trident";
    static final String BOW = NS + "bow";
    static final String CROSSBOW = NS + "crossbow";
    static final String ARROW = NS + "arrow";
    static final String SPECTRAL_ARROW = NS + "spectral_arrow";
    static final String TIPPED_ARROW = NS + "tipped_arrow";
    static final String SHIELD = NS + "shield";
    static final String TOTEM = NS + "totem_of_undying";
    static final String GOLDEN_APPLE = NS + "golden_apple";
    static final String ENCHANTED_GOLDEN_APPLE = NS + "enchanted_golden_apple";
    static final String SPLASH_POTION = NS + "splash_potion";
    static final String POTION = NS + "potion";
    static final String EXPERIENCE_BOTTLE = NS + "experience_bottle";
    static final String COBWEB = NS + "cobweb";
    static final String WATER_BUCKET = NS + "water_bucket";
    /** Never stocked (see LoadoutRoller); kept only so stored profiles from before that still get the right stack size and category. */
    static final String ENDER_PEARL = NS + "ender_pearl";
    static final String WIND_CHARGE = NS + "wind_charge";
    static final String END_CRYSTAL = NS + "end_crystal";
    static final String OBSIDIAN = NS + "obsidian";
    static final String RESPAWN_ANCHOR = NS + "respawn_anchor";
    static final String GLOWSTONE = NS + "glowstone";
    static final String FIREWORK_ROCKET = NS + "firework_rocket";

    /**
     * Foods PvP BOT ranks explicitly (best first). Anything with the FOOD component would be eaten, but
     * these are the ones whose priority is known, so an inhabitant's menu is predictable.
     */
    static final List<String> FOODS = List.of(
            NS + "golden_carrot", NS + "cooked_beef", NS + "cooked_porkchop", NS + "cooked_mutton",
            NS + "cooked_salmon", NS + "cooked_cod", NS + "cooked_chicken", NS + "bread",
            NS + "baked_potato", NS + "apple", NS + "carrot");

    static final List<String> HEALING_POTIONS = List.of(NS + "healing", NS + "strong_healing");

    /** Potions on tipped arrows: harmful effects that make sense to shoot at a player. */
    static final List<String> TIPPED_ARROW_POTIONS = List.of(
            NS + "poison", NS + "slowness", NS + "weakness", NS + "harming");

    static final String PROTECTION = NS + "protection";
    static final String FIRE_PROTECTION = NS + "fire_protection";
    static final String BLAST_PROTECTION = NS + "blast_protection";
    static final String PROJECTILE_PROTECTION = NS + "projectile_protection";
    static final String THORNS = NS + "thorns";
    static final String UNBREAKING = NS + "unbreaking";
    static final String MENDING = NS + "mending";
    static final String SHARPNESS = NS + "sharpness";
    static final String FIRE_ASPECT = NS + "fire_aspect";
    static final String KNOCKBACK = NS + "knockback";
    static final String SWEEPING_EDGE = NS + "sweeping_edge";
    static final String LUNGE = NS + "lunge";
    static final String IMPALING = NS + "impaling";
    static final String DENSITY = NS + "density";
    static final String BREACH = NS + "breach";
    static final String WIND_BURST = NS + "wind_burst";
    static final String POWER = NS + "power";
    static final String PUNCH = NS + "punch";
    static final String INFINITY = NS + "infinity";
    static final String QUICK_CHARGE = NS + "quick_charge";
    static final String PIERCING = NS + "piercing";

    /** Vanilla exclusive set: at most one of these per armor piece. */
    static final List<String> PROTECTION_TYPES = List.of(
            PROTECTION, FIRE_PROTECTION, BLAST_PROTECTION, PROJECTILE_PROTECTION);

    static final String MAX_HEALTH = NS + "max_health";
    static final String ENTITY_INTERACTION_RANGE = NS + "entity_interaction_range";
    static final String ATTACK_SPEED = NS + "attack_speed";
    static final String KNOCKBACK_RESISTANCE = NS + "knockback_resistance";
    static final String SCALE = NS + "scale";

    /** Vanilla base values of the attributes we modify; the formatter needs them to show resulting values. */
    static final Map<String, Double> ATTRIBUTE_BASE = Map.of(
            MAX_HEALTH, 20.0,
            ENTITY_INTERACTION_RANGE, 3.0,
            ATTACK_SPEED, 4.0,
            KNOCKBACK_RESISTANCE, 0.0,
            SCALE, 1.0);

    private static final Map<String, Integer> ENCHANTMENT_MAX = Map.ofEntries(
            Map.entry(PROTECTION, 4), Map.entry(FIRE_PROTECTION, 4), Map.entry(BLAST_PROTECTION, 4),
            Map.entry(PROJECTILE_PROTECTION, 4), Map.entry(THORNS, 3), Map.entry(UNBREAKING, 3),
            Map.entry(MENDING, 1), Map.entry(SHARPNESS, 5), Map.entry(FIRE_ASPECT, 2),
            Map.entry(KNOCKBACK, 2), Map.entry(SWEEPING_EDGE, 3), Map.entry(LUNGE, 3),
            Map.entry(IMPALING, 5), Map.entry(DENSITY, 5), Map.entry(BREACH, 4),
            Map.entry(WIND_BURST, 3), Map.entry(POWER, 5), Map.entry(PUNCH, 2),
            Map.entry(INFINITY, 1), Map.entry(QUICK_CHARGE, 3), Map.entry(PIERCING, 4));

    private static final List<String> ARMOR_ITEMS = armorItems();
    private static final List<String> MELEE_ITEMS = meleeItems();

    private static List<String> armorItems() {
        List<String> out = new ArrayList<>();
        for (ArmorTier t : ArmorTier.values()) {
            for (ArmorSlot s : ArmorSlot.values()) {
                out.add(armor(t, s));
            }
        }
        out.add(TURTLE_HELMET);
        out.add(ELYTRA);
        return Collections.unmodifiableList(out);
    }

    private static List<String> meleeItems() {
        List<String> out = new ArrayList<>();
        for (ToolTier t : ToolTier.values()) {
            out.add(sword(t));
            out.add(axe(t));
            out.add(spear(t));
        }
        out.add(MACE);
        out.add(TRIDENT);
        return Collections.unmodifiableList(out);
    }

    /** Sorted so vocabulary output and test failures are stable. */
    static List<String> allItems() {
        Set<String> all = new TreeSet<>(ARMOR_ITEMS);
        all.addAll(MELEE_ITEMS);
        all.addAll(List.of(BOW, CROSSBOW, ARROW, SPECTRAL_ARROW, TIPPED_ARROW, SHIELD, TOTEM, GOLDEN_APPLE,
                ENCHANTED_GOLDEN_APPLE, SPLASH_POTION, POTION, EXPERIENCE_BOTTLE, COBWEB, WATER_BUCKET,
                WIND_CHARGE, END_CRYSTAL, OBSIDIAN, RESPAWN_ANCHOR, GLOWSTONE, FIREWORK_ROCKET));
        all.addAll(FOODS);
        return List.copyOf(all);
    }

    static List<String> allEnchantments() {
        return List.copyOf(new TreeSet<>(ENCHANTMENT_MAX.keySet()));
    }

    static List<String> allPotions() {
        Set<String> all = new TreeSet<>(HEALING_POTIONS);
        for (Buff b : Buff.values()) {
            all.addAll(b.potions);
        }
        all.addAll(TIPPED_ARROW_POTIONS);
        return List.copyOf(all);
    }

    static List<String> allAttributes() {
        return List.copyOf(new TreeSet<>(ATTRIBUTE_BASE.keySet()));
    }

    /** 0 when the id is not an enchantment this generator knows. */
    static int maxEnchantmentLevel(String id) {
        return id == null ? 0 : ENCHANTMENT_MAX.getOrDefault(id, 0);
    }

    /**
     * Vanilla max stack size: tools, weapons, armor, potions, totems and buckets do not stack; ender
     * pearls stack to 16; everything else we emit stacks to 64. Unknown ids answer 64 (the vanilla default).
     */
    static int maxStack(String id) {
        if (id == null) {
            return 64;
        }
        if (ARMOR_ITEMS.contains(id) || MELEE_ITEMS.contains(id)) {
            return 1;
        }
        return switch (id) {
            case BOW, CROSSBOW, SHIELD, TOTEM, SPLASH_POTION, POTION, WATER_BUCKET -> 1;
            case ENDER_PEARL -> 16;
            default -> 64;
        };
    }

    static boolean isArmor(String id) {
        return id != null && ARMOR_ITEMS.contains(id);
    }

    static boolean isMelee(String id) {
        return id != null && MELEE_ITEMS.contains(id);
    }

    static boolean isFood(String id) {
        return id != null && (FOODS.contains(id) || GOLDEN_APPLE.equals(id) || ENCHANTED_GOLDEN_APPLE.equals(id));
    }

    static boolean isArrow(String id) {
        return ARROW.equals(id) || SPECTRAL_ARROW.equals(id) || TIPPED_ARROW.equals(id);
    }

    static boolean isPotionItem(String id) {
        return SPLASH_POTION.equals(id) || POTION.equals(id);
    }

    static boolean isSpear(String id) {
        return id != null && id.endsWith("_spear");
    }

    static boolean isAxe(String id) {
        return id != null && id.endsWith("_axe");
    }

    /** Coarse grouping the formatter uses to lay a loadout out by purpose. */
    enum Category { ARMOR, MELEE, RANGED, AMMO, DEFENCE, FOOD, POTION, UTILITY, EXPLOSIVE, FLIGHT, OTHER }

    static Category category(String id) {
        if (id == null) {
            return Category.OTHER;
        }
        if (isArmor(id)) {
            return Category.ARMOR;
        }
        if (isMelee(id)) {
            return Category.MELEE;
        }
        if (isArrow(id)) {
            return Category.AMMO;
        }
        if (isFood(id)) {
            return Category.FOOD;
        }
        if (isPotionItem(id)) {
            return Category.POTION;
        }
        return switch (id) {
            case BOW, CROSSBOW -> Category.RANGED;
            case SHIELD, TOTEM -> Category.DEFENCE;
            case EXPERIENCE_BOTTLE, COBWEB, WATER_BUCKET, ENDER_PEARL, WIND_CHARGE -> Category.UTILITY;
            case END_CRYSTAL, OBSIDIAN, RESPAWN_ANCHOR, GLOWSTONE -> Category.EXPLOSIVE;
            case FIREWORK_ROCKET -> Category.FLIGHT;
            default -> Category.OTHER;
        };
    }
}
