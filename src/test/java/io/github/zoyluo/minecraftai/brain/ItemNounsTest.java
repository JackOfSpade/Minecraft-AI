package io.github.zoyluo.minecraftai.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Raw versus crafted nouns, resolved through the real item registry. */
final class ItemNounsTest {
    @BeforeAll
    static void bootstrap() {
        RegistryBootstrap.ensure();
    }

    private static ItemNouns.Kind kind(String phrase) {
        return ItemNouns.classify(List.of(phrase.split(" ")));
    }

    @Test
    void resourcesTheWorldYieldsAreRaw() {
        for (String noun : List.of("logs", "oak logs", "oak log", "wood", "oak wood", "cobblestone", "cobble",
                "stone", "dirt", "sand", "gravel", "coal", "diamonds", "emeralds", "redstone", "lapis", "quartz",
                "iron", "raw iron", "gold", "iron ore", "diamond ore", "ores", "obsidian", "wheat", "carrots",
                "potatoes", "sweet berries", "seeds", "saplings", "leaves", "wool", "trees", "blaze rods",
                "bones", "string", "meat", "flowers", "ancient debris", "debris", "minecraft:iron_ore")) {
            assertEquals(ItemNouns.Kind.RAW, kind(noun), noun);
        }
    }

    @Test
    void thingsTheBotMakesAreCrafted() {
        for (String noun : List.of("iron pickaxe", "iron pickaxes", "pickaxe", "diamond sword", "iron helmet",
                "boots", "iron boots", "shield", "bow", "fishing rod", "iron ingots", "gold ingot", "iron nuggets",
                "iron blocks", "diamond block", "coal blocks", "planks", "oak planks", "sticks", "torches",
                "crafting table", "crafting tables", "table", "furnace", "chest", "ladders", "bread", "bucket",
                "beds", "boats", "oak boat", "doors", "stone bricks", "bricks", "glass", "charcoal",
                "armor", "iron armor", "stone tools", "cooked beef", "minecraft:iron_ingot")) {
            assertEquals(ItemNouns.Kind.CRAFTED, kind(noun), noun);
        }
    }

    @Test
    void modifiersInFrontOfTheNounAreSkipped() {
        assertEquals(ItemNouns.Kind.CRAFTED, kind("fresh shiny iron pickaxe"));
        assertEquals(ItemNouns.Kind.RAW, kind("big pile of logs"));
    }

    @Test
    void wordsThatNameNoItemAreUnknown() {
        for (String noun : List.of("house", "ready", "status update", "weather", "farm", "thoughts", "")) {
            assertEquals(ItemNouns.Kind.UNKNOWN, kind(noun), noun);
        }
    }

    @Test
    void anAmbiguousFamilyIsUnknownRatherThanGuessed() {
        // Some "...block" items are storage blocks and some natural, so the bare word decides nothing.
        assertEquals(ItemNouns.Kind.UNKNOWN, kind("block"));
    }

    @Test
    void stoneIsRawEvenThoughItIsSmeltedFromCobblestone() {
        assertFalse(ItemNouns.isCraftedOutput(Items.STONE));
        assertFalse(ItemNouns.isCraftedOutput(Items.COBBLESTONE));
        assertTrue(ItemNouns.isCraftedOutput(Items.STONE_BRICKS));
    }

    @Test
    void conversionRecipesDoNotMakeANaturalItemCrafted() {
        // Wood has a recipe from logs and raw iron one from its block; the world still yields both.
        assertFalse(ItemNouns.isCraftedOutput(Items.OAK_WOOD));
        assertFalse(ItemNouns.isCraftedOutput(Items.RAW_IRON));
        assertFalse(ItemNouns.isCraftedOutput(Items.DIAMOND_ORE));
        assertFalse(ItemNouns.isCraftedOutput(Items.GRASS_BLOCK));
        assertTrue(ItemNouns.isCraftedOutput(Items.IRON_BLOCK));
        assertTrue(ItemNouns.isCraftedOutput(Items.RAW_IRON_BLOCK));
    }

