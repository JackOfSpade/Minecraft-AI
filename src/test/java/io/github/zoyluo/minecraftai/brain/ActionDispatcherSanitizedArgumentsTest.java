package io.github.zoyluo.minecraftai.brain;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * tool_dispatch log lines redact every "message"/"text"/"value" argument so debugging bot chat
 * (say/tell_bot) from logs was impossible, even though the player's own chat_in is logged
 * verbatim. say/tell_bot should log their chat text (truncated/escaped) instead; every other
 * tool must keep full redaction.
 */
final class ActionDispatcherSanitizedArgumentsTest {
    @Test
    void sayMessageIsLoggedNotRedacted() {
        JsonObject args = new JsonObject();
        args.addProperty("purpose", "chat");
        args.addProperty("message", "hello there");

        JsonObject sanitized = ActionDispatcher.sanitizedArguments("say", args);

        assertEquals("hello there", sanitized.get("message").getAsString());
    }

    @Test
    void tellBotTextIsLoggedNotRedacted() {
        JsonObject args = new JsonObject();
        args.addProperty("target", "Moss2");
        args.addProperty("text", "follow me");

        JsonObject sanitized = ActionDispatcher.sanitizedArguments("tell_bot", args);

        assertEquals("follow me", sanitized.get("text").getAsString());
    }

    @Test
    void sayMessageIsTruncatedTo300CharsWithEllipsis() {
        String longMessage = "a".repeat(400);
        JsonObject args = new JsonObject();
        args.addProperty("message", longMessage);

        JsonObject sanitized = ActionDispatcher.sanitizedArguments("say", args);

        String logged = sanitized.get("message").getAsString();
        assertEquals(303, logged.length());
        assertTrue(logged.endsWith("..."));
        assertEquals("a".repeat(300) + "...", logged);
    }

    @Test
    void sayMessageNewlinesAreEscapedToASingleLine() {
        JsonObject args = new JsonObject();
        args.addProperty("message", "line one\nline two\r\nline three");

        JsonObject sanitized = ActionDispatcher.sanitizedArguments("say", args);

        String logged = sanitized.get("message").getAsString();
        assertTrue(logged.indexOf('\n') < 0 && logged.indexOf('\r') < 0, "expected no raw newlines: " + logged);
        assertEquals("line one\\nline two\\nline three", logged);
    }

    @Test
    void otherToolsStillRedactMessageTextAndValue() {
        JsonObject args = new JsonObject();
        args.addProperty("message", "secret contents");
        args.addProperty("text", "more secret contents");
        args.addProperty("value", "even more");

        JsonObject sanitized = ActionDispatcher.sanitizedArguments("deposit", args);

        assertEquals("<redacted>", sanitized.get("message").getAsString());
        assertEquals("<redacted>", sanitized.get("text").getAsString());
        assertEquals("<redacted>", sanitized.get("value").getAsString());
    }

    @Test
    void sayNonStringMessageFieldIsStillRedacted() {
        JsonObject args = new JsonObject();
        args.addProperty("message", 12345);

        JsonObject sanitized = ActionDispatcher.sanitizedArguments("say", args);

        assertEquals("<redacted>", sanitized.get("message").getAsString());
    }

    @Test
    void nullArgumentsProducesEmptyObject() {
        JsonObject sanitized = ActionDispatcher.sanitizedArguments("say", null);
        assertTrue(sanitized.entrySet().isEmpty());
    }
}
