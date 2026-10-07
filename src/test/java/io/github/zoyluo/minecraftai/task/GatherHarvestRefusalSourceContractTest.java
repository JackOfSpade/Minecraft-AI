package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Gather nominates what its eyes see, and what its eyes see through (a pane, a fence, a leaf it may not break) is more than its hand
 * reaches. The miner refuses such a start at once, and gather must learn that from every place it starts a break instead of standing
 * in HARVEST until the deadline. The behaviour is in MiningObstructionGameTests (a log behind a pane) and GatherStallGameTests (a log
 * that leaves the bot's sight).
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
    void everyBreakGatherStartsRemembersTheMinersRefusal() throws IOException {
        String source = Files.readString(SOURCE);
        String start = body(source, "private void startHarvestMining(");
        assertEquals(1, count(start, "HarvestCore.startMining(bot, targetPos)"), "one place asks for the harvest's break");
        assertEquals(3, count(source, "startHarvestMining(bot);"), "the start, the resume after a pause and the retry all go through it");
        assertTrue(start.contains("harvestStartRefusal = started.isFailed() && !harvestStartFenced ? started.reason() : null;"),
                "the typed refusal (target_obstructed, target_not_observed, ...) is kept for the harvest tick: " + start);
        String doStart = body(source, "private void doStartHarvest(");
        assertTrue(doStart.indexOf("phase = Phase.HARVEST;") > doStart.indexOf("startHarvestMining(bot);"),
                "a harvest is only entered once its break was asked for");
    }

    @Test
    void aRefusedTargetIsGivenUpLikeOneThatTimedOutAndNotWaitedOnForTheDeadline() throws IOException {
        String source = Files.readString(SOURCE);
        String abandon = body(source, "private void abandonHarvestTarget(");
        int stop = abandon.indexOf("stopAll()");
        int exclude = abandon.indexOf("EpisodeMemory.INSTANCE.exclude(");
        int retarget = abandon.indexOf("targetPos = null;");
        int phase = abandon.indexOf("phase = Phase.SURVEY;");
        assertTrue(stop >= 0 && exclude > stop && retarget > exclude && phase > retarget,
                "stop, exclude, forget the target, survey again: " + abandon);
        String harvest = body(source, "private void harvest(");
        int refusal = harvest.indexOf("abandonHarvestTarget(bot, \"gather_harvest_refused\", refusal, EpisodeMemory.ttlAfterMiningRefusal(refusal))");
        int deadline = harvest.indexOf("abandonHarvestTarget(bot, \"gather_harvest_timeout\", null, EpisodeMemory.TTL_UNREACHABLE)");
        assertTrue(refusal >= 0 && deadline > refusal,
                "a refusal is read before the deadline, and both end in the same give-up: " + harvest);
        assertTrue(harvest.indexOf("consumeFailedMining(") >= 0 && harvest.indexOf("consumeFailedMining(") < refusal,
                "a break that ended without breaking (the log left the bot's sight) is read the same way");
    }
}
