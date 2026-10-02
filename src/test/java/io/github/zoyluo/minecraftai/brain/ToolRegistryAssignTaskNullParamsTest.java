package io.github.zoyluo.minecraftai.brain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ToolRegistry.registerDefaults() eagerly evaluates Item/Block registry constants when it
 * builds each tool's handler, so a plain JUnit test cannot construct {@code new ToolRegistry()}
 * without a Minecraft bootstrap (see gametest's ToolRegistryMiningGameTests for the bootstrapped
 * equivalent). This asserts the fix as a source-text contract instead, the same way
 * ToolRegistryStrictMiningBoundaryTest pins assign_task's other ordering invariant.
 */
final class ToolRegistryAssignTaskNullParamsTest {
    @Test
    void assignTaskRejectsNullParamsBeforeTheMineOreAndMineSpecialCases() throws IOException {
        String registry = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/brain/ToolRegistry.java"));

        int nullCheck = registry.indexOf("if (params == null) {");
        int mineOreBranch = registry.indexOf("\"mine_ore\".equals(taskType)");
        int mineBranch = registry.indexOf("\"mine\".equals(taskType)");

        assertTrue(nullCheck >= 0 && mineOreBranch >= 0 && mineBranch >= 0,
                "expected markers were not found in ToolRegistry.java");
        assertTrue(nullCheck < mineOreBranch,
                "assign_task must reject a missing/non-object params before the mine_ore special case");
        assertTrue(nullCheck < mineBranch,
                "assign_task must reject a missing/non-object params before the mine special case");
        assertFalse(registry.contains("legacyMiningTaskRejection"),
                "retired mining routes must not leave a legacy rejection branch in assign_task");
    }
}
