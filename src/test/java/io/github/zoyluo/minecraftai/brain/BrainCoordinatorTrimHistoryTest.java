package io.github.zoyluo.minecraftai.brain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BrainCoordinatorTrimHistoryTest {
    private static final ChatToolCall TOOL_CALL = new ChatToolCall("call-1", "some_tool", "{}");

    @Test
    void keepsTheLastMessagesWhenTheCutDoesNotSplitAnAssistantToolRound() {
        List<ChatMessage> rest = List.of(
                ChatMessage.user("u1"),
                ChatMessage.assistant(null, List.of(TOOL_CALL)),
                ChatMessage.toolResult("call-1", "t1"),
                ChatMessage.user("u2"));

        // keep=3 cuts right before the assistant message, so its tool result stays paired.
        List<ChatMessage> kept = BrainCoordinator.trimmedTail(rest, 3);

        assertEquals(List.of(rest.get(1), rest.get(2), rest.get(3)), kept);
    }

    @Test
    void skipsAnOrphanedLeadingToolResultLeftByTheCountBasedCut() {
        // Mirrors the auditor's concrete trace: an assistant message is cut away by the raw
        // count-based window, leaving its tool-result messages as the new first entries.
        ChatMessage assistant2 = ChatMessage.assistant("a2", List.of(TOOL_CALL));
        ChatMessage tool2a = ChatMessage.toolResult("call-1", "t2a");
        ChatMessage tool2b = ChatMessage.toolResult("call-1", "t2b");
        ChatMessage user3 = ChatMessage.user("u3");
        ChatMessage assistant3 = ChatMessage.assistant("a3", List.of(TOOL_CALL, TOOL_CALL, TOOL_CALL));
        ChatMessage tool3a = ChatMessage.toolResult("call-1", "t3a");
        ChatMessage tool3b = ChatMessage.toolResult("call-1", "t3b");
        ChatMessage tool3c = ChatMessage.toolResult("call-1", "t3c");
        List<ChatMessage> rest = List.of(assistant2, tool2a, tool2b, user3, assistant3, tool3a, tool3b, tool3c);

        // keep=3 would otherwise land on [t3a, t3b, t3c] (start=5), orphaning them from assistant3.
        List<ChatMessage> kept = BrainCoordinator.trimmedTail(rest, 3);

        assertTrue(kept.isEmpty(), "every candidate in the raw window is an orphaned tool result");
        for (ChatMessage message : kept) {
            assertTrue(!"tool".equals(message.role()));
        }
    }

    @Test
    void aTrailingToolResultThatKeepsItsAssistantOwnerIsNotSkipped() {
        ChatMessage assistant = ChatMessage.assistant("a", List.of(TOOL_CALL));
        ChatMessage tool = ChatMessage.toolResult("call-1", "t");
        List<ChatMessage> rest = List.of(ChatMessage.user("u"), assistant, tool);

        List<ChatMessage> kept = BrainCoordinator.trimmedTail(rest, 2);

        assertEquals(List.of(assistant, tool), kept);
    }

    @Test
    void keepingNothingReturnsAnEmptyList() {
        List<ChatMessage> rest = List.of(ChatMessage.user("u"), ChatMessage.toolResult("call-1", "t"));

        List<ChatMessage> kept = BrainCoordinator.trimmedTail(rest, 0);

        assertTrue(kept.isEmpty());
    }
}