    @Test
    void naturalBlocksStayRawWhereTheGamesRecipeIndexListsTheirCompactionRecipe() {
        // The running server's recipe index holds these recipes (4 sand make sandstone ...); the bare test
        // JVM's does not, so the production classification has to be reproduced to be tested.
        RuntimeRecipeFixture.install(Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.SANDSTONE, Items.RED_SANDSTONE,
                Items.CLAY, Items.GLOWSTONE, Items.SNOW_BLOCK, Items.PACKED_ICE, Items.PISTON);
        try {
            for (String noun : List.of("andesite", "diorite", "granite", "sandstone", "red sandstone", "clay",
                    "glowstone", "snow blocks", "packed ice")) {
                assertEquals(ItemNouns.Kind.RAW, kind(noun), noun);
            }
            assertEquals(ItemNouns.Kind.CRAFTED, kind("pistons"), "an ordinary recipe still makes an item crafted");
        } finally {
            RuntimeRecipeFixture.clear();
        }
    }

    @Test
    void whatTheWorldYieldsIsRawWithTheGamesWholeRecipeIndexInstalledToo() {
        // The recipe index of a running server holds every vanilla crafting recipe. Each of these is a resource
        // the world (or a creature) yields that a recipe also makes, and none may read as crafted there.
        RuntimeRecipeFixture.installVanilla();
        try {
            for (String noun : List.of("white wool", "red wool", "wool", "melon", "melons", "melon seeds",
                    "pumpkin seeds", "sea lantern", "sea lanterns", "prismarine", "dark prismarine", "coarse dirt",
                    "leather", "magma cream", "snow", "snow blocks", "mossy cobblestone", "sandstone", "red sandstone",
                    "clay", "glowstone", "andesite", "diorite", "granite", "packed ice", "blue ice", "bone block",
                    "moss carpet", "muddy mangrove roots", "brown mushroom block", "red mushroom block",
                    "amethyst block", "nether wart block", "warped wart block", "dripstone block", "magma block",
                    "oak logs", "oak wood", "stripped oak log", "raw iron", "raw gold", "coal", "diamond", "emerald",
                    "redstone", "lapis", "quartz", "copper ore", "iron ore", "cobblestone", "stone", "obsidian")) {
                assertEquals(ItemNouns.Kind.RAW, kind(noun), noun + " reads as crafted in the running game");
            }
            // Whereas the made things stay made: the recipe index only adds to what the structure already says.
            for (String noun : List.of("piston", "dispenser", "lantern", "anvil", "hopper", "furnace", "bookshelf",
                    "white banner", "oak shelf", "shulker box", "iron bars", "bundle", "tnt", "rail", "paper", "book",
                    "iron pickaxe", "stone bricks", "iron ingot", "iron block", "hay block", "slime block")) {
                assertEquals(ItemNouns.Kind.CRAFTED, kind(noun), noun + " reads as raw in the running game");
            }
        } finally {
            RuntimeRecipeFixture.clear();
        }
    }

    @Test
    void aCollectingVerbIgnoresRecipesThatOnlyTheRunningGameKnows() {
        RuntimeRecipeFixture.install(Items.PISTON, Items.SEA_LANTERN);
        try {
            assertEquals(ItemNouns.Kind.CRAFTED, ItemNouns.classify(List.of("pistons"), true));
            assertEquals(ItemNouns.Kind.RAW, ItemNouns.classify(List.of("pistons"), false),
                    "gather/mine name what the world yields, whatever the recipe index says");
            // What is crafted whatever the recipes say stays crafted for every verb.
            assertEquals(ItemNouns.Kind.CRAFTED, ItemNouns.classify(List.of("iron", "pickaxe"), false));
            assertEquals(ItemNouns.Kind.CRAFTED, ItemNouns.classify(List.of("iron", "ingots"), false));
            assertEquals(ItemNouns.Kind.CRAFTED, ItemNouns.classify(List.of("torches"), false),
                    "the planner's own recipes are structure, not index");
        } finally {
            RuntimeRecipeFixture.clear();
        }
    }

    @Test
    void anItemIdIsRawWhenTheWorldYieldsIt() {
        assertTrue(ItemNouns.isRawItemId("minecraft:oak_log"));
        assertTrue(ItemNouns.isRawItemId("COAL"));
        assertTrue(ItemNouns.isRawItemId(" minecraft:raw_iron "));
        assertFalse(ItemNouns.isRawItemId("minecraft:crafting_table"));
        assertFalse(ItemNouns.isRawItemId("minecraft:iron_pickaxe"));
        assertFalse(ItemNouns.isRawItemId("minecraft:not_an_item"));
        assertFalse(ItemNouns.isRawItemId(null));
    }

