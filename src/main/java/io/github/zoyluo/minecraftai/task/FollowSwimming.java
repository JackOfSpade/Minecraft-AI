package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mode.FakePlayerMotion;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The swimming half of {@link FollowTask}: everything the bot does while the player it follows is
 * in the water, and while it climbs back out after them.
 *
 * <ul>
 *   <li><b>Entering.</b> A bot on land walks to the nearest reachable, observable water edge (plain
 *       adjacent water is enough -- no boat launch-site pairing) and steps in with the same
 *       collision-validated one-cell primitive the safety net uses. The edge search is throttled
 *       and its result reused while it stays valid.</li>
 *   <li><b>Swimming.</b> Verified one-cell swim steps toward the player, greedy first and a bounded
 *       water BFS ({@link SwimRoute}) when a greedy step is blocked. Following a diver goes down
 *       with them.</li>
 *   <li><b>Oxygen.</b> Follow owns the ascent while its {@link NavSafetyNet} lease is valid: it
 *       measures the bot's real air loss ({@link FollowOxygen.LossEstimator}) and turns up for
 *       breath early enough (see {@link FollowOxygen#shouldSurface}); it stays up until the lungs are
 *       nearly full. Below {@link FollowOxygen#RESCUE_AIR} it makes no move at all so the drowning
 *       rescue is never undone.</li>
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

    private double airRouteBlocks = Double.POSITIVE_INFINITY;
    private int nextAirRouteTick;

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
    }

    boolean isWaiting() {
        return waiting;
    }

    /** True while the bot is in (or touching) water, i.e. this class owns its movement. */
    static boolean inWater(AIPlayerEntity bot) {
        return isSwimCell(bot.getEntityWorld(), bot.getBlockPos());
    }

    // ---- following a swimming player ---------------------------------------------------------

    /**
     * One tick of following a waterborne player.
     *
     * @return true when the bot deliberately made no progress this tick (waiting)
     */
    boolean follow(AIPlayerEntity bot, ServerPlayerEntity target, int elapsed, double stopDistance) {
        ServerWorld world = bot.getEntityWorld();
        observeAir(bot, world);
        if (!isSwimCell(world, bot.getBlockPos())) {
            waiting = enterWater(bot, target, elapsed, stopDistance);
            return waiting;
        }
        clearEntry();
        waiting = swimAfter(bot, target, elapsed, stopDistance);
        return waiting;
    }

    private boolean swimAfter(AIPlayerEntity bot, ServerPlayerEntity target, int elapsed, double stopDistance) {
        ServerWorld world = bot.getEntityWorld();
        boolean submerged = bot.isSubmergedInWater();
        int air = bot.getAir();
        if (submerged && air <= FollowOxygen.RESCUE_AIR) {
            // NavSafetyNet's drowning rescue owns the bot from here (its lease ends at this air
            // level); make no move that could undo one of its steps.
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

    private void updateAscending(AIPlayerEntity bot, int air, double rate, boolean submerged, double blocksToAir) {
        if (ascending) {
            if (FollowOxygen.mayResumeDive(air, bot.getMaxAir(), rate)) {
                ascending = false;
                BotLog.action(bot, "follow_swim_resume_dive", "air", air);
            }
            return;
        }
        if (FollowOxygen.shouldSurface(air, rate, blocksToAir)) {
            ascending = true;
            BotLog.action(bot, "follow_swim_ascend",
                    "air", air,
                    "loss", String.format(Locale.ROOT, "%.3f", rate),
                    "blocks_to_air", Double.isInfinite(blocksToAir)
                            ? "none" : String.format(Locale.ROOT, "%.1f", blocksToAir),
                    "submerged", submerged);
        }
    }

    // ---- oxygen ------------------------------------------------------------------------------

    private void observeAir(AIPlayerEntity bot, ServerWorld world) {
        loss.observe(bot.getAir(), bot.isSubmergedInWater(), world.getServer().getTicks());
    }

    /** Air lost per tick while submerged: zero under a long breathing effect, otherwise measured. */
    private double lossRate(AIPlayerEntity bot) {
        StatusEffectInstance water = bot.getStatusEffect(StatusEffects.WATER_BREATHING);
        if (water != null && FollowOxygen.effectCoversDive(water.isInfinite(), water.getDuration())) {
            return 0.0D;
        }
        StatusEffectInstance conduit = bot.getStatusEffect(StatusEffects.CONDUIT_POWER);
        if (conduit != null && FollowOxygen.effectCoversDive(conduit.isInfinite(), conduit.getDuration())) {
            return 0.0D;
        }
        return loss.lossPerTick();
    }

    /**
     * Distance the bot must swim to breathe: the free water column above its head when there is one,
     * otherwise the length of a bounded water route to the nearest cell with air (throttled).
     */
    private double blocksToAir(AIPlayerEntity bot, ServerWorld world, int elapsed) {
        BlockPos eye = BlockPos.ofFloored(bot.getEyePos());
        for (int dy = 0; dy <= VERTICAL_AIR_SCAN; dy++) {
            BlockPos cell = eye.up(dy);
            if (world.getFluidState(cell).isIn(FluidTags.WATER)) {
                continue;
            }
            if (!world.getBlockState(cell).getCollisionShape(world, cell).isEmpty()) {
                break;
            }
            return Math.max(0.0D, cell.getY() - bot.getEyeY() + 0.3D);
        }
        if (elapsed >= nextAirRouteTick) {
            nextAirRouteTick = elapsed + AIR_ROUTE_COOLDOWN_TICKS;
            Optional<List<BlockPos>> path = SwimRoute.search(world, bot.getBlockPos(), bot.getBlockPos(),
                    SwimRoute.Goal.AIR, 0.0D);
            airRouteBlocks = path.map(cells -> (double) cells.size()).orElse(Double.POSITIVE_INFINITY);
        }
        return airRouteBlocks;
    }

    /** One step toward air: straight up when the column is open, else along a water route to it. */
    private boolean ascendStep(AIPlayerEntity bot, ServerWorld world, int elapsed) {
        BlockPos above = bot.getBlockPos().up();
        if (isSafeSwimCell(world, above)
                && FakePlayerMotion.swimStepTo(bot, above, "follow_swim_surface")) {
            clearRoute();
            return true;
        }
        if (route == null || routeGoal != SwimRoute.Goal.AIR || elapsed >= routeExpiryTick) {
            if (elapsed < nextRouteSearchTick) {
                return false;
            }
            searchRoute(bot, world, bot.getBlockPos(), SwimRoute.Goal.AIR, elapsed, 0.0D);
        }
        return stepAlongRoute(bot, "follow_swim_surface");
    }

    // ---- swimming after the player -----------------------------------------------------------

    private boolean swimStepToward(AIPlayerEntity bot, ServerPlayerEntity target, boolean allowDown) {
        ServerWorld world = bot.getEntityWorld();
        BlockPos current = bot.getBlockPos();
        BlockPos goal = target.getBlockPos();
        double before = current.getSquaredDistance(goal);
        List<BlockPos> choices = new ArrayList<>(6);
        for (Direction direction : Direction.Type.HORIZONTAL) {
            choices.add(current.offset(direction));
        }
        choices.add(current.up());
        if (allowDown) {
            choices.add(current.down());
        }
        return choices.stream()
                .filter(candidate -> isSafeSwimCell(world, candidate))
                .filter(candidate -> candidate.getSquaredDistance(goal) + 0.01D < before)
                .sorted(Comparator.comparingDouble(candidate -> candidate.getSquaredDistance(goal)))
                .anyMatch(candidate -> FakePlayerMotion.swimStepTo(bot, candidate, "follow_swim"));
    }

    /** A greedy step is blocked (an island, a wall): follow a bounded water route around it. */
    private boolean routeStepToward(AIPlayerEntity bot, ServerPlayerEntity target, int elapsed) {
        ServerWorld world = bot.getEntityWorld();
        if (route == null || routeGoal != SwimRoute.Goal.APPROACH || elapsed >= routeExpiryTick) {
            if (elapsed < nextRouteSearchTick) {
                return false;
            }
            searchRoute(bot, world, target.getBlockPos(), SwimRoute.Goal.APPROACH, elapsed, 0.0D);
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
    boolean exitWaterForLand(AIPlayerEntity bot, ServerPlayerEntity target, int elapsed, double standoff) {
        ServerWorld world = bot.getEntityWorld();
        observeAir(bot, world);
        if (!isSwimCell(world, bot.getBlockPos())) {
            clearRoute();
            return false;
        }
        if (elapsed < exitFailedUntilTick) {
            return false;
        }
        if (bot.isSubmergedInWater() && bot.getAir() <= FollowOxygen.RESCUE_AIR) {
            NavSafetyNet.INSTANCE.clearFollowSwim(bot);
            bot.getActionPack().stopMovement();
            clearRoute();
            waiting = true;
            return true;
        }
        NavSafetyNet.INSTANCE.renewFollowSwim(bot);
        if (route == null || routeGoal != SwimRoute.Goal.EXIT || elapsed >= routeExpiryTick) {
            if (elapsed < nextRouteSearchTick) {
                waiting = true;
                return true;
            }
            searchRoute(bot, world, target.getBlockPos(), SwimRoute.Goal.EXIT, elapsed, standoff);
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
    private boolean enterWater(AIPlayerEntity bot, ServerPlayerEntity target, int elapsed, double stopDistance) {
        clearRoute();
        if (ascending) {
            if (!FollowOxygen.mayResumeDive(bot.getAir(), bot.getMaxAir(), lossRate(bot))) {
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
        if (canStepInto(bot.getBlockPos(), edge.water())) {
            NavSafetyNet.INSTANCE.renewFollowSwim(bot);
            bot.getActionPack().stopAll();
            boolean moved = FakePlayerMotion.swimStepTo(bot, edge.water(), "follow_swim_enter");
            if (!moved) {
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
                bot.getActionPack().startWalkTo(edge.shore().toCenterPos(), 1.0D);
            }
            nextPathTick = elapsed + PATH_REPATH_TICKS;
        }
        return false;
    }

    /** No water edge in sight: close in on the swimmer over dry land so water comes into view. */
    private boolean approachOnLand(AIPlayerEntity bot, ServerPlayerEntity target, int elapsed) {
        if (elapsed < nextApproachTick) {
            return !bot.getActionPack().hasActiveActions();
        }
        nextApproachTick = elapsed + APPROACH_COOLDOWN_TICKS;
        if (elapsed % 200 == 1) {
            BotLog.action(bot, "follow_swim_no_water_edge");
        }
        BlockPos destination = Standability.findNearestStandable(
                bot.getEntityWorld(), target.getBlockPos(), APPROACH_SHORE_RADIUS, 8, 4).orElse(null);
        if (destination == null) {
            bot.getActionPack().stopMovement();
            return true;
        }
        ActionResult path = bot.getActionPack().startPathTo(destination);
        return path.isFailed();
    }

    private Entry currentEntry(AIPlayerEntity bot, ServerPlayerEntity target, int elapsed) {
        ServerWorld world = bot.getEntityWorld();
        if (elapsed >= nextBadShoreForgetTick) {
            badShores.clear();
            nextBadShoreForgetTick = elapsed + BAD_SHORE_FORGET_TICKS;
        }
        if (entry != null && entryTargetPos != null
                && target.getBlockPos().getSquaredDistance(entryTargetPos) <= ENTRY_RETARGET_DISTANCE_SQUARED
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
        entryTargetPos = target.getBlockPos();
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
    private Entry findEntry(AIPlayerEntity bot, ServerPlayerEntity target) {
        ServerWorld world = bot.getEntityWorld();
        BlockPos origin = bot.getBlockPos();
        BlockPos goal = target.getBlockPos();
        Standability.clearCache();
        record Scored(Entry entry, double cost) {
        }
        List<Scored> candidates = new ArrayList<>();
        for (BlockPos cell : BlockPos.iterate(
                origin.add(-ENTRY_RADIUS, -ENTRY_DOWN, -ENTRY_RADIUS),
                origin.add(ENTRY_RADIUS, ENTRY_UP, ENTRY_RADIUS))) {
            if (!BoatSupport.isWater(world, cell)) {
                continue;
            }
            BlockPos above = cell.up();
            if (!world.getFluidState(above).isEmpty()
                    || !world.getBlockState(above).getCollisionShape(world, above).isEmpty()
                    || Standability.isDangerous(world.getBlockState(cell))) {
                continue;
            }
            BlockPos water = cell.toImmutable();
            for (Direction direction : Direction.Type.HORIZONTAL) {
                for (int rise = 0; rise <= 1; rise++) {
                    BlockPos shore = water.offset(direction).up(rise);
                    if (badShores.contains(shore) || !NavSafetyNet.isDryStandableCell(world, shore)) {
                        continue;
                    }
                    double cost = Math.sqrt(shore.getSquaredDistance(origin))
                            + 0.75D * Math.sqrt(water.getSquaredDistance(goal));
                    candidates.add(new Scored(new Entry(shore.toImmutable(), water), cost));
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

    private void searchRoute(AIPlayerEntity bot, ServerWorld world, BlockPos target,
                             SwimRoute.Goal goal, int elapsed, double standoff) {
        nextRouteSearchTick = elapsed + ROUTE_COOLDOWN_TICKS;
        Standability.clearCache();
        Optional<List<BlockPos>> found = SwimRoute.search(world, bot.getBlockPos(), target, goal, standoff);
        if (found.isPresent() && !found.get().isEmpty()) {
            route = found.get();
            routeStart = bot.getBlockPos().toImmutable();
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
        ServerWorld world = bot.getEntityWorld();
        BlockPos feet = bot.getBlockPos();
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
        boolean moved = NavSafetyNet.isDryStandableCell(world, cell)
                ? FakePlayerMotion.stepToStandable(bot, cell, reason)
                : FakePlayerMotion.swimStepTo(bot, cell, reason);
        if (!moved && ++routeFailures >= 3) {
            clearRoute();
        }
        return moved;
    }

    private void clearRoute() {
        route = null;
        routeStart = null;
        routeGoal = null;
        routeFailures = 0;
    }

    // ---- cell predicates ---------------------------------------------------------------------

    static boolean isSwimCell(ServerWorld world, BlockPos pos) {
        return BoatSupport.isWater(world, pos) || BoatSupport.isWater(world, pos.up());
    }

    static boolean isSafeSwimCell(ServerWorld world, BlockPos pos) {
        if (!isSwimCell(world, pos)
                || !world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()
                || !world.getBlockState(pos.up()).getCollisionShape(world, pos.up()).isEmpty()) {
            return false;
        }
        return !world.getFluidState(pos).isIn(FluidTags.LAVA)
                && !world.getFluidState(pos.up()).isIn(FluidTags.LAVA);
    }
}
