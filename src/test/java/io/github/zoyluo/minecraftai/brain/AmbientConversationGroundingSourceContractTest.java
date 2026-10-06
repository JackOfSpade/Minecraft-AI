package io.github.zoyluo.minecraftai.brain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ambient chat is free-form text, so its evidence boundary has to be especially narrow. A failed
 * task is useful to the planner but cannot prove a bot travelled somewhere or observed terrain.
 */
final class AmbientConversationGroundingSourceContractTest {
    private static final Path AMBIENT = Path.of(
            "src/main/java/io/github/zoyluo/minecraftai/brain/AmbientConversationCoordinator.java");

    @Test
    void ambientPromptTreatsOmissionAndTaskOutcomesAsUnknownSceneEvidence() throws IOException {
        String source = Files.readString(AMBIENT);
        String prompt = methodBody(source, "private static String systemPrompt(");

        assertTrue(prompt.contains("An omitted material, place, route, or resource is UNKNOWN"),
                "absence from an ambient prompt must never become an absence claim about the world");
        assertTrue(prompt.contains("The conversation transcript is dialogue, not world evidence"),
                "another bot's social wording must not be promoted into scene evidence");
        assertTrue(prompt.contains("NO travel or action history")
                        && prompt.contains("Never claim that you tried, searched, found, failed to")
                        && prompt.contains("A task's outcome, an inventory, and missing observations"),
                "ambient chat must not manufacture a journey, a search, or terrain from task bookkeeping");
    }

    @Test
    void ambientPayloadPassesOnlyCurrentSelfAndObservedHighlights() throws IOException {
        String source = Files.readString(AMBIENT);
        String payload = methodBody(source, "static String userPayload(");

        assertFalse(payload.contains("snapshot.toJson()"),
                "the full planner snapshot leaks task failures, memory, and raw lists into social chat");
        assertFalse(payload.contains("snapshot.task()") || payload.contains("snapshot.knownStorage()")
                        || payload.contains("snapshot.blocks()") || payload.contains("snapshot.entities()")
                        || payload.contains("snapshot.items()"),
                "ambient payload must not smuggle task state, remembered places, or unfiltered scene lists");
        assertTrue(payload.contains("appendSelfFacts(builder, snapshot.self())")
                        && payload.contains("appendObservationFacts(builder, snapshot.highlights())"),
                "the only world-derived context must be the present self state and observation-fenced highlights");
        assertTrue(payload.contains("Their absence means unknown, not absent")
                        && payload.contains("No task state, task failure, travel history, route history"),
                "the payload must tell the model how to interpret its deliberately incomplete context");
    }

    @Test
    void noSceneEvidenceUsesANonSpatialFallbackInsteadOfAFreeFormModelLine() throws IOException {
        String source = Files.readString(AMBIENT);
        String turn = methodBody(source, "private void fireNextTurn(");
        String fallback = methodBody(source, "private static String ambientSocialFallback(");

        assertTrue(turn.contains("if (!hasSceneObservation(snapshot.highlights()))")
                        && turn.contains("conversation.pendingLine = ambientSocialFallback(mustBeStatement)")
                        && turn.contains("ambient_social_fallback_no_scene_evidence"),
                "without a current observed scene, ambient chat must use a deterministic social fallback");
        assertFalse(fallback.toLowerCase().contains("cliff")
                        || fallback.toLowerCase().contains("terrain")
                        || fallback.toLowerCase().contains("search"),
                "the no-evidence fallback must not itself narrate a scene or a journey");
    }

    /** The decisions themselves are unit-tested in AmbientConversationCoordinatorTest; this pins that every line passes through them. */
    @Test
    void noLineIsSpokenWithoutAnAddresseeStillPresent() throws IOException {
        String source = Files.readString(AMBIENT);
        String start = methodBody(source, "private void maybeStart(");
        assertTrue(start.contains("hasEnoughParticipants(eligible.size(), cfg)")
                        && start.contains("requiredParticipants(cfg)"),
                "a conversation starts only with two or more eligible companions, whatever the configured minimum");
        assertTrue(start.contains("indexOfOpener(chosen, ObservableWorldQuery::canNoticeCreature)")
                        && start.indexOf("indexOfOpener(") < start.indexOf("new ActiveConversation("),
                "nothing starts unless its first speaker has noticed another participant");
        String turn = methodBody(source, "private void fireNextTurn(");
        assertTrue(turn.indexOf("addresseePresent(conversation, speakerId)") >= 0
                        && turn.indexOf("addresseePresent(conversation, speakerId)") < turn.indexOf("ambientSocialFallback("),
                "the canned fallback line is chosen only after an addressee is confirmed");
        int noticed = turn.indexOf("ObservableWorldQuery.canNoticeCreature(speaker, other)");
        assertTrue(noticed > turn.indexOf("if (!hasSceneObservation(") && noticed < turn.indexOf("ambientSocialFallback("),
                "the canned fallback line, which speaks of company, is chosen only when the speaker has noticed a participant");
        String reveal = methodBody(source, "private void revealAndAdvance(");
        assertTrue(reveal.indexOf("addresseePresent(conversation, speakerId)") >= 0
                        && reveal.indexOf("addresseePresent(conversation, speakerId)") < reveal.indexOf("sendBotReply("),
                "a line whose listeners left during the reading pause is dropped, not spoken to nobody");
    }

    private static String methodBody(String source, String signature) {
        int signatureAt = source.indexOf(signature);
        assertTrue(signatureAt >= 0, () -> "missing method signature: " + signature);
        int open = source.indexOf('{', signatureAt);
        assertTrue(open >= 0, () -> "missing method body: " + signature);
        int depth = 0;
        for (int at = open; at < source.length(); at++) {
            char current = source.charAt(at);
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return source.substring(open, at + 1);
            }
        }
        throw new AssertionError("unterminated method body: " + signature);
    }
}
