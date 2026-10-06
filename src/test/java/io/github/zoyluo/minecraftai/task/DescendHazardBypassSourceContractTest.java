package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Contracts for the observed, walk-only descent reroute around fluids and exposed drops. */
final class DescendHazardBypassSourceContractTest {
    private static final Path DESCEND = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/task/DescendToYTask.java");

    @Test
    void localDetoursNeverPromoteObservedWaterIntoAWalkedStep() throws IOException {
        String localDetour = methodBody(Files.readString(DESCEND),
                "private boolean tryLateralDetour(");

        int observedGate = localDetour.indexOf("isObservedHazardFluid(bot, side)");
        int supportRead = localDetour.indexOf("hasSafeSupport(world, side)");
        assertTrue(observedGate >= 0 && supportRead > observedGate,
                "the already-observed side/head/support fluid gate must reject water as well as lava before landing proof");
        assertTrue(localDetour.contains("isObservedHazardFluid(bot, side.above())")
                        && localDetour.contains("isObservedHazardFluid(bot, support)"),
                "all three body/support cells must remain covered by the fluid gate");
    }

    @Test
    void bypassUsesBoundedObservedDryRimsAndAContractBoundBaritoneRoute() throws IOException {
        String source = Files.readString(DESCEND);
        String bypass = methodBody(source, "private boolean tryHazardBypass(");

        assertTrue(bypass.contains("isObservedDryStandable(bot, world, feet)")
                        && bypass.contains("isObservedDryRimCandidate(bot, world, candidate)")
                        && bypass.contains("candidate.getY() < feet.getY()")
                        && bypass.contains("HAZARD_BYPASS_PROBES_PER_TICK"),
                "only a current dry stance and individually observed, non-descending dry-rim candidates may enter the fallback");
        assertTrue(bypass.contains("startSurfacePathTo(")
                        && bypass.contains("candidate, anchor.getY(), anchor"),
                "each bypass leg must use the min-Y plus return-anchor surface-route contract");
        assertFalse(bypass.contains("startSurfaceDigFallbackPathTo")
                        || bypass.contains("startPillarPathTo")
                        || bypass.contains("startDigPathTo"),
                "hazard bypass may neither dig nor pillar through a hazard");
        assertTrue(bypass.contains("HAZARD_BYPASS_MAX_STARTED_LEGS")
                        && bypass.contains("hazardBypassStartedLegs >= HAZARD_BYPASS_MAX_STARTED_LEGS")
                        && bypass.contains("HAZARD_BYPASS_MAX_ROUTE_PROBES"),
                "repeated re-probing must remain bounded independently of the global descent timer");
        int admitted = bypass.indexOf("hazardBypassGoal = candidate.immutable()");
        int counted = bypass.indexOf("hazardBypassStartedLegs++");
        assertTrue(admitted >= 0 && counted > admitted,
                "the started-leg budget must be consumed when a route is admitted, not only after arrival");
    }

    @Test
    void arrivalIsReprovedAndEpisodeMemoryClearsOnlyAfterLowerProgress() throws IOException {
        String source = Files.readString(DESCEND);
        String tick = methodBody(source, "private boolean tickHazardBypass(");
        int reproof = tick.indexOf("isObservedDryRimCandidate(bot, world, feet)");
        int visited = tick.indexOf("hazardBypassVisitedGoals.add(goal)");
        assertTrue(reproof >= 0 && visited > reproof,
                "arrival must re-prove a dry rim before it becomes visited and resumes descent");

        String settle = methodBody(source, "private boolean settlePendingLandingAtCurrentPose(");
        int lower = settle.indexOf("boolean advancedLower");
        int reset = settle.indexOf("resetHazardBypassEpisode();");
        assertTrue(lower >= 0 && reset > lower,
                "candidate rejection/visit memory is released only after a committed lower landing");
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, () -> "missing method " + signature);
        int open = source.indexOf('{', start);
        assertTrue(open >= 0, () -> "missing method body " + signature);
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return source.substring(start, index + 1);
            }
        }
        throw new AssertionError("unterminated method " + signature);
    }
}
