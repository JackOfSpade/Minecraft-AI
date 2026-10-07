package io.github.zoyluo.minecraftai.goal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void onlyAPublicRequestIsMarkedIncremental() {
        assertTrue(Goal.HarvestCrop.additional(null, null, null, 1, 0).incremental());
        assertTrue(Goal.HarvestCrop.timedCollection(null, null, null, 5).incremental());

        assertFalse(new Goal.HarvestCrop(null, null, null, 4).incremental());
        assertFalse(new Goal.HarvestCrop(null, null, null, 1, 63).incremental(),
                "a baseline passed to a constructor is not the marker");
        assertFalse(Goal.HarvestCrop.timedCollection(null, null, null).incremental());
    }

    @Test
    void promotionRemeasuresOnlyAnIncrementalRequest() {
        Goal.HarvestCrop queued = Goal.HarvestCrop.additional(null, null, null, 3, 2);

        Goal.HarvestCrop promoted = queued.rebaselined(20);
        assertEquals(20, promoted.initialProduceCount());
        assertEquals(23, promoted.targetProduceCount());
        assertEquals(3, promoted.count());
        assertTrue(promoted.incremental());
        assertEquals(0, queued.rebaselined(-1).initialProduceCount());

        Goal.HarvestCrop absolute = new Goal.HarvestCrop(null, null, null, 8);
        assertSame(absolute, absolute.rebaselined(20));
        Goal.HarvestCrop absoluteWithBaseline = new Goal.HarvestCrop(null, null, null, 1, 63);
        assertSame(absoluteWithBaseline, absoluteWithBaseline.rebaselined(20));
    }

    @Test
    void promotionKeepsAnOverflowingBaselineAndRemeasuresATimedWindow() {
        Goal.HarvestCrop queued = Goal.HarvestCrop.additional(null, null, null, 10, 0);
        assertSame(queued, queued.rebaselined(Integer.MAX_VALUE - 9));

        Goal.HarvestCrop timed = Goal.HarvestCrop.timedCollection(null, null, null, 4).rebaselined(30);
        assertTrue(timed.isTimedCollection());
        assertEquals(30, timed.initialProduceCount());
    }
}
