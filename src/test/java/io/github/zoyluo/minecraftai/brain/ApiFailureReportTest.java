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
        boolean completed = ApiFailureReport.requestCompleted(true, false, false, false, TaskState.COMPLETED, null);

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
        assertTrue(ApiFailureReport.requestCompleted(true, false, false, false, TaskState.COMPLETED, GoalResult.Status.COMPLETED));

        assertFalse(ApiFailureReport.requestCompleted(false, false, false, false, TaskState.COMPLETED, null),
                "the request never started");
        assertFalse(ApiFailureReport.requestCompleted(true, true, false, false, TaskState.COMPLETED, null),
                "work is still running or paused");
        assertFalse(ApiFailureReport.requestCompleted(true, false, true, false, TaskState.COMPLETED, null),
                "a task failure is waiting to be reported");
        assertFalse(ApiFailureReport.requestCompleted(true, false, false, true, TaskState.COMPLETED, null),
                "a long-term goal is still unfinished: an idle bot there is stalled, not done");
        assertFalse(ApiFailureReport.requestCompleted(true, false, false, false, TaskState.FAILED, null));
        assertFalse(ApiFailureReport.requestCompleted(true, false, false, false, TaskState.CANCELLED, null));
        assertFalse(ApiFailureReport.requestCompleted(true, false, false, false, TaskState.RUNNING, null));
        assertFalse(ApiFailureReport.requestCompleted(true, false, false, false, TaskState.COMPLETED, GoalResult.Status.PARTIAL));
        assertFalse(ApiFailureReport.requestCompleted(true, false, false, false, TaskState.COMPLETED, GoalResult.Status.FAILED));
        assertFalse(ApiFailureReport.requestCompleted(true, false, false, false, TaskState.COMPLETED, GoalResult.Status.CANCELLED));
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
    void aRejectedRequestSendsThePlayerToTheLog() {
        LlmApiException rejected = new LlmApiException("http_error: status=400", LlmApiException.Kind.PERMANENT, 400, null, null);

        String message = ApiFailureReport.playerMessage(rejected);

        assertTrue(message.contains("usable answer"), message);
        assertTrue(message.contains("server log"), message);
    }

    @Test
    void anUnusableReplyThatCouldNotBeRepairedIsReportedLikeARejectedRequest() {
        LlmApiException malformed = LlmApiException.unusableReply("malformed_function_call", null);

        assertEquals(ApiFailureReport.playerMessage(
                new LlmApiException("x", LlmApiException.Kind.PERMANENT, 400, null, null)),
                ApiFailureReport.playerMessage(malformed));
    }

    @Test
    void everyMessageFitsTheChatLimitWithoutBeingCut() {
        for (LlmApiException.Kind kind : LlmApiException.Kind.values()) {
            String message = ApiFailureReport.playerMessage(new LlmApiException("x", kind, 0, null, null));
            assertTrue(message.length() <= 240, kind + " message is " + message.length() + " chars");
        }
    }

    @Test
    void onlyAnUnusableReplyWithCallsLeftIsWorthAnotherPlannerCall() {
        // A garbled body or a nameless function call is a model quirk: asking again may well work, and the
        // call budget and the one-repair rule bound a model that keeps doing it.
        assertTrue(ApiFailureReport.repairWithAnotherCall(LlmApiException.Kind.UNUSABLE_REPLY, false, false, false));

        assertFalse(ApiFailureReport.repairWithAnotherCall(LlmApiException.Kind.UNUSABLE_REPLY, false, true, false),
                "the instruction's call budget is spent");
        assertFalse(ApiFailureReport.repairWithAnotherCall(LlmApiException.Kind.UNUSABLE_REPLY, true, false, false),
                "a failure report has its deterministic fallback line");
        assertFalse(ApiFailureReport.repairWithAnotherCall(LlmApiException.Kind.UNUSABLE_REPLY, false, false, true),
                "the repair itself came back unusable: report it instead of asking a third time");
        // Asking again changes nothing for these: an outage is waited out below the budget, and a
        // rejected request or key is rejected again.
        assertFalse(ApiFailureReport.repairWithAnotherCall(LlmApiException.Kind.TRANSIENT, false, false, false));
        assertFalse(ApiFailureReport.repairWithAnotherCall(LlmApiException.Kind.PERMANENT, false, false, false));
        assertFalse(ApiFailureReport.repairWithAnotherCall(LlmApiException.Kind.AUTH, false, false, false));
    }

    @Test
    void aGoalWakeWaitsOneRetryPatienceAfterAFinalFailure() {
        // 5 minutes at 50 ms per tick.
        assertEquals(6_000, ApiFailureReport.GOAL_WAKE_COOLDOWN_TICKS);
        assertEquals(LlmRetryPolicy.TOTAL_PATIENCE_MS, ApiFailureReport.GOAL_WAKE_COOLDOWN_TICKS * 50L);
    }
}
