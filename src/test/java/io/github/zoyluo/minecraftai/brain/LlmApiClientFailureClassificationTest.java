package io.github.zoyluo.minecraftai.brain;

import com.sun.net.httpserver.HttpServer;
import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real HTTP against a loopback server: what each client tags a failed exchange with, and that the
 * brain's single-attempt client sends exactly one request however many retries the config allows.
 */
final class LlmApiClientFailureClassificationTest {
    private static final String INTERACTION = "{\"id\":\"interaction-1\",\"status\":\"completed\","
            + "\"steps\":[{\"type\":\"model_output\",\"content\":[{\"type\":\"text\",\"text\":\"done\"}]}]}";

    private record Canned(int status, String retryAfter, String body) {
    }

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final List<Canned> script = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int index = Math.min(requests.getAndIncrement(), script.size() - 1);
            Canned canned = script.get(index);
            if (canned.retryAfter() != null) {
                exchange.getResponseHeaders().add("Retry-After", canned.retryAfter());
            }
            byte[] body = canned.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(canned.status(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void geminiOverloadIsTransientAndCarriesTheStatusAndRetryAfter() {
        script.add(new Canned(503, "7", "{\"error\":{\"code\":\"service_unavailable\"}}"));

        LlmApiException failure = assertThrows(LlmApiException.class, () -> gemini().begin("prompt", List.of()));

        assertEquals(LlmApiException.Kind.TRANSIENT, failure.kind());
        assertEquals(503, failure.httpStatus());
        assertEquals(Duration.ofSeconds(7), failure.retryAfter());
        assertTrue(failure.getMessage().contains("status=503"), failure.getMessage());
        assertEquals(1, requests.get(), "one call is exactly one HTTP request");
    }

    @Test
    void geminiRateLimitIsTransientWithoutARetryAfterWhenTheServiceGivesNone() {
        script.add(new Canned(429, null, "{}"));

        LlmApiException failure = assertThrows(LlmApiException.class, () -> gemini().begin("prompt", List.of()));

        assertEquals(LlmApiException.Kind.TRANSIENT, failure.kind());
        assertNull(failure.retryAfter());
    }

    @Test
    void geminiBadRequestIsPermanentAndBadCredentialsAreAuth() {
        script.add(new Canned(400, null, "{\"error\":\"invalid argument\"}"));
        assertEquals(LlmApiException.Kind.PERMANENT,
                assertThrows(LlmApiException.class, () -> gemini().begin("prompt", List.of())).kind());

        requests.set(0);
        script.clear();
        script.add(new Canned(403, null, "{\"error\":\"API key not valid\"}"));
        assertEquals(LlmApiException.Kind.AUTH,
                assertThrows(LlmApiException.class, () -> gemini().begin("prompt", List.of())).kind());
    }

    @Test
    void geminiConnectionFailureIsTransient() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        GeminiInteractionsApiClient client = new GeminiInteractionsApiClient(
                config("http://127.0.0.1:" + closedPort + "/generativelanguage.googleapis.com/v1beta", 0));

        LlmApiException failure = assertThrows(LlmApiException.class, () -> client.begin("prompt", List.of()));

        assertEquals(LlmApiException.Kind.TRANSIENT, failure.kind());
        assertEquals(0, failure.httpStatus());
    }

    @Test
    void anUnusableGeminiReplyIsPermanent() {
        script.add(new Canned(200, null, "{\"id\":\"\"}"));

        LlmApiException failure = assertThrows(LlmApiException.class, () -> gemini().begin("prompt", List.of()));

        assertEquals(LlmApiException.Kind.PERMANENT, failure.kind());
    }

    @Test
    void aBurstOfGemini503sIsRetriedByTheRunnerUntilTheServiceAnswers() {
        // The real client over real HTTP, retried by the real runner on a fake clock: the outage of
        // 2026-10-05 22:14, with the service honouring a Retry-After on its second refusal.
        script.add(new Canned(503, null, "{}"));
        script.add(new Canned(503, "5", "{}"));
        script.add(new Canned(503, null, "{}"));
        script.add(new Canned(200, null, INTERACTION));
        List<Long> delays = new ArrayList<>();
        List<GeminiInteractionsApiClient.InteractionResponse> answers = new ArrayList<>();
        List<LlmApiException> failures = new ArrayList<>();
        GeminiInteractionsApiClient client = gemini();
        LlmRetryRunner runner = new LlmRetryRunner(Runnable::run,
                (delay, task) -> {
                    delays.add(delay);
                    task.run();
                },
                System::currentTimeMillis,
                new LlmRetryPolicy(() -> 0.0D));

        runner.run("Moss", () -> client.begin("prompt", List.of()), () -> true, answers::add, failures::add);

        assertEquals(List.of(1_000L, 5_000L, 4_000L), delays);
        assertEquals(4, requests.get());
        assertTrue(failures.isEmpty());
        assertEquals(1, answers.size());
        assertEquals("interaction-1", answers.getFirst().interactionId());
        assertEquals("done", answers.getFirst().outputText());
    }

    @Test
    void theSingleAttemptClientSendsOneRequestWhateverTheConfiguredRetryCount() {
        script.add(new Canned(503, "3", "{}"));
        MinecraftAiConfig.Llm withRetries = config("http://127.0.0.1:" + server.getAddress().getPort(), 3);

        LlmApiException failure = assertThrows(LlmApiException.class,
                () -> OpenAiCompatibleApiClient.singleAttempt(withRetries).chat(List.of(ChatMessage.user("hi")), List.of()));

        assertEquals(1, requests.get(), "the brain owns retrying; the client must not stack its own");
        assertEquals(LlmApiException.Kind.TRANSIENT, failure.kind());
        assertEquals(503, failure.httpStatus());
        assertEquals(Duration.ofSeconds(3), failure.retryAfter());
    }

    @Test
    void aPlainClientStillRetriesInsideItselfForTheBackgroundCallersThatRelyOnIt() {
        script.add(new Canned(503, null, "{}"));
        MinecraftAiConfig.Llm withRetries = config("http://127.0.0.1:" + server.getAddress().getPort(), 3);

        assertThrows(LlmApiException.class,
                () -> new OpenAiCompatibleApiClient(withRetries).chat(List.of(ChatMessage.user("hi")), List.of()));

        assertEquals(4, requests.get());
    }

    @Test
    void aMissingApiKeyIsAnAuthFailureThatNeverReachesTheNetwork() {
        MinecraftAiConfig.Llm noKey = new MinecraftAiConfig.Llm("", "http://127.0.0.1:" + server.getAddress().getPort(),
                "model", 100, 0.3D, 5, 0, 1, Boolean.FALSE, "low");

        LlmApiException failure = assertThrows(LlmApiException.class,
                () -> new OpenAiCompatibleApiClient(noKey).chat(List.of(ChatMessage.user("hi")), List.of()));

        assertEquals(LlmApiException.Kind.AUTH, failure.kind());
        assertEquals(0, requests.get());
    }

    @Test
    void anEmptyChatReplyIsPermanent() {
        script.add(new Canned(200, null, "{\"choices\":[]}"));

        LlmApiException failure = assertThrows(LlmApiException.class,
                () -> OpenAiCompatibleApiClient.singleAttempt(config("http://127.0.0.1:" + server.getAddress().getPort(), 0))
                        .chat(List.of(ChatMessage.user("hi")), List.of()));

        assertEquals(LlmApiException.Kind.PERMANENT, failure.kind());
    }

    private GeminiInteractionsApiClient gemini() {
        return new GeminiInteractionsApiClient(config(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/generativelanguage.googleapis.com/v1beta", 0));
    }

    private static MinecraftAiConfig.Llm config(String baseUrl, int retryCount) {
        // retryBackoffMs 1: the plain client's own retries must not slow the suite down.
        return new MinecraftAiConfig.Llm("key", baseUrl, "model", 100, 0.3D, 5, retryCount, 1, Boolean.FALSE, "low");
    }
}
