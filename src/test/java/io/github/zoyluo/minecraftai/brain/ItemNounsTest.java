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
