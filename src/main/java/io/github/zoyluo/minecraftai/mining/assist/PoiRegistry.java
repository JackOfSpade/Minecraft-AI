package io.github.zoyluo.minecraftai.mining.assist;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Per-bot, static, TTL-pruned dedupe registry of point-of-interest sites (mining-assist design 6.4), plus the
 * {@code BotMemory} ring-slot counter and the "open case" bookkeeping {@code PoiCoordinator} needs for resume
 * detection. Never touches {@code ServerWorld}, {@code TaskManager} or chat: pure bookkeeping keyed by
 * {@link UUID}, mirroring the {@code OreClaims}/{@code MissionAssistLedger} idiom (plain {@code HashMap}, server
 * thread only, no synchronization).
 *
 * <p><b>Design-text note (documented deliberate simplification, not a gap).</b> Design 6.4 says "A structure
 * label already stopped <b>in the same mission</b> also suppresses within {@code same_label_radius = 96}."
 * Nothing else in P0/P1 has a mission-<i>lifecycle</i> hook to bind to: {@code MissionAssistLedger} itself,
 * despite being described as "per mission," is purely a {@code (bot, mission-or-job-or-adhoc)} key plus a flat
 * {@code TTL_TICKS = 72_000} sweep, with no event that fires on mission end. This registry follows the exact
 * same established idiom: same-label suppression is bot+dimension scoped with {@link #STOPPED_TTL_TICKS} and
 * {@link #SAME_LABEL_RADIUS_BLOCKS}, not a strict mission boundary. This means a STOPPED entry can (a) outlive
 * its mission by up to {@value #STOPPED_TTL_TICKS} ticks into an unrelated later mission, or (b) lose
 * suppression mid-mission if the mission runs longer than {@value #STOPPED_TTL_TICKS} ticks. This is accepted
 * as consistent with the rest of the codebase's mission-scoping idiom, not silently: flag it in code review if
 * a future phase adds a real mission-lifecycle event, since at that point this should switch to keying off
 * it.</p>
 */
public final class PoiRegistry {
    public static final int MAX_ENTRIES_PER_BOT = 32;
    public static final int DECLINED_TTL_TICKS = 6000;
    public static final int STOPPED_TTL_TICKS = 24000;
    public static final int SAME_LABEL_RADIUS_BLOCKS = 96;
    public static final int RE_ASK_MIN_TICKS = 3000;
    public static final double RE_ASK_SCORE_GROWTH = 0.25D;
    public static final int RING_SIZE = 3;

    /** Life cycle of one dedupe entry (design 6.4/6.5). {@code CONSULTING} is P3 infrastructure: P2 only ever
     * records {@link #STOPPED} and {@link #DECLINED}. */
    public enum State {
        CONSULTING,
        STOPPED,
        DECLINED
    }

    /** One dedupe record. {@code score} is the structure score S at the tick it was recorded (used by the
     * DECLINED re-ask rule). */
    public record Entry(String dimensionKey, BlockPos anchor, String label, State state, double score, int recordedTick) {
        public Entry {
            dimensionKey = Objects.requireNonNull(dimensionKey, "dimensionKey");
            anchor = Objects.requireNonNull(anchor, "anchor").toImmutable();
            label = Objects.requireNonNull(label, "label");
            state = Objects.requireNonNull(state, "state");
        }
    }

    /** A site currently awaiting the player's "continue"/"cancel" (design 6.5's "case"). {@code source} is one
     * of {@code PoiCoordinator.Source.name()}, stored as a plain String so this class stays free of a
     * {@code coordination/} import. */
    public record OpenCase(String label, String source, String dimensionKey, BlockPos anchor) {
        public OpenCase {
            label = Objects.requireNonNull(label, "label");
            source = Objects.requireNonNull(source, "source");
            dimensionKey = Objects.requireNonNull(dimensionKey, "dimensionKey");
            anchor = Objects.requireNonNull(anchor, "anchor").toImmutable();
        }
    }

    /** {@code botId -> live entries}, newest last (append order; a refresh of an existing site keeps its slot). */
    private static final Map<UUID, List<Entry>> ENTRIES = new HashMap<>();
    /** {@code botId -> last handed-out ring slot}, 1..{@link #RING_SIZE}; absent means none handed out yet. */
    private static final Map<UUID, Integer> RING = new HashMap<>();
    /** {@code botId -> currently open case}, absent when none is open. */
    private static final Map<UUID, OpenCase> OPEN_CASES = new HashMap<>();
    /** Bots whose "Still paused:" restart notice has already been sent once for the currently open case. */
    private static final Set<UUID> RESTART_NOTICE_SENT = new HashSet<>();

    private PoiRegistry() {
    }

    /**
     * True when {@code (dimensionKey, anchor, label)} is suppressed by an existing live entry of this bot:
     * within {@code poi.dedupeRadius} (config) of a CONSULTING or STOPPED entry, within it of a DECLINED entry
     * that has not met the re-ask rule ({@code >= RE_ASK_MIN_TICKS} old AND {@code currentScore - entry.score
     * >= RE_ASK_SCORE_GROWTH}), or within {@link #SAME_LABEL_RADIUS_BLOCKS} of a STOPPED entry with the same
     * label. Prunes expired entries first (STOPPED older than {@link #STOPPED_TTL_TICKS}, DECLINED older than
     * {@link #DECLINED_TTL_TICKS}). Never called for MANDATORY candidates (design 6.4: "A DECLINED or STOPPED
     * registry entry never suppresses a mandatory candidate" — enforced by the caller never invoking this for a
     * mandatory band, not by this method).
     */
    public static boolean suppressed(UUID botId, String dimensionKey, BlockPos anchor, String label, double currentScore, int nowTick) {
        List<Entry> entries = ENTRIES.get(botId);
        if (entries == null || entries.isEmpty()) {
            return false;
        }
        pruneExpired(entries, nowTick);
        if (entries.isEmpty()) {
            return false;
        }
        int dedupeRadius = MiningAssistRuntime.config().poi().dedupeRadius();
        for (Entry entry : entries) {
            if (!entry.dimensionKey().equals(dimensionKey)) {
                continue;
            }
            switch (entry.state()) {
                case CONSULTING, STOPPED -> {
                    if (withinRadius(entry.anchor(), anchor, dedupeRadius)) {
                        return true;
                    }
                }
                case DECLINED -> {
                    if (withinRadius(entry.anchor(), anchor, dedupeRadius) && !reAskSatisfied(entry, currentScore, nowTick)) {
                        return true;
                    }
                }
            }
            if (entry.state() == State.STOPPED && entry.label().equals(label)
                    && withinRadius(entry.anchor(), anchor, SAME_LABEL_RADIUS_BLOCKS)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Creates or refreshes the newest entry for this bot (cap {@link #MAX_ENTRIES_PER_BOT}, evicts the stalest
     * by {@code recordedTick} when full). {@code score} is S at record time. An existing entry with the same
     * {@code (dimensionKey, anchor, label)} is refreshed in place rather than duplicated.
     */
    public static void record(UUID botId, String dimensionKey, BlockPos anchor, String label, State state, double score, int nowTick) {
        List<Entry> entries = ENTRIES.computeIfAbsent(botId, id -> new ArrayList<>());
        Entry fresh = new Entry(dimensionKey, anchor, label, state, score, nowTick);
        for (int i = 0; i < entries.size(); i++) {
            Entry existing = entries.get(i);
            if (existing.dimensionKey().equals(dimensionKey) && existing.anchor().equals(fresh.anchor())
                    && existing.label().equals(label)) {
                entries.set(i, fresh);
                return;
            }
        }
        if (entries.size() >= MAX_ENTRIES_PER_BOT) {
            evictStalest(entries);
        }
        entries.add(fresh);
    }

    /** Live entries of one bot, newest first (tests / diagnostics only). A new list; never null. */
    public static List<Entry> snapshot(UUID botId) {
        List<Entry> entries = ENTRIES.get(botId);
        if (entries == null || entries.isEmpty()) {
            return new ArrayList<>();
        }
        List<Entry> copy = new ArrayList<>(entries);
        copy.sort(Comparator.comparingInt(Entry::recordedTick).reversed());
        return copy;
    }

    /** Advances and returns this bot's BotMemory ring slot, 1..{@link #RING_SIZE}, wrapping. First call for a
     * bot returns 1. */
    public static int nextRingSlot(UUID botId) {
        return RING.merge(botId, 1, (oldSlot, ignored) -> oldSlot % RING_SIZE + 1);
    }

    public static OpenCase openCase(UUID botId) {
        return OPEN_CASES.get(botId);
    }

    public static void openCase(UUID botId, OpenCase openCase) {
        OPEN_CASES.put(botId, Objects.requireNonNull(openCase, "openCase"));
    }

    public static void closeCase(UUID botId) {
        OPEN_CASES.remove(botId);
        RESTART_NOTICE_SENT.remove(botId);
    }

    /** Whether the "Still paused:" restart notice has already been (re-)sent once this process lifetime for
     * this bot's currently open case. */
    public static boolean restartNoticeSent(UUID botId) {
        return RESTART_NOTICE_SENT.contains(botId);
    }

    public static void markRestartNoticeSent(UUID botId) {
        RESTART_NOTICE_SENT.add(botId);
    }

    /** Bot unload only (see mining-assist design 6.4's {@code MiningAssistRuntime.clearBotUnload} caller —
     * never call this from the idle-release path). */
    public static void clear(UUID botId) {
        ENTRIES.remove(botId);
        RING.remove(botId);
        OPEN_CASES.remove(botId);
        RESTART_NOTICE_SENT.remove(botId);
    }

    /** World unload. */
    public static void clearAll() {
        ENTRIES.clear();
        RING.clear();
        OPEN_CASES.clear();
        RESTART_NOTICE_SENT.clear();
    }

    /** The DECLINED re-ask rule: at least {@link #RE_ASK_MIN_TICKS} ticks old, and the current score has grown
     * by at least {@link #RE_ASK_SCORE_GROWTH} over the score recorded with the entry. */
    private static boolean reAskSatisfied(Entry entry, double currentScore, int nowTick) {
        long age = (long) nowTick - entry.recordedTick();
        return age >= RE_ASK_MIN_TICKS && (currentScore - entry.score()) >= RE_ASK_SCORE_GROWTH;
    }

    /** Drops every entry of {@code entries} expired at {@code nowTick}: STOPPED older than
     * {@link #STOPPED_TTL_TICKS}, DECLINED older than {@link #DECLINED_TTL_TICKS}. CONSULTING never expires
     * here (P3 owns its lifetime). */
    private static void pruneExpired(List<Entry> entries, int nowTick) {
        entries.removeIf(entry -> switch (entry.state()) {
            case STOPPED -> (long) nowTick - entry.recordedTick() > STOPPED_TTL_TICKS;
            case DECLINED -> (long) nowTick - entry.recordedTick() > DECLINED_TTL_TICKS;
            case CONSULTING -> false;
        });
    }

    /** Removes the entry with the smallest {@code recordedTick} (ties: the earliest in list order). */
    private static void evictStalest(List<Entry> entries) {
        int stalestIndex = 0;
        int stalestTick = entries.get(0).recordedTick();
        for (int i = 1; i < entries.size(); i++) {
            if (entries.get(i).recordedTick() < stalestTick) {
                stalestTick = entries.get(i).recordedTick();
                stalestIndex = i;
            }
        }
        entries.remove(stalestIndex);
    }

    /** True when {@code a} and {@code b} are within {@code radiusBlocks} of each other, squared Euclidean 3D. */
    private static boolean withinRadius(BlockPos a, BlockPos b, int radiusBlocks) {
        long dx = (long) a.getX() - b.getX();
        long dy = (long) a.getY() - b.getY();
        long dz = (long) a.getZ() - b.getZ();
        long distSq = dx * dx + dy * dy + dz * dz;
        long r = radiusBlocks;
        return distSq <= r * r;
    }
}
