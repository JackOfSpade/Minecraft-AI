package io.github.zoyluo.minecraftai.goal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GoalHarvestCropBaselineTest {
    @Test
    void capturedProduceChangesPhysicalTargetButNotHarvestQuota() {
        Goal.HarvestCrop additional = new Goal.HarvestCrop(null, null, null, 1, 63);

        assertEquals(1, additional.count(), "the requested harvest quota remains one produce item");
        assertEquals(63, additional.initialProduceCount());
        assertEquals(64, additional.targetProduceCount());
        assertEquals(0, additional.deliveredFromInitial(63));
        assertEquals(1, additional.deliveredFromInitial(64));
        assertEquals(1, additional.deliveredFromInitial(99));

        Goal.HarvestCrop legacy = new Goal.HarvestCrop(null, null, null, 64);
        assertEquals(0, legacy.initialProduceCount());
        assertEquals(64, legacy.targetProduceCount(),
                "the four-argument constructor remains the legacy absolute target");
    }

    @Test
    void baselineCannotOverflowThePhysicalPostcondition() {
        assertThrows(IllegalArgumentException.class,
                () -> new Goal.HarvestCrop(null, null, null, 1, Integer.MAX_VALUE));
    }
}
