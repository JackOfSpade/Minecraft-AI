package io.github.zoyluo.aibot.mining.assist;

import java.util.UUID;

/**
 * Per-mission bookkeeping of the detour (mining-assist design 2.4, 4.3, 4.10): how many detours a mission has
 * started, how long they took, how often routing failed, and whether detours are switched off for the rest of the
 * mission. Static and server wide, keyed by {@link #keyFor(UUID, UUID, UUID)} = (bot, mission), falling back to
 * {@code adhoc:<botUuid>} when the active origin carries no mission or job id. An entry expires
 * {@value #TTL_TICKS} server ticks after it was last touched. Cleared in {@code MiningAssistRuntime.clearWorldRuntime}.
 *
 * <p>The ledger survives OreDig replans of the same mission on purpose: "at most 24 detours per mission" and
 * "detours disabled after a failed return" are mission properties, not task-instance ones.</p>
 *
 * <p>All times are <b>server ticks</b>, except {@code detourTicks}, which counts <b>task ticks</b> spent inside
 * detours (it is compared with {@code min(3600, maxElapsed / 6)} and OreDig's {@code maxElapsed} is in task
 * ticks). Server thread only.</p>
 *
 * <h2>Start rate limits (design 4.3), {@link Entry#startVerdict}</h2>
 * First failing verdict wins, in this order:
 * <ol>
 *   <li>DISABLED: {@code detoursDisabled} (set after a return that had to rebase the cursor).</li>
 *   <li>HAZARD_COOLDOWN: the server tick is before the 600-tick hazard cooldown that {@code fluid_unsealable}
 *       starts.</li>
 *   <li>INTERVAL: fewer than {@link #intervalTicks} server ticks since the last detour ended, where
 *       {@code intervalTicks = min(MIN_INTERVAL_CAP_TICKS (3200), detour.minIntervalTicks * 2^consecutiveRouteFailures)}
 *       and the exponent is capped at 10. Never ended: no interval.</li>
 *   <li>MAX_PER_MISSION: {@code detoursStarted >= detour.maxPerMission}.</li>
 *   <li>TICK_BUDGET: {@code detourTicks >= min(3600, maxElapsedTicks / 6)}.</li>
 * </ol>
 * Route failures also feed {@link Entry#zeroTransitOnly}: after {@value #MISSION_ROUTE_FAILURE_LIMIT} failures in
 * one mission only zero-transit grabs (no route at all) are allowed, for {@value #ZERO_TRANSIT_ONLY_TICKS} server
 * ticks from the failure that reached or exceeded the limit; every later failure re-arms the window.
 */
public final class MissionAssistLedger {
    /** An entry is dropped this many server ticks after its last use. */
    public static final int TTL_TICKS = 72_000;
    /** Global hazard cooldown after {@code fluid_unsealable}, in server ticks. */
    public static final int HAZARD_COOLDOWN_TICKS = 600;
    /** Design 4.3: route failures per mission before only zero-transit grabs are allowed. */
    public static final int MISSION_ROUTE_FAILURE_LIMIT = 6;
    /** Design 4.3: length of the zero-transit-only window, in server ticks. */
    public static final int ZERO_TRANSIT_ONLY_TICKS = 6000;
    /** Design 4.3: cap of {@code minInterval * 2^failures}. */
    public static final int INTERVAL_CAP_TICKS = 3200;
    /** Design 4.3: cap of the total detour ticks per mission. */
    public static final int TICK_BUDGET_CAP = 3600;
    /** Design 4.13: least server ticks between two rare-find chat lines of one bot. */
    public static final int ANNOUNCE_INTERVAL_TICKS = 600;
    /**
     * Sentinel for "never" ({@code Integer.MIN_VALUE}: a plain {@code tick - x} subtraction overflows for it). Every
     * comparison against a NEVER-capable field is explicit: {@code x == NEVER} first, then a {@code long}
     * subtraction ({@code MiningAssistState.staleOrNever} is the shared predicate). A mission that never ended a
     * detour must not be held back by the interval rule, and a hazard cooldown that was never armed must not read as
     * active.
     */
    public static final int NEVER = Integer.MIN_VALUE;

    /** Verdict of {@link Entry#startVerdict}; see the class comment for the order. */
    public enum StartVerdict {
        OK,
        DISABLED,
        HAZARD_COOLDOWN,
        INTERVAL,
        MAX_PER_MISSION,
        TICK_BUDGET
    }

    private MissionAssistLedger() {
    }

    /**
     * The ledger key: {@code "m:" + missionId + "@" + botId} when a mission id is present, else
     * {@code "j:" + jobId + "@" + botId} when a job id is, else {@code "adhoc:" + botId}. The first non-null of
     * (missionId, jobId) wins; {@code botId} must not be null.
     */
    public static String keyFor(UUID botId, UUID missionId, UUID jobId) {
        throw new UnsupportedOperationException("P1 stub: MissionAssistLedger.keyFor");
    }

    /**
     * The record for {@code key}, created empty on first use, touched with {@code nowTick}. Expired entries (idle
     * for more than {@link #TTL_TICKS}) are pruned lazily, at most once per 1200 ticks, and an entry that is
     * itself expired when asked for is replaced by a fresh one.
     */
    public static Entry get(String key, int nowTick) {
        throw new UnsupportedOperationException("P1 stub: MissionAssistLedger.get");
    }

