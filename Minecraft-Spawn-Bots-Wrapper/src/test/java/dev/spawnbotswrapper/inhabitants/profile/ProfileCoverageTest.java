package dev.spawnbotswrapper.inhabitants.profile;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import static dev.spawnbotswrapper.inhabitants.profile.ProfileTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The point of coverage sampling, tested end to end: over thousands of bots that share ONE deck store
 * (as the world-wide store is shared), every numeric facet has samples in (nearly) every bucket of its
 * range and reaches both true endpoints, every category is reached, and every boolean is balanced.
 */
class ProfileCoverageTest {

    private static List<ProfileGenerator.Generation> full;
    private static List<BotProfile> fullProfiles;
    private static List<ProfileGenerator.Generation> stock;

    @BeforeAll
    static void generateOnce() {
        full = generate(SAMPLES, allOn(), everythingOptions());
        fullProfiles = profiles(full);
        stock = generate(SAMPLES, GlobalCapabilities.upstreamDefaults(), defaultOptions(), 99L);
    }

    private static List<Double> collect(List<BotProfile> from, Function<BotProfile, Double> value) {
        List<Double> out = new ArrayList<>();
        for (BotProfile p : from) {
            Double v = value.apply(p);
            if (v != null) {
                out.add(v);
            }
        }
        return out;
    }

    private static boolean isFood(String item) {
        return FOOD_IDS.contains(item);
    }

    private static int potionCount(BotProfile p, Predicate<String> potion) {
        int n = 0;
        for (BotProfile.ItemSpec s : specs(p)) {
            if (s.potion() != null && !s.item().equals(NS + "tipped_arrow") && potion.test(s.potion())) {
                n += s.count();
            }
        }
        return n;
    }

    private static double attributeValue(BotProfile p, String attribute) {
        BotProfile.AttributeMod m = p.vitals().attributes().get(attribute);
        return m == null ? 0.0 : m.value();
    }

