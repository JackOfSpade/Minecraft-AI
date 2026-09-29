package io.github.zoyluo.minecraftai.brain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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

    @Test
    void theWithheldSayFlagReachesBothProviderClients() throws IOException {
        String coordinator = read("brain/BrainCoordinator.java");
        String executor = read("brain/AsyncDecisionExecutor.java");

        assertTrue(coordinator.contains("toolsForCall(toolRegistry.tools("),
                "the offered tool set must be filtered when say is withheld");
        assertTrue(coordinator.contains("                    withholdSay,\n"),
                "the flag must reach the executor so chat-completions requires a tool call");
        assertTrue(executor.contains("apiClient.chatRequiringToolCall(historySnapshot, tools)"),
                "chat-completions must send tool_choice=required on a forced call");
        assertTrue(read("brain/GeminiInteractionsApiClient.java").contains("allowedTools.addProperty(\"mode\", \"any\")"),
                "the Gemini Interactions client must keep forcing a function call from the offered set");
    }

    @Test
    void failureWakesAreCheckedBeforeThePlannerBudgetAndUseTheirOwnReservation() throws IOException {
        String coordinator = read("brain/BrainCoordinator.java");
        int failureWake = coordinator.indexOf("if (hasFailure && maybeInjectFailure(bot, conversation))");
        int exhaustedWake = coordinator.indexOf("finishCallBudget(bot, conversation, \"automatic_wake\")");

        assertTrue(failureWake > 0 && exhaustedWake > 0, "expected both wake branches");
        assertTrue(failureWake < exhaustedWake,
                "an exhausted planner budget must not swallow the failure report");
        assertTrue(coordinator.contains("conversation.decision.beginEpoch(), true);"),
                "the failure wake must submit with the failure-report reservation");
        assertTrue(coordinator.contains("submit(bot, conversation, nextLease, true);"),
                "the continuation failure injection must use the failure-report reservation");
        assertTrue(coordinator.contains("reportFailureWithoutModel"),
                "with every call spent the player must still be told, deterministically");
    }

    @Test
    void budgetExhaustionWithoutAStartedRequestAlwaysTellsThePlayer() throws IOException {
        String coordinator = read("brain/BrainCoordinator.java");

        assertTrue(coordinator.contains("boolean requestNeverStarted = !conversation.initialActionStarted"),
                "the report decision must depend on whether the request started");
        assertTrue(coordinator.contains("couldNotStartMessage(conversation.lastInstruction)"),
                "the player must hear which command could not be started");
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
