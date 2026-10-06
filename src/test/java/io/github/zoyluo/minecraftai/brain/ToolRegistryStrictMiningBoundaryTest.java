package io.github.zoyluo.minecraftai.brain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryStrictMiningBoundaryTest {
    @Test
    void retiredMiningToolsAreAbsentInEveryProfile() {
        ToolRegistry registry = new ToolRegistry();
        assertTrue(registry.get("strip_mine").isEmpty());
        assertTrue(registry.get("mine_vein").isEmpty());
        assertTrue(registry.get("mine_ore").isPresent());
        assertTrue(registry.get("assign_task").isPresent());
    }

    @Test
    void noPublicRouteRegistersTheRetiredMiningTasks() throws IOException {
        String registry = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/brain/ToolRegistry.java"));
        String command = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/command/MinecraftAiTaskSubcommand.java"));

        assertFalse(registry.contains("register(\"strip_mine\"") || registry.contains("register(\"mine_vein\""));
        assertFalse(registry.contains("case \"strip_mine\"") || registry.contains("case \"mine_vein\""));
        assertFalse(command.contains("literal(\"strip_mine\"") || command.contains("literal(\"mine_vein\""));
        assertFalse(command.contains("StripMineTask"));
    }

    @Test
    void mineOreRejectsAnUnknownModeInsteadOfMiningOneOreAndHasNoUndeclaredAlias() throws IOException {
        String registry = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/brain/ToolRegistry.java"));

        assertTrue(registry.contains("List.of(\"count\", \"vein\")"), "the valid modes are declared once");
        int check = registry.indexOf("!MINE_ORE_MODES.contains(mineOreMode)");
        int veinBranch = registry.indexOf("if (\"vein\".equals(mineOreMode))");
        int countPath = registry.indexOf("Task task = hasCount ? new OreDigTask(ores, count) : OreDigTask.collectForDuration(ores);");
        assertTrue(check >= 0 && veinBranch > check && countPath > check,
                "an unknown mode must be rejected before either mode assigns anything");
        assertTrue(registry.contains("invalid_mode: mine_ore mode must be one of"), "the error lists the valid modes");
        assertFalse(registry.contains("until_vein_exhausted"),
                "an alias that is not in the tool schema must not be honoured silently");
    }

}
