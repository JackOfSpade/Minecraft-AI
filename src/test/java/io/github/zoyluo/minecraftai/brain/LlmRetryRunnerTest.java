package io.github.zoyluo.minecraftai.brain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link LlmRetryRunner} with a fake clock, a fake scheduler and a scripted model service, so
 * minutes of outage run instantly and deterministically.
 */
final class LlmRetryRunnerTest {
    private static final LlmApiException OVERLOADED = http(503, "server_error: status=503 body=high demand", null);

    private final FakeTime time = new FakeTime();
    private final List<String> answers = new ArrayList<>();
    private final List<LlmApiException> failures = new ArrayList<>();
    private final AtomicBoolean wanted = new AtomicBoolean(true);
    // Zero jitter: every wait is exactly the policy's floor, so the timeline below is exact.
    private final LlmRetryRunner runner = new LlmRetryRunner(
            Runnable::run, time, () -> time.nowMs, new LlmRetryPolicy(() -> 0.0D));

    @Test
    void aBurstOfOverloadErrorsIsWaitedOutAndThenAnswered() {
        // The 2026-10-05 outage: eleven 503s in a row, then the service recovers.
        Script service = new Script(time, repeat(OVERLOADED, 11), "answer");

        start(service);
        time.advanceBy(Duration.ofMinutes(10).toMillis());

        assertEquals(List.of("answer"), answers);
        assertTrue(failures.isEmpty());
        assertEquals(12, service.callTimes.size());
        for (int call = 1; call < service.callTimes.size(); call++) {
            long gap = service.callTimes.get(call) - service.callTimes.get(call - 1);
            assertTrue(gap >= 1_000, "attempt " + (call + 1) + " followed the previous one after only " + gap + " ms");
        }
        // 1+2+4+8 s, then seven waits of 15 s: nowhere near the 20 s the burst took before.
        assertEquals(120_000, service.callTimes.getLast());
    }

    @Test
    void anUnusableReplyIsHandedBackAtOnceSoTheBrainMetersTheRepairAsAModelCall() {
        // HTTP 200 with a garbled body is not an outage: replaying it here would be a free extra planner
        // turn outside the instruction's call budget. The one outcome per request is the failure itself.
        LlmApiException garbled = LlmApiException.unusableReply("bad_response: not an object", null);
        Script service = new Script(time, List.of(garbled), "never reached");

        start(service);
        time.advanceBy(Duration.ofMinutes(10).toMillis());

        assertEquals(1, service.callTimes.size());
        assertEquals(List.of(garbled), failures);
        assertTrue(answers.isEmpty());
        assertTrue(time.delays.isEmpty());
    }

    @Test
    void aPermanentErrorFailsFastWithoutAnyRetry() {
        LlmApiException badRequest = http(400, "http_error: status=400 body=bad schema", null);
        Script service = new Script(time, List.of(badRequest), "never reached");

        start(service);
        time.advanceBy(Duration.ofMinutes(10).toMillis());

        assertEquals(1, service.callTimes.size());
        assertEquals(List.of(badRequest), failures);
        assertEquals(LlmApiException.Kind.PERMANENT, failures.getFirst().kind());
        assertEquals(400, failures.getFirst().httpStatus());
        assertTrue(answers.isEmpty());
        assertTrue(time.delays.isEmpty());
    }

    @Test
    void rejectedCredentialsFailFastToo() {
        LlmApiException unauthorized = http(401, "auth_error: status=401 body=bad key", null);
        Script service = new Script(time, List.of(unauthorized), "never reached");

        start(service);
        time.advanceBy(Duration.ofMinutes(10).toMillis());

        assertEquals(1, service.callTimes.size());
        assertEquals(LlmApiException.Kind.AUTH, failures.getFirst().kind());
    }

    @Test
    void retryAfterIsHonouredAsTheMinimumWait() {
        LlmApiException limited = http(429, "rate_limited: status=429 body=slow down", Duration.ofSeconds(45));
        Script service = new Script(time, List.of(limited), "answer");

        start(service);
        time.advanceBy(44_999);
        assertEquals(1, service.callTimes.size(), "the service asked for 45 s; nothing may be sent earlier");
        time.advanceBy(1);

        assertEquals(2, service.callTimes.size());
        assertEquals(45_000, service.callTimes.get(1));
        assertEquals(List.of("answer"), answers);
    }