    /** Drops every record (world unload). */
    public static void clearAll() {
        throw new UnsupportedOperationException("P1 stub: MissionAssistLedger.clearAll");
    }

    /** Drops the records of one bot (all keys ending in {@code "@" + botId} and {@code "adhoc:" + botId}). */
    public static void clearBot(UUID botId) {
        throw new UnsupportedOperationException("P1 stub: MissionAssistLedger.clearBot");
    }

    /** Number of stored records (diagnostics and tests). */
    public static int size() {
        throw new UnsupportedOperationException("P1 stub: MissionAssistLedger.size");
    }

    /**
     * {@code min(INTERVAL_CAP_TICKS, minIntervalTicks * 2^min(failures, 10))}, computed in long so it cannot
     * overflow. Failures below 0 count as 0.
     */
    public static int intervalTicks(int minIntervalTicks, int consecutiveRouteFailures) {
        throw new UnsupportedOperationException("P1 stub: MissionAssistLedger.intervalTicks");
    }

    /** {@code min(TICK_BUDGET_CAP, maxElapsedTicks / 6)}; a non-positive {@code maxElapsedTicks} gives 0. */
    public static int detourTickBudget(int maxElapsedTicks) {
        throw new UnsupportedOperationException("P1 stub: MissionAssistLedger.detourTickBudget");
    }

    /** The mutable counters of one mission. Server thread only. */
    public static final class Entry {
        private int detoursStarted;
        private int detourTicks;
        private int consecutiveRouteFailures;
        private int missionRouteFailures;
        private int lastEndTick = NEVER;
        private int hazardCooldownUntil = NEVER;
        private int zeroTransitUntil = NEVER;
        private int lastAnnounceTick = NEVER;
        private boolean detoursDisabled;
        private int lastTouchedTick = NEVER;

        /** Public so test hosts can hold a private, unshared entry; production code uses {@link MissionAssistLedger#get}. */
        public Entry() {
        }

        /** The design's {@code ledger.detoursDisabled}. */
        public boolean detoursDisabled() {
            return detoursDisabled;
        }

        /** Switches detours off for the rest of the mission (set after a return that rebased the cursor). */
        public void disableDetours() {
            throw new UnsupportedOperationException("P1 stub: MissionAssistLedger.Entry.disableDetours");
        }

        /**
         * The start verdict at {@code serverTick} (see the class comment for the order).
         *
         * @param maxElapsedTicks OreDig's {@code maxElapsed} in task ticks
         */
        public StartVerdict startVerdict(int serverTick, MiningAssistConfig.Detour cfg, int maxElapsedTicks) {
            throw new UnsupportedOperationException("P1 stub: MissionAssistLedger.Entry.startVerdict");
        }

        /** A detour started: {@code detoursStarted++}. */
        public void noteStart(int serverTick) {
            throw new UnsupportedOperationException("P1 stub: MissionAssistLedger.Entry.noteStart");
        }

        /**
         * A detour ended at {@code serverTick}: sets {@code lastEndTick}, adds {@code detourTicks} (task ticks
         * spent), and when {@code completed} resets {@code consecutiveRouteFailures} to 0. {@code completed} is
         * true for a detour that reached its FINISH without an abort reason.
         */
        public void noteEnd(int serverTick, boolean completed, int detourTicksSpent) {
            throw new UnsupportedOperationException("P1 stub: MissionAssistLedger.Entry.noteEnd");
        }

        /**
         * A route or contract failure: {@code consecutiveRouteFailures++}, {@code missionRouteFailures++}, and once
         * {@code missionRouteFailures >= 6} the zero-transit-only window is (re)armed to
         * {@code serverTick + 6000}.
         */
        public void noteRouteFailure(int serverTick) {
            throw new UnsupportedOperationException("P1 stub: MissionAssistLedger.Entry.noteRouteFailure");
        }

        /** {@code fluid_unsealable}: no start until {@code serverTick + 600}. */
        public void noteHazard(int serverTick) {
            throw new UnsupportedOperationException("P1 stub: MissionAssistLedger.Entry.noteHazard");
        }

        /** True while only zero-transit (arm's length, no route) detours may start. */
        public boolean zeroTransitOnly(int serverTick) {
            throw new UnsupportedOperationException("P1 stub: MissionAssistLedger.Entry.zeroTransitOnly");
        }

        /** True when {@code serverTick - lastAnnounceTick >= 600} or no line was ever sent. */
        public boolean announceAllowed(int serverTick) {
            throw new UnsupportedOperationException("P1 stub: MissionAssistLedger.Entry.announceAllowed");
        }

        public void noteAnnounced(int serverTick) {
            throw new UnsupportedOperationException("P1 stub: MissionAssistLedger.Entry.noteAnnounced");
        }

        public int detoursStarted() {
            return detoursStarted;
        }

        public int detourTicks() {
            return detourTicks;
        }

        public int consecutiveRouteFailures() {
            return consecutiveRouteFailures;
        }

        public int missionRouteFailures() {
            return missionRouteFailures;
        }

        public int lastEndTick() {
            return lastEndTick;
        }

        public int hazardCooldownUntil() {
            return hazardCooldownUntil;
        }

        public int lastTouchedTick() {
            return lastTouchedTick;
        }
    }
}
