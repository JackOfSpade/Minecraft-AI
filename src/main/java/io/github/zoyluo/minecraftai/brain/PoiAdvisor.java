package io.github.zoyluo.minecraftai.brain;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogCategory;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistConfig;
import io.github.zoyluo.minecraftai.mining.assist.MiningAssistRuntime;
import io.github.zoyluo.minecraftai.mining.assist.PoiPrompt;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The R4 LLM client (mining-assist design 6.6), cloned from {@link ChatRecipientRouter}'s
 * {@link OpenAiCompatibleApiClient} usage pattern but with its own derived config, its own small executor, a
 * non-blocking wall-clock guard, and a test transport seam for GameTests. {@code PoiCoordinator} is the only
 * caller; nothing here touches {@code TaskManager}, {@code PoiRegistry} or chat -- it only resolves one
 * consult into a validated {@link PoiPrompt.Verdict} or a failure reason, on the server thread.
 *
 * <h2>Derived config</h2>
 * Positionally {@code new MinecraftAiConfig.Llm(base.apiKey(), base.baseUrl(), advisorModelOrBase,
 * advisor.maxTokens(), 0.0, advisor.timeoutSeconds(), 0, base.retryBackoffMs(), Boolean.FALSE,
 * base.reasoningEffort())}: zero retries (this class enforces its own bound, not the client's
 * retry-then-fallback loop), {@code thinking} forced off (a thinking-capable model must not spend the small
 * {@code maxTokens} budget on reasoning instead of the tool call).
 *
 * <h2>Wall-clock guard</h2>
 * The client's own per-request timeout is {@code advisor.timeoutSeconds()} (8s), and
 * {@code OpenAiCompatibleApiClient.modelsForRequest()} can retry across up to 3 fallback models on 429/404,
 * so one call can legitimately run past 10s. Design 6.6 calls for "a 10s deadline per consult with
 * {@code Future.get(timeout)} and {@code cancel(true)}" -- implemented here as a {@link ScheduledExecutorService}
 * watchdog that cancels the worker {@link Future} and settles the callback after {@link #consultDeadlineMs()}
 * milliseconds, rather than a literal blocking {@code get(timeout)} on a pool thread, which would either
 * block the server thread (if called from it) or need a second worker thread per consult (halving the
 * 2-thread pool's real concurrency). Both settle exactly once, guarded by a per-consult
 * {@link AtomicBoolean}; a late worker completion after the watchdog already fired a timeout is dropped
 * silently, matching "late replies never pause" at the {@code PoiCoordinator} layer.
 */
public final class PoiAdvisor {
    public static final PoiAdvisor INSTANCE = new PoiAdvisor();

    /** Design 6.6: "enforces a 10s deadline per consult." Overridable for tests via {@link #setTestConsultDeadlineMs}. */
    public static final long DEFAULT_CONSULT_DEADLINE_MS = 10_000L;

    /** A pluggable transport for GameTests: resolves the payload into a verdict (or throws) without any
     * network access. Production leaves this {@code null} and uses the real {@link OpenAiCompatibleApiClient}. */
    @FunctionalInterface
    public interface Transport {
        PoiPrompt.Verdict resolve(String userPayloadJson) throws Exception;
    }

    private final Object lifecycleLock = new Object();
    private OpenAiCompatibleApiClient apiClient;
    private ThreadPoolExecutor executor;
    private ScheduledExecutorService watchdog;
    private long generation;

    private static volatile Transport testTransport;
    private static volatile Long testDeadlineMs;

    private PoiAdvisor() {
    }

    public void configure(MinecraftAiConfig config) {
        Objects.requireNonNull(config, "config");
        synchronized (lifecycleLock) {
            generation++;
            if (executor != null) {
                executor.shutdownNow();
            }
            if (watchdog != null) {
                watchdog.shutdownNow();
            }
            MiningAssistConfig.Advisor advisorCfg = MiningAssistRuntime.config().advisor();
            MinecraftAiConfig.Llm base = config.llm();
            String model = advisorCfg.model() == null || advisorCfg.model().isBlank() ? base.model() : advisorCfg.model();
            MinecraftAiConfig.Llm advisorLlm = new MinecraftAiConfig.Llm(
                    base.apiKey(), base.baseUrl(), model,
                    advisorCfg.maxTokens(), 0.0D, advisorCfg.timeoutSeconds(),
                    0, base.retryBackoffMs(), Boolean.FALSE, base.reasoningEffort());
            apiClient = new OpenAiCompatibleApiClient(advisorLlm);
            ThreadFactory workers = runnable -> {
                Thread t = new Thread(runnable, "poi-advisor-worker");
                t.setDaemon(true);
                return t;
            };
            executor = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(4), workers, new ThreadPoolExecutor.AbortPolicy());
            watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread t = new Thread(runnable, "poi-advisor-watchdog");
                t.setDaemon(true);
                return t;
            });
        }
    }

    public void shutdown() {
        synchronized (lifecycleLock) {
            generation++;
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
            if (watchdog != null) {
                watchdog.shutdownNow();
                watchdog = null;
            }
            apiClient = null;
        }
    }

    /**
     * Resolves one consult. Exactly one of {@code onVerdict} or {@code onFailure} runs, always via
     * {@code server.execute} and only if this instance was not reconfigured/shut down since the call (the
     * same generation guard as {@link ChatRecipientRouter}). {@code onFailure} receives a short reason
     * ({@code "poi_advisor_unavailable"}, {@code "poi_advisor_queue_full"}, {@code "poi_advisor_timeout"}, or
     * the validator/transport's own failure text) -- the caller ({@code PoiCoordinator}) is responsible for
     * counting it against {@code PoiConsultBudget}'s breaker and applying the design 6.7 fallback.
     */
    public void consult(AIPlayerEntity bot, String systemPromptText, String userPayloadJson,
                        Consumer<PoiPrompt.Verdict> onVerdict, Consumer<String> onFailure) {
        Objects.requireNonNull(bot, "bot");
        Objects.requireNonNull(onVerdict, "onVerdict");
        Objects.requireNonNull(onFailure, "onFailure");

        Transport transport = testTransport;
        OpenAiCompatibleApiClient client;
        ThreadPoolExecutor worker;
        ScheduledExecutorService clock;
        long requestGeneration;
        synchronized (lifecycleLock) {
            client = apiClient;
            worker = executor;
            clock = watchdog;
            requestGeneration = generation;
        }
        if (transport == null && (client == null || worker == null || worker.isShutdown())) {
            onFailure.accept("poi_advisor_unavailable");
            return;
        }
        if (transport != null && (worker == null || worker.isShutdown())) {
            // A test transport still needs a live pool to hop off the calling thread; configure() must
            // have run in the test fixture (mirrors production: the pool is never optional infrastructure).
            onFailure.accept("poi_advisor_unavailable");
            return;
        }

        MinecraftServer server = bot.level().getServer();
        AtomicBoolean settled = new AtomicBoolean(false);
        Future<?> future;
        try {
            future = worker.submit(() -> {
                try {
                    PoiPrompt.Verdict verdict = transport != null
                            ? transport.resolve(userPayloadJson)
                            : callReal(client, systemPromptText, userPayloadJson);
                    if (settled.compareAndSet(false, true)) {
                        invokeOnServer(server, requestGeneration, worker, () -> onVerdict.accept(verdict));
                    }
                } catch (Exception exception) {
                    String reason = exception.getMessage() == null ? "poi_advisor_error" : exception.getMessage();
                    if (settled.compareAndSet(false, true)) {
                        invokeOnServer(server, requestGeneration, worker, () -> onFailure.accept(reason));
                    }
                }
            });
        } catch (RejectedExecutionException exception) {
            onFailure.accept("poi_advisor_queue_full");
            return;
        }
        long deadline = consultDeadlineMs();
        try {
            clock.schedule(() -> {
                if (settled.compareAndSet(false, true)) {
                    future.cancel(true);
                    invokeOnServer(server, requestGeneration, worker, () -> onFailure.accept("poi_advisor_timeout"));
                }
            }, deadline, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException exception) {
            // Watchdog pool gone (shutdown raced this call): the generation guard on the worker's own
            // completion callback already makes a late verdict a no-op, so there is nothing further to do.
            BotLog.warn(LogCategory.API, bot, "poi_advisor_watchdog_unavailable");
        }
    }

    private static PoiPrompt.Verdict callReal(OpenAiCompatibleApiClient client, String systemPromptText,
                                              String userPayloadJson) throws LlmApiException {
        List<ChatMessage> history = List.of(
                ChatMessage.system(systemPromptText),
                ChatMessage.user(userPayloadJson));
        ChatResponse response = client.chatRequiringToolCall(history, List.of(PoiPrompt.tool()));
        return PoiPrompt.validate(response)
                .orElseThrow(() -> new LlmApiException("poi_advisor_invalid_response"));
    }

    private void invokeOnServer(MinecraftServer server, long requestGeneration, ThreadPoolExecutor requestExecutor,
                                Runnable callback) {
        server.execute(() -> {
            synchronized (lifecycleLock) {
                if (generation != requestGeneration || executor != requestExecutor) {
                    return;
                }
            }
            callback.run();
        });
    }

    private static long consultDeadlineMs() {
        Long override = testDeadlineMs;
        return override != null ? override : DEFAULT_CONSULT_DEADLINE_MS;
    }

    // ---------------------------------------------------------------------------------------------
    // Test-only hooks (GameTests): mirrors MiningAssistRuntime.setTestTpsDegraded's idiom.
    // ---------------------------------------------------------------------------------------------

    /** Installs a stub transport in place of the real HTTP client; {@code null} restores production
     * behaviour. The surrounding async/executor/watchdog machinery still runs for real. */
    public static void setTestTransport(Transport transport) {
        testTransport = transport;
    }

    /** True while a stub transport is installed. {@code PoiCoordinator.advisorAvailable} treats this as
     * standing in for "the LLM key is present": a stub transport never needs a real key, and the harness's
     * real {@code MinecraftAiConfig} may or may not have one configured, which would otherwise make every GameTest
     * exercising the consult path (design 9: "GameTests for hold, continue, stop, timeout, ...") depend on
     * incidental, environment-specific config it has no business depending on. */
    public static boolean hasTestTransport() {
        return testTransport != null;
    }

    /** Overrides the 10s wall-clock guard for tests; {@code null} restores {@link #DEFAULT_CONSULT_DEADLINE_MS}. */
    public static void setTestConsultDeadlineMs(Long millis) {
        testDeadlineMs = millis;
    }
}
