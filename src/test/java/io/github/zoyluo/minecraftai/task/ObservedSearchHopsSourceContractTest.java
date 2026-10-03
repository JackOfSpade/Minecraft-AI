package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the distinction between a remembered/compass heading and a route destination.
 * Resource discovery may choose a direction, but strict survival must admit the actual
 * navigation goal from current observation before it moves the bot.
 */
final class ObservedSearchHopsSourceContractTest {
    private static final Path TASKS = Path.of("src/main/java/io/github/zoyluo/minecraftai/task");

    @Test
    void helperTurnsOnlyHeadingsIntoShortObservedNavigationLegs() throws IOException {
        String source = Files.readString(TASKS.resolve("ObservedSearchHops.java"));

        assertTrue(source.contains("startDirectionalPursuitTo(heading, HOP_DISTANCE, false, false)"),
                "a remote heading must be resolved by the observation-fenced directional pursuit API");
        assertTrue(source.contains("BlockPos observedGoal = bot.getActionPack().activePathGoal()"),
                "callers must receive the admitted local goal rather than treating the heading as a destination");
        assertTrue(source.contains("if (observedGoal == null)") && source.contains("bot.getActionPack().stopAll();"),
                "a directional admission without an observable local endpoint must be refused and cleaned up");
        assertTrue(source.contains("private static final int[][] COMPASS"),
                "an empty local view must still have a bounded, generic way to choose the next heading");
        assertFalse(source.contains("getBlockState("),
                "the helper must not inspect hidden terrain while selecting a hop");
        assertFalse(source.contains("findGroundAt(") || source.contains("startPathTo("),
                "the helper must not synthesize a remote terrain landing point or direct route");
    }

    @Test
    void gatherUsesObservedHopsAndReportsOnlyWhatItCouldObserve() throws IOException {
        String source = Files.readString(TASKS.resolve("GatherQuotaTask.java"));
        String startExplore = methodBody(source, "private boolean startExplore(");
        String exploreMove = methodBody(source, "private void exploreMove(");

        assertTrue(source.contains("new ObservedSearchHops(EXPLORE_MAX_HOPS)"),
                "gather should share the generic finite exploration primitive");
        assertTrue(source.contains("OreProspector.beginObservable("),
                "resource surveys must retain an observable-only prospect scan");
        assertFalse(source.contains("OreProspector.begin("),
                "the hidden-world prospect scan must not be reintroduced into gather");
        assertTrue(startExplore.contains("observedSearchHops.begin(bot, exploreHint)"),
                "the fallback must request an observation-fenced hop rather than build a terrain waypoint");
        assertFalse(startExplore.contains("findGroundAt(") || startExplore.contains("startPathTo("),
                "starting exploration must not inspect an unseen column or directly route to one");
        assertTrue(startExplore.contains("exploreTarget = attempt.observedGoal()"),
                "the task must retain the API-resolved local goal, never the remote heading");
        assertTrue(exploreMove.contains("bot.blockPosition().distSqr(exploreStart) > 9.0D")
                        && exploreMove.contains("gather_explore_arrived"),
                "a completed hop must be based on actual movement, not an attempted route");
        assertTrue(source.contains("no_observed_resource_after_exploration")
                        && source.contains("no_observed_resource_in_local_view"),
                "failure reporting must distinguish exhausted observed exploration from a local view, "
                        + "rather than asserting an unobserved resource absence");
    }

    @Test
    void directMineAndOreCountTasksReuseTheObservedSearchRatherThanBlindExcavation() throws IOException {
        String mine = Files.readString(TASKS.resolve("MineTask.java"));
        String oreDig = Files.readString(TASKS.resolve("OreDigTask.java"));
        String mineExplore = methodBody(mine, "private boolean startObservedExploration(");
        String oreExplore = methodBody(oreDig, "private boolean startObservedOreSearch(");

        assertTrue(mine.contains("new ObservedSearchHops(EXPLORE_MAX_HOPS)")
                        && mineExplore.contains("observedSearchHops.begin(bot, null)"),
                "a direct mine request needs the same generic recovery after its first local scan");
        assertFalse(mineExplore.contains("startPathTo(") || mineExplore.contains("getBlockState("),
                "direct mining must not turn an unseen material guess into a route or terrain read");
        assertTrue(mine.contains("no_observed_ore_after_exploration")
                        && mine.contains("no_observed_resource_after_exploration"),
                "direct mining should end at an observation boundary after the finite search");

        assertTrue(oreDig.contains("new ObservedSearchHops(OBSERVED_SEARCH_MAX_HOPS)")
                        && oreExplore.contains("observedOreSearch.begin(bot, null)"),
                "count-mode ore mining must widen its view through the shared observed-hop primitive");
        assertFalse(oreExplore.contains("startPathTo(") || oreExplore.contains("getBlockState(")
                        || oreExplore.contains("OreProspector.begin("),
                "the replacement ore fallback must not re-enable a direct, hidden, or blind search");
        int retiredBoundary = oreDig.indexOf("if (RetiredNavigationTask.legacyExcavationDisabled())");
        int observedStart = oreDig.indexOf("if (startObservedOreSearch(bot))", retiredBoundary);
        int observedFinish = oreDig.indexOf("finishObservedOreSearch(bot);", observedStart);
        assertTrue(retiredBoundary >= 0 && observedStart > retiredBoundary && observedFinish > observedStart,
                "the old disabled excavation boundary must now try finite observed discovery before it reports failure");
        assertTrue(oreDig.contains("no_observed_ore_after_exploration")
                        && oreDig.contains("no_observed_ore_in_local_view"),
                "ore failures must report only the searched observation boundary, never inferred terrain absence");
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
