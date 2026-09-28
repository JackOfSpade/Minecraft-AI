package io.github.zoyluo.minecraftai.craft;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the table/material transaction shape without booting Minecraft's mapped registries in
 * the ordinary JUnit VM. World-backed CraftTask execution remains covered by Fabric GameTests.
 */
final class CraftingHelperAtomicTablePlanTest {
    @Test
    void tableIsReservedBeforeTheTargetAgainstOneVirtualInventory() throws IOException {
        String helper = Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/craft/CraftingHelper.java"));

        assertTrue(helper.contains("planFromCounts("));
        assertTrue(helper.contains("boolean craftingTableAvailable"));
        assertTrue(helper.contains("boolean targetRecipeNeedsTable"));
        assertTrue(helper.contains("combinedPlanner.ensureItem(net.minecraft.item.Items.CRAFTING_TABLE, 1"));
        assertTrue(helper.contains("combinedPlanner.ensureItem(target, requiredCount"));
        assertTrue(helper.indexOf("combinedPlanner.ensureItem(net.minecraft.item.Items.CRAFTING_TABLE, 1")
                        < helper.indexOf("combinedPlanner.ensureItem(target, requiredCount"),
                "table materials must be committed before planning the requested 3x3 recipe");
    }

    @Test
    void craftTaskUsesTheAtomicPlanInsteadOfConcatenatingTwoIndependentPlans() throws IOException {
        String task = Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/task/CraftTask.java"));

        assertTrue(task.contains("boolean tableAvailable = WorkshopLocator.hasNearbyCraftingTable(bot)"));
        assertTrue(task.contains("CraftingHelper.plan(bot, target, targetCount, tableAvailable)"));
        assertFalse(task.contains("new ArrayList<>(tablePlan.steps())"),
                "independent table and target plans would double-book materials");
    }
}
