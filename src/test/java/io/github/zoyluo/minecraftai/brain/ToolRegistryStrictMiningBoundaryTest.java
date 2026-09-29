package io.github.zoyluo.minecraftai.brain;

import io.github.zoyluo.minecraftai.mode.OperatingProfile;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryStrictMiningBoundaryTest {
    @Test
    void strictProfileDoesNotPublishLegacyMiningTools() {
        assertFalse(ToolRegistry.publishTool(OperatingProfile.STRICT_SURVIVAL, "strip_mine"));
        assertFalse(ToolRegistry.publishTool(OperatingProfile.STRICT_SURVIVAL, "mine_vein"));
        assertFalse(ToolRegistry.publishTool(null, "strip_mine"));
        assertTrue(ToolRegistry.publishTool(OperatingProfile.STRICT_SURVIVAL, "mine_ore"));
        assertTrue(ToolRegistry.publishTool(OperatingProfile.STRICT_SURVIVAL, "assign_task"));

        assertTrue(ToolRegistry.publishTool(OperatingProfile.OPERATOR, "strip_mine"));
        assertTrue(ToolRegistry.publishTool(OperatingProfile.OPERATOR, "mine_vein"));
    }

    @Test
    void everyPublicRouteRejectsBeforeReplacingTheCurrentTask() throws IOException {
        String registry = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/brain/ToolRegistry.java"));
        String command = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/command/MinecraftAiTaskSubcommand.java"));

        assertTrue(occurrences(registry, "legacyMiningTaskRejection(\"") >= 2,
                "direct strip_mine and mine_vein handlers must both reject strict mode");
        int assignTaskGate = registry.indexOf("legacyMiningTaskRejection(taskType)");
        int createTask = registry.indexOf("Task task = createTask(bot, taskType, params)");
        assertTrue(assignTaskGate >= 0 && createTask > assignTaskGate,
                "assign_task must reject legacy mining before task assignment");
        assertTrue(occurrences(command, "requireLegacyMiningProfile();") == 2,
                "both player command routes must reject before constructing legacy mining work");
    }

    @Test
    void mineOreRejectsAnUnknownModeInsteadOfMiningOneOreAndHasNoUndeclaredAlias() throws IOException {
        String registry = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/brain/ToolRegistry.java"));

        assertTrue(registry.contains("List.of(\"count\", \"vein\")"), "the valid modes are declared once");
        int check = registry.indexOf("!MINE_ORE_MODES.contains(mineOreMode)");
        int veinBranch = registry.indexOf("if (\"vein\".equals(mineOreMode))");
        int countPath = registry.indexOf("Task task = new OreDigTask(oreTargetsFrom(requiredString(args, \"ore\")), optionalInt(args, \"count\", 1));");
        assertTrue(check >= 0 && veinBranch > check && countPath > check,
                "an unknown mode must be rejected before either mode assigns anything");
        assertTrue(registry.contains("invalid_mode: mine_ore mode must be one of"), "the error lists the valid modes");
        assertFalse(registry.contains("until_vein_exhausted"),
                "an alias that is not in the tool schema must not be honoured silently");
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
