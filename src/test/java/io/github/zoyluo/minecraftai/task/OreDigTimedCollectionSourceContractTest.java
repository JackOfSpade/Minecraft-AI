package io.github.zoyluo.minecraftai.task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the no-count ore collection boundary independently of the goal/planner path. */
final class OreDigTimedCollectionSourceContractTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/task/OreDigTask.java");

    @Test
    void timedOreFactoryUsesTenMinuteNewDropMode() throws IOException {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("DEFAULT_COLLECTION_DURATION_TICKS = 20 * 60 * 10"));
        assertTrue(source.contains("public static OreDigTask collectForDuration(Set<Block> targetOres)"));
        assertTrue(source.contains("return collectForDuration(targetOres, DEFAULT_COLLECTION_DURATION_TICKS);"));
        assertTrue(source.contains("public static OreDigTask collectForDuration(Set<Block> targetOres, int durationTicks)"));
        assertTrue(source.contains("new OreDigTask(targetOres, 1, 0,"),
                "the timed task should use a placeholder quota while its inventory baseline tracks only new drops");
        assertTrue(source.contains("return collectionDurationTicks > 0;"));
        assertTrue(source.contains("if (!isTimedCollection()) {")
                        && source.contains("if (!veinMode && collected >= targetCount)"),
                "a held/new first drop must not terminate a timed collection early");
    }

    @Test
    void deadlineSettlesPhysicalWorkThenHasTypedNoResourceOutcome() throws IOException {
        String source = Files.readString(SOURCE);
        String finish = methodBody(source, "private void finishTimedCollectionAtSafeBoundary(");

        assertTrue(source.contains("if (isTimedCollection() && totalBudget() >= collectionDurationTicks)"));
        assertTrue(source.contains("NO_RESOURCE_FOUND_BY_DEADLINE = \"no_resource_found_by_deadline\""));
        assertTrue(finish.contains("activeBreakStillMining")
                        && finish.contains("finishTargetBreak")
                        && finish.contains("miningExploration.abort"),
                "deadline handling must settle committed work instead of immediately discarding it");
        assertTrue(finish.contains("HarvestCore.countInventoryItems(bot, targetDrops) - invBaseline"),
                "the deadline outcome must count only drops gained after task start");
        assertTrue(finish.contains("if (collected > 0)")
                        && finish.contains("complete();")
                        && finish.contains("fail(NO_RESOURCE_FOUND_BY_DEADLINE);"));
    }

    @Test
    void explicitQuotaRestartsObservedSearchEpisodesUntilItsOwnBudgetEnds() throws IOException {
        String source = Files.readString(SOURCE);
        String finish = methodBody(source, "private void finishObservedOreSearch(");

        assertTrue(finish.contains("observedOreSearch.reset();")
                        && finish.contains("startObservedOreSearch(bot);"),
                "an exhausted safe-hop episode should restart from the newly observed position");
        assertFalse(finish.contains("fail(reason);"),
                "an empty local observation episode cannot itself claim the requested ore is absent");
    }

    private static String methodBody(String source, String signature) {
        int signatureAt = source.indexOf(signature);
        assertTrue(signatureAt >= 0, () -> "missing method signature: " + signature);
        int open = source.indexOf('{', signatureAt);
        assertTrue(open >= 0, () -> "missing method body: " + signature);
        int depth = 0;
        for (int at = open; at < source.length(); at++) {
            char current = source.charAt(at);
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return source.substring(open, at + 1);
            }
        }
        throw new AssertionError("unterminated method body: " + signature);
    }
}
