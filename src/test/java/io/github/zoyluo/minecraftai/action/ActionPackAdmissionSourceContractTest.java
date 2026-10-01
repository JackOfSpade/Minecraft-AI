package io.github.zoyluo.minecraftai.action;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the fail-closed admission boundary for walked steps.  A guarded owner can retain an
 * ActionPack fence after its physical step has ended, so every caller must treat a null lease as
 * "not started" and stateful callers must reconcile only the exact admission they obtained.
 */
class ActionPackAdmissionSourceContractTest {
    private static final Path MAIN_SOURCE = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    /**
     * A raw statement such as {@code pack.runStep(step);} drops the nullable admission result.
     * Assignments, returns, conditionals, and the edge-helper ternaries intentionally do not
     * match this narrow source guard.
     */
    private static final Pattern DISCARDED_RUN_STEP = Pattern.compile(
            "(?ms)^\\s*(?!(?:if|return)\\b)(?![^;\\n]*=)[A-Za-z_$][\\w$().]*\\.runStep\\s*\\([^;]*?\\);");

    private static final List<String> MIGRATED_RUN_STEP_SOURCES = List.of(
            "action/DigNav.java",
            "action/HarvestCore.java",
            "action/InCellWalk.java",
            "task/AcquireWaterTask.java",
            "task/CreateObsidianTask.java",
            "task/DescendToYTask.java",
            "task/DigDownTask.java",
            "task/EmergencyShelterTask.java",
            "task/FollowDigOut.java",
            "task/FollowStuckRecovery.java",
            "task/FollowSwimming.java",
            "task/MiningBarricadeTask.java",
            "task/NavSafetyNet.java",
            "task/OreDigTask.java",
            "task/ShelterExitDebtRepayer.java");

    @Test
    void runStepReturnsNullableLeaseAndExposesExactReconciliation() throws IOException {
        String source = source("action/ActionPack.java");
        String startStep = methodBody(source, "private StepLease startStep(");

        assertInOrder(startStep,
                "if (guardedStepLease != null)",
                "return null;",
                "StepLease lease = new StepLease();",
                "this.activeStepLease = lease;",
                "return lease;");
        assertContains(source, "public boolean stepInFlightFor(StepLease lease)",
                "ActionPack must expose exact live ownership checks");
        assertContains(source, "activeStepLease == lease",
                "exact live ownership must compare the active lease by identity");
        assertContains(source, "public WalkedStep.Result stepResultFor(StepLease lease)",
                "ActionPack must expose exact terminal reconciliation");
        assertContains(source, "completedStepLease == lease ? lastStepResult : null",
                "a result must be unavailable to a foreign/stale lease");
        assertContains(source, "public boolean stepAdmissionBlocked()",
                "ordinary controllers need a generic admission query rather than the Baritone-only fence API");
    }

    @Test
    void everyMigratedRunStepCallerConsumesTheAdmissionResult() throws IOException {
        List<String> discovered = discoveredRunStepSources();
        assertEquals(MIGRATED_RUN_STEP_SOURCES, discovered,
                "update the admission contract inventory when production adds/removes a runStep caller");
        for (String relative : discovered) {
            String source = source(relative);
            assertContains(source, ".runStep(", relative + " must keep its guarded runStep call covered");
            Matcher discarded = DISCARDED_RUN_STEP.matcher(source);
            assertFalse(discarded.find(), () -> relative + " discards nullable runStep admission: " + discarded.group());
        }
    }

