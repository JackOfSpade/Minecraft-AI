package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.WalkedStep;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
    private static final int ENTRY_CANDIDATES_CHECKED = 8;
    private static final int VERTICAL_AIR_SCAN = 48;
    private static final int APPROACH_SHORE_RADIUS = 24;
    /** Mirrors FollowTask's arrival slack: a wading bot this close to the player has already arrived. */
    private static final double WADE_ARRIVAL_SLACK = 0.5D;

    private record Entry(BlockPos shore, BlockPos water) {
    }

    private final FollowOxygen.LossEstimator loss = new FollowOxygen.LossEstimator();
    private boolean ascending;
    private boolean waiting;

    private Entry entry;
    private BlockPos entryTargetPos;
    private int nextEntryScanTick;
    private final Set<BlockPos> badShores = new HashSet<>();
    private int nextBadShoreForgetTick;
    private int nextPathTick;
    private int nextApproachTick;

    private List<BlockPos> route;
    private BlockPos routeStart;
    private SwimRoute.Goal routeGoal;
    private int routeExpiryTick;
    private int nextRouteSearchTick;
    private int routeFailures;
    private int exitFailedUntilTick;

    private int routeSearches;
    private int ascendCount;

    private double airRouteBlocks = Double.POSITIVE_INFINITY;
    private int nextAirRouteTick;

    /** A walked step this class started is (or was, until its end is noticed) running on the bot's action pack. */
    private boolean stepOwned;
    /** The water edge the running step enters through, so a failed entry marks the shore as bad. */
    private Entry stepEdge;

    void reset() {
        loss.reset();
        ascending = false;
        waiting = false;
        entry = null;
        entryTargetPos = null;
        nextEntryScanTick = 0;
        badShores.clear();
        nextBadShoreForgetTick = BAD_SHORE_FORGET_TICKS;
        nextPathTick = 0;
        nextApproachTick = 0;
        clearRoute();
        nextRouteSearchTick = 0;
        exitFailedUntilTick = 0;
        airRouteBlocks = Double.POSITIVE_INFINITY;
        nextAirRouteTick = 0;
        stepOwned = false;
        stepEdge = null;
    }

    /** Lets go of a step this class has in flight (a pause, an abort, a change of mode): its keys are released. */
    void cancelStep(AIPlayerEntity bot) {
        if (stepOwned) {
            bot.getActionPack().cancelStep();
        }
        stepOwned = false;
        stepEdge = null;
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
            // depth-aware air level); make no move that could undo one of its steps.
            NavSafetyNet.INSTANCE.clearFollowSwim(bot);
            bot.getActionPack().stopMovement();
            clearRoute();
            return true;
        }
        NavSafetyNet.INSTANCE.renewFollowSwim(bot);

        double rate = lossRate(bot);
        double blocksToAir = submerged ? blocksToAir(bot, world, elapsed) : 0.0D;
        updateAscending(bot, air, rate, submerged, blocksToAir);
        if (ascending) {
            if (submerged) {
                return ascendWhileSubmerged(bot, world, elapsed, air, blocksToAir);
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
                                         double blocksToAir) {
        if (Double.isInfinite(blocksToAir)) {
            // No known way to breathe: never dive on. Hand a low-air bot to the rescue.
            if (air <= FollowOxygen.SURFACE_FLOOR_AIR) {
                NavSafetyNet.INSTANCE.requestWaterRescue(bot);
                NavSafetyNet.INSTANCE.clearFollowSwim(bot);
            }
            bot.getActionPack().stopMovement();
            return true;
        }
        return !ascendStep(bot, world, elapsed);
    }

    private void updateAscending(AIPlayerEntity bot, int air, double rate, boolean submerged, double blocksToAir) {
        if (ascending) {
            if (FollowOxygen.mayResumeDive(air, bot.getMaxAirSupply(), rate)) {
                ascending = false;
                BotLog.action(bot, "follow_swim_resume_dive", "air", air);
            }
            return;
        }
        if (FollowOxygen.shouldSurface(air, rate, blocksToAir)) {
            ascending = true;
            ascendCount++;
            BotLog.action(bot, "follow_swim_ascend",
                    "air", air,
                    "loss", String.format(Locale.ROOT, "%.3f", rate),
                    "blocks_to_air", Double.isInfinite(blocksToAir)
                            ? "none" : String.format(Locale.ROOT, "%.1f", blocksToAir),
                    "submerged", submerged);
        }
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
     * <p>Raw world reads (deliberate): the column scan looks only at the cells directly above the bot's own
     * head -- the water it is submerged in and the block that would stop it surfacing -- which is what a
     * swimmer physically feels and sees straight up. It feeds only an oxygen decision (when to head up for
     * air); it never selects a block, item or entity. The bounded route search is {@link SwimRoute}, whose
     * header documents the same reasoning.
     */
    private double blocksToAir(AIPlayerEntity bot, ServerLevel world, int elapsed) {
        BlockPos eye = BlockPos.containing(bot.getEyePosition());
        for (int dy = 0; dy <= VERTICAL_AIR_SCAN; dy++) {
            BlockPos cell = eye.above(dy);
            if (world.getFluidState(cell).is(FluidTags.WATER)) {
                continue;
            }
            if (!world.getBlockState(cell).getCollisionShape(world, cell).isEmpty()) {
                break;
            }
            return Math.max(0.0D, cell.getY() - bot.getEyeY() + 0.3D);
        }
        if (elapsed >= nextAirRouteTick) {
            nextAirRouteTick = elapsed + AIR_ROUTE_COOLDOWN_TICKS;
            Optional<List<BlockPos>> path = SwimRoute.search(world, bot.blockPosition(), bot.blockPosition(),
                    SwimRoute.Goal.AIR, 0.0D);
            airRouteBlocks = path.map(cells -> (double) cells.size()).orElse(Double.POSITIVE_INFINITY);
        }
        return airRouteBlocks;
    }

    /** One step toward air: straight up when the column is open, else along a water route to it. */
    private boolean ascendStep(AIPlayerEntity bot, ServerLevel world, int elapsed) {
        BlockPos above = bot.blockPosition().above();
        if (isSafeSwimCell(world, above) && beginStep(bot, above, "follow_swim_surface")) {
            clearRoute();
            return true;
        }
        if (route == null || routeGoal != SwimRoute.Goal.AIR || elapsed >= routeExpiryTick) {
            if (elapsed < nextRouteSearchTick) {
                return false;
            }
            searchRoute(bot, world, bot.blockPosition(), SwimRoute.Goal.AIR, elapsed, 0.0D);
        }
        return stepAlongRoute(bot, "follow_swim_surface");
    }

    // ---- swimming after the player -----------------------------------------------------------

    private boolean swimStepToward(AIPlayerEntity bot, ServerPlayer target, boolean allowDown) {
        ServerLevel world = bot.level();
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
        return choices.stream()
                .filter(candidate -> isSafeSwimCell(world, candidate))
                .filter(candidate -> candidate.distSqr(goal) + 0.01D < before)
                .sorted(Comparator.comparingDouble(candidate -> candidate.distSqr(goal)))
                .anyMatch(candidate -> beginStep(bot, candidate, "follow_swim"));
    }

    /** A greedy step is blocked (an island, a wall): follow a bounded water route around it. */
    private boolean routeStepToward(AIPlayerEntity bot, ServerPlayer target, int elapsed) {
        ServerLevel world = bot.level();
        if (route == null || routeGoal != SwimRoute.Goal.APPROACH || elapsed >= routeExpiryTick) {
            if (elapsed < nextRouteSearchTick) {
                return false;
            }
            searchRoute(bot, world, target.blockPosition(), SwimRoute.Goal.APPROACH, elapsed, 0.0D);
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
            NavSafetyNet.INSTANCE.clearFollowSwim(bot);
            bot.getActionPack().stopMovement();
            clearRoute();
            waiting = true;
            return true;
        }
        if (elapsed < exitFailedUntilTick) {
            return false;
        }
        NavSafetyNet.INSTANCE.renewFollowSwim(bot);
        if (submerged) {
            // Heading for land does not excuse holding one's breath: honour the same follow-first air
            // floor / early-ascent rule as swimming after the player.
            double blocksToAir = blocksToAir(bot, world, elapsed);
            updateAscending(bot, air, lossRate(bot), true, blocksToAir);
            if (ascending) {
                waiting = ascendWhileSubmerged(bot, world, elapsed, air, blocksToAir);
                return true;
            }
        }
        if (route == null || routeGoal != SwimRoute.Goal.EXIT || elapsed >= routeExpiryTick) {
            if (elapsed < nextRouteSearchTick) {
                waiting = true;
                return true;
            }
            searchRoute(bot, world, target.blockPosition(), SwimRoute.Goal.EXIT, elapsed, standoff);
            if (route == null) {
                exitFailedUntilTick = elapsed + 40;
                return false;
            }
        }
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

    private Entry currentEntry(AIPlayerEntity bot, ServerPlayer target, int elapsed) {
        ServerLevel world = bot.level();
        if (elapsed >= nextBadShoreForgetTick) {
            badShores.clear();
            nextBadShoreForgetTick = elapsed + BAD_SHORE_FORGET_TICKS;
        }
        if (entry != null && entryTargetPos != null
                && target.blockPosition().distSqr(entryTargetPos) <= ENTRY_RETARGET_DISTANCE_SQUARED
                && BoatSupport.isWater(world, entry.water())
                && NavSafetyNet.isDryStandableCell(world, entry.shore())) {
            return entry;
        }
        entry = null;
        if (elapsed < nextEntryScanTick) {
            return null;
        }
        nextEntryScanTick = elapsed + ENTRY_SCAN_COOLDOWN_TICKS;
        entry = findEntry(bot, target);
        entryTargetPos = target.blockPosition();
        if (entry != null) {
            BotLog.action(bot, "follow_swim_water_edge", "shore", entry.shore().toShortString(),
                    "water", entry.water().toShortString());
        }
        return entry;
    }

    /**
     * Nearest observable open-water cell with a dry, standable neighbour to step in from, weighted
     * toward the player. Cheap block-state tests first; the raycast visibility check only runs for
     * the few best candidates.
     */
    private Entry findEntry(AIPlayerEntity bot, ServerPlayer target) {
        ServerLevel world = bot.level();
        BlockPos origin = bot.blockPosition();
        BlockPos goal = target.blockPosition();
        Standability.clearCache();
        record Scored(Entry entry, double cost) {
        }
        List<Scored> candidates = new ArrayList<>();
        for (BlockPos cell : BlockPos.betweenClosed(
                origin.offset(-ENTRY_RADIUS, -ENTRY_DOWN, -ENTRY_RADIUS),
                origin.offset(ENTRY_RADIUS, ENTRY_UP, ENTRY_RADIUS))) {
            if (!BoatSupport.isWater(world, cell)) {
                continue;
            }
            BlockPos above = cell.above();
            if (!world.getFluidState(above).isEmpty()
                    || !world.getBlockState(above).getCollisionShape(world, above).isEmpty()
                    || Standability.isDangerous(world.getBlockState(cell))) {
                continue;
            }
            BlockPos water = cell.immutable();
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                for (int rise = 0; rise <= 1; rise++) {
                    BlockPos shore = water.relative(direction).above(rise);
                    if (badShores.contains(shore) || !NavSafetyNet.isDryStandableCell(world, shore)) {
                        continue;
                    }
                    double cost = Math.sqrt(shore.distSqr(origin))
                            + 0.75D * Math.sqrt(water.distSqr(goal));
                    candidates.add(new Scored(new Entry(shore.immutable(), water), cost));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(Scored::cost));
        int checked = 0;
        for (Scored scored : candidates) {
            if (checked++ >= ENTRY_CANDIDATES_CHECKED) {
                break;
            }
            if (BoatSupport.canObserveWater(bot, scored.entry().water())) {
                return scored.entry();
            }
        }
        return null;
    }

    private void markBad(Entry failed) {
        badShores.add(failed.shore());
        entry = null;
    }

    private void clearEntry() {
        entry = null;
    }

    private static boolean canStepInto(BlockPos from, BlockPos to) {
        int dx = Math.abs(to.getX() - from.getX());
        int dy = Math.abs(to.getY() - from.getY());
        int dz = Math.abs(to.getZ() - from.getZ());
        int axes = (dx == 0 ? 0 : 1) + (dy == 0 ? 0 : 1) + (dz == 0 ? 0 : 1);
        return dx <= 1 && dy <= 1 && dz <= 1 && axes >= 1 && axes <= 2;
    }

    // ---- water routes ------------------------------------------------------------------------

    private void searchRoute(AIPlayerEntity bot, ServerLevel world, BlockPos target,
                             SwimRoute.Goal goal, int elapsed, double standoff) {
        nextRouteSearchTick = elapsed + ROUTE_COOLDOWN_TICKS;
        routeSearches++;
        Standability.clearCache();
        Optional<List<BlockPos>> found = SwimRoute.search(world, bot.blockPosition(), target, goal, standoff);
        if (found.isPresent() && !found.get().isEmpty()) {
            route = found.get();
            routeStart = bot.blockPosition().immutable();
            routeGoal = goal;
            routeExpiryTick = elapsed + ROUTE_LIFETIME_TICKS;
            routeFailures = 0;
        } else {
            clearRoute();
        }
    }

    private boolean stepAlongRoute(AIPlayerEntity bot, String reason) {
        if (route == null) {
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
        WalkedStep.Kind kind = NavSafetyNet.stepKindTo(bot, cell);
        if (kind == null || WalkedStep.refusal(bot, cell, kind) != null) {
            return false;
        }
        bot.getActionPack().runStep(WalkedStep.begin(bot, cell, kind, reason));
        stepOwned = true;
        return true;
    }

    /**
     * Whether a step this class started is still running. When it has just ended, its outcome is taken in: a failed entry marks its
     * shore bad and a failed route step counts against the route (three drop it), as a refused step used to.
     */
    private boolean stepInFlight(AIPlayerEntity bot) {
        if (!stepOwned) {
            return false;
        }
        var pack = bot.getActionPack();
        if (!pack.stepIdle()) {
            return true;
        }
        stepOwned = false;
        WalkedStep.Result result = pack.stepResult();
        if (result != null && result.failed()) {
            if (stepEdge != null) {
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
        if (!stepInFlight(bot)) {
            return false;
        }
        if (holdStep(bot)) {
            waiting = true;
            return true;
        }
        if (!ascending && bot.isUnderWater()
                && FollowOxygen.shouldSurface(bot.getAirSupply(), lossRate(bot), blocksToAir(bot, world, elapsed))) {
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
            NavSafetyNet.INSTANCE.clearFollowSwim(bot);
            bot.getActionPack().stopMovement();
            clearRoute();
            return true;
        }
        NavSafetyNet.INSTANCE.renewFollowSwim(bot);
        return false;
    }

    /** Follow only yields while submerged; a low-air swimmer who has reached the surface may refill normally. */
    private static boolean mustYieldToWaterRescue(AIPlayerEntity bot) {
        return bot.isUnderWater() && NavSafetyNet.followSwimMustYield(bot);
    }

    private void clearRoute() {
        route = null;
        routeStart = null;
        routeGoal = null;
        routeFailures = 0;
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
    private static boolean needsWaterExit(AIPlayerEntity bot, ServerPlayer target, double standoff) {
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
        return !hasDryFootingWithinOneStep(world, feet);
    }

    /** A dry, standable cell one step away (same level, one up, one down) that land follow's start snap can reach. */
    static boolean hasDryFootingWithinOneStep(ServerLevel world, BlockPos feet) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if ((dx == 0 && dz == 0 && dy == 0) || (dy != 0 && Math.abs(dx) + Math.abs(dz) > 1)) {
                        continue;
                    }
                    if (Standability.isStandableFresh(world, feet.offset(dx, dy, dz))) {
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

    static boolean isSafeSwimCell(ServerLevel world, BlockPos pos) {
        if (!isSwimCell(world, pos)
                || !world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()
                || !world.getBlockState(pos.above()).getCollisionShape(world, pos.above()).isEmpty()) {
            return false;
        }
        return !world.getFluidState(pos).is(FluidTags.LAVA)
                && !world.getFluidState(pos.above()).is(FluidTags.LAVA);
    }
}
