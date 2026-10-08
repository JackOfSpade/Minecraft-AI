package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** State-only coverage for retiring an observed local goal after its route stalls. */
class ObservedSearchHopsTest {
    @Test
    void retiringALocalGoalLastsForOneSearchEpisodeOnly() {
        ObservedSearchHops hops = new ObservedSearchHops(4);
        BlockPos goal = new BlockPos(32, 152, 6);

        assertFalse(hops.isRetiredObservedGoal(goal));
        hops.retireObservedGoal(goal);
        assertTrue(hops.isRetiredObservedGoal(goal));

        hops.reset();
        assertFalse(hops.isRetiredObservedGoal(goal), "a new resource-search episode may reassess the terrain");
    }

    @Test
    void completedSearchPatchesAreNotReusedByLaterEpisodes() {
        ObservedSearchHops hops = new ObservedSearchHops(4);
        BlockPos landing = new BlockPos(-72, 108, -85);

        hops.rememberExploredLanding(landing);
        assertTrue(hops.isPreviouslyExploredArea(new BlockPos(-72, 105, -88)),
                "a different-height cell in the same slope patch is not a new frontier");
        assertFalse(hops.isPreviouslyExploredArea(new BlockPos(-72, 108, -94)),
                "a genuinely farther observed corridor remains eligible");

        hops.reset();
        assertTrue(hops.isPreviouslyExploredArea(landing),
                "fresh attempt budgets must not erase completed search-patch history");
    }
}
