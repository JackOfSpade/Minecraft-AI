package io.github.zoyluo.minecraftai.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * A log the bot sees through leaves is mined by breaking the leaves first, one real block at a time, in the one place every break
 * goes through. These source contracts pin where that happens and what it must never become: a second break actuator, a way to
 * reach through a block that is not broken, or something a driver's controller does on top of Baritone's own aim. The behaviour is
 * in {@code MiningObstructionTest}, {@code ReachObstructionsTest} and the GameTests.
 */
class MiningObstructionSourceContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai");

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }

    private static String body(String source, String signature) {
        int signatureAt = source.indexOf(signature);
        assertTrue(signatureAt >= 0, () -> "missing method signature: " + signature);
        int open = source.indexOf('{', signatureAt);
        int depth = 0;
        for (int at = open; at < source.length(); at++) {
            char current = source.charAt(at);
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return source.substring(open, at + 1);
            }
        }
        throw new AssertionError("unterminated method body: " + signature);
    }

    private static void assertInOrder(String source, String... fragments) {
        int cursor = 0;
        for (String fragment : fragments) {
            int at = source.indexOf(fragment, cursor);
            assertTrue(at >= 0, () -> "missing or out-of-order fragment: " + fragment);
            cursor = at + fragment.length();
        }
    }

    private static int count(String source, String needle) {
        int count = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    @Test
    void onlyAnOrdinaryControllerClearsAndItsStepsAreOrdinaryControllersThatDoNot() throws IOException {
        String controller = read("action/MiningController.java");
        assertTrue(body(controller, "public MiningController(BlockPos pos, Direction face)").contains("this(pos, face, false, true, false, true)"),
                "a directly started controller clears the way to its target");
        assertTrue(body(controller, "public static MiningController driven(BlockPos pos, Direction face)")
                        .contains("new MiningController(pos, face, true, false, false, true)"),
                "Baritone clicks what its own pick ray meets, which is the leaf: its controller must not clear anything on top of that");
        assertEquals(1, count(controller, "new MiningController(plan.obstruction().pos()"),
                "a clearing step is created in one place");
        assertTrue(controller.contains("faceToward(player, plan.obstruction().pos()),\n                        false, false, false, swordsMine)"),
                "a step is neither driven, nor clearing, nor allowed to take the bot's footing: it aims, picks its tool and has the full gate itself");
        assertTrue(body(controller, "static MiningController ownSupport(BlockPos pos, Direction face)")
                        .contains("new MiningController(pos, face, false, false, true, true)"),
                "the break of the bot's own footing (TowerDescent) aims only at the block under its feet and never clears anything in front of it");
        assertTrue(body(controller, "public ActionResult tick(ActionPack pack)")
                        .contains("clearsObstructions && !started ? clearTheWay(pack, player) : visibilityRefused(player);"),
                "a break that has started on the target never turns into clearing: a lost line is the old refusal");
    }

    @Test
    void theClearingStepIsBrokenThroughThePackLikeAnyBreakAndAuditedLikeOne() throws IOException {
        String controller = read("action/MiningController.java");
        String step = body(controller, "private ActionResult tickClearing(ActionPack pack, AIPlayerEntity player)");
        assertInOrder(step,
                "pack.tickBreak(clearing)",
                "if (result.isInProgress())",
                "pack.recordBreak(step, pos)",
                "MiningObstruction.logCleared(",
                "return ActionResult.IN_PROGRESS;",
                "MiningObstruction.logRefused(",
                "return ActionResult.failed(TARGET_OBSTRUCTED);");
        assertFalse(step.contains("handleBlockBreakAction(") || step.contains("destroyBlock("),
                "the controller's own gated packet path is the only break actuator");
        String pack = read("action/ActionPack.java");
        String audit = body(pack, "void recordBreak(MiningController finished, BlockPos obstructionOf)");
        assertInOrder(audit, "\"mine_complete\"", "BaritoneEdits.recordBreak(");
        assertTrue(audit.contains("\"obstruction_of\""), "the audit line says which target a cleared block was broken for");
        assertTrue(body(pack, "private void tickMining()").contains("recordBreak(mining, null)"),
                "a target's own break is recorded by the same method");
    }

    @Test
    void cancellingTheOperationCancelsTheStepThatIsRunning() throws IOException {
        String controller = read("action/MiningController.java");
        String abort = body(controller, "public void abort(AIPlayerEntity player)");
        assertInOrder(abort, "abortClearing(player);", "if (!started)");
        String cancel = body(controller, "private void abortClearing(AIPlayerEntity player)");
        assertInOrder(cancel, "clearing.abort(player);", "clearing = null;", "clearingFor = null;");
        assertTrue(body(controller, "public ActionResult tick(ActionPack pack)").contains("abortClearing(player);\n            return settleVisibleAir(player);"),
                "a target that is gone while a step runs stops the step");
    }

    @Test
    void everyCallerThatAdmitsABreakAsksTheSameAdmission() throws IOException {
        assertTrue(body(read("action/ActionPack.java"), "public ActionResult startMining(BlockPos pos, Direction face)")
                .contains("MiningController.admission(player, pos)"));
        String miner = read("action/BlockMiner.java");
        assertEquals(2, count(miner, "MiningController.admission(bot, target)"), "the per-tick proof and the proof before the tool choice");
        assertFalse(miner.contains("currentObservedTarget"), "the miner asks the admission, never a private copy of the strict gate");
        assertTrue(body(read("baritone/BaritoneGoals.java"), "public static Outcome mineAt(")
                .contains("MiningController.admissionRefusal(bot, target)"));
    }

    @Test
    void theAdmissionAsksTheStrictGateFirstAndOnlyThenPlansAWayToClear() throws IOException {
        String controller = read("action/MiningController.java");
        String admission = body(controller, "static MiningObstruction.Plan admission(AIPlayerEntity player, BlockPos pos)");
        assertInOrder(admission, "currentObservedTarget(player, pos)", "MiningObstruction.Plan.REACHABLE",
                "MiningObstruction.plan(player, pos, List.of())");
        String plan = body(controller, "private ActionResult clearTheWay(ActionPack pack, AIPlayerEntity player)");
        assertInOrder(plan, "MiningObstruction.plan(player, pos, cleared)", "case CLEAR", "MiningObstruction.logDetected(",
                "return tickClearing(pack, player);", "case PROTECTED", "MiningObstruction.logRefused(",
                "return ActionResult.failed(TARGET_OBSTRUCTED);", "return visibilityRefused(player);");
    }

    @Test
    void theBreakGateStaysTheStrictProofAndThePlanNeverSendsAPacket() throws IOException {
        String controller = read("action/MiningController.java");
        assertTrue(body(controller, "private boolean sendBreakActionIfObserved(").contains("if (!currentObservedTarget(player, pos))"),
                "every packet, a clearing step's included, is gated by the strict proof");
        for (String file : new String[] {"action/MiningObstruction.java", "mode/ReachObstructions.java"}) {
            String source = read(file);
            assertFalse(source.contains("handleBlockBreakAction") || source.contains("gameMode") || source.contains("destroyBlock")
                            || source.contains("setBlock"),
                    file + " only decides what to break; the controller is the sole break actuator");
        }
        assertTrue(read("mode/ReachObstructions.java").contains("SightClip.pick("),
                "the lines are traced with the click's own ray");
        assertTrue(body(read("mode/ObservableWorldQuery.java"), "private static BlockHitResult handClip(").contains("SightClip.pick("),
                "and it is the very ray the strict proofs cast, so a line is clear exactly when the gate passes along it");
    }

    @Test
    void whatMayBeBrokenIsTheModWideBreakRuleAndTheBotsOwnSafety() throws IOException {
        String policy = body(read("action/MiningObstruction.java"),
                "private static String refusalOf(AIPlayerEntity player, Obstruction obstruction, Collection<BlockPos> cleared)");
        assertInOrder(policy, "cleared.contains(obstruction.pos())", "BreakRule.denialOf(obstruction.state())",
                "MiningSafety.supportOccupancy(player, obstruction.pos())", "exposesLava(player, obstruction.pos())",
                "exposesWater(player, obstruction.pos())");
        String reach = body(read("action/MiningObstruction.java"), "private static boolean breakableFromHere(");
        assertTrue(reach.contains("MiningController.currentObservedTarget(player, obstruction.pos())"),
                "a step that the controller would refuse is never planned");
        String neighbours = body(read("action/MiningObstruction.java"), "private static boolean visibleNeighbourHolds(");
        assertInOrder(neighbours, "ObservableWorldQuery.canObserveCell(player, neighbour)", "getFluidState(neighbour)");
        assertTrue(body(read("action/MiningObstruction.java"), "private static boolean exposesLava(").contains("FluidTags.LAVA"));
        assertTrue(body(read("action/MiningObstruction.java"), "private static boolean exposesWater(").contains("FluidTags.WATER, WATER_FEEDS"));
        String source = read("action/MiningObstruction.java");
        String feeds = source.substring(source.indexOf("WATER_FEEDS = {"), source.indexOf("};", source.indexOf("WATER_FEEDS = {")));
        assertFalse(feeds.contains("DOWN"), "water never climbs: the cell below a broken block does not feed it");
    }

    @Test
    void theEventsOfAClearingAreLogged() throws IOException {
        String source = read("action/MiningObstruction.java");
        for (String event : new String[] {"mining_obstruction_detected", "mining_obstruction_cleared", "mining_obstruction_refused"}) {
            assertTrue(source.contains("\"" + event + "\""), event);
        }
    }
}
