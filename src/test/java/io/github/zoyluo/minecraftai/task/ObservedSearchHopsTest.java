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
}
