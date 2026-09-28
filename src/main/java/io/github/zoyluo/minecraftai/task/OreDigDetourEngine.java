package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.mining.assist.DetourPhase;
import io.github.zoyluo.aibot.mining.assist.DetourPolicy;
import io.github.zoyluo.aibot.mining.assist.MissionAssistLedger;
import io.github.zoyluo.aibot.mining.assist.SafeGate;
import io.github.zoyluo.aibot.mining.assist.SafeReason;
import io.github.zoyluo.aibot.mining.assist.SightingLedger;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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

    // ---- kind FRONTIER (design 5.4) -------------------------------------------------------------------------
    /** Fixed lease for a cave-frontier excursion (design 5.4); unlike ORE it does not scale with breaks. */
    static final int FRONTIER_LEASE_TICKS = 900;
    /** Hard cap on waypoint hops per excursion (design 5.4, "at most 3 hops"; see contract §1.1). */
    static final int FRONTIER_MAX_WAYPOINTS = 3;
    /** New sightings recorded during the excursion at or above which it counts as "productive" (design 5.4). */
    static final int FRONTIER_PRODUCTIVE_SIGHTINGS = 2;
    /** Per-leg stall/total caps, same numbers as APPROACH (design 5.4 gives no separate figures). */
    static final int FRONTIER_LEG_STALL_TICKS = APPROACH_STALL_TICKS;
    static final int FRONTIER_LEG_TOTAL_TICKS = APPROACH_TOTAL_TICKS;

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

    /** Which detour this instance is currently running (design 5.4): distinguishes finish/abort bookkeeping only -- phase dispatch alone already separates ORE and FRONTIER execution. */
    private enum ExcursionKind { ORE, FRONTIER }

    /** A cave-frontier excursion to start (design 5.4): the observed route to the accepted candidate, already cut into waypoints by the caller. Package-private so OreDigTask.explorationTick can build one directly; no separate "FrontierStartSelector" exists because the candidate search is not a per-tick IDLE check like DetourStartSelector -- it runs once, when OreDigTask decides to try. */
    record FrontierSelection(List<BlockPos> waypoints) {
        FrontierSelection {
            Objects.requireNonNull(waypoints, "waypoints");
            if (waypoints.isEmpty() || waypoints.size() > FRONTIER_MAX_WAYPOINTS) {
                throw new IllegalArgumentException("waypoints must be 1.." + FRONTIER_MAX_WAYPOINTS + ", got " + waypoints.size());
            }
        }
    }

    /** The one open drop debt of the running detour (design 4.9): the break cell, the inventory baseline it is measured against, and when it started. */
    private static final class DropDebt {
        final BlockPos cell;
        int baseline;
        final int startedTick;

        DropDebt(BlockPos cell, int baseline, int startedTick) {
            this.cell = cell;
            this.baseline = baseline;
            this.startedTick = startedTick;
        }
    }

    // ---- state (see the P1 contract, section C.3, "fields the engine needs") ----------------------------------
    private DetourPhase phase = DetourPhase.IDLE;
    private DetourHost.Anchor anchor;
    private BlockPos seed;
    private String curId;
    private List<BlockPos> clusterCells = List.of();
    private BlockPos cur;
    private DetourHost.Pose pose;
    private final List<BlockPos> pending = new ArrayList<>();
    private final Set<BlockPos> done = new HashSet<>();
    private final Set<BlockPos> noStep = new HashSet<>();

    private ExcursionKind kind = ExcursionKind.ORE;
    private List<BlockPos> waypoints = List.of();
    private int waypointIndex;
    private Set<BlockPos> sightingPositionsAtStart = Set.of();
    /** Whether a route to the current waypoint has ever started OK this leg; see {@link #tickFrontierWalk}. */
    private boolean frontierRouteStarted;

    private int startNow;
    private int leaseDeadline;
    private int lastBeat;
    private double beatDist;
    private int phaseEnter;
    private int routeAttempts;
    private int lastRouteAttempt;
    private int waitSince = -1;
    private int returnRetryAt;
    private int lastTickNow;
    private int lastClaimRenew;

    private int breaks;
    private int membersStarted;
    private int seals;
    private int dropsLost;
    /** Whether the last FINISHED excursion was of kind FRONTIER and ended unproductive (design 5.4's cooldown gate). Deliberately NOT reset in resetToIdle() -- tickOpportunistic reads it one statement after tick() returns FINISHED, exactly like breaks/membersStarted/seals/dropsLost above. Meaningless before any frontier excursion has run. */
    private boolean lastFrontierProductive;

    private String abortReason;
    private String pendingAbort;
    private String returnWhy;

    // per-member (MINE)
    private int mineStart;
    private int swingStart;
    private boolean swingStarted;
    private boolean prepGated;
    private int reposes;
    private int lastMineBeat;
    private int unknownSince = -1;
    private int swingBaseline;

    // POSTBREAK / SETTLE_DROP
    private BlockPos lastBreak;
    private int postStart;
    private DropDebt debt;
    private int sealsThisDebt;
    private int settleStart;
    private boolean gained;
    private int chaseAttempts;
    private int lastChase;

    // RETURN
    private int returnStart;
    private int returnAttempts;

    private MissionAssistLedger.Entry ledger;

    OreDigDetourEngine() {
    }

    // ---- driving --------------------------------------------------------------------------------------------

    /**
     * One task tick. IDLE: when {@code DetourStartSelector.checkDue(now, host.staggerSeed())} run
     * {@link DetourStartSelector#select}; a selection is started with {@link #start} (which consumes the tick),
     * otherwise the result is IDLE. Active: detect a tick gap ({@link #TICK_GAP_ABORT_TICKS}), consume any pending
     * net abort, then lease, SAFE gate, claim renewal (every 40 server ticks), then the phase step. An abort raised
     * before the phase step ends the tick CONSUMED (the first RETURN step is on the next tick). Always CONSUMED
     * except in the tick that finishes.
     */
    Result tick(DetourHost host) {
        int now = host.now();
        if (phase == DetourPhase.IDLE) {
            if (!DetourStartSelector.checkDue(now, host.staggerSeed())) {
                return Result.IDLE;
            }
            DetourStartSelector.Result r = DetourStartSelector.select(host);
            if (r.selection() == null) {
                return Result.IDLE;
            }
            start(host, r.selection());
            return Result.CONSUMED;
        }

        int gap = now - lastTickNow;
        lastTickNow = now;
        if (gap > TICK_GAP_ABORT_TICKS) {
            if (phase != DetourPhase.RETURN) {
                return abort(host, "tick_gap");
            }
            beat(host);
        }
        if (pendingAbort != null) {
            String r = pendingAbort;
            pendingAbort = null;
            if (phase != DetourPhase.RETURN) {
                return abort(host, r);
            }
        }
        if (phase != DetourPhase.RETURN) {
            if (now > leaseDeadline) {
                return abort(host, "lease");
            }
            SafeGate.Stage stage = ((now - startNow) % 2 == 0) ? SafeGate.Stage.TICK_FULL : SafeGate.Stage.TICK_FAST;
            SafeReason reason = host.safety(stage, pose == null ? null : pose.stand(), cur);
            if (reason != SafeReason.OK) {
                return abort(host, reason.abortReason());
            }
            if (host.serverTick() - lastClaimRenew >= 40) {
                host.renewClaims();
                lastClaimRenew = host.serverTick();
            }
        }

        if (phase == DetourPhase.APPROACH) {
            return tickApproach(host);
        }
        if (phase == DetourPhase.MINE) {
            return tickMine(host);
        }
        if (phase == DetourPhase.POSTBREAK) {
            return tickPostbreak(host);
        }
        if (phase == DetourPhase.SETTLE_DROP) {
            return tickSettle(host);
        }
        if (phase == DetourPhase.FRONTIER_WALK) {
            return tickFrontierWalk(host);
        }
        if (phase == DetourPhase.RETURN) {
            return tickReturn(host);
        }
        // NEXT and FINISH are always resolved inside the tick that reaches them; IDLE is handled above.
        return Result.CONSUMED;
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
        kind = ExcursionKind.ORE;
        anchor = host.captureAnchor();
        host.clearStripOwnership();
        host.stopAll();
        beat(host);

        int now = host.now();
        startNow = now;
        lastTickNow = now;
        breaks = 0;
        membersStarted = 0;
        seals = 0;
        dropsLost = 0;
        pendingAbort = null;
        abortReason = null;
        returnWhy = null;

        leaseDeadline = now + DetourPolicy.leaseTicks(host.config(), 0);
        ledger = host.ledger();
        ledger.noteStart(host.serverTick());
        lastClaimRenew = host.serverTick();

        seed = selection.seed().toImmutable();
        curId = selection.blockId();
        clusterCells = new ArrayList<>(selection.cluster());

        host.exclude(seed, ATTEMPT_EXCLUDE_TICKS);

        BlockPos feet = host.feet();
        List<BlockPos> members = new ArrayList<>();
        for (BlockPos p : host.veinAt(seed, curId, MEMBER_CAP)) {
            BlockPos pos = p.toImmutable();
            if (pos.equals(seed) || host.excluded(pos) || members.contains(pos)) {
                continue;
            }
            members.add(pos);
        }
        members.sort(Comparator.<BlockPos>comparingLong(p -> distSq(feet, p))
                .thenComparing(OreDigDetourEngine::compareBlockPos));
        if (members.size() > MEMBER_CAP - 1) {
            members = new ArrayList<>(members.subList(0, MEMBER_CAP - 1));
        }

        int claimLimit = Math.min(START_CLAIMS, members.size());
        List<BlockPos> toClaim = new ArrayList<>(members.subList(0, claimLimit));
        for (BlockPos p : toClaim) {
            if (!host.tryClaim(p)) {
                members.remove(p);
            }
        }

        pending.clear();
        done.clear();
        noStep.clear();
        pending.addAll(members);

        host.recordFind(seed, curId);
        int value = selection.value();
        if (DetourPolicy.announces(value, host.config()) && ledger.announceAllowed(host.serverTick())) {
            host.announce(curId);
            ledger.noteAnnounced(host.serverTick());
        }
        host.log("ore_dig_detour_start", "pos", seed, "block", curId, "value", value);

        membersStarted = 1;
        cur = seed;
        pose = selection.pose();
        reposes = 0;

        if (pose.zeroTransit()) {
            enterMine(host);
        } else {
            enterApproach(host);
        }
    }

    /**
     * Starts a cave-frontier excursion (design 5.4): capture the anchor exactly like {@link #start}, clear
     * strip ownership, stop, one beat, take and cache the mission ledger entry, snapshot the bot's current
     * sighting positions (the productive-cave check at arrival counts NEW ones against this snapshot), fix
     * the lease at {@link #FRONTIER_LEASE_TICKS} (not {@code DetourPolicy.leaseTicks}, which is ORE-only),
     * and enter {@link DetourPhase#FRONTIER_WALK} toward the first waypoint. The engine must be IDLE.
     */
    void startFrontier(DetourHost host, FrontierSelection selection) {
        kind = ExcursionKind.FRONTIER;
        lastFrontierProductive = false; // an aborted-before-arrival excursion also reads as unproductive
        anchor = host.captureAnchor();
        host.clearStripOwnership();
        host.stopAll();
        beat(host);

        int now = host.now();
        startNow = now;
        lastTickNow = now;
        breaks = 0;
        membersStarted = 0;
        seals = 0;
        dropsLost = 0;
        pendingAbort = null;
        abortReason = null;
        returnWhy = null;

        leaseDeadline = now + FRONTIER_LEASE_TICKS;
        ledger = host.ledger();
        ledger.noteStart(host.serverTick());
        lastClaimRenew = host.serverTick();

        seed = null;
        curId = null;
        clusterCells = List.of();
        cur = null;
        pending.clear();
        done.clear();
        noStep.clear();

        sightingPositionsAtStart = new HashSet<>();
        for (SightingLedger.Sighting s : host.sightings()) {
            sightingPositionsAtStart.add(s.pos());
        }

        waypoints = List.copyOf(selection.waypoints());
        waypointIndex = 0;
        host.log("ore_dig_frontier_start", "waypoints", waypoints.size(), "final", waypoints.get(waypoints.size() - 1));

        enterFrontierLeg(host, waypoints.get(0));
    }

    /**
     * Asks the engine to abort with {@code reason} at its next {@link #tick}: used by the coordinator's net
     * (through {@code DetourControl}), by {@code OreDigTask.detourClaimLava} and by the state-lost check of
     * {@code tickOpportunistic}. Ignored while idle or in RETURN. The first reason wins.
     */
    void requestAbort(String reason) {
        if (phase == DetourPhase.IDLE || phase == DetourPhase.RETURN) {
            return;
        }
        if (pendingAbort == null) {
            pendingAbort = reason;
        }
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
        if (phase == DetourPhase.IDLE) {
            return null;
        }
        host.releaseClaims();
        boolean drift = host.restoreAnchorNumbers(anchor);
        if (drift) {
            host.log("ore_dig_detour_cursor_drift", "face", anchor.face());
        }
        ledger.noteEnd(host.serverTick(), false, host.now() - startNow);
        host.log("ore_dig_detour_abort", "reason", reason == null ? "paused" : reason);
        DetourHost.Anchor result = anchor;
        resetToIdle();
        return result;
    }

    /**
     * The coordinator found the owning task gone (orphan cleanup, cause {@code owner_changed} or
     * {@code not_running}): note the end in the cached ledger entry (not completed, ticks so far), release nothing
     * (the coordinator released the claims), touch neither the action pack nor the world, and go IDLE. Idempotent;
     * a no-op when idle. Reached through {@code DetourControl.abandoned}.
     */
    void abandon(DetourHost host) {
        if (phase == DetourPhase.IDLE) {
            return;
        }
        ledger.noteEnd(host.serverTick(), false, host.now() - startNow);
        host.log("ore_dig_detour_abort", "reason", "abandoned");
        resetToIdle();
    }

    // ---- introspection --------------------------------------------------------------------------------------------

    /** True from a start until the tick that finishes it. Drives {@code assistDetourActive()} in OreDig. */
    boolean isActive() {
        return phase != DetourPhase.IDLE;
    }

    DetourPhase phase() {
        return phase;
    }

    /** The anchor of the running detour, or null when idle. */
    DetourHost.Anchor anchor() {
        return anchor;
    }

    /** {@code anchor().face()} while active, else null: what {@code detourPublishedFace} substitutes into the checkpoint. */
    BlockPos anchorFace() {
        return anchor == null ? null : anchor.face();
    }

    /** The member being approached or mined, or null. */
    BlockPos currentOre() {
        return cur;
    }

    /** The abort reason of the running detour, or null when it has none (a normal end has none). */
    String abortReason() {
        return abortReason;
    }

    /**
     * Counters of the running detour, kept after it finished until the next {@link #start} (so the code that
     * handles a FINISHED result can still read them for the end log line and the window counters).
     */
    int breaks() {
        return breaks;
    }

    int membersStarted() {
        return membersStarted;
    }

    int seals() {
        return seals;
    }

    int dropsLost() {
        return dropsLost;
    }

    /** Whether the last FINISHED excursion was of kind FRONTIER and ended unproductive (design 5.4's cooldown gate). Meaningless before any frontier excursion has run. */
    boolean wasFrontierUnproductive() {
        return kind == ExcursionKind.FRONTIER && !lastFrontierProductive;
    }

    // ===========================================================================================================
    // Phase steps
    // ===========================================================================================================

    private Result tickApproach(DetourHost host) {
        int now = host.now();
        if ((now - phaseEnter) % 10 == 0) {
            DetourHost.Seen s = host.observeBlockIs(cur, curId);
            if (s == DetourHost.Seen.GONE) {
                host.forgetSighting(cur);
                memberDone(cur);
                logSkip(host, cur, "gone");
                return toNext(host);
            }
            // UNKNOWN: nothing, an approach never mines.
        }
        BlockPos feet = host.feet();
        boolean arrived = feet.equals(pose.stand())
                || (host.pathIdle() && host.walkIdle() && host.inBreakEnvelope(cur) && !host.isCurrentSupport(cur));
        if (arrived) {
            enterMine(host);
            return tickMine(host);
        }
        double d = euclid(feet, pose.stand());
        if (d <= beatDist - PROGRESS_BLOCKS) {
            beat(host);
        }
        if (now - lastBeat > APPROACH_STALL_TICKS || now - phaseEnter > APPROACH_TOTAL_TICKS) {
            return abort(host, "approach_stall");
        }
        if (host.pathIdle()) {
            if (routeAttempts >= ROUTE_ATTEMPTS) {
                return abort(host, "route");
            }
            if (routeAttempts == 0 || now - lastRouteAttempt >= ROUTE_ATTEMPT_GAP_TICKS) {
                SafeReason r = host.safety(SafeGate.Stage.TICK_FULL, pose.stand(), cur);
                if (r != SafeReason.OK) {
                    return abort(host, r.abortReason());
                }
                DetourHost.RouteResult rr = host.startRoute(pose.stand(), routeMinY(feet, pose.stand(), host), anchor.face());
                if (rr == DetourHost.RouteResult.OK) {
                    routeAttempts++;
                    lastRouteAttempt = now;
                    waitSince = -1;
                    beat(host);
                } else if (rr == DetourHost.RouteResult.FAILED) {
                    routeAttempts++;
                    lastRouteAttempt = now;
                    waitSince = -1;
                    host.log("ore_dig_detour_route", "leg", "approach", "result", rr, "reason", host.routeFailureReason());
                } else {
                    // THROTTLED or BUDGET
                    if (waitSince < 0) {
                        waitSince = now;
                    } else if (now - waitSince > ROUTE_WAIT_MAX_TICKS) {
                        return abort(host, "budget");
                    }
                }
            }
        }
        return Result.CONSUMED;
    }

    private void enterApproach(DetourHost host) {
        phase = DetourPhase.APPROACH;
        phaseEnter = host.now();
        routeAttempts = 0;
        waitSince = -1;
        beat(host);
    }

    private void enterMine(DetourHost host) {
        phase = DetourPhase.MINE;
        mineStart = host.now();
        swingStarted = false;
        prepGated = false;
        unknownSince = -1;
        beat(host);
    }

    private Result tickMine(DetourHost host) {
        int now = host.now();
        if (!swingStarted) {
            if (now - mineStart > MINE_PREP_TICKS) {
                host.exclude(cur, ATTEMPT_EXCLUDE_TICKS);
                memberDone(cur);
                logSkip(host, cur, "prep_timeout");
                return toNext(host);
            }
            DetourHost.Seen s = host.observeBlockIs(cur, curId);
            if (s == DetourHost.Seen.GONE) {
                host.forgetSighting(cur);
                memberDone(cur);
                logSkip(host, cur, "gone");
                return toNext(host);
            }
            if (s == DetourHost.Seen.UNKNOWN) {
                if (unknownSince < 0) {
                    unknownSince = now;
                }
                if (now - unknownSince >= REPROOF_UNKNOWN_TICKS) {
                    host.exclude(cur, ATTEMPT_EXCLUDE_TICKS);
                    memberDone(cur);
                    logSkip(host, cur, "unknown");
                    return toNext(host);
                }
                return Result.CONSUMED;
            }
            unknownSince = -1;
            if (!prepGated) {
                SafeReason r = host.safety(SafeGate.Stage.TICK_FULL, pose.stand(), cur);
                if (r != SafeReason.OK) {
                    return abort(host, r.abortReason());
                }
                prepGated = true;
            }
            BlockPos feet = host.feet();
            int band = Math.min(Math.min(feet.getY(), pose.stand().getY()), cur.getY());
            if (band <= host.lavaBandTopY() && !host.sealMaterialOk()) {
                host.exclude(cur, ATTEMPT_EXCLUDE_TICKS);
                memberDone(cur);
                logSkip(host, cur, "seal_material");
                return toNext(host);
            }
            if (!host.tryClaim(cur)) {
                memberDone(cur);
                logSkip(host, cur, "claimed");
                return toNext(host);
            }
            if (!host.pathIdle() || !host.walkIdle()) {
                return Result.CONSUMED;
            }
            if (!host.inBreakEnvelope(cur) || host.isCurrentSupport(cur)) {
                DetourHost.Pose p = host.poseFor(cur, anchor, noStep);
                if (p == null) {
                    host.exclude(cur, ATTEMPT_EXCLUDE_TICKS);
                    memberDone(cur);
                    logSkip(host, cur, "no_pose");
                    return toNext(host);
                }
                if (!p.stand().equals(feet)) {
                    if (reposes >= MAX_REPOSES_PER_MEMBER) {
                        host.exclude(cur, ATTEMPT_EXCLUDE_TICKS);
                        memberDone(cur);
                        logSkip(host, cur, "pose_thrash");
                        return toNext(host);
                    }
                    reposes++;
                    pose = p;
                    prepGated = false;
                    enterApproach(host);
                    return Result.CONSUMED;
                }
                // p.stand == feet: fall through
            }
            DetourHost.GeometryVerdict g = host.breakGeometry(cur);
            if (g != DetourHost.GeometryVerdict.OK) {
                host.exclude(cur, ATTEMPT_EXCLUDE_TICKS);
                memberDone(cur);
                logSkip(host, cur, "geometry");
                return toNext(host);
            }
            DetourHost.FluidProbe f = host.probeFluidAround(cur);
            if (f.present() != null) {
                host.exclude(cur, ATTEMPT_EXCLUDE_TICKS);
                memberDone(cur);
                logSkip(host, cur, "fluid_adjacent");
                return toNext(host);
            }
            DetourHost.ToolVerdict t = host.toolVerdict(cur, 1 + pending.size());
            if (t == DetourHost.ToolVerdict.NO_TOOL) {
                return abort(host, "tool");
            }
            if (t == DetourHost.ToolVerdict.WEAR) {
                return abort(host, "tool_wear");
            }
            if (!host.capacityOk(cur, curId)) {
                return abort(host, "capacity");
            }
            swingStarted = true;
            swingStart = now;
            lastMineBeat = now;
            swingBaseline = host.inventoryTotal();
            beat(host);
            // falls through to the swing section below, same tick.
        }

        if (now - swingStart > MINE_SWING_TICKS) {
            host.cancelMining();
            host.exclude(cur, ATTEMPT_EXCLUDE_TICKS);
            memberDone(cur);
            logSkip(host, cur, "mine_timeout");
            return toNext(host);
        }
        DetourHost.MineStep ms = host.mineStep(cur);
        if (ms.status() == DetourHost.MineStep.Status.MINING) {
            if (now - lastMineBeat >= MINE_BEAT_TICKS) {
                beat(host);
                lastMineBeat = now;
            }
            return Result.CONSUMED;
        }
        if (ms.status() == DetourHost.MineStep.Status.DONE) {
            return onBreakDone(host);
        }
        // FAILED
        if (ms.isMissingChannelTool()) {
            return abort(host, "tool");
        }
        host.cancelMining();
        host.exclude(cur, ATTEMPT_EXCLUDE_TICKS);
        memberDone(cur);
        host.log("ore_dig_detour_skip", "reason", "break_failed", "pos", cur, "block", curId, "detail", ms.reason());
        return toNext(host);
    }

    private Result onBreakDone(DetourHost host) {
        breaks++;
        lastBreak = cur;
        host.log("ore_dig_detour_break", "pos", cur, "block", curId, "breaks", breaks);
        leaseDeadline = startNow + DetourPolicy.leaseTicks(host.config(), breaks);
        host.forgetSighting(cur);
        memberDone(cur);
        beat(host);
        debt = new DropDebt(cur, swingBaseline, host.now());
        sealsThisDebt = 0;
        phase = DetourPhase.POSTBREAK;
        postStart = host.now();
        return tickPostbreak(host);
    }

    private Result tickPostbreak(DetourHost host) {
        int now = host.now();
        if (now - postStart > POSTBREAK_TOTAL_TICKS) {
            return abort(host, "fluid_unsealable");
        }
        DetourHost.FluidProbe f = host.probeFluidAround(lastBreak);
        if (f.present() != null) {
            if (seals >= SEAL_CAP) {
                return abort(host, "fluid_unsealable");
            }
            DetourHost.SealResult r = host.sealOneFluidNeighbour(f.present());
            if (r == DetourHost.SealResult.SEALED) {
                seals++;
                sealsThisDebt++;
                host.log("ore_dig_detour_seal", "pos", f.present());
                beat(host);
                return Result.CONSUMED;
            }
            return abort(host, "fluid_unsealable");
        }
        if (f.unknown()) {
            noStep.add(lastBreak);
        }
        for (BlockPos p : host.neighbours26Same(lastBreak, curId)) {
            BlockPos pos = p.toImmutable();
            if (!done.contains(pos) && !pending.contains(pos) && !host.excluded(pos)
                    && membersStarted + pending.size() < MEMBER_CAP) {
                pending.add(pos);
            }
        }
        debt.baseline = swingBaseline - sealsThisDebt;
        phase = DetourPhase.SETTLE_DROP;
        settleStart = now;
        chaseAttempts = 0;
        gained = false;
        beat(host);
        return Result.CONSUMED;
    }

    private Result tickSettle(DetourHost host) {
        int now = host.now();
        int t = now - settleStart;
        host.tryForcedPickup();
        if (host.inventoryTotal() > debt.baseline && !gained) {
            gained = true;
            beat(host);
        }
        DetourHost.DropView dv = null;
        boolean settled;
        if (gained && t >= SETTLE_MIN_TICKS) {
            settled = true;
        } else if (t >= SETTLE_NO_DROP_TICKS) {
            dv = host.observeDrop(debt.cell);
            settled = !dv.visible();
        } else {
            settled = false;
        }
        if (settled) {
            leaveSettle(host);
            return toNext(host);
        }
        if (t >= SETTLE_TOTAL_TICKS) {
            dropsLost++;
            host.log("ore_dig_detour_drop_lost", "reason", "timeout", "pos", debt.cell);
            leaveSettle(host);
            return toNext(host);
        }
        if (!gained && t >= SETTLE_MIN_TICKS && chaseAttempts < CHASE_ATTEMPTS && host.pathIdle()) {
            if (dv == null) {
                dv = host.observeDrop(debt.cell);
            }
            if (dv.visible()) {
                if (dv.stand() == null || noStep.contains(dv.stand())) {
                    dropsLost++;
                    host.log("ore_dig_detour_drop_lost", "reason", "no_stand", "pos", debt.cell);
                    leaveSettle(host);
                    return toNext(host);
                }
                if (!dv.stand().equals(host.feet()) && (chaseAttempts == 0 || now - lastChase >= ROUTE_ATTEMPT_GAP_TICKS)) {
                    SafeReason r = host.safety(SafeGate.Stage.TICK_FULL, dv.stand(), null);
                    if (r != SafeReason.OK) {
                        return abort(host, r.abortReason());
                    }
                    int minY = Math.max(host.minStandY(), Math.min(Math.min(host.feet().getY(), dv.stand().getY()), anchor.y() - 3));
                    DetourHost.RouteResult rr = host.startRoute(dv.stand(), minY, anchor.face());
                    if (rr == DetourHost.RouteResult.OK) {
                        chaseAttempts++;
                        lastChase = now;
                        beat(host);
                    } else if (rr == DetourHost.RouteResult.FAILED) {
                        chaseAttempts++;
                        lastChase = now;
                    }
                    // THROTTLED/BUDGET: nothing.
                }
            }
        }
        return Result.CONSUMED;
    }

    private void leaveSettle(DetourHost host) {
        if (!host.pathIdle()) {
            host.stopAll();
        }
        debt = null;
    }

    private void enterFrontierLeg(DetourHost host, BlockPos waypoint) {
        phase = DetourPhase.FRONTIER_WALK;
        BlockPos feet = host.feet();
        pose = new DetourHost.Pose(waypoint, waypoint.equals(feet));
        phaseEnter = host.now();
        routeAttempts = 0;
        waitSince = -1;
        frontierRouteStarted = false;
        beat(host);
    }

    /**
     * Walks the current waypoint leg (design 5.4): structurally {@link #tickApproach} with a waypoint instead
     * of an ore's pose, no mining at the end. On arrival at a non-final waypoint, advances to the next leg
     * (next tick, unlike {@link #toNext}'s same-tick continuation -- at most {@link #FRONTIER_MAX_WAYPOINTS}
     * legs, so the one-tick-per-hop cost is negligible). On arrival at the FINAL waypoint: panorama burst,
     * then either {@link #rebaseCursorHere} in place (productive) or the ordinary {@link #beginReturn}
     * (design 5.4's "same... return").
     */
    private Result tickFrontierWalk(DetourHost host) {
        int now = host.now();
        BlockPos feet = host.feet();
        // Bug fix vs the contract's literal text: tickApproach's own "path went idle short of the exact stand"
        // fallback is guarded by inBreakEnvelope(cur), which has no equivalent for a waypoint (there is no ore).
        // Guarding on routeAttempts > 0 alone is not enough either: a FAILED startRoute leaves pathIdle() and
        // walkIdle() both true without the bot moving at all, so the fallback would still fire (falsely) after
        // just one failed attempt instead of letting all ROUTE_ATTEMPTS play out and abort with "route" (caught
        // by OreDigDetourEngineTest's route-failure case). Tracking whether a route actually started this leg
        // (frontierRouteStarted, set only on RouteResult.OK) means the fallback can only ever fire once a route
        // was genuinely dispatched -- for a leg that never gets a successful route, only the exact-position
        // check below or the stall/attempt caps can end it.
        boolean arrived = feet.equals(pose.stand())
                || (frontierRouteStarted && host.pathIdle() && host.walkIdle());
        if (arrived) {
            boolean isFinal = waypointIndex == waypoints.size() - 1;
            if (!isFinal) {
                waypointIndex++;
                enterFrontierLeg(host, waypoints.get(waypointIndex));
                return Result.CONSUMED;
            }
            return arriveAtFrontier(host);
        }
        double d = euclid(feet, pose.stand());
        if (d <= beatDist - PROGRESS_BLOCKS) {
            beat(host);
        }
        if (now - lastBeat > FRONTIER_LEG_STALL_TICKS || now - phaseEnter > FRONTIER_LEG_TOTAL_TICKS) {
            return abort(host, "approach_stall");
        }
        if (host.pathIdle()) {
            if (routeAttempts >= ROUTE_ATTEMPTS) {
                return abort(host, "route");
            }
            if (routeAttempts == 0 || now - lastRouteAttempt >= ROUTE_ATTEMPT_GAP_TICKS) {
                SafeReason r = host.safety(SafeGate.Stage.TICK_FULL, pose.stand(), null);
                if (r != SafeReason.OK) {
                    return abort(host, r.abortReason());
                }
                DetourHost.RouteResult rr = host.startRoute(pose.stand(), routeMinY(feet, pose.stand(), host), anchor.face());
                if (rr == DetourHost.RouteResult.OK) {
                    routeAttempts++;
                    lastRouteAttempt = now;
                    waitSince = -1;
                    frontierRouteStarted = true;
                    beat(host);
                } else if (rr == DetourHost.RouteResult.FAILED) {
                    routeAttempts++;
                    lastRouteAttempt = now;
                    waitSince = -1;
                    host.log("ore_dig_frontier_route", "leg", waypointIndex, "result", rr, "reason", host.routeFailureReason());
                } else {
                    if (waitSince < 0) {
                        waitSince = now;
                    } else if (now - waitSince > ROUTE_WAIT_MAX_TICKS) {
                        return abort(host, "budget");
                    }
                }
            }
        }
        return Result.CONSUMED;
    }

    /**
     * Arrival at the final waypoint (design 5.4). Fixed vs the prior draft of this contract: sets
     * {@link #lastFrontierProductive} in BOTH branches -- the prior draft only ever left it at the
     * {@code false} that {@link #startFrontier} set, so a genuinely productive excursion would still read
     * as "unproductive" afterward and wrongly arm the 600-tick cooldown after every single excursion. That
     * would have silently defeated the design's own "cooldown 600 after an *unproductive* excursion" and
     * throttled the feature far below its intended rate. There is a dedicated unit test for this
     * (Writer 2, §9): assert {@code wasFrontierUnproductive()} reads {@code false} on the tick immediately
     * after a productive `tick()` returns FINISHED.
     */
    private Result arriveAtFrontier(DetourHost host) {
        host.panoramaBurst();
        int newSightings = 0;
        for (SightingLedger.Sighting s : host.sightings()) {
            if (!sightingPositionsAtStart.contains(s.pos())) {
                newSightings++;
            }
        }
        boolean productive = newSightings >= FRONTIER_PRODUCTIVE_SIGHTINGS;
        lastFrontierProductive = productive;
        host.log("ore_dig_frontier_arrive", "new_sightings", newSightings, "productive", productive);
        if (productive) {
            if (!host.pathIdle()) {
                host.stopAll();
            }
            host.rebaseCursorHere();
            returnWhy = "productive";
            return finish(host, false);
        }
        return beginReturn(host, "done");
    }

    private Result toNext(DetourHost host) {
        phase = DetourPhase.NEXT;
        beat(host);
        if (!host.pathIdle()) {
            host.stopAll();
        }
        if (membersStarted >= MEMBER_CAP) {
            return beginReturn(host, "caps");
        }
        while (true) {
            BlockPos feet = host.feet();
            BlockPos m = nearestPending(feet);
            if (m == null) {
                return beginReturn(host, "done");
            }
            pending.remove(m);
            if (done.contains(m) || host.excluded(m)) {
                continue;
            }
            if (host.claimedByOther(m)) {
                done.add(m);
                logSkip(host, m, "claimed");
                continue;
            }
            DetourHost.Pose p = host.poseFor(m, anchor, noStep);
            if (p == null) {
                done.add(m);
                host.exclude(m, ATTEMPT_EXCLUDE_TICKS);
                logSkip(host, m, "no_pose");
                continue;
            }
            if (!p.zeroTransit() && ledger.zeroTransitOnly(host.serverTick())) {
                done.add(m);
                logSkip(host, m, "route_failures");
                continue;
            }
            int band = Math.min(Math.min(feet.getY(), p.stand().getY()), m.getY());
            if (band <= host.lavaBandTopY() && !host.sealMaterialOk()) {
                done.add(m);
                logSkip(host, m, "seal_material");
                continue;
            }
            cur = m;
            pose = p;
            membersStarted++;
            reposes = 0;
            if (p.zeroTransit()) {
                enterMine(host);
                return tickMine(host);
            }
            enterApproach(host);
            return tickApproach(host);
        }
    }

    private Result beginReturn(DetourHost host, String why) {
        returnWhy = why;
        phase = DetourPhase.RETURN;
        returnStart = host.now();
        returnAttempts = 0;
        returnRetryAt = returnStart;
        host.cancelMining();
        if (!host.pathIdle()) {
            host.stopAll();
        }
        host.releaseClaims();
        beat(host);
        return Result.CONSUMED;
    }

    private Result tickReturn(DetourHost host) {
        int now = host.now();
        BlockPos here = host.feet();
        if (here.equals(anchor.face())) {
            return finish(host, true);
        }
        double d = euclid(here, anchor.face());
        if (d <= beatDist - PROGRESS_BLOCKS) {
            beat(host);
        }
        if (now - returnStart > RETURN_TOTAL_TICKS) {
            return rebase(host, "total_cap");
        }
        boolean standable = host.feetStandable();
        if (!standable && now - lastBeat >= RETURN_UNSTANDABLE_RETRY_TICKS) {
            beat(host);
        }
        if (standable && now - lastBeat > RETURN_STALL_TICKS) {
            return rebase(host, "stall");
        }
        if (host.pathIdle()) {
            if (standable && returnAttempts >= ROUTE_ATTEMPTS) {
                return rebase(host, "route");
            }
            if (now >= returnRetryAt) {
                int minY = Math.max(host.minStandY(), Math.min(here.getY(), anchor.y()) - 1);
                DetourHost.RouteResult rr = host.startReturnRoute(anchor.face(), minY);
                if (rr == DetourHost.RouteResult.OK) {
                    returnAttempts++;
                    returnRetryAt = now + (standable ? ROUTE_ATTEMPT_GAP_TICKS : RETURN_UNSTANDABLE_RETRY_TICKS);
                    beat(host);
                } else if (rr == DetourHost.RouteResult.FAILED) {
                    returnAttempts++;
                    returnRetryAt = now + (standable ? ROUTE_ATTEMPT_GAP_TICKS : RETURN_UNSTANDABLE_RETRY_TICKS);
                    host.log("ore_dig_detour_route", "leg", "return", "result", rr, "reason", host.routeFailureReason());
                    if (!standable) {
                        beat(host);
                    }
                } else {
                    // THROTTLED or BUDGET: always a beat, never a rebase.
                    returnRetryAt = now + RETURN_WAIT_RETRY_TICKS;
                    beat(host);
                }
            }
        }
        return Result.CONSUMED;
    }

    private Result finish(DetourHost host, boolean restoreAnchor) {
        if (restoreAnchor) {
            boolean drift = host.restoreAnchorNumbers(anchor);
            if (drift) {
                host.log("ore_dig_detour_cursor_drift", "face", anchor.face());
            }
        }
        host.rebaseTargetMonitors();
        host.noteProgress();
        host.releaseClaims();
        boolean completed = abortReason == null;
        ledger.noteEnd(host.serverTick(), completed, host.now() - startNow);
        String reason = abortReason != null ? abortReason : returnWhy;
        host.log("ore_dig_detour_end", "reason", reason, "breaks", breaks, "members", membersStarted,
                "seals", seals, "drops_lost", dropsLost, "ticks", host.now() - startNow, "abort", abortReason);
        Result result = Result.finished(reason);
        resetToIdle();
        return result;
    }

    private Result rebase(DetourHost host, String why) {
        host.stopAll();
        host.rebaseCursorHere();
        ledger.disableDetours();
        host.warn("ore_dig_detour_return_rebased", "reason", why, "unsafe", !host.feetStandable(), "at", host.feet(),
                "anchor", anchor.face(), "breaks", breaks);
        host.rebaseTargetMonitors();
        host.noteProgress();
        host.releaseClaims();
        ledger.noteEnd(host.serverTick(), false, host.now() - startNow);
        host.log("ore_dig_detour_end", "reason", "return_rebased", "breaks", breaks, "members", membersStarted,
                "seals", seals, "drops_lost", dropsLost, "ticks", host.now() - startNow, "abort", abortReason);
        Result result = Result.finished("return_rebased");
        resetToIdle();
        return result;
    }

    private Result abort(DetourHost host, String reason) {
        if (abortReason == null) {
            abortReason = reason;
        }
        host.log("ore_dig_detour_abort", "reason", abortReason, "phase", phase, "pos", cur, "breaks", breaks);
        host.cancelMining();
        host.stopAll();
        applyAbortEffects(host, abortReason);
        return beginReturn(host, abortReason);
    }

    private void applyAbortEffects(DetourHost host, String reason) {
        switch (reason) {
            case "lease" -> excludeCluster(host, ATTEMPT_EXCLUDE_TICKS);
            case "approach_stall", "route" -> {
                excludeCluster(host, FAILURE_EXCLUDE_TICKS);
                if (pose != null) {
                    host.exclude(pose.stand(), FAILURE_EXCLUDE_TICKS);
                }
                ledger.noteRouteFailure(host.serverTick());
            }
            case "tool", "tool_wear" -> excludeCluster(host, FAILURE_EXCLUDE_TICKS);
            case "fluid_unsealable" -> {
                excludeCluster(host, FAILURE_EXCLUDE_TICKS);
                ledger.noteHazard(host.serverTick());
            }
            default -> {
                // safety_*, degraded_tps, paused, deep_dark_biome, poi_evidence, trap_spot, budget, capacity,
                // tick_gap, safety_state_lost: no exclusion, no ledger effect (C.5).
            }
        }
    }

    // ===========================================================================================================
    // Helpers
    // ===========================================================================================================

    private void memberDone(BlockPos p) {
        done.add(p);
        pending.remove(p);
    }

    private void logSkip(DetourHost host, BlockPos pos, String reason) {
        host.log("ore_dig_detour_skip", "reason", reason, "pos", pos, "block", curId);
    }

    private void excludeCluster(DetourHost host, int ttlServerTicks) {
        Set<BlockPos> targets = new LinkedHashSet<>(clusterCells);
        targets.addAll(pending);
        for (BlockPos p : targets) {
            host.exclude(p, ttlServerTicks);
        }
    }

    private BlockPos nearestPending(BlockPos feet) {
        BlockPos best = null;
        long bestDist = Long.MAX_VALUE;
        for (BlockPos p : pending) {
            long d = distSq(feet, p);
            if (best == null || d < bestDist || (d == bestDist && compareBlockPos(p, best) < 0)) {
                best = p;
                bestDist = d;
            }
        }
        return best;
    }

    /**
     * {@code lastBeat = now}, {@code beatDist} refreshed to the distance to the current phase goal (APPROACH and
     * FRONTIER_WALK: the pose stand -- a waypoint leg is walked exactly like an approach, design 5.4; RETURN: the
     * anchor face; any other phase has no such goal and keeps whatever it holds, which is only ever read again
     * once one of those phases is (re)entered, and all three re-beat on entry), then {@code host.noteProgress()}.
     * Without the FRONTIER_WALK case, {@code beatDist} would keep whatever a previous phase left it at and the
     * progress re-beat in {@link #tickFrontierWalk} ({@code d <= beatDist - PROGRESS_BLOCKS}) could never fire,
     * risking a spurious {@code approach_stall} on any leg slower than {@link #FRONTIER_LEG_STALL_TICKS}.
     */
    private void beat(DetourHost host) {
        lastBeat = host.now();
        if ((phase == DetourPhase.APPROACH || phase == DetourPhase.FRONTIER_WALK) && pose != null) {
            beatDist = euclid(host.feet(), pose.stand());
        } else if (phase == DetourPhase.RETURN && anchor != null) {
            beatDist = euclid(host.feet(), anchor.face());
        }
        host.noteProgress();
    }

    private void resetToIdle() {
        phase = DetourPhase.IDLE;
        pendingAbort = null;
        abortReason = null;
        returnWhy = null;
        anchor = null;
        seed = null;
        curId = null;
        cur = null;
        pose = null;
        pending.clear();
        done.clear();
        noStep.clear();
        clusterCells = List.of();
        debt = null;
        lastBreak = null;
        waypoints = List.of();
        waypointIndex = 0;
        sightingPositionsAtStart = Set.of();
        frontierRouteStarted = false;
        // breaks, membersStarted, seals, dropsLost are deliberately NOT reset here: start() resets them.
        // kind and lastFrontierProductive are deliberately NOT reset here either -- see their field javadoc.
    }

    private static int routeMinY(BlockPos a, BlockPos b, DetourHost host) {
        return Math.max(host.minStandY(), Math.min(a.getY(), b.getY()) - 1);
    }

    private static double euclid(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static long distSq(BlockPos a, BlockPos b) {
        long dx = a.getX() - b.getX();
        long dy = a.getY() - b.getY();
        long dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /** BlockPos order the design uses for ties: y ascending, then z, then x. */
    private static int compareBlockPos(BlockPos a, BlockPos b) {
        int c = Integer.compare(a.getY(), b.getY());
        if (c != 0) {
            return c;
        }
        c = Integer.compare(a.getZ(), b.getZ());
        if (c != 0) {
            return c;
        }
        return Integer.compare(a.getX(), b.getX());
    }
}
