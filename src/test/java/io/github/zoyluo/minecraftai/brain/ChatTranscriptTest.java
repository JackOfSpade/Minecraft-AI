package io.github.zoyluo.minecraftai.brain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ChatTranscriptTest {
    private static final UUID BOT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    @AfterEach
    void clearRing() {
        ChatTranscript.clear(BOT_ID);
    }

    @Test
    void emptyRingRendersEmptyString() {
        assertEquals("", ChatTranscript.render(BOT_ID, 0L));
    }

    @Test
    void nullOrBlankInputsAreIgnored() {
        ChatTranscript.record(BOT_ID, null, "hello", 0L);
        ChatTranscript.record(BOT_ID, "", "hello", 0L);
        ChatTranscript.record(BOT_ID, "Jack", null, 0L);
        ChatTranscript.record(BOT_ID, "Jack", "   ", 0L);
        assertEquals("", ChatTranscript.render(BOT_ID, 0L));
    }

    @Test
    void ordersOldestFirstAndFormatsAgeInMinutes() {
        ChatTranscript.record(BOT_ID, "Jack", "stay here", 0L);
        ChatTranscript.record(BOT_ID, "Moss (you)", "I will hold my position here.", 120_000L);

        String rendered = ChatTranscript.render(BOT_ID, 240_000L);

        String expected = "Recent chat with you (oldest first):\n"
                + "[4m ago] Jack: stay here\n"
                + "[2m ago] Moss (you): I will hold my position here.";
        assertEquals(expected, rendered);
    }

    @Test
    void underOneMinuteRendersLessThanOneMinuteMarker() {
        ChatTranscript.record(BOT_ID, "Jack", "hi", 0L);

        assertEquals("[<1m ago] Jack: hi", lastFormattedLine(BOT_ID, 30_000L));
    }

    @Test
    void ringDropsOldestEntryBeyondMaxLines() {
        for (int i = 0; i < ChatTranscript.MAX_LINES + 5; i++) {
            ChatTranscript.record(BOT_ID, "Jack", "line" + i, i * 1000L);
        }

        List<String> lines = ChatTranscript.windowFor(
                ChatTranscript.snapshotForTest(BOT_ID), (ChatTranscript.MAX_LINES + 5) * 1000L);

        assertEquals(ChatTranscript.MAX_LINES, lines.size());
        assertTrue(lines.get(0).contains("line5"), "the 5 oldest lines must have been evicted");
        assertTrue(lines.get(lines.size() - 1).contains("line" + (ChatTranscript.MAX_LINES + 4)));
    }

    @Test
    void windowDropsEntriesOlderThanMaxAge() {
        List<ChatTranscript.Entry> entries = List.of(
                new ChatTranscript.Entry("Jack", "too old", 0L),
                new ChatTranscript.Entry("Jack", "just fresh enough", ChatTranscript.MAX_AGE_MILLIS));

        long now = ChatTranscript.MAX_AGE_MILLIS + 60_000L; // "too old" is now 31 minutes old
        List<String> lines = ChatTranscript.windowFor(entries, now);

        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("just fresh enough"));
    }

    @Test
    void overlongSingleLineIsTruncatedToMaxLineChars() {
        String longMessage = "x".repeat(ChatTranscript.MAX_LINE_CHARS + 200);
        ChatTranscript.record(BOT_ID, "Jack", longMessage, 0L);

        String line = lastFormattedLine(BOT_ID, 0L);
        int prefixLength = "[<1m ago] Jack: ".length();
        assertEquals(ChatTranscript.MAX_LINE_CHARS, line.length() - prefixLength);
    }

    @Test
    void multilineMessageIsCollapsedToOneLine() {
        ChatTranscript.record(BOT_ID, "Jack", "first\r\nsecond\nthird", 0L);

        assertEquals("[<1m ago] Jack: first second third", lastFormattedLine(BOT_ID, 0L));
    }

    @Test
    void totalCharBudgetDropsOldestLinesFirst() {
        // Each stored line is already capped at MAX_LINE_CHARS (300), so a handful of
        // near-max-length lines is enough to exceed the 4000-character total budget.
        String longMessage = "y".repeat(ChatTranscript.MAX_LINE_CHARS);
        List<ChatTranscript.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            entries.add(new ChatTranscript.Entry("Jack", longMessage + i, i * 1000L));
        }

        List<String> lines = ChatTranscript.windowFor(entries, 20_000L);

        int total = 0;
        for (String line : lines) {
            total += line.length();
        }
        total += Math.max(0, lines.size() - 1);
        assertTrue(total <= ChatTranscript.MAX_TOTAL_CHARS, "rendered block must respect the char budget");
        assertTrue(lines.size() < entries.size(), "the oldest lines must have been dropped to fit the budget");
        // The kept lines must be a suffix (most-recent) of the original oldest-first entries.
        assertTrue(lines.get(lines.size() - 1).contains("y".repeat(ChatTranscript.MAX_LINE_CHARS) + "19"));
    }

    @Test
    void clearRemovesOnlyThatBotsRing() {
        UUID other = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
        try {
            ChatTranscript.record(BOT_ID, "Jack", "hello", 0L);
            ChatTranscript.record(other, "Jack", "hello elsewhere", 0L);

            ChatTranscript.clear(BOT_ID);

            assertEquals("", ChatTranscript.render(BOT_ID, 0L));
            assertFalse(ChatTranscript.render(other, 0L).isEmpty());
        } finally {
            ChatTranscript.clear(other);
        }
    }

    @Test
    void recordBotReplyLabelsTheSpeakerAsYou() {
        ChatTranscript.recordBotReply(BOT_ID, "Moss", "I will hold my position here.");

        assertEquals("[<1m ago] Moss (you): I will hold my position here.",
                lastFormattedLine(BOT_ID, System.currentTimeMillis()));
    }

    private static String lastFormattedLine(UUID botId, long nowMillis) {
        List<String> lines = ChatTranscript.windowFor(ChatTranscript.snapshotForTest(botId), nowMillis);
        return lines.get(lines.size() - 1);
    }
}