    private static List<BotProfile.ItemSpec> specsMatching(List<BotProfile> from, Predicate<String> item) {
        List<BotProfile.ItemSpec> out = new ArrayList<>();
        for (BotProfile p : from) {
            for (BotProfile.ItemSpec s : specs(p)) {
                if (item.test(s.item())) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    private static void enchantLevel(String label, List<BotProfile> from, Predicate<String> item, String enchant, int max) {
        List<Double> levels = new ArrayList<>();
        for (BotProfile.ItemSpec s : specsMatching(from, item)) {
            levels.add((double) s.enchantments().getOrDefault(enchant, 0));
        }
        assertCovered(label, levels, 0, max, true, false);
    }

    /** Facets every configuration produces (nothing opt-in), checked on both the stock and the full setup. */
    private static void assertCoreNumericFacets(String tag, List<BotProfile> from) {
        assertCovered(tag + " arrows", collect(from, p -> has(p, i -> i.equals(NS + "bow") || i.equals(NS + "crossbow"))
                ? (double) countOf(p, ProfileTestSupport::isArrowItem) : null), 0, 256, true, true);
        assertCovered(tag + " food count", collect(from, p -> (double) countOf(p, ProfileCoverageTest::isFood)),
                0, 64, true, true);
        assertCovered(tag + " golden apples", collect(from, p -> (double) countOf(p, ItemIds.GOLDEN_APPLE)),
                0, 8, true, false);
        assertCovered(tag + " xp bottles", collect(from, p -> (double) countOf(p, ItemIds.EXPERIENCE_BOTTLE)),
                0, 64, true, true);
        assertCovered(tag + " healing potions", collect(from, p -> (double) potionCount(p,
                pot -> pot.equals(NS + "healing") || pot.equals(NS + "strong_healing"))), 0, 8, true, false);
        assertCovered(tag + " strength potions", collect(from, p -> (double) potionCount(p, pot -> pot.contains("strength"))),
                0, 8, true, false);
        assertCovered(tag + " swiftness potions", collect(from, p -> (double) potionCount(p, pot -> pot.contains("swiftness"))),
                0, 8, true, false);
        assertCovered(tag + " fire resistance potions", collect(from,
                p -> (double) potionCount(p, pot -> pot.contains("fire_resistance"))), 0, 8, true, false);
        assertCovered(tag + " cobwebs", collect(from, p -> (double) countOf(p, ItemIds.COBWEB)), 0, 16, true, false);
        assertEquals(0, from.stream().mapToInt(p -> countOf(p, ItemIds.ENDER_PEARL)).sum(), tag + ": ender pearls are never stocked");
        assertCovered(tag + " totems", collect(from, p -> (double) countOf(p, ItemIds.TOTEM)), 0, 4, true, false);
        assertCovered(tag + " wind charges", collect(from, p -> has(p, i -> i.equals(NS + "mace"))
                ? (double) countOf(p, ItemIds.WIND_CHARGE) : null), 0, 32, true, true);
    }

    @Test
    void loadoutCountsCoverTheirRangeOnTheStockConfiguration() {
        assertCoreNumericFacets("stock", profiles(stock));
    }

    @Test
    void loadoutCountsCoverTheirRangeWithEveryOptInEnabled() {
        assertCoreNumericFacets("full", fullProfiles);
    }

    @Test
    void explosiveKitAndFlightCountsCoverTheirRange() {
        assertCovered("end crystals", collect(fullProfiles, p -> has(p, i -> i.equals(NS + "end_crystal"))
                ? (double) countOf(p, ItemIds.END_CRYSTAL) : null), 1, 16, true, false);
        assertCovered("obsidian", collect(fullProfiles, p -> has(p, i -> i.equals(NS + "end_crystal"))
                ? (double) countOf(p, ItemIds.OBSIDIAN) : null), 4, 64, true, true);
        assertCovered("respawn anchors", collect(fullProfiles, p -> has(p, i -> i.equals(NS + "respawn_anchor"))
                ? (double) countOf(p, ItemIds.RESPAWN_ANCHOR) : null), 1, 8, true, false);
        assertCovered("glowstone", collect(fullProfiles, p -> has(p, i -> i.equals(NS + "respawn_anchor"))
                ? (double) countOf(p, ItemIds.GLOWSTONE) : null), 4, 64, true, true);
        assertCovered("firework rockets", collect(fullProfiles, p -> worn(p, BotProfile.Slot.CHEST) != null
                && worn(p, BotProfile.Slot.CHEST).item().equals(ItemIds.ELYTRA)
                ? (double) countOf(p, ItemIds.FIREWORK_ROCKET) : null), 0, 64, true, true);
    }

    private static void assertVitalsCovered(String tag, List<BotProfile> from, boolean withScale) {
        assertCovered(tag + " healthFraction", collect(from, p -> p.vitals().healthFraction()), 0.35, 1.0, false, true);
        assertCovered(tag + " foodLevel", collect(from, p -> (double) p.vitals().foodLevel()), 6, 20, true, true);
        assertCovered(tag + " max health modifier", collect(from, p -> attributeValue(p, ItemIds.MAX_HEALTH)),
                -10, 20, true, true);
        assertCovered(tag + " interaction range modifier", collect(from, p -> attributeValue(p, ItemIds.ENTITY_INTERACTION_RANGE)),
                -1.0, 3.0, false, true);
        assertCovered(tag + " attack speed modifier", collect(from, p -> attributeValue(p, ItemIds.ATTACK_SPEED)),
                -0.6, 0.0, false, true);
        assertCovered(tag + " knockback resistance", collect(from, p -> attributeValue(p, ItemIds.KNOCKBACK_RESISTANCE)),
                0.0, 1.0, false, true);
        if (withScale) {
            assertCovered(tag + " scale modifier", collect(from, p -> attributeValue(p, ItemIds.SCALE)),
                    -0.4, 0.5, false, true);
        }
    }

    @Test
    void vitalsCoverTheirRangeOnTheStockConfiguration() {
        assertVitalsCovered("stock", profiles(stock), false);
    }

    @Test
    void vitalsCoverTheirRangeWithEveryOptInEnabled() {
        assertVitalsCovered("full", fullProfiles, true);
    }

    @Test
    void attributesStayInTheirDocumentedRanges() {
        for (BotProfile p : fullProfiles) {
            for (var e : p.vitals().attributes().entrySet()) {
                BotProfile.AttributeMod m = e.getValue();
                switch (e.getKey()) {
                    case NS + "max_health" -> {
                        assertEquals(BotProfile.Op.ADD_VALUE, m.operation());
                        double finalHp = 20 + m.value();
                        assertTrue(finalHp >= 10 && finalHp <= 40, "final max health " + finalHp);
                    }
                    case NS + "entity_interaction_range" -> {
                        assertEquals(BotProfile.Op.ADD_VALUE, m.operation());
                        double reach = 3 + m.value();
                        assertTrue(reach >= 2 - 1e-9 && reach <= 6 + 1e-9, "final reach " + reach);
                    }
                    case NS + "attack_speed" -> {
                        assertEquals(BotProfile.Op.ADD_MULTIPLIED_BASE, m.operation());
                        assertTrue(m.value() >= -0.6 && m.value() <= 0.0, "attack speed " + m.value());
                    }
                    case NS + "knockback_resistance" -> {
                        assertEquals(BotProfile.Op.ADD_VALUE, m.operation());
                        assertTrue(m.value() >= 0.0 && m.value() <= 1.0);
                    }
                    case NS + "scale" -> assertTrue(1 + m.value() >= 0.6 - 1e-9 && 1 + m.value() <= 1.5 + 1e-9);
                    default -> fail("unexpected attribute " + e.getKey());
                }
                assertNotEquals(0.0, m.value(), "a zero modifier must be omitted");
            }
        }
    }

    @Test
    void movementSpeedIsNeverVaried() {
        for (BotProfile p : fullProfiles) {
            assertFalse(p.vitals().attributes().keySet().stream().anyMatch(k -> k.contains("movement_speed")));
        }
    }

    @Test
    void patrolShapeCoversItsRanges() {
        List<BotProfile> patrols = new ArrayList<>();
        for (BotProfile p : fullProfiles) {
            String s = p.behavior().stance();
            if (BotProfile.Stance.PATROL_PINGPONG.equals(s) || BotProfile.Stance.PATROL_CYCLE.equals(s)) {
                patrols.add(p);
            }
        }
        assertCovered("patrolRadius", collect(patrols, p -> p.behavior().patrolRadius()), 3.0, 50.0, false, true);
        assertCovered("waypointCount", collect(patrols, p -> (double) p.behavior().waypointCount()), 2, 6, true, false);
    }

    @Test
    void wearCoversItsRange() {
        List<Double> armor = new ArrayList<>();
        List<Double> weapons = new ArrayList<>();
        for (BotProfile p : fullProfiles) {
            for (BotProfile.ItemSpec s : specs(p)) {
                String path = s.item().substring(NS.length());
                if (isArmorPiece(path)) {
                    armor.add(s.damageFraction());
                } else if (path.endsWith("_sword") || path.endsWith("_axe") || path.endsWith("_spear")
                        || path.equals("mace") || path.equals("trident")) {
                    weapons.add(s.damageFraction());
                }
            }
        }
        assertCovered("armor damageFraction", armor, 0.0, 0.9, false, true);
        assertCovered("weapon damageFraction", weapons, 0.0, 0.9, false, true);
    }

    @Test
    void armorEnchantmentLevelsCoverTheirRange() {
        Predicate<String> armor = i -> isArmorPiece(i.substring(NS.length()));
        List<Double> protection = new ArrayList<>();
        for (BotProfile.ItemSpec s : specsMatching(fullProfiles, armor)) {
            protection.add((double) ItemIds.PROTECTION_TYPES.stream()
                    .mapToInt(t -> s.enchantments().getOrDefault(t, 0)).sum());
        }
        assertCovered("protection-type level", protection, 0, 4, true, false);
        enchantLevel("armor unbreaking", fullProfiles, armor, ItemIds.UNBREAKING, 3);
        enchantLevel("armor thorns", fullProfiles, armor, ItemIds.THORNS, 3);
    }

    @Test
    void meleeEnchantmentLevelsCoverTheirRange() {
        Predicate<String> sword = i -> i.endsWith("_sword");
        enchantLevel("sword sharpness", fullProfiles, sword, ItemIds.SHARPNESS, 5);
        enchantLevel("sword fire aspect", fullProfiles, sword, ItemIds.FIRE_ASPECT, 2);
        enchantLevel("sword knockback", fullProfiles, sword, ItemIds.KNOCKBACK, 2);
        enchantLevel("sword sweeping", fullProfiles, sword, ItemIds.SWEEPING_EDGE, 3);
        enchantLevel("sword unbreaking", fullProfiles, sword, ItemIds.UNBREAKING, 3);

        Predicate<String> axe = i -> i.endsWith("_axe");
        enchantLevel("axe sharpness", fullProfiles, axe, ItemIds.SHARPNESS, 5);
        enchantLevel("axe unbreaking", fullProfiles, axe, ItemIds.UNBREAKING, 3);

        Predicate<String> spear = i -> i.endsWith("_spear");
        enchantLevel("spear sharpness", fullProfiles, spear, ItemIds.SHARPNESS, 5);
        enchantLevel("spear fire aspect", fullProfiles, spear, ItemIds.FIRE_ASPECT, 2);
        enchantLevel("spear knockback", fullProfiles, spear, ItemIds.KNOCKBACK, 2);
        enchantLevel("spear lunge", fullProfiles, spear, ItemIds.LUNGE, 3);
        enchantLevel("spear unbreaking", fullProfiles, spear, ItemIds.UNBREAKING, 3);

        Predicate<String> mace = i -> i.equals(NS + "mace");
        enchantLevel("mace density", fullProfiles, mace, ItemIds.DENSITY, 5);
        enchantLevel("mace breach", fullProfiles, mace, ItemIds.BREACH, 4);
        enchantLevel("mace wind burst", fullProfiles, mace, ItemIds.WIND_BURST, 3);
        enchantLevel("mace fire aspect", fullProfiles, mace, ItemIds.FIRE_ASPECT, 2);
        enchantLevel("mace unbreaking", fullProfiles, mace, ItemIds.UNBREAKING, 3);

        Predicate<String> trident = i -> i.equals(NS + "trident");
        enchantLevel("trident impaling", fullProfiles, trident, ItemIds.IMPALING, 5);
        enchantLevel("trident unbreaking", fullProfiles, trident, ItemIds.UNBREAKING, 3);
    }

    @Test
    void rangedEnchantmentLevelsCoverTheirRange() {
        Predicate<String> bow = i -> i.equals(NS + "bow");
        enchantLevel("bow power", fullProfiles, bow, ItemIds.POWER, 5);
        enchantLevel("bow punch", fullProfiles, bow, ItemIds.PUNCH, 2);
        enchantLevel("bow infinity", fullProfiles, bow, ItemIds.INFINITY, 1);
        enchantLevel("bow unbreaking", fullProfiles, bow, ItemIds.UNBREAKING, 3);

        Predicate<String> crossbow = i -> i.equals(NS + "crossbow");
        enchantLevel("crossbow quick charge", fullProfiles, crossbow, ItemIds.QUICK_CHARGE, 3);
        enchantLevel("crossbow piercing", fullProfiles, crossbow, ItemIds.PIERCING, 4);
        enchantLevel("crossbow unbreaking", fullProfiles, crossbow, ItemIds.UNBREAKING, 3);
    }

    @Test
    void everyCategoricalFacetReachesEveryOption() {
        Set<Facts.ArmorMode> armor = EnumSet.noneOf(Facts.ArmorMode.class);
        Set<Facts.MeleeKind> melee = EnumSet.noneOf(Facts.MeleeKind.class);
        Set<Facts.RangedKind> ranged = EnumSet.noneOf(Facts.RangedKind.class);
        Set<Facts.ExplosiveKit> explosive = EnumSet.noneOf(Facts.ExplosiveKit.class);
        Set<String> stances = new HashSet<>();
        Set<String> walks = new HashSet<>();
        for (ProfileGenerator.Generation g : full) {
            armor.add(g.facts().armorMode());
            melee.add(g.facts().meleeKind());
            ranged.add(g.facts().rangedKind());
            explosive.add(g.facts().explosive());
            stances.add(g.profile().behavior().stance());
            if (g.profile().behavior().usesPath()) {
                walks.add(g.profile().behavior().walkType());
            }
        }
        assertEquals(EnumSet.allOf(Facts.ArmorMode.class), armor);
        assertEquals(EnumSet.allOf(Facts.MeleeKind.class), melee);
        assertEquals(EnumSet.allOf(Facts.RangedKind.class), ranged);
        assertEquals(EnumSet.allOf(Facts.ExplosiveKit.class), explosive);
        assertEquals(Set.of(BotProfile.Stance.STAND, BotProfile.Stance.GUARD_POST,
                BotProfile.Stance.PATROL_PINGPONG, BotProfile.Stance.PATROL_CYCLE), stances);
        assertEquals(Set.of(BotProfile.WalkType.BHOP, BotProfile.WalkType.SPRINT, BotProfile.WalkType.WALK), walks);
    }

    @Test
    void categoriesAreBalancedNotClustered() {
        // One card per option per cycle: with 3000 draws each of the 4 armor modes must sit near 25%.
        int[] modes = new int[Facts.ArmorMode.values().length];
        int[] stances = new int[4];
        for (ProfileGenerator.Generation g : full) {
            modes[g.facts().armorMode().ordinal()]++;
            stances[List.of(BotProfile.Stance.STAND, BotProfile.Stance.GUARD_POST, BotProfile.Stance.PATROL_PINGPONG,
                    BotProfile.Stance.PATROL_CYCLE).indexOf(g.profile().behavior().stance())]++;
        }
        for (int n : modes) {
            assertShare("armor mode", n, full.size(), 0.24, 0.26);
        }
        for (int n : stances) {
            assertShare("stance", n, full.size(), 0.24, 0.26);
        }
    }

    @Test
    void itemLevelCategoriesReachEveryOption() {
        Set<String> swordTiers = new HashSet<>();
        Set<String> axeTiers = new HashSet<>();
        Set<String> spearTiers = new HashSet<>();
        Set<String> heads = new HashSet<>();
        Set<String> chests = new HashSet<>();
        Set<String> legs = new HashSet<>();
        Set<String> feet = new HashSet<>();
        Set<String> foods = new HashSet<>();
        Set<String> protectionTypes = new HashSet<>();
        Set<String> healingPotions = new HashSet<>();
        Set<String> healingItems = new HashSet<>();
        Set<String> tipped = new HashSet<>();
        Set<String> arrowKinds = new HashSet<>();
        Set<String> buffPotions = new HashSet<>();
        for (BotProfile p : fullProfiles) {
            for (BotProfile.ItemSpec s : specs(p)) {
                String path = s.item().substring(NS.length());
                if (path.endsWith("_sword")) {
                    swordTiers.add(path);
                } else if (path.endsWith("_axe")) {
                    axeTiers.add(path);
                } else if (path.endsWith("_spear")) {
                    spearTiers.add(path);
                } else if (isFood(s.item())) {
                    foods.add(path);
                } else if (s.item().equals(NS + "tipped_arrow")) {
                    tipped.add(s.potion());
                }
                if (isArrowItem(s.item())) {
                    arrowKinds.add(path);
                }
                if (s.potion() != null && !s.item().equals(NS + "tipped_arrow")) {
                    if (s.potion().contains("healing")) {
                        healingPotions.add(s.potion());
                        healingItems.add(s.item());
                    } else {
                        buffPotions.add(s.potion());
                    }
                }
                s.enchantments().keySet().stream().filter(ItemIds.PROTECTION_TYPES::contains).forEach(protectionTypes::add);
            }
            add(heads, worn(p, BotProfile.Slot.HEAD));
            add(chests, worn(p, BotProfile.Slot.CHEST));
            add(legs, worn(p, BotProfile.Slot.LEGS));
            add(feet, worn(p, BotProfile.Slot.FEET));
        }
        assertEquals(6, swordTiers.size(), swordTiers.toString());
        assertEquals(6, axeTiers.size(), axeTiers.toString());
        assertEquals(6, spearTiers.size(), spearTiers.toString());
        assertEquals(7, heads.size(), "six tiers plus the turtle helmet: " + heads);
        assertTrue(heads.contains("turtle_helmet"));
        assertEquals(7, chests.size(), "six tiers plus the elytra: " + chests);
        assertEquals(6, legs.size());
        assertEquals(6, feet.size());
        assertEquals(11, foods.size(), foods.toString());
        assertEquals(4, protectionTypes.size());
        assertEquals(Set.of(NS + "healing", NS + "strong_healing"), healingPotions);
        assertEquals(Set.of(NS + "splash_potion", NS + "potion"), healingItems);
        assertEquals(Set.copyOf(ItemIds.TIPPED_ARROW_POTIONS), tipped);
        assertEquals(Set.of("arrow", "spectral_arrow", "tipped_arrow"), arrowKinds);
        for (ItemIds.Buff b : ItemIds.Buff.values()) {
            assertTrue(buffPotions.containsAll(b.potions), b + " variants: " + buffPotions);
        }
    }

    private static void add(Set<String> into, BotProfile.ItemSpec spec) {
        if (spec != null) {
            into.add(spec.item().substring(NS.length()));
        }
    }

    @Test
    void booleanFacetsAreBalanced() {
        long shield = fullProfiles.stream().filter(p -> has(p, i -> i.equals(NS + "shield"))).count();
        assertShare("shield", shield, fullProfiles.size(), 0.45, 0.55);

        long bucket = fullProfiles.stream().filter(p -> has(p, i -> i.equals(NS + "water_bucket"))).count();
        assertShare("water bucket", bucket, fullProfiles.size(), 0.45, 0.55);

        long pieces = 0;
        long mending = 0;
        for (BotProfile.ItemSpec s : specsMatching(fullProfiles, i -> isArmorPiece(i.substring(NS.length())))) {
            pieces++;
            if (s.enchantments().containsKey(ItemIds.MENDING)) {
                mending++;
            }
        }
        assertShare("mending", mending, pieces, 0.45, 0.55);

        long bows = 0;
        long infinity = 0;
        for (BotProfile.ItemSpec s : specsMatching(fullProfiles, i -> i.equals(NS + "bow"))) {
            bows++;
            if (s.enchantments().containsKey(ItemIds.INFINITY)) {
                infinity++;
            }
        }
        assertShare("infinity", infinity, bows, 0.45, 0.55);

        // Every inhabitant fights: the pacifist half of the old 50/50 combatant deck is retired.
        long pathFollowers = 0;
        for (BotProfile p : fullProfiles) {
            if (p.behavior().usesPath()) {
                pathFollowers++;
            }
            assertTrue(p.behavior().combatant(), "no inhabitant may be a pacifist");
        }
        assertTrue(pathFollowers > 0, "the sample must contain path followers for the check above to mean something");
    }

    @Test
    void rareOptionsAreRareButGuaranteed() {
        long thorns = specsMatching(fullProfiles, i -> isArmorPiece(i.substring(NS.length()))).stream()
                .filter(s -> s.enchantments().containsKey(ItemIds.THORNS)).count();
        long pieces = specsMatching(fullProfiles, i -> isArmorPiece(i.substring(NS.length()))).size();
        assertShare("thorns", thorns, pieces, 0.11, 0.14);

        long elytra = fullProfiles.stream().filter(p -> worn(p, BotProfile.Slot.CHEST) != null
                && worn(p, BotProfile.Slot.CHEST).item().equals(ItemIds.ELYTRA)).count();
        assertShare("elytra", elytra, fullProfiles.size(), 0.23, 0.27);

        long enchantedApples = fullProfiles.stream().filter(p -> has(p, i -> i.equals(NS + "enchanted_golden_apple"))).count();
        assertShare("enchanted golden apples", enchantedApples, fullProfiles.size(), 0.11, 0.14);
    }

    @Test
    void zeroArrowArchersExistButAreNotTheRule() {
        long archers = 0;
        long dry = 0;
        for (ProfileGenerator.Generation g : full) {
            if (g.facts().hasRanged()) {
                archers++;
                if (g.facts().arrows() == 0) {
                    dry++;
                }
            }
        }
        assertTrue(dry > 0, "a bow without arrows should occur naturally");
        assertTrue(dry < archers * 0.10, "but rarely: " + dry + "/" + archers);
    }
}
