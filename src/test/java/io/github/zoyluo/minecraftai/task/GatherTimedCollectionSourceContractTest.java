package io.github.zoyluo.minecraftai.task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the no-count gather contract: collect new material for ten minutes, never stop at a held stack. */
final class GatherTimedCollectionSourceContractTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/task/GatherQuotaTask.java");

    @Test
    void timedFactoryUsesTheTenMinuteNewItemMode() throws IOException {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("DEFAULT_COLLECTION_DURATION_TICKS = 20 * 60 * 10"));
        assertTrue(source.contains("public static GatherQuotaTask collectForDuration(Item targetItem)"));
        assertTrue(source.contains("return collectForDuration(targetItem, DEFAULT_COLLECTION_DURATION_TICKS);"));
        assertTrue(source.contains("new GatherQuotaTask(targetItem, 1, false, null, \"\", \"\", true,"),
                "the open-ended factory must count only newly obtained material, not held inventory");
        assertTrue(source.contains("return !isTimedCollection() && countSoFar >= targetCount;"),
                "a placeholder quota must never terminate time-boxed collection");
    }

    @Test
    void deadlineCompletesOnlyAfterNewMaterialAndOtherwiseHasTypedFailure() throws IOException {
        String source = Files.readString(SOURCE);
        String finish = methodBody(source, "private void finishTimedCollection(");

        assertTrue(source.contains("NO_RESOURCE_FOUND_BY_DEADLINE = \"no_resource_found_by_deadline\""));
        assertTrue(source.contains("if (isTimedCollection() && elapsed >= collectionDurationTicks && !settlingCollection())"));
        assertTrue(source.contains("(!isTimedCollection() || !settlingCollection())"),
                "the generic quota timeout must not preempt a break/pickup settling at the timed deadline");
        assertTrue(finish.contains("if (countSoFar > 0)") && finish.contains("complete();")
                        && finish.contains("fail(NO_RESOURCE_FOUND_BY_DEADLINE);"),
                "a timed gather must succeed only when it collected new material before its deadline");
    }

    @Test
    void normalGatherRestartsSafeSearchEpisodesRatherThanFailingAtFirstEmptyBoundary() throws IOException {
        String source = Files.readString(SOURCE);
        String emptySearch = methodBody(source, "private void escapeBarrenAreaOrFail(");
        String restart = methodBody(source, "private boolean continueAfterObservedSearchExhaustion(");

        assertTrue(emptySearch.contains("if (continueAfterObservedSearchExhaustion(bot))"),
                "ordinary gathering must continue after a bounded observed-search episode");
        assertTrue(restart.contains("if (countBrokenBlocks) {") && restart.contains("return false;"),
                "only exact local break requests may retain the immediate empty-view boundary");
        assertTrue(restart.contains("observedSearchHops.reset();") && restart.contains("startExplore(bot);"),
                "each continuation must begin another observation-fenced exploration episode");
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
