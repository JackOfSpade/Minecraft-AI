package io.github.zoyluo.aibot.brain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BrainCoordinatorReplyPurposeTest {
    @Test
    void acceptsAnExplicitAnswerOnlyReply() {
        assertTrue(BrainCoordinator.isAnswerOnlyReply(List.of(
                new ChatToolCall("call-1", "say", "{\"message\":\"Yes, this looks promising.\",\"purpose\":\"answer\"}"))));
    }

    @Test
    void doesNotTreatAPlanWithoutAnActionAsAnAnswer() {
        assertFalse(BrainCoordinator.isAnswerOnlyReply(List.of(
                new ChatToolCall("call-1", "say", "{\"message\":\"I will clear the grass.\",\"purpose\":\"plan\"}"))));
    }

    @Test
    void doesNotTrustAnUnlabelledSayCallAsAnAnswer() {
        assertFalse(BrainCoordinator.isAnswerOnlyReply(List.of(
                new ChatToolCall("call-1", "say", "{\"message\":\"I will clear the grass.\"}"))));
    }
}
