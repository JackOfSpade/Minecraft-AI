package io.github.zoyluo.minecraftai.brain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * A small, bounded per-bot ring of recent chat lines, rendered as a labelled context block for
 * every INITIAL model request (a fresh player instruction starts a brand-new provider-side
 * conversation with no memory of anything said before it -- see BrainCoordinator.handleMessage
 * clearing conversation.history and, for Gemini, starting a fresh stored interaction). Without
 * this, a follow-up such as "so shovel dont increase speed?" is answered with zero knowledge of
 * the exchange that produced it.
 *
 * <p>Caps -- MAX_LINES=30, MAX_AGE=30 minutes, MAX_TOTAL_CHARS=4000, MAX_LINE_CHARS=300 -- are
 * deliberately generous but still trivial next to the rest of the prompt: each planner call
 * already sends roughly 8k input tokens (59 tool schemas plus the perception snapshot). 4000
 * characters of transcript is roughly 1k tokens: comfortably inside even the smallest configured
 * model, gemini-3.5-flash-lite (a 1M-token context window), and cheap given how low the output
 * token usage of this mod already is, while a bound this small keeps the model focused on the
 * current instruction instead of re-litigating old chat.</p>
 *
 * <p>This class is a pure, static, in-memory helper: no I/O, no game-object references. The
 * windowing/formatting logic ({@link #windowFor} and {@link #render}) takes an explicit "now"
 * so it is deterministically unit-testable; {@link #recordPlayerLine}/{@link #recordBotReply}/
 * {@link #renderRecentChat} are the real-clock convenience wrappers the rest of the brain
 * package calls.</p>
 */
public final class ChatTranscript {
    public static final int MAX_LINES = 30;
    public static final long MAX_AGE_MILLIS = TimeUnit.MINUTES.toMillis(30);
    public static final int MAX_TOTAL_CHARS = 4000;
    public static final int MAX_LINE_CHARS = 300;

    private static final String HEADER = "Recent chat with you (oldest first):";

    private static final Map<UUID, Deque<Entry>> RINGS = new ConcurrentHashMap<>();

    private ChatTranscript() {
    }

    /** Records a line the bot received (from a real player or, via tell_bot, another bot). */
    public static void recordPlayerLine(UUID botId, String senderName, String text) {
        record(botId, senderName, text, System.currentTimeMillis());
    }

    /** Records the bot's own outgoing reply (say, or any other text the bot sends back). */
    public static void recordBotReply(UUID botId, String botName, String text) {
        if (botName == null || botName.isBlank()) {
            return;
        }
        record(botId, botName + " (you)", text, System.currentTimeMillis());
    }

    /** Renders this bot's current transcript window using the real clock, or "" when empty. */
    public static String renderRecentChat(UUID botId) {
        return render(botId, System.currentTimeMillis());
    }

    /** Drops one bot's ring entirely (bot removal / conversation reset). */
    public static void clear(UUID botId) {
        if (botId != null) {
            RINGS.remove(botId);
        }
    }

    /** Drops every bot's ring (server stop / full brain shutdown). */
    public static void clearAll() {
        RINGS.clear();
    }

    static void record(UUID botId, String label, String text, long nowMillis) {
        if (botId == null || label == null || label.isBlank() || text == null || text.isBlank()) {
            return;
        }
        Entry entry = new Entry(label, truncateLine(text), nowMillis);
        Deque<Entry> ring = RINGS.computeIfAbsent(botId, ignored -> new ArrayDeque<>());
        List<Entry> evicted = new ArrayList<>();
        synchronized (ring) {
            ring.addLast(entry);
            // Lines that age out of the window or overflow the ring are not lost outright: they move to the
            // bot's conversation memory (ChatMemory), which folds them into a summary later.
            while (ring.size() > 1 && nowMillis - ring.peekFirst().timestampMillis() > MAX_AGE_MILLIS) {
                evicted.add(ring.removeFirst());
            }
            while (ring.size() > MAX_LINES) {
                evicted.add(ring.removeFirst());
            }
        }
        if (!evicted.isEmpty()) {
            ChatMemory.offerEvicted(botId, evicted);
        }
    }

    /** The newest {@code count} raw lines of one bot's ring, oldest first (for persistence). */
    static List<Entry> tail(UUID botId, int count) {
        Deque<Entry> ring = botId == null ? null : RINGS.get(botId);
        if (ring == null || count <= 0) {
            return List.of();
        }
        synchronized (ring) {
            List<Entry> all = new ArrayList<>(ring);
            return List.copyOf(all.subList(Math.max(0, all.size() - count), all.size()));
        }
    }

    /**
     * Puts restored lines (oldest first, all still inside the age window) back in front of the ring. A restored
     * line that is already in the ring (same label, text and timestamp: a bot restored while its live ring still
     * holds the same chat) is dropped, so a restore never shows a line twice.
     */
    static void restore(UUID botId, List<Entry> entries) {
        if (botId == null || entries == null || entries.isEmpty()) {
            return;
        }
        Deque<Entry> ring = RINGS.computeIfAbsent(botId, ignored -> new ArrayDeque<>());
        synchronized (ring) {
            List<Entry> merged = new ArrayList<>();
            for (Entry entry : entries) {
                if (!ring.contains(entry) && !merged.contains(entry)) {
                    merged.add(entry);
                }
            }
            merged.addAll(ring);
            ring.clear();
            ring.addAll(merged.subList(Math.max(0, merged.size() - MAX_LINES), merged.size()));
        }
    }

    /** Removes and returns one bot's whole ring, oldest first (a conversation reset keeps the lines as memory). */
    static List<Entry> drain(UUID botId) {
        Deque<Entry> ring = botId == null ? null : RINGS.remove(botId);
        if (ring == null) {
            return List.of();
        }
        synchronized (ring) {
            return List.copyOf(ring);
        }
    }

    /** Test-only seam (mirrors {@code BrainCoordinator.setAwaitingTaskForTest}): a defensive copy
     * of one bot's raw ring contents, oldest-first, without going through the age/char window. */
    static List<Entry> snapshotForTest(UUID botId) {
        Deque<Entry> ring = RINGS.get(botId);
        if (ring == null) {
            return List.of();
        }
        synchronized (ring) {
            return List.copyOf(ring);
        }
    }

    static String render(UUID botId, long nowMillis) {
        if (botId == null) {
            return "";
        }
        Deque<Entry> ring = RINGS.get(botId);
        if (ring == null || ring.isEmpty()) {
            return "";
        }
        List<Entry> snapshot;
        synchronized (ring) {
            snapshot = new ArrayList<>(ring);
        }
        List<String> lines = windowFor(snapshot, nowMillis);
        if (lines.isEmpty()) {
            return "";
        }
        return HEADER + "\n" + String.join("\n", lines);
    }

    /**
     * Applies the age filter, then the oldest-first char budget, returning formatted lines in
     * their original (oldest-first) order. Package-private and static so the caps, ordering, age
     * rendering, and truncation can each be unit-tested without a real clock or a bot.
     */
    static List<String> windowFor(List<Entry> entries, long nowMillis) {
        List<String> fresh = new ArrayList<>();
        for (Entry entry : entries) {
            long age = nowMillis - entry.timestampMillis();
            if (age < 0 || age > MAX_AGE_MILLIS) {
                continue;
            }
            fresh.add(formatLine(entry, age));
        }
        while (fresh.size() > 1 && totalChars(fresh) > MAX_TOTAL_CHARS) {
            fresh.remove(0);
        }
        // A single remaining line can still exceed the budget (a near-300-char line plus its
        // age/label prefix); that is an acceptable, rare overshoot rather than losing the only
        // context line entirely.
        return fresh;
    }

    private static int totalChars(List<String> lines) {
        int total = 0;
        for (String line : lines) {
            total += line.length();
        }
        // Count the newline that will join this line to the next one, matching how render()
        // joins the final list, so the budget reflects the block that is actually sent.
        total += Math.max(0, lines.size() - 1);
        return total;
    }

    private static String formatLine(Entry entry, long ageMillis) {
        return "[" + formatAge(ageMillis) + " ago] " + entry.label() + ": " + entry.text();
    }

    static String formatAge(long ageMillis) {
        long minutes = ageMillis / 60_000L;
        return minutes < 1 ? "<1m" : minutes + "m";
    }

    private static String truncateLine(String text) {
        String singleLine = text.replaceAll("[\\r\\n]+", " ").trim();
        return singleLine.length() <= MAX_LINE_CHARS
                ? singleLine
                : singleLine.substring(0, MAX_LINE_CHARS);
    }

    record Entry(String label, String text, long timestampMillis) {
    }
}
