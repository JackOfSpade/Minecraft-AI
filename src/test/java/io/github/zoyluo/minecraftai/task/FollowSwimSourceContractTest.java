package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the safety-relevant structure of swim / dive / dig-out following: who owns the water when,
 * that expensive scans stay throttled, and that the dig-out stays inside the legal dig envelope.
 */
final class FollowSwimSourceContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    @Test
    void followYieldsToTheDrowningRescueBelowItsThresholdAndNeverMovesThen() throws IOException {
        String swim = read("task/FollowSwimming.java");
        String oxygen = read("task/FollowOxygen.java");
        String safety = read("task/NavSafetyNet.java");

        assertTrue(oxygen.contains("RESCUE_AIR = NavSafetyNet.AIR_SURFACE_THRESHOLD"),
                "the shallow-water oxygen floor must still be the safety net's historic floor");
        assertTrue(oxygen.contains("SURFACE_FLOOR_AIR = RESCUE_AIR +"),
                "follow must always start its ascent before the rescue has to");
        int predicate = safety.indexOf("static boolean followSwimMustYield(AIPlayerEntity bot)");
        assertTrue(predicate >= 0);
        String predicateBody = safety.substring(predicate, safety.indexOf("    /**", predicate + 1));
        assertTrue(predicateBody.contains("bot.getAirSupply() <= surfaceAirThreshold(bot)"),
                "Follow and NavSafetyNet must share the depth-aware rescue threshold");
        int yieldPredicate = swim.indexOf("private static boolean mustYieldToWaterRescue(AIPlayerEntity bot)");
        assertTrue(yieldPredicate >= 0);
        String yieldPredicateBody = swim.substring(yieldPredicate, swim.indexOf("    /**", yieldPredicate + 1));
        assertTrue(yieldPredicateBody.contains("NavSafetyNet.INSTANCE.rescueStepOwnsMovement(bot)"),
                "Follow must preserve Nav's live rescue stroke even after the swimmer reaches the surface");
        assertTrue(count(swim, "mustYieldToWaterRescue(bot)") == 3,
                "swim-after, water exit, and an in-flight step must all yield at the same boundary");
        String swimAfter = body(swim, "private boolean swimAfter", "private boolean ascendWhileSubmerged");
        String exit = body(swim, "boolean exitWaterForLand", "// ---- entering the water");
        String heldStep = body(swim, "private boolean holdStep", "private void clearRoute");
        assertYieldBeforeRenew(swimAfter, "swim-after");
        assertYieldBeforeRenew(exit, "water exit");
        assertYieldBeforeRenew(heldStep, "in-flight step");
        int yield = swimAfter.indexOf("if (mustYieldToWaterRescue(bot))");
        int renew = swimAfter.indexOf("NavSafetyNet.INSTANCE.renewFollowSwim(bot)");
        String yieldBlock = swimAfter.substring(yield, renew);
        assertTrue(yieldBlock.contains("yieldMovementToWaterRescue(bot)"),
                "the low-air yield must delegate its exact ownership handoff to the shared helper");
        String yieldHelper = body(swim, "private static void yieldMovementToWaterRescue(AIPlayerEntity bot)",
                "private void clearRoute");
        int clearFollowLease = yieldHelper.indexOf("NavSafetyNet.INSTANCE.clearFollowSwim(bot)");
        int rescueOwner = yieldHelper.indexOf("if (!NavSafetyNet.INSTANCE.rescueStepOwnsMovement(bot))");
        int stopMovement = yieldHelper.indexOf("bot.getActionPack().stopMovement()");
        assertTrue(clearFollowLease >= 0 && rescueOwner > clearFollowLease && stopMovement > rescueOwner,
                "the yield helper must drop Follow's lease, but zero inputs only when Nav does not own a live rescue stroke");
        assertFalse(yieldBlock.contains("swimStepTo") || yieldBlock.contains("stepAlongRoute")
                || yieldBlock.contains("ascendStep"),
                "the yield block must not move the bot");
    }

    @Test
    void followAndRescueLeasesReleaseTheirExactOwnersAtLifecycleBoundaries() throws IOException {
        String safety = read("task/NavSafetyNet.java");
        String follow = read("task/FollowTask.java");
        String swim = read("task/FollowSwimming.java");

        String renew = body(safety, "void renewFollowSwim(AIPlayerEntity bot)",
                "/** Clears the narrow FollowTask swim lease");
        int releaseRescue = renew.indexOf("releaseRescueStep(bot, true);");
        int publishFollowLease = renew.indexOf("followSwimLeaseUntil.put");
        assertTrue(releaseRescue >= 0 && publishFollowLease > releaseRescue,
                "renewing Follow's high-air lease must cancel and release an old exact rescue lease before publishing Follow ownership");
        assertTrue(renew.contains("strictAutomaticWaterRescueSessions.remove(bot.getUUID())"),
                "renewing Follow's high-air lease must clear an automatic strict rescue session before it can re-latch");
        String renewBaritone = body(safety, "public void renewBaritoneWater(AIPlayerEntity bot)",
                "/** Ends the Baritone water lease");
        assertTrue(renewBaritone.contains("strictAutomaticWaterRescueSessions.remove(bot.getUUID())"),
                "renewing Baritone's high-air lease must clear an automatic strict rescue session before expiry can revive it");

        String targetMissing = body(follow, "if (target == null || target.level() != bot.level())",
                "lastTarget = target;");
        int cancelFollow = targetMissing.indexOf("swimming.cancelStep(bot);");
        int stopActions = targetMissing.indexOf("stopBoatAndActions(bot);");
        assertTrue(cancelFollow >= 0 && stopActions > cancelFollow,
                "a missing or cross-dimension target must reconcile Follow's exact guarded lease before generic action shutdown");

        String cancelOwnedStep = body(swim, "void cancelStep(AIPlayerEntity bot)",
                "/** Must run before any path");
        assertTrue(cancelOwnedStep.contains("bot.getActionPack().cancelStep(stepLease);")
                        && !cancelOwnedStep.contains("bot.getActionPack().cancelStep();"),
                "Follow's lifecycle cleanup must target its own lease and never generically cancel a successor");
    }

    @Test
    void oxygenDecisionUsesTheMeasuredLossAndEffectsRatherThanHardCodedPotions() throws IOException {
        String swim = read("task/FollowSwimming.java");
        assertTrue(swim.contains("loss.observe(bot.getAirSupply(), bot.isUnderWater()"));
        assertTrue(swim.contains("private static boolean shouldSurface(int air, double rate, AirPlan airPlan)")
                        && swim.contains("FollowOxygen.shouldSurface(air, rate, airPlan.blocksToAir())"),
                "oxygen must use the measured loss and a resolved physical air distance");
        assertTrue(swim.contains("FollowOxygen.mayResumeDive("));
        assertTrue(swim.contains("MobEffects.WATER_BREATHING") && swim.contains("MobEffects.CONDUIT_POWER"));
    }

    @Test
    void waterEdgeAndRouteScansAreThrottledAndReused() throws IOException {
        String swim = read("task/FollowSwimming.java");
        assertTrue(swim.contains("ENTRY_SCAN_COOLDOWN_TICKS = 20"));
        assertTrue(swim.contains("elapsed < nextEntryScanTick && query.equals(entryCooldownQuery)"),
                "only a completed water-edge miss for the exact current entry query may be throttled");
        assertTrue(swim.contains("&& entryStillValid(bot, world, entry, hiddenWorldScan)"),
                "the last edge is reused only after its water and dry landing are re-proved");
        assertTrue(swim.contains("elapsed < nextRouteSearchTick && query.equals(routeCooldownQuery)"),
                "only a completed empty search for the exact same route query may be throttled");
        assertTrue(swim.contains("private record RouteSearchQuery(BlockPos start, BlockPos target, SwimRoute.Goal goal,")
                        && swim.contains("boolean hiddenWorldScan, double standoff, int observationRadius)"),
                "the route cooldown key must include feet, target, goal, capability, standoff, and the live observation radius");
        assertTrue(swim.contains("private int routeObservationRadius;")
                        && swim.contains("routeObservationRadius = observationRadius;")
                        && swim.contains("routeObservationRadius != SwimRoute.observationRadius(bot)"),
                "a published strict route must remember the radius that proved it and be discarded when the live view shrinks or changes");
        String publishedRoute = body(swim, "private boolean stepAlongRoute", "// ---- walked steps");
        int radiusMismatch = publishedRoute.indexOf("routeObservationRadius != SwimRoute.observationRadius(bot)");
        int clearPublished = publishedRoute.indexOf("clearRoute();", radiusMismatch);
        int immediateRetry = publishedRoute.indexOf("nextRouteSearchTick = 0;", clearPublished);
        assertTrue(radiusMismatch >= 0 && clearPublished > radiusMismatch && immediateRetry > clearPublished,
                "a radius-invalid published route must clear and re-search immediately instead of using a stale visibility proof");
        assertTrue(swim.contains("SwimRoute.observedCell(bot, world, current, hiddenWorldScan)")
                        && swim.contains("SwimRoute.observedCell(bot, world, entry.water(), hiddenWorldScan)")
                        && !swim.contains("BoatSupport.canObserveWater(bot,"),
                "entry discovery and reuse must retain their transparent-water cell proof rather than "
                        + "rejecting it with a water-opaque lake-surface ray");
    }

    @Test
    void strictFollowWaterScansProveCandidatesBeforeReadingThem() throws IOException {
        String swim = read("task/FollowSwimming.java");
        String route = read("task/SwimRoute.java");

        int classifier = route.indexOf("static CellProbe probeCell(");
        int columnProof = route.indexOf("canObserveColumn(bot, candidate)", classifier);
        int feetRead = route.indexOf("BlockState feet = world.getBlockState(candidate)", classifier);
        int headRead = route.indexOf("BlockState head = world.getBlockState(candidate.above())", classifier);
        int supportProof = route.indexOf("ObservableWorldQuery.canObserveCellThroughFluids(bot, candidate.below())", classifier);
        int freshStandability = route.indexOf("Standability.isStandableFresh(world, candidate)", classifier);
        int visibleInvalid = route.indexOf("CellProbe.valid(Cell.DRY) : CellProbe.invalid()", freshStandability);
        assertTrue(classifier >= 0 && columnProof > classifier && feetRead > columnProof && headRead > feetRead
                        && supportProof > headRead && freshStandability > supportProof && visibleInvalid > freshStandability,
                "a strict swim route must observe feet/head before state reads and a visible support cell before fresh standability, so an exposed empty floor is terminal invalid rather than UNKNOWN");
        int column = route.indexOf("static boolean canObserveColumn(");
        assertTrue(column >= 0
                        && route.indexOf("ObservableWorldQuery.canObserveCellThroughFluids(bot, candidate)", column) > column
                        && route.indexOf("ObservableWorldQuery.canObserveCellThroughFluids(bot, candidate.above())", column) > column,
                "water-route observation must prove both body cells through the water medium without seeing through solid terrain");
        String search = body(route, "static Optional<List<BlockPos>> search(AIPlayerEntity", "/** Whether this follow-water operation");
        assertTrue(search.contains("hiddenWorldScanAllowed(bot)")
                        && search.contains("probeCell(bot, world, candidate, hiddenWorldScan)"),
                "follow routing must choose its capability before it expands candidates through the bot-aware classifier");
        assertFalse(search.contains("NavSafetyNet.passableWaterColumn"),
                "strict follow routing must not retain the raw water-column prefilter");

        String air = body(swim, "private AirPlan blocksToAir", "/** One step toward air");
        assertTrue(air.indexOf("SwimRoute.canObserveColumn(bot, cell)")
                        < air.indexOf("world.getFluidState(cell)"),
                "an upward oxygen probe must observe a cell before reading its fluid");

        String entry = body(swim, "private Entry currentEntry", "/** Re-proves a cached water edge");
        assertTrue(entry.indexOf("SwimRoute.observedCell(bot, world, current, hiddenWorldScan)")
                        < entry.indexOf("world.getFluidState(current)"),
                "the entry scan must classify an observed water cell before its entry-specific fluid read");
        assertTrue(entry.contains("int observationRadius = SwimRoute.observationRadius(bot)")
                        && entry.contains("Math.min(ENTRY_RADIUS, observationRadius)")
                        && entry.contains("withinObservationRange"),
                "strict edge discovery must use the configured live observation radius before it scans candidates");
        assertFalse(entry.contains("STRICT_ENTRY_CANDIDATES") || entry.contains("STRICT_OBSERVATION_RADIUS"),
                "strict edge discovery must not restore a separate six-block/candidate-count policy");
        assertFalse(entry.contains("Standability.clearCache()"),
                "edge discovery must use fresh local checks instead of evicting the shared cache");
        String cachedEntry = body(swim, "private boolean entryStillValid", "private void markBad");
        assertTrue(cachedEntry.indexOf("SwimRoute.observedCell")
                        < cachedEntry.indexOf("world.getFluidState(entry.water())"),
                "a cached edge must be observed again before its water state is reused");

        String routeSearch = body(swim, "private SwimRoute.SearchStatus searchRoute", "private boolean stepAlongRoute");
        assertTrue(routeSearch.contains("SwimRoute.startSearch")
                        && routeSearch.contains("advanceLateralSearch(bot, world, routeSearch, hiddenWorldScan)")
                        && !routeSearch.contains("routeSearch.advance(bot, world"),
                "all FollowSwimming routes must use the shared-ledger resumable planner instead of spending an unaccounted direct slice");
        assertFalse(routeSearch.contains("Standability.clearCache()"),
                "route searches must not clear the shared standability cache");
        String begin = body(swim, "private boolean beginStep", "private boolean stepInFlight");
        int destination = begin.indexOf("SwimRoute.observedCell");
        int envelope = begin.indexOf("SwimRoute.canObserveWalkedStepRefusalEnvelope");
        int refusal = begin.indexOf("WalkedStep.refusal");
        int strictEnvelopeGate = begin.lastIndexOf("!hiddenWorldScan", envelope);
        assertTrue(destination >= 0 && envelope > destination && refusal > envelope,
                "a strict walked step must prove its destination and full refusal envelope before raw physical validation");
        assertTrue(strictEnvelopeGate > destination && strictEnvelopeGate < envelope,
                "only strict survival must require the visible refusal-envelope proof");
        String held = body(swim, "private boolean stepInFlight", "private boolean stepHoldsTheTick");
        assertTrue(swim.contains("private record StepAdmission(boolean hiddenWorldScan, BlockPos origin, BlockPos destination)"),
                "FollowSwimming must retain the capability and physical source-to-destination admission of every owned step");
        int exactLease = held.indexOf("if (pack.stepInFlightFor(lease))");
        int capabilityMismatch = held.indexOf("admission.hiddenWorldScan() != SwimRoute.hiddenWorldScanAllowed(bot)");
        int corridorMismatch = held.indexOf("!withinStepContinuationEnvelope(", capabilityMismatch);
        int cancel = held.indexOf("cancelStep(bot)", corridorMismatch);
        int emergencySuccessor = held.indexOf("boolean emergencySuccessorActive = !pack.stepIdle();", cancel);
        int emergencyLatch = held.indexOf("awaitingEmergencySuccessor = true;", emergencySuccessor);
        int result = held.indexOf("pack.stepResultFor(lease)", emergencySuccessor);
        int release = held.indexOf("pack.releaseStepLease(lease)", result);
        String holdsTick = body(swim, "private boolean stepHoldsTheTick",
                "/**\n     * One tick with a step in flight");
        int successorGate = holdsTick.indexOf("if (emergencySuccessorHoldsTheTick(bot))");
        int ownStep = holdsTick.indexOf("if (!stepInFlight(bot))", successorGate);
        assertTrue(exactLease >= 0 && capabilityMismatch > exactLease && corridorMismatch > capabilityMismatch && cancel > corridorMismatch
                        && emergencySuccessor > cancel && emergencyLatch > emergencySuccessor
                        && result > emergencySuccessor && release > result
                        && !held.substring(emergencySuccessor, release).contains("pack.cancelStep()")
                        && successorGate >= 0 && ownStep > successorGate
                        && holdsTick.indexOf("return emergencySuccessorHoldsTheTick(bot);", ownStep) > ownStep
                        && held.contains("pack.activeStepKind()") && held.contains("pack.activeStepTicks()"),
                "Follow must reconcile only its exact lease, latch an emergency-preempted successor before any ordinary planning, and consume an outcome only for its own natural completion");
        int invalidated = held.indexOf("\"continuation_guard\".equals(result.reason())", result);
        int forgetEntry = held.indexOf("entry = null;", invalidated);
        int markBad = held.indexOf("markBad(stepEdge)", invalidated);
        assertTrue(invalidated > result && forgetEntry > invalidated && markBad > forgetEntry,
                "a continuation-guard invalidation must forget/re-prove an entry instead of poisoning its shore as failed terrain");
        String approach = body(swim, "private boolean approachOnLand", "private Entry currentEntry");
        assertTrue(approach.indexOf("!SwimRoute.hiddenWorldScanAllowed(bot)")
                        < approach.indexOf("Standability.findNearestStandable"),
                "strict land approach must reject an unseen standability scan before it begins");
        String footing = body(swim, "static boolean hasDryFootingWithinOneStep", "static boolean isSwimCell");
        assertTrue(footing.contains("SwimRoute.observedCell"),
                "a shallow-water exit must not infer a hidden dry neighbour");
    }

    @Test
    void strictLandApproachAndWalkedStepValidationStayLocalAndObservable() throws IOException {
        String swim = read("task/FollowSwimming.java");
        String route = read("task/SwimRoute.java");
        String safety = read("task/NavSafetyNet.java");

        String enter = body(swim, "private boolean enterWater", "private boolean approachOnLand");
        int strictEnter = enter.indexOf("if (!SwimRoute.hiddenWorldScanAllowed(bot))");
        int observedEnter = enter.indexOf("beginObservedLandApproachStep(bot, edge.shore())", strictEnter);
        int rawPath = enter.indexOf("startPathTo(edge.shore())", strictEnter);
        assertTrue(strictEnter >= 0 && observedEnter > strictEnter && rawPath > observedEnter,
                "a visible distant water edge must take an observed local step before an operator-only path planner");

        String approach = body(swim, "private boolean approachOnLand", "private Entry currentEntry");
        int strictApproach = approach.indexOf("if (!SwimRoute.hiddenWorldScanAllowed(bot))");
        int observedApproach = approach.indexOf("beginObservedLandApproachStep(bot, target.blockPosition())", strictApproach);
        int remoteStandability = approach.indexOf("Standability.findNearestStandable", strictApproach);
        assertTrue(strictApproach >= 0 && observedApproach > strictApproach && remoteStandability > observedApproach,
                "strict no-edge follow must use an observed local step before any operator standability search");

        String local = body(swim, "private boolean beginObservedLandApproachStep", "private Entry currentEntry");
        assertTrue(local.contains("for (int dy = -3; dy <= 1; dy++)")
                        && local.contains("SwimRoute.observedCell(bot, world, candidate, false)")
                        && local.contains("follow_swim_observed_land_approach"),
                "strict land approach must enumerate the complete local walked-step envelope and start only observed dry steps");

        int helper = route.indexOf("static boolean canObserveWalkedStepRefusalEnvelope(");
        int swimReturn = route.indexOf("if (kind == WalkedStep.Kind.SWIM)", helper);
        int diagonal = route.indexOf("dx != 0 && dz != 0", helper);
        int stepUp = route.indexOf("kind == WalkedStep.Kind.STEP_UP", helper);
        int drop = route.indexOf("kind == WalkedStep.Kind.STEP_DOWN || kind == WalkedStep.Kind.DROP", helper);
        int helperEnd = route.indexOf("/** One empty or solid", helper);
        assertTrue(helper >= 0 && swimReturn > helper && diagonal > swimReturn && stepUp > diagonal
                        && drop > stepUp && helperEnd > drop,
                "the strict refusal helper must prove diagonal corners, hop headroom, and every drop-sweep cell");
        assertFalse(route.substring(helper, helperEnd).contains("getBlockState")
                        || route.substring(helper, helperEnd).contains("getFluidState"),
                "the refusal-envelope helper must be observation-only, never a hidden raw read");

        String rescue = body(safety, "private boolean beginRescueStep", "/**\n     * Strict-survival fallback");
        int rescueDestination = rescue.indexOf("observedWaterEscapeCell");
        int rescueEnvelope = rescue.indexOf("SwimRoute.canObserveWalkedStepRefusalEnvelope");
        int rescueRefusal = rescue.indexOf("WalkedStep.refusal");
        assertTrue(rescueDestination >= 0 && rescueEnvelope > rescueDestination && rescueRefusal > rescueEnvelope,
                "strict NavSafetyNet rescue must prove its complete refusal envelope before raw validation");
    }

    @Test
    void waterOwnersInstallPreOwnerContinuationGuardsBeforeAnyRawStepValidation() throws IOException {
        String pack = read("action/ActionPack.java");
        String step = read("action/WalkedStep.java");
        String swim = read("task/FollowSwimming.java");
        String safety = read("task/NavSafetyNet.java");

        String ordinaryRun = body(pack,
                "public StepLease runStep(WalkedStep next, WalkedStep.ContinuationGuard continuationGuard)",
                "/**\n     * Starts NavSafetyNet's own physical suffocation successor");
        String emergencyRun = body(pack,
                "public StepLease runEmergencyStep(WalkedStep next, WalkedStep.ContinuationGuard continuationGuard)",
                "private StepLease startStep(WalkedStep next, WalkedStep.ContinuationGuard continuationGuard,");
        String startStep = body(pack,
                "private StepLease startStep(WalkedStep next, WalkedStep.ContinuationGuard continuationGuard,",
                "/** True when no step is in flight.");
        int installed = startStep.indexOf("next.setContinuationGuard(continuationGuard);");
        int guardedFence = startStep.indexOf("if (guardedStepLease != null)");
        int deniedReplacement = startStep.indexOf("return null;", guardedFence);
        int claim = startStep.indexOf("claim(\"run_step\")", deniedReplacement);
        int leaseCreated = startStep.indexOf("StepLease lease = new StepLease();", claim);
        int guardCaptured = startStep.indexOf("this.guardedStepLease = lease;", leaseCreated);
        assertTrue(pack.contains("public static final class StepLease") && pack.contains("private StepLease()")
                        && ordinaryRun.contains("return startStep(next, continuationGuard, false);")
                        && emergencyRun.contains("return startStep(next, continuationGuard, true);")
                        && guardedFence >= 0 && deniedReplacement > guardedFence
                        && claim > deniedReplacement && installed > claim && leaseCreated > installed && guardCaptured > leaseCreated,
                "ActionPack must create an opaque exact lease, reject replacement before claiming input, and capture the guarded lease for both ordinary continuation and Nav emergency steps");

        String genericCancel = body(pack, "public void cancelStep()", "/**\n     * Cancels and releases only the strict step");
        String exactCancel = body(pack, "public boolean cancelStep(StepLease lease)", "/** Ticks the step");
        String releaseLease = body(pack, "public boolean releaseStepLease(StepLease lease)", "/** How the last step");
        assertFalse(genericCancel.contains("guardedStepLease = null"),
                "a generic cancellation must retain the guarded fence until its exact owner reconciles it");
        assertTrue(exactCancel.contains("guardedStepLease != lease") && exactCancel.contains("guardedStepLease = null")
                        && releaseLease.contains("guardedStepLease != lease") && releaseLease.contains("guardedStepLease = null")
                        && pack.contains("public boolean stepInFlightFor(StepLease lease)")
                        && pack.contains("public WalkedStep.Result stepResultFor(StepLease lease)"),
                "only the exact owner lease may cancel or release the fence and consume its result");

        String controllerFence = body(pack, "private boolean controllerStartBlocked()", "/**\n     * Whatever Baritone");
        String directInputFence = body(pack, "private boolean nonzeroInputBlocked()", "/**\n     * Whatever Baritone");
        String forward = body(pack, "public void setForward(float value)", "public void setStrafing(float value)");
        String strafing = body(pack, "public void setStrafing(float value)", "/**\n     * Sneak and sprint");
        String sneaking = body(pack, "public void setSneaking(boolean sneaking)", "public void setSprinting(boolean sprinting)");
        String sprinting = body(pack, "public void setSprinting(boolean sprinting)", "public void setJumping(boolean jumping)");
        String jumping = body(pack, "public void setJumping(boolean jumping)", "public void jumpOnce()");
        String jumpOnce = body(pack, "public void jumpOnce()", "// ==================== Pace");
        assertTrue(controllerFence.contains("return guardedStepLease != null;")
                        && directInputFence.contains("return guardedStepLease != null && !tickingGuardedStep;")
                        && forward.indexOf("nonzeroInputBlocked()") < forward.indexOf("this.forward =")
                        && strafing.indexOf("nonzeroInputBlocked()") < strafing.indexOf("this.strafing =")
                        && sneaking.contains("nonzeroInputBlocked()") && sprinting.contains("nonzeroInputBlocked()")
                        && jumping.indexOf("nonzeroInputBlocked()") < jumping.indexOf("this.jumping =")
                        && jumpOnce.contains("nonzeroInputBlocked()"),
                "the retained lease fence must block every nonzero direct input while still allowing the guarded step's own tick and zero-key release");
        int normalized = step.indexOf("normalizeWalkKindAtCurrentFeet()");
        int guard = step.indexOf("continuationGuard != null && !continuationGuard.allows(bot, this)", normalized);
        int guardFailure = step.indexOf("fail(\"continuation_guard\")", guard);
        int firstRawValidation = step.indexOf("startedWet = startsWet(bot)", guard);
        assertTrue(normalized >= 0 && guard > normalized && guardFailure > guard
                        && firstRawValidation > guardFailure,
                "WalkedStep must run the continuation guard after first-tick kind normalization but before raw terrain validation");

        String followBegin = body(swim, "private boolean beginStep", "private static boolean canContinueObservedStep");
        assertTrue(followBegin.contains("StepAdmission admission = new StepAdmission(hiddenWorldScan,")
                        && followBegin.contains("bot.blockPosition().immutable(), cell.immutable())")
                        && followBegin.contains("ActionPack.StepLease lease = bot.getActionPack().runStep(")
                        && followBegin.contains("canContinueObservedStep(guardBot, step, admission)")
                        && followBegin.contains("if (lease == null)") && followBegin.contains("stepLease = lease;"),
                "FollowSwimming must capture ActionPack's exact guarded lease together with every capability and physical-corridor proof");
        String followGuard = body(swim, "private static boolean canContinueObservedStep",
                "private static WalkedStep.Kind dryStepKind");
        int followDestination = followGuard.indexOf("!step.cell().equals(admission.destination())");
        int followProvenance = followGuard.indexOf("withinStepContinuationEnvelope(bot.blockPosition(), admission, step.kind(), step.ticks())");
        int followCapability = followGuard.indexOf("if (admission.hiddenWorldScan())");
        assertTrue(followDestination >= 0 && followProvenance > followDestination && followCapability > followProvenance
                        && followGuard.contains("SwimRoute.hiddenWorldScanAllowed(bot)")
                        && followGuard.contains("SwimRoute.observedCell(bot, bot.level(), step.cell(), false)")
                        && followGuard.contains("SwimRoute.canObserveWalkedStepRefusalEnvelope(bot, step.cell(), step.kind())"),
                "Follow's pre-owner guard must bind the exact admitted destination before corridor/capability checks and strict destination/envelope reproof");
        assertTrue(followGuard.contains("step.kind() == WalkedStep.Kind.SWIM ? !observed.isWater()")
                        && followGuard.contains("observed != SwimRoute.Cell.DRY"),
                "Follow's strict continuation guard must reject dry/water kind changes, not only total occlusion");
        String followEnvelope = body(swim, "private static boolean withinStepContinuationEnvelope",
                "private static boolean withinStepCorridor");
        assertTrue(followEnvelope.contains("withinStepCorridor(feet, admission.origin(), admission.destination())")
                        && followEnvelope.contains("kind == WalkedStep.Kind.SWIM")
                        && followEnvelope.contains("withinStepSwimSettlingEnvelope")
                        && followEnvelope.contains("isNormalizableDryWalk(kind)")
                        && followEnvelope.contains("activeStepTicks >= 0 && activeStepTicks <= 1")
                        && followEnvelope.contains("feet.getX() == origin.getX()")
                        && followEnvelope.contains("feet.getZ() == origin.getZ()"),
                "Follow's corridor must admit only its one-step box plus WalkedStep's first-tick dry normalization at the source column");

        String rescueBegin = body(safety, "private boolean beginRescueStep",
                "private static boolean canContinueRescueStep");
        assertTrue(rescueBegin.contains("RescueStepAdmission admission = new RescueStepAdmission(bot, null, hiddenWorldScan,")
                        && rescueBegin.contains("bot.blockPosition().immutable(), cell.immutable())")
                        && rescueBegin.contains("ActionPack.StepLease lease = bot.getActionPack().runStep(")
                        && rescueBegin.contains("canContinueRescueStep(guardBot, step, admission)")
                        && rescueBegin.contains("if (lease == null)")
                        && rescueBegin.contains("rescueSteps.put(bot.getUUID(), admission.withLease(lease));"),
                "NavSafetyNet must capture ActionPack's exact guarded lease together with every rescue capability and physical-corridor proof");
        String rescueGuard = body(safety, "private static boolean canContinueRescueStep",
                "private boolean beginObservableWaterRescueStep");
        int rescueDestination = rescueGuard.indexOf("!step.cell().equals(admission.destination())");
        int rescueProvenance = rescueGuard.indexOf("withinRescueStepContinuationEnvelope(bot.blockPosition(), admission, step.kind(), step.ticks())");
        int rescueCapability = rescueGuard.indexOf("if (admission.hiddenWorldScan())");
        assertTrue(rescueDestination >= 0 && rescueProvenance > rescueDestination && rescueCapability > rescueProvenance
                        && rescueGuard.contains("canUseHiddenWaterScan(bot)")
                        && rescueGuard.contains("observedWaterEscapeCell(bot, bot.level(), step.cell(), false)")
                        && rescueGuard.contains("SwimRoute.canObserveWalkedStepRefusalEnvelope(bot, step.cell(), step.kind())"),
                "NavSafetyNet's pre-owner guard must bind the exact admitted destination before corridor/capability checks and strict destination/envelope reproof");
        assertTrue(rescueGuard.contains("step.kind() == WalkedStep.Kind.SWIM ? observed != WaterEscapeCell.WATER")
                        && rescueGuard.contains("observed != WaterEscapeCell.DRY"),
                "NavSafetyNet's strict continuation guard must reject dry/water kind changes, not only total occlusion");
        assertTrue(rescueGuard.contains("if (observed == null) {")
                        && !rescueGuard.contains("probeWaterEscapeCell(bot, bot.level(), step.cell(), false).unknown()"),
                "late strict SWIM continuation must reject a newly unobservable target instead of retaining stale admission proof");
        String publicRescueOwnership = body(safety, "public boolean isWaterRescueActive(AIPlayerEntity bot)",
                "/**\n     * True only while NavSafetyNet still owns the exact live guarded stroke");
        assertTrue(publicRescueOwnership.contains("waterRescueShore.containsKey(id)")
                        && publicRescueOwnership.contains("strictAutomaticWaterRescueSessions.contains(id)")
                        && publicRescueOwnership.contains("rescueStepOwnsMovement(bot)"),
                "cross-task water ownership must remain visible throughout a strict automatic rescue, including the gap between guarded strokes");
        String rescueEnvelope = body(safety, "private static boolean withinRescueStepContinuationEnvelope",
                "private static boolean withinRescueStepSwimSettlingEnvelope");
        assertTrue(rescueEnvelope.contains("kind == WalkedStep.Kind.SWIM")
                        && rescueEnvelope.contains("admission.origin().getY() == admission.destination().getY()")
                        && rescueEnvelope.contains("withinRescueStepSwimSettlingEnvelope"),
                "the strict swim-settling allowance must be limited to a horizontal stroke, never widen a vertical rescue corridor");
    }

    @Test
    void suffocationEmergencySuccessorSurvivesForeignInterferenceUntilExactNavCleanup() throws IOException {
        String pack = read("action/ActionPack.java");
        String safety = read("task/NavSafetyNet.java");
        String swim = read("task/FollowSwimming.java");

        String emergencyFence = body(pack, "private boolean emergencyInputBlocked()",
                "/**\n     * Whatever Baritone");
        String genericCancel = body(pack, "public void cancelStep()",
                "/**\n     * Internal cancellation path");
        String stopMovement = body(pack, "public void stopMovement()", "public void stopNavigation()");
        String stopNavigation = body(pack, "public void stopNavigation()", "public void stopAll()");
        String stopAll = body(pack, "public void stopAll()", "public boolean hasActiveActions()");
        String forward = body(pack, "public void setForward(float value)", "public void setStrafing(float value)");
        String strafing = body(pack, "public void setStrafing(float value)", "/**\n     * Sneak and sprint");
        String sneaking = body(pack, "public void setSneaking(boolean sneaking)", "public void setSprinting(boolean sprinting)");
        String sprinting = body(pack, "public void setSprinting(boolean sprinting)", "public void setJumping(boolean jumping)");
        String jumping = body(pack, "public void setJumping(boolean jumping)", "public void jumpOnce()");
        assertTrue(emergencyFence.contains("activeStepLease == emergencyStepLease")
                        && emergencyFence.contains("!tickingGuardedStep")
                        && genericCancel.indexOf("emergencyInputBlocked()") < genericCancel.indexOf("cancelStepUnchecked()")
                        && stopMovement.contains("emergencyInputBlocked()")
                        && stopNavigation.contains("emergencyInputBlocked()")
                        && stopAll.contains("emergencyInputBlocked()")
                        && forward.contains("emergencyInputBlocked()") && strafing.contains("emergencyInputBlocked()")
                        && sneaking.contains("emergencyInputBlocked()") && sprinting.contains("emergencyInputBlocked()")
                        && jumping.contains("emergencyInputBlocked()"),
                "only a live Nav emergency lease must ignore foreign generic cancellation, stop calls, and direct zero/false input outside its own ActionPack tick");

        String emergencyRun = body(pack,
                "public StepLease runEmergencyStep(WalkedStep next, WalkedStep.ContinuationGuard continuationGuard)",
                "private StepLease startStep(WalkedStep next, WalkedStep.ContinuationGuard continuationGuard,");
        String startStep = body(pack,
                "private StepLease startStep(WalkedStep next, WalkedStep.ContinuationGuard continuationGuard,",
                "/** True when no step is in flight.");
        String exactCancel = body(pack, "public boolean cancelStep(StepLease lease)",
                "/**\n     * Explicit priority handoff");
        String exactRelease = body(pack, "public boolean releaseStepLease(StepLease lease)",
                "/** How the last step");
        String emergencyPreempt = body(pack, "public boolean preemptGuardedStepForEmergency()",
                "private void tickWalkTo()");
        int specialMarker = startStep.indexOf("this.emergencyStepLease = lease;");
        int exactMarkerClear = exactCancel.indexOf("emergencyStepLease = null");
        int preemptMarkerClear = emergencyPreempt.indexOf("emergencyStepLease = null");
        int preemptCancel = emergencyPreempt.indexOf("cancelStep();", preemptMarkerClear);
        assertTrue(emergencyRun.contains("return startStep(next, continuationGuard, true);")
                        && startStep.contains("continuationGuard != null || emergencyStep")
                        && specialMarker >= 0
                        && exactMarkerClear >= 0 && exactRelease.contains("emergencyStepLease = null")
                        && preemptMarkerClear >= 0 && preemptCancel > preemptMarkerClear,
                "the special immunity must be installed only by runEmergencyStep and removable only by its exact owner or a later emergency preemption");

        String escapeState = body(safety, "private static final class SuffocationEscape",
                "private final Map<UUID, SuffocationEscape> suffocationEscapes");
        String inFlight = body(safety, "private boolean suffocationStepInFlight",
                "private static boolean canContinueSuffocationStep");
        String beginEmergency = body(safety, "private boolean beginSuffocationEmergencyStep",
                "/** The bot is not (or no longer) inside a block");
        String releaseEmergency = body(safety, "private void releaseSuffocationEscape",
                "/**\n     * Getting out of a block with real inputs");
        String directEscape = body(safety, "boolean escapeSuffocationByInputs",
                "private boolean tickEscapeBreak");
        int directLive = directEscape.indexOf("if (suffocationStepInFlight(bot))");
        int directPreempt = directEscape.indexOf("preemptGuardedStepForEmergency()", directLive);
        assertTrue(escapeState.contains("ActionPack.StepLease stepLease;")
                        && inFlight.contains("stepInFlightFor(lease)")
                        && inFlight.contains("releaseStepLease(lease)")
                        && beginEmergency.contains("runEmergencyStep(step,")
                        && beginEmergency.contains("(guardBot, guardedStep) -> canContinueSuffocationStep(")
                        && beginEmergency.contains("state.stepOrigin = bot.blockPosition().immutable();")
                        && beginEmergency.contains("state.stepDestination = step.cell().immutable();")
                        && beginEmergency.contains("state.stepLease = lease;")
                        && beginEmergency.contains("awaitingEmergencySuccessors.add(bot.getUUID())")
                        && releaseEmergency.contains("cancelStep(state.stepLease)")
                        && directLive >= 0 && directPreempt > directLive
                        && directEscape.contains("beginSuffocationEmergencyStep(bot, state, WalkedStep.begin")
                        && directEscape.contains("beginSuffocationEmergencyStep(bot, state, walked)"),
                "a physical PUSH_OUT or adjacent suffocation successor must retain its exact Nav lease, never self-preempt on re-entry, and use exact release on completion or cancellation");

        String outerEscape = body(safety, "private boolean escapeSuffocation(",
                "/** The state of one bot's escape");
        int outerLive = outerEscape.indexOf("boolean physicalEscapeActive = suffocationStepInFlight(bot);");
        int buriedLava = outerEscape.indexOf("if (inLava(world, feet) || inLava(world, feet.below()))", outerLive);
        int buriedLavaPreempt = outerEscape.indexOf("preemptGuardedStepForEmergency()", buriedLava);
        int buriedLavaEscape = outerEscape.indexOf("escapeLava(bot, world, feet);", buriedLavaPreempt);
        int outerOwns = outerEscape.indexOf("if (physicalEscapeActive)", outerLive);
        int outerFallback = outerEscape.lastIndexOf("return escapeSuffocationByInputs(bot, world, feet);");
        String emergencyGate = body(safety, "private boolean emergencySuccessorOwnsMovement",
                "public boolean tickBot");
        String rescueInFlight = body(safety, "private boolean rescueStepInFlight",
                "/** Starts a walk to the middle");
        assertTrue(outerLive >= 0 && buriedLava > outerLive && buriedLavaPreempt > buriedLava
                        && buriedLavaEscape > buriedLavaPreempt && outerOwns > buriedLavaEscape
                        && outerFallback > outerOwns
                        && emergencyGate.contains("!bot.getActionPack().stepIdle()")
                        && emergencyGate.contains("awaitingEmergencySuccessors.remove(id)")
                        && rescueInFlight.indexOf("if (emergencySuccessorOwnsMovement(bot))")
                        < rescueInFlight.indexOf("RescueStepAdmission admission"),
                "a live physical successor must hold later safety planning until idle, except that a newly lethal buried lava cell explicitly preempts it before the physical-successor hold can return");

        String clear = body(safety, "public void clear(AIPlayerEntity bot)", "public void clearAll()");
        String clearAll = body(safety, "public void clearAll()", "/**\n     * Lets a task hand water recovery");
        String teleport = body(safety, "private boolean emergencyTeleportToAir",
                "// The nearest landing spot");
        int clearRelease = clear.indexOf("releaseSuffocationEscape(bot);");
        int clearRescue = clear.indexOf("releaseRescueStep(bot, true);");
        int clearAllRelease = clearAll.indexOf("releaseSuffocationEscape(escape.bot);");
        int clearAllDrop = clearAll.indexOf("suffocationEscapes.clear();", clearAllRelease);
        int teleportRelease = teleport.indexOf("releaseSuffocationEscape(bot);");
        int teleportStop = teleport.indexOf("bot.getActionPack().stopAll();", teleportRelease);
        int teleportMove = teleport.indexOf("bot.teleportTo(", teleportStop);
        assertTrue(clearRelease >= 0 && clearRescue > clearRelease
                        && clearAll.contains("for (SuffocationEscape escape : suffocationEscapes.values())")
                        && clearAllRelease >= 0 && clearAllDrop > clearAllRelease
                        && teleportRelease >= 0 && teleportStop > teleportRelease && teleportMove > teleportStop,
                "clear, clearAll, and privileged teleport lifecycle handoffs must exactly cancel the Nav successor before discarding its state or releasing movement");

        String followCancel = body(swim, "void cancelStep(AIPlayerEntity bot)",
                "/** Must run before any path");
        String followHold = body(swim, "private boolean stepHoldsTheTick",
                "/**\n     * One tick with a step in flight");
        assertTrue(followCancel.contains("cancelStep(stepLease)")
                        && !followCancel.contains("cancelStep();")
                        && followCancel.contains("awaitingEmergencySuccessor = true;")
                        && followHold.indexOf("emergencySuccessorHoldsTheTick(bot)")
                        < followHold.indexOf("stepInFlight(bot)"),
                "a stale Follow owner must release only its own lease and yield before it can plan over NavSafetyNet's live successor");
    }

    @Test
    void strictRouteAndRescueSearchesRetainPendingFrontiersInsteadOfReportingFalseEmpty() throws IOException {
        String route = read("task/SwimRoute.java");
        String swim = read("task/FollowSwimming.java");
        String safety = read("task/NavSafetyNet.java");

        String strictWork = body(swim, "private static final class StrictWaterWork",
                "/** Immutable physical/capability admission");
        String lateralAdvance = body(swim, "private SwimRoute.SearchResult advanceLateralSearch",
                "private enum AirColumnStatus");
        String observedExploration = body(swim, "private final class ObservedWaterExplorationProgress",
                "private boolean routeStepToward");
        assertTrue(swim.contains("private static final int UNKNOWN_WATER_EXPLORATION_RESERVE = 27;")
                        && swim.contains("private static final int OBSERVED_LAND_APPROACH_CANDIDATES = 5 * 3 * 3 - 1;")
                        && swim.contains("private static final int OBSERVED_LAND_APPROACH_WORK_RESERVE = OBSERVED_LAND_APPROACH_CANDIDATES * 2;")
                        && swim.contains("private final StrictWaterWork strictWaterWork = new StrictWaterWork();")
                        && strictWork.contains("remaining = SwimRoute.STRICT_SEARCH_CANDIDATES_PER_TICK;")
                        && strictWork.contains("verticalColumnClaimed") && strictWork.contains("lateralPlannerClaimed")
                        && strictWork.contains("int reserve = reserveObservedFallback ? UNKNOWN_WATER_EXPLORATION_RESERVE : 0;")
                        && strictWork.contains("private int claimEntrySearch(boolean reserveObservedLandApproach)")
                        && strictWork.contains("reserveObservedLandApproach ? OBSERVED_LAND_APPROACH_WORK_RESERVE : 0")
                        && strictWork.contains("return Math.max(0, remaining - reserve);"),
                "Follow strict water work must share one per-tick ledger, reserve the 26 observed-water slots plus admission before a lateral slice, reserve the complete dry approach envelope from entry scanning, and prevent a second column or lateral planner claim");
        String greedySwim = body(swim, "private boolean swimStepToward",
                "/**\n     * Continues local exploration");
        String chargedAdmission = body(swim,
                "private boolean beginStep(AIPlayerEntity bot, BlockPos cell, String reason, boolean chargeStrictAdmission)",
                "/**\n     * ActionPack invokes this before every in-flight step terrain read");
        int greedyReserve = greedySwim.indexOf("canAffordStrictWaterWork(hiddenWorldScan, nearer.size() * 2)");
        int greedyObserve = greedySwim.indexOf("consumeStrictWaterWork(hiddenWorldScan);", greedyReserve);
        int greedyStart = greedySwim.indexOf("beginStep(bot, candidate, \"follow_swim\")", greedyObserve);
        assertTrue(greedyReserve >= 0 && greedyObserve > greedyReserve && greedyStart > greedyObserve
                        && swim.contains("return beginStep(bot, cell, reason, true);")
                        && chargedAdmission.indexOf("!consumeStrictWaterWork(hiddenWorldScan)")
                        < chargedAdmission.indexOf("SwimRoute.observedCell"),
                "the greedy immediate swim list must reserve and charge every observation plus its possible admission before it starts a physical step");
        String waterExit = body(swim, "private boolean needsWaterExit",
                "/** A dry, standable cell one step away");
        String standaloneFooting = body(swim,
                "static boolean hasDryFootingWithinOneStep(AIPlayerEntity bot, BlockPos feet)",
                "/**\n     * Full 18-cell start envelope");
        String meteredFooting = body(swim,
                "private static boolean hasDryFootingWithinOneStep(AIPlayerEntity bot, BlockPos feet,",
                "static boolean isSwimCell");
        assertTrue(waterExit.contains("hasDryFootingWithinOneStep(bot, feet, strictWaterWork)")
                        && standaloneFooting.contains("return hasDryFootingWithinOneStep(bot, feet, null);")
                        && meteredFooting.contains("!strictWork.canAfford(18)")
                        && meteredFooting.contains("strictWork.spend(1);")
                        && meteredFooting.contains("SwimRoute.observedCell"),
                "Follow's 18-cell dry-footing predicate must use the shared strict ledger before observation while retaining its standalone two-argument behavior");
        assertTrue(count(swim, "beginStrictWaterWork(world);") >= 2
                        && lateralAdvance.contains("strictWaterWork.claimLateralPlanner(!hiddenWorldScan)")
                        && lateralAdvance.contains("result = search.advance(bot, world, 1);")
                        && lateralAdvance.contains("strictWaterWork.spend(1);")
                        && observedExploration.contains("private int nextNeighbor;")
                        && observedExploration.contains("private int nextCandidate;")
                        && observedExploration.contains("NavSafetyNet.waterEscapeNeighbors(query.feet())")
                        && swim.contains("private ObservedWaterExplorationProgress routeUnknownExploration;")
                        && swim.contains("private ObservedWaterExplorationProgress airUnknownExploration;")
                        && swim.contains("strictWaterWork.take(")
                        && swim.contains("SwimRoute.STRICT_SEARCH_CANDIDATES_PER_TICK), reason)")
                        && swim.contains("strictWaterWork.spend(advance.work());"),
                "Follow must charge vertical probes, one retained lateral SearchProgress, and retained route/air UNKNOWN exploration cursors to the same bounded ledger");

        String status = body(route, "enum SearchStatus", "record SearchResult");
        assertTrue(status.contains("PENDING") && status.contains("UNKNOWN") && status.contains("COOLDOWN")
                        && status.contains("FOUND") && status.contains("EMPTY"),
                "strict route search must distinguish active work, a viewpoint-blocked frontier, a query-keyed cooldown, and a completed empty route");
        String result = body(route, "record SearchResult", "static final class SearchProgress");
        assertTrue(result.contains("pendingResult()") && result.contains("new SearchResult(SearchStatus.PENDING, List.of())")
                        && result.contains("unknownResult()") && result.contains("new SearchResult(SearchStatus.UNKNOWN, List.of())")
                        && result.contains("emptyResult()") && result.contains("new SearchResult(SearchStatus.EMPTY, List.of())")
                        && result.contains("return status == SearchStatus.PENDING || status == SearchStatus.UNKNOWN;"),
                "PENDING/UNKNOWN and EMPTY must have separate results even though none owns a path yet, and COOLDOWN is not a retained-search result");
        String progress = body(route, "static final class SearchProgress", "static boolean hiddenWorldScanAllowed");
        assertTrue(progress.contains("ArrayDeque<BlockPos> queue")
                        && progress.contains("Map<BlockPos, BlockPos> parent")
                        && progress.contains("HashSet<BlockPos> visited")
                        && progress.contains("ArrayDeque<UnknownCandidate> unknowns")
                        && progress.contains("int observationEpoch")
                        && progress.contains("List<BlockPos> currentNeighbors")
                        && progress.contains("int nextNeighbor"),
                "a strict pending route must retain its queue, parents, visited/unknown sets, and partial neighbour cursor");
        assertTrue(progress.contains("while (work < workBudget)")
                        && progress.contains("return SearchResult.pendingResult()")
                        && progress.contains("if (hiddenWorldScan && nodes >= MAX_NODES)")
                        && progress.contains("return SearchResult.unknownResult()")
                        && progress.contains("rememberUnknown")
                        && progress.contains("observationEpoch++"),
                "a bounded strict slice must yield PENDING/UNKNOWN with retained unknown edges; only operator scans may use the historic node cutoff");
        int beginObservationTick = progress.indexOf("beginObservationTick(world.getServer().getTickCount())");
        int workLoop = progress.indexOf("while (work < workBudget)", beginObservationTick);
        assertTrue(beginObservationTick >= 0 && workLoop > beginObservationTick,
                "a retained strict route must reset its later-tick observation allowance before entering the same bounded work slice");
        String routeObservationTick = body(route, "private void beginObservationTick(int now)",
                "private boolean hasEligibleUnknown()");
        String routeEligibleUnknown = body(route, "private boolean hasEligibleUnknown()",
                "/** Performs exactly one dequeue/cursor/neighbour operation");
        String routeRetryUnknown = body(route, "private WaterAdmission retryOneUnknown(",
                "private WaterAdmission admitObservedCell");
        int routeStationaryCharge = routeRetryUnknown.indexOf("stationaryUnknownRetryUsed = true;");
        int routeProbe = routeRetryUnknown.indexOf("probeCell(bot, world, unknown.cell(), false)", routeStationaryCharge);
        assertTrue(progress.contains("int lastObservationTick")
                        && progress.contains("boolean stationaryUnknownRetryUsed")
                        && progress.contains("record UnknownCandidate(BlockPos cell, BlockPos parent, int observationEpoch, int observedTick)")
                        && progress.contains("new UnknownCandidate(unknown, parentCell.immutable(), observationEpoch,")
                        && progress.contains("lastObservationTick)")
                        && routeObservationTick.contains("if (lastObservationTick == now)")
                        && routeObservationTick.contains("lastObservationTick = now;")
                        && routeObservationTick.contains("stationaryUnknownRetryUsed = false;")
                        && !routeObservationTick.contains("observationEpoch++")
                        && routeEligibleUnknown.contains("unknown.observationEpoch() < observationEpoch")
                        && routeEligibleUnknown.contains("!stationaryUnknownRetryUsed && unknown.observedTick() < lastObservationTick")
                        && routeStationaryCharge >= 0 && routeProbe > routeStationaryCharge,
                "a stationary strict route may re-prove one retained stale ray on a later tick, while a real movement still releases the older epoch under the same bounded retry path");
        assertTrue(progress.contains("CellProbe probe = probeCell(bot, world, candidate, hiddenWorldScan)")
                        && progress.contains("if (!hiddenWorldScan && probe.unknown())")
                        && progress.contains("rememberUnknown(candidate, current)"),
                "only an unobserved strict route candidate may remain queued for a new viewpoint; visible invalid terrain is terminal");
        assertTrue(progress.contains("matchesContext") && progress.contains("this.observationRadius == currentObservationRadius")
                        && progress.contains("withinSearchBounds(start)")
                        && progress.contains("start.equals(lastFeet) || adjacent(lastFeet, start)"),
                "a retained strict route must invalidate only after an incompatible physical/capability observation context change");
        int admitFeet = progress.indexOf("private boolean admitCurrentFeet(BlockPos feet)");
        int updateFeet = progress.indexOf("lastFeet = currentFeet;", admitFeet);
        assertTrue(admitFeet >= 0 && updateFeet > admitFeet,
                "a real adjacent fallback landing must become the retained route's current physical feet");
        int exitGoal = progress.indexOf("case EXIT ->");
        int immediateExit = progress.indexOf("return new WaterAdmission(finishedPath(cell))", exitGoal);
        assertTrue(exitGoal >= 0 && immediateExit > exitGoal,
                "a proved strict EXIT shore must be actionable before exhaustive score refinement strands the swimmer");

        String followSearch = body(swim, "private SwimRoute.SearchStatus searchRoute", "private boolean stepAlongRoute");
        int advance = followSearch.indexOf("advanceLateralSearch(bot, world, routeSearch, hiddenWorldScan)");
        int cooperativeYield = followSearch.indexOf("if (result == null)", advance);
        int pending = followSearch.indexOf("if (result.waitingForViewpoint())", cooperativeYield);
        int clear = followSearch.indexOf("routeSearch = null;", pending);
        int cooldown = followSearch.indexOf("nextRouteSearchTick = elapsed + ROUTE_COOLDOWN_TICKS", clear);
        assertTrue(advance >= 0 && cooperativeYield > advance && pending > cooperativeYield && clear > pending && cooldown > clear,
                "FollowSwimming must preserve a cooperative ledger yield as PENDING, retain PENDING/UNKNOWN search state, and apply its cooldown only after a completed empty result");
        int liveFeet = followSearch.indexOf("BlockPos start = bot.blockPosition().immutable();");
        int observationRadius = followSearch.indexOf("int observationRadius = SwimRoute.observationRadius(bot);");
        int fullQuery = followSearch.indexOf("RouteSearchQuery query = new RouteSearchQuery(start, target.immutable(), goal, hiddenWorldScan, standoff,");
        int queryRadius = followSearch.indexOf("observationRadius);", fullQuery);
        assertTrue(liveFeet >= 0 && observationRadius > liveFeet && fullQuery > observationRadius && queryRadius > fullQuery
                        && followSearch.contains("RouteSearchQuery query = new RouteSearchQuery(start, target.immutable(), goal, hiddenWorldScan, standoff,")
                        && followSearch.contains("routeSearch = SwimRoute.startSearch(start, query.target(), goal, standoff, hiddenWorldScan)")
                        && followSearch.contains("routeSearchQuery = query;")
                        && followSearch.contains("RouteSearchQuery completedQuery = routeSearchQuery;")
                        && followSearch.contains("routeCooldownQuery = completedQuery;")
                        && followSearch.contains("return SwimRoute.SearchStatus.COOLDOWN;"),
                "a PENDING route keeps its original target snapshot, while only the exact completed query enters COOLDOWN");
        int staleNegative = followSearch.indexOf("if (!result.found() && !query.equals(completedQuery))");
        int restartCurrent = followSearch.indexOf("routeSearch = SwimRoute.startSearch(start, query.target(), goal, standoff, hiddenWorldScan)",
                staleNegative);
        int stalePending = followSearch.indexOf("return SwimRoute.SearchStatus.PENDING;", restartCurrent);
        int armCooldown = followSearch.indexOf("routeCooldownQuery = completedQuery;", stalePending);
        assertTrue(staleNegative >= 0 && restartCurrent > staleNegative && stalePending > restartCurrent
                        && armCooldown > stalePending,
                "a terminal negative result for an old retained target must restart the current query as PENDING before it can arm EMPTY/COOLDOWN");
        String currentQuery = body(swim, "private static RouteSearchQuery currentRouteSearchQuery",
                "private boolean stepAlongRoute");
        assertTrue(currentQuery.contains("bot.blockPosition().immutable()")
                        && currentQuery.contains("SwimRoute.observationRadius(bot)"),
                "a stale negative must be compared against the live feet and live perception radius after an observed fallback move");

        String entryScan = body(swim, "private Entry currentEntry", "private static final class EntrySearchProgress");
        int entryBudget = entryScan.indexOf("int entryBudget = hiddenWorldScan ? ENTRY_SCAN_WORK_PER_TICK");
        int entryClaim = entryScan.indexOf("strictWaterWork.claimEntrySearch(true)", entryBudget);
        int entryAdvance = entryScan.indexOf("entrySearch.advance(bot, world, badShores, entryBudget)", entryBudget);
        int entryPending = entryScan.indexOf("if (result.pending())", entryAdvance);
        int entryClear = entryScan.indexOf("entrySearch = null;", entryPending);
        int entryCooldown = entryScan.indexOf("nextEntryScanTick = elapsed + ENTRY_SCAN_COOLDOWN_TICKS", entryClear);
        assertTrue(entryScan.contains("EntrySearchProgress entrySearch")
                        || swim.contains("EntrySearchProgress entrySearch"),
                "FollowSwimming must retain a strict water-entry scan instead of restarting its local frontier every tick");
        assertTrue(entryBudget >= 0 && entryClaim > entryBudget && entryAdvance > entryClaim && entryPending > entryAdvance && entryClear > entryPending
                        && entryCooldown > entryClear,
                "a PENDING entry scan must resume its work next tick; strict entry work must leave the complete observed land-approach envelope available, and only a completed no-entry result receives the scan cooldown");
        assertTrue(swim.contains("private record EntrySearchQuery(BlockPos start, BlockPos target, boolean hiddenWorldScan,")
                        && swim.contains("int observationRadius)"),
                "a completed-negative entry scan must carry its feet, target, capability, and observation-radius provenance");
        int entryQuery = entryScan.indexOf("EntrySearchQuery query = new EntrySearchQuery(");
        int entryThrottle = entryScan.indexOf("elapsed < nextEntryScanTick && query.equals(entryCooldownQuery)", entryQuery);
        int completedEntryQuery = entryScan.indexOf("EntrySearchQuery completedQuery = entrySearchQuery;", entryThrottle);
        int staleEntry = entryScan.indexOf("if (!query.equals(completedQuery))", completedEntryQuery);
        int clearStaleEntryCooldown = entryScan.indexOf("entryCooldownQuery = null;", staleEntry);
        int armEntryCooldown = entryScan.indexOf("entryCooldownQuery = completedQuery;", clearStaleEntryCooldown);
        assertTrue(entryQuery >= 0 && entryThrottle > entryQuery && completedEntryQuery > entryThrottle
                        && staleEntry > completedEntryQuery && clearStaleEntryCooldown > staleEntry
                        && armEntryCooldown > clearStaleEntryCooldown,
                "only an exact completed EntrySearchQuery may cool down; a stale negative after movement, retarget, profile, or radius change restarts immediately");
        String entryProgress = body(swim, "private static final class EntrySearchProgress",
                "private record EntrySearchResult");
        assertTrue(entryProgress.contains("PriorityQueue<BlockPos> frontier")
                        && entryProgress.contains("Set<BlockPos> scheduled")
                        && entryProgress.contains("BlockPos current")
                        && entryProgress.contains("int nextExpansionDirection")
                        && entryProgress.contains("boolean waterChecked")
                        && entryProgress.contains("BlockPos water")
                        && entryProgress.contains("int nextShore"),
                "strict entry discovery must retain its frontier, scheduled cells, and partial water/shore cursors across slices");
        assertTrue(entryProgress.contains("while (work < workBudget)")
                        && entryProgress.contains("SwimRoute.observedCell(bot, world, current, hiddenWorldScan)")
                        && entryProgress.contains("matchesContext")
                        && !entryProgress.contains("ENTRY_CANDIDATES_CHECKED"),
                "entry search must use bounded resumable work with live observation proofs, not a hidden fixed candidate cutoff");
        String entryResult = body(swim, "private record EntrySearchResult", "/** Re-proves a cached water edge");
        assertTrue(entryResult.contains("boolean pending") && entryResult.contains("int work")
                        && entryResult.contains("new EntrySearchResult(null, true, work)")
                        && entryResult.contains("new EntrySearchResult(entry, false, work)"),
                "entry PENDING must remain a distinct incomplete result rather than a completed missing edge");

        String approachRoute = body(swim, "private boolean routeStepToward", "// ---- leaving the water");
        assertTrue(approachRoute.contains("status == SwimRoute.SearchStatus.UNKNOWN")
                        && approachRoute.contains("beginRouteObservedWaterExplorationStep")
                        && !approachRoute.contains("beginObservedWaterExplorationStep"),
                "ordinary PENDING route work must not start a speculative exploration stroke; only its retained UNKNOWN route cursor may do so");
        String exitRoute = body(swim, "boolean exitWaterForLand", "// ---- entering the water");
        int exitWait = exitRoute.indexOf("status == SwimRoute.SearchStatus.PENDING || status == SwimRoute.SearchStatus.UNKNOWN\n                    || status == SwimRoute.SearchStatus.COOLDOWN");
        int exitExplore = exitRoute.indexOf("status == SwimRoute.SearchStatus.UNKNOWN", exitWait);
        assertTrue(exitWait >= 0 && exitExplore > exitWait
                        && exitRoute.indexOf("beginRouteObservedWaterExplorationStep", exitExplore) > exitExplore,
                "a strict EXIT must wait during PENDING or exact-query COOLDOWN, but may advance its retained observed route cursor only after UNKNOWN");
        int exitQuery = exitRoute.indexOf("RouteSearchQuery exitQuery = currentRouteSearchQuery(");
        int failureThrottle = exitRoute.indexOf("elapsed < exitFailedUntilTick && exitQuery.equals(exitFailureQuery)", exitQuery);
        int clearStaleFailure = exitRoute.indexOf("if (!exitQuery.equals(exitFailureQuery))", failureThrottle);
        int armFailure = exitRoute.indexOf("exitFailureQuery = exitQuery;", clearStaleFailure);
        assertTrue(exitQuery >= 0 && failureThrottle > exitQuery && clearStaleFailure > failureThrottle
                        && armFailure > clearStaleFailure,
                "EXIT failure throttling must be keyed to the exact full RouteSearchQuery, never to a stale failed shore after feet, target, profile, standoff, or radius changes");

        String continuation = body(safety, "private WaterEscapeStep continueStrictWaterEscapeSearch",
                "private Optional<BlockPos> cachedFindNearestBreathableStandable");
        assertTrue(continuation.contains("strictWaterEscapeSearches.get(id)")
                        && continuation.contains("search.accepts(feet, observationRadius)")
                        && continuation.contains("search.admitCurrentFeet(feet)")
                        && continuation.contains("search.beginObservationTick(now)")
                        && continuation.contains("WaterEscapeStep escape = search.advance(bot, world, feet, STRICT_WATER_BFS_WORK_PER_TICK)")
                        && continuation.contains("if (escape != null || search.exhausted())"),
                "NavSafetyNet must retain a pending strict rescue session, re-rooting only after an incompatible movement/context change while reconsidering retained visibility misses on later ticks");
        int renewObservation = continuation.indexOf("search.beginObservationTick(now)");
        int boundedAdvance = continuation.indexOf("search.advance(bot, world, feet, STRICT_WATER_BFS_WORK_PER_TICK)");
        assertTrue(renewObservation >= 0 && boundedAdvance > renewObservation,
                "a later-tick UNKNOWN retry must enter the same bounded strict rescue slice before any route work runs");
        String waterCache = body(safety, "private WaterEscapeStep cachedFindPhysicalWaterEscape",
                "private WaterEscapeStep continueStrictWaterEscapeSearch");
        assertTrue(safety.contains("private record WaterSearchCache<T>(BlockPos feet, int computedTick, boolean hiddenBlockScan,")
                        && safety.contains("int observationRadius, T result)")
                        && waterCache.contains("cached.observationRadius() == SwimRoute.observationRadius(bot)")
                        && waterCache.contains("new WaterSearchCache<>(feet.immutable(), now, hiddenWaterScan,")
                        && continuation.contains("new WaterSearchCache<>(feet.immutable(), now, false,"),
                "a strict rescue cache, including a terminal null, must be keyed by the radius that observed it and bypassed immediately after a radius change");
        String strictRescue = body(safety, "private static final class StrictWaterEscapeSearch", "private enum WaterEscapeCell");
        String rescueAdmitFeet = body(safety, "private boolean admitCurrentFeet(BlockPos feet)",
                "private boolean exhausted()");
        int nonAdjacent = rescueAdmitFeet.indexOf("if (!adjacent(lastFeet, feet))");
        int rejectDisplacement = rescueAdmitFeet.indexOf("return false;", nonAdjacent);
        int attachKnownCell = rescueAdmitFeet.indexOf("if (!connected(feet))");
        assertTrue(nonAdjacent >= 0 && rejectDisplacement > nonAdjacent && attachKnownCell > rejectDisplacement,
                "a strict rescue must reject any non-adjacent feet relocation before it can reuse an older visited node");
        assertTrue(strictRescue.contains("ArrayDeque<BlockPos> queue")
                        && strictRescue.contains("List<BlockPos> currentNeighbors")
                        && strictRescue.contains("int nextNeighbor")
                        && strictRescue.contains("ArrayDeque<UnknownCandidate> unknowns")
                        && strictRescue.contains("int observationEpoch")
                        && strictRescue.contains("int lastObservationTick")
                        && strictRescue.contains("boolean stationaryUnknownRetryUsed")
                        && strictRescue.contains("beginObservationTick")
                        && strictRescue.contains("retryOneUnknown"),
                "strict Nav rescue must preserve its partial frontier and unknown-edge retry state across work slices");
        assertTrue(safety.contains("STRICT_WATER_SEARCH_CANDIDATES_PER_TICK = 96")
                        && safety.contains("STRICT_WATER_FALLBACK_WORK_PER_TICK = 26")
                        && safety.contains("STRICT_WATER_BFS_WORK_PER_TICK = STRICT_WATER_SEARCH_CANDIDATES_PER_TICK\n            - STRICT_WATER_FALLBACK_WORK_PER_TICK")
                        && strictRescue.contains("while (work < workBudget)")
                        && strictRescue.contains("fallbackNeighbors = waterEscapeNeighbors(fallbackFeet)")
                        && strictRescue.contains("fallbackCandidates.clear()")
                        && strictRescue.contains("while (!fallbackScanned && work < workBudget)")
                        && safety.contains("search.nextObservableFallback(bot, world, feet,")
                        && safety.contains("STRICT_WATER_FALLBACK_WORK_PER_TICK, allowWaterExploration"),
                "the 96-operation strict rescue budget must reserve the complete retained 26-cell fallback envelope and spend the remaining work on BFS without rescanning either slice");
        String observationTick = body(safety, "private void beginObservationTick(int now)",
                "private boolean exhausted()");
        assertTrue(observationTick.contains("if (lastObservationTick == now)")
                        && observationTick.contains("lastObservationTick = now;")
                        && observationTick.contains("stationaryUnknownRetryUsed = false;")
                        && observationTick.contains("if (!unknowns.isEmpty())")
                        && observationTick.contains("exhausted = false;")
                        && !observationTick.contains("observationEpoch++")
                        && !observationTick.contains("probeWaterEscapeCell"),
                "a stationary strict bot must reset only one later-tick UNKNOWN retry allowance after world edits, without scanning them outside the bounded rescue advance");
        String eligibleUnknown = body(safety, "private boolean hasEligibleUnknown()",
                "/** Performs exactly one queue/cursor/neighbour operation");
        String retryUnknown = body(safety, "private WaterEscapeStep retryOneUnknown(",
                "private WaterEscapeStep admitObservedCell");
        int rescueStaleEntry = retryUnknown.indexOf("!unknownCells.remove(unknown.cell())");
        int rescueStationaryCharge = retryUnknown.indexOf("stationaryUnknownRetryUsed = true;", rescueStaleEntry);
        int retryProbe = retryUnknown.indexOf("probeWaterEscapeCell(bot, world, unknown.cell(), false)", rescueStationaryCharge);
        assertTrue(eligibleUnknown.contains("unknown.observationEpoch() < observationEpoch")
                        && eligibleUnknown.contains("!stationaryUnknownRetryUsed && unknown.observedTick() < lastObservationTick")
                        && rescueStaleEntry >= 0 && rescueStationaryCharge > rescueStaleEntry && retryProbe > rescueStationaryCharge
                        && strictRescue.contains("new UnknownCandidate(unknown, parent.immutable(), observationEpoch,")
                        && strictRescue.contains("lastObservationTick)"),
                "a fixed viewpoint may re-probe one retained stale ray per tick, while a real viewpoint change still releases its epoch under the same bounded retry path");
        int verticalWaterGate = strictRescue.indexOf("if (!allowWaterExploration && candidate.kind() != WaterEscapeCell.DRY\n"
                + "                    && candidate.cell().getY() != fallbackFeet.getY())");
        int retainedVerticalWater = strictRescue.indexOf("return null;", verticalWaterGate);
        int consumedCandidate = strictRescue.indexOf("nextFallbackCandidate++;", verticalWaterGate);
        assertTrue(strictRescue.contains("private BlockPos nextObservableFallback(AIPlayerEntity bot, ServerLevel world, BlockPos feet,")
                        && strictRescue.contains("int workBudget, boolean allowWaterExploration)")
                        && verticalWaterGate >= 0
                        && retainedVerticalWater > verticalWaterGate
                        && consumedCandidate > retainedVerticalWater,
                "at full air a strict rescue must still consume observed dry and same-level water moves, while retaining an observed vertical water stroke until the shared air boundary becomes urgent");
        String waterCrisis = body(safety, "boolean surfaceAirUrgent = bot.getAirSupply() <= surfaceAirThreshold(bot, hiddenWaterScan);",
                "// Privileged local rescue path");
        assertTrue(waterCrisis.contains("beginObservableWaterRescueStep(bot, world, feet,")
                        && waterCrisis.contains("strictWaterEscapeSearches.get(bot.getUUID()), surfaceAirUrgent")
                        && waterCrisis.contains("if (surfaceAirUrgent && physicalStepTowardAir"),
                "one strict air-urgency decision must govern both retained water exploration and the direct physical air stroke");
        assertTrue(strictRescue.contains("WaterEscapeProbe probe = probeWaterEscapeCell(bot, world, candidate, false)")
                        && strictRescue.contains("if (probe.unknown())")
                        && strictRescue.contains("rememberUnknown(candidate, current)"),
                "Nav rescue must retain only truly unobserved edges; visible walls and hazards must complete rather than stall its session");
        String fallback = body(strictRescue, "private BlockPos nextObservableFallback",
                "private boolean isImmediateWaterBacktrack");
        String frontier = body(strictRescue, "private WaterEscapeStep advanceOneFrontierOperation",
                "/** Retries one formerly unknown edge");
        String rescueAdmission = body(safety, "private boolean beginRescueStep(",
                "/**\n     * A strict automatic rescue");
        String rescueGuard = body(safety, "private static boolean canContinueRescueStep(",
                "/** The axis-aligned corridor");
        String cachedReproof = body(safety, "private static boolean reproveWaterEscapeStep(",
                "private static int observableWaterStepPriority");
        int cornerGate = frontier.indexOf("hasVisibleSolidDiagonalWaterCorner");
        int admittedAfterCorner = frontier.indexOf("visited.add(candidate);", cornerGate);
        assertTrue(fallback.contains("hasVisibleSolidDiagonalWaterCorner(bot, fallbackFeet, candidate)")
                        && cornerGate > frontier.indexOf("WaterEscapeCell cell = probe.cell()")
                        && admittedAfterCorner > cornerGate
                        && retryUnknown.contains("hasVisibleSolidDiagonalWaterCorner(bot, unknown.parent(), unknown.cell())")
                        && retryUnknown.contains("visited.remove(unknown.cell())")
                        && rescueAdmission.contains("hasVisibleSolidDiagonalWaterCorner(bot, bot.blockPosition(), cell)")
                        && rescueGuard.contains("hasVisibleSolidDiagonalWaterCorner(bot, admission.origin(), admission.destination())")
                        && cachedReproof.contains("hasVisibleSolidDiagonalWaterCorner(bot, bot.blockPosition(), escape.next())"),
                "strict diagonal water edges must reject a visibly solid two-cell side column at fallback, retained-frontier, admission, reproof, and guarded-continuation boundaries without permanently visiting a parent-specific rejection");
        int reverseDeferral = fallback.indexOf("else if (!exhausted && !immediateWaterBacktrackDeferred)");
        int armReverseDeferral = fallback.indexOf("immediateWaterBacktrackDeferred = true;", reverseDeferral);
        int clearReverseDeferral = fallback.indexOf("immediateWaterBacktrackDeferred = false;", armReverseDeferral);
        int consumeReverse = fallback.indexOf("nextFallbackCandidate++;", clearReverseDeferral);
        int movedFeet = strictRescue.indexOf("if (moved) {");
        int resetAfterMove = strictRescue.indexOf("immediateWaterBacktrackDeferred = false;", movedFeet);
        assertTrue(strictRescue.contains("private boolean immediateWaterBacktrackDeferred;")
                        && reverseDeferral >= 0 && armReverseDeferral > reverseDeferral
                        && clearReverseDeferral > armReverseDeferral && consumeReverse > clearReverseDeferral
                        && resetAfterMove > movedFeet,
                "a retained UNKNOWN may defer an immediate strict-water reversal for one bounded slice, but must never indefinitely veto the only already-proved retreat");
    }

    @Test
    void strictAirColumnSearchKeepsItsCursorAndDistinguishesTerminalUnknownFromPending() throws IOException {
        String swim = read("task/FollowSwimming.java");

        String blocksToAir = body(swim, "private AirPlan blocksToAir", "private void clearAirSearch");
        int columnAdvance = blocksToAir.indexOf("airColumnSearch.advance(bot, world,");
        int columnBudget = blocksToAir.indexOf("strictWaterWork.claimVerticalColumn(VERTICAL_AIR_WORK_PER_TICK)", columnAdvance);
        int columnSpend = blocksToAir.indexOf("strictWaterWork.spend(column.work());", columnBudget);
        int pending = blocksToAir.indexOf("column.status() == AirColumnStatus.PENDING", columnAdvance);
        int found = blocksToAir.indexOf("column.status() == AirColumnStatus.FOUND", pending);
        int lateralRoute = blocksToAir.indexOf("SwimRoute.startSearch", found);
        assertTrue(columnAdvance >= 0 && columnBudget > columnAdvance && columnSpend > columnBudget
                        && pending > columnSpend && found > pending && lateralRoute > found,
                "a bounded vertical air probe must claim and charge shared strict work before it yields PENDING or starts a separate retained lateral route");
        int pendingYield = blocksToAir.indexOf("return AirPlan.yielded();", pending);
        int lateralPending = blocksToAir.indexOf("if (result.pending())", lateralRoute);
        int lateralYield = blocksToAir.indexOf("return AirPlan.yielded();", lateralPending);
        assertTrue(pendingYield > pending && lateralPending > lateralRoute && lateralYield > lateralPending,
                "cooperative vertical and lateral slices must remain scheduler yields, never resolve to no known air");
        String oxygenDecision = body(swim, "private static boolean shouldSurface", "// ---- oxygen");
        assertTrue(oxygenDecision.contains("airPlan.pending()")
                        && oxygenDecision.contains("air <= FollowOxygen.SURFACE_FLOOR_AIR")
                        && oxygenDecision.contains("FollowOxygen.shouldSurface(air, rate, airPlan.blocksToAir())"),
                "a pending air slice must preserve normal high-air pacing while retaining the real low-air surface boundary");
        String statuses = body(swim, "private enum AirColumnStatus", "private record AirColumnResult");
        assertTrue(statuses.contains("PENDING") && statuses.contains("FOUND")
                        && statuses.contains("NO_DIRECT") && statuses.contains("UNKNOWN"),
                "vertical-air planning must distinguish an unfinished slice, breathable column, physical ceiling, and unseen terminal cell");
        String airResult = body(swim, "private record AirColumnResult", "private static final class AirColumnSearch");
        assertTrue(airResult.contains("AirColumnStatus.PENDING")
                        && airResult.contains("AirColumnStatus.NO_DIRECT")
                        && airResult.contains("AirColumnStatus.UNKNOWN")
                        && airResult.contains("int work")
                        && airResult.contains("private AirColumnResult withoutWork()")
                        && airResult.contains("work == 0 ? this : new AirColumnResult(status, blocks, 0)"),
                "air-column terminal outcomes must carry charged work once, then reuse a zero-work terminal result rather than charging a completed proof again");
        String airSearch = body(swim, "private static final class AirColumnSearch", "private boolean ascendStep");
        int terminal = airSearch.indexOf("private AirColumnResult terminal");
        int terminalReuse = airSearch.indexOf("if (terminal != null)", terminal);
        int observation = airSearch.indexOf("!hiddenWorldScan && !SwimRoute.canObserveColumn(bot, cell)", terminalReuse);
        int rawFluid = airSearch.indexOf("world.getFluidState(cell)", observation);
        assertTrue(airSearch.contains("int nextDy") && terminal >= 0 && terminalReuse > terminal
                        && observation > terminalReuse && rawFluid > observation
                        && airSearch.contains("int work = 0;")
                        && airSearch.contains("work++;")
                        && airSearch.contains("terminal = AirColumnResult.unknown(work)")
                        && airSearch.contains("terminal = AirColumnResult.noDirect(work)")
                        && airSearch.contains("return AirColumnResult.pending(work)"),
                "strict air-column work must retain its cursor, charge each checked cell, stop at an unseen/blocked terminal, and never raw-read above an unobserved cell");
        int eyeReset = blocksToAir.indexOf("if (airColumnSearch == null || !airColumnSearch.matches");
        int columnProof = blocksToAir.indexOf("airColumnSearch.advance", eyeReset);
        String resetOnlyColumn = blocksToAir.substring(eyeReset, columnProof);
        assertFalse(resetOnlyColumn.contains("airRouteSearch = null"),
                "an eye-block change must reset only the vertical column cursor, preserving an adjacent-compatible lateral air route");
        int lateralResult = blocksToAir.indexOf("advanceLateralSearch(bot, world, airRouteSearch, hiddenWorldScan)");
        int awaiting = blocksToAir.indexOf(
                "airRouteAwaitingViewpoint = result.status() == SwimRoute.SearchStatus.UNKNOWN", lateralResult);
        assertTrue(lateralResult >= 0 && awaiting > lateralResult,
                "air exploration must use the shared lateral ledger and arm only when the retained air route returns UNKNOWN, not on ordinary PENDING work");
        String ascend = body(swim, "private boolean ascendWhileSubmerged", "private void updateAscending");
        assertTrue(ascend.contains("airRouteAwaitingViewpoint")
                        && ascend.contains("beginAirObservedWaterExplorationStep")
                        && !ascend.contains("beginObservedWaterExplorationStep"),
                "the air fallback must consume the UNKNOWN latch through its retained observed-air cursor rather than steering toward hidden air");
    }

    @Test
    void strictWaterObservationGameTestsHaveMatchingEnvironmentTemplates() throws IOException {
        assertGameTestEnvironment(
                "task/SurfaceWaterRecoveryGameTests.java",
                "surface_water_recovery_game_tests_strict_rescue_explores_visible_water_before_a_hidden_route");
        assertGameTestEnvironment(
                "task/FollowSwimGameTests.java",
                "follow_swim_game_tests_strict_route_does_not_plan_through_a_hidden_shore");
        assertGameTestEnvironment(
                "task/FollowSwimGameTests.java",
                "follow_swim_game_tests_strict_follow_approaches_a_visible_far_water_edge");
        assertGameTestEnvironment(
                "task/FollowSwimGameTests.java",
                "follow_swim_game_tests_strict_no_edge_uses_diagonal_observed_land_approach");
        assertGameTestEnvironment(
                "task/FollowSwimGameTests.java",
                "follow_swim_game_tests_operator_step_is_cancelled_before_strict_reproof");
        assertGameTestEnvironment(
                "task/FollowSwimGameTests.java",
                "follow_swim_game_tests_emergency_preemption_does_not_let_stale_follow_cancel_successor");
        assertGameTestEnvironment(
                "task/FollowSwimGameTests.java",
                "follow_swim_game_tests_strict_refusal_envelope_rejects_hidden_diagonal_corner");
        assertGameTestEnvironment(
                "task/FollowSwimGameTests.java",
                "follow_swim_game_tests_strict_refusal_envelope_rejects_hidden_deep_drop_column");
        assertGameTestEnvironment(
                "task/SurfaceWaterRecoveryGameTests.java",
                "surface_water_recovery_game_tests_strict_cached_route_reproof_rejects_new_occluder");
        assertGameTestEnvironment(
                "task/SurfaceWaterRecoveryGameTests.java",
                "surface_water_recovery_game_tests_strict_in_flight_rescue_guard_rejects_new_occluder");
        assertGameTestEnvironment(
                "task/SurfaceWaterRecoveryGameTests.java",
                "surface_water_recovery_game_tests_strict_in_flight_rescue_guard_rejects_late_interposing_wall");
        assertGameTestEnvironment(
                "task/SurfaceWaterRecoveryGameTests.java",
                "surface_water_recovery_game_tests_strict_in_flight_dry_step_rejects_flooded_target");
        assertGameTestEnvironment(
                "task/SurfaceWaterRecoveryGameTests.java",
                "surface_water_recovery_game_tests_strict_rescue_uses_visible_diagonal_water_step");
        assertGameTestEnvironment(
                "task/SurfaceWaterRecoveryGameTests.java",
                "surface_water_recovery_game_tests_strict_diagonal_rescue_rejects_visible_solid_corner");
        assertGameTestEnvironment(
                "task/FollowSwimGameTests.java",
                "follow_swim_game_tests_strict_route_search_resumes_after_pending_slice");
        assertGameTestEnvironment(
                "task/FollowSwimGameTests.java",
                "follow_swim_game_tests_strict_route_search_returns_adjacent_visible_exit");
        assertGameTestEnvironment(
                "task/FollowSwimGameTests.java",
                "follow_swim_game_tests_strict_pending_air_column_keeps_healthy_follow_stroke");
    }

    @Test
    void swimmerFollowNeverTouchesBoatsAndFollowTaskDelegatesToIt() throws IOException {
        String follow = read("task/FollowTask.java");
        assertTrue(follow.contains("swimming.follow(bot, target, elapsed, SWIM_STOP_DISTANCE)"));
        assertTrue(follow.contains("swimming.exitWaterForLand(bot, target, elapsed, STOP_DISTANCE)"));
        int swimming = follow.indexOf("private void followSwimming(AIPlayerEntity bot, ServerPlayer target)");
        int swimmingEnd = follow.indexOf("private void followLand", swimming);
        String swimmingBody = follow.substring(swimming, swimmingEnd);
        assertTrue(swimmingBody.indexOf("publishPace(bot, target);")
                        < swimmingBody.indexOf("swimming.follow(bot, target, elapsed, SWIM_STOP_DISTANCE)"),
                "a swimming follow must publish its FOLLOW gait before it starts swimming");
        int exit = follow.indexOf("swimming.exitWaterForLand(bot, target, elapsed, STOP_DISTANCE)");
        assertTrue(follow.lastIndexOf("publishPace(bot, target);", exit) < exit,
                "the water-to-land exit must retain its FOLLOW gait while it swims ashore");
        assertFalse(follow.contains("nextSwimRepathTick"), "the old launch-site swim entry is gone");
    }

    @Test
    void stuckFollowUsesOnlyVerifiedStepsOrAnObservedBaritoneReplan() throws IOException {
        String recovery = read("task/FollowStuckRecovery.java");
        String dig = read("task/FollowDigOut.java");

        int adjacent = recovery.indexOf("beginStep(bot, current, candidate)");
        int replan = recovery.indexOf("follow_recovery_baritone_required");
        assertTrue(adjacent >= 0 && replan > adjacent,
                "recovery must exhaust verified local steps before requesting a fresh Baritone route");
        assertTrue(recovery.contains("forceRepathPending = true;")
                        && !recovery.contains("FollowDigOut")
                        && !recovery.contains("digOut.start("),
                "follow recovery must never turn a blocked route into terrain excavation");
        assertTrue(dig.contains("RetiredNavigationTask.legacyExcavationDisabled()")
                        && dig.contains("legacy_navigation_retired"),
                "the retained legacy entry point must fail closed and leave an audit record");
    }

    @Test
    void stuckRecoveryReconcilesOnlyItsExactStepLease() throws IOException {
        String recovery = read("task/FollowStuckRecovery.java");
        assertTrue(recovery.contains("pack.cancelStep(stepLease);")
                        && recovery.contains("pack.releaseStepLease(stepLease);")
                        && recovery.contains("pack.releaseStepLease(lease);")
                        && !recovery.contains("pack.cancelStep();"),
                "stuck recovery must release only its own cancelled or completed guarded lease");
    }

    @Test
    void landBoundExitOnlyTakesOverForRealSwimmingAndHonoursTheAirFloor() throws IOException {
        String swim = read("task/FollowSwimming.java");
        int exit = swim.indexOf("boolean exitWaterForLand(");
        int end = swim.indexOf("// ---- entering the water");
        assertTrue(exit >= 0 && end > exit);
        String body = swim.substring(exit, end);
        assertTrue(body.contains("needsWaterExit(bot, target, standoff)"),
                "wading through shallows must stay with land follow, not run the swim exit");
        assertFalse(body.contains("if (!isSwimCell(world, bot.blockPosition()))"),
                "merely having wet feet is not swimming");
        int gate = body.indexOf("needsWaterExit(");
        int search = body.indexOf("searchRoute(");
        assertTrue(gate >= 0 && search > gate, "no water route search before the swimming gate");
        assertTrue(body.contains("updateAscending(bot, air, lossRate(bot), true, airPlan)")
                        && body.contains("ascendWhileSubmerged("),
                "a submerged bot heading for land must still surface for air early");
        assertTrue(swim.contains("static boolean isSwimming(AIPlayerEntity bot)")
                        && swim.contains("bot.isUnderWater()"),
                "swimming = head under water or afloat with nothing solid underfoot");
    }

    @Test
    void modeSwitchesAndPauseAbortResetLandRecoveryWithoutTerrainOwnership() throws IOException {
        String follow = read("task/FollowTask.java");
        assertTrue(follow.contains("private void suspendLandRecovery(AIPlayerEntity bot)")
                && follow.contains("stuckRecovery.reset(bot, elapsed);"));
        for (String owner : new String[]{"protected void onPause(", "protected void onAbort(", "protected void onResume("}) {
            int at = follow.indexOf(owner);
            assertTrue(at >= 0, owner);
            int next = follow.indexOf("@Override", at);
            String body = follow.substring(at, next < 0 ? follow.length() : next);
            assertTrue(body.contains("suspendLandRecovery(bot)"), owner + " must reset land recovery");
        }
        int tick = follow.indexOf("protected void onTick(");
        String onTick = follow.substring(tick, follow.indexOf("private void faceTarget"));
        assertTrue(count(onTick, "suspendLandRecovery(bot)") >= 5,
                "offline, boat, swim, leave-boat and exit-water ticks must each suspend land recovery");
        String recovery = read("task/FollowStuckRecovery.java");
        assertTrue(recovery.contains("boolean isDigging() {\n        return false;\n    }")
                        && !recovery.contains("FollowDigOut"),
                "reset has no dig-out controller because follow recovery never owns terrain breaking");
    }

    @Test
    void digOutNeverBreaksBuildingBlocksOrBlocksTheBotsPlaced() throws IOException {
        String dig = read("task/FollowDigOut.java");
        assertTrue(dig.contains("isBuildingBlock(state)") && dig.contains("BotEdits.wasPlaced(world, cell)"),
                "building blocks and the bots' own placements are never dug");
        assertTrue(dig.contains("state.is(Blocks.COBBLESTONE)") && dig.contains("BlockTags.PLANKS")
                && dig.contains("BlockTags.DOORS"));
        assertFalse(dig.contains("never doors, chests, beds, glass, planks or any other block entity / built block"),
                "the header must not claim it can tell built blocks from natural ones");
        assertTrue(dig.contains("Limits (not a guarantee)"));
        String tests = readGametest("task/FollowSwimGameTests.java");
        assertFalse(tests.contains("NeverPlayerBuiltWalls"), "the test name must not overclaim");
    }

    @Test
    void aSuppressedPhysicalSnapDoesNotEscalateToTheEmergencyTeleport() throws IOException {
        String pack = read("action/ActionPack.java");
        int suppressed = pack.indexOf("if (physicalSnapSuppressed(current, reason))");
        int planned = pack.indexOf("planAdjacentStep(world, current, reason)", suppressed);
        assertTrue(suppressed >= 0 && planned > suppressed);
        String between = pack.substring(suppressed, planned);
        assertTrue(between.contains("return false;"), "a suppressed snap must return, not plan another step");
        assertFalse(pack.contains("teleportTo(") || pack.contains("EMERGENCY_TELEPORT"),
                "no path-start relocation exists in any profile, so a suppressed snap cannot become one");
    }

    @Test
    void followRechecksItsBaritoneGoalAsSoonAsThePlayerMoves() throws IOException {
        String follow = read("task/FollowTask.java");
        assertTrue(follow.contains("baritoneTargetPos = targetPos.immutable();"));
        assertTrue(follow.contains("baritoneTargetPos.distSqr(targetPos) >= BARITONE_REGOAL_MOVED_SQ")
                && follow.contains("nextRepathTick = elapsed + (regoal.result().isFailed() ? REPATH_TICKS : BARITONE_REGOAL_TICKS);"));
    }

    @Test
    void combatIsLoggedAtHitDeathAndTaskLevel() throws IOException {
        String entity = read("entity/AIPlayerEntity.java");
        assertTrue(entity.contains("\"damage_taken\"") && entity.contains("\"bot_death\""),
                "damage taken and death (source, attacker) must be logged");
        String combat = read("task/CombatTask.java");
        assertTrue(combat.contains("\"combat_target\"") && combat.contains("\"combat_phase\"")
                && combat.contains("\"combat_kill\""));
        String interact = read("action/InteractAction.java");
        assertTrue(interact.contains("\"target_hp\""));
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            n++;
        }
        return n;
    }

    private static String body(String source, String start, String end) {
        int begin = source.indexOf(start);
        int finish = source.indexOf(end, begin);
        assertTrue(begin >= 0 && finish > begin, start);
        return source.substring(begin, finish);
    }

    private static void assertYieldBeforeRenew(String body, String branch) {
        int yield = body.indexOf("if (mustYieldToWaterRescue(bot))");
        int renew = body.indexOf("NavSafetyNet.INSTANCE.renewFollowSwim(bot)");
        assertTrue(yield >= 0 && renew > yield,
                branch + " must yield before it renews or continues Follow swimming");
    }

    private static String readGametest(String relative) throws IOException {
        return Files.readString(Path.of("src/gametest/java/io/github/zoyluo/minecraftai").resolve(relative));
    }

    private static void assertGameTestEnvironment(String sourcePath, String id) throws IOException {
        assertTrue(readGametest(sourcePath).contains("@GameTest(environment = \"minecraftai-gametest:" + id + "\""),
                sourcePath + " must name its exact GameTest environment id");
        Path template = Path.of("src/gametest/resources/data/minecraftai-gametest/test_environment", id + ".json");
        assertTrue(Files.isRegularFile(template), "missing GameTest environment template " + template);
        assertTrue(Files.readString(template).contains("\"type\": \"minecraft:all_of\""),
                "GameTest environment template must be a minimal all_of definition: " + template);
    }

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }
}
