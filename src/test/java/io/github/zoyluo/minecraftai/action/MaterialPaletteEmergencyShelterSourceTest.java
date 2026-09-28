package io.github.zoyluo.minecraftai.action;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the emergency-shelter rescue contract's falling-block exclusion (spec point 9): a shelter
 * must never be sealed -- roof least of all -- with a block that can fall out from under its own
 * placement. Referencing real {@code net.minecraft.item.Items}/{@code Blocks} constants needs a
 * bootstrapped registry that plain JUnit here does not set up (see the other {@code *SourceTest}
 * classes in this project for the same constraint), so this locks the guard at the source level
 * instead, the same way {@code BuildActionVisibilitySourceTest} and
 * {@code EmergencyShelterRecoverySourceContractTest} already do for their own real-behavior checks.
 */
class MaterialPaletteEmergencyShelterSourceTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/action/MaterialPalette.java");

    @Test
    void emergencyShelterSelectionAndCountingExcludeFallingBlocks() throws IOException {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("blockItem.getBlock() instanceof FallingBlock"),
                "must use vanilla's own FallingBlock marker, not a hand-picked item list");

        int pickStart = source.indexOf("public static OptionalInt pickEmergencyShelterBlockSlot(");
        int pickEnd = source.indexOf("public static int countEmergencyShelterBlocks(");
        assertTrue(pickStart >= 0 && pickEnd > pickStart);
        String pickBody = source.substring(pickStart, pickEnd);
        assertTrue(pickBody.contains("SHELTER_EASY_BLOCKS"));
        assertTrue(pickBody.contains("RecipeRegistry.PLANKS"));
        assertTrue(pickBody.contains("RecipeRegistry.LOGS"));
        assertTrue(pickBody.contains("SHELTER_TOOL_BLOCKS"));
        // Every source list the emergency picker draws from must be falling-block-guarded --
        // the roof is placed from whichever of these the build queue happens to reach last.
        assertEquals(4, countOccurrences(pickBody, "if (isFallingBlock(item)) {"),
                "every emergency-shelter block source (easy/planks/logs/tool) must be guarded, "
                        + "including the source that ends up placed as the roof");

        int countStart = source.indexOf("public static int countEmergencyShelterBlocks(");
        int countEnd = source.indexOf("private static boolean isFallingBlock(", countStart);
        assertTrue(countStart >= 0 && countEnd > countStart);
        String countBody = source.substring(countStart, countEnd);
        assertEquals(2, countOccurrences(countBody, "if (isFallingBlock(item)) {"),
                "the emergency-shelter budget count must exclude the same falling blocks the "
                        + "picker excludes, or the two could disagree on how much material exists");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while ((from = haystack.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }
}
