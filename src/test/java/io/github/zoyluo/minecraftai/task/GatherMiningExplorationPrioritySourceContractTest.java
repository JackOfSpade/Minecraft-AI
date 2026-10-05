package io.github.zoyluo.minecraftai.task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Empty source searches for mineable material should enter the safe descent before spending the
 * generic surface-hop budget.  This specifically covers a snow/mountain spawn seeking stone or
 * cobblestone: moving across sixteen surface patches cannot reveal the material beneath its feet.
 */
final class GatherMiningExplorationPrioritySourceContractTest {
    private static final Path TASKS = Path.of("src/main/java/io/github/zoyluo/minecraftai/task");

    @Test
    void mineableGatherHandsOffBeforeObservedSurfaceHops() throws IOException {
        String source = Files.readString(TASKS.resolve("GatherQuotaTask.java"));
        String fallback = methodBody(source, "private void escapeBarrenAreaOrFail(");

        int miningHandoff = fallback.indexOf("if (startMiningExploration(bot))");
        int observedHop = fallback.indexOf("if (escapeBarrenArea(bot))");
        assertTrue(miningHandoff >= 0 && observedHop > miningHandoff,
                "an exhausted visible mineable-source search must try the safe descent before generic surface hops");
        assertTrue(fallback.contains("local survey and its observable vertical prospect"),
                "the priority boundary must remain after the local observable search, not replace it");

        String handoff = methodBody(source, "private boolean startMiningExploration(");
        assertTrue(handoff.contains("countBrokenBlocks || !MiningExplorationTask.supports(harvestBlocks)")
                        && handoff.contains("if (miningExplorationAttempted)"),
                "only non-exact, eligible mining gathers may use the descent handoff");
        assertTrue(handoff.contains("GatherToolPolicy.hasTool")
                        && handoff.contains("ToolTier.canHarvestWithInventory"),
                "the early handoff must keep the pickaxe/tier safety gate");
    }

    @Test
    void stoneAndCobblestoneRemainGeologicalSourcesWithoutHiddenScanning() throws IOException {
        String exploration = Files.readString(TASKS.resolve("MiningExplorationTask.java"));
        assertTrue(exploration.contains("Blocks.STONE") && exploration.contains("Blocks.COBBLESTONE"),
                "stone and cobblestone must stay in the explicit geological-source allowlist");
        assertTrue(exploration.contains("MiningChain.shouldDescend(currentY, targetY)")
                        && exploration.contains("DescendToYTask.forMiningExploration(targetY, excludedOpenCavities)"),
                "the handoff must retain the target-Y boundary and safe staircase implementation");
        assertFalse(exploration.contains("getBlockState("),
                "choosing the descent must not inspect unseen terrain");
    }

    @Test
    void aMiningExplorationStaircaseHandsAnObservedOpenCaveBackToItsParent() throws IOException {
        String descent = Files.readString(TASKS.resolve("DescendToYTask.java"));
        int cavityHandoff = descent.indexOf("completeMiningExplorationAtObservedOpenCavity(bot, world, next)");
        int genericSeal = descent.indexOf("trySealOpenCavityLanding(bot, world, feet, next, stairDirIndex)");
        assertTrue(cavityHandoff >= 0 && genericSeal > cavityHandoff,
                "a fresh mining staircase must stop for a visible cave before generic descent seals and bypasses it");

        String handoff = methodBody(descent, "private boolean completeMiningExplorationAtObservedOpenCavity(");
        assertTrue(handoff.contains("if (!miningExplorationChild)")
                        && handoff.contains("canObservePosition(bot, hole)")
                        && handoff.contains("state.getFluidState().isEmpty()")
                        && handoff.contains("state.getCollisionShape(world, hole).isEmpty()")
                        && handoff.contains("isObservedDryStandable(bot, world, hole)")
                        && handoff.contains("complete();"),
                "only a fresh child may stop at a currently observed dry, standable cave entry");
        assertFalse(handoff.contains("BuildAction.placeBlockAt"),
                "the cave handoff must not close the space it is supposed to explore");

        String exploration = Files.readString(TASKS.resolve("MiningExplorationTask.java"));
        assertTrue(exploration.contains("descent.completedOpenCavity()")
                        && exploration.contains("completeCaveSurvey(bot)")
                        && exploration.contains("completedAtOpenCavity()"),
                "the child must report a visible cave completion to its owning mining request");
    }

    @Test
    void caveSurveyRequiresRealMovementBeforeBoundedRedescent() throws IOException {
        String source = Files.readString(TASKS.resolve("GatherQuotaTask.java"));
        String handoff = methodBody(source, "private boolean startMiningExploration(");

        assertTrue(source.contains("MAX_CAVE_REDESCENTS")
                        && handoff.contains("miningExplorationCaveSurveyRequired")
                        && handoff.contains("observedSearchHops.exhausted()")
                        && handoff.contains("exploreHops <= 0")
                        && handoff.contains("miningExplorationCaveRedescents >= MAX_CAVE_REDESCENTS"),
                "a cave rim must be surveyed through actual observed movement before a finite re-descent");
    }

    @Test
    void caveEntryIsWalkedWithoutDiggingAndExcludedFromFreshStairs() throws IOException {
        String descent = Files.readString(TASKS.resolve("DescendToYTask.java"));
        String exploration = Files.readString(TASKS.resolve("MiningExplorationTask.java"));

        String skip = methodBody(descent, "private boolean skipPreviouslySurveyedOpenCavity(");
        assertTrue(skip.contains("excludedOpenCavities.contains(hole)")
                        && skip.contains("rejectLandingDirection(feet, stairDirIndex)")
                        && skip.contains("rotateStair(bot, world, feet)"),
                "a later fresh staircase must rotate past a cave entry already surveyed by this request");
        assertTrue(exploration.contains("startSurfacePathTo(caveSurveyEntry)")
                        && exploration.contains("resumePastUnroutableCave")
                        && exploration.contains("excludedOpenCavities.add(caveSurveyEntry.immutable())"),
                "cave exploration must first take an admitted no-dig route, then safely resume past a refused entry");
        assertFalse(exploration.contains("startPathTo(caveSurveyEntry)"),
                "entering a surveyed cave must not grant a dig-capable path merely to explore it");
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
