package io.github.zoyluo.minecraftai.network;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source contract of {@code network/MinecraftAiServerNetworking.java}. Both handlers here run on
 * the server thread (queued from a network receiver via {@code context.server().execute(...)}) and
 * take real {@code ServerPlayer}/{@code AIPlayerEntity} arguments, so they cannot be
 * exercised without a full Minecraft bootstrap; this reads the production source as text instead,
 * like the other source-contract tests in this repo.
 */
class MinecraftAiServerNetworkingSourceContractTest {
    private static final Path FILE =
            Path.of("src/main/java/io/github/zoyluo/minecraftai/network/MinecraftAiServerNetworking.java");

    private static String source() throws IOException {
        return Files.readString(FILE);
    }

    private static String method(String src, String signature) {
        int start = src.indexOf(signature);
        assertTrue(start >= 0, signature + " must exist");
        int end = src.indexOf("\n    }\n", start);
        assertTrue(end > start, signature + " must end with a method-level closing brace");
        return src.substring(start, end);
    }

    @Test
    void theFileExists() {
        assertTrue(Files.exists(FILE));
    }

    @Test
    void handleSetOptionCatchesRuntimeExceptionsLikeHandleCommandDoes() throws IOException {
        String body = method(source(), "private void handleSetOption(ServerPlayer player, SetOptionC2S payload) {");

        assertTrue(body.contains("try {"), "the unknown_option throw must be covered by a try block");
        assertTrue(body.contains("} catch (RuntimeException exception) {"),
                "an unknown SetOptionC2S.key must not propagate uncaught, matching handleCommand's own pattern");
        assertTrue(body.contains("BotLog.error(target, \"panel_set_option_exception\", exception, \"key\", payload.key())"),
                "the failure must be logged with distinguishing context, like handleCommand's panel_command_exception");
        assertTrue(body.contains("sendSystem(player, payload.botName(), \"Command execution failed: \" + reason)"),
                "the player must get feedback on failure instead of silence, like handleCommand's failure path");

        int tryIndex = body.indexOf("try {");
        int throwIndex = body.indexOf("throw new IllegalArgumentException(\"unknown_option: \" + payload.key());");
        int catchIndex = body.indexOf("} catch (RuntimeException exception) {");
        assertTrue(tryIndex >= 0 && tryIndex < throwIndex && throwIndex < catchIndex,
                "the unknown-option throw must sit between the try and its catch");
    }

    @Test
    void countClampsBothLowAndHighEnds() throws IOException {
        String body = method(source(), "private static int count(BotCommandC2S payload) {");

        assertTrue(body.contains("Math.max(1,"), "the low end must stay clamped to at least 1, as before");
        assertTrue(body.contains("Math.min(MAX_TASK_COUNT, payload.count())"),
                "a client-supplied count must also be capped, so a modified/fuzzing client cannot hand a task an unbounded quantity");
    }

    @Test
    void maxTaskCountIsAFiniteConstant() throws IOException {
        String source = source();
        assertTrue(source.contains("private static final int MAX_TASK_COUNT = 2304;"));
    }

    @Test
    void panelMineDistinguishesAnOmittedCountFromAnExplicitQuota() throws IOException {
        String source = source();

        assertTrue(source.contains("boolean countSpecified = hasCount(payload);"),
                "mine dispatch must preserve whether the panel actually supplied a count");
        assertTrue(source.contains("OreDigTask.collectForDuration(OreScan.oreFamily(block))"),
                "a no-count ore request must use the ten-minute collection mode");
        assertTrue(source.contains("timedResourceMineTask(block)"),
                "a no-count non-ore request must collect its real drops, rather than mine one block");
        assertTrue(source.contains("return payload.count() != 0;"),
                "zero is the backwards-compatible wire sentinel for a count omitted by the panel");
        assertTrue(source.contains("GatherQuotaTask.collectForDuration(drop)"),
                "the timed non-ore path must track the block's actual drop item");
    }

    @Test
    void panelAndCommandFallbackCanSendAnOmittedMiningCount() throws IOException {
        String bridge = Files.readString(Path.of(
                "src/client/java/io/github/zoyluo/minecraftai/client/BotCommandBridge.java"));
        String quickActions = Files.readString(Path.of(
                "src/client/java/io/github/zoyluo/minecraftai/client/screen/ui/cards/QuickActionCard.java"));

        assertTrue(quickActions.contains("countField.setValue(\"\");"),
                "the quick-action count field must start empty instead of silently requesting one item");
        assertTrue(quickActions.contains("if (value.isEmpty()) {\n            return 0;"),
                "an intentionally blank count must reach the command bridge as the omitted-count sentinel");
        assertTrue(bridge.contains("(count == 0 ? \"\" : \" \" + Math.max(1, count))"),
                "the no-network fallback must omit the Mine count argument too");
    }
}
