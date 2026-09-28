package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class GatherVisibilityAndIncrementSourceContractTest {
    @Test
    void collisionlessPlantsUseAnOptInLineOfSightCellFallback() throws IOException {
        String harvestCore = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/action/HarvestCore.java"));
        String gather = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/task/GatherQuotaTask.java"));

        assertTrue(harvestCore.contains("boolean allowObservableCellFallback"));
        assertTrue(harvestCore.contains("allowObservableCellFallback && ObservableWorldQuery.canObserveCell(bot, pos)"),
                "thin targets must still pass a normal line-of-sight cell check");
        assertTrue(gather.contains("needsCellObservation()"));
        assertTrue(gather.contains("harvestBlocks.contains(Blocks.SHORT_GRASS)"));
        assertTrue(gather.contains("harvestBlocks.contains(Blocks.TALL_GRASS)"));
        assertTrue(gather.contains("countSoFar++"),
                "clear_grass and break_blocks must continue to count physical blocks broken");
    }

    @Test
    void explicitGatherCountsNewItemsInsteadOfExistingInventory() throws IOException {
        String gather = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/task/GatherQuotaTask.java"));
        String tools = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/brain/ToolRegistry.java"));
        String prompt = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/brain/BrainCoordinator.java"));

        assertTrue(gather.contains("public static GatherQuotaTask collectAdditional(Item targetItem, int targetCount)"));
        assertTrue(gather.contains("acceptedInventoryAtStart"));
        assertTrue(gather.contains("pickedUpAtStart"));
        assertTrue(gather.contains("countSoFar = countBrokenBlocks || countNewItems ? 0 : acceptedInventoryAtStart"));
        assertTrue(gather.contains("countSoFar = Math.max(countSoFar, observedNewItems)"));
        assertTrue(tools.contains("GatherQuotaTask.collectAdditional("));
        assertTrue(tools.contains("Existing copies in inventory never satisfy count"));
        assertTrue(tools.contains("count always means NEW/additional inventory items"));
        assertTrue(prompt.contains("count always means the additional amount"));
    }
}
