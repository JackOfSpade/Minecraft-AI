package io.github.zoyluo.aibot.mining.assist;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins the design 4.2 value table and its two fallbacks. */
class ValueTableTest {
    @Test
    void designTableValues() {
        assertEquals(100, ValueTable.valueOf("minecraft", "ancient_debris"));
        assertEquals(100, ValueTable.valueOf("minecraft", "diamond_ore"));
        assertEquals(90, ValueTable.valueOf("minecraft", "emerald_ore"));
        assertEquals(45, ValueTable.valueOf("minecraft", "gold_ore"));
        assertEquals(40, ValueTable.valueOf("minecraft", "raw_gold_block"));
        assertEquals(35, ValueTable.valueOf("minecraft", "lapis_ore"));
        assertEquals(30, ValueTable.valueOf("minecraft", "redstone_ore"));
        assertEquals(30, ValueTable.valueOf("minecraft", "iron_ore"));
        assertEquals(30, ValueTable.valueOf("minecraft", "raw_iron_block"));
        assertEquals(30, ValueTable.valueOf("minecraft", "gilded_blackstone"));
        assertEquals(25, ValueTable.valueOf("minecraft", "nether_gold_ore"));
        assertEquals(15, ValueTable.valueOf("minecraft", "copper_ore"));
        assertEquals(15, ValueTable.valueOf("minecraft", "raw_copper_block"));
        assertEquals(12, ValueTable.valueOf("minecraft", "coal_ore"));
        assertEquals(8, ValueTable.valueOf("minecraft", "nether_quartz_ore"));
        assertEquals(6, ValueTable.valueOf("minecraft", "amethyst_cluster"));
    }

    @Test
    void deepslateVariantsEqualTheirOverworldOre() {
        for (String ore : List.of("coal", "iron", "copper", "gold", "redstone", "lapis", "diamond", "emerald")) {
            assertEquals(ValueTable.valueOf("minecraft", ore + "_ore"),
                    ValueTable.valueOf("minecraft", "deepslate_" + ore + "_ore"), ore);
        }
        assertEquals(100, ValueTable.valueOf("minecraft", "deepslate_diamond_ore"));
    }

    @Test
    void unknownOresAreWorthTwentyFiveAndNonOresAreWorthNothing() {
        assertEquals(ValueTable.UNKNOWN_ORE_VALUE, ValueTable.valueOf("create", "zinc_ore"));
        assertEquals(ValueTable.UNKNOWN_ORE_VALUE, ValueTable.valueOf("othermod", "deepslate_tin_ore"));
        assertEquals(0, ValueTable.valueOf("minecraft", "stone"));
        assertEquals(0, ValueTable.valueOf("minecraft", "deepslate"));
        assertEquals(0, ValueTable.valueOf("minecraft", "deepslate_bricks"));
        assertEquals(0, ValueTable.valueOf("minecraft", "oak_planks"));
        assertEquals(0, ValueTable.valueOf("minecraft", "iron_block"));
        assertEquals(0, ValueTable.valueOf("minecraft", "diamond_block"));
    }

    @Test
    void namespaceIsIgnoredForKnownPaths() {
        assertEquals(100, ValueTable.valueOf("othermod", "diamond_ore"));
    }

    @Test
    void inputIsNormalisedAndNeverThrows() {
        assertEquals(100, ValueTable.valueOf(null, "  Diamond_Ore "));
        assertEquals(100, ValueTable.valueOf(null, "minecraft:diamond_ore"));
        assertEquals(0, ValueTable.valueOf(null, null));
        assertEquals(0, ValueTable.valueOf(null, ""));
        assertEquals(0, ValueTable.valueOf("minecraft", "   "));
        assertEquals(100, ValueTable.valueOf("diamond_ore"));
    }
}