    @Test
    void creaturesAreToldApartFromTheirDrops() {
        for (String noun : List.of("cow", "cows", "sheep", "zombie", "zombies", "skeletons", "spiders", "pigs",
                "creeper", "mobs", "monsters", "animals", "enderman")) {
            assertTrue(ItemNouns.isCreature(List.of(noun)), noun);
        }
        assertTrue(ItemNouns.isCreature(List.of("cave", "spiders")));
        for (String noun : List.of("beef", "leather", "bones", "wool", "logs", "iron", "drops", "")) {
            assertFalse(ItemNouns.isCreature(List.of(noun)), noun);
        }
        assertFalse(ItemNouns.isCreature(List.of()));
    }

    @Test
    void aNounPhraseKeepsTheWordsAnItemMustContainToBeIt() {
        assertEquals(java.util.Set.of("oak", "logs", "log"), ItemNouns.matchWords(List.of("oak", "logs")));
        assertTrue(ItemNouns.matchWords(List.of("wood")).contains("log"), "wood is the log items' own word");
        assertTrue(ItemNouns.matchWords(List.of("trees")).contains("log"));
        assertTrue(ItemNouns.matchWords(List.of("stone")).contains("cobblestone"), "breaking stone drops cobblestone");
        assertTrue(ItemNouns.matchWords(List.of("stack", "of", "logs")).contains("log"));
        assertFalse(ItemNouns.matchWords(List.of("stack", "of", "logs")).contains("stack"));
        assertEquals(java.util.Set.of(), ItemNouns.matchWords(List.of("stacks", "of")));

        assertTrue(ItemNouns.matchesItem(ItemNouns.matchWords(List.of("logs")), "minecraft:oak_log"));
        assertTrue(ItemNouns.matchesItem(ItemNouns.matchWords(List.of("logs")), "logs"), "the gather_then_give sentinel");
        assertTrue(ItemNouns.matchesItem(ItemNouns.matchWords(List.of("coal")), "minecraft:coal_ore"));
        assertTrue(ItemNouns.matchesItem(ItemNouns.matchWords(List.of("coal")), "minecraft:deepslate_coal_ore"));
        assertTrue(ItemNouns.matchesItem(ItemNouns.matchWords(List.of("iron")), "minecraft:raw_iron"));
        assertTrue(ItemNouns.matchesItem(ItemNouns.matchWords(List.of("diamonds")), "minecraft:diamond"));
        assertTrue(ItemNouns.matchesItem(ItemNouns.matchWords(List.of("berries")), "minecraft:sweet_berries"));
        assertTrue(ItemNouns.matchesItem(ItemNouns.matchWords(List.of("pork")), "minecraft:porkchop"));
        assertFalse(ItemNouns.matchesItem(ItemNouns.matchWords(List.of("logs")), "minecraft:coal"));
        assertFalse(ItemNouns.matchesItem(ItemNouns.matchWords(List.of("coal")), "minecraft:oak_log"));
        assertTrue(ItemNouns.matchesItem(java.util.Set.of(), "minecraft:anything"), "no words: any item");
    }

    @Test
    void gearIsCraftedBecauseItHasDurability() {
        assertTrue(ItemNouns.isCraftedOutput(Items.IRON_PICKAXE));
        assertTrue(ItemNouns.isCraftedOutput(Items.SHIELD));
        assertTrue(ItemNouns.isCraftedOutput(Items.NETHERITE_CHESTPLATE));
        assertTrue(ItemNouns.isCraftedOutput(Items.SHEARS));
        assertFalse(ItemNouns.isCraftedOutput(Items.DIAMOND));
    }

    @Test
    void pluralSpellingsReduceToTheRegistryWord() {
        List<String> torches = ItemNouns.singularVariants("torches");
        assertTrue(torches.contains("torch"), torches.toString());
        assertTrue(ItemNouns.singularVariants("berries").contains("berry"));
        assertTrue(ItemNouns.singularVariants("leaves").contains("leaf"));
        assertTrue(ItemNouns.singularVariants("potatoes").contains("potato"));
        List<String> unchanged = new ArrayList<>(ItemNouns.singularVariants("boots"));
        assertEquals("boots", unchanged.get(0), "the plural-looking id is tried first");
    }

    @Test
    void spacedOutItemNamesJoinIntoTheirIdWord() {
        assertTrue(ItemNouns.isItemSegment("porkchop"));
        assertTrue(ItemNouns.isItemSegment("cobblestone"));
        assertFalse(ItemNouns.isItemSegment("gatherer"));
        assertTrue(ItemNouns.isItemPath("crafting_table"));
        assertFalse(ItemNouns.isItemPath("crafting_tables"));
    }
}
