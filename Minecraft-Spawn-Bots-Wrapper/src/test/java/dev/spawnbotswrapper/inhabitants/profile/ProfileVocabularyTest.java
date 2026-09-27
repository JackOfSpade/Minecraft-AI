package dev.spawnbotswrapper.inhabitants.profile;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static dev.spawnbotswrapper.inhabitants.profile.ProfileTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The vocabulary is the contract with the Minecraft-side applier: closed, valid, and exactly what the
 * generator can emit. The expected lists below are transcribed independently from the decompiled 1.21.11
 * registries (Items, Enchantments, Potions, EntityAttributes), so a typo in the generator's own tables
 * cannot pass by agreeing with itself.
 */
class ProfileVocabularyTest {

    private static List<String> expectedItems() {
        Set<String> ids = new TreeSet<>();
        for (String material : List.of("leather", "chainmail", "golden", "iron", "diamond", "netherite")) {
            for (String piece : List.of("helmet", "chestplate", "leggings", "boots")) {
                ids.add(NS + material + "_" + piece);
            }
        }
        for (String material : List.of("wooden", "stone", "golden", "iron", "diamond", "netherite")) {
            for (String tool : List.of("sword", "axe", "spear")) {
                ids.add(NS + material + "_" + tool);
            }
        }
        for (String name : List.of("turtle_helmet", "elytra", "mace", "trident", "bow", "crossbow", "arrow",
                "spectral_arrow", "tipped_arrow", "shield", "totem_of_undying", "golden_apple",
                "enchanted_golden_apple", "golden_carrot", "cooked_beef", "cooked_porkchop", "cooked_mutton",
                "cooked_salmon", "cooked_cod", "cooked_chicken", "bread", "baked_potato", "apple", "carrot",
                "splash_potion", "potion", "experience_bottle", "cobweb", "water_bucket", "ender_pearl",
                "wind_charge", "end_crystal", "obsidian", "respawn_anchor", "glowstone", "firework_rocket")) {
            ids.add(NS + name);
        }
        return new ArrayList<>(ids);
    }

    private static List<String> namespaced(String... names) {
        Set<String> ids = new TreeSet<>();
        for (String n : names) {
            ids.add(NS + n);
        }
        return new ArrayList<>(ids);
    }

    @Test
    void itemsAreExactlyTheIndependentlyTranscribedRegistryIds() {
        assertEquals(expectedItems(), ProfileVocabulary.items());
        assertEquals(78, ProfileVocabulary.items().size());
    }

    @Test
    void enchantmentsAreExactlyTheIndependentlyTranscribedRegistryIds() {
        assertEquals(namespaced("protection", "fire_protection", "blast_protection", "projectile_protection",
                "thorns", "unbreaking", "mending", "sharpness", "fire_aspect", "knockback", "sweeping_edge",
                "lunge", "impaling", "density", "breach", "wind_burst", "power", "punch", "infinity",
                "quick_charge", "piercing"), ProfileVocabulary.enchantments());
    }

    @Test
    void potionsAreExactlyTheIndependentlyTranscribedRegistryIds() {
        assertEquals(namespaced("healing", "strong_healing", "strength", "long_strength", "strong_strength",
                "swiftness", "long_swiftness", "strong_swiftness", "fire_resistance", "long_fire_resistance",
                "poison", "slowness", "weakness", "harming"), ProfileVocabulary.potions());
    }

    @Test
    void attributesAreExactlyTheIndependentlyTranscribedRegistryIds() {
        assertEquals(namespaced("max_health", "entity_interaction_range", "attack_speed", "knockback_resistance",
                "scale"), ProfileVocabulary.attributes());
        assertFalse(ProfileVocabulary.attributes().contains(NS + "movement_speed"),
                "PvP BOT does not read the movement speed attribute");
    }

    @Test
    void listsAreSortedDistinctImmutableAndWellFormed() {
        for (List<String> ids : List.of(ProfileVocabulary.items(), ProfileVocabulary.enchantments(),
                ProfileVocabulary.potions(), ProfileVocabulary.attributes())) {
            assertFalse(ids.isEmpty());
            assertEquals(new TreeSet<>(ids).size(), ids.size(), "duplicates");
            assertEquals(new ArrayList<>(new TreeSet<>(ids)), ids, "not sorted");
            for (String id : ids) {
                assertTrue(id.matches("minecraft:[a-z0-9_]+"), id);
            }
            assertThrows(UnsupportedOperationException.class, () -> ids.add("minecraft:extra"));
            assertThrows(UnsupportedOperationException.class, () -> ids.remove(0));
        }
    }