    @Test
    void aWaitIsCancelledByANewerRequestWithoutSendingAgain() {
        Script service = new Script(time, repeat(OVERLOADED, 50), "never reached");

        start(service);
        assertEquals(1, service.callTimes.size());
        assertEquals(1, time.delays.size(), "the first failure schedules a wait");

        wanted.set(false); // a new player message, or an intent cancel, replaced this request
        time.advanceBy(Duration.ofMinutes(10).toMillis());

        assertEquals(1, service.callTimes.size(), "the superseded request must not hit the service again");
        assertTrue(answers.isEmpty());
        assertEquals(1, failures.size());
        assertEquals(LlmRetryRunner.SUPERSEDED, failures.getFirst().getMessage());
        assertEquals(0, time.pending(), "nothing is left scheduled");
    }

    @Test
    void aRequestThatIsAlreadyStaleIsNeverSent() {
        Script service = new Script(time, List.of(), "never reached");
        wanted.set(false);

        start(service);

        assertTrue(service.callTimes.isEmpty());
        assertEquals(LlmRetryRunner.SUPERSEDED, failures.getFirst().getMessage());
    }

    @Test
    void givesUpAfterThePatienceAndReportsTheLastTransientFailure() {
        Script service = new Script(time, repeat(OVERLOADED, 1_000), "never reached");

        start(service);
        time.advanceBy(Duration.ofHours(1).toMillis());

        assertEquals(1, failures.size());
        assertEquals(LlmApiException.Kind.TRANSIENT, failures.getFirst().kind());
        assertEquals(503, failures.getFirst().httpStatus());
        assertTrue(answers.isEmpty());
        assertTrue(service.callTimes.getLast() <= LlmRetryPolicy.TOTAL_PATIENCE_MS);
        // It kept trying for the whole patience: 1+2+4+8 s, then a probe every 15 s.
        assertTrue(service.callTimes.getLast() >= LlmRetryPolicy.TOTAL_PATIENCE_MS - 15_000);
        assertEquals(0, time.pending());
    }

    @Test
    void timeoutsAndDroppedConnectionsAreRetried() {
        LlmApiException timeout = new LlmApiException("api_timeout: request timed out",
                LlmApiException.Kind.TRANSIENT, 0, null, new java.net.http.HttpTimeoutException("request timed out"));
        LlmApiException reset = new LlmApiException("io_error: connection reset",
                LlmApiException.Kind.TRANSIENT, 0, null, new java.io.IOException("connection reset"));
        Script service = new Script(time, List.of(timeout, reset), "answer");

        start(service);
        time.advanceBy(Duration.ofMinutes(1).toMillis());

        assertEquals(3, service.callTimes.size());
        assertEquals(List.of("answer"), answers);
    }

    @Test
    void anUnexpectedExceptionFromTheClientIsPermanent() {
        IllegalStateException bug = new IllegalStateException("boom");
        Script service = new Script(time, List.of(bug), "never reached");

        start(service);
        time.advanceBy(Duration.ofMinutes(10).toMillis());

        assertEquals(1, service.callTimes.size());
        assertEquals(LlmApiException.Kind.PERMANENT, failures.getFirst().kind());
        assertSame(bug, failures.getFirst().getCause());
        assertTrue(failures.getFirst().getMessage().contains("IllegalStateException"));
    }

    @Test
    void everyAttemptReplaysTheIdenticalRequest() {
        // The request context (player message, native tool results) is fixed when the request is
        // submitted; a retry sends that same context, not a rebuilt or extended one.
        List<String> request = List.of("user: gather 32 logs", "function_result: gather ok");
        List<List<String>> sent = new ArrayList<>();
        Script service = new Script(time, repeat(OVERLOADED, 3), "answer");

        runner.run("Moss", () -> {
            sent.add(request);
            return service.call();
        }, wanted::get, answers::add, failures::add);
        time.advanceBy(Duration.ofMinutes(1).toMillis());

        assertEquals(4, sent.size());
        for (List<String> attempt : sent) {
            assertSame(request, attempt);
        }
        assertEquals(List.of("user: gather 32 logs", "function_result: gather ok"), request);
        assertEquals(List.of("answer"), answers);
    }

    @Test
    void aWaitingPlayerIsToldOnceWhenTheRequestHasBeenUnansweredForTenSeconds() {
        // Waits of 1, 2, 4 and 8 s: the first three end at 7 s and the fourth would carry the request past
        // ten, so the notice comes with the fourth failure, at 7 s, and never again however long the outage.
        List<Long> noticeTimes = new ArrayList<>();
        Script service = new Script(time, repeat(OVERLOADED, 11), "answer");

        runner.run("Moss", service::call, wanted::get, failure -> noticeTimes.add(time.nowMs), answers::add, failures::add);
        time.advanceBy(Duration.ofMinutes(10).toMillis());

        assertEquals(List.of(7_000L), noticeTimes);
        assertEquals(List.of("answer"), answers);
    }

