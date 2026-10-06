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

        assertTrue(source.contains("startDirectionalPursuitTo(heading, hopDistance(), false, false)"),
                "a remote heading must be resolved by the observation-fenced directional pursuit API");
        assertTrue(source.contains("BlockPos observedGoal = bot.getActionPack().activePathGoal()"),
                "callers must receive the admitted local goal rather than treating the heading as a destination");
        assertTrue(source.contains("if (observedGoal == null)") && source.contains("bot.getActionPack().stopAll();"),
                "a directional admission without an observable local endpoint must be refused and cleaned up");
        assertTrue(source.contains("retiredObservedGoals")
                        && source.contains("directional_hop_retired_observed_goal"),
                "a locally observed goal that previously stalled must not be admitted into a repeat route");
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
        assertTrue(source.contains("ObservableSearchBounds.surveyRadius(configuredRadius)")
                        && source.contains("PROSPECT_STATIONARY_TICK_LIMIT")
                        && source.contains("gather_prospect_deferred_to_explore")
                        && source.contains("gather_observed_search_exhausted"),
                "an empty strict-survival view must skip unobservable wide scans and defer a slow visible prospect to bounded exploration");
        assertTrue(startExplore.contains("observedSearchHops.begin(bot, exploreHint)"),
                "the fallback must request an observation-fenced hop rather than build a terrain waypoint");
        assertTrue(startExplore.contains("nextExploreAdmissionTick")
                        && source.contains("phase == Phase.SURVEY && nextExploreAdmissionTick >= 0")
                        && source.contains("elapsed + EXPLORE_REFUSED_HOP_RETRY_TICKS"),
                "a refused local hop must try its bounded alternate headings directly rather than appearing idle through a re-survey");
        assertFalse(startExplore.contains("findGroundAt(") || startExplore.contains("startPathTo("),
                "starting exploration must not inspect an unseen column or directly route to one");
        assertTrue(startExplore.contains("exploreTarget = attempt.observedGoal()"),
                "the task must retain the API-resolved local goal, never the remote heading");
        assertTrue(exploreMove.contains("bot.blockPosition().equals(exploreTarget)")
                        && exploreMove.contains("!bot.blockPosition().equals(exploreStart)")
                        && exploreMove.contains("gather_explore_arrived"),
                "a completed hop must reach its exact admitted local goal after actual movement");
        assertFalse(exploreMove.contains("distSqr(exploreTarget) <= 9.0D"),
                "a GoalBlock hop must not be cancelled two or three cells early and reissued from the same area");
        assertTrue(exploreMove.contains("explorationProgress.stalled(bot.blockPosition())")
                        && exploreMove.contains("observedSearchHops.retireObservedGoal(stalledGoal)")
                        && exploreMove.contains("gather_explore_stalled"),
                "an active-but-stationary Baritone route must be retired before the coarse route timeout");
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
        String mineRestart = methodBody(mine, "private void restartObservedExploration(");
        assertTrue(mine.contains("restartObservedExploration(bot)")
                        && mineRestart.contains("observedSearchHops.reset()")
                        && mineRestart.contains("phase = Phase.SEARCHING"),
                "direct mining must begin another bounded, observation-fenced episode rather than "
                        + "mistaking its first finite search boundary for resource absence");

        assertTrue(oreDig.contains("new ObservedSearchHops(OBSERVED_SEARCH_MAX_HOPS)")
                        && oreExplore.contains("observedOreSearch.begin(bot, null)"),
                "count-mode ore mining must widen its view through the shared observed-hop primitive");
        assertFalse(oreExplore.contains("startPathTo(") || oreExplore.contains("getBlockState(")
                        || oreExplore.contains("OreProspector.begin("),
                "the replacement ore fallback must not re-enable a direct, hidden, or blind search");
        int nearestOreBoundary = oreDig.indexOf("BlockPos found = nearestOre(bot, world);");
        int emptySurfaceBoundary = oreDig.indexOf("if (RetiredNavigationTask.legacyExcavationDisabled())",
                nearestOreBoundary);
        int descent = oreDig.indexOf("if (startMiningExploration(bot))", emptySurfaceBoundary);
        int observedStart = oreDig.indexOf("if (startObservedOreSearch(bot))", emptySurfaceBoundary);
        int observedFinish = oreDig.indexOf("finishObservedOreSearch(bot);", observedStart);
        assertTrue(nearestOreBoundary >= 0 && emptySurfaceBoundary > nearestOreBoundary
                        && descent > emptySurfaceBoundary && observedStart > descent && observedFinish > observedStart,
                "after an empty observable surface scan, ore mining must try its safe target-Y descent before surface hops");
        assertTrue(oreDig.contains("no_observed_ore_after_exploration")
                        && oreDig.contains("no_observed_ore_in_local_view"),
                "ore failures must report only the searched observation boundary, never inferred terrain absence");

        String oreDescent = methodBody(oreDig, "private boolean startMiningExploration(");
        assertTrue(oreDescent.contains("miningExplorationCaveSurveyRequired")
                        && oreDescent.contains("observedOreSearch.exhausted()")
                        && oreDescent.contains("observedOreSearchCompletedHops <= 0")
                        && oreDescent.contains("miningExplorationCaveRedescents >= MAX_CAVE_REDESCENTS"),
                "an empty observed cave survey may reopen the depth handoff only after actual movement, and only finitely");
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
