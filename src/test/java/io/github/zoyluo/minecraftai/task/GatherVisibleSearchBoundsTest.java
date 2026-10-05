package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Guards against spending task time surveying cells the current perception boundary rejects. */
final class GatherVisibleSearchBoundsTest {
    @Test
    void surveyNeverGrowsPastTheConfiguredVisibleRange() {
        assertEquals(8, ObservableSearchBounds.surveyRadius(8));
        assertEquals(16, ObservableSearchBounds.surveyRadius(16));
        assertEquals(32, ObservableSearchBounds.surveyRadius(32));
        assertEquals(48, ObservableSearchBounds.surveyRadius(96));
    }

    @Test
    void prospectIsReservedForVisibleTerrainBeyondTheSurveyCeiling() {
        assertEquals(16, ObservableSearchBounds.prospectRadius(16));
        assertEquals(48, ObservableSearchBounds.prospectRadius(48));
        assertEquals(96, ObservableSearchBounds.prospectRadius(128));
    }
}