    @Test
    void noModdedOrInvisibleToPvpBotItemsAreListed() {
        // Copper tools/armor score 0 in PvP BOT's tables (never equipped, never dropped): they would only
        // confuse its auto-equip, so the generator must not hand them out.
        for (String id : ProfileVocabulary.items()) {
            assertFalse(id.contains("copper"), id);
        }
    }

    @Test
    void maxStackSizesMatchTheVanillaOracleForEveryVocabularyItem() {
        for (String id : ProfileVocabulary.items()) {
            assertEquals(vanillaMaxStack(id), ProfileVocabulary.maxStackSize(id), id);
        }
        assertEquals(64, ProfileVocabulary.maxStackSize("modded:thing"), "unknown ids answer the vanilla default");
    }

    @Test
    void maxEnchantmentLevelsMatchTheVanillaOracleForEveryVocabularyEnchantment() {
        for (String id : ProfileVocabulary.enchantments()) {
            assertEquals(VANILLA_MAX_LEVEL.get(id).intValue(), ProfileVocabulary.maxEnchantmentLevel(id), id);
        }
        assertEquals(VANILLA_MAX_LEVEL.keySet(), Set.copyOf(ProfileVocabulary.enchantments()));
        assertEquals(0, ProfileVocabulary.maxEnchantmentLevel("minecraft:sharpness_xx"));
    }

    @Test
    void everyGeneratedIdIsInTheVocabularyAcrossManyConfigurations() {
        Set<String> items = Set.copyOf(ProfileVocabulary.items());
        Set<String> enchants = Set.copyOf(ProfileVocabulary.enchantments());
        Set<String> potions = Set.copyOf(ProfileVocabulary.potions());
        Set<String> attributes = Set.copyOf(ProfileVocabulary.attributes());
        List<GlobalCapabilities> capabilities = new ArrayList<>(List.of(allOn(), GlobalCapabilities.upstreamDefaults(), allOff()));
        for (String flag : capabilityNames()) {
            capabilities.add(allOnExcept(flag));
        }
        for (GlobalCapabilities caps : capabilities) {
            for (BotProfile p : profiles(generate(400, caps, everythingOptions(), caps.hashCode()))) {
                for (BotProfile.ItemSpec s : specs(p)) {
                    assertTrue(items.contains(s.item()), s.item());
                    assertTrue(enchants.containsAll(s.enchantments().keySet()), s.enchantments().toString());
                    if (s.potion() != null) {
                        assertTrue(potions.contains(s.potion()), s.potion());
                    }
                }
                assertTrue(attributes.containsAll(p.vitals().attributes().keySet()), p.vitals().attributes().toString());
            }
        }
    }

    @Test
    void everyListedIdIsActuallyReachable() {
        Set<String> items = new HashSet<>();
        Set<String> enchants = new HashSet<>();
        Set<String> potions = new HashSet<>();
        Set<String> attributes = new HashSet<>();
        for (BotProfile p : profiles(generate(8000, allOn(), everythingOptions(), 424242L))) {
            for (BotProfile.ItemSpec s : specs(p)) {
                items.add(s.item());
                enchants.addAll(s.enchantments().keySet());
                if (s.potion() != null) {
                    potions.add(s.potion());
                }
            }
            attributes.addAll(p.vitals().attributes().keySet());
        }
        assertEquals(Set.copyOf(ProfileVocabulary.items()), items, "unreachable: "
                + difference(ProfileVocabulary.items(), items));
        assertEquals(Set.copyOf(ProfileVocabulary.enchantments()), enchants, "unreachable: "
                + difference(ProfileVocabulary.enchantments(), enchants));
        assertEquals(Set.copyOf(ProfileVocabulary.potions()), potions, "unreachable: "
                + difference(ProfileVocabulary.potions(), potions));
        assertEquals(Set.copyOf(ProfileVocabulary.attributes()), attributes, "unreachable: "
                + difference(ProfileVocabulary.attributes(), attributes));
    }

    private static List<String> difference(List<String> listed, Set<String> seen) {
        List<String> out = new ArrayList<>(listed);
        out.removeAll(seen);
        return out;
    }
}
