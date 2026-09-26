package io.github.zoyluo.aibot.brain;

import java.util.List;

public record ChatResponse(
        String content,
        List<ChatToolCall> toolCalls,
        String finishReason,
        int promptTokens,
        int completionTokens,
        int promptCacheHitTokens,
        String geminiInteractionId,
        List<GeminiInteractionsApiClient.FunctionResult> geminiCappedFunctionResults
) {
    public ChatResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        geminiCappedFunctionResults = geminiCappedFunctionResults == null
                ? List.of()
                : List.copyOf(geminiCappedFunctionResults);
    }

    public ChatResponse(String content,
                        List<ChatToolCall> toolCalls,
                        String finishReason,
                        int promptTokens,
                        int completionTokens,
                        int promptCacheHitTokens) {
        this(content, toolCalls, finishReason, promptTokens, completionTokens, promptCacheHitTokens,
                null, List.of());
    }

    public boolean wantsToolCalls() {
        return "tool_calls".equals(finishReason) && toolCalls != null && !toolCalls.isEmpty();
    }

    public boolean isDone() {
        return "stop".equals(finishReason);
    }
}
