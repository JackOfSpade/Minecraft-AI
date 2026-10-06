package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Regression coverage for a leaf/trunk first-hit directly above or below the gather bot. */
final class GatherVerticalLandmarkTest {
    @Test
    void sameColumnLandmarksNeverSupplyADirectionalHeading() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/task/GatherQuotaTask.java"));
        String heading = methodBody(source, "static boolean hasHorizontalLandmarkHeading(");
        String sameColumn = methodBody(source, "private static boolean sameHorizontalColumn(");

        // Keep this bootstrap-free: loading GatherQuotaTask initializes vanilla Items, which unit
        // tests deliberately avoid before the Minecraft registry bootstrap. The source contract
        // still locks the exact geometry predicate used by both pursuit guards.
        assertTrue(heading.contains("feet != null && landmark != null && !sameHorizontalColumn(feet, landmark)"));
        assertTrue(sameColumn.contains("first.getX() == second.getX()")
                        && sameColumn.contains("first.getZ() == second.getZ()"),
                "a vertical leaf/log shares both horizontal coordinates and therefore has no heading");
    }

    @Test
    void verticalHintIsRetainedForPillarRecoveryInsteadOfRetriedAsAPursuit() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/task/GatherQuotaTask.java"));
        String scan = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/task/TreeHorizonScan.java"));
        String seek = methodBody(source, "private boolean seekVisibleTree(");
        String pursuit = methodBody(source, "private boolean startTreeSightingPursuit(");
        String pillar = methodBody(source, "private boolean tryPillarApproach(");
        String directHint = methodBody(source, "private HarvestCore.PillarApproach pillarApproachForVerticalHint(");
        String scanStep = methodBody(scan, "Sighting step(");

        int retainedHintCheck = seek.indexOf("!hasHorizontalLandmarkHeading(bot.blockPosition(), treeSightingHint)");
        int nextHorizonScan = seek.indexOf("treeHorizonScan.step(bot)");
        assertTrue(retainedHintCheck >= 0 && retainedHintCheck < nextHorizonScan,
                "a stationary same-column hint must stop the repeat vertical horizon ray before a new pursuit");
        assertTrue(pursuit.contains("gather_tree_sighting_vertical_handoff")
                        && pursuit.contains("visible_landmark_no_horizontal_heading")
                        && pursuit.contains("return false;"),
                "both proactive and defensive no-heading cases must hand off to pillar recovery without clearing the hint");
        int directHintFirst = pillar.indexOf("pillarApproachForVerticalHint(bot)");
        int broadCursor = pillar.indexOf("HarvestCore.beginNearestPillarApproachScan");
        assertTrue(directHintFirst >= 0 && broadCursor > directHintFirst,
                "a re-proven vertical log must be admitted before the broad pillar cursor can delay it");
        assertTrue(directHint.contains("EpisodeMemory.INSTANCE.isExcluded")
                        && directHint.contains("HarvestCore.pillarApproachFor(bot, hint, harvestBlocks)"),
                "the direct handoff must honor unreachable exclusions and re-prove an exact requested block");
        int sharedReturn = scanStep.indexOf("if (remembered != null)");
        int cadenceAdvance = scanStep.indexOf("steps++;", sharedReturn);
        int returnRemembered = scanStep.indexOf("return remembered;", sharedReturn);
        assertTrue(sharedReturn >= 0 && cadenceAdvance > sharedReturn && cadenceAdvance < returnRemembered,
                "a shared-sight hit must advance the horizon cadence instead of returning the same sighting every tick");
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
