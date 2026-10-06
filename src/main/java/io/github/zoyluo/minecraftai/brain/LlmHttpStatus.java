package io.github.zoyluo.minecraftai.brain;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Shared HTTP-status-to-reason classification for the LLM clients. Both
 * {@link OpenAiCompatibleApiClient} and {@link GeminiInteractionsApiClient} turn a failed
 * response into the same family of reason strings; only the response-body excerpt length
 * they keep for diagnostics differs.
 */
final class LlmHttpStatus {
    private static final Duration ABSURD_WAIT = Duration.ofDays(365);

    private LlmHttpStatus() {
    }

    static String classify(int status, String body, int excerptLength) {
        String excerpt = body == null ? "" : body.substring(0, Math.min(excerptLength, body.length()));
        if (status == 429) {
            return "rate_limited: status=429 body=" + excerpt;
        }
        if (status == 408) {
            return "api_timeout: status=408 body=" + excerpt;
        }
        if (status >= 500) {
            return "server_error: status=" + status + " body=" + excerpt;
        }
        if (status == 401 || status == 403) {
            return "auth_error: status=" + status + " body=" + excerpt;
        }
        return "http_error: status=" + status + " body=" + excerpt;
    }

    /**
     * Whether asking again can succeed: the request timed out (408), the service shed load (429) or
     * failed on its side (5xx). Every other status says the request itself is wrong.
     */
    static LlmApiException.Kind kind(int status) {
        if (status == 408 || status == 429 || status >= 500) {
            return LlmApiException.Kind.TRANSIENT;
        }
        if (status == 401 || status == 403) {
            return LlmApiException.Kind.AUTH;
        }
        return LlmApiException.Kind.PERMANENT;
    }

    /** The wait a failed response asked for through {@code Retry-After}, or null when it gave none. */
    static Duration retryAfter(HttpResponse<?> response) {
        return parseRetryAfter(response.headers().firstValue("Retry-After").orElse(null), Instant.now());
    }

    /**
     * Parses {@code Retry-After}, which RFC 9110 allows as either a number of seconds or an HTTP
     * date. Returns null for an absent or unreadable value; a date already in the past means "now".
     */
    static Duration parseRetryAfter(String value, Instant now) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.chars().allMatch(Character::isDigit)) {
            long seconds;
            try {
                seconds = Long.parseLong(trimmed);
            } catch (NumberFormatException exception) {
                seconds = Long.MAX_VALUE;
            }
            // Only keeps Duration.toMillis() from overflowing: any wait this long is far beyond the
            // retry policy's patience, which then gives up exactly as it would for a one-year wait.
            return Duration.ofSeconds(Math.min(seconds, ABSURD_WAIT.toSeconds()));
        }
        try {
            Duration wait = Duration.between(now, ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            return wait.isNegative() ? Duration.ZERO : wait;
        } catch (DateTimeParseException exception) {
            return null;
        }
    }
}
