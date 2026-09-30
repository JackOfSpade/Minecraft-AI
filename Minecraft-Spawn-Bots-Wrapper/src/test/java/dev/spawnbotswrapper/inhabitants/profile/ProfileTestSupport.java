package dev.spawnbotswrapper.inhabitants.profile;

import dev.spawnbotswrapper.inhabitants.config.InhabitantsConfig;
import dev.spawnbotswrapper.inhabitants.sample.DeckStore;
import dev.spawnbotswrapper.inhabitants.sample.TransientDeckStore;
import dev.spawnbotswrapper.inhabitants.util.SplitMix64;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shared helpers and independent oracles for the profile tests.
 * <p>
 * The oracles (max stack sizes, enchantment level caps, which enchantment fits which item, exclusive
 * sets) are written out here from the decompiled 1.21.11 sources instead of read back from the code under
 * test, so a wrong table in the generator is caught rather than mirrored.
 */
final class ProfileTestSupport {
    private ProfileTestSupport() {
    }

    /** Above the "at least 2000 generations" the brief asks for, so bucket coverage is not a near miss. */
    static final int SAMPLES = 3000;

    static final String NS = "minecraft:";

    static InhabitantsConfig.Profiles defaultOptions() {
        return new InhabitantsConfig.Profiles();
    }

    /**
     * Every opt-in switched on: explosive kits, elytra, scale variation (plus the defaults that are on), and no
     * disabled enchantments, so the vocabulary and coverage tests still see everything the roller can produce.
     */
    static InhabitantsConfig.Profiles everythingOptions() {
        InhabitantsConfig.Profiles o = new InhabitantsConfig.Profiles();
        o.allowExplosiveKits = true;
        o.allowElytra = true;
        o.scaleVariation = true;
        o.disabledEnchantments = new ArrayList<>();
        return o;
    }

    static GlobalCapabilities allOn() {
        return GlobalCapabilities.allEnabled();
    }

    /** All capabilities on except the named ones (record component names, e.g. {@code maceEnabled}). */
    static GlobalCapabilities allOnExcept(String... off) {
        Set<String> disabled = Set.of(off);
        return build(name -> !disabled.contains(name));
    }

    static GlobalCapabilities allOff() {
        return build(name -> false);
    }

