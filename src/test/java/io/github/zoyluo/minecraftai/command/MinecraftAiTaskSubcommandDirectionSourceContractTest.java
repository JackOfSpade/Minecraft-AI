package io.github.zoyluo.minecraftai.command;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source contract for retiring {@code command/MinecraftAiTaskSubcommand.java}'s mining-only
 * {@code direction(...)} parser. It takes a Brigadier {@code CommandContext<CommandSourceStack>},
 * which cannot be constructed without a full Minecraft bootstrap, so this reads the production
 * source as text instead, like the other source-contract tests in this repo.
 */
class MinecraftAiTaskSubcommandDirectionSourceContractTest {
    private static final Path FILE =
            Path.of("src/main/java/io/github/zoyluo/minecraftai/command/MinecraftAiTaskSubcommand.java");

    private static String source() throws IOException {
        return Files.readString(FILE);
    }

    @Test
    void theFileExists() {
        assertTrue(Files.exists(FILE));
    }

    @Test
    void retiredMiningDirectionParserIsAbsent() throws IOException {
        String source = source();

        assertFalse(source.contains("private static Direction direction(CommandContext<CommandSourceStack> context)"),
                "the retired strip-mine command must not retain a direction parser");
        assertFalse(source.contains("StringArgumentType.getString(context, \"direction\")"),
                "no public task command may still consume the retired mining direction argument");
    }
}
