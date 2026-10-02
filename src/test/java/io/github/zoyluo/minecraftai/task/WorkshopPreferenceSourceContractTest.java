package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks the deterministic workstation precedence without needing a running server world. */
final class WorkshopPreferenceSourceContractTest {
    @Test
    void craftingRequiresARealLocalTableBeforeAThreeByThreeStep() throws IOException {
        String craft = read("CraftTask.java");

        assertTrue(craft.contains("WorkshopLocator.hasNearbyCraftingTable(bot)"));
        assertTrue(craft.contains("recipe.needsCraftingTable()\n                && !WorkshopLocator.hasNearbyCraftingTable(bot)"));
        assertTrue(craft.contains("phase = Phase.ENSURING_TABLE"));
        assertFalse(craft.contains("&& InventoryAction.findItem(bot, Items.CRAFTING_TABLE).isEmpty())"),
                "holding a table must not bypass physical table placement for a 3x3 recipe");
    }

    @Test
    void furnaceLookupIsRecipeAwareAndDoesNotOverwriteDifferentQueuedItems() throws IOException {
        String locator = read("WorkshopLocator.java");
        String smelt = read("SmeltTask.java");

        assertTrue(locator.contains("Blocks.SMOKER"));
        assertTrue(locator.contains("Blocks.BLAST_FURNACE"));
        assertTrue(locator.contains("SmeltChain.RAW_FOODS.contains(input)"));
        assertTrue(locator.contains("BLAST_FURNACE_INPUTS.contains(input)"));
        assertTrue(locator.contains("!queuedInput.isEmpty() && !queuedInput.is(input)"));
        assertTrue(locator.contains("queuedOutput.isEmpty() || queuedOutput.is(output)"));
        assertTrue(smelt.contains("WorkshopLocator.nearestCompatibleFurnace(bot, input, output, requestedItems, excluded)"));
    }

    @Test
    void furnaceSelectionUsesCookingSpeedThenLocalTravelAndFallsBackFromBlockedStations()
            throws IOException {
        String locator = read("WorkshopLocator.java");
        String smelt = read("SmeltTask.java");

        assertTrue(locator.contains("NORMAL_COOK_TICKS = 200"));
        assertTrue(locator.contains("FAST_COOK_TICKS = 100"));
        assertTrue(locator.contains("estimatedCompletionTicks("));
        assertTrue(locator.contains("ESTIMATED_WALK_TICKS_PER_BLOCK"));
        assertTrue(locator.contains("Set<BlockPos> excluded"));
        assertTrue(smelt.contains("private final Set<BlockPos> rejectedFurnaces"));
        assertTrue(smelt.contains("rejectedFurnaces).orElse(null)"));
        assertTrue(smelt.contains("rejectCurrentFurnace(bot, \"baritone_route_unavailable:"));
        assertTrue(smelt.contains("rejectCurrentFurnace(bot, \"baritone_route_stalled\")"));
        assertTrue(smelt.contains("rejectCurrentFurnace(bot, \"baritone_route_ended_short\")"));
        assertTrue(smelt.contains("rejectCurrentFurnace(bot, \"no_stand_position\")"));
    }

    @Test
    void miningServiceRecognizesAnExistingLocalTableBeforeWithdrawingOne() throws IOException {
        String mining = read("MiningServiceTask.java");

        assertTrue(mining.contains("private static boolean hasCraftingTableAccess"));
        assertTrue(mining.contains("|| WorkshopLocator.hasNearbyCraftingTable(bot)"));
        assertTrue(mining.contains("&& !hasCraftingTableAccess(bot)"));
    }

    private static String read(String file) throws IOException {
        return Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/task").resolve(file));
    }
}
