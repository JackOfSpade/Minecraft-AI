package io.github.zoyluo.minecraftai.task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the no-count crop contract: a held harvest is a baseline, not an instant completion. */
final class FarmTimedCollectionSourceContractTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/task/FarmTask.java");

    @Test
    void timedFarmUsesATenMinuteWindowAndCountsOnlyNewProduce() throws IOException {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("DEFAULT_COLLECTION_DURATION_TICKS = 20 * 60 * 10"));
        assertTrue(source.contains("public static FarmTask collectForDuration("));
        assertTrue(source.contains("produceBaseline = restoredTimedCollection == null ? inventoryNow")
                        && source.contains("elapsed = restoredTimedCollection.elapsedTicks();"),
                "a restarted farm must retain its original baseline and remaining collection window");
        assertTrue(source.contains("!isTimedCollection() && produceItem != null"),
                "the fixed quota must not prematurely finish a time-boxed farm");
        assertTrue(source.contains("elapsed >= collectionDurationTicks && !settlingHarvest()"));
        assertTrue(source.contains("fail(GatherQuotaTask.NO_RESOURCE_FOUND_BY_DEADLINE);"));
        assertTrue(source.contains("new ObservedSearchHops(EXPLORE_MAX_HOPS)"));
        assertTrue(source.contains("observedCropSearch.begin(bot, null)"));
        assertTrue(source.contains("BlockPos surveyCenter = isResourceCollection() ? bot.blockPosition() : areaCenter;"),
                "each observed hop must reveal a new local crop survey rather than rescanning the start field");
        assertTrue(source.contains("!isResourceCollection() && keepTending && hasDepositItems(bot)"),
                "a quota collection must retain its newly harvested produce until its target is measured");
        assertTrue(source.contains("isResourceCollection() && (phase == Phase.SURVEY || phase == Phase.EXPLORE)"),
                "resource searches must be bounded by their own quota/deadline, not cut short as generic stuck work");
    }
}
