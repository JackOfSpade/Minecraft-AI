package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.WalkedStep;
import io.github.zoyluo.minecraftai.action.WalkedStepRules;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/**
 * The swimming half of {@link FollowTask}: everything the bot does while the player it follows is
 * in the water, and while it climbs back out after them.
 *
 * <ul>
 *   <li><b>Entering.</b> A bot on land walks to the nearest reachable, observable water edge (plain
 *       adjacent water is enough -- no boat launch-site pairing) and walks in with a {@link WalkedStep}
 *       (real movement keys, never a teleport). The edge search is throttled and its result reused
 *       while it stays valid.</li>
 *   <li><b>Swimming.</b> One-cell {@link WalkedStep} swim steps toward the player (forward and jump keys,
 *       at the speed a swimmer has), greedy first and a bounded water BFS ({@link SwimRoute}) when a
 *       greedy step is blocked. Following a diver goes down with them. A step runs over several ticks
 *       and this class does nothing else with the bot until it has ended (or the drowning rescue
 *       takes over).</li>
 *   <li><b>Oxygen.</b> Follow owns the ascent while its {@link NavSafetyNet} lease is valid: it
 *       measures the bot's real air loss ({@link FollowOxygen.LossEstimator}) and turns up for
 *       breath early enough (see {@link FollowOxygen#shouldSurface}); it stays up until the lungs are
 *       nearly full. At NavSafetyNet's current depth-aware rescue boundary it makes no move at all
 *       so the drowning rescue is never undone.</li>
 *   <li><b>Leaving.</b> When the player leaves the water the bot swims to a dry landing near them,
 *       then ordinary land follow takes over.</li>
 * </ul>
 */
final class FollowSwimming {
    private static final int PATH_REPATH_TICKS = 30;
    private static final int ENTRY_SCAN_COOLDOWN_TICKS = 20;
    private static final int APPROACH_COOLDOWN_TICKS = 20;
    private static final int ROUTE_COOLDOWN_TICKS = 20;
    private static final int ROUTE_LIFETIME_TICKS = 80;
    private static final int AIR_ROUTE_COOLDOWN_TICKS = 10;
    private static final int BAD_SHORE_FORGET_TICKS = 300;
    private static final double ENTRY_RETARGET_DISTANCE_SQUARED = 100.0D;
    private static final int ENTRY_RADIUS = BoatSupport.LOCAL_WATER_SEARCH_RADIUS;
    private static final int ENTRY_DOWN = 6;
    private static final int ENTRY_UP = 3;
    /** Cooperative scan yield; every candidate/edge cursor is retained, never dropped as a result limit. */
    private static final int ENTRY_SCAN_WORK_PER_TICK = 96;
    /** The strict dry-approach envelope spans five vertical levels and every adjacent horizontal cell except its source. */
    private static final int OBSERVED_LAND_APPROACH_CANDIDATES = 5 * 3 * 3 - 1;
    /** One observed classification plus one final re-proved admission for every possible dry approach candidate. */
    private static final int OBSERVED_LAND_APPROACH_WORK_RESERVE = OBSERVED_LAND_APPROACH_CANDIDATES * 2;
    private static final int VERTICAL_AIR_SCAN = 48;
    /** Cooperative yield only; the column cursor is retained until it reaches its physical conclusion. */
    private static final int VERTICAL_AIR_WORK_PER_TICK = 8;
    /**
     * The complete adjacent swim envelope has 26 slots. Reserve those classifications plus the
     * first re-proved physical admission, so an UNKNOWN frontier can make a real observed
     * viewpoint change in the same tick without the route BFS and fallback exceeding 96 work.
     */
    private static final int UNKNOWN_WATER_EXPLORATION_RESERVE = 27;
    private static final int APPROACH_SHORE_RADIUS = 24;
    /** Mirrors FollowTask's arrival slack: a wading bot this close to the player has already arrived. */
    private static final double WADE_ARRIVAL_SLACK = 0.5D;

    private record Entry(BlockPos shore, BlockPos water) {
    }

    /** Snapshot for a retained or throttled route query; a completed negative result is never reused for another position. */
    private record RouteSearchQuery(BlockPos start, BlockPos target, SwimRoute.Goal goal,
                                    boolean hiddenWorldScan, double standoff, int observationRadius) {
    }

    /**
     * Identity-bearing context for a retained local exploration.  {@code source} deliberately
     * uses the exact SearchProgress object, whose default identity equality prevents an exhausted
     * frontier from a completed/restarted equal-looking query being reused.  The frozen goal is
     * the same legal target snapshot held by that source, rather than a moving player position.
     */
    private record ObservedWaterExplorationQuery(SwimRoute.SearchProgress source, BlockPos feet, BlockPos eye,
                                                 BlockPos goal, boolean preferUp, boolean hiddenWorldScan,
                                                 int observationRadius) {
    }

    /**
     * Per-server-tick accounting for strict water planning.  A planner may reserve at most one
     * lateral BFS slice per tick; an UNKNOWN result then spends only the remaining capacity on its
     * own local cursor. Reserving the 26-cell envelope and its first physical admission before a
     * strict BFS starts makes the cap independent of whether the frontier turns UNKNOWN on its
     * first or last examined edge.
     */
    private static final class StrictWaterWork {
        private int serverTick = Integer.MIN_VALUE;
        private int remaining;
        private boolean verticalColumnClaimed;
        private boolean lateralPlannerClaimed;

        private void begin(ServerLevel world) {
            int now = world.getServer().getTickCount();
            if (serverTick != now) {
                serverTick = now;
                remaining = SwimRoute.STRICT_SEARCH_CANDIDATES_PER_TICK;
                verticalColumnClaimed = false;
                lateralPlannerClaimed = false;
            }
        }

        private void reset() {
            serverTick = Integer.MIN_VALUE;
            remaining = 0;
            verticalColumnClaimed = false;
            lateralPlannerClaimed = false;
        }

        private int take(int requested) {
            return Math.min(Math.max(0, requested), remaining);
        }

        private boolean canAfford(int requested) {
            return requested >= 0 && remaining >= requested;
        }

        private void spend(int work) {
            remaining = Math.max(0, remaining - Math.max(0, work));
        }

        /** A second oxygen query in the same Follow tick may reuse a terminal result, never scan a second column slice. */
        private int claimVerticalColumn(int requested) {
            if (verticalColumnClaimed) {
                return 0;
            }
            verticalColumnClaimed = true;
            return take(requested);
        }

        /**
         * The caller receives the only lateral-BFS slot for this tick.  Strict mode leaves room
         * for the complete local envelope; operator hidden scans need no observed fallback.
         */
        private int claimLateralPlanner(boolean reserveObservedFallback) {
            if (lateralPlannerClaimed) {
                return 0;
            }
            lateralPlannerClaimed = true;
            int reserve = reserveObservedFallback ? UNKNOWN_WATER_EXPLORATION_RESERVE : 0;
            return Math.max(0, remaining - reserve);
        }

        /**
         * Entry discovery and strict land approach share the same tick. Retain enough work to
         * classify the entire local dry envelope and retry every candidate's final physical
         * admission; otherwise an unfinished entry frontier can starve a plainly visible first
         * step forever while still staying technically under the global cap.
         */
        private int claimEntrySearch(boolean reserveObservedLandApproach) {
            int reserve = reserveObservedLandApproach ? OBSERVED_LAND_APPROACH_WORK_RESERVE : 0;
            return Math.max(0, remaining - reserve);
        }
    }

    /** Immutable physical/capability admission for a Follow-owned walked step. */
    private record StepAdmission(boolean hiddenWorldScan, BlockPos origin, BlockPos destination) {
    }

    /** Complete context of a completed-negative water-entry scan. */
    private record EntrySearchQuery(BlockPos start, BlockPos target, boolean hiddenWorldScan,
                                    int observationRadius) {
    }

    private final FollowOxygen.LossEstimator loss = new FollowOxygen.LossEstimator();
    /** One strict navigation budget shared by every Follow path entered during a server tick. */
    private final StrictWaterWork strictWaterWork = new StrictWaterWork();
    private boolean ascending;
    private boolean waiting;

    private Entry entry;
    private BlockPos entryTargetPos;
    private int nextEntryScanTick;
    private EntrySearchProgress entrySearch;
    private EntrySearchQuery entrySearchQuery;
    private EntrySearchQuery entryCooldownQuery;
    private final Set<BlockPos> badShores = new HashSet<>();
    private int nextBadShoreForgetTick;
    private int nextPathTick;
    private int nextApproachTick;

    private List<BlockPos> route;
    private BlockPos routeStart;
    private SwimRoute.Goal routeGoal;
    private int routeExpiryTick;
    private int nextRouteSearchTick;
    /** Query which produced {@link #nextRouteSearchTick}; a changed feet, target, goal, or profile retries immediately. */
    private RouteSearchQuery routeCooldownQuery;
    /** Retained cooperative BFS; PENDING is never represented as an empty route. */
    private SwimRoute.SearchProgress routeSearch;
    /** Snapshot held by {@link #routeSearch}; its target intentionally stays stable while that search is pending. */
    private RouteSearchQuery routeSearchQuery;
    /** Retained observed local exploration for an UNKNOWN generic route/exit/air frontier. */
    private ObservedWaterExplorationProgress routeUnknownExploration;
    private int routeFailures;
    /** Capability state that produced {@link #route}; stale privileged routes are never reused in strict mode. */
    private boolean routeHiddenWorldScan;
    /** Strict observation radius that proved {@link #route}; a shrunken view re-plans immediately. */
    private int routeObservationRadius;
    private int exitFailedUntilTick;
    /** Exact query which produced {@link #exitFailedUntilTick}; other contexts retry at once. */
    private RouteSearchQuery exitFailureQuery;

    private int routeSearches;
    private int ascendCount;

    private double airRouteBlocks = Double.POSITIVE_INFINITY;
    private int nextAirRouteTick;
    private SwimRoute.SearchProgress airRouteSearch;
    /** True only when the retained lateral air route is blocked on an unobserved strict frontier. */
    private boolean airRouteAwaitingViewpoint;
    /** Separate from {@link #routeUnknownExploration}: both frontiers may be UNKNOWN concurrently. */
    private ObservedWaterExplorationProgress airUnknownExploration;
    private AirColumnSearch airColumnSearch;
    /** Capability state that produced {@link #airRouteBlocks}; a hidden result cannot steer strict oxygen decisions. */
    private boolean airRouteHiddenWorldScan;

