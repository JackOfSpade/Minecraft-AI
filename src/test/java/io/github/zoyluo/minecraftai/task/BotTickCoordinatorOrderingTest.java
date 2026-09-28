package io.github.zoyluo.aibot.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the position of the mining assist call inside {@code BotTickCoordinator.tick} (mining-assist design
 * 2.3 step 2 and 8.1): after the danger scan has produced {@code handled}, before the goal executor, and
 * without touching the existing control flow. The call must be a bare, unconditional statement so it can
 * never consume the tick or skip the bot's remaining checks.
 */
class BotTickCoordinatorOrderingTest {
    private static final Path SOURCE = Path.of("src/main/java/io/github/zoyluo/aibot/task/BotTickCoordinator.java");
    private static final String CALL = "MiningAssistCoordinator.INSTANCE.tickBot(server, bot, handled);";

    private static String source() throws IOException {
        return Files.readString(SOURCE);
    }

    @Test
    void theCoordinatorSitsBetweenTheDangerScanAndTheGoalExecutorAndReceivesHandled() throws IOException {
        String source = source();
        int navSafety = source.indexOf("NavSafetyNet.INSTANCE.tickBot(server, bot)");
        int stuck = source.indexOf("StuckWatcher.INSTANCE.tickBot(server, bot);");
        int handledDeclaration = source.indexOf("boolean handled = runDanger && DangerWatcher.INSTANCE.scanBot(server, bot);");
        int assist = source.indexOf(CALL);
        int goalExecutor = source.indexOf("GoalExecutor.INSTANCE.tickBot(server, bot)");
        assertTrue(navSafety > 0 && stuck > navSafety && handledDeclaration > stuck,
                "the existing order NavSafetyNet, StuckWatcher, DangerWatcher is unchanged");
        assertTrue(assist > handledDeclaration, "after DangerWatcher.scanBot has produced handled");
        assertTrue(goalExecutor > assist, "before GoalExecutor");
        assertEquals(1, count(source, "MiningAssistCoordinator.INSTANCE.tickBot("), "exactly one call site");
    }

    @Test
    void theCallIsABareUnconditionalStatementDirectlyAfterTheHandledDeclaration() throws IOException {
        String source = source();
        int handledDeclaration = source.indexOf("boolean handled = ");
        int declarationEnd = source.indexOf('\n', handledDeclaration) + 1;
        int assist = source.indexOf(CALL);
        String between = source.substring(declarationEnd, assist);
        for (String line : between.split("\n")) {
            String trimmed = line.strip();
            assertTrue(trimmed.isEmpty() || trimmed.startsWith("//"),
                    "only comments may sit between handled and the call, found: " + trimmed);
        }
        int lineStart = source.lastIndexOf('\n', assist) + 1;
        assertEquals("            " + CALL, source.substring(lineStart, source.indexOf('\n', assist)),
                "same indentation as the surrounding for-body statements: not nested in an if or a lambda");
    }

    @Test
    void theExistingControlFlowAroundTheCallIsUntouched() throws IOException {
        String source = source();
        assertTrue(source.contains(
                "            if (NavSafetyNet.INSTANCE.tickBot(server, bot)) {\n"
                        + "                continue;\n"
                        + "            }\n"));
        assertTrue(source.contains(
                "            if (!handled && GoalExecutor.INSTANCE.tickBot(server, bot)) {\n"
                        + "                continue;\n"
                        + "            }\n"));
        assertTrue(source.contains(
                "            if (!handled && runBackground) {\n"
                        + "                io.github.zoyluo.aibot.action.EquipAction.equipBestArmor(bot);"));
        assertFalse(source.contains("= MiningAssistCoordinator"), "the call's result is never used");
        assertFalse(source.contains("if (MiningAssistCoordinator"), "the call never gates the tick");
    }

    @Test
    void theCoordinatorReturnsNothingSoItCannotConsumeTheTick() throws IOException {
        String coordinator = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/aibot/coordination/MiningAssistCoordinator.java"));
        assertTrue(coordinator.contains(
                "public void tickBot(MinecraftServer server, AIPlayerEntity bot, boolean handled) {"));
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + 1)) {
            count++;
        }
        return count;
    }
}
