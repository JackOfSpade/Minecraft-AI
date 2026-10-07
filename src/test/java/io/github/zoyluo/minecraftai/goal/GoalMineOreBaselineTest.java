package io.github.zoyluo.minecraftai.goal;

import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalMineOreBaselineTest {
    @Test
    void capturedInventoryChangesThePhysicalTargetButNotTheMissionQuota() {
        Goal.MineOre additional = new Goal.MineOre(Set.of(), 1, 63);

        assertEquals(1, additional.count(), "the requested mine quota remains one drop");
        assertEquals(63, additional.initialDropCount());
        assertEquals(64, additional.targetDropCount());
        assertEquals(0, additional.deliveredFromInitial(63));
        assertEquals(1, additional.deliveredFromInitial(64));
        assertEquals(1, additional.deliveredFromInitial(99),
                "unrelated surplus cannot advance the declared mission quota");

        Goal.MineOre legacy = new Goal.MineOre(Set.of(), 64);
        assertEquals(0, legacy.initialDropCount());
        assertEquals(64, legacy.targetDropCount(),
                "the two-argument constructor remains the legacy absolute target");
    }

    @Test
    void baselineCannotOverflowThePhysicalPostcondition() {
        assertThrows(IllegalArgumentException.class,
                () -> new Goal.MineOre(Set.of(), 1, Integer.MAX_VALUE));
    }

    @Test
    void onlyAPublicRequestIsMarkedIncremental() {
        assertTrue(Goal.MineOre.additional(Set.of(), 1, 0).incremental(),
                "a request made while holding nothing is still incremental: a zero baseline cannot say so");
        assertTrue(Goal.MineOre.timedCollection(Set.of(), 5).incremental());

        assertFalse(new Goal.MineOre(Set.of(), 4).incremental(), "the two-argument form is absolute");
        assertFalse(new Goal.MineOre(Set.of(), 1, 63).incremental(),
                "a baseline passed to a constructor is not the marker");
        assertFalse(Goal.MineOre.timedCollection(Set.of()).incremental(),
                "a timed collection with no baseline is an explicit zero, not a snapshot");
    }

    @Test
    void promotionRemeasuresAnIncrementalRequestFromTheInventoryItStartsWith() {
        Goal.MineOre queued = Goal.MineOre.additional(Set.of(), 3, 2);

        Goal.MineOre promoted = queued.rebaselined(20);

        assertEquals(20, promoted.initialDropCount(), "what the mission ahead mined is not new for this request");
        assertEquals(3, promoted.count());
        assertEquals(23, promoted.targetDropCount());
        assertEquals(0, promoted.deliveredFromInitial(20));
        assertEquals(3, promoted.deliveredFromInitial(23));
        assertTrue(promoted.incremental(), "a promoted request stays incremental");

        assertEquals(0, queued.rebaselined(0).initialDropCount(),
                "what the mission ahead consumed is not owed back");
        assertEquals(0, queued.rebaselined(-5).initialDropCount());
    }

    @Test
    void promotionNeverTurnsAnAbsoluteGoalIntoAnIncrementalOne() {
        Goal.MineOre absolute = new Goal.MineOre(Set.of(), 8);
        assertSame(absolute, absolute.rebaselined(20),
                "a quota meant as a total keeps its zero baseline, however much is carried");

        Goal.MineOre absoluteWithBaseline = new Goal.MineOre(Set.of(), 1, 63);
        assertSame(absoluteWithBaseline, absoluteWithBaseline.rebaselined(20));

        Goal.MineOre timedWithoutBaseline = Goal.MineOre.timedCollection(Set.of());
        assertSame(timedWithoutBaseline, timedWithoutBaseline.rebaselined(7));
    }

    @Test
    void promotionKeepsAnOverflowingBaselineInsteadOfThrowingOnThePromotingTick() {
        Goal.MineOre queued = Goal.MineOre.additional(Set.of(), 10, 0);

        assertSame(queued, queued.rebaselined(Integer.MAX_VALUE - 9));
        assertEquals(Integer.MAX_VALUE - 10, queued.rebaselined(Integer.MAX_VALUE - 10).initialDropCount());
    }

    @Test
    void aTimedCollectionIsRemeasuredToo() {
        Goal.MineOre timed = Goal.MineOre.timedCollection(Set.of(), 4).rebaselined(30);

        assertTrue(timed.isTimedCollection());
        assertEquals(30, timed.initialDropCount(),
                "the end-of-window report counts what the window collected, not what the queue ahead mined");
    }
}
