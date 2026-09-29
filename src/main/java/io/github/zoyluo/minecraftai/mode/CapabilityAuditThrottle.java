package io.github.zoyluo.minecraftai.mode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides which capability decisions get their own {@code capability_decision} log line and which are only
 * counted into a periodic {@code capability_decision_summary}. Pure (no Minecraft types, no clock: the caller
 * passes the server tick), so the policy is unit-testable.
 *
 * <p>Rules:</p>
 * <ul>
 *   <li>The <b>first occurrence</b> of every (bot, capability, allowed, reason, context) is always logged in full.
 *       A denial can therefore never go unseen, and an ALLOWED decision can never be hidden (the strict-survival
 *       canary GameTests read the bot log for {@code allowed='true'} lines).</li>
 *   <li>A <b>repeat</b> of an already-seen combination is not logged individually. It is counted per
 *       (bot, capability, allowed, reason); once a window of {@link #SUMMARY_INTERVAL_TICKS} has passed since the
 *       first counted repeat, the next repeat of that key returns a {@link Summary} (count, window length, the
 *       contexts that repeated) for the caller to log, and a new window starts. A lone repeat that no later
 *       repeat pushes out is reported by the periodic {@link #drainDue} sweep, and pending counts are flushed by
 *       {@link #drain} when a bot goes away and {@link #drainAll} on a world boundary, shutdown or reload.</li>
 *   <li>Decisions the caller marks {@code alwaysAudit} (manual and emergency teleports) are always logged in full.</li>
 * </ul>
 * Before this existed the same bot logged one INFO line per (capability, context) every 5 s forever, which was 18%
 * of a two-hour session log (about 3,700 lines, all identical denials).
 */
public final class CapabilityAuditThrottle {
    public static final int SUMMARY_INTERVAL_TICKS = 1200;
    /** Upper bound on remembered contexts per key; on overflow the memory restarts (a harmless re-log). */
    static final int MAX_SEEN_CONTEXTS = 128;
    static final int MAX_SUMMARY_CONTEXTS = 6;

    public record Key(UUID botId, PrivilegedCapability capability, boolean allowed, CapabilityDecision.Reason reason) {
    }

    public record Summary(Key key, int count, int windowTicks, List<String> contexts, int otherContexts) {
    }

    public record Outcome(boolean logDecision, Summary summary) {
        static final Outcome LOG = new Outcome(true, null);
        static final Outcome QUIET = new Outcome(false, null);
    }

    private static final class Entry {
        final Set<String> seenContexts = new HashSet<>();
        final Set<String> repeatContexts = new LinkedHashSet<>();
        int repeatCount;
        int windowStartTick;
        int otherContexts;
    }

    private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();

    public Outcome observe(UUID botId,
                           PrivilegedCapability capability,
                           boolean allowed,
                           CapabilityDecision.Reason reason,
                           String context,
                           int nowTick,
                           boolean alwaysAudit) {
        Key key = new Key(botId, capability, allowed, reason);
        Entry entry = entries.computeIfAbsent(key, k -> new Entry());
        synchronized (entry) {
            if (alwaysAudit) {
                entry.seenContexts.add(context);
                return Outcome.LOG;
            }
            if (entry.seenContexts.size() >= MAX_SEEN_CONTEXTS) {
                entry.seenContexts.clear();
            }
            if (entry.seenContexts.add(context)) {
                return Outcome.LOG;
            }
            if (entry.repeatCount == 0) {
                entry.windowStartTick = nowTick;
            }
            entry.repeatCount++;
            if (entry.repeatContexts.size() < MAX_SUMMARY_CONTEXTS) {
                entry.repeatContexts.add(context);
            } else if (!entry.repeatContexts.contains(context)) {
                entry.otherContexts++;
            }
            if (nowTick - entry.windowStartTick >= SUMMARY_INTERVAL_TICKS) {
                return new Outcome(false, takeSummary(key, entry, nowTick));
            }
            return Outcome.QUIET;
        }
    }

    /**
     * Summaries for every pending key whose window has run for at least {@link #SUMMARY_INTERVAL_TICKS}. Called on
     * a periodic sweep so a lone repeat (a single count after the first occurrence, which {@link #observe} only
     * reports when a later repeat arrives) is still reported within a bounded time: the window length plus the
     * sweep period.
     */
    public List<Summary> drainDue(int nowTick) {
        List<Summary> out = new ArrayList<>();
        for (var e : entries.entrySet()) {
            Entry entry = e.getValue();
            synchronized (entry) {
                if (entry.repeatCount > 0 && nowTick - entry.windowStartTick >= SUMMARY_INTERVAL_TICKS) {
                    out.add(takeSummary(e.getKey(), entry, nowTick));
                }
            }
        }
        return out;
    }

    /** Summaries for every pending key of every bot; call before the whole table is cleared (world boundary, shutdown, reload). */
    public List<Summary> drainAll(int nowTick) {
        List<Summary> out = new ArrayList<>();
        for (var e : entries.entrySet()) {
            Entry entry = e.getValue();
            synchronized (entry) {
                if (entry.repeatCount > 0) {
                    out.add(takeSummary(e.getKey(), entry, nowTick));
                }
            }
        }
        return out;
    }

    /** Summaries for every pending (counted but not yet reported) key of one bot; call when the bot goes away. */
    public List<Summary> drain(UUID botId, int nowTick) {
        List<Summary> out = new ArrayList<>();
        for (var e : entries.entrySet()) {
            if (!e.getKey().botId().equals(botId)) {
                continue;
            }
            Entry entry = e.getValue();
            synchronized (entry) {
                if (entry.repeatCount > 0) {
                    out.add(takeSummary(e.getKey(), entry, nowTick));
                }
            }
        }
        return out;
    }

    public void clear(UUID botId) {
        entries.keySet().removeIf(key -> key.botId().equals(botId));
    }

    public void clearAll() {
        entries.clear();
    }

    private static Summary takeSummary(Key key, Entry entry, int nowTick) {
        Summary summary = new Summary(key, entry.repeatCount, Math.max(0, nowTick - entry.windowStartTick),
                List.copyOf(entry.repeatContexts), entry.otherContexts);
        entry.repeatCount = 0;
        entry.repeatContexts.clear();
        entry.otherContexts = 0;
        entry.windowStartTick = nowTick;
        return summary;
    }
}
