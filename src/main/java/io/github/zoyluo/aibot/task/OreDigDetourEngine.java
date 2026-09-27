package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.mining.assist.DetourPhase;
import net.minecraft.util.math.BlockPos;

/**
 * The opportunistic valuables detour of OreDig (mining-assist design 4.1 to 4.14, phase P1, walk only): a small
 * deterministic state machine that {@code OreDigTask.tickOpportunistic} ticks once per task tick, before the
 * vein, bonus, target and scan ladder. It knows nothing of Minecraft: it talks to a {@link DetourHost} and reads
 * time from it, so a scripted fake can drive every path. One instance per OreDigTask instance.
 *
 * <h2>Phases</h2>
 * {@code IDLE -> APPROACH -> MINE -> POSTBREAK -> SETTLE_DROP -> NEXT -> RETURN -> FINISH -> IDLE}, see
 * {@link DetourPhase}. NEXT and FINISH are resolved inside the tick that reaches them. An abort from any phase
 * but RETURN records the reason, cancels the miner, stops movement and enters RETURN (the first RETURN step runs
 * on the NEXT tick); RETURN is the only way out (there is no dig fallback and no handoff to
 * {@code returnToSavedFace}, design 4.10).
 *
 * <h2>Invariants</h2>
 * <ul>
 *   <li><b>I8</b>: never calls anything that fails the mission; every failure is a typed abort followed by a
 *       return, or a member skip.</li>
 *   <li><b>I14</b>: every phase has a hard tick cap and the engine calls {@link DetourHost#noteProgress()} (a
 *       "beat") at the points listed in the P1 contract, so the largest gap between two beats is at most
 *       {@value #MAX_HEARTBEAT_GAP_TICKS} task ticks, below OreDig's {@code NO_PROGRESS_LIMIT} of 200. A beat
 *       also happens at every phase transition. While RETURN starts from a cell that is not standable, a keepalive
 *       beat every {@value #RETURN_UNSTANDABLE_RETRY_TICKS} ticks whatever the route answers, so
 *       {@value #RETURN_TOTAL_TICKS} (the cap) is the only bound there. {@code EngineDeadlinesTest} enforces the
 *       bound with a recording fake host.</li>
 *   <li><b>I7</b>: the SAFE gate is asked live: {@code TICK_FAST} on odd and {@code TICK_FULL} on even ticks
 *       since the start (both skipped in RETURN), and once more with {@code TICK_FULL} before every break, every
 *       new route leg and every drop chase. {@code START} belongs to the selector only.</li>
 *   <li><b>Books</b>: the mission ledger entry is taken once, at {@link #start}, and cached; every later effect
 *       (interrupt, finish, rebase, abandon) goes to that cached entry, never to a fresh {@code host.ledger()}.</li>
 *   <li><b>Sentinels</b>: engine timestamps that mean "never" are separate booleans or counters
 *       ({@code routeAttempts == 0}), never {@code Integer.MIN_VALUE} in a subtraction (overflow).</li>
 *   <li>Determinism: no clock, no randomness, no iteration over hash order (members are ordered nearest first
 *       from the feet, ties by BlockPos order).</li>
 * </ul>
 *
 * <p>The full algorithm, deadline table and failure matrix are in the P1 contract, sections C and E. The tick
 * counts below are task ticks unless a name says otherwise.</p>
 */
