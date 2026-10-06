package io.github.zoyluo.minecraftai.brain;

import java.time.Duration;

/**
 * A failed LLM request, tagged with whether trying the very same request again can help.
 *
 * <p>The tag is what lets the brain react to an outage the way an API client should: wait and
 * retry while the service is merely overloaded or unreachable ({@link Kind#TRANSIENT}), but fail
 * at once, with a precise reason, when no retry could change the answer.</p>
 */
public class LlmApiException extends Exception {
    public enum Kind {
        /** The service or the network could not answer right now: HTTP 408/429/5xx, a timeout, a dropped connection. */
        TRANSIENT,
        /** Retrying cannot help, and only the server owner can fix it: missing or rejected credentials (401/403). */
        AUTH,
        /** Retrying cannot help: the request was rejected (other 4xx) or the code failed. */
        PERMANENT,
        /**
         * The service answered (HTTP 200) but the body was empty, unparsable or structurally invalid, such
         * as an empty choice list or a function call without id or name. The same request is not doomed:
         * model output is not deterministic, so asking again may give a usable reply. That is another
         * planner turn, not a transport retry, so the brain spends one call of the instruction's model-call
         * budget on it (see {@link ApiFailureReport#repairWithAnotherCall}) instead of the retry runner
         * replaying it for free.
         */
        UNUSABLE_REPLY
    }

    static final int HTTP_OK = 200;

    private final Kind kind;
    private final int httpStatus;
    private final Duration retryAfter;

    /** An unclassified failure is permanent: a retry storm against an unknown error is worse than failing fast. */
    public LlmApiException(String message) {
        this(message, Kind.PERMANENT, 0, null, null);
    }

    public LlmApiException(String message, Throwable cause) {
        this(message, Kind.PERMANENT, 0, null, cause);
    }

    /**
     * @param httpStatus the HTTP status of the reply, or 0 when the failure was not an HTTP reply
     * @param retryAfter how long the service asked us to wait ({@code Retry-After}), or null
     */
    public LlmApiException(String message, Kind kind, int httpStatus, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.retryAfter = retryAfter;
    }

    /** A reply that came back as HTTP 200 but could not be used; see {@link Kind#UNUSABLE_REPLY}. */
    static LlmApiException unusableReply(String message, Throwable cause) {
        return new LlmApiException(message, Kind.UNUSABLE_REPLY, HTTP_OK, null, cause);
    }

    public Kind kind() {
        return kind;
    }

    public boolean isTransient() {
        return kind == Kind.TRANSIENT;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /** The wait the service asked for, or null when it gave none. */
    public Duration retryAfter() {
        return retryAfter;
    }

    /** The failure as an {@link LlmApiException}: an unexpected exception from the client code is permanent. */
    static LlmApiException classify(Throwable failure) {
        if (failure instanceof LlmApiException classified) {
            return classified;
        }
        String message = failure.getMessage();
        return new LlmApiException(
                failure.getClass().getSimpleName() + (message == null ? "" : ": " + message), failure);
    }
}
