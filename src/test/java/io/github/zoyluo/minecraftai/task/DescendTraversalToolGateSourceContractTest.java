package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for a surface snow descent: transit blocks must not be misreported as a
 * missing pickaxe (which used to surface as need_better_tool:minecraft:air).
 */
class DescendTraversalToolGateSourceContractTest {
    private static final Path SOURCE = Path.of("src/main/java/io/github/zoyluo/minecraftai/task/DescendToYTask.java");

    @Test
    void onlyActualPickaxeTerrainUsesTheDescentToolGate() throws IOException {
        String source = Files.readString(SOURCE);

        // Ordinary JUnit deliberately leaves Minecraft's registry unbootstrapped. Pin the exact
        // classification rule here; the production task applies it to live BlockState instances.
        assertTrue(source.contains("!state.isAir()"));
        assertTrue(source.contains("state.getFluidState().isEmpty()"));
        assertTrue(source.contains("ToolTier.requiredPickaxeTier(state.getBlock()) != ToolTier.NONE"));
    }

    @Test
    void descentUsesTheTransitAwareGateForBothStairAndDetourClearing() throws IOException {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("private static boolean canClearForDescent(AIPlayerEntity bot, BlockState state)"));
        assertTrue(source.contains("!requiresPickaxeForDescent(state) || ToolTier.canHarvestWithInventory(bot, state)"));
        assertTrue(source.contains("static boolean requiresPickaxeForDescent(BlockState state)"));
        assertEquals(2, occurrences(source, "BlockState solidState = world.getBlockState(solid);"),
                "each gate must make one coherent terrain observation for its verdict and error");
        assertEquals(2, occurrences(source, "canClearForDescent(bot, solidState)"),
                "both the main stair and lateral-detour tool gates must use the transit-aware rule");
    }

    @Test
    void failedMiningAttemptsAreNeverRetriedAsTheSameStairOrDetour() throws IOException {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("recoverFailedMiningAttempt(bot, world, feet, activeMineTarget, reason);"));
        assertTrue(source.contains("recoverFailedMiningAttempt(bot, world, feet, solid, reason);"));
        assertTrue(source.contains("rejectLandingDirection(feet, stairDirIndex);"));
        assertTrue(source.contains("descend_mine_failed at_y="));
        assertTrue(source.contains("failedStepEdges.add(edge);"));
        assertTrue(source.contains("descend_detour_mining_failed"));
    }

    @Test
    void delayedDetourMiningFailureRetainsAndPoisonsItsOriginalEdge() throws IOException {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("private ActiveMiningAttempt activeMiningAttempt;"));
        assertTrue(source.contains("new ActiveMiningAttempt(target, detourEdge, detourDirectionIndex)"));
        assertTrue(source.contains("activeAttempt.detourEdge()"));
        assertTrue(source.contains("failedStepEdges.add(activeAttempt.detourEdge());"));
        assertTrue(source.contains("rejectLandingDirection(activeAttempt.detourEdge().origin(), activeAttempt.detourDirectionIndex());"));
        assertTrue(source.contains("BlockMiner.MINING_PREEMPTED.equals(reason)"));
        assertTrue(source.contains("ActionPack.GUARDED_STEP_FENCE.equals(reason)"));
        assertTrue(source.contains("bot.getActionPack().hasActiveActions()"));
        assertTrue(source.contains("!bot.getActionPack().isPathExecutorIdle()"));
        assertTrue(source.contains("bot.getActionPack().stepAdmissionBlocked()"));
        assertTrue(source.contains("clearActiveMiningAttempt(recoveryTarget);"));
        assertTrue(source.contains("clearActiveMiningAttempt(blocked);"));
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        int from = 0;
        while ((from = source.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }
}
