package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks lighting to carried torches so mining cannot consume tool-service sticks. */
class MiningTorchReserveSourceContractTest {
    private static final Path TASKS = Path.of("src/main/java/io/github/zoyluo/minecraftai/task");

    @Test
    void oreDigLightingOnlyUsesCarriedTorches() throws IOException {
        String source = Files.readString(TASKS.resolve("OreDigTask.java"));
        String lighting = methodSlice(source, "private boolean maybePlaceAutomaticTorchAtSafeBoundary",
                "private static boolean hasPickupConfirmationSupport");
        String automatic = Files.readString(TASKS.resolve("AutomaticLighting.java"));

        assertTrue(lighting.contains("AutomaticLighting.tryPlaceDarkestReachable"),
                "ore digging must retain the shared carried-torch lighting reflex");
        assertTrue(automatic.contains("InventoryAction.countItem(bot, Items.TORCH)"),
                "automatic lighting must use carried torches");
        assertFalse(automatic.contains("Items.COAL"),
                "automatic lighting must not convert incidental coal into torches");
        assertFalse(automatic.contains("Items.STICK"),
                "automatic lighting must preserve tool-service sticks");
        assertFalse(lighting.contains("ore_dig_torch_crafted"),
                "ore digging must not publish a synthetic torch craft");
    }

    @Test
    void descentLightingUsesTheSharedCarriedTorchReflex() throws IOException {
        String source = Files.readString(TASKS.resolve("DescendToYTask.java"));
        String lighting = methodSlice(source, "private boolean maybePlaceTorch", "static void restoreActiveMiningTool");

        assertTrue(lighting.contains("AutomaticLighting.tryPlaceDarkestReachable"),
                "descent must retain the shared carried-torch lighting reflex");
        assertFalse(lighting.contains("BuildAction.placeBlockAt"),
                "descent must not blindly place a torch at its own feet");
        assertFalse(lighting.contains("Items.COAL"),
                "descent must not convert incidental coal into torches");
        assertFalse(lighting.contains("Items.STICK"),
                "descent must preserve tool-service sticks");
        assertFalse(lighting.contains("giveItem("),
                "descent lighting must not synthesize inventory");
    }

    private static String methodSlice(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue(start >= 0 && end > start,
                () -> "missing lighting method boundary: " + startMarker + " -> " + endMarker);
        return source.substring(start, end);
    }
}
