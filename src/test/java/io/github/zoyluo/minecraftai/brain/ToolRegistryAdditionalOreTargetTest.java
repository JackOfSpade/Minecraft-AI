package io.github.zoyluo.minecraftai.brain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryAdditionalOreTargetTest {
    private static final Path REGISTRY = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/brain/ToolRegistry.java");

    @Test
    void incrementalOreRequestCapturesTheHeldDropBaseline() throws IOException {
        String registry = Files.readString(REGISTRY);

        assertTrue(registry.contains("int heldDrops = HarvestCore.countInventoryItems(bot, HarvestCore.expectedDropsFor(ores));"));
        assertTrue(registry.contains("return Goal.MineOre.additional(ores, requestedDrops, heldDrops);"),
                "one held coal plus a request to mine one must remain a one-drop mission with a one-coal baseline");
    }

    @Test
    void everyPlayerFacingOreGoalUsesTheIncrementalBoundaryAdapter() throws IOException {
        String registry = Files.readString(REGISTRY);

        assertTrue(registry.contains("additionalOreGoal(bot, ores, count)"),
                "mine_ore, mine_and_stockpile, and assign_task mine_ore must preserve new-drop semantics");
        assertTrue(registry.contains("additionalOreGoal(bot, resumeOres, optionalInt(args, \"count\", 1))"),
                "resume_mining promises an additional quota");
        assertTrue(registry.contains("additionalOreGoal(bot, OreScan.oreFamily(block), count)"),
                "assign_task mine of an ore must not revert to absolute inventory semantics");
        assertTrue(registry.contains("NEW ore drops"),
                "the public tool contract must describe the behavior that is enforced");
    }

    @Test
    void omittedOreCountsSelectTheTimedExploratoryModeInsteadOfAnInventoryShortcut() throws IOException {
        String registry = Files.readString(REGISTRY);

        assertTrue(registry.contains("boolean hasCount = args.has(\"count\");"));
        assertTrue(registry.contains("hasCount ? additionalOreGoal(bot, ores, count) : timedOreGoal(bot, ores)"));
        assertTrue(registry.contains("hasCount ? new OreDigTask(ores, count) : OreDigTask.collectForDuration(ores)"));
        assertTrue(registry.contains("Goal.MineOre.timedCollection(ores, heldDrops)"),
                "the timed goal retains a held-item baseline for reporting, never for instant completion");
        assertTrue(registry.contains("for up to ten minutes"));
    }
}
