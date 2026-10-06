package io.github.zoyluo.minecraftai.task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the high-ore recovery ordering that keeps a visible upper ledge or staircase from being
 * discarded in favor of a fresh downward exploration episode.
 */
final class OreDigHighTargetStairRouteSourceContractTest {
    private static final Path ORE_DIG = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/task/OreDigTask.java");
    private static final Path OBSERVED_HOPS = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/task/ObservedSearchHops.java");

    @Test
    void highTargetUsesBoundedObservedHopsBeforeGenericApproach() throws IOException {
        String source = Files.readString(ORE_DIG);
        int highRoute = source.indexOf("if (tickHighTargetStairSearch(bot, targetOre)");
        int genericApproach = source.indexOf("approachTargetOre(bot, world, targetOre);", highRoute);

        assertTrue(highRoute >= 0 && genericApproach > highRoute,
                "a visible high ore must try its stair/ledge recovery before generic approach can tunnel or hand off");
        String interval = source.substring(highRoute, genericApproach);
        assertTrue(interval.contains("startHighTargetStairSearch(bot, world, targetOre)")
                        && interval.contains("return;"),
                "the high-target route must retain ownership while its finite observed-hop episode runs");

        String start = methodBody(source, "private boolean startHighTargetStairSearch(");
        String tick = methodBody(source, "private boolean tickHighTargetStairSearch(");
        String needs = methodBody(source, "private boolean needsHighTargetStairSearch(");

        assertTrue(source.contains("new ObservedSearchHops(\n            OBSERVED_SEARCH_MAX_HOPS)"),
                "the route must use the same finite observed-hop budget as other exploration");
        assertTrue(start.contains("highTargetStairSearch.begin(\n                bot, highTargetStairSearchHeading)")
                        && start.contains("highTargetStairSearch.exhausted()")
                        && start.contains("ore_dig_high_stair_search_exhausted")
                        && !start.contains("abandonTargetApproach("),
                "the hop search must stay bounded, and its exhaustion must hand the tick to the stair dug up to the ore"
                        + " rather than give the ore up");
        String approach = methodBody(source, "private void approachTargetOre(");
        String reached = methodBody(source, "private boolean climbsTo(");
        assertTrue(approach.contains("climbsTo(bot, ore)")
                        && approach.contains("climbTowardHighTarget(bot, world, ore)")
                        && reached.contains("ore.equals(targetOre)"),
                "the primary overhead target must get its stair from the generic approach once the hops found no ledge");
        assertTrue(start.contains("highTargetStairSearchHeading = highTargetStairHeading(bot.blockPosition(), ore);")
                        && start.contains("else if (highTargetStairSearchHeading != null)"),
                "guided hops must refresh their compass point from the new local stance rather than stop advancing after one projection");
        assertFalse(start.contains("startPathTo(") || start.contains("startDigPathTo(")
                        || start.contains("beginMine(") || start.contains("BuildAction."),
                "starting a high-target recovery may not route to the ore, dig, mine, or place blocks directly");
        assertTrue(needs.contains("ore.getY() - bot.blockPosition().getY() <= MAX_TARGET_BREAK_DY")
                        && needs.contains("approachGoalFor(bot, world, ore)")
                        && needs.contains("rememberedHighWorkPose(bot, world, ore)"),
                "the recovery applies only to a genuinely high ore that lacks both a current and retained safe work pose");
        assertTrue(tick.contains("highTargetStairSearch.retireObservedGoal(observedGoal)")
                        && tick.contains("bot.getActionPack().stopAll()")
                        && tick.contains("OBSERVED_SEARCH_MOVE_LIMIT"),
                "every admitted local hop must retire or time out before another one is started");
    }

    @Test
    void sharedHopPrimitiveKeepsTheRemoteOreAsHeadingOnly() throws IOException {
        String source = Files.readString(OBSERVED_HOPS);

        assertTrue(source.contains("startDirectionalPursuitTo(heading, HOP_DISTANCE, false, false)"),
                "the route must ask Baritone for a no-break/no-place directional hop");
        assertTrue(source.contains("BlockPos observedGoal = bot.getActionPack().activePathGoal()")
                        && source.contains("directional_hop_missing_observed_goal"),
                "Baritone must expose a locally admitted observed goal; the remote heading is never an endpoint");
        assertFalse(source.contains("getBlockState(") || source.contains("startPathTo("),
                "the shared hop helper must not inspect terrain or create a direct remote route");
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
