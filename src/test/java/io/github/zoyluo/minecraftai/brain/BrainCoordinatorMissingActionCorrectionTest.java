package io.github.zoyluo.minecraftai.brain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the missing_required_action continuation-correction text (the message injected when the
 * model responded with no in-world action). BrainCoordinator eagerly touches Minecraft registries
 * building a live continuation, so this asserts the fix as a source-text contract instead, the
 * same way ToolRegistryAssignTaskNullParamsTest pins its own invariant.
 *
 * The bot-reported bug: when part of a request is impossible with the available tools (e.g.
 * "give an item to the player" with no give_item tool), the model would repeat say(purpose=plan)
 * until model_call_budget_exhausted instead of doing the doable part and explaining the rest.
 * The correction text must tell the model to start the doable part(s) and plainly say which part
 * cannot be done.
 */
final class BrainCoordinatorMissingActionCorrectionTest {
    @Test
    void missingRequiredActionCorrectionKeepsExistingGuidanceAndAddsPartialCapabilityGuidance() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/minecraftai/brain/BrainCoordinator.java"));

        // Existing invariants preserved verbatim.
        assertTrue(source.contains(
                "\"You did not start an in-world action. If the player asked only a question, call say \""),
                "expected the original opening sentence to be preserved");
        assertTrue(source.contains(
                "+ \"with purpose=answer. Otherwise call an appropriate action or goal tool now; a plan or \""),
                "expected the original second sentence to be preserved");
        assertTrue(source.contains(
                "+ \"status say alone is not enough. For grass, call clear_grass with the requested count. \""),
                "expected the original grass guidance to be preserved");

        // New guidance for partially-impossible requests (normalize away the source's line-wrap
        // whitespace inside the string-literal concatenation before matching).
        String normalized = source.replaceAll("\"\\s*\\n\\s*\\+\\s*\"", "");
        assertTrue(normalized.contains("If part of the request cannot be done with your available tools"),
                "expected new guidance about partially-impossible requests");
        assertTrue(normalized.contains("start the part(s) that can be done now with an action or goal tool"),
                "expected new guidance to instruct starting the doable part");
        assertTrue(normalized.contains("to plainly tell the player which part cannot be done and why"),
                "expected new guidance to instruct plainly telling the player what cannot be done");
    }
}