    @Test
    void leaseBackedStatefulCallersPublishOnlyAfterAdmissionAndReconcileExactly() throws IOException {
        String createObsidian = source("task/CreateObsidianTask.java");
        String recovery = methodBody(createObsidian, "private void recoverWater(");
        assertInOrder(recovery,
                "obsidian_surface",
                "if (lease != null)",
                "recoveryWalk = \"surface\"",
                "recoveryWalkLease = lease");
        assertInOrder(recovery,
                "obsidian_return_rim",
                "if (lease != null)",
                "recoveryWalk = \"rim\"",
                "recoveryWalkLease = lease");
        assertContains(recovery, "stepInFlightFor(lease)",
                "CreateObsidian must wait for its own recovery lease");
        assertContains(recovery, "stepResultFor(lease)",
                "CreateObsidian must not consume a successor recovery result");
        assertInOrder(methodBody(createObsidian, "private static boolean beginPickupStep("),
                "return bot.getActionPack().runStep(", "!= null");

        String acquireWater = source("task/AcquireWaterTask.java");
        assertInOrder(methodBody(acquireWater, "private boolean settleOnStandableCell("),
                "ActionPack.StepLease lease = bot.getActionPack().runStep(step)",
                "if (lease == null)",
                "ascentSettle = step",
                "ascentSettleLease = lease");
        assertExactLeaseReconciliation(acquireWater, "AcquireWaterTask");

        String shelter = source("task/EmergencyShelterTask.java");
        assertInOrder(methodBody(shelter, "private boolean startMotion("),
                "ActionPack.StepLease lease = pack.runStep(step)",
                "if (lease == null)",
                "motion = kind",
                "motionLease = lease");
        assertExactLeaseReconciliation(shelter, "EmergencyShelterTask");

        String ore = source("task/OreDigTask.java");
        assertContains(ore, "record MoveInFlight(ActionPack.StepLease lease",
                "OreDig move state must retain its admission lease");
        assertInOrder(methodBody(ore, "private boolean beginWalkedMove("),
                "ActionPack.StepLease lease = bot.getActionPack().runStep(",
                "if (lease == null)",
                "moveInFlight = new MoveInFlight(lease");
        assertExactLeaseReconciliation(ore, "OreDigTask");

        assertTaskLeaseLifecycle("task/DigDownTask.java", "private boolean launchStep(",
                "step = walked", "stepLease = lease", "DigDownTask");
        assertTaskLeaseLifecycle("task/DescendToYTask.java", "private boolean launchStep(",
                "stepLease = lease", "step = walked", "DescendToYTask");
        String descend = source("task/DescendToYTask.java");
        String edgeTick = methodBody(descend, "private void tickEdgePlacement(");
        assertInOrder(edgeTick,
                "if (!current.step.ended())",
                "if (!pack.stepIdle())",
                "edge = null;",
                "return;",
                "WalkedStep.Result result = current.step.outcome()");
        String acquireEdgeTick = methodBody(acquireWater, "private void tickEdgePlacement(");
        assertInOrder(acquireEdgeTick,
                "if (!current.step.ended())",
                "if (!pack.stepIdle())",
                "edge = null;",
                "return;",
                "WalkedStep.Result result = current.step.outcome()");
        assertInOrder(edgeTick,
                "current.stage == EdgeStage.RETURN_PENDING",
                "InCellWalk.beginEdgeReturn(",
                "if (returning == null)",
                "return;",
                "current.step = returning");
        assertTaskLeaseLifecycle("task/FollowDigOut.java", "private boolean stepInto(",
                "step = next", "stepLease = lease", "FollowDigOut");
        assertTaskLeaseLifecycle("task/FollowStuckRecovery.java", "private StepStart beginStep(",
                "step = next", "stepLease = lease", "FollowStuckRecovery");
        assertTaskLeaseLifecycle("task/FollowSwimming.java",
                "private boolean beginStep(AIPlayerEntity bot, BlockPos cell, String reason, boolean chargeStrictAdmission)",
                "stepLease = lease", "stepAdmission = admission", "FollowSwimming");
        assertTaskLeaseLifecycle("task/MiningBarricadeTask.java", "private void tickRetreat(",
                "retreatStep = next", "retreatStepLease = lease", "MiningBarricadeTask");
        assertTaskLeaseLifecycle("task/ShelterExitDebtRepayer.java", "boolean repay(",
                "step = next", "stepLease = lease", "ShelterExitDebtRepayer");

        String safetyNet = source("task/NavSafetyNet.java");
        assertContains(safetyNet, "record RescueStepAdmission(AIPlayerEntity bot, ActionPack.StepLease lease",
                "NavSafetyNet rescue state must retain the guarded lease");
        assertInOrder(methodBody(safetyNet, "private boolean beginRescueRecenter("),
                "ActionPack.StepLease lease = bot.getActionPack().runStep(",
                "if (lease == null)",
                "rescueSteps.put(bot.getUUID(), admission.withLease(lease))");
        assertInOrder(methodBody(safetyNet, "private boolean beginRescueStep("),
                "ActionPack.StepLease lease = bot.getActionPack().runStep(",
                "if (lease == null)",
                "rescueSteps.put(bot.getUUID(), admission.withLease(lease))");
        assertContains(safetyNet, "stepInFlightFor(admission.lease())",
                "NavSafetyNet must check exact rescue ownership before acting");
        assertContains(safetyNet, "releaseStepLease(admission.lease())",
                "NavSafetyNet must release only its own guarded rescue lease");
        assertInOrder(methodBody(safetyNet, "private boolean beginSuffocationEmergencyStep("),
                "ActionPack.StepLease lease = bot.getActionPack().runEmergencyStep(",
                "if (lease == null)",
                "state.stepLease = lease");
    }

