package io.github.zoyluo.minecraftai.task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A direct mine request should behave like a player at an empty mountain/snow surface: first
 * inspect what is visible, then use the bounded safe stair for stone or an ore rather than spend
 * the generic horizontal-search budget at the wrong elevation.
 */
final class MineMiningExplorationPrioritySourceContractTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/task/MineTask.java");

    @Test
    void eligibleMineHandsOffToSafeDescentBeforeSurfaceHops() throws IOException {
        String source = Files.readString(SOURCE);
        String search = methodBody(source, "private void search(");

        int visibleScan = search.indexOf("HarvestCore.nearestReachableBlock(");
        int handoff = search.indexOf("if (startMiningExploration(bot))");
        int observedHop = search.indexOf("if (startObservedExploration(bot))");
        assertTrue(visibleScan >= 0 && handoff > visibleScan && observedHop > handoff,
                "an empty local visible scan must hand eligible stone/ore mining to the safe "
                        + "target-Y descent before consuming observed surface hops");

        String descent = methodBody(source, "private boolean startMiningExploration(");
        assertTrue(descent.contains("MiningExplorationTask.supports(targetBlock)")
                        && descent.contains("ToolTier.canHarvestWithInventory")
                        && descent.contains("MiningExplorationTask.forBlocks(Set.of(targetBlock), miningExplorationExcludedCavities)"),
                "the priority handoff must stay allowlisted, tool-gated, and use the safe "
                        + "mining-exploration implementation");
        assertTrue(descent.contains("miningExplorationCaveSurveyRequired")
                        && descent.contains("observedSearchHops.exhausted()")
                        && descent.contains("completedExploreHops <= 0")
                        && descent.contains("miningExplorationCaveRedescents >= MAX_CAVE_REDESCENTS"),
                "a cave completion must consume real observed exploration before its bounded next stair");
    }

    private static String methodBody(String source, String signature) {
        int signatureAt = source.indexOf(signature);
        assertTrue(signatureAt >= 0, () -> "missing method signature: " + signature);
        int open = source.indexOf('{', signatureAt);
        assertTrue(open >= 0, () -> "missing method body: " + signature);
        int depth = 0;
        for (int at = open; at < source.length(); at++) {
            char current = source.charAt(at);
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return source.substring(open, at + 1);
            }
        }
        throw new AssertionError("unterminated method body: " + signature);
    }
}