    /** Exact ActionPack admission for the guarded step this class owns, until it is reconciled. */
    private ActionPack.StepLease stepLease;
    /** Capability and physical source/target corridor under which the running step was proved. */
    private StepAdmission stepAdmission;
    /** The water edge the running step enters through, so a failed entry marks the shore as bad. */
    private Entry stepEdge;
    /**
     * A stale Follow lease was preempted by NavSafetyNet and replaced by its emergency successor.
     * That successor owns movement until ActionPack reports no active step; this latch prevents a
     * later follow tick from renewing its lease, stopping inputs, or starting a fresh route first.
     */
    private boolean awaitingEmergencySuccessor;

    void reset(AIPlayerEntity bot) {
        // onStart can reuse this task instance after an interrupted step. Release the exact
        // guarded admission before discarding local state so ActionPack cannot retain a fence
        // that would block a future physical action.
        cancelStep(bot);
        if (bot.getActionPack().stepIdle()) {
            awaitingEmergencySuccessor = false;
        }
        loss.reset();
        strictWaterWork.reset();
        ascending = false;
        waiting = false;
        entry = null;
        entryTargetPos = null;
        nextEntryScanTick = 0;
        entrySearch = null;
        entrySearchQuery = null;
        entryCooldownQuery = null;
        badShores.clear();
        nextBadShoreForgetTick = BAD_SHORE_FORGET_TICKS;
        nextPathTick = 0;
        nextApproachTick = 0;
        clearRoute();
        nextRouteSearchTick = 0;
        routeCooldownQuery = null;
        exitFailedUntilTick = 0;
        exitFailureQuery = null;
        airRouteBlocks = Double.POSITIVE_INFINITY;
        nextAirRouteTick = 0;
        airRouteHiddenWorldScan = false;
        airRouteSearch = null;
        airRouteAwaitingViewpoint = false;
        airUnknownExploration = null;
        airColumnSearch = null;
        stepLease = null;
        stepAdmission = null;
        stepEdge = null;
    }

    /** Lets go of a step this class has in flight (a pause, an abort, a change of mode): its keys are released. */
    void cancelStep(AIPlayerEntity bot) {
        if (stepLease != null) {
            // Only the exact owner may cancel/release a guarded fence. A stale Follow instance
            // must not cancel a successor another controller legitimately started later.
            if (!bot.getActionPack().stepInFlightFor(stepLease) && !bot.getActionPack().stepIdle()) {
                awaitingEmergencySuccessor = true;
            }
            bot.getActionPack().cancelStep(stepLease);
        }
        stepLease = null;
        stepAdmission = null;
        stepEdge = null;
    }

    /** Must run before any path which can ask an oxygen or water-route question this server tick. */
    private void beginStrictWaterWork(ServerLevel world) {
        strictWaterWork.begin(world);
    }

    /** Spends one shared strict-planning unit for a candidate proof or an attempted admission. */
    private boolean consumeStrictWaterWork(boolean hiddenWorldScan) {
        if (hiddenWorldScan) {
            return true;
        }
        if (strictWaterWork.take(1) == 0) {
            return false;
        }
        strictWaterWork.spend(1);
        return true;
    }

    /** Preserves an all-candidates ranking: do not scan a strict envelope we cannot finish. */
    private boolean canAffordStrictWaterWork(boolean hiddenWorldScan, int work) {
        return hiddenWorldScan || strictWaterWork.canAfford(work);
    }

    /** A foreign safety successor must run alone until ActionPack has naturally gone idle. */
    private boolean emergencySuccessorHoldsTheTick(AIPlayerEntity bot) {
        if (!awaitingEmergencySuccessor) {
            return false;
        }
        if (bot.getActionPack().stepIdle()) {
            awaitingEmergencySuccessor = false;
            return false;
        }
        waiting = true;
        return true;
    }

    boolean isWaiting() {
        return waiting;
    }

    /** GameTests: how many bounded water-route searches this follower has run. */
    int routeSearchCount() {
        return routeSearches;
    }

    /** GameTests: how many times it turned up for breath. */
    int ascendCount() {
        return ascendCount;
    }

    /** True while the bot is in (or touching) water, i.e. this class owns its movement. */
    static boolean inWater(AIPlayerEntity bot) {
        return isSwimCell(bot.level(), bot.blockPosition());
    }

    // ---- following a swimming player ---------------------------------------------------------

    /**
     * One tick of following a waterborne player.
     *
     * @return true when the bot deliberately made no progress this tick (waiting)
     */
    boolean follow(AIPlayerEntity bot, ServerPlayer target, int elapsed, double stopDistance) {
        ServerLevel world = bot.level();
        beginStrictWaterWork(world);
        observeAir(bot, world);
        if (stepHoldsTheTick(bot, world, elapsed)) {
            return waiting;
        }
        if (!isSwimCell(world, bot.blockPosition())) {
            waiting = enterWater(bot, target, elapsed, stopDistance);
            return waiting;
        }
        clearEntry();
        waiting = swimAfter(bot, target, elapsed, stopDistance);
        return waiting;
    }

    private boolean swimAfter(AIPlayerEntity bot, ServerPlayer target, int elapsed, double stopDistance) {
        ServerLevel world = bot.level();
        boolean submerged = bot.isUnderWater();
        int air = bot.getAirSupply();
        if (mustYieldToWaterRescue(bot)) {
            // NavSafetyNet's drowning rescue owns the bot from here (its lease ends at this
            // depth-aware air level). Clear Follow's lease, but never zero the keys of an
            // already-admitted rescue stroke between its controller tick and vanilla physics.
            yieldMovementToWaterRescue(bot);
            clearRoute();
            return true;
        }
        NavSafetyNet.INSTANCE.renewFollowSwim(bot);

        double rate = lossRate(bot);
        AirPlan airPlan = submerged ? blocksToAir(bot, world, elapsed) : AirPlan.resolved(0.0D);
        updateAscending(bot, air, rate, submerged, airPlan);
        if (ascending) {
            if (submerged) {
                return ascendWhileSubmerged(bot, world, elapsed, air, airPlan);
            }
            // Head above water again: keep company with the player at the surface while the lungs
            // refill, but never go back down until they have.
            if (bot.distanceTo(target) <= stopDistance) {
                bot.getActionPack().stopMovement();
                return true;
            }
            return !swimStepToward(bot, target, false);
        }

        if (bot.distanceTo(target) <= stopDistance) {
            bot.getActionPack().stopMovement();
            clearRoute();
            return true;
        }
        if (swimStepToward(bot, target, true)) {
            clearRoute();
            return false;
        }
        return !routeStepToward(bot, target, elapsed);
    }

    /**
     * One tick of heading for air while the head is under water (shared by swimming after a player and by
     * climbing out after one).
     *
     * @return true when the bot deliberately made no progress this tick (waiting)
     */
    private boolean ascendWhileSubmerged(AIPlayerEntity bot, ServerLevel world, int elapsed, int air,
                                         AirPlan airPlan) {
        if (airPlan.pending()) {
            // A bounded column/route slice is scheduler state, not evidence that breathing air
            // is unavailable.  Once the real low-air floor has already selected ascent, re-prove
            // and take one physical upward stroke instead of freezing until the next slice.
            return !ascendStep(bot, world, elapsed);
        }
        double blocksToAir = airPlan.blocksToAir();
        if (Double.isInfinite(blocksToAir)) {
            // A direct upward column can be unknown while a visible lateral route to air exists.
            // Advance that retained route's viewpoint with one observed stroke instead of treating
            // the unknown column as a reason to freeze until the drowning rescue takes over.
            if (!SwimRoute.hiddenWorldScanAllowed(bot) && airRouteSearch != null
                    && airRouteAwaitingViewpoint
                    && beginAirObservedWaterExplorationStep(bot, "follow_swim_air_explore")) {
                return false;
            }
            // No known way to breathe: never dive on. Hand a low-air bot to the rescue.
            if (air <= FollowOxygen.SURFACE_FLOOR_AIR) {
                NavSafetyNet.INSTANCE.requestWaterRescue(bot);
                yieldMovementToWaterRescue(bot);
            } else {
                bot.getActionPack().stopMovement();
            }
            return true;
        }
        return !ascendStep(bot, world, elapsed);
    }

    private void updateAscending(AIPlayerEntity bot, int air, double rate, boolean submerged, AirPlan airPlan) {
        if (ascending) {
            if (FollowOxygen.mayResumeDive(air, bot.getMaxAirSupply(), rate)) {
                ascending = false;
                BotLog.action(bot, "follow_swim_resume_dive", "air", air);
            }
            return;
        }
        if (shouldSurface(air, rate, airPlan)) {
            ascending = true;
            ascendCount++;
            BotLog.action(bot, "follow_swim_ascend",
                    "air", air,
                    "loss", String.format(Locale.ROOT, "%.3f", rate),
                    "blocks_to_air", airPlan.pending() ? "pending" : Double.isInfinite(airPlan.blocksToAir())
                            ? "none" : String.format(Locale.ROOT, "%.1f", airPlan.blocksToAir()),
                    "submerged", submerged);
        }
    }

    /**
     * Keeps a cooperative oxygen-planning yield distinct from a terminal lack of a known route.
     * At the regular low-air floor we still turn up and let the strict physical step/rescue
     * re-prove the next cell; a healthy swimmer otherwise keeps normal follow pacing.
     */
    private static boolean shouldSurface(int air, double rate, AirPlan airPlan) {
        return airPlan.pending()
                ? rate > 0.0D && air <= FollowOxygen.SURFACE_FLOOR_AIR
                : FollowOxygen.shouldSurface(air, rate, airPlan.blocksToAir());
    }

    // ---- oxygen ------------------------------------------------------------------------------

    private void observeAir(AIPlayerEntity bot, ServerLevel world) {
        loss.observe(bot.getAirSupply(), bot.isUnderWater(), world.getServer().getTickCount());
    }

