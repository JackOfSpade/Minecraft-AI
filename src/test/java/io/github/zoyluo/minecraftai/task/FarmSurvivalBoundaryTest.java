package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks the farming code to vanilla item use, real breaks, observed cells and walked-over pickups. */
class FarmSurvivalBoundaryTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    @Test
    void farmActionsNeverWriteBlocksOrEditTheInventory() throws IOException {
        String farm = read("action/FarmAction.java");
        assertFalse(farm.contains("setBlock("), "till/plant must be real item use, not block writes");
        assertFalse(farm.contains("destroyBlock("), "harvest must be a real break");
        assertFalse(farm.contains("InventoryAction.removeItems"), "the seed/bone meal is consumed by the item itself");
        assertFalse(farm.contains("InventoryAction.giveItem"));
        assertFalse(farm.contains("placeWater"), "water goes through BucketAction");
        assertTrue(farm.contains("BuildAction.useItemOnFace"));
        assertTrue(farm.contains("BuildAction.useItemOnCell"));
        assertTrue(farm.contains("canObserveFarmCell"), "the harvest proof needs the crop's real outline");
        assertTrue(farm.contains("isWithinBlockInteractionRange"));

        String milk = read("action/MilkCowAction.java");
        assertFalse(milk.contains("InventoryAction.removeItems"));
        assertFalse(milk.contains("InventoryAction.giveItem"));
        assertTrue(milk.contains("InteractAction.useItemOnEntity"));
        assertTrue(milk.contains("isWithinEntityInteractionRange"));

        String irrigate = read("task/IrrigateTask.java");
        assertFalse(irrigate.contains("FarmAction.placeWater"));
        assertTrue(irrigate.contains("BucketAction.placeWater"));
    }

    @Test
    void farmTasksSeeCropsByOutlineAndWalkOverDrops() throws IOException {
        String farmTask = read("task/FarmTask.java");
        assertTrue(farmTask.contains("ObservableWorldQuery.canObserveFarmCell"));
        assertTrue(farmTask.contains("FarmAction.harvestProof"));
        assertTrue(farmTask.contains("HarvestCore.walkOverDrops"),
                "forced pickup is denied in strict_survival; drops must be walked over");
        assertTrue(farmTask.contains("FarmAction.boneMeal"));

        String raid = read("task/RaidCropsTask.java");
        assertTrue(raid.contains("OreProspector.beginFarmCells"));
        assertTrue(raid.contains("FarmAction.harvestProof"));
        assertTrue(raid.contains("HarvestCore.walkOverDrops"));
        assertFalse(raid.contains("forcePickupNearbyAnyOf"));

        String prospector = read("mining/OreProspector.java");
        assertTrue(prospector.contains("ObservableWorldQuery.canObserveFarmCell(bot, pos)"));
    }

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }
}
