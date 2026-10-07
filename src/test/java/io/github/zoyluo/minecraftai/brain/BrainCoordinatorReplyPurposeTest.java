package io.github.zoyluo.minecraftai.brain;

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

    @Test
    void aPlanThatDeclaresMoreStepsSaysSo() {
        assertTrue(BrainCoordinator.planDeclaresMoreSteps(List.of(
                new ChatToolCall("call-1", "say",
                        "{\"message\":\"I will gather logs, then craft a table.\",\"purpose\":\"plan\",\"more_steps\":true}"))));
    }

    @Test
    void aPlanWithoutTheFlagOrWithItFalseDeclaresNothing() {
        assertFalse(BrainCoordinator.planDeclaresMoreSteps(List.of(
                new ChatToolCall("call-1", "say", "{\"message\":\"I will gather logs.\",\"purpose\":\"plan\"}"))));
        assertFalse(BrainCoordinator.planDeclaresMoreSteps(List.of(
                new ChatToolCall("call-1", "say",
                        "{\"message\":\"I will gather logs.\",\"purpose\":\"plan\",\"more_steps\":false}"))));
        assertFalse(BrainCoordinator.planDeclaresMoreSteps(null));
        assertFalse(BrainCoordinator.planDeclaresMoreSteps(List.of()));
    }

    @Test
    void onlyAValidPlanCanDeclareMoreSteps() {
        // The flag belongs to the plan: an answer or a status line that carries it is not a plan, and a
        // value that is not a boolean is no declaration.
        assertFalse(BrainCoordinator.planDeclaresMoreSteps(List.of(
                new ChatToolCall("call-1", "say", "{\"message\":\"Done.\",\"purpose\":\"status\",\"more_steps\":true}"))));
        assertFalse(BrainCoordinator.planDeclaresMoreSteps(List.of(
                new ChatToolCall("call-1", "say", "{\"message\":\"\",\"purpose\":\"plan\",\"more_steps\":true}"))));
        assertFalse(BrainCoordinator.planDeclaresMoreSteps(List.of(
                new ChatToolCall("call-1", "say", "{\"message\":\"Plan.\",\"purpose\":\"plan\",\"more_steps\":\"maybe\"}"))));
        assertFalse(BrainCoordinator.planDeclaresMoreSteps(List.of(
                new ChatToolCall("call-1", "say", "not json"))));
    }
}
