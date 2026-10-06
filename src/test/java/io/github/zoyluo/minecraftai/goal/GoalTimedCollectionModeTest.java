package io.github.zoyluo.minecraftai.goal;

import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalTimedCollectionModeTest {
    @Test
    void timedMineAndHarvestGoalsCarryTheTenMinuteCollectionContract() {
        Goal.MineOre mine = Goal.MineOre.timedCollection(Set.of(), 63);
        Goal.HarvestCrop harvest = Goal.HarvestCrop.timedCollection(null, null, null, 31);

        assertEquals(Goal.CollectionMode.TIMED_COLLECTION, mine.collectionMode());
        assertEquals(Goal.CollectionMode.TIMED_COLLECTION, harvest.collectionMode());
        assertTrue(mine.isTimedCollection());
        assertTrue(harvest.isTimedCollection());
        assertEquals(20 * 60 * 10, mine.timeLimitTicks());
        assertEquals(20 * 60 * 10, harvest.timeLimitTicks());
        assertEquals(63, mine.initialDropCount());
        assertEquals(31, harvest.initialProduceCount());
        assertEquals(1, mine.count(), "timed collection intentionally has no fixed quota");
        assertEquals(1, harvest.count(), "timed collection intentionally has no fixed quota");
    }

    @Test
    void existingConstructorsRemainFixedQuotaGoals() {
        Goal.MineOre mine = new Goal.MineOre(Set.of(), 4, 2);
        Goal.HarvestCrop harvest = new Goal.HarvestCrop(null, null, null, 4, 2);

        assertEquals(Goal.CollectionMode.FIXED_QUOTA, mine.collectionMode());
        assertEquals(Goal.CollectionMode.FIXED_QUOTA, harvest.collectionMode());
        assertFalse(mine.isTimedCollection());
        assertFalse(harvest.isTimedCollection());
        assertEquals(0, mine.timeLimitTicks());
        assertEquals(0, harvest.timeLimitTicks());
    }

    @Test
    void persistedModeValuesAreStableAndStrict() {
        assertEquals(Goal.CollectionMode.FIXED_QUOTA,
                Goal.CollectionMode.fromPersistedValue("fixed_quota"));
        assertEquals(Goal.CollectionMode.TIMED_COLLECTION,
                Goal.CollectionMode.fromPersistedValue("timed_collection"));
        assertEquals(Goal.CollectionMode.TIMED_COLLECTION,
                Goal.CollectionMode.fromPersistedValue("five_minute_collection"));
        assertThrows(IllegalArgumentException.class,
                () -> Goal.CollectionMode.fromPersistedValue("ten_minutes"));
    }
}
