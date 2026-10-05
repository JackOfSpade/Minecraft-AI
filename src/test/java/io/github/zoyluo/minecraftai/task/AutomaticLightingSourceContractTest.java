package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins three decisions about night behaviour: bots have no sleep feature (only human players skip the
 * night, through the vanilla vote), the sleep vote ignores bots, and the automatic lighting reflexes
 * never light the surface.
 */
final class AutomaticLightingSourceContractTest {
    private static final Path ROOT = Path.of("src");
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    private static int count(String source, String needle) {
        return source.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path);
    }

    @Test
    void noSleepToolTaskTypeOrTaskExistsAnyMore() throws IOException {
        assertFalse(Files.exists(MAIN.resolve("task/SleepTask.java")));
        try (Stream<Path> files = Files.walk(ROOT)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !f.getFileName().toString().equals("AutomaticLightingSourceContractTest.java")).toList()) {
                assertFalse(read(file).contains("SleepTask"), file + " still references SleepTask");
            }
        }
        String registry = read(MAIN.resolve("brain/ToolRegistry.java"));
        assertFalse(registry.contains("register(\"sleep\""), "the sleep tool must not exist");
        assertFalse(registry.contains("case \"sleep\""), "assign_task must not accept sleep");
        assertFalse(registry.contains("build, sleep,"), "the task_type list must not offer sleep");
        assertFalse(read(MAIN.resolve("brain/BrainCoordinator.java")).contains("\"sleep\""));
        assertFalse(read(MAIN.resolve("command/MinecraftAiTaskSubcommand.java")).contains("\"sleep\""));
        assertFalse(read(MAIN.resolve("network/MinecraftAiServerNetworking.java")).contains("\"sleep\""));
        String lang = read(Path.of("src/client/resources/assets/minecraftai/lang/en_us.json"));
        assertFalse(lang.contains("minecraftai.sleep\""), "no sleep button or task label");
        assertFalse(read(Path.of("src/client/java/io/github/zoyluo/minecraftai/client/BotCommandBridge.java"))
                .contains("\"sleep\""));
    }

    @Test
    void theSleepVoteOnlyCountsHumans() throws IOException {
        String mixins = read(Path.of("src/main/resources/minecraftai.mixins.json"));
        assertTrue(mixins.contains("\"SleepManagerHumansOnlyMixin\""));
        String mixin = read(MAIN.resolve("mixin/SleepManagerHumansOnlyMixin.java"));
        assertTrue(mixin.contains("PlayerKind.humansOnly(players)"));
        assertTrue(mixin.contains("method = \"update\"") && mixin.contains("method = \"areEnoughDeepSleeping\""));
    }

    @Test
    void bothAutomaticReflexesSkipTheSurfaceAndOnlyLightUnderARoof() throws IOException {
        String watcher = read(MAIN.resolve("task/DangerWatcher.java"));
        assertTrue(watcher.contains("skipAutoLightOnSurface(bot, now, \"night_task\")"));
        assertTrue(watcher.contains("skipAutoLightOnSurface(bot, now, \"dark_area_light\")"));
        assertTrue(watcher.contains("auto_light_skipped"));
        assertFalse(watcher.contains("new LightAreaTask("), "automatic lighting must use LightAreaTask.automatic");
        assertEquals(2, count(watcher, "LightAreaTask.automatic(8, 8)"));
        assertEquals(2, count(watcher, "night.autoLight()") + count(watcher, "night().autoLight()"),
                "night.autoLight gates both automatic reflexes");

        String lightArea = read(MAIN.resolve("task/LightAreaTask.java"));
        assertTrue(lightArea.contains("SurfaceCheck.isOnSurface(world, cell)"));
        for (String explicit : new String[]{"brain/ToolRegistry.java", "command/MinecraftAiTaskSubcommand.java",
                "coordination/IdleCoordinator.java"}) {
            assertFalse(read(MAIN.resolve(explicit)).contains("LightAreaTask.automatic"),
                    explicit + " is an explicit request and lights the surface too");
        }
    }

    @Test
    void activeMiningLightingUsesNativeSpawnRulesAndNeverBlindlyPlacesAtFeet() throws IOException {
        String automatic = read(MAIN.resolve("task/AutomaticLighting.java"));
        assertTrue(automatic.contains("monsterSpawnBlockLightLimit()"));
        assertTrue(automatic.contains("monsterSpawnLightTest().getMaxValue()"));
        assertTrue(automatic.contains("SurfaceCheck.isOnSurface"));
        assertTrue(automatic.contains("BuildAction.canAcceptPlacementAt"));
        assertTrue(automatic.contains("darkestReachableFloor"));
        assertTrue(automatic.contains("miningTorchAutomationEnabled")
                        && automatic.contains("mining().placeTorches()"),
                "active mining must retain its dedicated torch-policy switch");
        assertFalse(automatic.contains("night().autoLight()"),
                "the idle night-light setting must not disable active mining torches");

        String descend = read(MAIN.resolve("task/DescendToYTask.java"));
        String descendLighting = descend.substring(descend.indexOf("private void maybePlaceTorch"),
                descend.indexOf("static void restoreActiveMiningTool"));
        assertTrue(descendLighting.contains("AutomaticLighting.tryPlaceDarkestReachable"));
        assertTrue(descendLighting.contains("AutomaticLighting.miningTorchAutomationEnabled"));
        assertTrue(descendLighting.contains("miner.target() != null")
                        && descendLighting.contains("isMiningIdle()")
                        && descendLighting.contains("hasActiveActions()"),
                "descent may only light at a quiet boundary, never during an in-flight break");
        assertFalse(descendLighting.contains("BuildAction.placeBlockAt"));

        String valuables = read(MAIN.resolve("task/MineValuablesTask.java"));
        int valuableLightingStart = valuables.indexOf("private void maybePlaceTorch");
        String valuableLighting = valuables.substring(valuableLightingStart,
                valuables.indexOf("    @Override", valuableLightingStart));
        assertTrue(valuableLighting.contains("AutomaticLighting.tryPlaceDarkestReachable"));
        assertTrue(valuableLighting.contains("AutomaticLighting.miningTorchAutomationEnabled"));
        assertFalse(valuableLighting.contains("world.canSeeSky"));

        String oreDig = read(MAIN.resolve("task/OreDigTask.java"));
        assertTrue(oreDig.contains("maybePlaceAutomaticTorchAtSafeBoundary"));
        assertTrue(oreDig.contains("AutomaticLighting.miningTorchAutomationEnabled"));
        assertTrue(oreDig.contains("pendingPickupPos != null"));
        assertTrue(oreDig.contains("activeTargetBreakPos != null"));
        assertTrue(oreDig.contains("rareDarkBoundary") && oreDig.contains("torchPlacements++"),
                "rare observed-ore work must light through the shared reflex while preserving its torch budget");

        String obsidian = read(MAIN.resolve("task/CreateObsidianTask.java"));
        assertTrue(obsidian.contains("AutomaticLighting.tryPlaceDarkestReachable"));
        assertTrue(obsidian.contains("AutomaticLighting.miningTorchAutomationEnabled"));
        assertFalse(obsidian.contains("combinedSearchLight"));

        String exploration = read(MAIN.resolve("task/MiningExplorationTask.java"));
        assertTrue(exploration.contains("AutomaticLighting.miningTorchAutomationEnabled"));

        String genericMine = read(MAIN.resolve("task/MineTask.java"));
        assertTrue(genericMine.contains("maybePlaceAutomaticTorch"));
        assertTrue(genericMine.contains("AutomaticLighting.tryPlaceDarkestReachable"));
        assertTrue(genericMine.contains("phase != Phase.SEARCHING")
                        && genericMine.contains("miner.target() != null"),
                "generic block mining must only light at its own quiet search boundary");

        String stripMine = read(MAIN.resolve("task/StripMineTask.java"));
        int stripLightingStart = stripMine.indexOf("private void light(AIPlayerEntity bot)");
        String stripLighting = stripMine.substring(stripLightingStart,
                stripMine.indexOf("    private void move(AIPlayerEntity bot)", stripLightingStart));
        assertTrue(stripLighting.contains("AutomaticLighting.tryPlaceDarkestReachable"));
        assertFalse(stripLighting.contains("BuildAction.placeBlockAt"));
    }
}