    @Test
    void walkedStepBackedAndStatelessCallersAlsoFailClosed() throws IOException {
        String inCellWalk = source("action/InCellWalk.java");
        String recenter = methodBody(inCellWalk, "public Centering recenter(");
        assertInOrder(recenter,
                "if (pack.runStep(candidate) == null)",
                "return Centering.WALKING;",
                "recenterStep = candidate");
        assertContains(recenter, "recenterStep.outcome()",
                "InCellWalk must reconcile the owned step object, not a successor result");
        assertContains(inCellWalk, "runStep(step) == null ? null : step",
                "edge helpers must return null when their admission is denied");

        String digNav = source("action/DigNav.java");
        assertContains(digNav, "if (pack.runStep(descent) == null)",
                "DigNav must not report a denied descent as started");
        String harvest = source("action/HarvestCore.java");
        assertContains(harvest, "return pack.runStep(step) != null",
                "HarvestCore must return whether pickup-step admission succeeded");

        for (String relative : discoveredRunStepSources()) {
            assertFalse(source(relative).contains(".stepResult()"),
                    () -> relative + " must not reconcile a walked move through ActionPack's global result");
        }
    }

    private static void assertTaskLeaseLifecycle(String relative, String methodSignature,
                                                 String firstPublication, String leasePublication,
                                                 String owner) throws IOException {
        String source = source(relative);
        String admission = methodBody(source, methodSignature);
        assertInOrder(admission,
                "ActionPack.StepLease lease =",
                "if (lease == null)",
                firstPublication,
                leasePublication);
        assertExactLeaseReconciliation(source, owner);
    }

    private static void assertExactLeaseReconciliation(String source, String owner) {
        assertContains(source, "ActionPack.StepLease", owner + " must retain an exact step admission");
        assertContains(source, "stepInFlightFor(", owner + " must check exact live ownership before acting");
        assertContains(source, "stepResultFor(", owner + " must consume only its exact terminal result");
    }

    private static String source(String relative) throws IOException {
        return Files.readString(MAIN_SOURCE.resolve(relative));
    }

    private static List<String> discoveredRunStepSources() throws IOException {
        List<String> discovered = new ArrayList<>();
        Path actionPack = MAIN_SOURCE.resolve("action/ActionPack.java");
        try (Stream<Path> paths = Files.walk(MAIN_SOURCE)) {
            for (Path path : paths.filter(candidate -> candidate.toString().endsWith(".java")).toList()) {
                if (!path.equals(actionPack) && Files.readString(path).contains(".runStep(")) {
                    discovered.add(MAIN_SOURCE.relativize(path).toString().replace('\\', '/'));
                }
            }
        }
        discovered.sort(String::compareTo);
        return List.copyOf(discovered);
    }

    private static String methodBody(String source, String signature) {
        int signatureAt = source.indexOf(signature);
        if (signatureAt < 0) {
            throw new AssertionError("missing method signature: " + signature);
        }
        int openBrace = source.indexOf('{', signatureAt);
        if (openBrace < 0) {
            throw new AssertionError("missing method body: " + signature);
        }
        int depth = 0;
        for (int index = openBrace; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return source.substring(openBrace, index + 1);
            }
        }
        throw new AssertionError("unterminated method body: " + signature);
    }

    private static void assertInOrder(String source, String... fragments) {
        int cursor = 0;
        for (String fragment : fragments) {
            int at = source.indexOf(fragment, cursor);
            if (at < 0) {
                throw new AssertionError("missing or out-of-order source fragment: " + fragment);
            }
            cursor = at + fragment.length();
        }
    }

    private static void assertContains(String source, String fragment, String message) {
        assertTrue(source.contains(fragment), message + "; missing: " + fragment);
    }
}
