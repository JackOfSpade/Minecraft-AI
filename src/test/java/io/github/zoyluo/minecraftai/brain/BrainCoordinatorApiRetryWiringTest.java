package io.github.zoyluo.minecraftai.brain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-text guards for how {@link BrainCoordinator} hands transient model failures to the
 * retry layer. The behaviour itself (backoff, Retry-After, cancel, give-up) is covered with a fake
 * clock in {@link LlmRetryRunnerTest}; BrainCoordinator cannot be built without a bootstrapped
 * game, so only the wiring that keeps those guarantees true is pinned here.
 */
final class BrainCoordinatorApiRetryWiringTest {
    private static String read(String file) throws IOException {
        return Files.readString(Path.of("src/main/java/io/github/zoyluo/minecraftai/" + file));
    }

    /** The text from {@code signature} to the next method declaration at class-member indent. */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, signature + " not found");
        int end = source.indexOf("\n    }\n", start);
        return source.substring(start, end);
    }

    @Test
    void aFinalApiErrorNeverResubmitsAndNeverSpendsTheModelCallBudget() throws IOException {
        String onError = methodBody(read("brain/BrainCoordinator.java"), "private void onError(");

        // Transient failures were already replayed below the budget; what reaches here is final.
        assertFalse(onError.contains("submit("), "an error must not start another model call");
        assertFalse(onError.contains("beginEpoch"), "an error must not open a new decision epoch");
        assertFalse(onError.contains("callBudget"), "an error must not touch the planner call budget");
        assertFalse(onError.contains("history.add"), "an error must not alter the replayed request context");
    }

    @Test
    void theFinalFailureTextComesFromTheTruthfulReportAndIsNeverTheGenericApology() throws IOException {
        String coordinator = read("brain/BrainCoordinator.java");
        String onError = methodBody(coordinator, "private void onError(");
        String report = methodBody(coordinator, "private void reportApiFailure(");

        assertFalse(onError.contains("finishCallBudget"), "api errors must not end in the 'could not work out' apology");
        assertTrue(report.contains("ApiFailureReport.playerMessage(failure)"));
        assertTrue(report.contains("ApiFailureReport.requestCompleted("),
                "a request that already finished must not be followed by a failure text");
    }

    @Test
    void theExecutorRetriesBelowTheBudgetAndDropsAWaitOnceItsLeaseIsReplaced() throws IOException {
        String coordinator = read("brain/BrainCoordinator.java");

        assertTrue(coordinator.contains("OpenAiCompatibleApiClient.singleAttempt(config.llm())"),
                "the planner client must not stack its own sleep-and-retry under the brain's retry");
        assertTrue(coordinator.contains("this::leaseInFlight"));
        assertTrue(methodBody(coordinator, "private boolean leaseInFlight(").contains("decision.isInFlight(lease)"),
                "a backoff must end when a newer message, an intent cancel or a reset replaces the lease");
    }

    @Test
    void everyModelCallGoesThroughTheRetryRunner() throws IOException {
        String executor = read("brain/AsyncDecisionExecutor.java");

        assertTrue(executor.contains("retryRunner.run("));
        assertFalse(executor.contains("executor.submit("), "a direct submit would bypass the retry layer");
    }
}
