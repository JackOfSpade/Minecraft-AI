package io.github.zoyluo.minecraftai.brain;

/**
 * Shared HTTP-status-to-reason classification for the LLM clients. Both
 * {@link OpenAiCompatibleApiClient} and {@link GeminiInteractionsApiClient} turn a failed
 * response into the same family of reason strings; only the response-body excerpt length
 * they keep for diagnostics differs.
 */
final class LlmHttpStatus {
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
}
