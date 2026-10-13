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
    void everyAutomaticReflexSkipsTheSurfaceAndOnlyLightsUnderARoof() throws IOException {
        String watcher = read(MAIN.resolve("task/DangerWatcher.java"));
        assertTrue(watcher.contains("skipAutoLightOnSurface(bot, now, \"night_task\")"));
        assertTrue(watcher.contains("skipAutoLightOnSurface(bot, now, \"dark_area_light\")"));
        assertTrue(watcher.contains("auto_light_skipped"));
        assertFalse(watcher.contains("new LightAreaTask("), "automatic lighting must use LightAreaTask.automatic");
        // Only the two genuinely idle reflexes own an area-light task. A dark-trap torch is
        // interaction-range only, so it cannot pause the work that brought the bot there.
        assertEquals(2, count(watcher, "LightAreaTask.automatic(8, 8)"));
        assertTrue(watcher.contains("AutomaticLighting.tryPlaceTorchAtFeetPassively(bot)"));
        assertTrue(watcher.contains("dark_trap_passive_light"));
        assertEquals(3, count(watcher, "night.autoLight()") + count(watcher, "night().autoLight()"),
                "night.autoLight gates every automatic reflex");
        assertTrue(watcher.contains("return !SurfaceCheck.isOnSurface(world, feet);"),
                "a dark-trap cell is never on the surface");

        String lightArea = read(MAIN.resolve("task/LightAreaTask.java"));
        assertTrue(lightArea.contains("SurfaceCheck.isOnSurface(world, cell)"));
        for (String explicit : new String[]{"brain/ToolRegistry.java", "command/MinecraftAiTaskSubcommand.java",
                "coordination/IdleCoordinator.java"}) {
            assertFalse(read(MAIN.resolve(explicit)).contains("LightAreaTask.automatic"),
                    explicit + " is an explicit request and lights the surface too");
        }
    }

    @Test
    void activeMiningLightingUsesNativeSpawnRulesAndOnlyPlacesAtFeetWithProof() throws IOException {
        String automatic = read(MAIN.resolve("task/AutomaticLighting.java"));
        assertTrue(automatic.contains("monsterSpawnBlockLightLimit()"));
        assertTrue(automatic.contains("monsterSpawnLightTest().getMaxValue()"));
        assertTrue(automatic.contains("SurfaceCheck.isOnSurface"));
        assertTrue(automatic.contains("BuildAction.canAcceptPlacementAt"));
        assertTrue(automatic.contains("tryPlaceTorchAtFeet"));
        assertTrue(automatic.contains("world.getBlockState(feet).isAir() || !BuildAction.canAcceptPlacementAt(bot, feet)"),
                "the bot's own floor is the only automatic candidate, with a real vanilla placement proof");
        assertFalse(automatic.contains("darkestReachableFloor") || automatic.contains("ObservableWorldQuery"),
                "automatic lighting must not scan neighbouring cells");
        int usingItemGuard = automatic.indexOf("if (bot.isUsingItem())");
        int equipTorch = automatic.indexOf("InventoryAction.equipFromSlot");
        assertTrue(usingItemGuard >= 0 && equipTorch > usingItemGuard,
                "a held vanilla use must defer before automatic lighting mutates the selected slot");
        assertTrue(automatic.contains("miningTorchAutomationEnabled")
                        && automatic.contains("mining().placeTorches()"),
                "active mining must retain its dedicated torch-policy switch");
        int activeLightingStart = automatic.indexOf("static Placement tryPlaceTorchAtFeet(AIPlayerEntity bot)");
        int passiveLightingStart = automatic.indexOf("static Placement tryPlaceTorchAtFeetPassively(AIPlayerEntity bot, int serverTick)");
        assertTrue(activeLightingStart >= 0 && passiveLightingStart > activeLightingStart,
                "the active-mining torch helper must remain separately auditable from the passive reflex");
        assertFalse(automatic.substring(activeLightingStart, passiveLightingStart).contains("night().autoLight()"),
                "the idle night-light setting must not disable active mining torches");

        String descend = read(MAIN.resolve("task/DescendToYTask.java"));
        int descendLightingStart = descend.lastIndexOf(
                "maybePlaceTorch(AIPlayerEntity bot, ServerLevel world, BlockPos feet)");
        int descendLightingEnd = descend.indexOf("static void restoreActiveMiningTool", descendLightingStart);
        assertTrue(descendLightingStart >= 0 && descendLightingEnd > descendLightingStart,
                "the descent torch boundary must remain a locally auditable helper");
        String descendLighting = descend.substring(descendLightingStart, descendLightingEnd);
        assertTrue(descendLighting.contains("AutomaticLighting.tryPlaceTorchAtFeet"));
        assertTrue(descendLighting.contains("AutomaticLighting.miningTorchAutomationEnabled"));
        assertTrue(descendLighting.contains("miner.target() != null")
                        && descendLighting.contains("isMiningIdle()")
                        && descendLighting.contains("hasActiveActions()"),
                "descent may only light at a quiet boundary, never during an in-flight break");
        assertTrue(descendLighting.contains("if (bot.isUsingItem())"),
                "a held item use must yield descent before it can start the next stair break");
        assertTrue(descendLighting.indexOf("AutomaticLighting.Placement placement")
                        < descendLighting.indexOf("lastTorchCheckBudget = totalBudget()"),
                "an in-progress placement must not consume the descent lighting cadence");
        assertFalse(descendLighting.contains("BuildAction.placeBlockAt"));

        String valuables = read(MAIN.resolve("task/MineValuablesTask.java"));
        int valuableLightingStart = valuables.lastIndexOf(
                "maybePlaceTorch(AIPlayerEntity bot, boolean beforeMining)");
        int valuableLightingEnd = valuables.indexOf("    @Override", valuableLightingStart);
        assertTrue(valuableLightingStart >= 0 && valuableLightingEnd > valuableLightingStart,
                "the valuables torch boundary must remain a locally auditable helper");
        String valuableLighting = valuables.substring(valuableLightingStart, valuableLightingEnd);
        assertTrue(valuableLighting.contains("AutomaticLighting.tryPlaceTorchAtFeet"));
        assertTrue(valuableLighting.contains("AutomaticLighting.miningTorchAutomationEnabled"));
        assertTrue(valuableLighting.contains("bot.isUsingItem()")
                        && valuableLighting.contains("beforeMining && bot.getActionPack().hasActiveActions()"),
                "valuables mining must yield an imminent break to an existing hand/action owner");
        assertTrue(valuableLighting.indexOf("AutomaticLighting.Placement placement")
                        < valuableLighting.indexOf("lastTorchCheckTick = elapsed"),
                "an in-progress valuables placement must retry without consuming its cadence");
        int valuableMoveStart = valuables.indexOf("    private void move(AIPlayerEntity bot)");
        int valuableMoveEnd = valuables.indexOf("    private void startMiningTarget", valuableMoveStart);
        assertTrue(valuableMoveStart >= 0 && valuableMoveEnd > valuableMoveStart,
                "valuables arrival handoff must remain locally auditable");
        String valuableMove = valuables.substring(valuableMoveStart, valuableMoveEnd);
        int valuableHeldUse = valuableMove.indexOf("if (bot.isUsingItem())");
        int valuableStopAll = valuableMove.indexOf("bot.getActionPack().stopAll()");
        assertTrue(valuableHeldUse >= 0 && valuableStopAll > valuableHeldUse,
                "valuables must preserve a held food/bow use before stopping its arrived route");
        assertFalse(valuableLighting.contains("world.canSeeSky"));

        String oreDig = read(MAIN.resolve("task/OreDigTask.java"));
        assertTrue(oreDig.contains("maybePlaceAutomaticTorchAtSafeBoundary"));
        assertTrue(oreDig.contains("AutomaticLighting.miningTorchAutomationEnabled"));
        assertTrue(oreDig.contains("pendingPickupPos != null"));
        assertTrue(oreDig.contains("activeTargetBreakPos != null"));
        assertTrue(oreDig.contains("rareDarkBoundary") && oreDig.contains("torchPlacements++"),
                "rare observed-ore work must light through the shared reflex while preserving its torch budget");
        assertTrue(oreDig.contains("maybePlaceAutomaticTorchAtSafeBoundary(bot, bot.level(), true)"),
                "a freshly reachable observed ore must re-check lighting before its first swing");
        assertTrue(oreDig.contains("boolean beforeMining")
                        && oreDig.contains("beforeMining && lastAutomaticTorchCheckTick == elapsed"),
                "the pre-break rare-expedition check may bypass cadence but never duplicate a same-tick probe");
        assertTrue(oreDig.contains("bot.getActionPack().hasActiveActions()"),
                "OreDig must not swap to a torch or start a fresh target break while another action owns controls");
        int oreDigLightingStart = oreDig.indexOf("private boolean maybePlaceAutomaticTorchAtSafeBoundary");
        int oreDigLightingEnd = oreDig.indexOf("private static boolean hasPickupConfirmationSupport", oreDigLightingStart);
        assertTrue(oreDigLightingStart >= 0 && oreDigLightingEnd > oreDigLightingStart,
                "OreDig's shared lighting boundary must remain locally auditable");
        String oreDigLighting = oreDig.substring(oreDigLightingStart, oreDigLightingEnd);
        assertTrue(oreDigLighting.contains("bot.isUsingItem()")
                        && oreDigLighting.contains("beforeMining && bot.getActionPack().hasActiveActions()"),
                "OreDig must yield a new break to a held use or pre-existing action owner");
        assertTrue(oreDigLighting.indexOf("AutomaticLighting.Placement placement")
                        < oreDigLighting.indexOf("lastAutomaticTorchCheckTick = elapsed"),
                "an in-progress OreDig placement must retry without consuming its cadence");

        String obsidian = read(MAIN.resolve("task/CreateObsidianTask.java"));
        assertTrue(obsidian.contains("AutomaticLighting.tryPlaceTorchAtFeet"));
        assertTrue(obsidian.contains("AutomaticLighting.miningTorchAutomationEnabled"));
        assertFalse(obsidian.contains("combinedSearchLight"));
        int searchOwner = obsidian.indexOf("if (miner.target() == null && searchActionOwned(bot))");
        int searchLighting = obsidian.indexOf("if (!searchLightingReady(bot))");
        assertTrue(searchOwner >= 0 && searchLighting > searchOwner,
                "obsidian SEARCH must yield route/walk/foreign-action owners before lighting");
        int ownerHelper = obsidian.indexOf("private boolean searchActionOwned");
        int lightingHelper = obsidian.indexOf("private boolean ensureAutomaticSearchLighting");
        assertTrue(ownerHelper >= 0 && lightingHelper > ownerHelper,
                "obsidian SEARCH ownership helpers must remain locally auditable");
        String searchOwnership = obsidian.substring(ownerHelper, lightingHelper);
        assertTrue(searchOwnership.contains("!bot.getActionPack().isMiningIdle()")
                        && searchOwnership.contains("!bot.getActionPack().isPathExecutorIdle()")
                        && searchOwnership.contains("!bot.getActionPack().isWalkToIdle()")
                        && searchOwnership.contains("bot.getActionPack().hasActiveActions()"),
                "obsidian SEARCH lighting must wait for mining, route, walk, and held-action owners");
        int lightingOwnerGuard = obsidian.indexOf("if (miner.target() != null || searchActionOwned(bot))",
                lightingHelper);
        int torchPlacement = obsidian.indexOf("AutomaticLighting.tryPlaceTorchAtFeet", lightingHelper);
        assertTrue(lightingOwnerGuard > lightingHelper && torchPlacement > lightingOwnerGuard,
                "the obsidian torch helper must reject a live miner, route, walk, or held action before it can equip a torch");

        String exploration = read(MAIN.resolve("task/MiningExplorationTask.java"));
        assertTrue(exploration.contains("AutomaticLighting.miningTorchAutomationEnabled"));

        String genericMine = read(MAIN.resolve("task/MineTask.java"));
        assertTrue(genericMine.contains("maybePlaceAutomaticTorch"));
        assertTrue(genericMine.contains("AutomaticLighting.tryPlaceTorchAtFeet"));
        assertTrue(genericMine.contains("phase != Phase.SEARCHING")
                        && genericMine.contains("miner.target() != null"),
                "generic block mining must only light at its own quiet search boundary");
        int genericMineLightingStart = genericMine.indexOf("private boolean maybePlaceAutomaticTorch");
        int genericMineLightingEnd = genericMine.indexOf("    private void search", genericMineLightingStart);
        assertTrue(genericMineLightingStart >= 0 && genericMineLightingEnd > genericMineLightingStart,
                "generic mining's shared lighting boundary must remain locally auditable");
        String genericMineLighting = genericMine.substring(genericMineLightingStart, genericMineLightingEnd);
        assertTrue(genericMineLighting.contains("bot.isUsingItem()")
                        && genericMineLighting.contains("beforeMining && bot.getActionPack().hasActiveActions()"),
                "generic mining must yield a new break to a held use or pre-existing action owner");
        assertTrue(genericMineLighting.indexOf("AutomaticLighting.Placement placement")
                        < genericMineLighting.indexOf("lastTorchCheckElapsed = elapsed"),
                "an in-progress generic-mining placement must retry without consuming its cadence");
        int genericMoveStart = genericMine.indexOf("    private void move(AIPlayerEntity bot)");
        int genericMoveEnd = genericMine.indexOf("    private void mine(AIPlayerEntity bot)", genericMoveStart);
        assertTrue(genericMoveStart >= 0 && genericMoveEnd > genericMoveStart,
                "generic mining arrival handoff must remain locally auditable");
        String genericMove = genericMine.substring(genericMoveStart, genericMoveEnd);
        int genericHeldUse = genericMove.indexOf("if (bot.isUsingItem())");
        int genericStopAll = genericMove.indexOf("bot.getActionPack().stopAll()");
        assertTrue(genericHeldUse >= 0 && genericStopAll > genericHeldUse,
                "generic mining must preserve a held food/bow use before stopping its arrived route");

        String stripMine = read(MAIN.resolve("task/StripMineTask.java"));
        int stripLightingStart = stripMine.indexOf("private void light(AIPlayerEntity bot)");
        String stripLighting = stripMine.substring(stripLightingStart,
                stripMine.indexOf("    private void move(AIPlayerEntity bot)", stripLightingStart));
        assertTrue(stripLighting.contains("AutomaticLighting.tryPlaceTorchAtFeet"));
        assertFalse(stripLighting.contains("BuildAction.placeBlockAt"));
    }

    @Test
    void netherLightingRequiresARealLocalEnclosureNotTheWorldCeiling() throws IOException {
        String automatic = read(MAIN.resolve("task/AutomaticLighting.java"));

        assertTrue(automatic.contains("world.dimension() == Level.NETHER"));
        assertTrue(automatic.contains("isEnclosedNetherSpace(world, pos)"));
        assertTrue(automatic.contains("for (int dy = 1; dy <= 6; dy++)"),
                "a nearby ceiling, rather than the distant Nether roof, is required");
        assertTrue(automatic.contains("pos.north()") && automatic.contains("pos.south()")
                        && automatic.contains("pos.east()") && automatic.contains("pos.west()"),
                "a nearby side wall distinguishes a cave or corridor from exposed Nether terrain");
        assertTrue(automatic.contains("The End has no comparable roof"),
                "open End islands follow the normal surface rule; only naturally enclosed End spaces are lit");
    }

    @Test
    void passiveLightingUsesAStableCadenceAndNeverTakesOverTaskMovementOrInventory() throws IOException {
        String automatic = read(MAIN.resolve("task/AutomaticLighting.java"));
        String coordinator = read(MAIN.resolve("task/BotTickCoordinator.java"));

        assertTrue(automatic.contains("PASSIVE_CHECK_INTERVAL_TICKS = 20"));
        assertTrue(automatic.contains("tryPlaceTorchAtFeetPassively(AIPlayerEntity bot, int serverTick)"));
        assertTrue(automatic.contains("!actions.isPathExecutorIdle() || !actions.hasActiveActions()"),
                "passive lighting may continue an existing route or use only a fully quiet boundary");
        assertTrue(automatic.contains("!actions.isWalkToIdle() || !actions.isMiningIdle() || !actions.stepIdle()"),
                "a direct walk, break, or step must retain exclusive ownership");
        assertTrue(automatic.contains("inventory.setSelectedSlot(selectedSlot)")
                        && automatic.contains("LookAction.setYawPitch(bot, yaw, pitch)"),
                "the task's held slot and aim must be restored after a background placement");
        assertTrue(automatic.contains("restoreInventorySwap && equippedSlot >= 0")
                        && automatic.contains("inventory.getNonEquipmentItems().set(torchSlot, equippedStack)")
                        && automatic.contains("inventory.getNonEquipmentItems().set(equippedSlot, sourceStack)"),
                "a passive torch must reverse its exact full-hotbar swap before returning to the task");
        assertTrue(coordinator.contains("AutomaticLighting.tryPlaceTorchAtFeetPassively(bot, tick);"));
        assertTrue(coordinator.indexOf("ShieldGuard.INSTANCE.tickBot(server, bot);")
                        < coordinator.indexOf("AutomaticLighting.tryPlaceTorchAtFeetPassively(bot, tick);")
                        && coordinator.indexOf("AutomaticLighting.tryPlaceTorchAtFeetPassively(bot, tick);")
                        < coordinator.indexOf("GoalExecutor.INSTANCE.tickBot(server, bot)"),
                "passive lighting must yield to the shield and settle before goal ownership runs");
    }
}
