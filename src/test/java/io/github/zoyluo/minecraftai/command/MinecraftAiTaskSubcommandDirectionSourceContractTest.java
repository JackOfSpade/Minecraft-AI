package io.github.zoyluo.minecraftai.command;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source contract of {@code command/MinecraftAiTaskSubcommand.java}'s {@code direction(...)}
 * parser. It takes a Brigadier {@code CommandContext<CommandSourceStack>}, which cannot be
 * constructed without a full Minecraft bootstrap, so this reads the production source as text
 * instead, like the other source-contract tests in this repo.
 */
class MinecraftAiTaskSubcommandDirectionSourceContractTest {
    private static final Path FILE =
            Path.of("src/main/java/io/github/zoyluo/minecraftai/command/MinecraftAiTaskSubcommand.java");

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
    void upAndUResolveToDirectionUpNotDown() throws IOException {
        String body = method(source(), "private static Direction direction(CommandContext<CommandSourceStack> context) {");

        assertTrue(body.contains("case \"up\", \"u\" -> Direction.UP;"),
                "\"up\"/\"u\" must resolve to Direction.UP, not the previously-inverted Direction.DOWN");
        assertTrue(body.contains("case \"down\", \"d\" -> Direction.DOWN;"),
                "\"down\"/\"d\" must keep resolving to Direction.DOWN, as its own branch");
        assertFalse(body.contains("case \"down\", \"d\", \"up\", \"u\" -> Direction.DOWN;"),
                "the combined, inverted case label must be gone");
    }
}
