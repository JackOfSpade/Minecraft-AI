package io.github.zoyluo.minecraftai.brain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-text contracts for wiring that cannot be constructed without a bootstrapped game
 * (ToolRegistry/BrainCoordinator touch Minecraft registries). Each pins one invariant of the
 * brain fixes: forced tool use, guaranteed failure reports, ambient-chat logging, the lookup tool.
 */
final class BrainCoordinatorBrainFixesContractTest {
    private static String read(String file) throws IOException {
        return Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/" + file));
    }

    /** True when the source contains the text with any run of whitespace (incl. CRLF) matching any other. */
    private static boolean containsIgnoringWhitespace(String source, String text) {
        return indexIgnoringWhitespace(source, text) >= 0;
    }

    private static int indexIgnoringWhitespace(String source, String text) {
        StringBuilder regex = new StringBuilder();
        for (String part : text.trim().split("\\s+")) {
            if (regex.length() > 0) {
                regex.append("\\s+");
            }
            regex.append(Pattern.quote(part));
        }
        var matcher = Pattern.compile(regex.toString()).matcher(source);
        return matcher.find() ? matcher.start() : -1;
    }

    @Test
    void theWithheldSayFlagReachesBothProviderClients() throws IOException {
        String coordinator = read("brain/BrainCoordinator.java");
        String executor = read("brain/AsyncDecisionExecutor.java");

        assertTrue(containsIgnoringWhitespace(coordinator, "toolsForCall(toolRegistry.tools("),
                "the offered tool set must be filtered when say is withheld");
        assertTrue(containsIgnoringWhitespace(coordinator, "geminiRequest, withholdSay,"),
                "the flag must reach the executor so chat-completions requires a tool call");
        assertTrue(containsIgnoringWhitespace(executor, "apiClient.chatRequiringToolCall(historySnapshot, tools)"),
                "chat-completions must send tool_choice=required on a forced call");
        assertTrue(containsIgnoringWhitespace(read("brain/GeminiInteractionsApiClient.java"),
                        "allowedTools.addProperty(\"mode\", \"any\")"),
                "the Gemini Interactions client must keep forcing a function call from the offered set");
    }

    @Test
    void failureWakesAreCheckedBeforeThePlannerBudgetAndUseTheirOwnReservation() throws IOException {
        String coordinator = read("brain/BrainCoordinator.java");
        int failureWake = indexIgnoringWhitespace(coordinator, "maybeInjectFailure(bot, conversation);");
        int exhaustedWake = indexIgnoringWhitespace(coordinator, "finishCallBudget(bot, conversation, \"automatic_wake\")");

        assertTrue(failureWake > 0 && exhaustedWake > 0, "expected both wake branches");
        assertTrue(failureWake < exhaustedWake,
                "an exhausted planner budget must not swallow the failure report");
        assertTrue(containsIgnoringWhitespace(coordinator, "conversation.decision.beginEpoch(), true);"),
                "the failure wake must submit with the failure-report reservation");
        assertTrue(containsIgnoringWhitespace(coordinator, "submit(bot, conversation, nextLease, true);"),
                "the continuation failure injection must use the failure-report reservation");
        assertTrue(coordinator.contains("reportFailureWithoutModel"),
                "with every call spent the player must still be told, deterministically");
        assertTrue(containsIgnoringWhitespace(coordinator, "FailureWake.REPORT_DIRECTLY) {"),
                "a directly reported failure must end the wake instead of also finishing the budget");
    }

    @Test
    void budgetExhaustionWithoutAStartedRequestAlwaysTellsThePlayer() throws IOException {
        String coordinator = read("brain/BrainCoordinator.java");

        assertTrue(containsIgnoringWhitespace(coordinator,
                        "InstructionRoundEvaluator.budgetReport( conversation.budgetExhaustionReported, workActive, conversation.requestStarted,"),
                "the report decision must depend on whether THIS instruction's request started, not on running work");
        assertTrue(containsIgnoringWhitespace(coordinator, "couldNotStartMessage(conversation.lastInstruction)"),
                "the player must hear which command could not be started");
    }

    @Test
    void theInstructionChainAndWithholdSayWiringStaysInPlace() throws IOException {
        String coordinator = read("brain/BrainCoordinator.java");

        assertTrue(containsIgnoringWhitespace(coordinator, "conversation.instructionChain.beginPlayerInstruction();"),
                "a player instruction must mark the chain as belonging to the player");
        assertTrue(containsIgnoringWhitespace(coordinator, "conversation.instructionChain.beginAutonomousWake();"),
                "an autonomous wake must not inherit the old player instruction");
        assertTrue(containsIgnoringWhitespace(coordinator,
                        "conversation.instructionChain.playerInstruction() && !conversation.lastInstruction.isBlank()"),
                "the budget report may only blame a player instruction of this chain");
        assertTrue(containsIgnoringWhitespace(coordinator,
                        "InstructionRoundEvaluator.nextWithholdSay( conversation.withholdSayNextCall, failureReportCall,"),
                "a failure-report round must leave the withheld-say flag alone");
    }

    @Test
    void theRequestStartedFlagIsNeverDerivedFromMereRunningWork() throws IOException {
        String coordinator = read("brain/BrainCoordinator.java");

        assertTrue(!Pattern.compile("requestStarted\\s*=\\s*true").matcher(coordinator).find(),
                "only the round evaluator may move the flag (via a successful work-start tool), never a raw assignment");
        assertTrue(!Pattern.compile("if\\s*\\(\\s*workActive\\s*\\|\\|\\s*actionToolSucceeded").matcher(coordinator).find(),
                "running work of any origin must not count as the request having started");
        assertTrue(containsIgnoringWhitespace(coordinator, "conversation.failureReportCall = failureReport;"),
                "a failure report is marked per call, not by mutating the instruction-level flags");
    }


    @Test
    void ambientChatAndPlainTextRepliesAreLogged() throws IOException {
        assertTrue(read("brain/AmbientConversationCoordinator.java").contains("\"ambient_say\""));
        assertTrue(read("brain/BrainCoordinator.java").contains("\"bot_text_reply\""));
    }

    @Test
    void lookupRecipeIsRegisteredReadOnlyAndBackedByTheRecipeIndex() throws IOException {
        String registry = read("brain/ToolRegistry.java");
        String lookup = read("craft/ItemLookup.java");

        assertTrue(registry.contains("register(\"lookup_recipe\""));
        assertTrue(registry.contains("ItemLookup.describe("));
        assertTrue(lookup.contains("RecipeRegistry.find(item)"),
                "the answer must come from the hand table + RuntimeRecipeIndex, not memory");
        assertTrue(read("craft/RecipeRegistry.java").contains("RuntimeRecipeIndex.find(output)"));
    }

    @Test
    void breakBlocksAcceptsAnyLeavesInBothEntryPoints() throws IOException {
        String registry = read("brain/ToolRegistry.java");

        assertTrue(registry.contains("isAnyLeavesRequest(args, \"block\")"));
        assertTrue(registry.contains("isAnyLeavesRequest(params, \"block\")"));
        assertTrue(read("task/GatherQuotaTask.java").contains("public static GatherQuotaTask breakLeaves("));
        assertTrue(read("task/GatherQuotaTask.java").contains("categoryForBreak(targetState, bot, !countBrokenBlocks)"),
                "the harvest tool policy must know whether drops matter");
    }
}
