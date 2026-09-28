package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class GatherExactBreakSourceContractTest {
    @Test
    void exactBlockBreakingCountsDestructionRatherThanDropsAndStaysLocal() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/task/GatherQuotaTask.java"));

        assertTrue(source.contains("public static GatherQuotaTask breakBlocks(Block block, int targetCount)"));
        assertTrue(source.contains("\"break_blocks\", Registries.BLOCK.getId(block).toString()"));
        assertTrue(source.contains("countBrokenBlocks ? 0 : countAccepted(bot)"));
        assertTrue(source.contains("countSoFar++"));
        assertTrue(source.contains("if (countBrokenBlocks) {\n                // An exact break request"));
        assertTrue(source.contains("return countBrokenBlocks ? EXACT_BREAK_SEARCH_RADIUS : SEARCH_RADIUS"));
        assertTrue(source.contains("if (countBrokenBlocks) {\n            return false;"));
    }
}
