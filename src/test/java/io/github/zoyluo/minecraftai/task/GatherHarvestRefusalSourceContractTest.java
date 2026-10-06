package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Gather nominates what its eyes see, and what its eyes see through (a pane, a fence, a leaf it may not break) is more than its hand
 * reaches. The miner refuses such a start at once, and gather must read that refusal at every place it starts a break instead of
 * standing in HARVEST until the deadline. The behaviour is in MiningObstructionGameTests.
 */
class GatherHarvestRefusalSourceContractTest {
    private static final Path SOURCE = Path.of("src/main/java/io/github/zoyluo/minecraftai/task/GatherQuotaTask.java");

    private static int count(String source, String needle) {
        int count = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    private static String body(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing " + signature);
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(start, i + 1);
            }
        }
        throw new AssertionError("unbalanced braces after " + signature);
    }

    @Test
    void everyBreakGatherStartsReadsTheMinersRefusal() throws IOException {
        String source = Files.readString(SOURCE);
        assertEquals(3, count(source, "HarvestCore.startMining(bot, targetPos)"), "the start, the resume after a pause and the retry");
        assertEquals(3, count(source, "HarvestCore.refusedAsUnmineable("), "each of them reads the refusal");
        assertTrue(body(source, "private void doStartHarvest(").indexOf("refusedAsUnmineable(")
                        > body(source, "private void doStartHarvest(").indexOf("HarvestCore.startMining("),
                "a harvest is only entered once the miner has taken it");
    }

    @Test
    void aRefusedTargetIsGivenUpLikeOneThatTimedOutAndNotWaitedOnForTheDeadline() throws IOException {
        String source = Files.readString(SOURCE);
        String abandon = body(source, "private void abandonHarvestTarget(");
        int stop = abandon.indexOf("stopAll()");
        int exclude = abandon.indexOf("EpisodeMemory.INSTANCE.exclude(");
        int retarget = abandon.indexOf("targetPos = null;");
        int phase = abandon.indexOf("phase = Phase.SURVEY;");
        assertTrue(stop >= 0 && exclude > stop && retarget > exclude && phase > retarget && abandon.contains("TTL_UNREACHABLE"),
                "stop, exclude for the unreachable TTL, forget the target, survey again: " + abandon);
        assertTrue(body(source, "private void harvest(").contains("abandonHarvestTarget(bot, \"gather_harvest_timeout\", null)"),
                "the deadline is the same give-up");
    }
}
