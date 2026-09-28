package io.github.zoyluo.minecraftai.brain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class LlmHttpStatusTest {
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
}
