package io.github.zoyluo.minecraftai.mining.assist;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * R4 advisor consult budget and circuit breaker (mining-assist design 6.6): "At most 6 consults per mission
 * (at most 2 cavern-only), at least 400 ticks between consults per bot, at most 2 in flight globally. The
 * circuit breaker opens after 3 consecutive failures for 6000 ticks." Deliberately separate from
 * {@link MissionAssistLedger} (design's own deviation note: "Budgets are separate from
 * PlayerInstructionCallBudget"; this class exists because the design's own budget mixes per-mission counts
 * with per-bot and process-global state that {@code MissionAssistLedger}'s pure per-mission-key TTL idiom
 * does not model). Static, server wide, server thread only, mirroring {@link MissionAssistLedger}'s TTL-prune
 * idiom for the per-mission half.
 */
public final class PoiConsultBudget {
    public static final int TTL_TICKS = MissionAssistLedger.TTL_TICKS;
    public static final int MAX_CAVERN_ONLY_PER_MISSION = 2;
    public static final int MAX_IN_FLIGHT_GLOBAL = 2;
    private static final int NEVER = Integer.MIN_VALUE;

    private static final Map<String, Entry> MISSION_ENTRIES = new HashMap<>();
    private static int lastPruneTick = NEVER;
    private static final Map<UUID, Integer> lastConsultTickByBot = new HashMap<>();
    private static int inFlight;
    private static int consecutiveFailures;
    private static int breakerOpenUntilTick = NEVER;

    private PoiConsultBudget() {
    }

    /** Same key shape as {@link MissionAssistLedger#keyFor}: this budget is mission-scoped for its per-mission
     * half, bot-scoped and process-global for the rest. */
    public static String keyFor(UUID botId, UUID missionId, UUID jobId) {
        return MissionAssistLedger.keyFor(botId, missionId, jobId);
    }

    /**
     * True when a new consult may be started right now for {@code (key, botId)}: the breaker is closed, a
     * global in-flight slot is free, at least {@code cfg.minIntervalTicks()} ticks have passed since this
     * bot's last consult, the mission has not used its {@code cfg.maxConsultsPerMission()} budget, and -- when
     * {@code cavernOnly} -- the mission has not used its {@value #MAX_CAVERN_ONLY_PER_MISSION} cavern-only
     * share of it. Read-only: call {@link #reserve} only after this returns true.
     */
    public static boolean canConsult(String key, UUID botId, boolean cavernOnly, int nowTick,
                                     MiningAssistConfig.Advisor cfg) {
        if (breakerOpen(nowTick)) {
            return false;
        }
        if (inFlight >= MAX_IN_FLIGHT_GLOBAL) {
            return false;
        }
        Integer last = lastConsultTickByBot.get(botId);
        if (last != null && (long) nowTick - (long) last < cfg.minIntervalTicks()) {
            return false;
        }
        Entry e = peek(key, nowTick);
        if (e != null) {
            if (e.consultsUsed >= cfg.maxConsultsPerMission()) {
                return false;
            }
            if (cavernOnly && e.cavernOnlyConsultsUsed >= MAX_CAVERN_ONLY_PER_MISSION) {
                return false;
            }
        }
        return true;
    }

    /** Reserves budget for a consult about to start: bumps the mission counters, this bot's last-consult
     * tick, and the global in-flight count. Call exactly once, only after {@link #canConsult} returned true,
     * and pair with exactly one {@link #release}. */
    public static void reserve(String key, UUID botId, boolean cavernOnly, int nowTick) {
        Entry e = entry(key, nowTick);
        e.consultsUsed++;
        if (cavernOnly) {
            e.cavernOnlyConsultsUsed++;
        }
        lastConsultTickByBot.put(botId, nowTick);
        inFlight++;
    }

    /** Releases the in-flight slot a prior {@link #reserve} took: call exactly once per reserve, on the
     * consult's completion, failure, or timeout. */
    public static void release() {
        if (inFlight > 0) {
            inFlight--;
        }
    }

    /** A consult resolved (a valid verdict was parsed, cache hit does not count): resets the breaker's
     * failure streak. */
    public static void recordSuccess() {
        consecutiveFailures = 0;
    }

    /** A consult failed (network, parse, or 10s wall-clock timeout): bumps the failure streak and opens the
     * breaker for {@code cfg.breakerOpenTicks()} once it reaches {@code cfg.breakerFailures()}. */
    public static void recordFailure(int nowTick, MiningAssistConfig.Advisor cfg) {
        consecutiveFailures++;
        if (consecutiveFailures >= cfg.breakerFailures()) {
            breakerOpenUntilTick = nowTick + cfg.breakerOpenTicks();
        }
    }

    public static boolean breakerOpen(int nowTick) {
        return breakerOpenUntilTick != NEVER && nowTick < breakerOpenUntilTick;
    }

    public static int inFlight() {
        return inFlight;
    }

    public static int consecutiveFailures() {
        return consecutiveFailures;
    }

    /** The record for {@code key} if one exists and has not expired; never creates one (used by the read-only
     * {@link #canConsult}). */
    private static Entry peek(String key, int nowTick) {
        maybePrune(nowTick);
        Entry e = MISSION_ENTRIES.get(key);
        if (e == null || MiningAssistState.staleOrNever(nowTick, e.lastTouchedTick, TTL_TICKS)) {
            return null;
        }
        return e;
    }

    /** The record for {@code key}, created empty on first use or after expiry, touched with {@code nowTick}. */
    private static Entry entry(String key, int nowTick) {
        maybePrune(nowTick);
        Entry e = MISSION_ENTRIES.get(key);
        if (e == null || MiningAssistState.staleOrNever(nowTick, e.lastTouchedTick, TTL_TICKS)) {
            e = new Entry();
            MISSION_ENTRIES.put(key, e);
        }
        e.lastTouchedTick = nowTick;
        return e;
    }

    private static void maybePrune(int nowTick) {
        if (lastPruneTick != NEVER && (long) nowTick - (long) lastPruneTick < 1200) {
            return;
        }
        lastPruneTick = nowTick;
        MISSION_ENTRIES.entrySet().removeIf(en -> MiningAssistState.staleOrNever(nowTick, en.getValue().lastTouchedTick, TTL_TICKS));
    }

    /** Number of stored mission records (diagnostics and tests). */
    public static int size() {
        return MISSION_ENTRIES.size();
    }

    /** World unload: drops every mission record, per-bot spacing, in-flight count and breaker state. */
    public static void clearAll() {
        MISSION_ENTRIES.clear();
        lastPruneTick = NEVER;
        lastConsultTickByBot.clear();
        inFlight = 0;
        consecutiveFailures = 0;
        breakerOpenUntilTick = NEVER;
    }

    /** The mutable per-mission counters. Server thread only. */
    private static final class Entry {
        private int consultsUsed;
        private int cavernOnlyConsultsUsed;
        private int lastTouchedTick = NEVER;
    }
}
