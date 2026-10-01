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
            if (entry.getKey().equals("manager/AIPlayerManager.java")) {
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
        // The FakePlayerMotion teleport primitives and ActionPack.descendInto are deleted: nothing in production can call them.
        String motion = read("mode/FakePlayerMotion.java");
        assertFalse(motion.contains("teleportTo(") || motion.contains("setPos(") || motion.contains("snapTo("),
                "FakePlayerMotion only reads (collision and occupant checks); it never moves a bot");
        for (String removed : new String[]{"stepTo(", "stepToStandable(", "swimStepTo(", "jumpTo(", "shiftToSupportEdge(",
                "returnToBlockCenter(", "nudgeWithinBlockToward("}) {
            assertFalse(motion.contains("static boolean " + removed), "FakePlayerMotion." + removed + " is deleted");
        }
        assertFalse(actionPack.contains("boolean descendInto("), "ActionPack.descendInto (the teleporting descent) is deleted");
        assertFalse(actionPack.contains("tryPhysicalSnap("), "ActionPack.tryPhysicalSnap (the teleporting snap) is deleted");
        assertFalse(read("pathfinding/PathExecutor.java").contains("FakePlayerMotion.step"),
                "PathExecutor never calls a FakePlayerMotion move primitive");
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
    void strictWaterRescueProvesVisibleCellsBeforeReadingTheirState() throws IOException {
        String safety = read("task/NavSafetyNet.java");
        int crisis = safety.indexOf("if (inCrisis)");
        int hiddenScan = safety.indexOf("boolean hiddenWaterScan = canUseHiddenWaterScan(bot);", crisis);
        int routeSearch = safety.indexOf("cachedFindPhysicalWaterEscape(bot, world, feet, now, hiddenWaterScan)", crisis);
        int urgency = safety.indexOf("boolean surfaceAirUrgent = bot.getAirSupply() <= surfaceAirThreshold(bot, hiddenWaterScan);", routeSearch);
        int physicalAir = safety.indexOf("if (surfaceAirUrgent && physicalStepTowardAir", urgency);
        int fallback = safety.indexOf("beginObservableWaterRescueStep(bot, world, feet,", physicalAir);
        int retainedFallback = safety.indexOf("strictWaterEscapeSearches.get(bot.getUUID()), surfaceAirUrgent", fallback);
        assertTrue(crisis >= 0 && hiddenScan > crisis && routeSearch > hiddenScan && urgency > routeSearch
                        && physicalAir > urgency && fallback > physicalAir && retainedFallback > fallback,
                "strict water recovery must compute one air boundary, take its urgent physical air stroke before speculative strict fallback, and retain that fallback for non-urgent observed exploration");

        int candidate = safety.indexOf("private static WaterEscapeProbe probeWaterEscapeCell(");
        int cellProof = safety.indexOf("canObserveWaterRescueColumn(bot, candidate)", candidate);
        int feetRead = safety.indexOf("BlockState feet = world.getBlockState(candidate)", candidate);
        int headRead = safety.indexOf("BlockState head = world.getBlockState(candidate.above())", candidate);
        int supportProof = safety.indexOf("ObservableWorldQuery.canObserveCellThroughFluids(bot, candidate.below())", candidate);
        int freshStandability = safety.indexOf("Standability.isStandableFresh(world, candidate)", candidate);
        int visibleInvalid = safety.indexOf("WaterEscapeProbe.valid(WaterEscapeCell.DRY) : WaterEscapeProbe.invalid()", freshStandability);
        assertTrue(candidate >= 0 && cellProof > candidate && feetRead > cellProof && headRead > feetRead
                        && supportProof > headRead && freshStandability > supportProof && visibleInvalid > freshStandability,
                "a strict water candidate needs visible feet/head before raw reads and a visible support cell before standability, so an exposed empty floor completes as invalid instead of UNKNOWN");

        int columnProof = safety.indexOf("private static boolean canObserveWaterRescueColumn(");
        assertTrue(columnProof >= 0
                        && safety.indexOf("ObservableWorldQuery.canObserveCellThroughFluids(bot, candidate)", columnProof) > columnProof
                        && safety.indexOf("ObservableWorldQuery.canObserveCellThroughFluids(bot, candidate.above())", columnProof) > columnProof,
                "water-route proof must observe both feet and head cells through the water medium");

        String observation = read("mode/ObservableWorldQuery.java");
        String waterCollider = body(observation, "public static boolean canObserveColliderThroughFluids",
                "private static boolean observeShapeFaces");
        int waterColliderGate = waterCollider.indexOf("\"observable_water_collider_query\"");
        int waterColliderRay = waterCollider.indexOf("ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE");
        assertTrue(waterColliderGate >= 0 && waterColliderRay > waterColliderGate
                        && waterCollider.contains("hit.getType() == HitResult.Type.BLOCK")
                        && waterCollider.contains("hit.getBlockPos().equals(pos)")
                        && !waterCollider.contains("getBlockState(pos)")
                        && !waterCollider.contains("FaceAim.aim"),
                "the fluid-transparent collider helper must use a first-hit Fluid.NONE proof before any target-state shape read");
        assertTrue(observation.contains("public static boolean canObserveCellThroughFluids")
                        && observation.contains("\"observable_water_cell_query\")")
                        && observation.contains("canObserveCellWithinAfterPolicy(bot, pos, 0, ClipContext.Fluid.NONE)"),
                "only water navigation may use the fluid-transparent line-of-sight helpers");
        assertTrue(observation.contains("public static boolean canObserveCollider(AIPlayerEntity bot, BlockPos pos)")
                        && observation.contains("\"observable_block_query\", ClipContext.Fluid.ANY")
                        && observation.contains("private static boolean canObserveCellWithinAfterPolicy(AIPlayerEntity bot, BlockPos pos, int range)")
                        && observation.contains("canObserveCellWithinAfterPolicy(bot, pos, range, ClipContext.Fluid.ANY)"),
                "ordinary block, collider, and interaction observers must retain Fluid.ANY occlusion");
        assertEquals(Set.of("mode/ObservableWorldQuery.java", "task/NavSafetyNet.java", "task/SwimRoute.java"),
                matchingSources(Pattern.compile("canObserve(?:Cell|Collider)ThroughFluids\\s*\\(")).keySet(),
                "fluid-transparent observation must stay scoped to the reviewed water-navigation helpers");

        int crisisEnd = safety.indexOf("/** Memoized front", crisis);
        int routeSearchEnd = safety.indexOf("private static int cellsFromOrigin", candidate);
        assertFalse(safety.substring(crisis, crisisEnd).contains("Standability.clearCache()"),
                "a water crisis must use fresh local reads instead of clearing the global cache every tick");
        assertFalse(safety.substring(candidate, routeSearchEnd).contains("Standability.clearCache()"),
                "the hot water-route search must not evict other bots' standability cache");
        int probeEnd = safety.indexOf("/** Immediate caller view", candidate);
        String probe = safety.substring(candidate, probeEnd);
        assertTrue(probe.contains("WaterEscapeProbe.unobserved()")
                        && probe.contains("WaterEscapeProbe.invalid()"),
                "strict rescue must distinguish a hidden candidate worth retrying from a visible physical rejection");

        int depthThreshold = safety.indexOf("private static int surfaceAirThreshold(AIPlayerEntity bot, boolean hiddenWaterScan)");
        int measuredDepth = safety.indexOf("verticalWaterCellsToSurface", depthThreshold);
        assertTrue(depthThreshold >= 0 && safety.indexOf("if (!hiddenWaterScan)", depthThreshold) > depthThreshold
                        && measuredDepth > depthThreshold,
                "strict water recovery must keep the conservative air floor before any hidden depth scan");

        // Strict survival is bounded by the same live perception radius that tells it what the bot
        // can see, not by a second, smaller six-block/256-candidate policy. A visible shore must
        // not become unknowable merely because an old safety heuristic stopped looking at it.
        String route = read("task/SwimRoute.java");
        assertFalse(safety.contains("STRICT_WATER_RESCUE_OBSERVATION_RADIUS = 6")
                        || safety.contains("STRICT_WATER_RESCUE_MAX_CANDIDATES = 256"),
                "water rescue must not hard-cap strict observation at six blocks or 256 candidates");
        assertFalse(route.contains("STRICT_OBSERVATION_RADIUS = 6")
                        || route.contains("STRICT_MAX_NODES = 256")
                        || route.contains("STRICT_MAX_CANDIDATES = 256"),
                "swim routing must not discard a configured-visible route through a strict 6/256 cap");
        assertTrue(route.contains("Math.max(1, MinecraftAiConfig.get().perception().radius())"),
                "strict swim observation must use the configured perception radius");

        // A cache entry carries the capability that produced it, then re-proves both the next
        // physical step and the dry shore before it can affect strict movement. The same provenance
        // rule applies to an already-running NavSafetyNet step when a profile flips mid-tick.
        assertTrue(safety.contains("cached.hiddenBlockScan() == hiddenWaterScan"),
                "water-route cache reuse must preserve hidden-scan provenance");
        assertTrue(safety.contains("reproveWaterEscapeStep(bot, world, escape, hiddenWaterScan)"),
                "a cached rescue route must be re-proved before strict movement");
        int reprove = safety.indexOf("private static boolean reproveWaterEscapeStep(");
        assertTrue(reprove >= 0
                        && safety.indexOf("observedWaterEscapeCell(bot, world, escape.next(), hiddenWaterScan)", reprove) > reprove
                        && safety.indexOf("observedWaterEscapeCell(bot, world, escape.shore(), hiddenWaterScan)", reprove) > reprove,
                "cached rescue reproof needs both the immediate next cell and the destination shore");
        int rescueInFlight = safety.indexOf("private boolean rescueStepInFlight(AIPlayerEntity bot, boolean hiddenWaterScan)");
        int beginRescue = safety.indexOf("private boolean beginRescueRecenter", rescueInFlight);
        String inFlight = safety.substring(rescueInFlight, beginRescue);
        assertTrue(safety.contains("private final Map<UUID, RescueStepAdmission> rescueSteps")
                        && safety.contains("private record RescueStepAdmission(AIPlayerEntity bot, ActionPack.StepLease lease,")
                        && safety.contains("boolean hiddenWorldScan, BlockPos origin, BlockPos destination)"),
                "NavSafetyNet must latch exact ActionPack ownership as well as capability provenance and the physical corridor of its rescue step");
        int exactLease = inFlight.indexOf("if (!pack.stepInFlightFor(admission.lease()))");
        int emergencyRelease = inFlight.indexOf("releaseRescueStep(bot, false);", exactLease);
        int capabilityMismatch = inFlight.indexOf("admission.hiddenWorldScan() != hiddenWaterScan");
        int corridorMismatch = inFlight.indexOf("!withinRescueStepContinuationEnvelope(");
        int ownerCancel = inFlight.indexOf("releaseRescueStep(bot, true);", corridorMismatch);
        assertTrue(exactLease >= 0 && emergencyRelease > exactLease && capabilityMismatch > emergencyRelease
                        && corridorMismatch > capabilityMismatch && ownerCancel > corridorMismatch
                        && !inFlight.substring(exactLease, capabilityMismatch).contains("pack.cancelStep()")
                        && inFlight.contains("pack.activeStepKind()") && inFlight.contains("pack.activeStepTicks()"),
                "Nav rescue must recognize only its exact lease, leave an emergency-preempted successor running while it drops stale bookkeeping, and cancel its owned capability/corridor mismatch before it advances");
        String releaseRescue = body(safety, "private void releaseRescueStep(AIPlayerEntity bot, boolean cancel)",
                "/**\n     * Whether a rescue step is still running");
        assertTrue(releaseRescue.contains("rescueSteps.remove(bot.getUUID())")
                        && releaseRescue.contains("bot.getActionPack().cancelStep(admission.lease())")
                        && releaseRescue.contains("bot.getActionPack().releaseStepLease(admission.lease())"),
                "NavSafetyNet may release a retained strict fence only through the exact rescue admission lease");
        int guard = safety.indexOf("private static boolean canContinueRescueStep(");
        int guardDestination = safety.indexOf("!step.cell().equals(admission.destination())", guard);
        int guardCorridor = safety.indexOf("withinRescueStepContinuationEnvelope(bot.blockPosition(), admission, step.kind(), step.ticks())", guard);
        int continuationEnvelope = safety.indexOf("private static boolean withinRescueStepContinuationEnvelope(", guard);
        assertTrue(guard >= 0 && guardDestination > guard && guardCorridor > guardDestination && continuationEnvelope > guard
                        && safety.contains("withinRescueStepCorridor(feet, admission.origin(), admission.destination())")
                        && safety.contains("isNormalizableDryWalk(kind)")
                        && safety.contains("activeStepTicks >= 0 && activeStepTicks <= 1"),
                "the ActionPack continuation guard must bind its admitted destination and keep every rescue tick in its corridor, with only first-tick dry normalization at the source column");
        int beginStep = safety.indexOf("private boolean beginRescueStep(");
        int strictProof = safety.indexOf("observedWaterEscapeCell(bot, bot.level(), cell, false)", beginStep);
        int envelopeProof = safety.indexOf("SwimRoute.canObserveWalkedStepRefusalEnvelope(bot, cell, kind)", beginStep);
        int refusal = safety.indexOf("WalkedStep.refusal(bot, cell, kind)", beginStep);
        assertTrue(beginStep >= 0 && strictProof > beginStep && envelopeProof > strictProof && refusal > envelopeProof,
                "a strict cached rescue must prove its destination and full walked-step envelope before raw refusal checks");

        String deadlineSchedule = body(safety, "private void scheduleWaterRescueDeadline", "private boolean waterRescueDeadlineExceeded");
        String deadlineExceeded = body(safety, "private boolean waterRescueDeadlineExceeded", "private static WaterEscapeStep findPhysicalWaterEscape");
        assertTrue(safety.contains("private record WaterRescueDeadline(BlockPos feet, BlockPos shore, int routeCells,\n"
                        + "                                       boolean hiddenWorldScan, int deadlineTick)")
                        && deadlineSchedule.contains("existing.hiddenWorldScan() == hiddenWorldScan")
                        && deadlineSchedule.contains("hiddenWorldScan, now + waterRescueTimeoutTicks")
                        && deadlineExceeded.contains("deadline.hiddenWorldScan() == hiddenWorldScan"),
                "a water-rescue deadline must be keyed to its hidden-world capability so an expired operator route cannot teleport immediately after a strict round trip");

        int refusalEnvelope = route.indexOf("static boolean canObserveWalkedStepRefusalEnvelope(");
        int refusalEnvelopeEnd = route.indexOf("/** One empty or solid", refusalEnvelope);
        String envelopeProofSource = route.substring(refusalEnvelope, refusalEnvelopeEnd);
        assertTrue(refusalEnvelope >= 0
                        && envelopeProofSource.contains("dx != 0 && dz != 0")
                        && envelopeProofSource.contains("kind == WalkedStep.Kind.STEP_UP")
                        && envelopeProofSource.contains("kind == WalkedStep.Kind.STEP_DOWN || kind == WalkedStep.Kind.DROP"),
                "strict movement validation must observe diagonal corners, hop headroom, and all deep-drop sweep cells");
        assertFalse(envelopeProofSource.contains("getBlockState") || envelopeProofSource.contains("getFluidState"),
                "the strict refusal envelope must prove visibility without performing raw world reads itself");

        int neighbors = safety.indexOf("static List<BlockPos> waterEscapeNeighbors(BlockPos current)");
        int passable = safety.indexOf("static boolean passableWaterColumn", neighbors);
        String envelope = safety.substring(neighbors, passable);
        assertTrue(envelope.contains("for (int dy = -1; dy <= 1; dy++)")
                        && envelope.contains("for (int dx = -1; dx <= 1; dx++)")
                        && envelope.contains("for (int dz = -1; dz <= 1; dz++)")
                        && envelope.contains("dx != 0 || dy != 0 || dz != 0"),
                "water rescue must retain every legal adjacent diagonal stroke, not just cardinal cells");
    }

    @Test
    void guardedStepLeaseFencesForeignControllersAndDirectInputsUntilExactOwnerReconciles() throws IOException {
        String pack = read("action/ActionPack.java");

        String ordinaryRun = body(pack,
                "public StepLease runStep(WalkedStep next, WalkedStep.ContinuationGuard continuationGuard)",
                "/**\n     * Starts NavSafetyNet's own physical suffocation successor");
        String emergencyRun = body(pack,
                "public StepLease runEmergencyStep(WalkedStep next, WalkedStep.ContinuationGuard continuationGuard)",
                "private StepLease startStep(WalkedStep next, WalkedStep.ContinuationGuard continuationGuard,");
        String guardedRun = body(pack,
                "private StepLease startStep(WalkedStep next, WalkedStep.ContinuationGuard continuationGuard,",
                "/** True when no step is in flight.");
        int fence = guardedRun.indexOf("if (guardedStepLease != null)");
        int rejectReplacement = guardedRun.indexOf("return null;", fence);
        int claim = guardedRun.indexOf("claim(\"run_step\")", rejectReplacement);
        int capturedLease = guardedRun.indexOf("StepLease lease = new StepLease();", claim);
        int capturedGuard = guardedRun.indexOf("this.guardedStepLease = lease;", capturedLease);
        assertTrue(pack.contains("public static final class StepLease") && pack.contains("private StepLease()")
                        && ordinaryRun.contains("return startStep(next, continuationGuard, false);")
                        && emergencyRun.contains("return startStep(next, continuationGuard, true);")
                        && fence >= 0 && rejectReplacement > fence && claim > rejectReplacement
                        && capturedLease > claim && capturedGuard > capturedLease
                        && guardedRun.contains("continuationGuard != null || emergencyStep")
                        && guardedRun.contains("if (emergencyStep)")
                        && guardedRun.contains("this.emergencyStepLease = lease;"),
                "ordinary and Nav emergency guarded physical steps must receive opaque leases and reject every foreign replacement before they claim controller input");

        String genericCancel = body(pack, "public void cancelStep()",
                "/**\n     * Internal cancellation path");
        String exactCancel = body(pack, "public boolean cancelStep(StepLease lease)",
                "/** Ticks the step; true");
        String exactRelease = body(pack, "public boolean releaseStepLease(StepLease lease)",
                "/** How the last step");
        assertFalse(genericCancel.contains("guardedStepLease = null"),
                "a generic cancellation must preserve the ordinary strict fence instead of allowing an unguarded replacement before the owner sees it");
        assertTrue(exactCancel.contains("guardedStepLease != lease") && exactCancel.contains("guardedStepLease = null")
                        && exactRelease.contains("guardedStepLease != lease") && exactRelease.contains("guardedStepLease = null")
                        && genericCancel.indexOf("emergencyInputBlocked()") < genericCancel.indexOf("cancelStepUnchecked()")
                        && exactCancel.contains("emergencyStepLease = null")
                        && exactRelease.contains("emergencyStepLease = null")
                        && pack.contains("return lease != null && step != null && activeStepLease == lease;")
                        && pack.contains("return lease != null && completedStepLease == lease ? lastStepResult : null;"),
                "ordinary generic cancellation must remain intact, while only the exact owner lease may release/cancel a live Nav emergency fence or consume a natural step result");

        String emergencyPreempt = body(pack, "public boolean preemptGuardedStepForEmergency()",
                "private void tickWalkTo()");
        String safety = read("task/NavSafetyNet.java");
        int lava = safety.indexOf("if (inLava(world, feet) || inLava(world, feet.below()))");
        int lavaPreempt = safety.indexOf("bot.getActionPack().preemptGuardedStepForEmergency()", lava);
        int lavaEscape = safety.indexOf("escapeLava(bot, world, feet);", lavaPreempt);
        String suffocation = body(safety, "private boolean escapeSuffocation(",
                "/** The state of one bot's escape");
        int suffocationPreempt = suffocation.indexOf("bot.getActionPack().preemptGuardedStepForEmergency()");
        int suffocationRecovery = suffocation.indexOf("return escapeSuffocationByInputs(bot, world, feet);", suffocationPreempt);
        int preemptEmergencyMarker = emergencyPreempt.indexOf("emergencyStepLease = null");
        int preemptCancel = emergencyPreempt.indexOf("cancelStep();", preemptEmergencyMarker);
        assertEquals(Set.of("action/ActionPack.java", "task/NavSafetyNet.java"),
                matchingSources(Pattern.compile("preemptGuardedStepForEmergency\\s*\\(")).keySet(),
                "emergency lease preemption must stay whitelisted to ActionPack and NavSafetyNet");
        assertTrue(emergencyPreempt.contains("StepLease lease = guardedStepLease;")
                        && preemptEmergencyMarker >= 0 && preemptCancel > preemptEmergencyMarker
                        && emergencyPreempt.contains("guardedStepLease = null;")
                        && lava >= 0 && lavaPreempt > lava && lavaEscape > lavaPreempt
                        && suffocationPreempt >= 0 && suffocationRecovery > suffocationPreempt,
                "a reviewed later emergency must clear a live special marker before cancellation, and NavSafetyNet's lava and buried-suffocation paths must retain their explicit preemption handoffs");

        String controllerFence = body(pack, "private boolean controllerStartBlocked()",
                "/**\n     * Whatever Baritone");
        String directInputFence = body(pack, "private boolean nonzeroInputBlocked()",
                "/**\n     * Whatever Baritone");
        String emergencyInputFence = body(pack, "private boolean emergencyInputBlocked()",
                "/**\n     * Whatever Baritone");
        String walk = body(pack, "public ActionResult startWalkTo(Vec3 target, double arrivalThreshold)",
                "public ActionResult startDigPathTo(BlockPos goal)");
        String forward = body(pack, "public void setForward(float value)", "public void setStrafing(float value)");
        String strafing = body(pack, "public void setStrafing(float value)", "/**\n     * Sneak and sprint");
        String sneaking = body(pack, "public void setSneaking(boolean sneaking)", "public void setSprinting(boolean sprinting)");
        String sprinting = body(pack, "public void setSprinting(boolean sprinting)", "public void setJumping(boolean jumping)");
        String jumping = body(pack, "public void setJumping(boolean jumping)", "public void jumpOnce()");
        String jumpOnce = body(pack, "public void jumpOnce()", "// ==================== Pace");
        String stopMovement = body(pack, "public void stopMovement()",
                "/**\n     * Cancels the active path executor");
        String stopNavigation = body(pack, "public void stopNavigation()", "public void stopAll()");
        String stopAll = body(pack, "public void stopAll()", "public boolean hasActiveActions()");
        assertTrue(controllerFence.contains("return guardedStepLease != null;")
                        && directInputFence.contains("return guardedStepLease != null && !tickingGuardedStep;")
                        && emergencyInputFence.contains("activeStepLease == emergencyStepLease")
                        && emergencyInputFence.contains("!tickingGuardedStep")
                        && walk.indexOf("controllerStartBlocked()") < walk.indexOf("claim(\"walk_to\")")
                        && forward.indexOf("nonzeroInputBlocked()") < forward.indexOf("this.forward =")
                        && strafing.indexOf("nonzeroInputBlocked()") < strafing.indexOf("this.strafing =")
                        && sneaking.contains("nonzeroInputBlocked()") && sprinting.contains("nonzeroInputBlocked()")
                        && jumping.indexOf("nonzeroInputBlocked()") < jumping.indexOf("this.jumping =")
                        && jumpOnce.contains("nonzeroInputBlocked()")
                        && forward.contains("emergencyInputBlocked()") && strafing.contains("emergencyInputBlocked()")
                        && sneaking.contains("emergencyInputBlocked()") && sprinting.contains("emergencyInputBlocked()")
                        && jumping.contains("emergencyInputBlocked()") && jumpOnce.contains("emergencyInputBlocked()")
                        && stopMovement.contains("emergencyInputBlocked()")
                        && stopNavigation.contains("emergencyInputBlocked()") && stopAll.contains("emergencyInputBlocked()"),
                "the retained lease fence must reject foreign starts and nonzero input, while a live Nav emergency successor additionally ignores all generic stop/cancel and zero/false-input interference outside its own tick");
    }

    @Test
    void guardedStepLeaseAlsoFencesBaritoneBeforeAndAfterItsPreTick() throws IOException {
        String pack = read("action/ActionPack.java");
        String driver = read("baritone/BaritoneDriver.java");
        String goals = read("baritone/BaritoneGoals.java");
        String navigator = read("baritone/BaritoneNavigator.java");

        String baritoneFence = body(pack, "public boolean baritoneControlBlocked()",
                "/** Zero/false releases remain safe");
        assertTrue(baritoneFence.contains("return controllerStartBlocked();"),
                "the Baritone boundary must share the exact guarded-step fence, rather than introducing a weaker parallel ownership flag");
        assertEquals(Set.of("action/ActionPack.java", "baritone/BaritoneDriver.java", "baritone/BaritoneGoals.java",
                        "baritone/BaritoneNavigator.java"),
                matchingSources(Pattern.compile("baritoneControlBlocked\\s*\\(")).keySet(),
                "only ActionPack's reviewed Baritone driver, goal adapters, and direct navigator boundary may consult the guarded-step Baritone fence");

        String before = body(driver, "public static boolean beforePhysics(AIPlayerEntity bot)",
                "/**\n     * Cancels a direct or already-driven Baritone process");
        int firstFence = before.indexOf("if (guardedStepBlocksBaritone(bot, entry, baritone))");
        int refresh = before.indexOf("entry.context.refreshEntities()");
        int preTick = before.indexOf("baritone.getGameEventHandler().onTick(nextTick(EventState.PRE))");
        int postPreFence = before.indexOf("if (guardedStepBlocksBaritone(bot, entry, baritone))", preTick);
        int bridge = before.indexOf("BotInputBridge.apply(bot, baritone)");
        int bridgeFence = before.lastIndexOf("if (guardedStepBlocksBaritone(bot, entry, baritone))", bridge);
        assertTrue(firstFence >= 0 && refresh > firstFence && preTick > refresh
                        && postPreFence > preTick && bridge > postPreFence
                        && bridgeFence > postPreFence && bridge > bridgeFence,
                "the driver must fence a pre-existing process before PRE, recheck ownership after PRE, and make one final check before bridge inputs reach the bot");
        String blocker = body(driver, "private static boolean guardedStepBlocksBaritone(",
                "/**\n     * Runs steps 4-7");
        assertTrue(blocker.contains("bot.getActionPack().baritoneControlBlocked()")
                        && blocker.contains("entry.driven || busy(baritone)")
                        && blocker.contains("BaritoneRegistry.INSTANCE.preempt(bot, ActionPack.GUARDED_STEP_FENCE)")
                        && blocker.contains("return true;"),
                "a guarded lease must cancel an already-running Baritone process and block this tick without releasing the lease");

        String setGoal = body(goals, "public static Outcome setGoal(AIPlayerEntity bot, Goal goal)",
                "public static Outcome walkTo");
        int goalFence = setGoal.indexOf("if (bot.getActionPack().baritoneControlBlocked())");
        int goalRefusal = setGoal.indexOf("Outcome.refused(ActionPack.GUARDED_STEP_FENCE)", goalFence);
        int goalMutation = setGoal.indexOf("setGoalAndPath(goal)");
        assertTrue(goalFence >= 0 && goalRefusal > goalFence && goalMutation > goalRefusal,
                "direct Baritone setGoal admission must refuse a guarded lease before mutating the process");
        String mineAt = body(goals, "public static Outcome mineAt(AIPlayerEntity bot, BlockPos target)",
                "/** For callers that build a set of stand cells");
        int mineFence = mineAt.indexOf("if (bot.getActionPack().baritoneControlBlocked())");
        int minePolicy = mineAt.indexOf("BaritoneRegistry.INSTANCE.policy(bot)");
        int mineGoal = mineAt.indexOf("return setGoal(bot, new GoalBlock(target));");
        assertTrue(mineFence >= 0 && minePolicy > mineFence && mineGoal > minePolicy
                        && mineAt.contains("Outcome.refused(ActionPack.GUARDED_STEP_FENCE)"),
                "direct Baritone mine admission must likewise refuse the guarded lease before policy/world work or goal creation");

        String navigatorStart = body(navigator, "public static Admission start(AIPlayerEntity bot, NavRoute route, boolean admit)",
                "/** A start that was refused or failed");
        int navigatorFence = navigatorStart.indexOf("if (bot.getActionPack().baritoneControlBlocked())");
        int navigatorRefusal = navigatorStart.indexOf("Admission.refused(ActionPack.GUARDED_STEP_FENCE)", navigatorFence);
        int registry = navigatorStart.indexOf("BaritoneRegistry registry = BaritoneRegistry.INSTANCE;", navigatorRefusal);
        int policy = navigatorStart.indexOf("registry.setPolicy(bot, policyOf(options));", registry);
        int navigatorGoalMutation = navigatorStart.indexOf("setGoalAndPath(goal)", policy);
        assertTrue(navigatorFence >= 0 && navigatorRefusal > navigatorFence && registry > navigatorRefusal
                        && policy > registry && navigatorGoalMutation > policy,
                "the direct Baritone navigator start boundary must refuse a guarded step before registry lookup, policy mutation, or goal installation");
    }

    @Test
    void digDownAndDigNavMoveByWalkedStepsNeverByTeleportPrimitives() throws IOException {
        // R5: the stair descent, the horizontal advance and the return up the trail of DigDownTask, and the descent of DigNav, are
        // input-driven WalkedSteps whose landing is verified on a later tick; neither calls a teleport primitive.
        for (String file : new String[]{"task/DigDownTask.java", "action/DigNav.java"}) {
            String source = read(file);
            assertFalse(source.contains("FakePlayerMotion"), file + " must not use a FakePlayerMotion primitive");
            assertFalse(source.contains(".descendInto("), file + " must not use the teleporting descendInto");
            assertFalse(source.contains("teleportTo("), file + " must not teleport");
            assertTrue(source.contains("beginDescend(") || source.contains("WalkedStep"), file + " moves by walked steps");
        }
        String digDown = read("task/DigDownTask.java");
        int launch = digDown.indexOf("private boolean launchStep(");
        int settle = digDown.indexOf("private void settleStep(");
        int landing = digDown.indexOf("rememberDescentStep(bot.blockPosition())", settle);
        assertTrue(launch >= 0 && settle > launch && landing > settle,
                "the trail is extended from the verified landing in settleStep, never in the tick that launches a step");
        String launchBody = digDown.substring(launch, settle);
        assertFalse(launchBody.contains("rememberDescentStep(") || launchBody.contains("returnTrailIndex"),
                "launching a step changes neither the trail nor the return cursor");
    }

    @Test
    void descendToYMovesByWalkedStepsNeverByTeleportPrimitives() throws IOException {
        // R5: the stair steps, the flat landings, the climb-overs, the lateral detours, the retreat out of a reoccupied body cell and
        // the sneak-bridge lean of DescendToYTask are input-driven WalkedSteps whose landing is verified on a later tick.
        String descend = read("task/DescendToYTask.java");
        assertFalse(descend.contains("FakePlayerMotion"), "DescendToYTask must not use a FakePlayerMotion primitive");
        assertFalse(descend.contains(".descendInto("), "DescendToYTask must not use the teleporting descendInto");
        assertFalse(descend.contains("teleportTo("), "DescendToYTask must not teleport");
        assertTrue(descend.contains("beginDescend(") && descend.contains("InCellWalk.beginEdgeShift("),
                "DescendToYTask moves by walked steps");
        int launch = descend.indexOf("private boolean launchStep(");
        int settle = descend.indexOf("private void settleStep(");
        int pending = descend.indexOf("pendingLandingTarget = target;", settle);
        assertTrue(launch >= 0 && settle > launch && pending > settle,
                "the pending landing is recorded from the verified landing in settleStep, never in the tick that launches a step");
        String launchBody = descend.substring(launch, settle);
        assertFalse(launchBody.contains("pendingLanding") || launchBody.contains("traversedDetourEdges")
                        || launchBody.contains("lateralDetours"),
                "launching a step changes neither the landing history nor the detour debt");
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

    private static String body(String source, String start, String end) {
        int begin = source.indexOf(start);
        int finish = source.indexOf(end, begin);
        assertTrue(begin >= 0 && finish > begin, start);
        return source.substring(begin, finish);
    }

    private static int occurrences(String source, String needle) {
        return source.split(Pattern.quote(needle), -1).length - 1;
    }
}
