package io.github.zoyluo.minecraftai.mining.assist;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistRulesTest {
    @Test
    void trapBlocksAreThePlatesTripwireTntAndDispenser() {
        for (String path : List.of("stone_pressure_plate", "oak_pressure_plate", "crimson_pressure_plate",
                "light_weighted_pressure_plate", "heavy_weighted_pressure_plate", "polished_blackstone_pressure_plate",
                "tripwire", "tripwire_hook", "tnt", "dispenser")) {
            assertTrue(AssistRules.isTrap("minecraft", path), path);
        }
    }

    @Test
    void ordinaryBlocksAndLookalikesAreNotTraps() {
        for (String path : List.of("stone", "dropper", "observer", "tnt_minecart", "string", "oak_button",
                "stone_button", "lever", "hopper", "pressure_plate_holder")) {
            assertFalse(AssistRules.isTrap("minecraft", path), path);
        }
        assertFalse(AssistRules.isTrap("minecraft", null));
    }

    @Test
    void moddedNamespacesNeverCountAsVanillaTraps() {
        assertFalse(AssistRules.isTrap("othermod", "stone_pressure_plate"));
        assertFalse(AssistRules.isTrap("othermod", "tnt"));
    }

    @Test
    void trapMatchingIsCaseAndBlankInsensitive() {
        assertTrue(AssistRules.isTrap(null, " TNT "));
        assertTrue(AssistRules.isTrap("", "dispenser"));
        assertTrue(AssistRules.isTrap("MineCraft", "tripwire"));
    }

    @Test
    void biomeRulesAreExactIds() {
        assertTrue(AssistRules.isDeepDarkBiome("minecraft:deep_dark"));
        assertTrue(AssistRules.isDeepDarkBiome(" Minecraft:Deep_Dark "));
        assertFalse(AssistRules.isDeepDarkBiome("minecraft:dripstone_caves"));
        assertFalse(AssistRules.isDeepDarkBiome("othermod:deep_dark"));
        assertFalse(AssistRules.isDeepDarkBiome(null));

        assertTrue(AssistRules.isLushBiome("minecraft:lush_caves"));
        assertFalse(AssistRules.isLushBiome("minecraft:plains"));
        assertFalse(AssistRules.isLushBiome(""));
    }

    @Test
    void vanillaNamespaceDetection() {
        assertTrue(AssistRules.isVanilla(null));
        assertTrue(AssistRules.isVanilla(" "));
        assertTrue(AssistRules.isVanilla("minecraft"));
        assertTrue(AssistRules.isVanilla("MINECRAFT"));
        assertFalse(AssistRules.isVanilla("create"));
    }
}
