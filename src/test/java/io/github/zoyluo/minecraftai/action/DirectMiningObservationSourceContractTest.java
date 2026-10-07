package io.github.zoyluo.minecraftai.action;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Direct mining is intentionally narrower than an observed Baritone route: a caller may name one
 * exposed block, but the retained coordinate can never become authority to inspect, tool-select,
 * or send a break packet for hidden terrain. A touching fire or powder-snow cell is the narrow
 * direct-body exception. These source contracts pin the state-free-first order at all three
 * public/compatibility entry points.
 */
class DirectMiningObservationSourceContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/minecraftai/action");

    @Test
    void actionPackRejectsUnobservedTargetsBeforeItClaimsOrCreatesAMiner() throws IOException {
        String start = body(read("ActionPack.java"), "public ActionResult startMining(BlockPos pos, Direction face)");
        assertInOrder(start,
                "if (controllerStartBlocked())",
                "if (pos == null || face == null)",
                "MiningObstruction.Plan admission = MiningController.admission(player, pos);",
                "if (admission.refused())",
                "BotLog.action(player, \"mine_refused\"",
                "MiningSafety.SupportOccupancy support = MiningSafety.supportOccupancy(player, pos);",
                "if (support != MiningSafety.SupportOccupancy.NONE)",
                "MiningSafety.refusalReason(support)",
                "claim(\"mining\")",
                "this.mining = new MiningController(pos, face);");
        assertFalse(start.contains("getBlockState("),
                "admission must not inspect target state itself; MiningController owns the guarded read");
    }

    @Test
    void controllerUsesAStateFreeFaceProofBeforeAnyStateAndBeforeEachPacket() throws IOException {
        String source = read("MiningController.java");
        String observation = body(source, "static boolean currentObservedTarget(AIPlayerEntity player, BlockPos pos)");
        assertInOrder(observation,
                "ownBodyEmergencyBlock(player, pos)",
                "ObservableWorldQuery.canObserveBlockCellFaceStrict(player, pos)",
                "ObservableWorldQuery.canObserveCellStrict(player, pos)",
                "ObservableWorldQuery.canObserveBlockStrict(player, pos)",
                "ObservableWorldQuery.canObserveBlockWithInsetFacesStrict(player, pos)");
        assertTrue(observation.contains("ObservableWorldQuery.canObserveBlockCellFaceStrict(player, pos)\n"
                        + "                || ObservableWorldQuery.canObserveCellStrict(player, pos)"),
                "a partial but exposed block needs the ordinary state-free cell ray when it cannot reach a unit-cell face");
        assertFalse(observation.matches("(?s).*canObserve(Block|BlockCellFace|Cell|BlockWithInsetFaces|FarmCell)\\(.*"),
                "the break gate is the reach gate: it asks the strict (vanilla clip) predicates, never the see-through sight ones, "
                        + "so a log seen behind a leaf is not mined through the leaf");
        String crop = body(source, "private static boolean currentObservedCropTarget(AIPlayerEntity player, BlockPos pos)");
        assertInOrder(crop,
                "ObservableWorldQuery.canObserveFarmCellStrict(player, pos)",
                "player.level().getBlockState(pos)",
                "instanceof CropBlock");
        String bodyEmergency = body(source, "private static boolean ownBodyEmergencyBlock(AIPlayerEntity player, BlockPos pos)");
        assertInOrder(bodyEmergency,
                "player.getBoundingBox().deflate(0.001D).intersects(",
                "BlockState state = player.level().getBlockState(pos);");
        assertTrue(bodyEmergency.contains("Blocks.FIRE")
                        && bodyEmergency.contains("Blocks.SOUL_FIRE")
                        && bodyEmergency.contains("Blocks.POWDER_SNOW")
                        && bodyEmergency.contains("state.getCollisionShape(player.level(), pos).isEmpty()")
                        && bodyEmergency.contains("state.getDestroySpeed(player.level(), pos) >= 0.0F")
                        && bodyEmergency.contains("BreakRule.denialOf(state) == null"),
                "a direct-body solid escape must be colliding, breakable natural terrain; protected or unseen cells remain refused");
        String air = body(source, "static boolean visiblyAir(AIPlayerEntity player, BlockPos pos)");
        assertInOrder(air,
                "ObservableWorldQuery.canObserveCell(player, pos)",
                "player.level().getBlockState(pos).isAir()");
        assertFalse(air.contains("ToolSelector") || air.contains("handleBlockBreakAction"),
                "the visible-air exception may settle completion only, never choose a tool or break a block");

        String tick = body(source, "public ActionResult tick(ActionPack pack)");
        assertInOrder(tick,
                "if (visiblyAir(player, pos))",
                "return settleVisibleAir(player);",
                "if (clearing != null)",
                "if (!currentObservedTarget(player, pos))",
                "clearsObstructions && !started ? clearTheWay(pack, player) : visibilityRefused(player);",
                "MiningSafety.SupportOccupancy support = footingOccupancy(player);",
                "return supportRefused(player, support);",
                "BlockState state = world.getBlockState(pos);");
        assertFalse(tick.contains("handleBlockBreakAction("),
                "tick must route every START/STOP packet through the live observation gate");
        assertTrue(tick.contains("sendBreakActionIfObserved(player, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK)")
                        && tick.contains("sendBreakActionIfObserved(player, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK)"),
                "both start and completion packets need a fresh proof");

        String packet = body(source, "private boolean sendBreakActionIfObserved(AIPlayerEntity player,");
        assertInOrder(packet,
                "action != ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK",
                "visiblyAir(player, pos)",
                "if (!currentObservedTarget(player, pos))",
                "footingOccupancy(player)",
                "player.gameMode.handleBlockBreakAction(");
        // Only the bot's own footing can ever be released, and only by the break TowerDescent starts.
        String footing = body(source, "private MiningSafety.SupportOccupancy footingOccupancy(AIPlayerEntity player)");
        assertTrue(footing.contains("MiningSafety.supportOccupancy(player, pos)")
                        && footing.contains("releasesOwnFooting && support == MiningSafety.SupportOccupancy.SELF")
                        && !footing.contains("SupportOccupancy.PLAYER"),
                "another player's footing must stay protected whatever the controller is allowed to release");
        String abort = body(source, "public void abort(AIPlayerEntity player)");
        String reset = body(source, "private void resetProgress(AIPlayerEntity player)");
        assertTrue(abort.contains("sendBreakActionIfObserved(player, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK)")
                        && reset.contains("sendBreakActionIfObserved(player, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK)"),
                "cancellation must not send an unproved ABORT packet");
        assertTrue(source.contains("\"mine_visibility_refused\"") && source.contains("TARGET_NOT_OBSERVED"),
                "a live visibility loss needs an auditable typed refusal");
        assertTrue(source.contains("\"mine_support_refused\"")
                        && source.contains("MiningSafety.refusalReason(support)"),
                "a live support transition must end the controller with a typed refusal");

        String safety = read("MiningSafety.java");
        assertTrue(safety.contains("actor.level().players()")
                        && safety.contains("BlockCollisions<Boolean>")
                        && safety.contains("target.equals(position)")
                        && safety.contains("while (supports.hasNext())")
                        && safety.contains("SupportOccupancy.PLAYER")
                        && safety.contains("SELF_SUPPORT")
                        && safety.contains("PLAYER_SUPPORT"),
                "support protection must scan every live collision shape under a body, including slabs and straddled floors");
    }

    @Test
    void blockMinerReprovesBeforeItsStateReadsAndToolChoice() throws IOException {
        String source = read("BlockMiner.java");
        String tick = body(source, "public Status tick(AIPlayerEntity bot)");
        assertInOrder(tick,
                "MiningSafety.SupportOccupancy initialSupport = MiningSafety.supportOccupancy(bot, target);",
                "return handleSupportOccupancy(bot, initialSupport);",
                "if (MiningController.visiblyAir(bot, target))",
                "return Status.DONE;",
                "MiningObstruction.Plan admission = MiningController.admission(bot, target);",
                "if (admission.refused())",
                "return targetNotObserved(bot, admission);",
                "BlockState targetState = world.getBlockState(target);");
        int idle = tick.indexOf("if (bot.getActionPack().isMiningIdle())");
        int support = tick.indexOf("MiningSafety.SupportOccupancy admissionSupport = MiningSafety.supportOccupancy(bot, target);", idle);
        int reproved = tick.indexOf("MiningObstruction.Plan reproved = MiningController.admission(bot, target);", support);
        int toolState = tick.indexOf("BlockState equipTarget = world.getBlockState(target);", support);
        assertTrue(idle >= 0 && support > idle && reproved > support && toolState > reproved,
                "a target retained through the task tick must be checked for new live footing and re-proven before tool selection");
        assertFalse(tick.contains("world.getBlockState(target).getBlock()"),
                "the diagnostic must use an already guarded state rather than make a hidden extra read");
        assertInOrder(tick,
                "ActionResult startedAction = MiningAction.startMining(bot, target, face);",
                "if (startedAction.isFailed())",
                "failureReason = startedAction.reason();",
                "return Status.FAILED;");
        assertTrue(source.contains("\"miner_target_unobserved\""),
                "checkpoint-backed miner refusals need an action log entry");
        String sidestep = body(source, "private Status handleSupportOccupancy(AIPlayerEntity bot,");
        assertInOrder(sidestep,
                "support == MiningSafety.SupportOccupancy.PLAYER",
                "MiningSafety.PLAYER_SUPPORT",
                "Direction.Plane.HORIZONTAL",
                "canObserveSidestepEnvelope(bot, side)",
                "WalkedStep.Kind.FLAT",
                "runStep(",
                "\"miner_self_support_step_aside\"");
        assertTrue(source.contains("\"miner_self_support_moved_aside\"")
                        && source.contains("MiningSafety.SELF_SUPPORT"),
                "the miner must release its own footing before retrying, while a player-held support remains unavailable");
        String begin = body(source, "public void begin(AIPlayerEntity bot, BlockPos pos, boolean miningChannelToolPolicy)");
        assertTrue(begin.contains("started || selfSupportMoveLease != null"),
                "repeated finite-target submissions must preserve an admitted self-support sidestep until it settles");
    }

    private static String read(String name) throws IOException {
        return Files.readString(MAIN.resolve(name));
    }

    private static String body(String source, String signature) {
        int signatureAt = source.indexOf(signature);
        assertTrue(signatureAt >= 0, () -> "missing method signature: " + signature);
        int open = source.indexOf('{', signatureAt);
        assertTrue(open >= 0, () -> "missing method body: " + signature);
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
}
