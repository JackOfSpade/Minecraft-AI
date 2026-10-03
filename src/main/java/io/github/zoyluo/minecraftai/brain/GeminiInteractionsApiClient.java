package io.github.zoyluo.minecraftai.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogCategory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Minimal client for Gemini's native Interactions API.
 *
 * <p>Unlike the OpenAI compatibility endpoint, an interaction retains Gemini's internal
 * function-call context.  Callers must keep the returned {@linkplain InteractionResponse#interactionId()
 * interaction id} and pass it, together with one result for every returned function call, to
 * {@link #continueInteraction(String, List, List)}.  That avoids replaying an assistant/tool
 * transcript (and accidentally losing Gemini 3 thought signatures) on every tool turn.</p>
 *
 * <p>This class deliberately owns no mutable conversation map.  The brain owns a session per bot;
 * keeping the state there makes cancellation and latest-request-wins semantics straightforward.</p>
 */
public final class GeminiInteractionsApiClient {
    /**
     * Fallback only for direct callers that do not supply the brain setting. Runtime wiring uses
     * {@code brain.maxToolCallsPerTurn}; keep this aligned with that shipped default rather than
     * silently restoring the former three-call cap.
     */
    public static final int DEFAULT_MAX_FUNCTION_CALLS_PER_RESPONSE = 6;

    private final MinecraftAiConfig.Llm config;
    private final HttpClient httpClient;
    private final int maxFunctionCallsPerResponse;

    public GeminiInteractionsApiClient(MinecraftAiConfig.Llm config) {
        this(config, DEFAULT_MAX_FUNCTION_CALLS_PER_RESPONSE);
    }

    public GeminiInteractionsApiClient(MinecraftAiConfig.Llm config, int maxFunctionCallsPerResponse) {
        this.config = Objects.requireNonNull(config, "config");
        if (!isGoogleInteractionsEndpoint(config)) {
            throw new IllegalArgumentException("gemini_interactions_requires_google_endpoint");
        }
        if (maxFunctionCallsPerResponse <= 0) {
            throw new IllegalArgumentException("max_function_calls_must_be_positive");
        }
        this.maxFunctionCallsPerResponse = maxFunctionCallsPerResponse;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** True when a configured OpenAI-compatible Gemini URL can be converted to /interactions. */
    public static boolean isGoogleInteractionsEndpoint(MinecraftAiConfig.Llm config) {
        if (config == null || config.baseUrl() == null) {
            return false;
        }
        return config.baseUrl().contains("generativelanguage.googleapis.com/");
    }

    /**
     * Starts a stored interaction from one complete prompt.  The caller should combine its system
     * instruction, player request, and current perception into this prompt before calling here.
     */
    public InteractionResponse begin(String prompt, List<ToolDefinition> tools) throws GeminiInteractionsApiException {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("gemini_interaction_prompt_blank");
        }
        JsonObject body = baseBody();
        body.addProperty("input", prompt);
        addTools(body, tools);
        return execute("initial", null, body, tools == null ? 0 : tools.size(), 0);
    }

    /**
     * Continues an existing stored interaction with the results of the previous response's calls.
     * Every call returned by {@link InteractionResponse#allCalls()} needs exactly one result,
     * including calls deliberately rejected by {@link InteractionResponse#cappedCalls()}.
     */
    public InteractionResponse continueInteraction(String previousInteractionId,
                                                   List<FunctionResult> functionResults,
                                                   List<ToolDefinition> tools) throws GeminiInteractionsApiException {
        return continueInteraction(previousInteractionId, functionResults, null, tools);
    }

    /**
     * Continues a stored interaction and, optionally, appends fresh world state after the function
     * results.  The optional state is deliberately placed after every function_result so Gemini
     * receives a complete function-response batch before the next player/context turn.
     */
    public InteractionResponse continueInteraction(String previousInteractionId,
                                                   List<FunctionResult> functionResults,
                                                   String stateUpdate,
                                                   List<ToolDefinition> tools) throws GeminiInteractionsApiException {
        if (previousInteractionId == null || previousInteractionId.isBlank()) {
            throw new IllegalArgumentException("previous_interaction_id_blank");
        }
        if ((functionResults == null || functionResults.isEmpty())
                && (stateUpdate == null || stateUpdate.isBlank())) {
            throw new IllegalArgumentException("function_results_or_state_update_required");
        }
        List<FunctionResult> safeFunctionResults = functionResults == null ? List.of() : functionResults;
        JsonObject body = baseBody();
        body.addProperty("previous_interaction_id", previousInteractionId);
        JsonArray input = new JsonArray();
        for (FunctionResult result : safeFunctionResults) {
            input.add(serializeFunctionResult(Objects.requireNonNull(result, "functionResult")));
        }
        if (stateUpdate != null && !stateUpdate.isBlank()) {
            JsonObject context = new JsonObject();
            context.addProperty("type", "user_input");
            // A structured user_input step uses Content[] rather than the convenient
            // top-level string form accepted for a fresh interaction input.
            JsonArray content = new JsonArray();
            JsonObject text = new JsonObject();
            text.addProperty("type", "text");
            text.addProperty("text", stateUpdate);
            content.add(text);
            context.add("content", content);
            input.add(context);
        }
        body.add("input", input);
        addTools(body, tools);
        return execute("continuation", previousInteractionId, body,
                tools == null ? 0 : tools.size(), safeFunctionResults.size());
    }

    private InteractionResponse execute(String kind,
                                        String previousInteractionId,
                                        JsonObject body,
                                        int toolCount,
                                        int functionResultCount) throws GeminiInteractionsApiException {
        // A planner turn is exactly one HTTP request. Transport retries or model fallbacks here
        // would invisibly exceed the player-visible "initial + two repairs" allowance. Failures
        // instead return to BrainCoordinator, which records and spends one explicit repair turn.
        String model = config.model();
        body.addProperty("model", model);
        BotLog.api(null, "gemini_interaction_request",
                "kind", kind,
                "model", model,
                "tools_count", toolCount,
                "function_results", functionResultCount,
                "has_previous_interaction", previousInteractionId != null);
        HttpResponse<String> response = sendOnce(requestFor(body));
        if (response.statusCode() == 200) {
            return parseResponse(response.body(), maxFunctionCallsPerResponse);
        }
        throw new GeminiInteractionsApiException(classifyStatus(response.statusCode(), response.body()));
    }

    private JsonObject baseBody() {
        JsonObject body = new JsonObject();
        // previous_interaction_id works only for stored interactions.  Be explicit rather than
        // relying on a server-side default that can vary by endpoint revision.
        body.addProperty("store", true);
        return body;
    }

    private void addTools(JsonObject body, List<ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return;
        }
        JsonArray serialized = new JsonArray();
        for (ToolDefinition tool : tools) {
            if (tool == null) {
                continue;
            }
            JsonObject item = new JsonObject();
            item.addProperty("type", "function");
            item.addProperty("name", tool.name());
            item.addProperty("description", tool.description());
            item.add("parameters", tool.parametersSchema().deepCopy());
            serialized.add(item);
        }
        if (!serialized.isEmpty()) {
            body.add("tools", serialized);
            // The Interactions API represents forced tool use as an allowed_tools object, not
            // as the legacy string value.  Listing exactly the tools sent in this request lets a
            // plain-language response use the say tool while still requiring a structured call.
            JsonArray allowedNames = new JsonArray();
            for (JsonElement element : serialized) {
                allowedNames.add(element.getAsJsonObject().get("name").getAsString());
            }
            JsonObject allowedTools = new JsonObject();
            allowedTools.addProperty("mode", "any");
            allowedTools.add("tools", allowedNames);
            JsonObject toolChoice = new JsonObject();
            toolChoice.add("allowed_tools", allowedTools);
            JsonObject generationConfig = new JsonObject();
            generationConfig.add("tool_choice", toolChoice);
            body.add("generation_config", generationConfig);
        }
    }

    private JsonObject serializeFunctionResult(FunctionResult result) {
        JsonObject item = new JsonObject();
        item.addProperty("type", "function_result");
        item.addProperty("name", result.name());
        item.addProperty("call_id", result.callId());
        JsonArray content = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", result.content());
        content.add(text);
        item.add("result", content);
        return item;
    }

    private HttpRequest requestFor(JsonObject body) {
        return HttpRequest.newBuilder()
                .uri(URI.create(interactionsUrl()))
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .header("x-goog-api-key", config.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
    }

    private HttpResponse<String> sendOnce(HttpRequest request) throws GeminiInteractionsApiException {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException exception) {
            throw new GeminiInteractionsApiException("api_timeout: " + exception.getMessage(), exception);
        } catch (IOException exception) {
            throw new GeminiInteractionsApiException("io_error: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GeminiInteractionsApiException("interrupted", exception);
        }
    }

    private String interactionsUrl() {
        String base = config.baseUrl();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        int openAiSuffix = base.indexOf("/openai");
        if (openAiSuffix >= 0) {
            base = base.substring(0, openAiSuffix);
        }
        return base + "/interactions";
    }

    private static String classifyStatus(int status, String body) {
        return LlmHttpStatus.classify(status, body, 400);
    }

    static InteractionResponse parseResponse(String body, int maxFunctionCalls) throws GeminiInteractionsApiException {
        if (body == null || body.isBlank()) {
            throw new GeminiInteractionsApiException("empty_response");
        }
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonObject interaction = objectField(root, "interaction");
            if (interaction == null) {
                interaction = root;
            }
            String interactionId = stringField(interaction, "id");
            if (interactionId == null || interactionId.isBlank()) {
                interactionId = stringField(root, "id");
            }
            if (interactionId == null || interactionId.isBlank()) {
                throw new GeminiInteractionsApiException("missing_interaction_id");
            }
            String status = firstNonBlank(stringField(interaction, "status"), stringField(root, "status"), "unknown");
            String output = firstNonBlank(
                    stringField(interaction, "output_text"),
                    stringField(interaction, "outputText"),
                    collectTextFromSteps(arrayField(interaction, "steps")),
                    "");
            JsonArray steps = arrayField(interaction, "steps");
            if (steps == null) {
                steps = arrayField(root, "steps");
            }
            List<ChatToolCall> allCalls = parseFunctionCalls(steps);
            int allowed = Math.min(Math.max(0, maxFunctionCalls), allCalls.size());
            List<ChatToolCall> executable = List.copyOf(allCalls.subList(0, allowed));
            List<ChatToolCall> capped = List.copyOf(allCalls.subList(allowed, allCalls.size()));
            JsonObject usage = objectField(interaction, "usage");
            if (usage == null) {
                usage = objectField(root, "usage");
            }
            int inputTokens = tokenField(usage, "total_input_tokens", "input_tokens", "prompt_tokens");
            int outputTokens = tokenField(usage, "total_output_tokens", "output_tokens", "completion_tokens");
            BotLog.api(null, "gemini_interaction_response",
                    "interaction_id", abbreviateId(interactionId),
                    "status", status,
                    "function_calls", allCalls.size(),
                    "executable_calls", executable.size(),
                    "capped_calls", capped.size(),
                    "tokens_in", inputTokens,
                    "tokens_out", outputTokens);
            return new InteractionResponse(interactionId, status, output, executable, capped, inputTokens, outputTokens);
        } catch (GeminiInteractionsApiException exception) {
            BotLog.warn(LogCategory.API, null, "gemini_interaction_parse_error",
                    "reason", exception.getMessage(),
                    "body_excerpt", body.substring(0, Math.min(400, body.length())));
            throw exception;
        } catch (RuntimeException exception) {
            BotLog.error("gemini_interaction_parse_error", exception,
                    "body_excerpt", body.substring(0, Math.min(400, body.length())));
            throw new GeminiInteractionsApiException("bad_response: " + exception.getMessage(), exception);
        }
    }

    private static List<ChatToolCall> parseFunctionCalls(JsonArray steps) throws GeminiInteractionsApiException {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        List<ChatToolCall> calls = new ArrayList<>();
        for (JsonElement element : steps) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject step = element.getAsJsonObject();
            JsonObject call = objectField(step, "function_call");
            String type = stringField(step, "type");
            if (call == null && !"function_call".equals(type)) {
                continue;
            }
            if (call == null) {
                call = step;
            }
            String id = firstNonBlank(stringField(call, "id"), stringField(call, "call_id"));
            String name = stringField(call, "name");
            if (id == null || id.isBlank() || name == null || name.isBlank()) {
                throw new GeminiInteractionsApiException("malformed_function_call");
            }
            calls.add(new ChatToolCall(id, name, jsonText(call.get("arguments"))));
        }
        return List.copyOf(calls);
    }

    private static String collectTextFromSteps(JsonArray steps) {
        if (steps == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (JsonElement element : steps) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject step = element.getAsJsonObject();
            if (!"model_output".equals(stringField(step, "type"))) {
                continue;
            }
            appendContentText(text, step.get("content"));
        }
        return text.toString();
    }

    private static void appendContentText(StringBuilder target, JsonElement content) {
        if (content == null || content.isJsonNull()) {
            return;
        }
        if (content.isJsonPrimitive()) {
            target.append(content.getAsString());
            return;
        }
        if (!content.isJsonArray()) {
            return;
        }
        for (JsonElement part : content.getAsJsonArray()) {
            if (part.isJsonObject()) {
                String value = stringField(part.getAsJsonObject(), "text");
                if (value != null) {
                    target.append(value);
                }
            }
        }
    }

    private static JsonObject objectField(JsonObject object, String name) {
        if (object == null || !object.has(name) || !object.get(name).isJsonObject()) {
            return null;
        }
        return object.getAsJsonObject(name);
    }

    private static JsonArray arrayField(JsonObject object, String name) {
        if (object == null || !object.has(name) || !object.get(name).isJsonArray()) {
            return null;
        }
        return object.getAsJsonArray(name);
    }

    private static String stringField(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) {
            return null;
        }
        JsonElement value = object.get(name);
        return value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static String jsonText(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "{}";
        }
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    private static int tokenField(JsonObject usage, String... names) {
        if (usage == null) {
            return 0;
        }
        for (String name : names) {
            if (usage.has(name) && usage.get(name).isJsonPrimitive()) {
                try {
                    return usage.get(name).getAsInt();
                } catch (RuntimeException ignored) {
                    // Try the next compatible token-count field.
                }
            }
        }
        return 0;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String abbreviateId(String value) {
        return value.length() <= 32 ? value : value.substring(0, 32) + "...";
    }

    /** A result to return for exactly one function_call in the previous interaction response. */
    public record FunctionResult(String callId, String name, String content) {
        public FunctionResult {
            if (callId == null || callId.isBlank()) {
                throw new IllegalArgumentException("function_result_call_id_blank");
            }
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("function_result_name_blank");
            }
            content = content == null ? "" : content;
        }

        public static FunctionResult from(ChatToolCall call, ToolDefinition.ToolResult result) {
            Objects.requireNonNull(call, "call");
            Objects.requireNonNull(result, "result");
            return new FunctionResult(call.id(), call.name(), result.toToolContent());
        }
    }

    /**
     * The immutable result of one native Gemini interaction.  Calls beyond the configured cap are
     * exposed separately so the caller can return a rejected function result for each one, which
     * keeps the native interaction valid while preventing extra game actions.
     */
    public record InteractionResponse(String interactionId,
                                      String status,
                                      String outputText,
                                      List<ChatToolCall> executableCalls,
                                      List<ChatToolCall> cappedCalls,
                                      int inputTokens,
                                      int outputTokens) {
        public InteractionResponse {
            Objects.requireNonNull(interactionId, "interactionId");
            status = status == null ? "unknown" : status;
            outputText = outputText == null ? "" : outputText;
            executableCalls = executableCalls == null ? List.of() : List.copyOf(executableCalls);
            cappedCalls = cappedCalls == null ? List.of() : List.copyOf(cappedCalls);
        }

        public List<ChatToolCall> allCalls() {
            if (cappedCalls.isEmpty()) {
                return executableCalls;
            }
            List<ChatToolCall> all = new ArrayList<>(executableCalls.size() + cappedCalls.size());
            all.addAll(executableCalls);
            all.addAll(cappedCalls);
            return List.copyOf(all);
        }

        /** Synthetic results for calls not executed because of the per-response safety cap. */
        public List<FunctionResult> cappedCallResults() {
            return cappedCalls.stream()
                    .map(call -> new FunctionResult(call.id(), call.name(),
                            "{\"ok\":false,\"message\":\"throttled: per-response function-call cap reached\"}"))
                    .toList();
        }

        public boolean wantsToolCalls() {
            return !executableCalls.isEmpty() || !cappedCalls.isEmpty();
        }
    }

    public static final class GeminiInteractionsApiException extends Exception {
        public GeminiInteractionsApiException(String message) {
            super(message);
        }

        public GeminiInteractionsApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
