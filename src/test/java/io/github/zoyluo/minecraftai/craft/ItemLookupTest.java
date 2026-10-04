package io.github.zoyluo.minecraftai.craft;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure parsing/formatting of the lookup_recipe answer (the registry-backed part runs in a GameTest). */
final class ItemLookupTest {
    @Test
    void normalizeAcceptsPlainNamesAndFullIds() {
        assertEquals("minecraft:copper_pickaxe", ItemLookup.normalize("Copper Pickaxe"));
        assertEquals("minecraft:copper_pickaxe", ItemLookup.normalize("Copper\tPickaxe"));
        assertEquals("minecraft:copper_pickaxe", ItemLookup.normalize("Copper\nPickaxe"));
        assertEquals("minecraft:lunge", ItemLookup.normalize("  lunge "));
        assertEquals("minecraft:copper_pickaxe", ItemLookup.normalize("minecraft:copper_pickaxe"));
        assertEquals("othermod:thing", ItemLookup.normalize("othermod:thing"));
        assertEquals("", ItemLookup.normalize("   "));
        assertEquals("", ItemLookup.normalize(null));
    }

    @Test
    void craftableAnswerIsOneShortLineWithTableRequirement() {
        String answer = ItemLookup.formatCraftable("minecraft:copper_pickaxe", 1,
                List.of("3x minecraft:copper_ingot", "2x minecraft:stick"), true);

        assertEquals("minecraft:copper_pickaxe exists in Minecraft 1.21.11. Craft 1 from "
                + "3x minecraft:copper_ingot, 2x minecraft:stick at a crafting table.", answer);
        assertTrue(ItemLookup.formatCraftable("minecraft:stick", 4, List.of("2x minecraft:oak_planks"), false)
                .endsWith("(2x2 inventory grid)."));
        assertTrue(answer.length() < 200, "the tool output must stay short");
    }

    @Test
    void ingredientAlternativesAreCapped() {
        assertEquals("1x a|b", ItemLookup.formatIngredient(1, List.of("a", "b")));
        assertEquals("2x a|b|c|+2 more", ItemLookup.formatIngredient(2, List.of("a", "b", "c", "d", "e")));
    }

    @Test
    void smeltedAndRecipelessItemsSayHowElseTheyAreObtained() {
        assertEquals("minecraft:copper_ingot exists in Minecraft 1.21.11. Not crafted: smelt "
                + "minecraft:raw_copper in a furnace.",
                ItemLookup.formatSmelted("minecraft:copper_ingot", "minecraft:raw_copper"));
        assertTrue(ItemLookup.formatNoRecipe("minecraft:diamond", "unknown").contains("mining, loot"));
        assertTrue(ItemLookup.formatNoRecipe("minecraft:coal", "mine").contains("obtained by: mine"));
    }

    @Test
    void enchantmentsAreReportedAsExistingAndNotAsItems() {
        String answer = ItemLookup.formatEnchantment("minecraft:lunge", 3);

        assertTrue(answer.contains("enchantment that exists in Minecraft 1.21.11"));
        assertTrue(answer.contains("max level 3"));
    }

    @Test
    void unknownNamesListSimilarIdsOrExplainTheyFoundNone() {
        assertEquals("minecraft:zzz is neither an item nor an enchantment in Minecraft 1.21.11 under that id. "
                        + "No similar ids found; check the spelling (ids use underscores).",
                ItemLookup.formatNotFound("minecraft:zzz", List.of()));
        String withHints = ItemLookup.formatNotFound("minecraft:copper_tool",
                List.of("minecraft:copper_axe", "minecraft:copper_pickaxe"));
        assertTrue(withHints.contains("Similar ids: minecraft:copper_axe, minecraft:copper_pickaxe."));
    }

    @Test
    void suggestionsPreferSubstringMatchesThenWordMatchesAndAreCapped() {
        List<String> ids = List.of("minecraft:copper_axe", "minecraft:copper_pickaxe", "minecraft:iron_pickaxe",
                "minecraft:stick", "minecraft:copper_block");

        assertEquals(List.of("minecraft:copper_axe", "minecraft:copper_block", "minecraft:copper_pickaxe"),
                ItemLookup.suggestions(ids, "minecraft:copper"));
        // No id contains "copper_tool"; fall back to its words (copper, tool) of 4+ letters.
        assertEquals(List.of("minecraft:copper_axe", "minecraft:copper_block", "minecraft:copper_pickaxe"),
                ItemLookup.suggestions(ids, "minecraft:copper_tool"));
        assertTrue(ItemLookup.suggestions(ids, "minecraft:zzz").isEmpty());
        assertEquals(6, ItemLookup.suggestions(
                List.of("a:x1", "a:x2", "a:x3", "a:x4", "a:x5", "a:x6", "a:x7", "a:x8"), "a:x").size());
    }
}
