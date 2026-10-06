package io.github.zoyluo.minecraftai.brain;

import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogCategory;

import java.util.OptionalLong;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Sends one model request and, while the service only fails transiently, sends it again after a
 * growing wait ({@link LlmRetryPolicy}) until it answers, fails for good, or is no longer wanted.
 *
 * <p>Every attempt replays the identical request, so a retry can neither duplicate a tool result
 * nor lose the player's message, and the caller sees exactly one outcome per request: a response,
 * or a final failure. A wait is a scheduled task, not a sleeping thread, and it checks
 * {@code stillWanted} before each attempt, so a request that was replaced or cancelled meanwhile
 * (a new player message, an intent cancel) is dropped instead of being sent.</p>
 *
 * <p>Nothing here knows the per-instruction model-call budget: a retry is not another planner turn.</p>
 */
final class LlmRetryRunner {
    /** What a request that stopped being wanted reports as its final failure; the caller discards it as stale. */
    static final String SUPERSEDED = "request_superseded";

    /** Runs a task after a delay without a thread waiting for it. */
    interface Scheduler {
        void schedule(long delayMillis, Runnable task);
    }

    /** One try of the request: a single HTTP exchange. */
    interface Attempt<T> {
        T call() throws Exception;
    }

    private final Executor worker;
    private final Scheduler scheduler;
    private final LongSupplier clockMillis;
    private final LlmRetryPolicy policy;

    LlmRetryRunner(Executor worker, Scheduler scheduler, LongSupplier clockMillis, LlmRetryPolicy policy) {
        this.worker = worker;
        this.scheduler = scheduler;
        this.clockMillis = clockMillis;
        this.policy = policy;
    }

    /**
     * @param botName only for log lines
     * @param stillWanted false once the request was replaced or cancelled
     * @param onSuccess called once, on a worker thread, with the answer
     * @param onFailure called once, on a worker thread, when the request fails for good or is no longer wanted
     */
    <T> void run(String botName,
                 Attempt<T> attempt,
                 BooleanSupplier stillWanted,
                 Consumer<T> onSuccess,
                 Consumer<LlmApiException> onFailure) {
        long started = clockMillis.getAsLong();
        worker.execute(() -> tryOnce(botName, attempt, stillWanted, onSuccess, onFailure, started, 1));
    }

    private <T> void tryOnce(String botName,
                             Attempt<T> attempt,
                             BooleanSupplier stillWanted,
                             Consumer<T> onSuccess,
                             Consumer<LlmApiException> onFailure,
                             long started,
                             int attemptNumber) {
        if (!stillWanted.getAsBoolean()) {
            if (attemptNumber > 1) {
                BotLog.api(null, "llm_retry_cancelled",
                        "bot", botName,
                        "attempts", attemptNumber - 1,
                        "elapsed_ms", clockMillis.getAsLong() - started);
            }
            onFailure.accept(new LlmApiException(SUPERSEDED));
            return;
        }
        T result;
        try {
            result = attempt.call();
        } catch (Exception exception) {
            LlmApiException failure = LlmApiException.classify(exception);
            long elapsed = clockMillis.getAsLong() - started;
            OptionalLong delay = failure.isTransient()
                    ? policy.delayBeforeRetry(attemptNumber, failure.retryAfter(), elapsed)
                    : OptionalLong.empty();
            if (delay.isEmpty()) {
                BotLog.warn(LogCategory.API, null, "llm_retry_gave_up",
                        "bot", botName,
                        "kind", failure.kind(),
                        "status", failure.httpStatus(),
                        "attempts", attemptNumber,
                        "elapsed_ms", elapsed,
                        "reason", abbreviate(failure.getMessage()));
                onFailure.accept(failure);
                return;
            }
            BotLog.warn(LogCategory.API, null, "llm_retry_scheduled",
                    "bot", botName,
                    "failed_attempts", attemptNumber,
                    "retry_in_ms", delay.getAsLong(),
                    "retry_after_ms", failure.retryAfter() == null ? 0 : failure.retryAfter().toMillis(),
                    "elapsed_ms", elapsed,
                    "status", failure.httpStatus(),
                    "reason", abbreviate(failure.getMessage()));
            scheduler.schedule(delay.getAsLong(), () -> worker.execute(() ->
                    tryOnce(botName, attempt, stillWanted, onSuccess, onFailure, started, attemptNumber + 1)));
            return;
        }
        if (attemptNumber > 1) {
            BotLog.api(null, "llm_retry_recovered",
                    "bot", botName,
                    "attempts", attemptNumber,
                    "elapsed_ms", clockMillis.getAsLong() - started);
        }
        onSuccess.accept(result);
    }

    private static String abbreviate(String message) {
        if (message == null) {
            return "";
        }
        String singleLine = message.replace('\n', ' ').replace('\r', ' ').trim();
        return singleLine.length() > 180 ? singleLine.substring(0, 180) : singleLine;
    }
}
