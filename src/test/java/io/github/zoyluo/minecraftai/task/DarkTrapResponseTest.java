package io.github.zoyluo.minecraftai.task;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static io.github.zoyluo.minecraftai.task.DangerWatcher.DarkTrapResponse.CRAFT_TORCHES;
import static io.github.zoyluo.minecraftai.task.DangerWatcher.DarkTrapResponse.LIGHT;
import static io.github.zoyluo.minecraftai.task.DangerWatcher.DarkTrapResponse.LIGHTING_OFF;
import static io.github.zoyluo.minecraftai.task.DangerWatcher.DarkTrapResponse.NONE;
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
    void withNeitherTheBotHasNothingToDo() {
        assertEquals(NONE, DangerWatcher.darkTrapResponse(true, 0, false));
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
        String nothing = DangerWatcher.darkTrapReport("Moss", cell, NONE);
        String off = DangerWatcher.darkTrapReport("Moss", cell, LIGHTING_OFF);

        assertEquals("Moss is stuck in the dark at (13,137,-6) and has nothing to light it with.", nothing);
        assertTrue(off.contains("automatic lighting is switched off"), off);
        assertFalse(off.contains("nothing to light it with"), "a bot carrying torches is not out of torches: " + off);
    }

    @Test
    void theSameAnswerForTheSameCellIsNotGivenTwice() {
        BlockPos cell = new BlockPos(13, 137, -6);
        DangerWatcher.DarkTrapAnswer answered = new DangerWatcher.DarkTrapAnswer(cell, NONE);

        assertTrue(answered.repeatedBy(cell, NONE), "still nothing to light it with: nothing new to say");
        assertTrue(answered.repeatedBy(new BlockPos(13, 137, -6), NONE), "the cell is compared by value");
    }

    @Test
    void aChangeOfMeansOrOfCellIsANewSituation() {
        BlockPos cell = new BlockPos(13, 137, -6);
        DangerWatcher.DarkTrapAnswer crafted = new DangerWatcher.DarkTrapAnswer(cell, CRAFT_TORCHES);

        assertFalse(crafted.repeatedBy(cell, LIGHT), "the torches now exist: the next step is to place them");
        assertFalse(crafted.repeatedBy(cell, NONE), "the makings are gone");
        assertFalse(crafted.repeatedBy(cell.above(), CRAFT_TORCHES), "another cell is another trap");
    }

    @Test
    void everyChainOfAnswersEndsBecauseEachStepNeedsAChangeOfMeans() {
        // LIGHT -> (torches used up, still dark) NONE, CRAFT_TORCHES -> LIGHT -> NONE: a cell is answered at most three times.
        BlockPos cell = new BlockPos(0, 40, 0);
        DangerWatcher.DarkTrapResponse[] sequence = {CRAFT_TORCHES, LIGHT, NONE, NONE, NONE};
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
