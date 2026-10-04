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
                "if (!MiningController.currentObservedTarget(player, pos))",
                "BotLog.action(player, \"mine_refused\"",
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
                "ObservableWorldQuery.canObserveBlockCellFace(player, pos)",
                "ObservableWorldQuery.canObserveBlock(player, pos)",
                "ObservableWorldQuery.canObserveBlockWithInsetFaces(player, pos)");
        String crop = body(source, "private static boolean currentObservedCropTarget(AIPlayerEntity player, BlockPos pos)");
        assertInOrder(crop,
                "ObservableWorldQuery.canObserveFarmCell(player, pos)",
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
                "return ActionResult.SUCCESS;",
                "if (!currentObservedTarget(player, pos))",
                "return visibilityRefused(player);",
                "BlockState state = world.getBlockState(pos);");
        assertFalse(tick.contains("handleBlockBreakAction("),
                "tick must route every START/STOP packet through the live observation gate");
        assertTrue(tick.contains("sendBreakActionIfObserved(player, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK)")
                        && tick.contains("sendBreakActionIfObserved(player, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK)"),
                "both start and completion packets need a fresh proof");

        String packet = body(source, "private boolean sendBreakActionIfObserved(AIPlayerEntity player,");
        assertInOrder(packet,
                "if (!currentObservedTarget(player, pos))",
                "player.gameMode.handleBlockBreakAction(");
        String abort = body(source, "public void abort(AIPlayerEntity player)");
        String reset = body(source, "private void resetProgress(AIPlayerEntity player)");
        assertTrue(abort.contains("sendBreakActionIfObserved(player, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK)")
                        && reset.contains("sendBreakActionIfObserved(player, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK)"),
                "cancellation must not send an unproved ABORT packet");
        assertTrue(source.contains("\"mine_visibility_refused\"") && source.contains("TARGET_NOT_OBSERVED"),
                "a live visibility loss needs an auditable typed refusal");
    }

    @Test
    void blockMinerReprovesBeforeItsStateReadsAndToolChoice() throws IOException {
        String source = read("BlockMiner.java");
        String tick = body(source, "public Status tick(AIPlayerEntity bot)");
        assertInOrder(tick,
                "if (MiningController.visiblyAir(bot, target))",
                "return Status.DONE;",
                "if (!MiningController.currentObservedTarget(bot, target))",
                "return targetNotObserved(bot);",
                "BlockState targetState = world.getBlockState(target);");
        int idle = tick.indexOf("if (bot.getActionPack().isMiningIdle())");
        int reproved = tick.indexOf("if (!MiningController.currentObservedTarget(bot, target))", idle);
        int toolState = tick.indexOf("BlockState equipTarget = world.getBlockState(target);", reproved);
        assertTrue(idle >= 0 && reproved > idle && toolState > reproved,
                "a target retained through the task tick must be re-proven before tool selection");
        assertFalse(tick.contains("world.getBlockState(target).getBlock()"),
                "the diagnostic must use an already guarded state rather than make a hidden extra read");
        assertInOrder(tick,
                "ActionResult startedAction = MiningAction.startMining(bot, target, face);",
                "if (startedAction.isFailed())",
                "failureReason = startedAction.reason();",
                "return Status.FAILED;");
        assertTrue(source.contains("\"miner_target_unobserved\""),
                "checkpoint-backed miner refusals need an action log entry");
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