    @Test
    void aQuickBlipIsNotWorthANotice() {
        List<Long> noticeTimes = new ArrayList<>();
        Script service = new Script(time, repeat(OVERLOADED, 2), "answer");

        runner.run("Moss", service::call, wanted::get, failure -> noticeTimes.add(time.nowMs), answers::add, failures::add);
        time.advanceBy(Duration.ofMinutes(1).toMillis());

        assertTrue(noticeTimes.isEmpty(), "a retry that lands within ten seconds needs no word to the player");
        assertEquals(List.of("answer"), answers);
    }

    @Test
    void aServiceThatAsksForALongWaitIsReportedAtOnceNotTenSecondsLater() {
        LlmApiException limited = http(429, "rate_limited: status=429 body=slow down", Duration.ofSeconds(34));
        List<LlmApiException> notices = new ArrayList<>();
        Script service = new Script(time, List.of(limited), "answer");

        runner.run("Moss", service::call, wanted::get, notices::add, answers::add, failures::add);

        assertEquals(List.of(limited), notices, "the 34 s wait that has just begun is the reason to speak, at 0 s");
        assertEquals(0, time.nowMs);
    }

    @Test
    void aNoticeThatCannotBeDeliveredDoesNotStrandTheRetry() {
        Script service = new Script(time, repeat(OVERLOADED, 6), "answer");

        runner.run("Moss", service::call, wanted::get, failure -> {
            throw new IllegalStateException("server is stopping");
        }, answers::add, failures::add);
        time.advanceBy(Duration.ofMinutes(10).toMillis());

        assertEquals(List.of("answer"), answers);
    }

    @Test
    void anImmediateAnswerSchedulesNothing() {
        Script service = new Script(time, List.of(), "answer");

        start(service);

        assertEquals(List.of("answer"), answers);
        assertTrue(time.delays.isEmpty());
        assertEquals(1, service.callTimes.size());
    }

    private void start(Script service) {
        runner.run("Moss", service::call, wanted::get, answers::add, failures::add);
    }

    private static LlmApiException http(int status, String reason, Duration retryAfter) {
        return new LlmApiException(reason, LlmHttpStatus.kind(status), status, retryAfter, null);
    }

    private static List<Exception> repeat(Exception failure, int times) {
        List<Exception> failures = new ArrayList<>();
        for (int index = 0; index < times; index++) {
            failures.add(failure);
        }
        return failures;
    }

    /** A model service that fails with each scripted exception in turn, then answers every call. */
    private static final class Script {
        private final FakeTime time;
        private final Deque<Exception> failuresBeforeAnswer;
        private final String answer;
        final List<Long> callTimes = new ArrayList<>();

        Script(FakeTime time, List<? extends Exception> failuresBeforeAnswer, String answer) {
            this.time = time;
            this.failuresBeforeAnswer = new ArrayDeque<>(failuresBeforeAnswer);
            this.answer = answer;
        }

        String call() throws Exception {
            callTimes.add(time.nowMs);
            if (!failuresBeforeAnswer.isEmpty()) {
                throw failuresBeforeAnswer.removeFirst();
            }
            return answer;
        }
    }

    /** A clock and a scheduler in one: time only moves when a test advances it, running what falls due. */
    private static final class FakeTime implements LlmRetryRunner.Scheduler {
        private record Scheduled(long due, Runnable task) {
        }

        long nowMs;
        final List<Long> delays = new ArrayList<>();
        private final List<Scheduled> queue = new ArrayList<>();

        @Override
        public void schedule(long delayMillis, Runnable task) {
            delays.add(delayMillis);
            queue.add(new Scheduled(nowMs + delayMillis, task));
        }

        int pending() {
            return queue.size();
        }

        void advanceBy(long millis) {
            long target = nowMs + millis;
            while (true) {
                Scheduled next = queue.stream()
                        .filter(scheduled -> scheduled.due() <= target)
                        .min(Comparator.comparingLong(Scheduled::due))
                        .orElse(null);
                if (next == null) {
                    break;
                }
                queue.remove(next);
                nowMs = Math.max(nowMs, next.due());
                next.task().run();
            }
            nowMs = target;
        }
    }
}
