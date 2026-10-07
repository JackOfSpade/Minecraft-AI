package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GatherExactBreakSourceContractTest {
    @Test
    void exactBlockBreakingCountsDestructionRatherThanDropsAndUsesOnlyVisibleTargets() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/task/GatherQuotaTask.java"));

        assertTrue(source.contains("public static GatherQuotaTask breakBlocks(Block block, int targetCount)"));
        assertTrue(source.contains("\"break_blocks\", BuiltInRegistries.BLOCK.getKey(block).toString()"));
        assertTrue(source.contains("countBrokenBlocks ? 0 : countAccepted(bot)"));
        assertTrue(source.contains("countSoFar++"));
        assertTrue(source.contains("if (countBrokenBlocks) {\n                // An exact break request"));
        String defaultSearchRadius = methodBody(source, "private int defaultSearchRadius()");
        assertTrue(defaultSearchRadius.contains("if (countBrokenBlocks)")
                        && defaultSearchRadius.contains("return EXACT_BREAK_SEARCH_RADIUS;"),
                "exact breaking must retain its small local survey radius");
        assertTrue(source.contains("if (countBrokenBlocks) {\n            return false;"));
        String survey = methodBody(source, "private void survey(");
        int visibleSight = survey.indexOf("seekVisibleTarget(bot)");
        int exactFailure = survey.indexOf("if (countBrokenBlocks)", visibleSight);
        assertTrue(visibleSight >= 0 && exactFailure > visibleSight,
                "an exact break may pursue only the exact target when it is directly visible at render range before failing");
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

        // The fallback walks to the factual break cell (through the sweep, whose first step is that plain
        // walk, pinned in theSweepStartsWithThePlainWalkToTheBreakCellAndReportsWhatItDid), only while every movement
        // owner is idle (never fights a route or another walked step) and no observed drop is being chased.
        assertTrue(matches(bootstrapPickup,
                        "!chasingVisibleDrop\\s*&&\\s*bootstrapPickupOrigin\\s*!=\\s*null\\s*&&\\s*bootstrapOriginSweep\\s*!=\\s*null"
                                + "\\s*&&\\s*bot\\.getActionPack\\(\\)\\.isPathExecutorIdle\\(\\)"
                                + "\\s*&&\\s*bot\\.getActionPack\\(\\)\\.isWalkToIdle\\(\\)"
                                + "\\s*&&\\s*bot\\.getActionPack\\(\\)\\.stepIdle\\(\\)"),
                "the origin sweep must wait for every movement owner, and not run while a visible drop is chased");
        assertTrue(matches(bootstrapPickup,
                        "chasingVisibleDrop\\s*=\\s*HarvestCore\\.approachDropPhysically\\(\\s*bot\\s*,\\s*visibleDrop\\.get\\(\\)\\s*\\)"),
                "a supported observed drop is chased first and suppresses the sweep");
        assertTrue(matches(bootstrapPickup, "bootstrapOriginSweep\\.step\\(bot\\)"),
                "bootstrapPickup must sweep from the recorded break cell");
        assertTrue(matches(source, "bootstrapOriginSweep\\s*=\\s*new KnownCellPickupSweep\\(\\s*bootstrapPickupOrigin\\s*\\)"),
                "the sweep is centred on the recorded break cell");
        assertTrue(matches(bootstrapPickup,
                        "swept\\s*==\\s*KnownCellPickupSweep\\.Step\\.MOVING\\s*&&\\s*!bootstrapOriginApproachLogged"),
                "the approach is logged only when a walk really started");
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
        assertTrue(matches(source, "bootstrapOriginSweep\\s*=\\s*null;\\s*pickupOriginSweep\\s*=\\s*null;"),
                "starting the task must reset both sweeps");
        assertTrue(matches(bootstrapPickup, "bootstrapPickupOrigin\\s*=\\s*null;\\s*bootstrapOriginSweep\\s*=\\s*null;"),
                "the sweep is forgotten together with the origin on exit");
    }

    @Test
    void theRegularPickupKeepsItsOriginFallbackToTheBreakCell() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/task/GatherQuotaTask.java"));
        String pickup = methodBody(source, "private void pickup(AIPlayerEntity bot)");

        assertTrue(matches(pickup,
                        "!chasingVisibleDrop\\s*&&\\s*lookAround\\s*!=\\s*null\\s*&&\\s*bot\\.getActionPack\\(\\)\\.isPathExecutorIdle\\(\\)"
                                + "\\s*&&\\s*bot\\.getActionPack\\(\\)\\.isWalkToIdle\\(\\)"
                                + "\\s*&&\\s*bot\\.getActionPack\\(\\)\\.stepIdle\\(\\)"),
                "the pickup origin sweep must wait for every movement owner and must not run while a visible drop is chased");
        assertTrue(matches(pickup,
                        "chasingVisibleDrop\\s*=\\s*HarvestCore\\.approachDropPhysically\\(\\s*bot\\s*,\\s*visibleDrop\\.get\\(\\)\\s*\\)"),
                "a supported observed drop is chased first and suppresses the sweep");
        assertTrue(matches(pickup, "BlockPos\\s+lookAround\\s*=\\s*dropRestedAt\\s*!=\\s*null\\s*\\?\\s*dropRestedAt\\s*:\\s*pickupOrigin;")
                        && matches(pickup, "new KnownCellPickupSweep\\(\\s*lookAround\\s*\\)")
                        && matches(pickup, "pickupOriginSweep\\.step\\(bot\\)"),
                "pickup must sweep from where the drop was last seen at rest, else from the recorded break cell");
        assertTrue(matches(pickup,
                        "swept\\s*==\\s*KnownCellPickupSweep\\.Step\\.MOVING\\s*&&\\s*!pickupOriginApproachLogged"),
                "the approach is logged only when a walk really started");
        assertTrue(pickup.contains("\"gather_pickup_origin_approach\""),
                "the approach must stay observable in the log");
        assertTrue(matches(source, "pickupOrigin\\s*=\\s*targetPos\\s*==\\s*null\\s*\\?\\s*null\\s*:\\s*targetPos\\.immutable\\(\\);"),
                "the origin is the block that was just started on");
    }

    @Test
    void theSweepStartsWithThePlainWalkToTheBreakCellAndReportsWhatItDid() throws IOException {
        String sweep = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/action/KnownCellPickupSweep.java"));
        String step = methodBody(sweep, "public Step step(AIPlayerEntity bot)");

        // First cell: exactly the plain approach (HarvestCore.approachKnownPickupCell) toward the remembered
        // break cell, both to walk there and to nudge once standing in it; later cells use exact surface routes.
        assertTrue(matches(step, "targetIsFirst\\s*\\?\\s*HarvestCore\\.approachKnownPickupCell\\(\\s*bot\\s*,\\s*origin\\s*\\)"
                        + "\\s*:\\s*HarvestCore\\.startExactPickupPath\\(\\s*bot\\s*,\\s*target\\s*\\)"),
                "the first sweep step must be the plain walk to the break cell, later ones exact surface routes");
        assertTrue(matches(step, "if\\s*\\(\\s*targetIsFirst\\s*\\)\\s*\\{[^}]*HarvestCore\\.approachKnownPickupCell\\(\\s*bot\\s*,\\s*origin\\s*\\)"),
                "standing in the first cell it nudges toward the break cell exactly as the plain approach does");
        // MOVING is returned only after a route/approach was really started; dwelling, skipping and
        // exhaustion are distinct results so callers log a walk only when one began.
        assertTrue(matches(step, "if\\s*\\(\\s*!started\\s*\\)\\s*\\{\\s*retire\\(\\);\\s*return Step\\.SKIPPED;\\s*\\}\\s*return Step\\.MOVING;"),
                "MOVING is reported only when the approach/route really started");
        assertTrue(step.contains("return Step.DWELLING;") && step.contains("return Step.EXHAUSTED;"),
                "dwelling and exhaustion are reported as such");
        assertFalse(matches(step, "return\\s+(true|false);"), "step reports an enum, not a boolean");
    }
}
