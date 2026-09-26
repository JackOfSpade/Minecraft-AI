package io.github.zoyluo.aibot.brain;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.observe.BotProfiler;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

public final class AsyncDecisionExecutor {
    private final DeepSeekApiClient apiClient;
    private final GeminiInteractionsApiClient geminiInteractionsClient;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    public AsyncDecisionExecutor(DeepSeekApiClient apiClient) {
        this(apiClient, null);
    }

    public AsyncDecisionExecutor(DeepSeekApiClient apiClient,
                                 GeminiInteractionsApiClient geminiInteractionsClient) {
        this.apiClient = apiClient;
        this.geminiInteractionsClient = geminiInteractionsClient;
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
        var server = bot.getServer();
        var botId = bot.getUuid();
        String botName = bot.getGameProfile().getName();
        executor.submit(() -> {
            long started = System.nanoTime();
            try {
                ChatResponse response = geminiRequest == null
                        ? apiClient.chat(historySnapshot, tools)
                        : executeGeminiInteraction(geminiRequest, tools);
                long elapsed = System.nanoTime() - started;
                server.execute(() -> onResponse.accept(lease, response));
                BotProfiler.INSTANCE.record(botId, botName, "brain_latency", elapsed);
            } catch (Exception exception) {
                long elapsed = System.nanoTime() - started;
                BotProfiler.INSTANCE.record(botId, botName, "brain_latency_error", elapsed);
                server.execute(() -> onError.accept(lease, exception));
            }
        });
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
