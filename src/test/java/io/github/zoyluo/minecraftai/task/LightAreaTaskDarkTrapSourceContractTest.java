package io.github.zoyluo.minecraftai.task;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Guards the one-cell recovery path used when a stuck companion is already standing in darkness. */
final class LightAreaTaskDarkTrapSourceContractTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/task/LightAreaTask.java");
    private static final Path WATCHER = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/task/DangerWatcher.java");

    @Test
    void darkTrapLightingIncludesAndPrioritizesTheOccupiedFloorCell() throws IOException {
        String source = Files.readString(SOURCE);
        int factory = source.indexOf("static LightAreaTask automaticDarkTrap()");
        int scan = source.indexOf("private void scan(AIPlayerEntity bot)");
        int picker = source.indexOf("private void pickNextTarget(AIPlayerEntity bot)");

        assertTrue(factory >= 0 && source.substring(factory, scan).contains("new LightAreaTask(2, 1, true, true)"),
                "dark-trap recovery must have a bounded one-torch automatic mode");
        assertTrue(source.substring(scan, picker)
                        .contains("!pos.equals(origin.above()) && (prioritizeOrigin || !pos.equals(origin))"),
                "the specialized scan must include the occupied floor while still excluding the head cell");
        assertTrue(source.substring(picker).contains("prioritizeOrigin && available.contains(origin)"),
                "the occupied dark floor must win over a merely nearby dark candidate");
        int predicate = source.indexOf("private static boolean isAutomaticDarkFloorCell");
        assertTrue(predicate > picker);
        String automaticPredicate = source.substring(predicate);
        assertTrue(automaticPredicate.contains("DangerWatcher.DARK_TRAP_LIGHT")
                        && automaticPredicate.contains("world.getMaxLocalRawBrightness(pos, world.getSkyDarken())"),
                "dark-trap recovery must use the detector's below-eight combined-light contract, not only the native spawn rule");
        assertTrue(source.substring(scan, predicate).contains("isAutomaticDarkFloorCell(bot.level(), target, prioritizeOrigin)"),
                "walking and placing must re-check the same special dark-trap predicate");
    }

    @Test
    void failedPhysicalDarkTrapLightingIsNotRecordedAsAnAnswer() throws IOException {
        String watcher = Files.readString(WATCHER);
        int responseMethod = watcher.indexOf("private boolean answerDarkTrapWithoutTeleport");
        int lightBranch = watcher.indexOf("if (response == DarkTrapResponse.LIGHT)", responseMethod);
        assertTrue(responseMethod >= 0 && lightBranch > responseMethod);
        String setup = watcher.substring(responseMethod, lightBranch);

        assertTrue(setup.contains("if (response != DarkTrapResponse.LIGHT)")
                        && setup.contains("darkTrapAnswers.put(bot.getUUID(), new DarkTrapAnswer(feet, response))"),
                "an unplaceable torch attempt must be retried instead of being permanently suppressed as answered");
    }
}
