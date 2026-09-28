package io.github.zoyluo.minecraftai.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * shelterdig-refactor-4: {@code ShelterCleanupRegistry.CLEANUP_MAX_AGE_TICKS} is a fixed safety
 * margin above the largest {@code @GameTest(maxTicks = ...)} anywhere in src/gametest, not a
 * value recomputed from it (see the doc comment above the constant). Nothing else keeps the two
 * numbers in sync, so this test greps every gametest source file for its own reasoning and fails
 * loudly the day some fixture's maxTicks reaches or exceeds the constant, instead of letting a
 * live test's cleanup debt get silently pruned as "too old". This file never boots a Minecraft
 * registry: every check reads the production sources as text, like its neighbours in this
 * package.
 */
class EmergencyShelterAgeMaxTicksSourceContractTest {
    private static final Path TASK_SOURCE = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/task/ShelterCleanupRegistry.java");
    private static final Path GAMETEST_ROOT = Path.of("src/gametest");
    private static final Pattern MAX_TICKS_CONSTANT = Pattern.compile(
            "CLEANUP_MAX_AGE_TICKS\\s*=\\s*([0-9_]+)\\s*;");
    private static final Pattern MAX_TICKS_USAGE = Pattern.compile(
            "maxTicks\\s*=\\s*([0-9_]+)");

    @Test
    void everyGameTestMaxTicksStaysBelowTheCleanupAgeCeiling() throws IOException {
        int ceiling = readCleanupMaxAgeTicks();
        assertTrue(ceiling > 0, "could not locate CLEANUP_MAX_AGE_TICKS in " + TASK_SOURCE);

        int largestObserved = 0;
        Path offender = null;
        try (var files = Files.walk(GAMETEST_ROOT)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                Matcher matcher = MAX_TICKS_USAGE.matcher(source);
                while (matcher.find()) {
                    int ticks = Integer.parseInt(matcher.group(1).replace("_", ""));
                    if (ticks > largestObserved) {
                        largestObserved = ticks;
                        offender = file;
                    }
                }
            }
        }

        assertTrue(largestObserved > 0, "found no maxTicks= usage anywhere under " + GAMETEST_ROOT);
        assertTrue(largestObserved < ceiling,
                "a @GameTest maxTicks value (" + largestObserved + ", in " + offender
                        + ") reached or exceeded CLEANUP_MAX_AGE_TICKS (" + ceiling
                        + "); a still-running test's shelter cleanup debt can now be pruned as "
                        + "stale by an unrelated concurrent test. Raise CLEANUP_MAX_AGE_TICKS in "
                        + TASK_SOURCE + " (and update its doc comment) before raising this test's "
                        + "budget further.");
    }

    private static int readCleanupMaxAgeTicks() throws IOException {
        String source = Files.readString(TASK_SOURCE);
        Matcher matcher = MAX_TICKS_CONSTANT.matcher(source);
        if (!matcher.find()) {
            return -1;
        }
        return Integer.parseInt(matcher.group(1).replace("_", ""));
    }
}