    /** Air lost per tick while submerged: zero under a long breathing effect, otherwise measured. */
    private double lossRate(AIPlayerEntity bot) {
        MobEffectInstance water = bot.getEffect(MobEffects.WATER_BREATHING);
        if (water != null && FollowOxygen.effectCoversDive(water.isInfiniteDuration(), water.getDuration())) {
            return 0.0D;
        }
        MobEffectInstance conduit = bot.getEffect(MobEffects.CONDUIT_POWER);
        if (conduit != null && FollowOxygen.effectCoversDive(conduit.isInfiniteDuration(), conduit.getDuration())) {
            return 0.0D;
        }
        return loss.lossPerTick();
    }

    /**
     * Distance the bot must swim to breathe: the free water column above its head when there is one,
     * otherwise the length of a bounded water route to the nearest cell with air (throttled).
     *
     * <p>In strict survival each column cell is observed before its state is read; an unseen
     * ceiling or water column is unknown, not a reason to keep diving. The explicit operator
     * capability retains the longer direct scan and the full route search in {@link SwimRoute}.
     */
    private AirPlan blocksToAir(AIPlayerEntity bot, ServerLevel world, int elapsed) {
        boolean hiddenWorldScan = SwimRoute.hiddenWorldScanAllowed(bot);
        if (airRouteHiddenWorldScan != hiddenWorldScan) {
            clearAirSearch(hiddenWorldScan);
        }
        BlockPos eye = BlockPos.containing(bot.getEyePosition());
        int observationRadius = SwimRoute.observationRadius(bot);
        if (airColumnSearch == null || !airColumnSearch.matches(eye, hiddenWorldScan, observationRadius)) {
            airColumnSearch = new AirColumnSearch(eye, hiddenWorldScan, observationRadius);
            // An eye-block change invalidates only the exact vertical proof.  A lateral search is
            // rooted at feet and accepts one physical swim move, so retaining it lets its cursor
            // re-root and retry the newly visible frontier instead of restarting every stroke.
            airRouteBlocks = Double.POSITIVE_INFINITY;
            nextAirRouteTick = 0;
            airRouteAwaitingViewpoint = false;
        }
        AirColumnResult column = airColumnSearch.advance(bot, world,
                strictWaterWork.claimVerticalColumn(VERTICAL_AIR_WORK_PER_TICK));
        strictWaterWork.spend(column.work());
        if (column.status() == AirColumnStatus.PENDING) {
            return AirPlan.yielded();
        }
        if (column.status() == AirColumnStatus.FOUND) {
            airRouteSearch = null;
            airRouteAwaitingViewpoint = false;
            airUnknownExploration = null;
            airRouteBlocks = column.blocks();
            return AirPlan.resolved(airRouteBlocks);
        }

        // UNKNOWN never licenses a straight-up ascent, but it must not prevent a separately
        // observed water route from finding a visible lateral way to breathe.
        BlockPos start = bot.blockPosition().immutable();
        if (airRouteSearch != null && !airRouteSearch.matchesContext(start, SwimRoute.Goal.AIR, 0.0D,
                hiddenWorldScan, observationRadius)) {
            airRouteSearch = null;
            airRouteAwaitingViewpoint = false;
            airUnknownExploration = null;
        }
        if (!ascending && !(lossRate(bot) > 0.0D)) {
            // Water Breathing / Conduit Power makes a lateral air route non-urgent.  Do not let
            // a permanently pending oxygen convenience search monopolise the one shared lateral
            // slice and starve the actual FOLLOW/EXIT route; its cursor remains intact for the
            // moment oxygen loss resumes.
            return AirPlan.resolved(airRouteBlocks);
        }
        if (airRouteSearch == null) {
            if (elapsed < nextAirRouteTick) {
                airRouteAwaitingViewpoint = false;
                return AirPlan.resolved(airRouteBlocks);
            }
            airRouteSearch = SwimRoute.startSearch(start, start, SwimRoute.Goal.AIR, 0.0D, hiddenWorldScan);
            airUnknownExploration = null;
        }
        SwimRoute.SearchResult result = advanceLateralSearch(bot, world, airRouteSearch, hiddenWorldScan);
        if (result == null) {
            // A route/exit frontier already spent this tick's only lateral slice.  This is a
            // cooperative PENDING yield, never evidence that the air route is empty or unknown.
            return AirPlan.yielded();
        }
        if (result.pending()) {
            // Retain this exact lateral frontier.  Its slice simply yielded after bounded work;
            // it must not be reinterpreted as an unknown/empty air route by oxygen control.
            airRouteAwaitingViewpoint = false;
            return AirPlan.yielded();
        }
        if (result.waitingForViewpoint()) {
            // PENDING was handled above.  The remaining waiting status is a terminal strict
            // UNKNOWN frontier, which may legitimately ask the observed-air exploration cursor
            // for a new physical viewpoint.
            airRouteAwaitingViewpoint = result.status() == SwimRoute.SearchStatus.UNKNOWN;
            return AirPlan.resolved(Double.POSITIVE_INFINITY);
        }
        airRouteSearch = null;
        airRouteAwaitingViewpoint = false;
        airUnknownExploration = null;
        airRouteBlocks = result.found() && !result.path().isEmpty()
                ? result.path().size() : Double.POSITIVE_INFINITY;
        nextAirRouteTick = elapsed + AIR_ROUTE_COOLDOWN_TICKS;
        return AirPlan.resolved(airRouteBlocks);
    }

    private void clearAirSearch(boolean hiddenWorldScan) {
        airRouteBlocks = Double.POSITIVE_INFINITY;
        nextAirRouteTick = 0;
        airRouteHiddenWorldScan = hiddenWorldScan;
        airRouteSearch = null;
        airRouteAwaitingViewpoint = false;
        airUnknownExploration = null;
        airColumnSearch = null;
    }

    /**
     * Advances at most one retained lateral water frontier during this server tick.  SearchProgress
     * deliberately exposes only a bounded advance API, not its internal operation counter, so
     * advancing in one-operation slices is the least invasive way to charge every queue/cursor/
     * neighbour operation to Follow's shared ledger.  A terminal/no-work return is charged one
     * conservatively; that can only leave capacity unused, never exceed the cap.
     *
     * @return null when another lateral planner already owns this tick's slice
     */
    private SwimRoute.SearchResult advanceLateralSearch(AIPlayerEntity bot, ServerLevel world,
                                                         SwimRoute.SearchProgress search,
                                                         boolean hiddenWorldScan) {
        int budget = strictWaterWork.claimLateralPlanner(!hiddenWorldScan);
        if (budget <= 0) {
            return null;
        }
        SwimRoute.SearchResult result = null;
        for (int work = 0; work < budget; work++) {
            result = search.advance(bot, world, 1);
            strictWaterWork.spend(1);
            if (!result.pending()) {
                return result;
            }
        }
        return result;
    }

    /**
     * An oxygen-planning answer. {@link #pending()} is a cooperative scheduler yield, whereas a
     * resolved infinity is a terminal proof that no direct/known breathing path is currently
     * available and therefore may trigger the conservative ascent behavior.
     */
    private record AirPlan(double blocksToAir, boolean pending) {
        private static AirPlan resolved(double blocksToAir) {
            return new AirPlan(blocksToAir, false);
        }

        private static AirPlan yielded() {
            return new AirPlan(Double.POSITIVE_INFINITY, true);
        }
    }

    private enum AirColumnStatus {
        PENDING,
        FOUND,
        NO_DIRECT,
        UNKNOWN
    }

    private record AirColumnResult(AirColumnStatus status, double blocks, int work) {
        private static AirColumnResult pending(int work) {
            return new AirColumnResult(AirColumnStatus.PENDING, Double.POSITIVE_INFINITY, work);
        }

        private static AirColumnResult noDirect(int work) {
            return new AirColumnResult(AirColumnStatus.NO_DIRECT, Double.POSITIVE_INFINITY, work);
        }

        private static AirColumnResult unknown(int work) {
            return new AirColumnResult(AirColumnStatus.UNKNOWN, Double.POSITIVE_INFINITY, work);
        }

        private static AirColumnResult found(double blocks, int work) {
            return new AirColumnResult(AirColumnStatus.FOUND, blocks, work);
        }

        private AirColumnResult withoutWork() {
            return work == 0 ? this : new AirColumnResult(status, blocks, 0);
        }
    }

    /**
     * Retained vertical probe for oxygen planning. It never materialises an arbitrary column or
     * lets an unseen strict cell become a raw fluid read; each call advances only a few cells and
     * preserves {@link #nextDy} for the next task tick.
     */
    private static final class AirColumnSearch {
        private final BlockPos eye;
        private final boolean hiddenWorldScan;
        private final int observationRadius;
        private int nextDy;
        /** The first unknown, ceiling, or breathable cell ends this exact vertical proof. */
        private AirColumnResult terminal;

        private AirColumnSearch(BlockPos eye, boolean hiddenWorldScan, int observationRadius) {
            this.eye = eye.immutable();
            this.hiddenWorldScan = hiddenWorldScan;
            this.observationRadius = observationRadius;
        }

        private boolean matches(BlockPos currentEye, boolean currentHiddenWorldScan,
                                int currentObservationRadius) {
            return eye.equals(currentEye)
                    && hiddenWorldScan == currentHiddenWorldScan
                    && observationRadius == currentObservationRadius;
        }

        private AirColumnResult advance(AIPlayerEntity bot, ServerLevel world, int workBudget) {
            if (terminal != null) {
                return terminal.withoutWork();
            }
            int work = 0;
            while (nextDy <= VERTICAL_AIR_SCAN && work < workBudget) {
                work++;
                BlockPos cell = eye.above(nextDy);
                if (!hiddenWorldScan && !SwimRoute.canObserveColumn(bot, cell)) {
                    // Keep this exact cursor. Advancing above an unseen water/ceiling cell would
                    // turn a later visible air block into invented knowledge of a clear column.
                    terminal = AirColumnResult.unknown(work);
                    return terminal;
                }
                nextDy++;
                if (world.getFluidState(cell).is(FluidTags.WATER)) {
                    continue;
                }
                if (!world.getBlockState(cell).getCollisionShape(world, cell).isEmpty()) {
                    terminal = AirColumnResult.noDirect(work);
                    return terminal;
                }
                terminal = AirColumnResult.found(Math.max(0.0D, cell.getY() - bot.getEyeY() + 0.3D), work);
                return terminal;
            }
            if (nextDy > VERTICAL_AIR_SCAN) {
                terminal = AirColumnResult.noDirect(work);
                return terminal;
            }
            return AirColumnResult.pending(work);
        }
    }