    private static GlobalCapabilities build(Predicate<String> valueOf) {
        try {
            RecordComponent[] parts = GlobalCapabilities.class.getRecordComponents();
            Class<?>[] types = new Class<?>[parts.length];
            Object[] values = new Object[parts.length];
            for (int i = 0; i < parts.length; i++) {
                types[i] = parts[i].getType();
                values[i] = valueOf.test(parts[i].getName());
            }
            Constructor<GlobalCapabilities> ctor = GlobalCapabilities.class.getDeclaredConstructor(types);
            return ctor.newInstance(values);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    static List<String> capabilityNames() {
        List<String> names = new ArrayList<>();
        for (RecordComponent c : GlobalCapabilities.class.getRecordComponents()) {
            names.add(c.getName());
        }
        return names;
    }

    /** Distinct, reproducible bot seeds. */
    static long[] seeds(long base, int n) {
        SplitMix64 g = new SplitMix64(base);
        long[] out = new long[n];
        for (int i = 0; i < n; i++) {
            out[i] = g.nextLong();
        }
        return out;
    }

    /**
     * {@code n} generations that share ONE deck store, exactly as the world-wide store is shared by all
     * the bots of a world.
     */
    static List<ProfileGenerator.Generation> generate(int n, GlobalCapabilities caps, InhabitantsConfig.Profiles options) {
        return generate(n, caps, options, 20240607L);
    }

    static List<ProfileGenerator.Generation> generate(int n, GlobalCapabilities caps,
                                                      InhabitantsConfig.Profiles options, long seedBase) {
        ProfileGenerator generator = new ProfileGenerator(() -> options);
        DeckStore decks = new TransientDeckStore();
        List<ProfileGenerator.Generation> out = new ArrayList<>(n);
        for (long seed : seeds(seedBase, n)) {
            out.add(generator.generate(seed, caps, decks));
        }
        return out;
    }

    static List<BotProfile> profiles(List<ProfileGenerator.Generation> gens) {
        List<BotProfile> out = new ArrayList<>(gens.size());
        for (ProfileGenerator.Generation g : gens) {
            out.add(g.profile());
        }
        return out;
    }

    static List<BotProfile.ItemSpec> specs(BotProfile p) {
        List<BotProfile.ItemSpec> out = new ArrayList<>();
        for (BotProfile.PlacedItem placed : p.loadout().items()) {
            out.add(placed.spec());
        }
        return out;
    }

    static int countOf(BotProfile p, Predicate<String> item) {
        int n = 0;
        for (BotProfile.ItemSpec s : specs(p)) {
            if (item.test(s.item())) {
                n += s.count();
            }
        }
        return n;
    }

    static int countOf(BotProfile p, String item) {
        return countOf(p, item::equals);
    }

    static boolean has(BotProfile p, Predicate<String> item) {
        return countOf(p, item) > 0;
    }

    static BotProfile.ItemSpec worn(BotProfile p, String slot) {
        for (BotProfile.PlacedItem placed : p.loadout().items()) {
            if (slot.equals(placed.slot())) {
                return placed.spec();
            }
        }
        return null;
    }

    static BotProfile.ItemSpec hotbar(BotProfile p, int index) {
        for (BotProfile.PlacedItem placed : p.loadout().items()) {
            if (BotProfile.Slot.HOTBAR.equals(placed.slot()) && placed.index() == index) {
                return placed.spec();
            }
        }
        return null;
    }

    static Set<String> allItemIds(Iterable<BotProfile> profiles) {
        Set<String> out = new HashSet<>();
        for (BotProfile p : profiles) {
            for (BotProfile.ItemSpec s : specs(p)) {
                out.add(s.item());
            }
        }
        return out;
    }

    static boolean isArmorPiece(String item) {
        return item.endsWith("_helmet") || item.endsWith("_chestplate") || item.endsWith("_leggings")
                || item.endsWith("_boots");
    }

    /** The foods PvP BOT ranks explicitly (analysis 03, foodOrder), i.e. the generator's main-food menu. */
    static final Set<String> FOOD_IDS = Set.of(NS + "golden_carrot", NS + "cooked_beef", NS + "cooked_porkchop",
            NS + "cooked_mutton", NS + "cooked_salmon", NS + "cooked_cod", NS + "cooked_chicken", NS + "bread",
            NS + "baked_potato", NS + "apple", NS + "carrot");

    static boolean isArrowItem(String item) {
        return item.equals(NS + "arrow") || item.equals(NS + "spectral_arrow") || item.equals(NS + "tipped_arrow");
    }

    /** Vanilla max stack size for every item this generator may emit. */
    static int vanillaMaxStack(String item) {
        String path = item.substring(NS.length());
        if (isArmorPiece(path) || path.equals("elytra") || path.endsWith("_sword") || path.endsWith("_axe")
                || path.endsWith("_spear")) {
            return 1;
        }
        return switch (path) {
            case "mace", "trident", "bow", "crossbow", "shield", "totem_of_undying", "splash_potion",
                 "potion", "water_bucket" -> 1;
            case "ender_pearl" -> 16;
            default -> 64;
        };
    }

    /** Vanilla maximum levels (Enchantments.bootstrap definitions). */
    static final Map<String, Integer> VANILLA_MAX_LEVEL = Map.ofEntries(
            Map.entry(NS + "protection", 4), Map.entry(NS + "fire_protection", 4),
            Map.entry(NS + "blast_protection", 4), Map.entry(NS + "projectile_protection", 4),
            Map.entry(NS + "thorns", 3), Map.entry(NS + "unbreaking", 3), Map.entry(NS + "mending", 1),
            Map.entry(NS + "sharpness", 5), Map.entry(NS + "fire_aspect", 2), Map.entry(NS + "knockback", 2),
            Map.entry(NS + "sweeping_edge", 3), Map.entry(NS + "lunge", 3), Map.entry(NS + "impaling", 5),
            Map.entry(NS + "density", 5), Map.entry(NS + "breach", 4), Map.entry(NS + "wind_burst", 3),
            Map.entry(NS + "power", 5), Map.entry(NS + "punch", 2), Map.entry(NS + "infinity", 1),
            Map.entry(NS + "quick_charge", 3), Map.entry(NS + "piercing", 4));

    /** Enchantments vanilla accepts on an item (enchantable item tags), restricted to those we may emit. */
    static Set<String> vanillaAllowedEnchantments(String item) {
        String path = item.substring(NS.length());
        if (isArmorPiece(path)) {
            return prefixed("protection", "fire_protection", "blast_protection", "projectile_protection",
                    "thorns", "unbreaking", "mending");
        }
        if (path.endsWith("_sword")) {
            return prefixed("sharpness", "fire_aspect", "knockback", "sweeping_edge", "unbreaking");
        }
        if (path.endsWith("_axe")) {
            return prefixed("sharpness", "unbreaking");
        }
        if (path.endsWith("_spear")) {
            return prefixed("sharpness", "fire_aspect", "knockback", "lunge", "unbreaking");
        }
        return switch (path) {
            case "mace" -> prefixed("density", "breach", "wind_burst", "fire_aspect", "unbreaking");
            case "trident" -> prefixed("impaling", "unbreaking");
            case "bow" -> prefixed("power", "punch", "infinity", "unbreaking");
            case "crossbow" -> prefixed("quick_charge", "piercing", "unbreaking");
            default -> Set.of();
        };
    }

    private static Set<String> prefixed(String... names) {
        Set<String> out = new HashSet<>();
        for (String n : names) {
            out.add(NS + n);
        }
        return out;
    }

    static boolean damageable(String item) {
        String path = item.substring(NS.length());
        return isArmorPiece(path) || path.endsWith("_sword") || path.endsWith("_axe") || path.endsWith("_spear")
                || path.equals("mace") || path.equals("trident") || path.equals("elytra");
    }

    /** PvP BOT's weapon ranking (preferSword on), from the analysis of BotEquipment. */
    static int oracleWeaponScore(String item) {
        String path = item.substring(NS.length());
        Map<String, Integer> damage = Map.ofEntries(
                Map.entry("netherite_sword", 8), Map.entry("netherite_axe", 10), Map.entry("diamond_sword", 7),
                Map.entry("diamond_axe", 9), Map.entry("iron_sword", 6), Map.entry("iron_axe", 9),
                Map.entry("stone_sword", 5), Map.entry("stone_axe", 9), Map.entry("golden_sword", 4),
                Map.entry("golden_axe", 7), Map.entry("wooden_sword", 4), Map.entry("wooden_axe", 7),
                Map.entry("trident", 9), Map.entry("mace", 6));
        int d = damage.getOrDefault(path, 0);
        return d == 0 ? 0 : (path.endsWith("_sword") ? d + 5 : d);
    }

    /**
     * Bucket coverage of a numeric facet, mirroring how {@code CoverageSampler} cuts the range: integer
     * ranges of fewer than 24 values get one bucket per value (at most 8), everything else eight equal
     * slices. Asserts at least 95% of buckets hold samples and that both true endpoints were produced;
     * with {@code quarters} it also asserts neither outer quarter is starved (the bell-curve failure mode).
     */
    static void assertCovered(String name, List<Double> values, double lo, double hi, boolean integer,
                              boolean quarters) {
        assertFalse(values.isEmpty(), name + ": no samples at all");
        int buckets;
        boolean exactInts = false;
        if (integer) {
            long span = (long) (hi - lo) + 1L;
            exactInts = span < 24;
            buckets = exactInts ? (int) Math.min(8, span) : 8;
        } else {
            buckets = 8;
        }
        int[] hits = new int[buckets];
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (double v : values) {
            assertTrue(v >= lo - 1e-9 && v <= hi + 1e-9, name + ": " + v + " outside [" + lo + ", " + hi + "]");
            hits[bucketOf(v, lo, hi, buckets, exactInts)]++;
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        int filled = 0;
        for (int h : hits) {
            if (h > 0) {
                filled++;
            }
        }
        assertTrue(filled >= Math.ceil(0.95 * buckets),
                name + ": only " + filled + "/" + buckets + " buckets filled " + java.util.Arrays.toString(hits));
        assertEquals(lo, min, 1e-9, name + ": true minimum never produced");
        assertEquals(hi, max, 1e-9, name + ": true maximum never produced");
        if (quarters) {
            double q1 = lo + 0.25 * (hi - lo);
            double q3 = lo + 0.75 * (hi - lo);
            long low = values.stream().filter(v -> v < q1).count();
            long high = values.stream().filter(v -> v > q3).count();
            assertTrue(low >= 0.20 * values.size(),
                    name + ": low quarter under-represented " + low + "/" + values.size());
            assertTrue(high >= 0.20 * values.size(),
                    name + ": high quarter under-represented " + high + "/" + values.size());
        }
    }

    private static int bucketOf(double v, double lo, double hi, int buckets, boolean exactInts) {
        if (exactInts) {
            long span = (long) (hi - lo) + 1L;
            long i = Math.round(v - lo);
            for (int b = 0; b < buckets; b++) {
                long from = (span * b) / buckets;
                long to = (span * (b + 1)) / buckets - 1;
                if (i >= from && i <= to) {
                    return b;
                }
            }
            throw new AssertionError("value outside integer buckets: " + v);
        }
        double width = (hi - lo) / buckets;
        return Math.min(buckets - 1, (int) ((v - lo) / width));
    }

    static void assertShare(String name, long part, long whole, double lo, double hi) {
        assertTrue(whole > 0, name + ": empty population");
        double share = (double) part / whole;
        assertTrue(share >= lo && share <= hi,
                name + ": share " + share + " (" + part + "/" + whole + ") outside [" + lo + ", " + hi + "]");
    }

    /**
     * A compact, order-independent text form of a profile. Built by hand (not with toString of maps) so it
     * is identical in every JVM run: immutable maps iterate in a per-run randomised order.
     */
    static String fingerprint(BotProfile p) {
        StringBuilder b = new StringBuilder(p.archetype()).append(" |");
        for (BotProfile.PlacedItem placed : p.loadout().items()) {
            BotProfile.ItemSpec s = placed.spec();
            b.append(' ').append(placed.slot()).append(':').append(placed.index()).append('=')
                    .append(s.item().substring(NS.length()));
            if (s.count() != 1) {
                b.append('x').append(s.count());
            }
            if (s.potion() != null) {
                b.append('<').append(s.potion().substring(NS.length())).append('>');
            }
            new TreeMap<>(s.enchantments()).forEach((id, level) ->
                    b.append('+').append(id.substring(NS.length())).append(level));
            if (s.damageFraction() > 0) {
                b.append('~').append(s.damageFraction());
            }
        }
        b.append(" | hp ").append(p.vitals().healthFraction()).append(" food ").append(p.vitals().foodLevel());
        new TreeMap<>(p.vitals().attributes()).forEach((id, mod) ->
                b.append(' ').append(id.substring(NS.length())).append(mod.operation().charAt(4)).append(mod.value()));
        BotProfile.Behavior bh = p.behavior();
        b.append(" | ").append(bh.stance()).append(' ').append(bh.combatant()).append(' ').append(bh.walkType())
                .append(' ').append(bh.patrolRadius()).append(' ').append(bh.waypointCount());
        return b.toString();
    }
}
