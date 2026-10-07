package io.github.zoyluo.minecraftai.brain;

import java.time.Duration;
import java.util.OptionalLong;
import java.util.function.DoubleSupplier;

/**
 * How long to wait before asking the model service again after a transient failure, and when to
 * stop asking. The numbers are the usual client etiquette for an overloaded or rate-limiting API
 * (truncated exponential backoff with jitter), not a gameplay rule.
 *
 * <p>These retries are not model calls in the sense of {@link PlayerInstructionCallBudget}: that
 * budget meters the planner's tool-loop turns for one player instruction. Re-sending a request the
 * service never answered adds no turn, so it is accounted for here, by elapsed time, instead.</p>
 */
final class LlmRetryPolicy {
    /**
     * First wait. A 503 "high demand" spike usually clears within seconds, so the first retry is
     * quick; the equal jitter below keeps it from landing in the same instant as other clients'.
     */
    static final long INITIAL_BACKOFF_MS = 2_000L;
    /** Waits double up to this, so a recovered service is noticed within about half a minute however long the outage ran. */
    static final long MAX_BACKOFF_MS = 30_000L;
    /**
     * How long one request may stay unanswered before the player is told. Long enough to ride out a
     * typical multi-minute provider incident (the capped waits then probe it about twice a minute),
     * short enough that the world state replayed to the model is not stale when it finally answers.
     * A newer player message or intent cancel ends the wait at once, whatever is left of this.
     */
    static final long TOTAL_PATIENCE_MS = 5 * 60_000L;
    /**
     * How long a player waits in silence before being told the bot is still trying. A person asked
     * something wonders after about ten seconds without a word, and a quick blip (one 503 retried a
     * second later) must not turn into chatter.
     */
    static final long NOTICE_AFTER_MS = 10_000L;

    private final DoubleSupplier jitter;

    /** @param jitter a uniform random value in [0, 1) */
    LlmRetryPolicy(DoubleSupplier jitter) {
        this.jitter = jitter;
    }

    /**
     * @param failures how many attempts of this request have failed so far (at least 1)
     * @param retryAfter the wait the service asked for, or null
     * @param elapsedMs time since the first attempt started
     * @return the wait before the next attempt, or empty when the patience is spent (or the service
     *         asked for a wait that would outlast it)
     */
    OptionalLong delayBeforeRetry(int failures, Duration retryAfter, long elapsedMs) {
        int doublings = Math.min(Math.max(failures - 1, 0), 20);
        long ceiling = Math.min(MAX_BACKOFF_MS, INITIAL_BACKOFF_MS << doublings);
        // Equal jitter: half the ceiling is guaranteed, so a burst can never retry in a tight loop,
        // and the other half is random so simultaneous clients spread out.
        long half = ceiling / 2;
        long delay = half + (long) (jitter.getAsDouble() * half);
        if (retryAfter != null) {
            // The service's own request is a floor, never something to wait less than.
            delay = Math.max(delay, retryAfter.toMillis());
        }
        return elapsedMs + delay > TOTAL_PATIENCE_MS ? OptionalLong.empty() : OptionalLong.of(delay);
    }

    /**
     * Whether the wait that is about to start is the one that makes the request unanswered for
     * {@link #NOTICE_AFTER_MS}: that is when a still-waiting player is told, once.
     *
     * @param elapsedMs time since the first attempt started
     * @param delayMs the wait about to start
     */
    static boolean worthTellingThePlayer(long elapsedMs, long delayMs) {
        return elapsedMs + delayMs >= NOTICE_AFTER_MS;
    }
}
