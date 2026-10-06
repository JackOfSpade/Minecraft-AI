package io.github.zoyluo.minecraftai.brain;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.observe.BotProfiler;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * Runs model requests off the server thread. A request that fails only transiently (overload, rate
 * limit, timeout, dropped connection) is replayed here, unchanged and outside the per-instruction
 * model-call budget, until it answers, fails for good or its lease is no longer in flight; see
 * {@link LlmRetryRunner}. The caller therefore sees exactly one callback per request.
 */
public final class AsyncDecisionExecutor {
    private final OpenAiCompatibleApiClient apiClient;
    private final GeminiInteractionsApiClient geminiInteractionsClient;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Predicate<DecisionLease> leaseInFlight;
    private final LlmRetryRunner retryRunner;

    public AsyncDecisionExecutor(OpenAiCompatibleApiClient apiClient) {
        this(apiClient, null);
    }

    public AsyncDecisionExecutor(OpenAiCompatibleApiClient apiClient,
                                 GeminiInteractionsApiClient geminiInteractionsClient) {
        this(apiClient, geminiInteractionsClient, lease -> true);
    }

    /**
     * @param leaseInFlight whether a lease is still the request its decision session waits on; a
     *                      request waiting out a backoff is dropped once this turns false
     */
    public AsyncDecisionExecutor(OpenAiCompatibleApiClient apiClient,
                                 GeminiInteractionsApiClient geminiInteractionsClient,
                                 Predicate<DecisionLease> leaseInFlight) {
        this.apiClient = apiClient;
        this.geminiInteractionsClient = geminiInteractionsClient;
        this.leaseInFlight = leaseInFlight;
        this.retryRunner = LlmRetryRunner.standard(executor);
    }

    public boolean usesGeminiInteractions() {
        return geminiInteractionsClient != null;
    }

    public void submit(AIPlayerEntity bot,
                       DecisionLease lease,
                       List<ChatMessage> historySnapshot,
                       List<ToolDefinition> tools,
                       GeminiInteractionRequest geminiRequest,
                       BiConsumer<DecisionLease, ChatResponse> onResponse,
                       BiConsumer<DecisionLease, Throwable> onError) {
        submit(bot, lease, historySnapshot, tools, geminiRequest, false, onResponse, onError);
    }

    /**
     * {@code requireToolCall} forces a function call on the chat-completions path (tool_choice
     * required); the Gemini Interactions path always requires one (allowed_tools mode any), so the
     * flag changes nothing there -- the offered tool set is what constrains it.
     */
    public void submit(AIPlayerEntity bot,
                       DecisionLease lease,
                       List<ChatMessage> historySnapshot,
                       List<ToolDefinition> tools,
                       GeminiInteractionRequest geminiRequest,
                       boolean requireToolCall,
                       BiConsumer<DecisionLease, ChatResponse> onResponse,
                       BiConsumer<DecisionLease, Throwable> onError) {
        submit(bot, lease, historySnapshot, tools, geminiRequest, requireToolCall, onResponse, onError, null);
    }

    /**
     * @param onWaiting called on the server thread, at most once per request, when the request has gone
     *                  unanswered long enough that a waiting player should hear the bot is still trying
     *                  (see {@link LlmRetryPolicy#NOTICE_AFTER_MS}); null for a request nobody waits on
     */
    public void submit(AIPlayerEntity bot,
                       DecisionLease lease,
                       List<ChatMessage> historySnapshot,
                       List<ToolDefinition> tools,
                       GeminiInteractionRequest geminiRequest,
                       boolean requireToolCall,
                       BiConsumer<DecisionLease, ChatResponse> onResponse,
                       BiConsumer<DecisionLease, Throwable> onError,
                       BiConsumer<DecisionLease, LlmApiException> onWaiting) {
        var server = bot.level().getServer();
        var botId = bot.getUUID();
        String botName = bot.getGameProfile().name();
        retryRunner.run(
                botName,
                () -> {
                    // Timed per attempt: the latency a retry spends waiting is not the service's.
                    long started = System.nanoTime();
                    try {
                        ChatResponse response = geminiRequest == null
                                ? (requireToolCall && tools != null && !tools.isEmpty()
                                        ? apiClient.chatRequiringToolCall(historySnapshot, tools)
                                        : apiClient.chat(historySnapshot, tools))
                                : executeGeminiInteraction(geminiRequest, tools);
                        BotProfiler.INSTANCE.record(botId, botName, "brain_latency", System.nanoTime() - started);
                        return response;
                    } catch (Exception exception) {
                        BotProfiler.INSTANCE.record(botId, botName, "brain_latency_error", System.nanoTime() - started);
                        throw exception;
                    }
                },
                () -> leaseInFlight.test(lease),
                onWaiting == null ? null : failure -> server.execute(() -> onWaiting.accept(lease, failure)),
                response -> server.execute(() -> onResponse.accept(lease, response)),
                failure -> server.execute(() -> onError.accept(lease, failure)));
    }

    private ChatResponse executeGeminiInteraction(GeminiInteractionRequest request,
                                                   List<ToolDefinition> tools)
            throws GeminiInteractionsApiClient.GeminiInteractionsApiException {
        if (geminiInteractionsClient == null) {
            throw new GeminiInteractionsApiClient.GeminiInteractionsApiException(
                    "gemini_interactions_not_configured");
        }
        GeminiInteractionsApiClient.InteractionResponse response = request.initial()
                ? geminiInteractionsClient.begin(request.initialPrompt(), tools)
                : geminiInteractionsClient.continueInteraction(
                        request.previousInteractionId(),
                        request.functionResults(),
                        request.stateUpdate(),
                        tools);
        return new ChatResponse(
                response.outputText(),
                response.executableCalls(),
                response.wantsToolCalls() ? "tool_calls" : finishReason(response.status()),
                response.inputTokens(),
                response.outputTokens(),
                0,
                response.interactionId(),
                response.cappedCallResults());
    }

    private static String finishReason(String status) {
        return "incomplete".equalsIgnoreCase(status) ? "length" : "stop";
    }

    /** Immutable, per-submission state for Gemini's native stateful interaction endpoint. */
    public record GeminiInteractionRequest(String initialPrompt,
                                           String previousInteractionId,
                                           List<GeminiInteractionsApiClient.FunctionResult> functionResults,
                                           String stateUpdate) {
        public GeminiInteractionRequest {
            functionResults = functionResults == null ? List.of() : List.copyOf(functionResults);
        }

        public static GeminiInteractionRequest initial(String prompt) {
            return new GeminiInteractionRequest(prompt, null, List.of(), null);
        }

        public static GeminiInteractionRequest continuation(String previousInteractionId,
                                                             List<GeminiInteractionsApiClient.FunctionResult> functionResults,
                                                             String stateUpdate) {
            return new GeminiInteractionRequest(null, previousInteractionId, functionResults, stateUpdate);
        }

        public boolean initial() {
            return previousInteractionId == null || previousInteractionId.isBlank();
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