final class OreDigDetourEngine {
    // ---- deadlines (task ticks) and caps -------------------------------------------------------------------
    /** No two beats may be further apart than this (I14). The engine's own limits keep the gap at 101 or less. */
    static final int MAX_HEARTBEAT_GAP_TICKS = 110;
    /** A beat is due when the distance to the phase goal improved by this many blocks since the last beat. */
    static final double PROGRESS_BLOCKS = 1.0D;
    /** APPROACH and RETURN: abort or rebase when no beat happened for more than this many ticks. */
    static final int APPROACH_STALL_TICKS = 100;
    static final int RETURN_STALL_TICKS = 100;
    /** APPROACH: hard cap from phase entry; RETURN: hard cap from entry (then rebase). */
    static final int APPROACH_TOTAL_TICKS = 160;
    static final int RETURN_TOTAL_TICKS = 400;
    /** APPROACH and chase: consecutive ticks a start is refused (THROTTLED or BUDGET) before abort {@code budget}. */
    static final int ROUTE_WAIT_MAX_TICKS = 40;
    /** MINE: cap of the preparation (re-proof, gates, waiting for idle movement) and of the swing, each from its own start. */
    static final int MINE_PREP_TICKS = 60;
    static final int MINE_SWING_TICKS = 160;
    /** MINE: a beat every this many ticks while the miner reports MINING. */
    static final int MINE_BEAT_TICKS = 50;
    /** MINE: an UNKNOWN re-proof is retried for this many ticks, then the member is skipped. */
    static final int REPROOF_UNKNOWN_TICKS = 20;
    /** MINE: a member may be re-posed (MINE -> APPROACH) this many times; the next one skips it as {@code pose_thrash}. */
    static final int MAX_REPOSES_PER_MEMBER = 1;
    /** POSTBREAK: cap from entry (seals included). */
    static final int POSTBREAK_TOTAL_TICKS = 20;
    /** SETTLE_DROP: least ticks before a settle counts, tick at which "no visible drop" settles, and the cap. */
    static final int SETTLE_MIN_TICKS = 5;
    static final int SETTLE_NO_DROP_TICKS = 10;
    static final int SETTLE_TOTAL_TICKS = 60;
    /** Drop chase: route attempts per drop. */
    static final int CHASE_ATTEMPTS = 2;
    /** Route attempts per approach and per return, and the least ticks between two attempts. */
    static final int ROUTE_ATTEMPTS = 3;
    static final int ROUTE_ATTEMPT_GAP_TICKS = 20;
    /** RETURN from a cell that is not standable: retry the route (and beat, as a keepalive) every this many ticks. */
    static final int RETURN_UNSTANDABLE_RETRY_TICKS = 40;
    /** RETURN: after a THROTTLED or BUDGET answer, try again after this many ticks (the answer is a beat, not an attempt). */
    static final int RETURN_WAIT_RETRY_TICKS = 5;
    /**
     * The engine was not ticked for more than this many task ticks (an early return of OreDig before hook 9: water
     * rescue, blocked-body recovery, a tool gate): outside RETURN it aborts {@code tick_gap}; in RETURN it beats and
     * goes on. Task ticks do not advance while the task is skipped under degraded TPS, so this is never a TPS artefact.
     */
    static final int TICK_GAP_ABORT_TICKS = 3;

    // ---- per detour caps -------------------------------------------------------------------------------------
    /**
     * Members (seed included) started per detour, fluid seals, claims taken at the start. There is no separate
     * break cap: breaks never exceed members started, so a "16 breaks" cap of the design was unreachable.
     */
    static final int MEMBER_CAP = 12;
    static final int SEAL_CAP = 3;
    static final int START_CLAIMS = 3;

    // ---- exclusions (server ticks) -----------------------------------------------------------------------------
    /** One attempt per candidate per this many ticks (applied to the seed at the start) and for a skipped or timed out member. */
    static final int ATTEMPT_EXCLUDE_TICKS = 600;
    /** A route or contract failure, fluid_unsealable and tool aborts exclude the whole cluster for this long. */
    static final int FAILURE_EXCLUDE_TICKS = 1200;

    // ---- observability -------------------------------------------------------------------------------------------
    /** {@code BotProfiler} section of one active engine tick, and of one selector run (observability only, cost gate). */
    static final String SECTION_DETOUR = "assist_detour";
    static final String SECTION_DETOUR_SELECT = "assist_detour_select";

    /** Kind of a {@link Result}. */
    enum Kind {
        /** The engine is idle and did not consume the tick: OreDig runs its ladder as usual. */
        IDLE,
        /** The engine owns this tick: OreDig returns from its tick. */
        CONSUMED,
        /** The detour ended in this tick (claims released, anchor numbers restored or the cursor rebased). The tick is consumed. */
        FINISHED
    }

    /** Result of {@link #tick}: {@code reason} is set only for FINISHED ({@code done}, {@code caps}, an abort reason, or {@code return_rebased}). */
    record Result(Kind kind, String reason) {
        static final Result IDLE = new Result(Kind.IDLE, "");
        static final Result CONSUMED = new Result(Kind.CONSUMED, "");

        static Result finished(String reason) {
            return new Result(Kind.FINISHED, reason == null ? "" : reason);
        }
    }

    OreDigDetourEngine() {
    }

    // ---- driving --------------------------------------------------------------------------------------------------

    /**
     * One task tick. IDLE: when {@code DetourStartSelector.checkDue(host.now(), host.staggerSeed())} run
     * {@link DetourStartSelector#select}; a selection is started with {@link #start} (which consumes the tick),
     * otherwise the result is IDLE. Active: detect a tick gap ({@link #TICK_GAP_ABORT_TICKS}), consume any pending
     * net abort, then lease, SAFE gate, claim renewal (every 40 server ticks), then the phase step. An abort raised
     * before the phase step ends the tick CONSUMED (the first RETURN step is on the next tick). Always CONSUMED
     * except in the tick that finishes.
     */
    Result tick(DetourHost host) {
        throw new UnsupportedOperationException("P1 stub: OreDigDetourEngine.tick");
    }