    /** One step toward air: straight up when the column is open, else along a water route to it. */
    private boolean ascendStep(AIPlayerEntity bot, ServerLevel world, int elapsed) {
        BlockPos above = bot.blockPosition().above();
        boolean hiddenWorldScan = SwimRoute.hiddenWorldScanAllowed(bot);
        if (consumeStrictWaterWork(hiddenWorldScan)
                && isObservedSafeSwimCell(bot, world, above, hiddenWorldScan)
                && beginStep(bot, above, "follow_swim_surface")) {
            clearRoute();
            return true;
        }
        if (route != null && (routeGoal != SwimRoute.Goal.AIR || elapsed >= routeExpiryTick)) {
            clearRoute();
        }
        if (route == null) {
            SwimRoute.SearchStatus status = searchRoute(bot, world, bot.blockPosition(),
                    SwimRoute.Goal.AIR, elapsed, 0.0D);
            if (status == SwimRoute.SearchStatus.UNKNOWN
                    && !SwimRoute.hiddenWorldScanAllowed(bot)
                    && beginRouteObservedWaterExplorationStep(bot, "follow_swim_surface_explore")) {
                return true;
            }
        }
        return stepAlongRoute(bot, "follow_swim_surface");
    }

    // ---- swimming after the player -----------------------------------------------------------

