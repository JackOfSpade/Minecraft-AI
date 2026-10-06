package io.github.zoyluo.minecraftai.brain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class LlmHttpStatusTest {
    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");


    @Test
    void classifiesKnownStatusesAndTruncatesTheBodyExcerpt() {
        assertEquals("rate_limited: status=429 body=oops", LlmHttpStatus.classify(429, "oops", 200));
        assertEquals("api_timeout: status=408 body=oops", LlmHttpStatus.classify(408, "oops", 200));
        assertEquals("server_error: status=500 body=oops", LlmHttpStatus.classify(500, "oops", 200));
        assertEquals("server_error: status=503 body=oops", LlmHttpStatus.classify(503, "oops", 200));
        assertEquals("auth_error: status=401 body=oops", LlmHttpStatus.classify(401, "oops", 200));
        assertEquals("auth_error: status=403 body=oops", LlmHttpStatus.classify(403, "oops", 200));
        assertEquals("http_error: status=418 body=oops", LlmHttpStatus.classify(418, "oops", 200));
    }

    @Test
    void excerptLengthIsRespectedIndependentlyPerCaller() {
        String body = "0123456789";
        assertEquals("http_error: status=404 body=012", LlmHttpStatus.classify(404, body, 3));
        assertEquals("http_error: status=404 body=" + body, LlmHttpStatus.classify(404, body, 400));
    }

    @Test
    void aNullBodyProducesAnEmptyExcerpt() {
        assertEquals("http_error: status=404 body=", LlmHttpStatus.classify(404, null, 200));
    }

    @Test
    void overloadRateLimitAndServerErrorsAreTransient() {
        for (int status : new int[] {408, 429, 500, 502, 503, 504, 529}) {
            assertEquals(LlmApiException.Kind.TRANSIENT, LlmHttpStatus.kind(status), "status " + status);
        }
    }

    @Test
    void rejectedCredentialsAreAuthAndOtherClientErrorsPermanent() {
        assertEquals(LlmApiException.Kind.AUTH, LlmHttpStatus.kind(401));
        assertEquals(LlmApiException.Kind.AUTH, LlmHttpStatus.kind(403));
        for (int status : new int[] {400, 404, 409, 413, 422, 204, 302}) {
            assertEquals(LlmApiException.Kind.PERMANENT, LlmHttpStatus.kind(status), "status " + status);
        }
    }

    @Test
    void retryAfterInSecondsIsParsed() {
        assertEquals(Duration.ofSeconds(7), LlmHttpStatus.parseRetryAfter("7", NOW));
        assertEquals(Duration.ofSeconds(120), LlmHttpStatus.parseRetryAfter(" 120 ", NOW));
        assertEquals(Duration.ZERO, LlmHttpStatus.parseRetryAfter("0", NOW));
    }

    @Test
    void retryAfterAsAnHttpDateIsMeasuredFromNow() {
        assertEquals(Duration.ofSeconds(30),
                LlmHttpStatus.parseRetryAfter("Tue, 06 Oct 2026 12:00:30 GMT", NOW));
        assertEquals(Duration.ZERO, LlmHttpStatus.parseRetryAfter("Tue, 06 Oct 2026 11:00:00 GMT", NOW));
    }

    @Test
    void retryAfterThatIsAbsentOrUnreadableMeansNoPreference() {
        assertNull(LlmHttpStatus.parseRetryAfter(null, NOW));
        assertNull(LlmHttpStatus.parseRetryAfter("", NOW));
        assertNull(LlmHttpStatus.parseRetryAfter("soon", NOW));
        assertNull(LlmHttpStatus.parseRetryAfter("-5", NOW));
    }

    @Test
    void anAbsurdRetryAfterIsClampedSoItCannotOverflowMilliseconds() {
        Duration wait = LlmHttpStatus.parseRetryAfter("99999999999999999999999", NOW);

        assertEquals(Duration.ofDays(365), wait);
        assertEquals(365L * 24 * 60 * 60 * 1000, wait.toMillis());
    }
}
