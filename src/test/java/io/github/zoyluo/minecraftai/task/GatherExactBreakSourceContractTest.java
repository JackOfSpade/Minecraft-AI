package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GatherExactBreakSourceContractTest {
    @Test
    void exactBlockBreakingCountsDestructionRatherThanDropsAndStaysLocal() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/task/GatherQuotaTask.java"));

        assertTrue(source.contains("public static GatherQuotaTask breakBlocks(Block block, int targetCount)"));
        assertTrue(source.contains("\"break_blocks\", BuiltInRegistries.BLOCK.getKey(block).toString()"));
        assertTrue(source.contains("countBrokenBlocks ? 0 : countAccepted(bot)"));
        assertTrue(source.contains("countSoFar++"));
        assertTrue(source.contains("if (countBrokenBlocks) {\n                // An exact break request"));
        assertTrue(source.contains("return countBrokenBlocks ? EXACT_BREAK_SEARCH_RADIUS : SEARCH_RADIUS"));
        assertTrue(source.contains("if (countBrokenBlocks) {\n            return false;"));
    }

    @Test
    void theBootstrapPickupWaitIsOnlyOwedForABareHandLogBreakAndTheExclusionIsResetWhenTheBootstrapEnds() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/task/GatherQuotaTask.java"));

        assertTrue(source.contains("} else if (bootstrapActive && handLogBreakInFlight) {"),
                "BOOTSTRAP_PICKUP must only follow a bare-hand break of a log, not any block broken while bootstrapActive");
        int flag = source.indexOf("handLogBreakInFlight = true;");
        int start = source.indexOf("doStartHarvest(bot);", flag);
        assertTrue(flag > 0 && start > flag, "the flag is raised on the bootstrap hand-break path only");
        assertTrue(source.contains("private void endBootstrap(AIPlayerEntity bot) {"));
        assertTrue(source.contains("bootstrapExcluded = Math.min(bootstrapExcluded, rawNewItems(bot));"),
                "a new-items quota keeps excluded only the logs that really arrived while bootstrapping");
        assertTrue(source.contains("bootstrapExcluded = 0;"), "an absolute quota stops discounting when the bootstrap ends");
        assertFalse(source.contains("            bootstrapActive = false;\n            pendingToolCategory = null;"),
                "ensureTool must end the bootstrap through endBootstrap");
    }

    /** The source of the named method: from its signature to the matching closing brace. */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing method " + signature);
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

    private static boolean matches(String source, String regex) {
        return java.util.regex.Pattern.compile(regex).matcher(source).find();
    }

    @Test
    void theBootstrapPickupWalksToTheBreakCellWhenTheDropIsOutOfSightAndForgetsItOnExit() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/task/GatherQuotaTask.java"));
        String bootstrapPickup = methodBody(source, "private void bootstrapPickup(AIPlayerEntity bot)");

        // The fallback walks to the factual break cell, only while both movers are idle (never fights a route).
        assertTrue(matches(bootstrapPickup,
                        "bootstrapPickupOrigin\\s*!=\\s*null\\s*&&\\s*bot\\.getActionPack\\(\\)\\.isPathExecutorIdle\\(\\)"
                                + "\\s*&&\\s*bot\\.getActionPack\\(\\)\\.isWalkToIdle\\(\\)"),
                "the origin approach must wait for an idle path executor and walk-to");
        assertTrue(matches(bootstrapPickup,
                        "HarvestCore\\.approachKnownPickupCell\\(\\s*bot\\s*,\\s*bootstrapPickupOrigin\\s*\\)"),
                "bootstrapPickup must walk to the recorded break cell via HarvestCore.approachKnownPickupCell");
        assertTrue(bootstrapPickup.contains("\"gather_bootstrap_origin_approach\""),
                "the approach must stay observable in the log");
        // The origin is dropped when the pickup window ends (collected or given up), before the phase changes.
        int clear = bootstrapPickup.lastIndexOf("bootstrapPickupOrigin = null;");
        int leave = bootstrapPickup.lastIndexOf("phase = Phase.SURVEY;");
        assertTrue(clear > 0 && leave > clear,
                "bootstrapPickup must clear the origin on exit, before returning to SURVEY");

        // It is recorded where the broken block is recorded (the exact-break bookkeeping), for the hand-log bootstrap only.
        assertTrue(matches(source,
                        "bootstrapPickupBaseline\\s*=\\s*countAccepted\\(bot\\);\\s*"
                                + "bootstrapPickupOrigin\\s*=\\s*cleared\\.immutable\\(\\);"),
                "the break cell must be recorded next to the pickup baseline when the bootstrap pickup starts");
        int recorded = source.indexOf("bootstrapPickupOrigin = cleared.immutable();");
        int handLog = source.lastIndexOf("} else if (bootstrapActive && handLogBreakInFlight) {", recorded);
        int exactBroken = source.lastIndexOf("\"exact_block_broken\"", recorded);
        assertTrue(handLog > exactBroken && exactBroken > 0,
                "the origin is set in the hand-log branch that follows the exact_block_broken bookkeeping");
        // ...and a task restart never inherits a stale one.
        assertTrue(matches(source, "pickupOriginApproachLogged\\s*=\\s*false;\\s*bootstrapPickupOrigin\\s*=\\s*null;"),
                "starting the task must reset the bootstrap origin");
    }

    @Test
    void theRegularPickupKeepsItsOriginFallbackToTheBreakCell() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/task/GatherQuotaTask.java"));
        String pickup = methodBody(source, "private void pickup(AIPlayerEntity bot)");

        assertTrue(matches(pickup,
                        "pickupOrigin\\s*!=\\s*null\\s*&&\\s*bot\\.getActionPack\\(\\)\\.isPathExecutorIdle\\(\\)"
                                + "\\s*&&\\s*bot\\.getActionPack\\(\\)\\.isWalkToIdle\\(\\)"),
                "the pickup origin approach must wait for idle movers");
        assertTrue(matches(pickup, "HarvestCore\\.approachKnownPickupCell\\(\\s*bot\\s*,\\s*pickupOrigin\\s*\\)"),
                "pickup must walk to the recorded break cell via HarvestCore.approachKnownPickupCell");
        assertTrue(pickup.contains("\"gather_pickup_origin_approach\""),
                "the approach must stay observable in the log");
        assertTrue(matches(source, "pickupOrigin\\s*=\\s*targetPos\\s*==\\s*null\\s*\\?\\s*null\\s*:\\s*targetPos\\.immutable\\(\\);"),
                "the origin is the block that was just started on");
    }
}
