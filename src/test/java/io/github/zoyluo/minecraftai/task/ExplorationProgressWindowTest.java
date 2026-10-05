package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** Regression coverage for the active-but-stationary observed-exploration failure mode. */
class ExplorationProgressWindowTest {
    @Test
    void aStationaryActiveRouteExpiresBeforeTheCoarseExploreTimeout() {
        ExplorationProgressWindow window = new ExplorationProgressWindow();
        BlockPos origin = new BlockPos(27, 152, 8);
        window.beginLeg(origin);

        for (int tick = 1; tick < ExplorationProgressWindow.WINDOW_TICKS; tick++) {
            assertFalse(window.stalled(origin), "must remain live until the no-frontier window closes");
        }
        assertTrue(window.stalled(origin), "a non-idle route at one cell must be abandoned after 80 ticks");
    }

    @Test
    void newGroundRearmsButReturningToAnAlreadyVisitedCellDoesNot() {
        ExplorationProgressWindow window = new ExplorationProgressWindow();
        BlockPos origin = new BlockPos(0, 64, 0);
        BlockPos newGround = origin.east();
        window.beginLeg(origin);

        assertFalse(window.stalled(newGround), "reaching a fresh observed cell is real exploration progress");
        assertFalse(window.stalled(origin), "returning to an old cell must not renew the whole route budget");
        for (int tick = 3; tick <= ExplorationProgressWindow.WINDOW_TICKS; tick++) {
            assertFalse(window.stalled(origin), "the loop remains inside the previous frontier");
        }
        assertTrue(window.stalled(origin), "a circle through prior cells must eventually be retired");
    }
}
