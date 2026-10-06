package io.github.zoyluo.minecraftai.task;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static io.github.zoyluo.minecraftai.task.DangerWatcher.DarkTrapResponse.CRAFT_TORCHES;
import static io.github.zoyluo.minecraftai.task.DangerWatcher.DarkTrapResponse.DIG_OUT;
import static io.github.zoyluo.minecraftai.task.DangerWatcher.DarkTrapResponse.LIGHT;
import static io.github.zoyluo.minecraftai.task.DangerWatcher.DarkTrapResponse.LIGHTING_OFF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a bot does about a dark trap when the surface teleport is denied, and that it never repeats an answer. */
final class DarkTrapResponseTest {
    @Test
    void aCarriedTorchLightsTheCellWhetherOrNotMoreCouldBeCrafted() {
        assertEquals(LIGHT, DangerWatcher.darkTrapResponse(true, 1, false));
        assertEquals(LIGHT, DangerWatcher.darkTrapResponse(true, 7, true));
    }

    @Test
    void withoutATorchTheMakingsAreCraftedFirst() {
        assertEquals(CRAFT_TORCHES, DangerWatcher.darkTrapResponse(true, 0, true));
    }

    @Test
    void withNeitherTheBotDigsItsWayOutInsteadOfStandingInTheDark() {
        assertEquals(DIG_OUT, DangerWatcher.darkTrapResponse(true, 0, false));
    }

    @Test
    void withAutomaticLightingOffNothingIsDoneWhateverTheBotCarries() {
        assertEquals(LIGHTING_OFF, DangerWatcher.darkTrapResponse(false, 20, true));
        assertEquals(LIGHTING_OFF, DangerWatcher.darkTrapResponse(false, 0, true));
        assertEquals(LIGHTING_OFF, DangerWatcher.darkTrapResponse(false, 0, false));
    }

    @Test
    void theReportNamesTheReasonThatIsTrue() {
        BlockPos cell = new BlockPos(13, 137, -6);
        String digging = DangerWatcher.darkTrapReport("Moss", cell, DIG_OUT);
        String off = DangerWatcher.darkTrapReport("Moss", cell, LIGHTING_OFF);

        assertEquals("Moss is stuck in the dark at (13,137,-6) and has nothing to light it with, "
                + "so it is digging a stair up out of it.", digging);
        assertTrue(off.contains("automatic lighting is switched off"), off);
        assertFalse(off.contains("nothing to light it with"), "a bot carrying torches is not out of torches: " + off);
        assertFalse(off.contains("digging"), "a bot that does nothing must not say it is digging: " + off);
    }

    @Test
    void theSameAnswerForTheSameCellIsNotGivenTwice() {
        BlockPos cell = new BlockPos(13, 137, -6);
        DangerWatcher.DarkTrapAnswer answered = new DangerWatcher.DarkTrapAnswer(cell, DIG_OUT);

        assertTrue(answered.repeatedBy(cell, DIG_OUT), "still nothing to light it with: nothing new to do");
        assertTrue(answered.repeatedBy(new BlockPos(13, 137, -6), DIG_OUT), "the cell is compared by value");
    }

    @Test
    void aChangeOfMeansOrOfCellIsANewSituation() {
        BlockPos cell = new BlockPos(13, 137, -6);
        DangerWatcher.DarkTrapAnswer crafted = new DangerWatcher.DarkTrapAnswer(cell, CRAFT_TORCHES);

        assertFalse(crafted.repeatedBy(cell, LIGHT), "the torches now exist: the next step is to place them");
        assertFalse(crafted.repeatedBy(cell, DIG_OUT), "the makings are gone");
        assertFalse(crafted.repeatedBy(cell.above(), CRAFT_TORCHES), "another cell is another trap");
    }

    @Test
    void everyChainOfAnswersEndsBecauseEachStepNeedsAChangeOfMeans() {
        // LIGHT -> (torches used up, still dark) DIG_OUT, CRAFT_TORCHES -> LIGHT -> DIG_OUT: a cell is answered at most three times.
        BlockPos cell = new BlockPos(0, 40, 0);
        DangerWatcher.DarkTrapResponse[] sequence = {CRAFT_TORCHES, LIGHT, DIG_OUT, DIG_OUT, DIG_OUT};
        DangerWatcher.DarkTrapAnswer last = null;
        int answers = 0;
        for (DangerWatcher.DarkTrapResponse next : sequence) {
            if (last != null && last.repeatedBy(cell, next)) {
                continue;
            }
            last = new DangerWatcher.DarkTrapAnswer(cell, next);
            answers++;
        }
        assertEquals(3, answers);
    }
}
