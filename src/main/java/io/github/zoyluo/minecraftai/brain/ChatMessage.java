package io.github.zoyluo.minecraftai.brain;

import java.util.List;

public record ChatMessage(
        String role,
        String content,
        List<ChatToolCall> toolCalls,
        String toolCallId,
        String name
) {
    public static ChatMessage system(String content) {
        return new ChatMessage("system", content, List.of(), null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, List.of(), null, null);
    }

    public static ChatMessage assistant(String content, List<ChatToolCall> calls) {
        return new ChatMessage("assistant", content, calls == null ? List.of() : List.copyOf(calls), null, null);
    }

    public static ChatMessage toolResult(String toolCallId, String toolName, String content) {
        return new ChatMessage("tool", content, List.of(), toolCallId, toolName);
    }

    /**
     * Kept for callers that do not have the tool name. New tool-result producers should pass
     * it: Gemini's OpenAI-compatible endpoint requires the name on each returned function
     * result, and retaining it is useful for diagnostics on every provider.
     */
    public static ChatMessage toolResult(String toolCallId, String content) {
        return toolResult(toolCallId, null, content);
    }
}
