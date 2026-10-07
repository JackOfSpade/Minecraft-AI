package io.github.zoyluo.minecraftai.brain;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared HTTP-status-to-reason classification for the LLM clients. Both
 * {@link OpenAiCompatibleApiClient} and {@link GeminiInteractionsApiClient} turn a failed
 * response into the same family of reason strings; only the response-body excerpt length
 * they keep for diagnostics differs.
 */
final class LlmHttpStatus {
    private static final Duration ABSURD_WAIT = Duration.ofDays(365);
    /** google.rpc.RetryInfo as protobuf JSON writes a Duration: seconds with up to nine fractional digits, then "s". */
    private static final Pattern PROTO_DURATION = Pattern.compile("(\\d+)(?:\\.(\\d{1,9}))?s");

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
        if (status == 401 || status == 403 || rejectsApiKey(status, body)) {
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

    /**
     * {@link #kind(int)}, read with the body: Google answers a wrong or expired API key with HTTP 400
     * (INVALID_ARGUMENT, reason API_KEY_INVALID), not 401, so the status alone would call a credentials
     * problem a bad request.
     */
    static LlmApiException.Kind kind(int status, String body) {
        return rejectsApiKey(status, body) ? LlmApiException.Kind.AUTH : kind(status);
    }

    /** Google's HTTP 400 for an API key that is wrong, malformed or expired. */
    static boolean rejectsApiKey(int status, String body) {
        if (status != 400 || body == null) {
            return false;
        }
        if (body.contains("API_KEY_INVALID")) {
            return true;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        return lower.contains("api key not valid") || lower.contains("api key expired");
    }

    /** The wait a failed response asked for through {@code Retry-After}, or null when it gave none. */
    static Duration retryAfter(HttpResponse<?> response) {
        return parseRetryAfter(response.headers().firstValue("Retry-After").orElse(null), Instant.now());
    }

    /**
     * The wait a failed response asked for, by header or, as Gemini's 429 does, in the error body
     * ({@link #retryDelayFromBody}); the longer one when it gave both, null when it gave neither.
     */
    static Duration retryAfter(HttpResponse<?> response, String body) {
        Duration header = retryAfter(response);
        Duration inBody = retryDelayFromBody(body);
        if (header == null || inBody == null) {
            return header == null ? inBody : header;
        }
        return header.compareTo(inBody) >= 0 ? header : inBody;
    }

    /**
     * The {@code retryDelay} of Google's {@code google.rpc.RetryInfo} error detail, for example
     * {@code {"error":{"code":429,"details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"34s"}]}}}.
     * Looked for anywhere in the body, because the OpenAI-compatible endpoint wraps the same error in
     * an array. Null when the body is not JSON, has no such detail or the value is not a duration.
     */
    static Duration retryDelayFromBody(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return findRetryDelay(JsonParser.parseString(body));
        } catch (JsonParseException exception) {
            return null;
        }
    }

    private static Duration findRetryDelay(JsonElement element) {
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                Duration found = findRetryDelay(item);
                if (found != null) {
                    return found;
                }
            }
        } else if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            JsonElement delay = object.get("retryDelay");
            if (delay != null && delay.isJsonPrimitive()) {
                Duration parsed = parseProtoDuration(delay.getAsString());
                if (parsed != null) {
                    return parsed;
                }
            }
            for (Map.Entry<String, JsonElement> member : object.entrySet()) {
                Duration found = findRetryDelay(member.getValue());
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Duration parseProtoDuration(String value) {
        Matcher matcher = PROTO_DURATION.matcher(value.trim());
        if (!matcher.matches()) {
            return null;
        }
        BigDecimal seconds = new BigDecimal(matcher.group(1) + (matcher.group(2) == null ? "" : "." + matcher.group(2)));
        // Only keeps Duration.toMillis() from overflowing, like the Retry-After clamp below.
        if (seconds.compareTo(BigDecimal.valueOf(ABSURD_WAIT.toSeconds())) > 0) {
            return ABSURD_WAIT;
        }
        return Duration.ofMillis(seconds.movePointRight(3).longValue());
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
