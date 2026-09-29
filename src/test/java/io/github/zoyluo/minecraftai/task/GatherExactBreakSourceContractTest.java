package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GatherExactBreakSourceContractTest {
    @Test
    void exactBlockBreakingCountsDestructionRatherThanDropsAndStaysLocal() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/task/GatherQuotaTask.java"));

        assertTrue(source.contains("public static GatherQuotaTask breakBlocks(Block block, int targetCount)"));
        assertTrue(source.contains("\"break_blocks\", BuiltInRegistries.BLOCK.getKey(block).toString()"));
        assertTrue(source.contains("countBrokenBlocks ? 0 : countAccepted(bot)"));
        assertTrue(source.contains("countSoFar++"));
        assertTrue(source.contains("if (countBrokenBlocks) {\n                // An exact break request"));
        assertTrue(source.contains("return countBrokenBlocks ? EXACT_BREAK_SEARCH_RADIUS : SEARCH_RADIUS"));
        assertTrue(source.contains("if (countBrokenBlocks) {\n            return false;"));
    }

    @Test
    void theBootstrapPickupWaitIsOnlyOwedForABareHandLogBreakAndTheExclusionIsResetWhenTheBootstrapEnds() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/task/GatherQuotaTask.java"));

        assertTrue(source.contains("} else if (bootstrapActive && handLogBreakInFlight) {"),
                "BOOTSTRAP_PICKUP must only follow a bare-hand break of a log, not any block broken while bootstrapActive");
        int flag = source.indexOf("handLogBreakInFlight = true;");
        int start = source.indexOf("doStartHarvest(bot);", flag);
        assertTrue(flag > 0 && start > flag, "the flag is raised on the bootstrap hand-break path only");
        assertTrue(source.contains("private void endBootstrap(AIPlayerEntity bot) {"));
        assertTrue(source.contains("bootstrapExcluded = Math.min(bootstrapExcluded, rawNewItems(bot));"),
                "a new-items quota keeps excluded only the logs that really arrived while bootstrapping");
        assertTrue(source.contains("bootstrapExcluded = 0;"), "an absolute quota stops discounting when the bootstrap ends");
        assertFalse(source.contains("            bootstrapActive = false;\n            pendingToolCategory = null;"),
                "ensureTool must end the bootstrap through endBootstrap");
    }
}
