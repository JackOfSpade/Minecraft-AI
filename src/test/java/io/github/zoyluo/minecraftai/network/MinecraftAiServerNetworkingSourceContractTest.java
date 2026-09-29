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
}