    /**
     * Starts the detour for a selection (design 4.3 "start actions"): capture the anchor, clear strip ownership,
     * {@code stopAll()}, one beat, take and CACHE the mission ledger entry and note the start in it, exclude the
     * seed for 600 ticks, take the vein ({@code host.veinAt(seed, id, MEMBER_CAP)}), order it nearest first, claim
     * the seed's 3 nearest vein members (a member claimed by another bot is dropped), feed the knowledge base, send
     * the rare-find line when allowed, log {@code ore_dig_detour_start}, and enter MINE (zero transit pose) or
     * APPROACH. Resets the counters of the previous detour. Package-private so a test can start a detour with a
     * hand built selection. The engine must be IDLE.
     */
    void start(DetourHost host, DetourStartSelector.Selection selection) {
        throw new UnsupportedOperationException("P1 stub: OreDigDetourEngine.start");
    }

    /**
     * Asks the engine to abort with {@code reason} at its next {@link #tick}: used by the coordinator's net
     * (through {@code DetourControl}), by {@code OreDigTask.detourClaimLava} and by the state-lost check of
     * {@code tickOpportunistic}. Ignored while idle or in RETURN. The first reason wins.
     */
    void requestAbort(String reason) {
        throw new UnsupportedOperationException("P1 stub: OreDigDetourEngine.requestAbort");
    }

    /**
     * The task was paused, aborted or ended by someone else (design 4.10): forget the detour without a return.
     * Releases the claims through the host, idempotently writes the anchor's strip numbers back
     * ({@code host.restoreAnchorNumbers}, a difference is only logged), notes the end in the CACHED mission ledger
     * entry (not completed), logs {@code ore_dig_detour_abort} with {@code reason} and goes IDLE. It does not stop
     * movement or mining (the caller's {@code onPause} and {@code onAbort} do). Returns the anchor that was active,
     * or null when idle, so the caller can set {@code lastFace = anchor.face()}.
     */
    DetourHost.Anchor interrupt(DetourHost host, String reason) {
        throw new UnsupportedOperationException("P1 stub: OreDigDetourEngine.interrupt");
    }

    /**
     * The coordinator found the owning task gone (orphan cleanup, cause {@code owner_changed} or
     * {@code not_running}): note the end in the cached ledger entry (not completed, ticks so far), release nothing
     * (the coordinator released the claims), touch neither the action pack nor the world, and go IDLE. Idempotent;
     * a no-op when idle. Reached through {@code DetourControl.abandoned}.
     */
    void abandon(DetourHost host) {
        throw new UnsupportedOperationException("P1 stub: OreDigDetourEngine.abandon");
    }

    // ---- introspection --------------------------------------------------------------------------------------------

    /** True from a start until the tick that finishes it. Drives {@code assistDetourActive()} in OreDig. */
    boolean isActive() {
        throw new UnsupportedOperationException("P1 stub: OreDigDetourEngine.isActive");
    }

    DetourPhase phase() {
        throw new UnsupportedOperationException("P1 stub: OreDigDetourEngine.phase");
    }

    /** The anchor of the running detour, or null when idle. */
    DetourHost.Anchor anchor() {
        throw new UnsupportedOperationException("P1 stub: OreDigDetourEngine.anchor");
    }

    /** {@code anchor().face()} while active, else null: what {@code detourPublishedFace} substitutes into the checkpoint. */
    BlockPos anchorFace() {
        throw new UnsupportedOperationException("P1 stub: OreDigDetourEngine.anchorFace");
    }

    /** The member being approached or mined, or null. */
    BlockPos currentOre() {
        throw new UnsupportedOperationException("P1 stub: OreDigDetourEngine.currentOre");
    }

    /** The abort reason of the running detour, or null when it has none (a normal end has none). */
    String abortReason() {
        throw new UnsupportedOperationException("P1 stub: OreDigDetourEngine.abortReason");
    }

    /**
     * Counters of the running detour, kept after it finished until the next {@link #start} (so the code that
     * handles a FINISHED result can still read them for the end log line and the window counters).
     */
    int breaks() {
        throw new UnsupportedOperationException("P1 stub: OreDigDetourEngine.breaks");
    }

    int membersStarted() {
        throw new UnsupportedOperationException("P1 stub: OreDigDetourEngine.membersStarted");
    }

    int seals() {
        throw new UnsupportedOperationException("P1 stub: OreDigDetourEngine.seals");
    }

    int dropsLost() {
        throw new UnsupportedOperationException("P1 stub: OreDigDetourEngine.dropsLost");
    }
}
