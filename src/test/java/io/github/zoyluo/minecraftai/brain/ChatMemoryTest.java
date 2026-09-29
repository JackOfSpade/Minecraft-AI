package io.github.zoyluo.minecraftai.brain;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ChatMemoryTest {
    private static final UUID BOT = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final MinecraftAiConfig.ConversationMemory CFG = MinecraftAiConfig.ConversationMemory.defaults();
    private static final long NOW = 1_000_000_000_000L;

    @BeforeEach
    @AfterEach
    void reset() {
        ChatMemory.forget(BOT);
        ChatTranscript.clear(BOT);
    }

    private static List<ChatTranscript.Entry> lines(int count, String prefix) {
        List<ChatTranscript.Entry> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(new ChatTranscript.Entry("Jack", prefix + i, NOW + i));
        }
        return out;
    }

    /** Fills the pending list through the real eviction path: overflow the recent-chat ring. */
    private static void overflowRing(String firstLine, int extra) {
        ChatTranscript.record(BOT, "Jack", firstLine, NOW);
        for (int i = 0; i < ChatTranscript.MAX_LINES + extra - 1; i++) {
            ChatTranscript.record(BOT, "Jack", "filler" + i, NOW + 1 + i);
        }
    }

    // --- summarisation -------------------------------------------------------------------------------------

    @Test
    void linesThatOverflowTheRecentChatRingBecomePendingMemory() {
        overflowRing("remember that my base is at the birch forest", 10);
        assertEquals(10, ChatMemory.pendingCountOf(BOT));
        String block = ChatMemory.render(BOT, CFG);
        assertTrue(block.startsWith(ChatMemory.HEADER));
        assertTrue(block.contains("remember that my base is at the birch forest"));
    }

    @Test
    void linesThatAgeOutOfTheWindowAlsoMoveToMemory() {
        ChatTranscript.record(BOT, "Jack", "old fact", NOW);
        ChatTranscript.record(BOT, "Jack", "fresh", NOW + ChatTranscript.MAX_AGE_MILLIS + 1);
        assertEquals(1, ChatMemory.pendingCountOf(BOT));
        assertEquals(1, ChatTranscript.tail(BOT, 10).size());
    }

    @Test
    void summaryKeepsWhatTheStubModelReturnsAndPromptCarriesTheUserFacts() {
        overflowRing("please remember my name is Jack and I prefer diamond tools", 10);
        AtomicReference<String> sentSystem = new AtomicReference<>();
        AtomicReference<String> sentUser = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        ChatMemory.Transport stub = (system, user) -> {
            calls.incrementAndGet();
            sentSystem.set(system);
            sentUser.set(user);
            return "  Player is Jack;\nprefers diamond tools.  ";
        };
        assertTrue(ChatMemory.summariseIfDue(BOT, CFG, stub, NOW, Runnable::run, null));
        assertEquals(1, calls.get());
        assertTrue(sentUser.get().contains("please remember my name is Jack and I prefer diamond tools"));
        assertTrue(sentUser.get().contains("Existing notes:\n(none)"));
        assertTrue(sentSystem.get().contains("500"));
        assertEquals("Player is Jack; prefers diamond tools.", ChatMemory.summaryOf(BOT));
        assertEquals(0, ChatMemory.pendingCountOf(BOT));
        String block = ChatMemory.render(BOT, CFG);
        assertTrue(block.contains("Summary: Player is Jack; prefers diamond tools."));
        assertFalse(block.contains("not yet summarised"));
    }

    @Test
    void secondSummaryMergesWithThePreviousOne() {
        overflowRing("first fact", 10);
        AtomicReference<String> sentUser = new AtomicReference<>();
        ChatMemory.summariseIfDue(BOT, CFG, (s, u) -> "Notes one", NOW, Runnable::run, null);
        ChatMemory.offerEvicted(BOT, lines(10, "second"));
        long later = NOW + CFG.minIntervalSeconds() * 1000L;
        assertTrue(ChatMemory.summariseIfDue(BOT, CFG, (s, u) -> {
            sentUser.set(u);
            return "Notes one; notes two";
        }, later, Runnable::run, null));
        assertTrue(sentUser.get().contains("Existing notes:\nNotes one"));
        assertEquals("Notes one; notes two", ChatMemory.summaryOf(BOT));
    }

    @Test
    void summaryIsCappedAtTheConfiguredLength() {
        ChatMemory.offerEvicted(BOT, lines(10, "x"));
        ChatMemory.summariseIfDue(BOT, CFG, (s, u) -> "a".repeat(5000), NOW, Runnable::run, null);
        assertEquals(CFG.maxSummaryChars(), ChatMemory.summaryOf(BOT).length());
    }

    @Test
    void failedCallFallsBackToPlainTrimmingAndKeepsTheOldSummary() {
        ChatMemory.offerEvicted(BOT, lines(10, "x"));
        ChatMemory.summariseIfDue(BOT, CFG, (s, u) -> "Kept notes", NOW, Runnable::run, null);
        ChatMemory.offerEvicted(BOT, lines(CFG.maxPendingLines() + 15, "y"));
        long later = NOW + CFG.minIntervalSeconds() * 1000L;
        assertTrue(ChatMemory.summariseIfDue(BOT, CFG, (s, u) -> {
            throw new java.io.IOException("provider down");
        }, later, Runnable::run, null));
        assertEquals("Kept notes", ChatMemory.summaryOf(BOT));
        // Bounded: the oldest lines beyond maxPendingLines were plainly dropped.
        assertEquals(CFG.maxPendingLines(), ChatMemory.pendingCountOf(BOT));
        // An empty reply counts as a failure too.
        assertTrue(ChatMemory.summariseIfDue(BOT, CFG, (s, u) -> "   ", later + CFG.minIntervalSeconds() * 1000L,
                Runnable::run, null));
        assertEquals("Kept notes", ChatMemory.summaryOf(BOT));
    }

    @Test
    void callBudgetOneInFlightMinimumIntervalAndThreshold() {
        MinecraftAiConfig.ConversationMemory cfg = CFG;
        int threshold = cfg.summarizeAfterLines();
        assertFalse(ChatMemory.due(threshold - 1, false, 0, Long.MIN_VALUE, NOW, cfg));
        assertTrue(ChatMemory.due(threshold, false, 0, Long.MIN_VALUE, NOW, cfg));
        // One call in flight blocks another, until the call is presumed hung.
        assertFalse(ChatMemory.due(threshold, true, NOW, NOW - 1, NOW + 1000, cfg));
        assertTrue(ChatMemory.due(threshold, true, NOW, Long.MIN_VALUE, NOW + 4L * cfg.timeoutSeconds() * 1000L, cfg));
        // Minimum interval between two calls.
        long interval = cfg.minIntervalSeconds() * 1000L;
        assertFalse(ChatMemory.due(threshold, false, 0, NOW, NOW + interval - 1, cfg));
        assertTrue(ChatMemory.due(threshold, false, 0, NOW, NOW + interval, cfg));
        // Disabled.
        MinecraftAiConfig.ConversationMemory off = new MinecraftAiConfig.ConversationMemory(false, 500, 8, 40, 12, 20, 60, 400);
        assertFalse(ChatMemory.due(100, false, 0, Long.MIN_VALUE, NOW, off));
    }

    @Test
    void onlyOneCallIsMadeWhileTheFirstIsStillRunning() {
        ChatMemory.offerEvicted(BOT, lines(10, "x"));
        List<Runnable> queued = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        ChatMemory.Transport stub = (s, u) -> {
            calls.incrementAndGet();
            return "n";
        };
        assertTrue(ChatMemory.summariseIfDue(BOT, CFG, stub, NOW, queued::add, null));
        // The worker has not run yet: a second attempt (even much later, inside the hung-call window) is refused.
        assertFalse(ChatMemory.summariseIfDue(BOT, CFG, stub, NOW + 1000L, queued::add, null));
        queued.forEach(Runnable::run);
        assertEquals(1, calls.get());
    }

    @Test
    void appliedCallbackRunsOnlyAfterASuccessfulSummary() {
        ChatMemory.offerEvicted(BOT, lines(10, "x"));
        AtomicInteger applied = new AtomicInteger();
        ChatMemory.summariseIfDue(BOT, CFG, (s, u) -> {
            throw new IllegalStateException("boom");
        }, NOW, Runnable::run, applied::incrementAndGet);
        assertEquals(0, applied.get());
        ChatMemory.summariseIfDue(BOT, CFG, (s, u) -> "ok", NOW + CFG.minIntervalSeconds() * 1000L, Runnable::run,
                applied::incrementAndGet);
        assertEquals(1, applied.get());
    }

    @Test
    void batchRespectsThePromptBudgetButAlwaysTakesTheOldestLine() {
        List<ChatTranscript.Entry> big = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            big.add(new ChatTranscript.Entry("Jack", "z".repeat(ChatTranscript.MAX_LINE_CHARS), NOW + i));
        }
        List<ChatTranscript.Entry> batch = ChatMemory.takeBatch(big);
        assertTrue(batch.size() < big.size());
        assertTrue(batch.size() >= 1);
        assertEquals(big.get(0), batch.get(0));
    }

    // --- persistence ---------------------------------------------------------------------------------------

    @Test
    void snapshotRoundTripKeepsSummaryPendingAndRecentChat() {
        ChatMemory.offerEvicted(BOT, lines(10, "x"));
        ChatMemory.summariseIfDue(BOT, CFG, (s, u) -> "Player is Jack; base at the birch forest", NOW, Runnable::run, null);
        ChatMemory.offerEvicted(BOT, lines(3, "waiting"));
        long now = System.currentTimeMillis();
        ChatTranscript.record(BOT, "Jack", "come with me", now - 60_000L);
        ChatTranscript.record(BOT, "Moss (you)", "on my way", now - 30_000L);

        String json = ChatMemory.encode(BOT, CFG);
        assertNotNull(json);
        assertTrue(json.contains("\"version\":" + ChatMemory.SNAPSHOT_VERSION));

        // "Restart": all in-memory state is gone.
        ChatMemory.forget(BOT);
        ChatTranscript.clear(BOT);
        assertEquals("", ChatMemory.summaryOf(BOT));
        assertEquals("", ChatTranscript.renderRecentChat(BOT));

        ChatMemory.restore(BOT, json, now);
        assertEquals("Player is Jack; base at the birch forest", ChatMemory.summaryOf(BOT));
        assertEquals(3, ChatMemory.pendingCountOf(BOT));
        String recent = ChatTranscript.renderRecentChat(BOT);
        assertTrue(recent.contains("Jack: come with me"));
        assertTrue(recent.contains("Moss (you): on my way"));
        String block = ChatMemory.render(BOT, CFG);
        assertTrue(block.contains("Summary: Player is Jack; base at the birch forest"));
        assertTrue(block.contains("waiting0"));
    }

    @Test
    void recentChatOlderThanTheWindowBecomesPendingOnRestore() {
        long now = System.currentTimeMillis();
        String json = ChatMemory.encodeSnapshot("Notes",
                List.of(),
                List.of(new ChatTranscript.Entry("Jack", "yesterday request", now - ChatTranscript.MAX_AGE_MILLIS - 5000L),
                        new ChatTranscript.Entry("Jack", "just now", now - 1000L)));
        ChatMemory.restore(BOT, json, now);
        assertEquals(1, ChatMemory.pendingCountOf(BOT));
        assertTrue(ChatMemory.render(BOT, CFG).contains("yesterday request"));
        assertTrue(ChatTranscript.renderRecentChat(BOT).contains("just now"));
        assertFalse(ChatTranscript.renderRecentChat(BOT).contains("yesterday request"));
    }

    @Test
    void emptyStateEncodesToNothing() {
        assertNull(ChatMemory.encode(BOT, CFG));
        assertNull(ChatMemory.encode(null, CFG));
    }

    @Test
    void oldMissingMalformedAndHostileSnapshotsAreTolerated() {
        for (String bad : new String[] {
                null, "", "   ", "not json", "[]", "42", "{}", "{\"summary\":\"no version\"}",
                "{\"version\":\"x\",\"summary\":\"s\"}", "{\"version\":0,\"summary\":\"s\"}"}) {
            ChatMemory.restore(BOT, bad, NOW);
            assertEquals("", ChatMemory.summaryOf(BOT), "input: " + bad);
            assertEquals(0, ChatMemory.pendingCountOf(BOT), "input: " + bad);
        }
        // Wrong member types and junk entries are skipped, unknown fields ignored, sizes clamped.
        String hostile = "{\"version\":7,\"future\":{\"a\":1},\"summary\":\"" + "s".repeat(9000) + "\","
                + "\"pending\":[1,\"x\",{\"label\":\"A\"},{\"label\":\"B\",\"text\":\"ok\",\"ts\":5},"
                + "{\"label\":\"C\",\"text\":\"bad ts\",\"ts\":\"nope\"}],\"tail\":\"not an array\"}";
        ChatMemory.Decoded decoded = ChatMemory.decode(hostile);
        assertEquals(ChatMemory.HARD_MAX_SUMMARY_CHARS, decoded.summary().length());
        assertEquals(1, decoded.pending().size());
        assertEquals("ok", decoded.pending().get(0).text());
        assertTrue(decoded.tail().isEmpty());
    }

    @Test
    void badSnapshotDoesNotWipeExistingMemory() {
        ChatMemory.offerEvicted(BOT, lines(2, "keep"));
        ChatMemory.restore(BOT, "garbage", NOW);
        assertEquals(2, ChatMemory.pendingCountOf(BOT));
    }

    @Test
    void conversationResetKeepsTheRecentChatAsMemoryAndForgetDropsIt() {
        ChatTranscript.record(BOT, "Jack", "keep this across the reset", NOW);
        ChatMemory.keepRecentChat(BOT);
        assertEquals("", ChatTranscript.renderRecentChat(BOT));
        assertEquals(1, ChatMemory.pendingCountOf(BOT));
        ChatMemory.forget(BOT);
        assertEquals(0, ChatMemory.pendingCountOf(BOT));
    }

    @Test
    void disabledMemoryRendersNothing() {
        ChatMemory.offerEvicted(BOT, lines(3, "x"));
        MinecraftAiConfig.ConversationMemory off = new MinecraftAiConfig.ConversationMemory(false, 500, 8, 40, 12, 20, 60, 400);
        assertEquals("", ChatMemory.render(BOT, off));
    }

    @Test
    void memoryConfigHasDefaultsAndPartialFilesFallBack() {
        MinecraftAiConfig.ConversationMemory partial = new MinecraftAiConfig.ConversationMemory(null, 0, 0, 0, 0, 0, 0, 0)
                .withDefaults(MinecraftAiConfig.ConversationMemory.defaults());
        assertEquals(MinecraftAiConfig.ConversationMemory.defaults(), partial);
        assertEquals(500, partial.maxSummaryChars());
        MinecraftAiConfig.Brain old = new MinecraftAiConfig.Brain(36, 6, 3, false, true, false, 3, false);
        assertEquals(MinecraftAiConfig.ConversationMemory.defaults(), old.memorySettings());
    }
}
