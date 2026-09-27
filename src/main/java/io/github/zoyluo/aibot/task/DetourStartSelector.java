package io.github.zoyluo.aibot.task;

import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * The IDLE half of the detour engine: decides, for one bot at one moment, whether a detour may start and on what
 * (mining-assist design 4.3 start conditions, 4.2 admission, 4.5 pose). Pure over {@link DetourHost}: it reads
 * facts through the host, calls {@code DetourPolicy}, {@code MissionAssistLedger}, {@code ObservedReach} through
 * the host, and returns a {@link Selection} or a reason. It changes nothing except the skip bookkeeping the
 * design asks for (exclusions of failed candidates, sightings observed gone, one log line per skipped cell) and
 * the seed claim of the returned selection. The engine ({@link OreDigDetourEngine#tick}) calls it at most once
 * every {@value #START_CHECK_INTERVAL_TICKS} task ticks (staggered by {@link DetourHost#staggerSeed()}) and then
 * performs the start actions of design 4.3 itself.
 *
 * <h2>Procedure (cheap first; every step returns "no selection" on failure, the reason being logged only where stated)</h2>
 * <ol>
 *   <li>{@code host.ownersIdle()}, and {@code host.now() >= MIN_TASK_AGE_TICKS} ({@value #MIN_TASK_AGE_TICKS}). Silent.</li>
 *   <li>{@code host.ledger().startVerdict(serverTick, config, taskMaxElapsedTicks) == OK}. Silent.</li>
 *   <li>{@code host.sightings()} not empty; anchor = {@code host.captureAnchor()} (side effect free);
 *       {@code DetourPolicy.rank(sightings, feet, eyePos, anchor.face, targetLock, config)} not empty (rank also
 *       drops {@code neverDetour} ids and raw blocks without natural context). Silent.</li>
 *   <li>Only now the expensive gate: {@code host.safety(START, null, null)} is OK (mode, origin, audit, TPS,
 *       headroom, hp, water, paused, threats, lava, deep dark, POI, traps). Silent. An idle bot with nothing to
 *       rank never pays for the gate.</li>
 *   <li>For each ranked candidate in order, with two caps: at most {@value #MAX_REPROOFS} candidates reach the
 *       re-proof (step d, one observation each) and at most {@value #MAX_EVALUATED} of those pass it and go on to
 *       the pose (steps e and later, "evaluated"):
 *     <ol type="a">
 *       <li>{@code host.excluded(pos)}: skip, log {@code excluded}. Not counted.</li>
 *       <li>{@code host.isTargetOre(blockId)}: skip silently. {@code host.bonusOwns(pos, blockId)}: skip silently.
 *           Not counted.</li>
 *       <li>{@code host.claimedByOther(pos)}: skip, log {@code claimed}. Not counted.</li>
 *       <li>Re-proof {@code host.observeBlockIs(pos, blockId)} (counts toward MAX_REPROOFS): GONE forgets the
 *           sighting ({@code host.forgetSighting}) and logs {@code gone}; UNKNOWN logs {@code unknown} and keeps
 *           the nomination (no exclusion); only PRESENT goes on and counts toward MAX_EVALUATED.</li>
 *       <li>{@code host.capacityOk(pos, blockId)} false: skip, log {@code capacity}, and exclude the cluster for
 *           {@value #SOFT_EXCLUDE_TICKS} ticks (a short back-off, so the same candidates are not re-examined every
 *           check). Cheap, so before the pose.</li>
 *       <li>{@code host.poseFor(pos, anchor, {})}: null logs {@code no_pose} and excludes the whole cluster for
 *           {@value #CLUSTER_EXCLUDE_TICKS} ticks.</li>
 *       <li>If the pose is not zero transit: with {@code ledger.zeroTransitOnly(serverTick)} skip
 *           ({@code route_failures}); {@code host.observedReach(feet, stand)} UNREACHABLE logs
 *           {@code unreachable_observed} and excludes the cluster for {@value #CLUSTER_EXCLUDE_TICKS}; REACHABLE
 *           with {@code !DetourPolicy.pathLengthOk(length, config)} logs {@code path_too_long} and excludes the
 *           cluster for {@value #CLUSTER_EXCLUDE_TICKS}; INCONCLUSIVE goes on. Then
 *           {@code host.routeStartAllowed()} false ends the whole selection with the reason {@code budget}
 *           (nothing is excluded, the next check retries).</li>
 *       <li>Lava band: when {@code min(feet.y, stand.y, ore.y) <= host.lavaBandTopY()} and
 *           {@code !host.sealMaterialOk()}: skip, log {@code seal_material}, and exclude the cluster for
 *           {@value #SOFT_EXCLUDE_TICKS} ticks.</li>
 *       <li>{@code host.toolVerdict(pos, 1)} (it equips the tool, hence this late position): NO_TOOL logs
 *           {@code tool} and WEAR logs {@code tool_wear}; both exclude the cluster for
 *           {@value #TOOL_EXCLUDE_TICKS} ticks.</li>
 *       <li>{@code host.tryClaim(pos)} false: skip, log {@code claimed}. Otherwise return the selection.</li>
 *     </ol>
 *   </li>
 * </ol>
 * A "log" line is {@code host.log("ore_dig_detour_skip", "reason", r, "pos", pos, "block", blockId, "value", v)}
 * written only when {@code host.shouldLogSkip(pos)} says the cell has not been logged for 600 ticks; the skip line
 * is the typed form of the design's non-abort reasons ({@code claimed}, {@code gone}, {@code no_pose},
 * {@code unreachable_observed}). Deterministic: for equal host answers the result is equal.
 */
final class DetourStartSelector {
    /** Design 4.3: start checks run at most every this many task ticks. */
    static final int START_CHECK_INTERVAL_TICKS = 10;
    /** Design 4.3: no detour before the task is this old (task ticks). */
    static final int MIN_TASK_AGE_TICKS = 60;
    /** Most candidates that pass the re-proof and get a pose search in one check (bounds the pose and reach cost of a check). */
    static final int MAX_EVALUATED = 4;
    /** Most candidates that reach the re-proof in one check (bounds the observation cost, UNKNOWN ones included). */
    static final int MAX_REPROOFS = 8;
    /** Design 4.5: exclusion of a cluster with no pose or no observed route. */
    static final int CLUSTER_EXCLUDE_TICKS = 600;
    /** Exclusion of a cluster whose mining tool is missing or worn (server ticks). */
    static final int TOOL_EXCLUDE_TICKS = 1200;
    /** Short back-off (server ticks) of a cluster rejected for capacity or missing seal material. */
    static final int SOFT_EXCLUDE_TICKS = 100;

    /**
     * A candidate the engine may start. {@code cluster} is the Chebyshev-3 same-block cluster (candidate first);
     * {@code value} is the raw value; {@code score} the admission score; {@code anchor} the provisional anchor the
     * pose was searched against (the engine captures the real one again at the start). The selector has already
     * claimed the seed.
     */
    record Selection(BlockPos seed, String blockId, int value, double score, DetourHost.Pose pose,
                     List<BlockPos> cluster, DetourHost.Anchor anchor) {
    }

    /** Outcome of {@link #select}: {@code selection} is null when nothing may start; {@code reason} then says why the last candidate or the whole check ended ({@code ""} when silently nothing to do). */
    record Result(Selection selection, String reason) {
        static final Result NONE = new Result(null, "");

        static Result none(String reason) {
            return new Result(null, reason == null ? "" : reason);
        }

        static Result of(Selection selection) {
            return new Result(selection, "");
        }
    }

    private DetourStartSelector() {
    }

    /** Whether a start check is due at task tick {@code taskTick}: {@code (taskTick + staggerSeed) % START_CHECK_INTERVAL_TICKS == 0}. */
    static boolean checkDue(int taskTick, int staggerSeed) {
        throw new UnsupportedOperationException("P1 stub: DetourStartSelector.checkDue");
    }

    /** The procedure of the class comment. */
    static Result select(DetourHost host) {
        throw new UnsupportedOperationException("P1 stub: DetourStartSelector.select");
    }
}
