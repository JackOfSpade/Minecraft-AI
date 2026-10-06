package io.github.zoyluo.minecraftai.brain;

import io.github.zoyluo.minecraftai.goal.GoalResult;
import io.github.zoyluo.minecraftai.task.TaskState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ApiFailureReportTest {
    private static final LlmApiException OVERLOADED =
            new LlmApiException("server_error: status=503", LlmApiException.Kind.TRANSIENT, 503, null, null);

    @Test
    void aFinishedRequestWhoseClosingWordsWereLostGetsNoFailureText() {
        // The 2026-10-05 incident: the gather completed, then the call that would have reported it got 503s.
        boolean completed = ApiFailureReport.requestCompleted(true, false, false, TaskState.COMPLETED, null);

        assertTrue(completed);
        assertEquals(ApiFailureReport.Decision.SILENT_REQUEST_COMPLETED,
                ApiFailureReport.decide(false, completed));
    }

    @Test
    void anUnfinishedRequestIsToldTheTruthAboutTheService() {
        assertEquals(ApiFailureReport.Decision.REPORT, ApiFailureReport.decide(false, false));
    }

    @Test
    void aFailureAlreadyReportedForThisInstructionIsNotRepeated() {
        assertEquals(ApiFailureReport.Decision.SILENT_ALREADY_REPORTED, ApiFailureReport.decide(true, false));
        assertEquals(ApiFailureReport.Decision.SILENT_ALREADY_REPORTED, ApiFailureReport.decide(true, true));
    }

    @Test
    void completionNeedsEveryPieceOfEvidence() {
        assertTrue(ApiFailureReport.requestCompleted(true, false, false, TaskState.COMPLETED, GoalResult.Status.COMPLETED));

        assertFalse(ApiFailureReport.requestCompleted(false, false, false, TaskState.COMPLETED, null),
                "the request never started");
        assertFalse(ApiFailureReport.requestCompleted(true, true, false, TaskState.COMPLETED, null),
                "work is still running or paused");
        assertFalse(ApiFailureReport.requestCompleted(true, false, true, TaskState.COMPLETED, null),
                "a task failure is waiting to be reported");
        assertFalse(ApiFailureReport.requestCompleted(true, false, false, TaskState.FAILED, null));
        assertFalse(ApiFailureReport.requestCompleted(true, false, false, TaskState.CANCELLED, null));
        assertFalse(ApiFailureReport.requestCompleted(true, false, false, TaskState.RUNNING, null));
        assertFalse(ApiFailureReport.requestCompleted(true, false, false, TaskState.COMPLETED, GoalResult.Status.PARTIAL));
        assertFalse(ApiFailureReport.requestCompleted(true, false, false, TaskState.COMPLETED, GoalResult.Status.FAILED));
        assertFalse(ApiFailureReport.requestCompleted(true, false, false, TaskState.COMPLETED, GoalResult.Status.CANCELLED));
    }

    @Test
    void anOutageIsReportedAsTheServiceBeingUnavailableNotAsTheRequestBeingUnclear() {
        String message = ApiFailureReport.playerMessage(OVERLOADED);

        assertTrue(message.contains("overloaded or unavailable"), message);
        assertTrue(message.contains("try again in a moment"), message);
        assertFalse(message.contains("work out"), message);
        assertFalse(message.contains("say it another way"), message);
    }

    @Test
    void rejectedCredentialsPointTheServerOwnerAtTheApiKey() {
        LlmApiException unauthorized =
                new LlmApiException("auth_error: status=401", LlmApiException.Kind.AUTH, 401, null, null);

        assertTrue(ApiFailureReport.playerMessage(unauthorized).contains("API key"));
    }

    @Test
    void aPermanentFailureSendsThePlayerToTheLog() {
        LlmApiException malformed = new LlmApiException("malformed_function_call");

        String message = ApiFailureReport.playerMessage(malformed);

        assertTrue(message.contains("usable answer"), message);
        assertTrue(message.contains("server log"), message);
    }

    @Test
    void everyMessageFitsTheChatLimitWithoutBeingCut() {
        for (LlmApiException.Kind kind : LlmApiException.Kind.values()) {
            String message = ApiFailureReport.playerMessage(new LlmApiException("x", kind, 0, null, null));
            assertTrue(message.length() <= 240, kind + " message is " + message.length() + " chars");
        }
    }
}
