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

    private static ItemNouns.Words words(String... noun) {
        return ItemNouns.matchWords(List.of(noun));
    }

    @Test
    void aNounPhraseKeepsTheWordsAnItemMustContainToBeIt() {
        assertEquals(List.of(java.util.Set.of("oak"), java.util.Set.of("logs", "log")), words("oak", "logs").groups());
        assertTrue(words("wood").groups().get(0).contains("log"), "wood is the log items' own word");
        assertTrue(words("trees").groups().get(0).contains("log"));
        assertTrue(words("stone").groups().get(0).contains("cobblestone"), "breaking stone drops cobblestone");
        assertEquals(1, words("stack", "of", "logs").groups().size(), "stack and of say how much, not what");
        assertEquals(ItemNouns.Words.ANY, words("stacks", "of"));
        assertEquals(words("iron"), words("raw", "iron"), "raw iron is what mining iron ore yields");
        assertEquals(words("logs"), words("fresh", "logs"), "no item is called fresh: the word cannot make a log miss");
        assertEquals(ItemNouns.Words.ANY, words("steaks"), "no item id says steak: nothing to hold an item to");
        assertEquals(words("iron"), words("iron", "vein"), "a vein is the ore it is made of");
        assertEquals(ItemNouns.Words.ANY, words("vein"));

        assertTrue(words("logs").matches("minecraft:oak_log"));
        assertTrue(words("logs").matches("logs"), "the gather_then_give sentinel");
        assertTrue(words("coal").matches("minecraft:coal_ore"));
        assertTrue(words("coal").matches("minecraft:deepslate_coal_ore"));
        assertTrue(words("iron").matches("minecraft:raw_iron"));
        assertTrue(words("raw", "iron").matches("minecraft:iron_ore"), "the ore and its raw drop are one collection");
        assertTrue(words("diamonds").matches("minecraft:diamond"));
        assertTrue(words("berries").matches("minecraft:sweet_berries"));
        assertTrue(words("pork").matches("minecraft:porkchop"));
        assertFalse(words("logs").matches("minecraft:coal"));
        assertFalse(words("coal").matches("minecraft:oak_log"));
        assertTrue(ItemNouns.Words.ANY.matches("minecraft:anything"), "no words: any item");
    }

    @Test
    void aSongsNameIsNoResourceTheChatNames() {
        // music_disc_wait, _far, _mall, _blocks: the discs are named after songs, and "and wait" is no second resource
        assertFalse(ItemNouns.isNounWord("wait"));
        assertFalse(ItemNouns.isItemSegment("mall"));
        assertEquals(ItemNouns.Kind.UNKNOWN, ItemNouns.classify(List.of("wait")));
        assertTrue(ItemNouns.isNounWord("logs"), "the other words keep their meaning");
    }

    @Test
    void anItemHasToHaveEveryWordOfThePhraseToBeIt() {
        assertTrue(words("oak", "logs").matches("minecraft:oak_log"));
        assertTrue(words("oak", "logs").matches("minecraft:stripped_oak_log"));
        assertFalse(words("oak", "logs").matches("minecraft:birch_log"), "a species is part of what was asked for");
        assertFalse(words("birch", "logs").matches("minecraft:oak_log"));
        assertFalse(words("raw", "copper").matches("minecraft:raw_iron"));
        assertFalse(words("raw", "iron").matches("minecraft:raw_copper"));
        assertTrue(words("oak", "wood").matches("minecraft:oak_log"), "oak wood is the oak log items");
        assertTrue(words("dark", "oak", "logs").matches("minecraft:dark_oak_log"));

        assertEquals(1, words("lapis", "lazuli").matchedWords("minecraft:lapis_ore"));
        assertEquals(2, words("lapis", "lazuli").matchedWords("minecraft:lapis_lazuli"));
        assertFalse(words("lapis", "lazuli").matches("minecraft:lapis_ore"));
        assertEquals(0, words("oak", "logs").matchedWords("minecraft:coal"));
    }

    @Test
    void twoPhrasesNameTheSameResourceOnlyWhenTheirWordsPairUp() {
        assertTrue(words("logs").sameAs(words("wood")), "two words for the log items");
        assertTrue(words("logs").sameAs(words("log")));
        assertTrue(words("oak", "logs").sameAs(words("oak", "wood")));
        assertTrue(words("stone").sameAs(words("cobblestone")), "breaking stone drops cobblestone");
        assertTrue(words("iron").sameAs(words("raw", "iron")));
        assertTrue(ItemNouns.Words.ANY.sameAs(words("steaks")), "neither says anything an item can be held to");

        assertFalse(words("oak", "logs").sameAs(words("birch", "logs")), "they share the word logs, not the resource");
        assertFalse(words("raw", "iron").sameAs(words("raw", "copper")));
        assertFalse(words("logs").sameAs(words("oak", "logs")), "any log is not the oak ones");
        assertFalse(words("coal").sameAs(words("iron")));
        assertFalse(ItemNouns.Words.ANY.sameAs(words("coal")));
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
