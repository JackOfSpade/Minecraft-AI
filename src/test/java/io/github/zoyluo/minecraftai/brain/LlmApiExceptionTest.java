package io.github.zoyluo.minecraftai.brain;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LlmApiExceptionTest {
    @Test
    void anUnclassifiedFailureIsPermanentSoUnknownErrorsNeverStartARetryStorm() {
        LlmApiException failure = new LlmApiException("empty_response");

        assertEquals(LlmApiException.Kind.PERMANENT, failure.kind());
        assertFalse(failure.isTransient());
        assertEquals(0, failure.httpStatus());
    }

    @Test
    void onlyTheTransientKindIsWorthRetrying() {
        assertTrue(new LlmApiException("x", LlmApiException.Kind.TRANSIENT, 503, null, null).isTransient());
        assertFalse(new LlmApiException("x", LlmApiException.Kind.AUTH, 401, null, null).isTransient());
        assertFalse(new LlmApiException("x", LlmApiException.Kind.PERMANENT, 400, null, null).isTransient());
    }

    @Test
    void theGeminiClientsExceptionCarriesTheSameClassification() {
        GeminiInteractionsApiClient.GeminiInteractionsApiException failure =
                new GeminiInteractionsApiClient.GeminiInteractionsApiException("server_error: status=503",
                        LlmApiException.Kind.TRANSIENT, 503, Duration.ofSeconds(2), null);

        LlmApiException classified = LlmApiException.classify(failure);

        assertSame(failure, classified);
        assertTrue(classified.isTransient());
        assertEquals(Duration.ofSeconds(2), classified.retryAfter());
    }

    @Test
    void classifyingAnArbitraryExceptionWrapsItAsPermanentAndKeepsTheCause() {
        IllegalArgumentException bug = new IllegalArgumentException("bad uri");

        LlmApiException classified = LlmApiException.classify(bug);

        assertEquals(LlmApiException.Kind.PERMANENT, classified.kind());
        assertSame(bug, classified.getCause());
        assertEquals("IllegalArgumentException: bad uri", classified.getMessage());
    }

    @Test
    void classifyingAnExceptionWithoutAMessageStillNamesIt() {
        assertEquals("NullPointerException", LlmApiException.classify(new NullPointerException()).getMessage());
    }
}
