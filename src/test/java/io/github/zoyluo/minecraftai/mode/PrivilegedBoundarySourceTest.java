package io.github.zoyluo.minecraftai.mode;

import io.github.zoyluo.minecraftai.goal.StructureVerifier;
import io.github.zoyluo.minecraftai.task.BlueprintSchema;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks down the small, reviewed set of production adapters that may invoke privileged primitives. */
class PrivilegedBoundarySourceTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");
    private static final Pattern DIRECT_TELEPORT = Pattern.compile("\\.teleportTo\\s*\\(");

    @Test
    void directTeleportsStayInsideReviewedAdapters() throws IOException {
        Set<String> expected = Set.of(
                "entity/AIPlayerEntity.java", // only the super delegation of the teleport overrides that end a fall (no teleport of its own)
                "manager/AIPlayerManager.java",
                "mode/FakePlayerMotion.java",
                "network/MinecraftAiServerNetworking.java",
                "task/DangerWatcher.java",
                "task/GatherQuotaTask.java",
                "task/NavSafetyNet.java");
        Map<String, String> matches = matchingSources(DIRECT_TELEPORT);
        assertEquals(expected, matches.keySet(),
                "A new direct teleport requires an explicit capability or lifecycle-adapter review");

        for (Map.Entry<String, String> entry : matches.entrySet()) {
            if (entry.getKey().equals("entity/AIPlayerEntity.java")) {
                assertTrue(!entry.getValue().replace("super.teleportTo(", "").contains(".teleportTo("), "AIPlayerEntity only delegates to super");
                continue;
            }
            if (entry.getKey().equals("manager/AIPlayerManager.java")
                    || entry.getKey().equals("mode/FakePlayerMotion.java")) {
                continue;
            }
            assertTrue(entry.getValue().contains("CapabilityRuntime"), entry.getKey());
            assertTrue(entry.getValue().contains("EMERGENCY_TELEPORT")
                    || entry.getValue().contains("MANUAL_TELEPORT"), entry.getKey());
        }

        // R5: no path correction moves a bot in any profile. The path-start snap has no privileged relocation any more: a valid
        // start is used as it is, anything else is left by a walked step, and neither ActionPack nor PathExecutor calls a
        // FakePlayerMotion teleport primitive (the safety net's privileged rescues are the only relocations that remain).
        String actionPack = read("action/ActionPack.java");
        int snapMethod = actionPack.indexOf("boolean snapPlayerToNearestStandable");
        int currentStandable = actionPack.indexOf("Standability.isStandable(world, current)", snapMethod);
        int plan = actionPack.indexOf("planAdjacentStep(world, current, reason)", currentStandable);
        assertTrue(snapMethod >= 0 && currentStandable > snapMethod && plan > currentStandable,
                "a valid start is used as it is, before any step is planned");
        assertFalse(actionPack.contains("EMERGENCY_TELEPORT") || actionPack.contains("CapabilityRuntime"),
                "the path-start snap has no privileged relocation");
        for (String primitive : new String[]{"stepTo(", "stepToStandable(", "jumpTo(", "returnToBlockCenter(", "swimStepTo("}) {
            String call = "FakePlayerMotion." + primitive;
            // descendInto (DigDown/Descend/OreDig, converted by their own jobs) is the one remaining caller of stepToStandable.
            String pack = actionPack.replace("io.github.zoyluo.minecraftai.mode.FakePlayerMotion.stepToStandable(", "");
            assertFalse(pack.contains(call), "ActionPack must not call " + call);
            assertFalse(read("pathfinding/PathExecutor.java").contains(call), "PathExecutor must not call " + call);
        }
    }

    @Test
    void suffocationAndDrowningRescuesDecideTheCapabilityBeforeAnyColumnScan() throws IOException {
        String safety = read("task/NavSafetyNet.java");
        int suffocation = safety.indexOf("private boolean escapeSuffocation(");
        int decide = safety.indexOf("PrivilegedCapability.EMERGENCY_TELEPORT", suffocation);
        int scan = safety.indexOf("Standability.isStandable(world, candidate)", suffocation);
        int byInputs = safety.indexOf("return escapeSuffocationByInputs(bot, world, feet);", suffocation);
        assertTrue(suffocation >= 0 && decide > suffocation && scan > decide && byInputs > scan,
                "the EMERGENCY_TELEPORT decision comes first; the unobserved climb scan runs only when it is allowed");
        String suffocationBody = safety.substring(suffocation, byInputs);
        assertFalse(suffocationBody.contains("snapPlayerToNearestStandable"),
                "the strict suffocation branch must walk, shove or dig out, not snap");
        int drowning = safety.indexOf("private boolean emergencyTeleportToAir(");
        int drownDecide = safety.indexOf("PrivilegedCapability.EMERGENCY_TELEPORT", drowning);
        int drownScan = safety.indexOf("cachedFindNearestBreathableStandable(bot, world, feet, now)", drowning);
        assertTrue(drowning >= 0 && drownDecide > drowning && drownScan > drownDecide,
                "the drowning rescue decides the capability before it scans the volume around the bot");
        int inputs = safety.indexOf("boolean escapeSuffocationByInputs(");
        String byInputsBody = safety.substring(inputs, safety.indexOf("private boolean tickEscapeBreak(", inputs));
        assertFalse(byInputsBody.contains("teleportTo("), "the input escape never teleports");
        assertTrue(byInputsBody.contains("castViewRay") || safety.contains("castViewRay"),
                "the escape only digs a block a view ray from the bot's eye reaches");
    }

    @Test
    void digDownRelocationUsesObservedFailureInsteadOfHiddenColumnScan() throws IOException {
        String digDown = read("task/DigDownTask.java");
        assertFalse(digDown.contains("dryColumn("),
                "DigDown must not pre-scan buried fluid columns in strict survival");

        int onStart = digDown.indexOf("protected void onStart");
        int relocation = digDown.indexOf("private void prepareEntryRelocation", onStart);
        int descent = digDown.indexOf("private void initializeFreshDescent", relocation);
        assertTrue(onStart >= 0 && relocation > onStart && descent > relocation);
        String entrySelection = digDown.substring(onStart, descent);
        assertTrue(entrySelection.contains("EpisodeMemory.INSTANCE.isExcluded"),
                "entry relocation must be gated by an observed episode failure");
        assertTrue(entrySelection.contains("Standability.isStandable"),
                "replacement entries still require a factual surface landing");
        assertFalse(entrySelection.contains("getFluidState"),
                "entry selection must not inspect buried fluid state");

        int remember = digDown.indexOf("private void rememberUnusableEntry");
        int beginReturn = digDown.indexOf("private void beginReturn", remember);
        assertTrue(remember >= 0 && beginReturn > remember);
        String failureMemory = digDown.substring(remember, beginReturn);
        assertTrue(failureMemory.contains("ReturnOutcome.WALLED"));
        assertTrue(failureMemory.contains("ReturnOutcome.NO_PROGRESS")
                        && failureMemory.contains("horizontalMode"),
                "only an observed horizontal no-progress may retire the local entry");
        assertTrue(failureMemory.contains("EpisodeMemory.INSTANCE.exclude"));
    }

    @Test
    void resourceAndEntityDiscoveryUsesObservableBoundary() throws IOException {
        // Container code reaches the boundary through ContainerAction (whose canSee wraps ObservableWorldQuery),
        // so each of these files must name its specific gating call, not just any observation call.
        java.util.Map<String, String> containerGates = java.util.Map.of(
                "action/ContainerAction.java", "ObservableWorldQuery.canObserveCell(bot, pos)",
                "task/ContainerTask.java", "ContainerAction.canSee(bot, containerPos)",
                "task/ResupplyTask.java", "ContainerAction.inReachAndSight(bot, containerPos)",
                "task/StockpileTask.java", "ContainerAction.inReachAndSight(bot, containerPos)");
        containerGates.forEach((relative, gate) -> {
            try {
                assertTrue(read(relative).contains(gate), relative + " must gate on " + gate);
            } catch (IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
        });
        for (String relative : Set.of(
                "action/HarvestCore.java",
                "brain/ToolRegistry.java",
                "goal/GoalSnapshotCollector.java",
                "log/DiagnosticLogger.java",
                "mining/OreProspector.java",
                "mining/OreScan.java",
                "mining/assist/PoiDetector.java",
                "mining/assist/ViewSweeper.java",
                "perception/PerceptionCollector.java",
                "task/CraftTask.java",
                "task/CreeperDefenseTask.java",
                "task/DangerWatcher.java",
                "task/FarmTask.java",
                "task/FishTask.java",
                "task/OreDigTask.java",
                "task/RecoverDropsTask.java",
                "task/SiteFinder.java",
                "task/SmeltTask.java",
                "task/StripMineTask.java")) {
            assertTrue(read(relative).contains("ObservableWorldQuery"), relative);
        }

        String prospector = read("mining/OreProspector.java");
        assertTrue(prospector.contains("private void stepObservable"));
        assertTrue(prospector.contains("private void stepRaw"));
        // The palette prefilter and the state match run before the ray, but a candidate is only accepted after
        // the observation proof.
        int observeStep = prospector.indexOf("private void stepObservable");
        int observed = prospector.indexOf("ObservableWorldQuery.canObserveBlock(bot, pos)", observeStep);
        int accepted = prospector.indexOf("best = pos.immutable()", observeStep);
        assertTrue(observeStep >= 0 && observed > observeStep && accepted > observed,
                "an observable-scan candidate must be ray-proven before it can become the result");
        assertTrue(prospector.indexOf("candidateSection(", observeStep) < observed
                && prospector.indexOf("match.test(", observeStep) < observed,
                "the cheap palette and state conjuncts run before the ray casts");

        String oreScan = read("mining/OreScan.java");
        assertFalse(oreScan.contains("veinFrom(Level"), "raw world-only vein scans must not be public");
        assertTrue(oreScan.contains("veinFrom(AIPlayerEntity"));
    }

    @Test
    void placementHasNoDirectMutationFallbackAndTheOtherGuardsRemain() throws IOException {
        String build = read("action/BuildAction.java");
        assertFalse(build.contains("ObservableWorldQuery.canObserveBlock"),
                "face-center observation must not pre-empt the exact inset click sampler");
        assertTrue(build.contains("player.pick(sampleRange, 1.0F, false)"),
                "exact placement rays must use the perception-and-interaction bounded range");
        assertTrue(build.contains("hit.getDirection() != face"));
        assertFalse(build.contains("OperatingProfile"),
                "no profile may use a placement path a survival player lacks");
        assertFalse(build.contains("setBlock("),
                "a block is only ever placed by a real click on a real support face");

        String buildTask = read("task/BuildTask.java");
        assertTrue(buildTask.contains("StructureVerifier.verify"));
        assertTrue(buildTask.contains("structure_incomplete"),
                "a best-effort blueprint must not report completion without exact verification");
        assertEquals(2, occurrences(buildTask, "isObservableStandable(bot, candidate)"),
                "both work-pose scans must cross the observable-world boundary");
        assertEquals(1, occurrences(buildTask, "Standability.isStandable"),
                "raw standability must stay inside the observable work-pose adapter");
        assertFalse(StructureVerifier.matches(
                        null,
                        BlockPos.ZERO,
                        new BlueprintSchema.BlockPlacement(0, 0, 0, "invalid id", null)),
                "invalid blueprint IDs must fail closed before touching world state");

        String container = read("task/ContainerTask.java");
        assertTrue(container.contains("ContainerAction.inReachAndSight(bot, containerPos)"),
                "a transfer must be gated on reach and line of sight");
        assertTrue(container.contains("ContainerAction.open(bot, containerPos"),
                "contents are only learned by really opening the container");
        String containerAction = read("action/ContainerAction.java");
        assertTrue(containerAction.contains("distanceToSqr(pos.getCenter()) <= REACH_SQUARED"));
        assertTrue(containerAction.contains("ObservableWorldQuery.canObserveCell(bot, pos)"));

        assertTrue(matchingSources(Pattern.compile("setDayTime\\s*\\(")).isEmpty(),
                "no bot task may rewrite the time of day: night skipping belongs to the vanilla sleep vote among human players");
    }

    @Test
    void miningServiceDefersRememberedDepotReadsUntilObservableReach() throws IOException {
        String service = read("task/MiningServiceTask.java");

        int onStart = service.indexOf("protected void onStart");
        int onTick = service.indexOf("protected void onTick", onStart);
        assertTrue(onStart >= 0 && onTick > onStart);
        assertFalse(service.substring(onStart, onTick).contains("ContainerAction.resolve"),
                "restoring a remembered depot must not read a remote container");

        int resolveDepot = service.indexOf("private BlockPos resolveDepot");
        int approach = service.indexOf("private ActionResult startDepotApproach", resolveDepot);
        assertTrue(resolveDepot >= 0 && approach > resolveDepot);
        String resolver = service.substring(resolveDepot, approach);
        assertFalse(resolver.contains("ContainerAction.resolve"));
        assertFalse(resolver.contains("nearestContainerNear"));

        int walkToDepot = service.indexOf("private void walkToDepot");
        int deposit = service.indexOf("private void deposit", walkToDepot);
        String approachBody = service.substring(walkToDepot, deposit);
        assertTrue(approachBody.indexOf("canInteractWithDepot(bot, depot)")
                        < approachBody.indexOf("ContainerAction.resolve(bot, depot)"),
                "the remembered depot must cross the reach/visibility guard before resolution");
        int serviceTool = service.indexOf("private void serviceTool", deposit);
        String depositBody = service.substring(deposit, serviceTool);
        assertTrue(depositBody.indexOf("canInteractWithDepot(bot, depot)")
                        < depositBody.indexOf("ContainerAction.resolve(bot, depot)"),
                "container transfer must re-check reach and visibility");

        int interactionGuard = service.indexOf("private static boolean canInteractWithDepot");
        assertTrue(interactionGuard >= 0);
        String guard = service.substring(interactionGuard);
        assertTrue(guard.contains("distanceToSqr(pos.getCenter()) <= REACH_SQUARED"));
        assertTrue(guard.contains("ObservableWorldQuery.canObserveCell(bot, pos)"));
    }

    @Test
    void productionSourceSetDoesNotContainVerificationHarness() throws IOException {
        assertFalse(Files.exists(MAIN.resolve("command/MinecraftAiTestSubcommand.java")));
        assertFalse(Files.exists(MAIN.resolve("command/MinecraftAiVerifySubcommand.java")));
    }

    private static Map<String, String> matchingSources(Pattern pattern) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        try (var paths = Files.walk(MAIN)) {
            for (Path path : paths.filter(file -> file.toString().endsWith(".java")).sorted().toList()) {
                String source = Files.readString(path);
                if (pattern.matcher(source).find()) {
                    result.put(MAIN.relativize(path).toString().replace('\\', '/'), source);
                }
            }
        }
        return result;
    }

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }

    private static int occurrences(String source, String needle) {
        return source.split(Pattern.quote(needle), -1).length - 1;
    }
}
