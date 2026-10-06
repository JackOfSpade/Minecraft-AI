package io.github.zoyluo.minecraftai.goal;

import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