    private boolean swimStepToward(AIPlayerEntity bot, ServerPlayer target, boolean allowDown) {
        ServerLevel world = bot.level();
        boolean hiddenWorldScan = SwimRoute.hiddenWorldScanAllowed(bot);
        BlockPos current = bot.blockPosition();
        BlockPos goal = target.blockPosition();
        double before = current.distSqr(goal);
        List<BlockPos> choices = new ArrayList<>(6);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            choices.add(current.relative(direction));
        }
        choices.add(current.above());
        if (allowDown) {
            choices.add(current.below());
        }
        List<BlockPos> nearer = choices.stream()
                .filter(candidate -> candidate.distSqr(goal) + 0.01D < before)
                .sorted(Comparator.comparingDouble(candidate -> candidate.distSqr(goal)))
                .toList();
        // Preserve the prior complete-list ranking: if strict work cannot classify and retry the
        // whole immediate envelope this tick, yield rather than pick a candidate based on a
        // budget-dependent prefix. One unit covers each observation and each beginStep reproof.
        if (!canAffordStrictWaterWork(hiddenWorldScan, nearer.size() * 2)) {
            return false;
        }
        List<BlockPos> safe = new ArrayList<>(nearer.size());
        for (BlockPos candidate : nearer) {
            consumeStrictWaterWork(hiddenWorldScan);
            if (isObservedSafeSwimCell(bot, world, candidate, hiddenWorldScan)) {
                safe.add(candidate);
            }
        }
        for (BlockPos candidate : safe) {
            if (beginStep(bot, candidate, "follow_swim")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Continues local exploration for the generic route/exit/AIR SearchProgress which just said
     * UNKNOWN. Its target snapshot is deliberately the progress query's target, not the live
     * follower target: changing player positions must not reset a fully observed 26-cell cursor
     * before it can make the one real viewpoint move that lets the retained BFS retry.
     */
    private boolean beginRouteObservedWaterExplorationStep(AIPlayerEntity bot, String reason) {
        if (routeSearch == null || routeSearchQuery == null) {
            return false;
        }
        RouteSearchQuery sourceQuery = routeSearchQuery;
        BlockPos feet = bot.blockPosition().immutable();
        BlockPos eye = BlockPos.containing(bot.getEyePosition()).immutable();
        boolean preferUp = sourceQuery.goal() == SwimRoute.Goal.AIR;
        BlockPos goal = (preferUp ? sourceQuery.start().above() : sourceQuery.target()).immutable();
        ObservedWaterExplorationQuery query = new ObservedWaterExplorationQuery(routeSearch, feet, eye, goal,
                preferUp, sourceQuery.hiddenWorldScan(), sourceQuery.observationRadius());
        if (query.hiddenWorldScan()) {
            return false;
        }
        if (routeUnknownExploration == null || !routeUnknownExploration.matches(query)) {
            routeUnknownExploration = new ObservedWaterExplorationProgress(query);
        }
        return advanceObservedWaterExploration(bot, routeUnknownExploration, reason);
    }

    /** Same retained cursor discipline for the independent lateral route which seeks breathable air. */
    private boolean beginAirObservedWaterExplorationStep(AIPlayerEntity bot, String reason) {
        if (airRouteSearch == null) {
            return false;
        }
        BlockPos feet = bot.blockPosition().immutable();
        BlockPos eye = BlockPos.containing(bot.getEyePosition()).immutable();
        boolean hiddenWorldScan = SwimRoute.hiddenWorldScanAllowed(bot);
        ObservedWaterExplorationQuery query = new ObservedWaterExplorationQuery(airRouteSearch, feet, eye,
                feet.above().immutable(), true, hiddenWorldScan, SwimRoute.observationRadius(bot));
        if (query.hiddenWorldScan()) {
            return false;
        }
        if (airUnknownExploration == null || !airUnknownExploration.matches(query)) {
            airUnknownExploration = new ObservedWaterExplorationProgress(query);
        }
        return advanceObservedWaterExploration(bot, airUnknownExploration, reason);
    }

    /** Charges every local observation and attempted physical admission to the common tick ledger. */
    private boolean advanceObservedWaterExploration(AIPlayerEntity bot, ObservedWaterExplorationProgress progress,
                                                    String reason) {
        ObservedWaterExplorationAdvance advance = progress.advance(bot, strictWaterWork.take(
                SwimRoute.STRICT_SEARCH_CANDIDATES_PER_TICK), reason);
        strictWaterWork.spend(advance.work());
        return advance.started();
    }

    private record ObservedWaterExplorationAdvance(boolean started, int work) {
    }

    /**
     * Retained, fully observed 26-cell local fallback.  It first classifies every legal local
     * slot over as many slices as necessary, then applies the historic complete-list comparator.
     * That ordering matters: choosing the first visible candidate from a partial scan would change
     * the path choice merely because a tick budget happened to end there.
     */
    private final class ObservedWaterExplorationProgress {
        private final ObservedWaterExplorationQuery query;
        private final List<BlockPos> neighbors;
        private final List<BlockPos> candidates = new ArrayList<>();
        private int nextNeighbor;
        private int nextCandidate;
        private boolean scanned;

        private ObservedWaterExplorationProgress(ObservedWaterExplorationQuery query) {
            this.query = query;
            this.neighbors = NavSafetyNet.waterEscapeNeighbors(query.feet());
        }

        private boolean matches(ObservedWaterExplorationQuery current) {
            return query.equals(current);
        }

        private ObservedWaterExplorationAdvance advance(AIPlayerEntity bot, int workBudget, String reason) {
            ServerLevel world = bot.level();
            int work = 0;
            while (!scanned && work < workBudget) {
                if (nextNeighbor >= neighbors.size()) {
                    finishScan();
                    break;
                }
                BlockPos candidate = neighbors.get(nextNeighbor++);
                work++;
                // No cached cell state is ever read: each candidate goes through its current
                // strict observation proof before the water/collision predicates inspect terrain.
                if (isObservedSafeSwimCell(bot, world, candidate, false)) {
                    candidates.add(candidate.immutable());
                }
            }
            if (!scanned && nextNeighbor >= neighbors.size()) {
                finishScan();
            }
            while (scanned && !candidates.isEmpty() && work < workBudget
                    && nextCandidate < candidates.size()) {
                BlockPos candidate = candidates.get(nextCandidate++);
                work++;
                // beginStep repeats the strict observation/refusal proof immediately before
                // pressing keys, so an old visible candidate never licenses stale movement.
                if (beginStep(bot, candidate, reason, false)) {
                    // A started walked step can still fail before changing cells.  Start from the
                    // best rank again if that happens; only a real feet change may advance this
                    // local exploration's viewpoint/key.
                    nextCandidate = 0;
                    return new ObservedWaterExplorationAdvance(true, work);
                }
            }
            if (scanned && nextCandidate >= candidates.size()) {
                // A refusal can be transient (for example a separately owned guard fence).  Keep
                // the observed ranking but retry it from its best candidate next tick, exactly as
                // the former stream implementation retried the complete local envelope.
                nextCandidate = 0;
            }
            return new ObservedWaterExplorationAdvance(false, work);
        }

        private void finishScan() {
            candidates.sort(Comparator.comparingDouble(candidate -> query.preferUp()
                    ? -(candidate.getY() - query.feet().getY()) * 10_000.0D + candidate.distSqr(query.goal())
                    : candidate.distSqr(query.goal())));
            scanned = true;
        }
    }

    /** A greedy step is blocked (an island, a wall): follow a bounded water route around it. */
    private boolean routeStepToward(AIPlayerEntity bot, ServerPlayer target, int elapsed) {
        ServerLevel world = bot.level();
        if (route != null && (routeGoal != SwimRoute.Goal.APPROACH || elapsed >= routeExpiryTick)) {
            clearRoute();
        }
        if (route == null) {
            SwimRoute.SearchStatus status = searchRoute(bot, world, target.blockPosition(),
                    SwimRoute.Goal.APPROACH, elapsed, 0.0D);
            if (status == SwimRoute.SearchStatus.UNKNOWN
                    && !SwimRoute.hiddenWorldScanAllowed(bot)
                    && beginRouteObservedWaterExplorationStep(bot, "follow_swim_route_explore")) {
                return true;
            }
        }
        return stepAlongRoute(bot, "follow_swim_route");
    }

    // ---- leaving the water -------------------------------------------------------------------

    /**
     * The followed player is no longer waterborne but the bot still is: swim to a dry landing near
     * them. Ordinary land follow resumes once the bot stands on it.
     *
     * @return true when this class owned the tick (the caller must not run land follow), false when
     *         the bot is not in water or no way out is known (the caller falls back to land logic)
     */
    boolean exitWaterForLand(AIPlayerEntity bot, ServerPlayer target, int elapsed, double standoff) {
        ServerLevel world = bot.level();
        beginStrictWaterWork(world);
        observeAir(bot, world);
        if (stepHoldsTheTick(bot, world, elapsed)) {
            return true;
        }
        if (!needsWaterExit(bot, target, standoff)) {
            // Dry, or wading through shallows that land follow can handle itself (its pathfinder, stop
            // distance and stuck recovery): nothing here may run a water search for it.
            clearRoute();
            ascending = false;
            return false;
        }
        boolean submerged = bot.isUnderWater();
        int air = bot.getAirSupply();
        if (mustYieldToWaterRescue(bot)) {
            yieldMovementToWaterRescue(bot);
            clearRoute();
            waiting = true;
            return true;
        }
        NavSafetyNet.INSTANCE.renewFollowSwim(bot);
        if (submerged) {
            // Heading for land does not excuse holding one's breath: honour the same follow-first air
            // floor / early-ascent rule as swimming after the player.
            AirPlan airPlan = blocksToAir(bot, world, elapsed);
            updateAscending(bot, air, lossRate(bot), true, airPlan);
            if (ascending) {
                waiting = ascendWhileSubmerged(bot, world, elapsed, air, airPlan);
                return true;
            }
        }
        if (route != null && (routeGoal != SwimRoute.Goal.EXIT || elapsed >= routeExpiryTick)) {
            clearRoute();
        }
        if (route == null) {
            RouteSearchQuery exitQuery = currentRouteSearchQuery(bot, target.blockPosition(),
                    SwimRoute.Goal.EXIT, standoff);
            if (elapsed < exitFailedUntilTick && exitQuery.equals(exitFailureQuery)) {
                return false;
            }
            if (!exitQuery.equals(exitFailureQuery)) {
                exitFailedUntilTick = 0;
                exitFailureQuery = null;
            }
            SwimRoute.SearchStatus status = searchRoute(bot, world, target.blockPosition(),
                    SwimRoute.Goal.EXIT, elapsed, standoff);
            if (status == SwimRoute.SearchStatus.PENDING || status == SwimRoute.SearchStatus.UNKNOWN
                    || status == SwimRoute.SearchStatus.COOLDOWN) {
                if (status == SwimRoute.SearchStatus.UNKNOWN
                        && !SwimRoute.hiddenWorldScanAllowed(bot)
                        && beginRouteObservedWaterExplorationStep(bot, "follow_swim_exit_explore")) {
                    waiting = false;
                } else {
                    waiting = true;
                }
                return true;
            }
            if (route == null) {
                exitFailedUntilTick = elapsed + 40;
                exitFailureQuery = exitQuery;
                return false;
            }
        }
        exitFailedUntilTick = 0;
        exitFailureQuery = null;
        waiting = !stepAlongRoute(bot, "follow_swim_exit");
        return true;
    }

    // ---- entering the water ------------------------------------------------------------------

    /** @return true when waiting (nothing to do this tick) */
    private boolean enterWater(AIPlayerEntity bot, ServerPlayer target, int elapsed, double stopDistance) {
        clearRoute();
        if (ascending) {
            if (!FollowOxygen.mayResumeDive(bot.getAirSupply(), bot.getMaxAirSupply(), lossRate(bot))) {
                bot.getActionPack().stopMovement();
                return true;
            }
            ascending = false;
        }
        if (bot.distanceTo(target) <= stopDistance) {
            bot.getActionPack().stopMovement();
            return true;
        }
        Entry edge = currentEntry(bot, target, elapsed);
        if (edge == null) {
            return approachOnLand(bot, target, elapsed);
        }
        if (canStepInto(bot.blockPosition(), edge.water())) {
            boolean hiddenWorldScan = SwimRoute.hiddenWorldScanAllowed(bot);
            // Do not revoke the current controller merely to discover that the shared strict
            // ledger has no unit left for this edge's final physical admission.
            if (!canAffordStrictWaterWork(hiddenWorldScan, 1)) {
                return true;
            }
            NavSafetyNet.INSTANCE.renewFollowSwim(bot);
            bot.getActionPack().stopAll();
            boolean moved = beginStep(bot, edge.water(), "follow_swim_enter");
            if (moved) {
                stepEdge = edge;
            } else {
                markBad(edge);
            }
            return !moved;
        }
        if (!SwimRoute.hiddenWorldScanAllowed(bot)) {
            // Even a known visible edge does not authorize an unrestricted A* route through the
            // terrain between it and the bot. Drop any old operator planner and advance only by a
            // freshly observed local step toward that visible shore.
            bot.getActionPack().stopNavigation();
            return !beginObservedLandApproachStep(bot, edge.shore());
        }
        if (elapsed >= nextPathTick) {
            ActionResult path = bot.getActionPack().startPathTo(edge.shore());
            if (path.isFailed()) {
                if (!String.valueOf(path.reason()).contains("throttled")) {
                    markBad(edge);
                }
                bot.getActionPack().startWalkTo(edge.shore().getCenter(), 1.0D);
            }
            nextPathTick = elapsed + PATH_REPATH_TICKS;
        }
        return false;
    }

    /** No water edge in sight: close in on the swimmer over dry land so water comes into view. */
    private boolean approachOnLand(AIPlayerEntity bot, ServerPlayer target, int elapsed) {
        // Finding a remote standable cell is a hidden-world path prefilter. In strict survival,
        // advance only one observation-proven dry walked step toward the known player, then scan
        // again. This neither invents a destination around the swimmer nor gives up merely because
        // the next water edge is farther than a short local range.
        if (!SwimRoute.hiddenWorldScanAllowed(bot)) {
            // An operator-only land path may still be running after the capability flips. Keys
            // alone are not enough: a live executor re-presses them on its next tick. Stop that
            // stale navigation before strict survival either begins a newly observed WalkedStep or
            // yields because no local physical step exists.
            bot.getActionPack().stopNavigation();
            return !beginObservedLandApproachStep(bot, target.blockPosition());
        }
        if (elapsed < nextApproachTick) {
            return !bot.getActionPack().hasActiveActions();
        }
        nextApproachTick = elapsed + APPROACH_COOLDOWN_TICKS;
        if (elapsed % 200 == 1) {
            BotLog.action(bot, "follow_swim_no_water_edge");
        }
        BlockPos destination = Standability.findNearestStandable(
                bot.level(), target.blockPosition(), APPROACH_SHORE_RADIUS, 8, 4).orElse(null);
        if (destination == null) {
            bot.getActionPack().stopMovement();
            return true;
        }
        ActionResult path = bot.getActionPack().startPathTo(destination);
        return path.isFailed();
    }

    /**
     * Strict-survival land approach: make one legal, visible dry step that closes the gap to the
     * player, never a remote terrain scan or hidden path plan. The next follower tick re-runs the
     * ordinary edge scan from the new physical position.
     */
    private boolean beginObservedLandApproachStep(AIPlayerEntity bot, BlockPos goal) {
        ServerLevel world = bot.level();
        BlockPos feet = bot.blockPosition();
        double before = feet.distSqr(goal);
        List<BlockPos> candidates = new ArrayList<>();
        // This is the complete local dry WalkedStep envelope: every horizontal/diagonal neighbour
        // at a level, one-up, or up-to-three-down landing, plus a vertical drop. beginStep and
        // WalkedStep.refusal retain the final vanilla-physical legality check.
        for (int dy = -3; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dy != 0 || dz != 0) {
                        candidates.add(feet.offset(dx, dy, dz));
                    }
                }
            }
        }
        List<BlockPos> ranked = candidates.stream()
                .filter(candidate -> candidate.distSqr(goal) + 0.01D < before)
                .sorted(Comparator.comparingDouble(candidate -> candidate.distSqr(goal)))
                .toList();
        // The historic stream fully classified this ranked envelope before it attempted its
        // first step. Keep that decision independent of a partial strict budget: either reserve
        // observations plus all possible re-proved admissions, or yield for the next tick.
        if (!canAffordStrictWaterWork(false, ranked.size() * 2)) {
            return false;
        }
        List<BlockPos> dry = new ArrayList<>(ranked.size());
        for (BlockPos candidate : ranked) {
            consumeStrictWaterWork(false);
            if (SwimRoute.observedCell(bot, world, candidate, false) == SwimRoute.Cell.DRY) {
                dry.add(candidate);
            }
        }
        for (BlockPos candidate : dry) {
            if (beginStep(bot, candidate, "follow_swim_observed_land_approach")) {
                return true;
            }
        }
        return false;
    }

    private Entry currentEntry(AIPlayerEntity bot, ServerPlayer target, int elapsed) {
        ServerLevel world = bot.level();
        boolean hiddenWorldScan = SwimRoute.hiddenWorldScanAllowed(bot);
        if (elapsed >= nextBadShoreForgetTick) {
            badShores.clear();
            nextBadShoreForgetTick = elapsed + BAD_SHORE_FORGET_TICKS;
        }
        if (entry != null && entryTargetPos != null
                && target.blockPosition().distSqr(entryTargetPos) <= ENTRY_RETARGET_DISTANCE_SQUARED
                && entryStillValid(bot, world, entry, hiddenWorldScan)) {
            return entry;
        }
        entry = null;
        int observationRadius = SwimRoute.observationRadius(bot);
        EntrySearchQuery query = new EntrySearchQuery(bot.blockPosition().immutable(), target.blockPosition().immutable(),
                hiddenWorldScan, observationRadius);
        if (entrySearch != null && !entrySearch.matchesContext(bot.blockPosition(), hiddenWorldScan, observationRadius)) {
            entrySearch = null;
            entrySearchQuery = null;
            nextEntryScanTick = 0;
            entryCooldownQuery = null;
        }
        if (entrySearch == null) {
            if (elapsed < nextEntryScanTick && query.equals(entryCooldownQuery)) {
                return null;
            }
            nextEntryScanTick = 0;
            entryCooldownQuery = null;
            entrySearch = new EntrySearchProgress(bot.blockPosition(), target.blockPosition(), hiddenWorldScan,
                    observationRadius);
            entrySearchQuery = query;
        }
        int entryBudget = hiddenWorldScan ? ENTRY_SCAN_WORK_PER_TICK
                : strictWaterWork.claimEntrySearch(true);
        EntrySearchResult result = entrySearch.advance(bot, world, badShores, entryBudget);
        if (!hiddenWorldScan) {
            strictWaterWork.spend(result.work());
        }
        if (result.pending()) {
            return null;
        }
        EntrySearchQuery completedQuery = entrySearchQuery;
        entrySearch = null;
        entrySearchQuery = null;
        entry = result.entry();
        entryTargetPos = target.blockPosition().immutable();
        if (entry != null) {
            BotLog.action(bot, "follow_swim_water_edge", "shore", entry.shore().toShortString(),
                    "water", entry.water().toShortString());
        } else {
            if (!query.equals(completedQuery)) {
                // A retained scan remembers its target while pending, but an exhausted negative
                // result belongs only to that snapshot. Do not let it throttle a newly moved
                // player, profile, radius, or physical source position.
                nextEntryScanTick = 0;
                entryCooldownQuery = null;
                return null;
            }
            // Only a completed scan is cooled down. A PENDING scan resumes on the very next
            // eligible follower tick while strict local land approach continues physically.
            nextEntryScanTick = elapsed + ENTRY_SCAN_COOLDOWN_TICKS;
            entryCooldownQuery = completedQuery;
        }
        return entry;
    }

    /**
     * A retained, distance-ordered entry scan. It expands the fixed local water-search volume
     * lazily instead of materialising/sorting its whole cuboid, and retains a partial water-cell
     * and shore cursor across ticks. Every raw fluid/standability read remains downstream of the
     * relevant strict observation proof.
     */
    private static final class EntrySearchProgress {
        private static final Direction[] EXPANSION_DIRECTIONS = Direction.values();
        private static final Direction[] SHORE_DIRECTIONS = {
                Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
        };

        private final BlockPos origin;
        /** Last observed target position; target movement does not discard a live scan cursor. */
        private final BlockPos target;
        private final boolean hiddenWorldScan;
        private final int observationRadius;
        private final int horizontalRadius;
        private final int down;
        private final int up;
        private final PriorityQueue<BlockPos> frontier;
        private final Set<BlockPos> scheduled = new HashSet<>();
        private BlockPos current;
        private int nextExpansionDirection;
        private boolean waterChecked;
        private BlockPos water;
        private int nextShore;
        private Entry best;
        private double bestCost = Double.MAX_VALUE;

        private EntrySearchProgress(BlockPos origin, BlockPos target, boolean hiddenWorldScan,
                                    int observationRadius) {
            this.origin = origin.immutable();
            this.target = target.immutable();
            this.hiddenWorldScan = hiddenWorldScan;
            this.observationRadius = observationRadius;
            this.horizontalRadius = hiddenWorldScan ? ENTRY_RADIUS : Math.min(ENTRY_RADIUS, observationRadius);
            this.down = hiddenWorldScan ? ENTRY_DOWN : Math.min(ENTRY_DOWN, observationRadius);
            this.up = hiddenWorldScan ? ENTRY_UP : Math.min(ENTRY_UP, observationRadius);
            this.frontier = new PriorityQueue<>(Comparator.comparingDouble(cell -> cell.distSqr(this.origin)));
            enqueue(this.origin);
        }

        private boolean matchesContext(BlockPos currentOrigin, boolean currentHiddenWorldScan,
                                       int currentObservationRadius) {
            return origin.equals(currentOrigin)
                    && hiddenWorldScan == currentHiddenWorldScan
                    && observationRadius == currentObservationRadius;
        }

        private EntrySearchResult advance(AIPlayerEntity bot, ServerLevel world, Set<BlockPos> badShores,
                                          int workBudget) {
            int work = 0;
            while (work < workBudget) {
                if (current == null) {
                    current = frontier.poll();
                    work++;
                    if (current == null) {
                        return EntrySearchResult.completed(best, work);
                    }
                    nextExpansionDirection = 0;
                    waterChecked = false;
                    water = null;
                    nextShore = 0;
                    continue;
                }
                if (nextExpansionDirection < EXPANSION_DIRECTIONS.length) {
                    BlockPos candidate = current.relative(EXPANSION_DIRECTIONS[nextExpansionDirection++]);
                    work++;
                    if (withinBounds(candidate)) {
                        enqueue(candidate);
                    }
                    continue;
                }
                if (!waterChecked) {
                    waterChecked = true;
                    work++;
                    if (!hiddenWorldScan && !SwimRoute.withinObservationRange(bot, current)) {
                        continue;
                    }
                    SwimRoute.Cell waterCell = SwimRoute.observedCell(bot, world, current, hiddenWorldScan);
                    if (waterCell == null || !waterCell.isWater()) {
                        continue;
                    }
                    // observedCell proved feet/head before these entry-specific fluid reads.
                    if (!world.getFluidState(current).is(FluidTags.WATER)
                            || !world.getFluidState(current.above()).isEmpty()) {
                        continue;
                    }
                    // observedCell proved this water cell through fluids before the raw reads
                    // above. Requiring BoatSupport's Fluid.ANY lake-surface ray here would make
                    // intervening transparent water hide the very entry it already proved.
                    water = current.immutable();
                    continue;
                }
                if (water == null || nextShore >= SHORE_DIRECTIONS.length * 2) {
                    // Closing a partial candidate is also one scheduled operation; it prevents
                    // a frontier made entirely of rejected cells from spinning unboundedly.
                    current = null;
                    water = null;
                    work++;
                    // A fully proved nearby entry is immediately useful. Do not make a follower
                    // wait for every farther cell in the local volume merely to refine a score;
                    // it can physically approach the visible shore now and re-scan from that new
                    // viewpoint if the edge goes stale.
                    if (best != null) {
                        return EntrySearchResult.completed(best, work);
                    }
                    continue;
                }
                int shoreIndex = nextShore++;
                BlockPos shore = water.relative(SHORE_DIRECTIONS[shoreIndex / 2]).above(shoreIndex % 2);
                work++;
                if (badShores.contains(shore)
                        || (!hiddenWorldScan && !SwimRoute.withinObservationRange(bot, shore))) {
                    continue;
                }
                if (SwimRoute.observedCell(bot, world, shore, hiddenWorldScan) != SwimRoute.Cell.DRY) {
                    continue;
                }
                Entry candidate = new Entry(shore.immutable(), water);
                double cost = Math.sqrt(candidate.shore().distSqr(origin))
                        + 0.75D * Math.sqrt(candidate.water().distSqr(target));
                if (cost < bestCost) {
                    bestCost = cost;
                    best = candidate;
                }
            }
            return EntrySearchResult.inProgress(work);
        }

        private boolean withinBounds(BlockPos candidate) {
            return Math.abs(candidate.getX() - origin.getX()) <= horizontalRadius
                    && Math.abs(candidate.getZ() - origin.getZ()) <= horizontalRadius
                    && candidate.getY() >= origin.getY() - down
                    && candidate.getY() <= origin.getY() + up;
        }

        private void enqueue(BlockPos candidate) {
            BlockPos cell = candidate.immutable();
            if (scheduled.add(cell)) {
                frontier.add(cell);
            }
        }
    }

    private record EntrySearchResult(Entry entry, boolean pending, int work) {
        private static EntrySearchResult inProgress(int work) {
            return new EntrySearchResult(null, true, work);
        }

        private static EntrySearchResult completed(Entry entry, int work) {
            return new EntrySearchResult(entry, false, work);
        }
    }

    /** Re-proves a cached water edge before an action can reuse it. */
    private boolean entryStillValid(AIPlayerEntity bot, ServerLevel world, Entry entry,
                                    boolean hiddenWorldScan) {
        if (!canAffordStrictWaterWork(hiddenWorldScan, 2)) {
            return false;
        }
        consumeStrictWaterWork(hiddenWorldScan);
        SwimRoute.Cell water = SwimRoute.observedCell(bot, world, entry.water(), hiddenWorldScan);
        if (water == null || !water.isWater()) {
            return false;
        }
        consumeStrictWaterWork(hiddenWorldScan);
        if (SwimRoute.observedCell(bot, world, entry.shore(), hiddenWorldScan) != SwimRoute.Cell.DRY) {
            return false;
        }
        if (!world.getFluidState(entry.water()).is(FluidTags.WATER)) {
            return false;
        }
        // Both the water and shore have just been re-proved with the transparent-water policy;
        // that proof still stops at solid collision terrain, so no second Fluid.ANY water-face
        // ray may reject an otherwise visible underwater entry.
        return true;
    }

    private void markBad(Entry failed) {
        badShores.add(failed.shore());
        entry = null;
        // The retained winner may be this now-failed shore; restart lazily with the bad-shore
        // memory rather than allowing an old best candidate to be returned later.
        entrySearch = null;
        entrySearchQuery = null;
        entryCooldownQuery = null;
        nextEntryScanTick = 0;
    }

    private void clearEntry() {
        entry = null;
        entrySearch = null;
        entrySearchQuery = null;
        entryCooldownQuery = null;
        nextEntryScanTick = 0;
    }

    private static boolean canStepInto(BlockPos from, BlockPos to) {
        int dx = Math.abs(to.getX() - from.getX());
        int dy = Math.abs(to.getY() - from.getY());
        int dz = Math.abs(to.getZ() - from.getZ());
        int axes = (dx == 0 ? 0 : 1) + (dy == 0 ? 0 : 1) + (dz == 0 ? 0 : 1);
        return dx <= 1 && dy <= 1 && dz <= 1 && axes >= 1 && axes <= 2;
    }

    // ---- water routes ------------------------------------------------------------------------

    /**
     * Advances one retained water-route search. A slice is a cooperative server-work yield, not
     * a routing limit: its queue/parents survive every {@link SwimRoute.SearchStatus#PENDING}
     * result. A completed empty search starts a query-keyed cooldown, reported separately as
     * {@link SwimRoute.SearchStatus#COOLDOWN}; it is never presented as another EMPTY result.
     */
    private SwimRoute.SearchStatus searchRoute(AIPlayerEntity bot, ServerLevel world, BlockPos target,
                                               SwimRoute.Goal goal, int elapsed, double standoff) {
        boolean hiddenWorldScan = SwimRoute.hiddenWorldScanAllowed(bot);
        BlockPos start = bot.blockPosition().immutable();
        int observationRadius = SwimRoute.observationRadius(bot);
        RouteSearchQuery query = new RouteSearchQuery(start, target.immutable(), goal, hiddenWorldScan, standoff,
                observationRadius);
        if (routeSearch != null && !routeSearch.matchesContext(start, goal, standoff,
                hiddenWorldScan, observationRadius)) {
            // The start/profile/goal changed physically. A target movement alone intentionally
            // does not restart a pending search: its target snapshot is the bot's legal memory.
            routeSearch = null;
            routeSearchQuery = null;
            routeUnknownExploration = null;
        }
        if (routeSearch == null) {
            if (elapsed < nextRouteSearchTick && query.equals(routeCooldownQuery)) {
                return SwimRoute.SearchStatus.COOLDOWN;
            }
            nextRouteSearchTick = 0;
            routeCooldownQuery = null;
            routeSearch = SwimRoute.startSearch(start, query.target(), goal, standoff, hiddenWorldScan);
            routeSearchQuery = query;
            routeUnknownExploration = null;
            routeSearches++;
        }
        SwimRoute.SearchResult result = advanceLateralSearch(bot, world, routeSearch, hiddenWorldScan);
        if (result == null) {
            // The oxygen route already owns this server tick's lateral work.  Preserve the exact
            // route frontier and report only a cooperative yield; UNKNOWN/EMPTY would wrongly
            // authorize a local move or a query-keyed cooldown for work not yet performed.
            return SwimRoute.SearchStatus.PENDING;
        }
        if (result.waitingForViewpoint()) {
            return result.status();
        }
        RouteSearchQuery completedQuery = routeSearchQuery;
        routeSearch = null;
        routeSearchQuery = null;
        routeUnknownExploration = null;
        if (!result.found() && !query.equals(completedQuery)) {
            // A retained frontier deliberately keeps its target while it is pending, so a moving
            // player cannot restart every 96-work slice. Its terminal *negative* result belongs
            // only to that original snapshot, however: never let it tell an EXIT caller that a
            // newly moved player has no shore. Start the current query on the next cooperative
            // slice instead of arming the exhausted-route cooldown or exit-failure handoff.
            routeSearch = SwimRoute.startSearch(start, query.target(), goal, standoff, hiddenWorldScan);
            routeSearchQuery = query;
            routeUnknownExploration = null;
            routeSearches++;
            return SwimRoute.SearchStatus.PENDING;
        }
        if (result.found() && !result.path().isEmpty()) {
            route = result.path();
            routeStart = bot.blockPosition().immutable();
            routeGoal = goal;
            routeExpiryTick = elapsed + ROUTE_LIFETIME_TICKS;
            routeFailures = 0;
            routeHiddenWorldScan = hiddenWorldScan;
            routeObservationRadius = observationRadius;
            return SwimRoute.SearchStatus.FOUND;
        } else {
            clearRoute();
            nextRouteSearchTick = elapsed + ROUTE_COOLDOWN_TICKS;
            routeCooldownQuery = completedQuery;
            return SwimRoute.SearchStatus.EMPTY;
        }
    }

    /** The complete context a completed negative route result is allowed to throttle. */
    private static RouteSearchQuery currentRouteSearchQuery(AIPlayerEntity bot, BlockPos target,
                                                            SwimRoute.Goal goal, double standoff) {
        return new RouteSearchQuery(bot.blockPosition().immutable(), target.immutable(), goal,
                SwimRoute.hiddenWorldScanAllowed(bot), standoff, SwimRoute.observationRadius(bot));
    }

    private boolean stepAlongRoute(AIPlayerEntity bot, String reason) {
        if (route == null) {
            return false;
        }
        if (routeHiddenWorldScan != SwimRoute.hiddenWorldScanAllowed(bot)
                || (!routeHiddenWorldScan && routeObservationRadius != SwimRoute.observationRadius(bot))) {
            clearRoute();
            nextRouteSearchTick = 0;
            routeCooldownQuery = null;
            return false;
        }
        BlockPos feet = bot.blockPosition();
        int index = route.indexOf(feet);
        if (index < 0 && !feet.equals(routeStart)) {
            clearRoute();
            return false;
        }
        int next = index + 1;
        if (next >= route.size()) {
            clearRoute();
            return false;
        }
        BlockPos cell = route.get(next);
        boolean moved = beginStep(bot, cell, reason);
        if (!moved && ++routeFailures >= 3) {
            clearRoute();
        }
        return moved;
    }

    // ---- walked steps ------------------------------------------------------------------------

    /**
     * Starts a walked step onto {@code cell} (a swim step for a water cell, a walk, hop or drop for a dry landing) unless the step
     * is refused (a block or an entity in the way, a hazard, not adjacent). The bot is never moved: only its keys are pressed.
     *
     * @return whether the step was started
     */
    private boolean beginStep(AIPlayerEntity bot, BlockPos cell, String reason) {
        return beginStep(bot, cell, reason, true);
    }

    /**
     * Starts one physical admission. Callers that already reserve this admission in a retained
     * cursor pass {@code false}; every other strict caller pays one common-ledger unit here for
     * its immediate re-observation/refusal proof and input attempt.
     */
    private boolean beginStep(AIPlayerEntity bot, BlockPos cell, String reason, boolean chargeStrictAdmission) {
        boolean hiddenWorldScan = SwimRoute.hiddenWorldScanAllowed(bot);
        if (chargeStrictAdmission && !consumeStrictWaterWork(hiddenWorldScan)) {
            return false;
        }
        SwimRoute.Cell observed = SwimRoute.observedCell(bot, bot.level(), cell, hiddenWorldScan);
        if (observed == null) {
            return false;
        }
        WalkedStep.Kind kind = observed == SwimRoute.Cell.DRY
                ? dryStepKind(bot, cell)
                : WalkedStep.Kind.SWIM;
        if (kind == null) {
            return false;
        }
        // observedCell proved the landing (or water destination) itself. Before the vanilla
        // validator reads its diagonal corner, hop-headroom, or descent sweep cells, strict
        // survival must prove those finite physical cells too.
        if (!hiddenWorldScan && !SwimRoute.canObserveWalkedStepRefusalEnvelope(bot, cell, kind)) {
            return false;
        }
        if (WalkedStep.refusal(bot, cell, kind) != null) {
            return false;
        }
        StepAdmission admission = new StepAdmission(hiddenWorldScan, bot.blockPosition().immutable(), cell.immutable());
        ActionPack.StepLease lease = bot.getActionPack().runStep(WalkedStep.begin(bot, cell, kind, reason),
                (guardBot, step) -> canContinueObservedStep(guardBot, step, admission));
        if (lease == null) {
            // Another guarded physical step still owns ActionPack's fail-closed handoff fence.
            // Its owner will reconcile/release it before this route publishes a fresh movement.
            return false;
        }
        stepLease = lease;
        stepAdmission = admission;
        return true;
    }

    /**
     * ActionPack invokes this before every in-flight step terrain read, which is earlier than the
     * end-of-tick Follow owner check. An operator admission cannot therefore cross into strict
     * survival, and a strict step cannot continue after its currently visible destination or
     * vanilla refusal envelope has become occluded.
     */
    private static boolean canContinueObservedStep(AIPlayerEntity bot, WalkedStep step,
                                                   StepAdmission admission) {
        // Do the capability-free physical provenance check before any fresh world observation.
        // The narrow first-tick source-settling envelope mirrors WalkedStep's own no-world-read
        // normalization; a distant controller relocation can never retain this step's keys.
        if (!step.cell().equals(admission.destination())
                || !withinStepContinuationEnvelope(bot.blockPosition(), admission, step.kind(), step.ticks())) {
            return false;
        }
        if (admission.hiddenWorldScan()) {
            return SwimRoute.hiddenWorldScanAllowed(bot);
        }
        SwimRoute.Cell observed = SwimRoute.observedCell(bot, bot.level(), step.cell(), false);
        if (observed == null) {
            return false;
        }
        // A changing fluid state is not merely an occlusion change: WalkedStep's dry and swim
        // validators read different terrain. Fail closed instead of letting a formerly dry
        // landing flood (or water drain) between admission and this pre-tick proof.
        if (step.kind() == WalkedStep.Kind.SWIM ? !observed.isWater() : observed != SwimRoute.Cell.DRY) {
            return false;
        }
        return SwimRoute.canObserveWalkedStepRefusalEnvelope(bot, step.cell(), step.kind());
    }

    private static boolean withinStepContinuationEnvelope(BlockPos feet, StepAdmission admission,
                                                          WalkedStep.Kind kind, int activeStepTicks) {
        if (withinStepCorridor(feet, admission.origin(), admission.destination())) {
            return true;
        }
        BlockPos origin = admission.origin();
        return isNormalizableDryWalk(kind)
                && activeStepTicks >= 0 && activeStepTicks <= 1
                && feet.getX() == origin.getX()
                && feet.getZ() == origin.getZ()
                && Math.abs(feet.getY() - origin.getY()) == 1;
    }

    private static boolean withinStepCorridor(BlockPos feet, BlockPos origin, BlockPos destination) {
        return between(feet.getX(), origin.getX(), destination.getX())
                && between(feet.getY(), origin.getY(), destination.getY())
                && between(feet.getZ(), origin.getZ(), destination.getZ());
    }

    private static boolean between(int value, int first, int second) {
        return value >= Math.min(first, second) && value <= Math.max(first, second);
    }

    private static boolean isNormalizableDryWalk(WalkedStep.Kind kind) {
        return kind == WalkedStep.Kind.FLAT
                || kind == WalkedStep.Kind.STEP_UP
                || kind == WalkedStep.Kind.STEP_DOWN;
    }

    /** The dry-cell member of the complete adjacent WalkedStep envelope, including a vertical drop. */
    private static WalkedStep.Kind dryStepKind(AIPlayerEntity bot, BlockPos cell) {
        BlockPos feet = bot.blockPosition();
        if (cell.getX() == feet.getX() && cell.getZ() == feet.getZ() && cell.getY() < feet.getY()) {
            return WalkedStep.Kind.DROP;
        }
        return WalkedStepRules.walkKindFor(cell.getY() - feet.getY());
    }

    /**
     * Whether a step this class started is still running. When it has just ended, its outcome is taken in: a failed entry marks its
     * shore bad and a failed route step counts against the route (three drop it), as a refused step used to.
     */
    private boolean stepInFlight(AIPlayerEntity bot) {
        ActionPack.StepLease lease = stepLease;
        if (lease == null) {
            return false;
        }
        var pack = bot.getActionPack();
        if (pack.stepInFlightFor(lease)) {
            // A cell admitted under the operator's hidden-world capability must never continue
            // after a profile/capability change restores strict survival. Cancel before the action
            // pack advances; the normal branch below can only start a freshly observed step.
            StepAdmission admission = stepAdmission;
            if (admission == null || admission.hiddenWorldScan() != SwimRoute.hiddenWorldScanAllowed(bot)
                    || !withinStepContinuationEnvelope(bot.blockPosition(), admission, pack.activeStepKind(),
                    pack.activeStepTicks())) {
                cancelStep(bot);
                clearRoute();
                nextRouteSearchTick = 0;
                routeCooldownQuery = null;
                return false;
            }
            return true;
        }
        // A guarded lease can only produce a result for its own natural terminal. Ordinary
        // foreign starts are fenced while the lease is live; an active non-owned successor here
        // is therefore the higher-priority emergency handoff. Never cancel it while reconciling
        // stale Follow state, and do not let its global ActionPack result poison this route.
        boolean emergencySuccessorActive = !pack.stepIdle();
        if (emergencySuccessorActive) {
            awaitingEmergencySuccessor = true;
        }
        WalkedStep.Result result = emergencySuccessorActive ? null : pack.stepResultFor(lease);
        pack.releaseStepLease(lease);
        stepLease = null;
        stepAdmission = null;
        if (result == null) {
            // Generic cancellation and a foreign handoff say nothing about a water edge's
            // physical validity. Forget the plan and re-prove it instead of marking the shore
            // bad from someone else's action result.
            stepEdge = null;
            clearRoute();
            nextRouteSearchTick = 0;
            routeCooldownQuery = null;
            return false;
        }
        if (result != null && result.failed()) {
            if ("continuation_guard".equals(result.reason())) {
                // A capability/corridor/visibility invalidation says nothing about whether the
                // entry shore is bad terrain. Forget its cached selection and re-prove it under
                // the current context instead of poisoning it for BAD_SHORE_FORGET_TICKS.
                entry = null;
                entrySearch = null;
                nextEntryScanTick = 0;
                clearRoute();
                nextRouteSearchTick = 0;
                routeCooldownQuery = null;
            } else if (stepEdge != null) {
                markBad(stepEdge);
            } else if (route != null && ++routeFailures >= 3) {
                clearRoute();
            }
        }
        stepEdge = null;
        return false;
    }

    /**
     * Whether a step in flight takes this tick: it carries on by itself unless the drowning rescue takes the bot over (then 
     * {@code waiting} is set) or the lungs call for the way up right now, which is decided every tick, not only between steps (a step
     * down through deep water takes seconds): the step is dropped and the tick carries on with the ordinary swim logic.
     */
    private boolean stepHoldsTheTick(AIPlayerEntity bot, ServerLevel world, int elapsed) {
        // This must precede holdStep: renewing Follow's short water lease while an
        // emergency-preempted successor is still active would reclaim movement from safety.
        if (emergencySuccessorHoldsTheTick(bot)) {
            return true;
        }
        if (!stepInFlight(bot)) {
            // The first reconciliation of a preempted lease discovers the successor below.
            // Hold this same tick too; do not wait until the next caller tick to honor it.
            return emergencySuccessorHoldsTheTick(bot);
        }
        if (holdStep(bot)) {
            waiting = true;
            return true;
        }
        if (!ascending && bot.isUnderWater()
                && shouldSurface(bot.getAirSupply(), lossRate(bot), blocksToAir(bot, world, elapsed))) {
            cancelStep(bot);
            return false;
        }
        waiting = false;
        return true;
    }

    /**
     * One tick with a step in flight: it carries on by itself (the bot is not touched), the swim lease is kept, and a bot whose air
     * has fallen to the rescue level is let go so that the drowning rescue owns it from this tick.
     *
     * @return true when the bot deliberately made no progress (it was handed to the rescue)
     */
    private boolean holdStep(AIPlayerEntity bot) {
        if (mustYieldToWaterRescue(bot)) {
            cancelStep(bot);
            yieldMovementToWaterRescue(bot);
            clearRoute();
            return true;
        }
        NavSafetyNet.INSTANCE.renewFollowSwim(bot);
        return false;
    }

    /**
     * Follow only yields for low air while submerged, but it must always preserve a live Nav
     * rescue stroke. A swimmer can bob out of the underwater state between Nav's controller tick
     * and physics without making that already-admitted stroke safe to cancel.
     */
    private static boolean mustYieldToWaterRescue(AIPlayerEntity bot) {
        return NavSafetyNet.INSTANCE.rescueStepOwnsMovement(bot)
                || (bot.isUnderWater() && NavSafetyNet.followSwimMustYield(bot));
    }

    /**
     * Drops Follow's narrow water lease without clearing a rescue stroke that NavSafetyNet has
     * already admitted. {@link ActionPack#stopMovement()} is normally a safe key release, but a
     * guarded step writes its inputs before entity physics; a later Follow task tick must not
     * erase that exact step's jump/forward input in the intervening window.
     */
    private static void yieldMovementToWaterRescue(AIPlayerEntity bot) {
        NavSafetyNet.INSTANCE.clearFollowSwim(bot);
        if (!NavSafetyNet.INSTANCE.rescueStepOwnsMovement(bot)) {
            bot.getActionPack().stopMovement();
        }
    }

    private void clearRoute() {
        route = null;
        routeStart = null;
        routeGoal = null;
        routeExpiryTick = 0;
        routeFailures = 0;
        routeHiddenWorldScan = false;
        routeObservationRadius = 0;
        routeSearch = null;
        routeSearchQuery = null;
        routeUnknownExploration = null;
    }

    // ---- cell predicates ---------------------------------------------------------------------

    /**
     * True when the bot is genuinely swimming: its head is under water, or it is afloat with nothing
     * solid under its feet. Standing on a solid bottom with the head clear is wading -- ordinary
     * walking, not swimming -- however wet its feet are.
     */
    static boolean isSwimming(AIPlayerEntity bot) {
        ServerLevel world = bot.level();
        BlockPos feet = bot.blockPosition();
        if (!isSwimCell(world, feet)) {
            return false;
        }
        if (bot.isUnderWater()) {
            return true;
        }
        BlockPos below = feet.below();
        return world.getBlockState(below).getCollisionShape(world, below).isEmpty();
    }

    /**
     * Whether this class must take a land-bound tick from land follow. Genuine swimming always does.
     * Wading (feet wet, head clear, solid bottom) is ordinary walking and stays with land follow --
     * except in the middle of a shallow, where land follow has no legal start cell (a wet cell is
     * never "standable", and its start snap only reaches a dry neighbour): there the bot would sit
     * forever, so it wades out along a water route.
     */
    private boolean needsWaterExit(AIPlayerEntity bot, ServerPlayer target, double standoff) {
        if (isSwimming(bot)) {
            return true;
        }
        ServerLevel world = bot.level();
        BlockPos feet = bot.blockPosition();
        if (!isSwimCell(world, feet)) {
            return false;
        }
        if (bot.distanceTo(target) <= standoff + WADE_ARRIVAL_SLACK) {
            return false;
        }
        // A budget-yield is deliberately treated as "no confirmed dry footing": falling back to
        // water ownership is conservative, whereas giving land follow an unproved wet start is
        // not. The next server tick resumes with a fresh shared strict-work slice.
        return !hasDryFootingWithinOneStep(bot, feet, strictWaterWork);
    }

    /** A dry, standable cell one step away (same level, one up, one down) that land follow's start snap can reach. */
    static boolean hasDryFootingWithinOneStep(AIPlayerEntity bot, BlockPos feet) {
        return hasDryFootingWithinOneStep(bot, feet, null);
    }

    /**
     * Full 18-cell start envelope. The Follow path passes its shared ledger so this immediate
     * observation loop cannot sit outside the 96-work strict planning cap; standalone callers
     * retain the complete unmetered predicate above.
     */
    private static boolean hasDryFootingWithinOneStep(AIPlayerEntity bot, BlockPos feet,
                                                       StrictWaterWork strictWork) {
        ServerLevel world = bot.level();
        boolean hiddenWorldScan = SwimRoute.hiddenWorldScanAllowed(bot);
        if (!hiddenWorldScan && strictWork != null && !strictWork.canAfford(18)) {
            return false;
        }
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if ((dx == 0 && dz == 0 && dy == 0) || (dy != 0 && Math.abs(dx) + Math.abs(dz) > 1)) {
                        continue;
                    }
                    BlockPos candidate = feet.offset(dx, dy, dz);
                    if (!hiddenWorldScan && strictWork != null) {
                        strictWork.spend(1);
                    }
                    if (SwimRoute.observedCell(bot, world, candidate, hiddenWorldScan) == SwimRoute.Cell.DRY) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    static boolean isSwimCell(ServerLevel world, BlockPos pos) {
        return BoatSupport.isWater(world, pos) || BoatSupport.isWater(world, pos.above());
    }

    private static boolean isObservedSafeSwimCell(AIPlayerEntity bot, ServerLevel world, BlockPos pos) {
        return isObservedSafeSwimCell(bot, world, pos, SwimRoute.hiddenWorldScanAllowed(bot));
    }

    private static boolean isObservedSafeSwimCell(AIPlayerEntity bot, ServerLevel world, BlockPos pos,
                                                   boolean hiddenWorldScan) {
        SwimRoute.Cell cell = SwimRoute.observedCell(bot, world, pos, hiddenWorldScan);
        return cell != null && cell.isWater();
    }
}
