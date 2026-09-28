package io.github.zoyluo.minecraftai.goal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks the planner/task boundary that preserves nearby player-built workstations. */
final class WorkshopPreferenceSourceContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    @Test
    void planningUsesVisibleStationsBeforeSchedulingTheirRecipes() throws IOException {
        String planner = read("goal/GoalPlanner.java");

        assertTrue(planner.contains("WorkshopLocator.hasNearbyCraftingTable(bot)"),
                "a live plan must treat a visible crafting table as a capability");
        assertTrue(planner.contains("private boolean ensureCraftingTableAvailable"),
                "craft dependencies must centralize table availability");
        int tableHelper = planner.indexOf("private boolean ensureCraftingTableAvailable");
        String tableHelperBody = planner.substring(tableHelper,
                planner.indexOf("private boolean ensureNormalFurnaceAvailable", tableHelper));
        assertTrue(tableHelperBody.contains("nearbyCraftingTable")
                        && tableHelperBody.contains("Items.CRAFTING_TABLE"),
                "the table dependency must prefer the local block before a carried/crafted table");
        assertTrue(planner.contains("WorkshopLocator.hasNearbyCompatibleFurnace(bot, input, output)"),
                "smelting must reuse only a furnace-family block compatible with its exact recipe");
        assertTrue(planner.contains("private boolean ensureNormalFurnaceAvailable"),
                "the explicit base goal must retain its normal-furnace postcondition");
    }

    @Test
    void stationPlacementSkipsVisibleTableFurnaceAndChest() throws IOException {
        String stations = read("task/PlaceStationsTask.java");

        assertTrue(stations.contains("if (stationAlreadyNearby(bot, station))"));
        assertTrue(stations.contains("WorkshopLocator.hasNearbyCraftingTable(bot)"));
        assertTrue(stations.contains("WorkshopLocator.hasNearbyFurnace(bot)"));
        assertTrue(stations.contains("Blocks.CHEST") && stations.contains("Blocks.TRAPPED_CHEST"),
                "an existing normal or trapped chest should satisfy the storage station");
    }

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }
}
