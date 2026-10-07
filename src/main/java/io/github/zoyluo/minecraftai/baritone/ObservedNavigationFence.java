package io.github.zoyluo.minecraftai.baritone;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mining.assist.BotEdits;
import io.github.zoyluo.minecraftai.mining.assist.ObservedGraphSearch;
import io.github.zoyluo.minecraftai.mining.assist.RayGrid;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.navigation.NavRoute;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import io.github.zoyluo.minecraftai.perception.SharedWorldSight;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Immutable, per-bot evidence boundary supplied to the patched Baritone world accessor.
 *
 * <p>A cell enters this snapshot only when a ray from the bot's eye traversed it, when it was the
 * first cell struck by such a ray, when the bot can directly prove that one local cell is in
 * view, or as the confirmed result of this bot's own action at an already admitted cell. The
 * snapshot stores the state seen at that time; it never authorises a later raw-world reread of
 * an occluded cell. Cells absent from it are deliberately impassable in Baritone.
 * Construction is server-thread-only; reads are pure array lookups safe for Baritone workers.</p>
 */
public final class ObservedNavigationFence {
    /** The small, bounded memory makes a previously exposed target useful without creating a map scanner. */
    public static final int MEMORY_TTL_TICKS = 6_000;
    /** Enough for several local view cones, while keeping each immutable worker snapshot small. */
    public static final int MAX_CELLS = 8_192;
    /** View rays used for a fresh route admission. */
    static final int ADMISSION_RAYS = 72;
    /** View rays added as a driven route advances. */
    static final int REFRESH_RAYS = 24;
    /** A running route acquires a fresh view cone at most this often. */
    static final int REFRESH_INTERVAL_TICKS = 4;
    /**
     * A render-distance landmark can be nominated from far away, but every individual Baritone
     * leg remains a modest observed segment. Repeated legs still walk directly toward the same
     * live landmark without rebuilding a multi-thousand-cell corridor every refresh.
     */
    private static final int VISIBLE_LANDMARK_MAX_HOP_RANGE = 24;
    /** A running visible-landmark leg only extends fresh evidence a short distance ahead. */
    private static final int VISIBLE_LANDMARK_REFRESH_LOOKAHEAD = 12;
    /** Full landmark-corridor refreshes are deliberately less frequent than local routes. */
    private static final int VISIBLE_LANDMARK_REFRESH_INTERVAL_TICKS = 12;
    /** Keep a short live landmark leg from inheriting a full route-evidence cache. */
    private static final int VISIBLE_LANDMARK_SHARED_SIGHT_LIMIT = 256;
    /** Parallel, individually visible walking strips captured toward a proven target stance. */
    private static final int BASE_CORRIDOR_HALF_WIDTH = 2;
    /** Baritone validates a movement's source/destination headroom up to three cells above its feet. */
    private static final int NAVIGATION_HEADROOM = 3;
    /**
     * The only directional-pursuit label permitted to use the factual tracked render range.
     * Ordinary follow/search pursuits deliberately retain the configured small local horizon.
     */
    private static final String VISIBLE_LANDMARK_PURSUIT = "visible_landmark_pursuit";
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final ObservedNavigationFence EMPTY = new ObservedNavigationFence("", Integer.MIN_VALUE,
            0L, 0, new long[0], new BlockState[0], new int[0]);

    /** Why a coordinate is allowed to influence this particular route. */
    public enum TargetProvenance {
        /** The target was re-observed during this route admission. */
        LIVE,
        /** The target was genuinely seen earlier by this bot; no terrain changes may be inferred from that memory. */
        REMEMBERED,
        /** An approach/flee goal is a visible entity direction rather than a block target. */
        DIRECTION_ONLY
    }

    /** Result of a route admission or a view refresh. */
    public record Capture(ObservedNavigationFence fence, boolean accepted, String failure,
                          TargetProvenance provenance, int rays, int freshCells, boolean pillarGoal,
                          BlockPos pillarBase) {
        public Capture {
            pillarBase = pillarBase == null ? null : pillarBase.immutable();
        }

        static Capture refused(String failure, int rays, int freshCells) {
            return new Capture(EMPTY, false, failure, TargetProvenance.DIRECTION_ONLY, rays, freshCells, false, null);
        }
    }

    private record Cell(BlockState state, int seenTick) {
        private Cell {
            state = Objects.requireNonNull(state, "state");
        }
    }

    private final String dimension;
    private final int minimumY;
    private final long generation;
    private final int lastObservationTick;
    /** Sorted packed BlockPos values; state/tick arrays have the same index. */
    private final long[] cells;
    private final BlockState[] states;
    private final int[] seenTicks;

    private ObservedNavigationFence(String dimension, int minimumY, long generation, int lastObservationTick,
                                    long[] cells, BlockState[] states, int[] seenTicks) {
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        this.minimumY = minimumY;
        this.generation = generation;
        this.lastObservationTick = lastObservationTick;
        this.cells = cells;
        this.states = states;
        this.seenTicks = seenTicks;
    }

    /** No cell is known: the safe default before a route has been admitted. */
    public static ObservedNavigationFence empty() {
        return EMPTY;
    }

    public String dimension() {
        return dimension;
    }

    public long generation() {
        return generation;
    }

    public int cellCount() {
        return cells.length;
    }

    public int lastObservationTick() {
        return lastObservationTick;
    }

    /** Pure, worker-safe predicate used by patched Baritone before any terrain/cache lookup. */
    public boolean allows(int x, int y, int z) {
        return y >= minimumY && indexOf(BlockPos.asLong(x, y, z)) >= 0;
    }

    /**
     * The exact state previously observed at this cell, or null when no read is allowed. This lets
     * a remembered route use memory rather than secretly learn whether an out-of-sight block changed.
     */
    public BlockState stateAt(int x, int y, int z) {
        if (y < minimumY) {
            return null;
        }
        int index = indexOf(BlockPos.asLong(x, y, z));
        return index < 0 ? null : states[index];
    }

    public BlockState stateAt(BlockPos pos) {
        return stateAt(pos.getX(), pos.getY(), pos.getZ());
    }

    public boolean seenThisTick(BlockPos pos, int tick) {
        int index = indexOf(pos.asLong());
        return index >= 0 && seenTicks[index] == tick;
    }

    /**
     * Replaces one already-authorised cell with the result of this bot's own successful world
     * action.  This is deliberately not an observation API: callers may not extend the fence or
     * inspect a neighbouring cell with it.  It exists so an active bridge/pillar can recognise
     * the block it just placed instead of treating the immutable pre-action AIR snapshot as live
     * terrain on its next movement tick.
     */
    ObservedNavigationFence withTrustedActionResult(BlockPos pos, BlockState state, int tick) {
        if (pos == null || state == null || !allows(pos.getX(), pos.getY(), pos.getZ())) {
            return this;
        }
        Map<Long, Cell> updated = new HashMap<>();
        for (int i = 0; i < cells.length; i++) {
            updated.put(cells[i], new Cell(states[i], seenTicks[i]));
        }
        put(updated, pos.asLong(), state, tick);
        return freeze(dimension, minimumY, generation + 1L, Math.max(lastObservationTick, tick), updated);
    }

    /** The nearest all-observed standable stance at or next to a target, or null without one. */
    public BlockPos nearestObservedStance(BlockPos target) {
        // A vine/ladder is a real Baritone endpoint even though it has no collision floor. Its
        // state was already earned by the immutable fence; returning the target does not make a
        // dry standability claim or read live terrain.
        return isObservedClimbable(this, target) ? target.immutable() : nearestStandable(this, target);
    }

    /**
     * Builds a new evidence snapshot and admits the requested goal. A BLOCK goal has to be live
     * evidence or a bounded remembered observation connected by an all-observed walking corridor.
     * Entity-style NEAR/RUN_AWAY goals may supply direction, but terrain still remains fenced.
     */
    public static Capture admit(AIPlayerEntity bot, NavRoute route, ObservedNavigationFence previous,
                                long generation) {
        Objects.requireNonNull(bot, "bot");
        Objects.requireNonNull(route, "route");
        int tick = bot.getServer().getTickCount();
        String dimension = BotEdits.dimensionKey(bot.level());
        // A visible-landmark route is a short live-sight leg, not a map-memory route. Starting
        // it from fresh local evidence bounds the snapshot even when an unrelated previous
        // route happened to fill its retained cache, and avoids copying and re-trimming that cache
        // while retaining only terrain that the bot can currently re-prove.
        Map<Long, Cell> observed = isVisibleLandmarkPursuit(route)
                ? new HashMap<>() : retained(previous, dimension, tick);
        int before = observed.size();
        BlockPos feet = navigationFeet(bot);
        seedBodyEnvelope(bot, observed, tick);
        // A route carrying the render-distance landmark label gets a larger view budget than
        // ordinary directional pursuit. Re-prove that exact marker here too, rather than
        // trusting its public label or retained memory. This runs before any terrain capture
        // and therefore cannot turn a stale landmark into a route heading.
        if (isVisibleLandmarkPursuit(route)
                && !ObservableWorldQuery.canObserveBlock(bot, route.target())) {
            return Capture.refused("visible_landmark_unobserved", 0,
                    Math.max(0, observed.size() - before));
        }
        mergeSharedWorldSight(bot, route.target(), observed, tick,
                isVisibleLandmarkPursuit(route) ? VISIBLE_LANDMARK_SHARED_SIGHT_LIMIT : Integer.MAX_VALUE);
        if (route.shape() == NavRoute.Shape.OWNER_FOLLOW) {
            // A live coordinate of this bot's already-verified owner is intentionally not a terrain observation. The navigator
            // separately validates the UUID and installs a server-captured full-chunk snapshot in ServerPlayerContext; the only
            // ordinary terrain evidence merged here is the owner's own line-of-sight rays, never a global allow list.
            ObservedNavigationFence candidate = freeze(dimension, route.minimumY(), generation, tick, observed);
            return new Capture(candidate, true, "", TargetProvenance.DIRECTION_ONLY, 0,
                    Math.max(0, observed.size() - before), false, null);
        }
        int rays = scan(bot, observed, route.target(), ADMISSION_RAYS, tick);

        // A target whose cell can presently be seen gets a small, individually-ray-proven stance
        // envelope. This is not a cube scan: every added cell has its own line-of-sight proof.
        boolean waterTraversal = route.options().allowWater();
        boolean exactWaterGoal = route.options().exactWaterGoal();
        boolean liveTarget = observeRouteCellIfVisible(bot, route.target(), waterTraversal, observed, tick);
        if (liveTarget) {
            if (exactWaterGoal) {
                observeWaterEnvelope(bot, route.target(), observed, tick);
            } else {
                // A swimmer can visibly prove a dry shore through water.  Its stance evidence
                // must use the same fluid-transparent player view as its target-cell proof;
                // solid terrain still blocks every individual ray.
                observeStandingEnvelope(bot, route.target(), waterTraversal, observed, tick);
            }
        }

        TargetProvenance provenance = TargetProvenance.DIRECTION_ONLY;
        if (route.shape() == NavRoute.Shape.BLOCK) {
            Cell target = observed.get(route.target().asLong());
            if (target == null) {
                return Capture.refused("navigation_goal_unobserved", rays, Math.max(0, observed.size() - before));
            }
            provenance = liveTarget || target.seenTick() == tick ? TargetProvenance.LIVE : TargetProvenance.REMEMBERED;
            if (exactWaterGoal) {
                // An explicit swim goal is satisfied in its actual observed water/shore cell, not at an
                // invented dry stance beside it.  The corridor captures each visible water
                // column through fluids, so Baritone can swim only in water it can truly see.
                observeVisibleCorridors(bot, feet, route.target(), true, observed, tick);
                ObservedNavigationFence candidate = freeze(dimension, route.minimumY(), generation, tick, observed);
                return new Capture(candidate, true, "", provenance, rays, Math.max(0, observed.size() - before), false, null);
            }
            ObservedNavigationFence candidate = freeze(dimension, route.minimumY(), generation, tick, observed);
            // Do not snap a visibly observed vine/ladder goal back to the dry platform beside it:
            // Baritone must receive the climbable cell so it can take the one visible descent or
            // ascent movement. The subsequent corridor capture still independently proves every
            // cell the planner may inspect.
            boolean climbableGoal = isObservedClimbable(candidate, route.target());
            // An explicit pillar route never snaps either: its goal is the top of a column it has to
            // build, so any standable cell within one block of it (the bot's own cell for a one-block
            // pillar, a step of terrain beside the column) would end the route before a block is placed.
            BlockPos stance = climbableGoal ? route.target().immutable()
                    : route.requiresPillarPlacementColumn() ? null : nearestStandable(candidate, route.target());
            if (stance == null) {
                // A vertical build goal begins unsupported. Admit it only when the actual goal is
                // visible now and a real, dry base plus every cell of the future pillar/body
                // column was individually observed. This never infers a floor or an unseen height.
                BlockPos pillarBase = route.options().allowPlace() && liveTarget && route.returnAnchor() == null
                        ? observePillarColumn(bot, feet, route.target(), observed, tick) : null;
                boolean columnClear = false;
                boolean walkProved = false;
                if (pillarBase != null) {
                    // The walk to the column's foot is proved like the walk to any other stance: lanes toward it,
                    // each cell by its own ray. Without them the bot gets to the foot only over whatever its eyes
                    // happened to cross earlier, which depends on where it had been looking (over the same open
                    // floor a foot seven cells away on each axis was admitted, eight cells away refused every time).
                    observePillarWalk(bot, feet, pillarBase, observed, tick);
                    candidate = freeze(dimension, route.minimumY(), generation, tick, observed);
                    columnClear = isObservedPillarColumn(candidate, pillarBase, route.target());
                    walkProved = columnClear
                            && ObservedGraphSearch.path(feet, pillarBase, new SnapshotEnvironment(candidate)) != null;
                    if (walkProved) {
                        return new Capture(candidate, true, "", provenance, rays,
                                Math.max(0, observed.size() - before), true, pillarBase);
                    }
                }
                // One refusal covers several proofs that can fail; the log says which one did.
                BotLog.path(bot, "nav_pillar_goal_refused", "goal", route.target(), "from", feet,
                        "base", pillarBase == null ? "none" : pillarBase.toShortString(),
                        "live_goal", liveTarget, "column_seen", pillarBase != null,
                        "column_clear", columnClear, "walk_seen", walkProved);
                return Capture.refused("navigation_goal_without_observed_stance", rays, Math.max(0, observed.size() - before));
            }
            // A fan tells us what the eye happened to cross, but a walk needs known feet, head,
            // and support cells all the way along it. Prove a small set of actual sight-lines
            // toward the admissible stance. Each individual cell is still gated by a vanilla view
            // ray; this is a bounded look-around, not a loaded-chunk scan or an inferred floor.
            observeVisibleCorridors(bot, feet, stance, waterTraversal, observed, tick);
            candidate = freeze(dimension, route.minimumY(), generation, tick, observed);
            stance = climbableGoal && isObservedClimbable(candidate, route.target())
                    ? route.target().immutable() : nearestStandable(candidate, route.target());
            if (stance == null) {
                return Capture.refused("navigation_goal_without_observed_stance", rays,
                        Math.max(0, observed.size() - before));
            }
            // This fence proves cells, not a whitelist of movement types. The later Baritone
            // admission is still constrained to this immutable snapshot and requires a complete
            // dry/no-place plan before any input is sent. That proof understands legitimate
            // observed parkour, doors, ladders and vines, which ObservedGraphSearch intentionally
            // does not model. Keep the graph only for contracts that specifically need a dry
            // return/pillar proof below.
            if (route.returnAnchor() != null) {
                BlockPos anchor = nearestStandable(candidate, route.returnAnchor());
                if (anchor == null || ObservedGraphSearch.path(stance, anchor, new SnapshotEnvironment(candidate)) == null) {
                    return Capture.refused("navigation_return_corridor_unavailable", rays,
                            Math.max(0, observed.size() - before));
                }
            }
            return new Capture(candidate, true, "", provenance, rays, Math.max(0, observed.size() - before), false, null);
        }

        if (route.shape() == NavRoute.Shape.NEAR) {
            // A near-goal (follow/approach) has no single stand cell to validate, but its path
            // may only use a target the bot can currently see; it still receives only the narrow
            // terrain corridor the bot can actually see. RUN_AWAY remains direction-only below.
            if (!liveTarget) {
                return Capture.refused("navigation_goal_unobserved", rays, Math.max(0, observed.size() - before));
            }
            observeVisibleCorridors(bot, feet, route.target(), waterTraversal, observed, tick);
        } else if (route.shape() == NavRoute.Shape.DIRECTIONAL_PURSUIT) {
            // Follow may know its owner's server position while the owner is outside this bot's
            // view. That coordinate supplies a heading only: expose one short local corridor,
            // then select an already observed stance that actually advances in that direction.
            // No remote cell, chunk, or terrain state becomes route authority here.
            boolean visibleLandmarkPursuit = isVisibleLandmarkPursuit(route);
            int pursuitRange = pursuitObservationRange(bot, route);
            BlockPos pursuitPoint = pursuitObservationPoint(bot, route);
            if (visibleLandmarkPursuit) {
                observeVisibleCorridorsWithinRange(bot, feet, pursuitPoint, waterTraversal, observed, pursuitRange, tick);
                BlockPos detourPoint = pursuitObservationPoint(bot, route, VISIBLE_LANDMARK_REFRESH_LOOKAHEAD);
                observeDirectionalDetourCorridorsWithinRange(bot, feet, detourPoint, route, waterTraversal, observed,
                        pursuitRange, tick);
            } else {
                observeVisibleCorridors(bot, feet, pursuitPoint, waterTraversal, observed, tick);
                observeDirectionalDetourCorridors(bot, feet, pursuitPoint, route, waterTraversal, observed, tick);
            }
            ObservedNavigationFence candidate = freeze(dimension, route.minimumY(), generation, tick, observed);
            BlockPos hop = nearestDirectionalPursuitStance(candidate, feet, route.target(), route.radius(),
                    pursuitRange, route.options().allowBreak());
            if (hop == null) {
                return Capture.refused("navigation_pursuit_no_observed_hop", rays,
                        Math.max(0, observed.size() - before));
            }
            // The first horizontal lookahead can select a proven stance one step higher or
            // lower on a hill.  Give that exact elevation-changing leg its full, ray-proven
            // movement envelope before Baritone receives it; otherwise its source/destination
            // headroom becomes virtual bedrock even when the snowy slope is plainly walkable.
            if (visibleLandmarkPursuit) {
                observeVisibleCorridorsWithinRange(bot, feet, hop, waterTraversal, observed, pursuitRange, tick);
            } else {
                observeVisibleCorridors(bot, feet, hop, waterTraversal, observed, tick);
            }
            candidate = freeze(dimension, route.minimumY(), generation, tick, observed);
            // The remote target intentionally remains NavRoute.target() for the next heading
            // calculation and logs. The local observed hop is the only Baritone GoalBlock.
            route.setResolvedGoal(hop);
            return new Capture(candidate, true, "", TargetProvenance.DIRECTION_ONLY, rays,
                    Math.max(0, observed.size() - before), false, null);
        } else if (route.shape() == NavRoute.Shape.RUN_AWAY) {
            // GoalRunAway itself chooses the safe destination.  This merely exposes a short,
            // visible corridor away from the observed threat so it cannot discover terrain by
            // planning into an unobserved direction.
            observeVisibleCorridors(bot, feet, fleeObservationPoint(bot, route), false, observed, tick);
        }
        ObservedNavigationFence candidate = freeze(dimension, route.minimumY(), generation, tick, observed);
        if (!candidate.allows(feet.getX(), feet.getY(), feet.getZ())) {
            return Capture.refused("navigation_observation_fence_insufficient", rays,
                    Math.max(0, observed.size() - before));
        }
        // Do not use ObservedGraphSearch as a generic movement gate here: it models only dry
        // cardinal walking, steps and drops. Baritone's synchronous admission and its pre-input
        // replan guard require a complete fence-constrained dry/no-place path, which preserves
        // the edge-safety rule without rejecting a visibly valid jump, door, ladder or vine.
        return new Capture(candidate, true, "", provenance, rays, Math.max(0, observed.size() - before), false, null);
    }

    /** Adds a modest fresh view cone to an active route, preserving only bounded, same-dimension memory. */
    public static Capture refresh(AIPlayerEntity bot, NavRoute route, ObservedNavigationFence previous,
                                  long generation) {
        Objects.requireNonNull(bot, "bot");
        Objects.requireNonNull(route, "route");
        int tick = bot.getServer().getTickCount();
        // Do this before the refresh cadence shortcut. A target that left direct view may not
        // keep a privileged landmark leg alive for the remainder of its twelve-tick interval.
        if (isVisibleLandmarkPursuit(route)
                && !ObservableWorldQuery.canObserveBlock(bot, route.target())) {
            return Capture.refused("visible_landmark_unobserved", 0, 0);
        }
        int refreshInterval = isVisibleLandmarkPursuit(route)
                ? VISIBLE_LANDMARK_REFRESH_INTERVAL_TICKS : REFRESH_INTERVAL_TICKS;
        if (previous != null && previous.dimension.equals(BotEdits.dimensionKey(bot.level()))
                && tick >= previous.lastObservationTick
                && tick - previous.lastObservationTick < refreshInterval) {
            return new Capture(previous, true, "", TargetProvenance.DIRECTION_ONLY, 0, 0, false, null);
        }
        String dimension = BotEdits.dimensionKey(bot.level());
        // Keep the short live landmark leg local on every refresh as well. The route's exact
        // marker is re-proved above, then this fresh evidence window supplies the next legal
        // observed hop without inheriting a large, stale terrain map.
        Map<Long, Cell> observed = isVisibleLandmarkPursuit(route)
                ? new HashMap<>() : retained(previous, dimension, tick);
        int before = observed.size();
        seedBodyEnvelope(bot, observed, tick);
        mergeSharedWorldSight(bot, route.target(), observed, tick,
                isVisibleLandmarkPursuit(route) ? VISIBLE_LANDMARK_SHARED_SIGHT_LIMIT : Integer.MAX_VALUE);
        if (route.shape() == NavRoute.Shape.OWNER_FOLLOW) {
            // Return a fresh object (rather than `previous`) at the regular refresh cadence. BaritoneNavigator sees the replacement
            // and renews the paired LoadedChunkSnapshot, so newly normal-player-loaded chunks can become available without a force load.
            ObservedNavigationFence fence = freeze(dimension, route.minimumY(), generation, tick, observed);
            return new Capture(fence, true, "", TargetProvenance.DIRECTION_ONLY, 0,
                    Math.max(0, observed.size() - before), false, null);
        }
        int rays = scan(bot, observed, route.target(), REFRESH_RAYS, tick);
        BlockPos feet = navigationFeet(bot);
        if (route.shape() == NavRoute.Shape.RUN_AWAY) {
            observeVisibleCorridors(bot, feet, fleeObservationPoint(bot, route), false, observed, tick);
        } else if (route.shape() == NavRoute.Shape.DIRECTIONAL_PURSUIT) {
            boolean visibleLandmarkPursuit = isVisibleLandmarkPursuit(route);
            int pursuitRange = pursuitObservationRange(bot, route);
            BlockPos pursuitPoint = visibleLandmarkPursuit
                    ? pursuitObservationPoint(bot, route, VISIBLE_LANDMARK_REFRESH_LOOKAHEAD)
                    : pursuitObservationPoint(bot, route);
            if (visibleLandmarkPursuit) {
                observeVisibleCorridorsWithinRange(bot, feet, pursuitPoint, route.options().allowWater(), observed,
                        pursuitRange, tick);
                observeDirectionalDetourCorridorsWithinRange(bot, feet, pursuitPoint, route,
                        route.options().allowWater(), observed, pursuitRange, tick);
            } else {
                observeVisibleCorridors(bot, feet, pursuitPoint, route.options().allowWater(), observed, tick);
                observeDirectionalDetourCorridors(bot, feet, pursuitPoint, route,
                        route.options().allowWater(), observed, tick);
            }
        } else {
            // Rays refresh faces and air cells, but they do not by themselves establish every
            // feet/head/support triple that a newly reached walking cell needs.  Carry a narrow
            // proof forward from the bot's new position on every driven route.
            observeVisibleCorridors(bot, feet, route.target(), route.options().allowWater(), observed, tick);
        }
        ObservedNavigationFence fence = freeze(dimension, route.minimumY(), generation, tick, observed);
        if (!fence.allows(feet.getX(), feet.getY(), feet.getZ())) {
            return Capture.refused("navigation_observation_lost", rays, Math.max(0, observed.size() - before));
        }
        return new Capture(fence, true, "", TargetProvenance.DIRECTION_ONLY, rays,
                Math.max(0, observed.size() - before), false, null);
    }

    /** Re-proves a remembered target and its exact state before a route reports arrival. No raw read occurs before the view proof. */
    public static boolean revalidateRememberedTarget(AIPlayerEntity bot, NavRoute route, ObservedNavigationFence fence) {
        if (fence == null || !ObservableWorldQuery.canObserveCell(bot, route.target())) {
            return false;
        }
        BlockState remembered = fence.stateAt(route.target());
        return remembered != null && remembered.equals(bot.level().getBlockState(route.target()));
    }

    private static Map<Long, Cell> retained(ObservedNavigationFence previous, String dimension, int tick) {
        Map<Long, Cell> observed = new HashMap<>();
        if (previous == null || !previous.dimension.equals(dimension)) {
            return observed;
        }
        for (int i = 0; i < previous.cells.length; i++) {
            if ((long) tick - previous.seenTicks[i] <= MEMORY_TTL_TICKS) {
                observed.put(previous.cells[i], new Cell(previous.states[i], previous.seenTicks[i]));
            }
        }
        return observed;
    }

    /**
     * Imports only ray-proven bot/linked-owner sight. SharedWorldSight has already clipped each
     * ray and returns a bounded, same-dimension memory; this method merely preserves the exact
     * state/tick in the immutable route fence that Baritone may inspect.
     */
    private static void mergeSharedWorldSight(AIPlayerEntity bot, BlockPos target,
                                              Map<Long, Cell> observed, int tick) {
        List<SharedWorldSight.Observation> evidence = SharedWorldSight.routeEvidence(bot, target);
        for (SharedWorldSight.Observation observation : evidence) {
            if ((long) tick - observation.seenTick() <= MEMORY_TTL_TICKS) {
                put(observed, observation.packedPos(), observation.state(), observation.seenTick());
            }
        }
    }

    /**
     * Imports the newest route evidence only. A visible-landmark route already re-proves its
     * exact target and builds a new local corridor below, so retaining thousands of older shared
     * cells would only pressure the immutable fence's bounded snapshot.
     */
    private static void mergeSharedWorldSight(AIPlayerEntity bot, BlockPos target,
                                              Map<Long, Cell> observed, int tick, int limit) {
        if (limit == Integer.MAX_VALUE) {
            mergeSharedWorldSight(bot, target, observed, tick);
            return;
        }
        List<SharedWorldSight.Observation> evidence = SharedWorldSight.routeEvidence(bot, target);
        int first = Math.max(0, evidence.size() - Math.max(0, limit));
        for (int index = first; index < evidence.size(); index++) {
            SharedWorldSight.Observation observation = evidence.get(index);
            if ((long) tick - observation.seenTick() <= MEMORY_TTL_TICKS) {
                put(observed, observation.packedPos(), observation.state(), observation.seenTick());
            }
        }
    }

    private static void seedBodyEnvelope(AIPlayerEntity bot, Map<Long, Cell> observed, int tick) {
        BlockPos feet = navigationFeet(bot);
        // The occupied cell, head space, and physical support are directly known to the player.
        observeKnown(bot, feet, observed, tick);
        observeKnown(bot, feet.above(), observed, tick);
        observeKnown(bot, feet.below(), observed, tick);
        observeStandingEnvelope(bot, feet, false, observed, tick);
    }

    /**
     * The navigation grid names the air cell immediately above a partial support, matching
     * {@link ServerPlayerContext#playerFeet()}.  Keeping the observation fence on that same
     * coordinate is essential: otherwise it can select a snow-top stance that Baritone starts
     * one cell below and therefore cannot enter without breaking.
     */
    private static BlockPos navigationFeet(AIPlayerEntity bot) {
        BlockPos raw = bot.blockPosition();
        BlockState occupied = bot.level().getBlockState(raw);
        return occupied.getBlock() instanceof SlabBlock || occupied.getBlock() instanceof SnowLayerBlock
                ? raw.above() : raw;
    }

    private static void observeKnown(AIPlayerEntity bot, BlockPos pos, Map<Long, Cell> observed, int tick) {
        put(observed, pos.asLong(), bot.level().getBlockState(pos), tick);
    }

    /** Each cell is queried before it is read; it is deliberately not a raw radius/chunk scan. */
    private static void observeStandingEnvelope(AIPlayerEntity bot, BlockPos centre, boolean throughFluids,
                                                Map<Long, Cell> observed, int tick) {
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                for (int y = -1; y <= 1; y++) {
                    BlockPos pos = centre.offset(x, y, z);
                    // A floor-cell centre ray from a standing player's eye usually intersects a
                    // nearer part of the same flat floor. Aim at its visible top surface instead;
                    // this still records only the first surface the eye can actually see, and
                    // lets a plainly visible distant air cell prove its real support without a
                    // raw downward world read.
                    if (y == -1) {
                        observeFloorTopIfVisible(bot, pos, throughFluids, observed, tick);
                    } else {
                        observeRouteCellIfVisible(bot, pos, throughFluids, observed, tick);
                    }
                }
            }
        }
    }

    private static boolean observeCellIfVisible(AIPlayerEntity bot, BlockPos pos, Map<Long, Cell> observed, int tick) {
        if (!ObservableWorldQuery.canObserveCell(bot, pos)) {
            return false;
        }
        put(observed, pos.asLong(), bot.level().getBlockState(pos), tick);
        return true;
    }

    /** The swim form deliberately lets water stay transparent, just as a player's eye does. */
    private static boolean observeRouteCellIfVisible(AIPlayerEntity bot, BlockPos pos, boolean throughFluids,
                                                     Map<Long, Cell> observed, int tick) {
        if (throughFluids ? ObservableWorldQuery.canObserveCellThroughFluids(bot, pos)
                : ObservableWorldQuery.canObserveCell(bot, pos)) {
            put(observed, pos.asLong(), bot.level().getBlockState(pos), tick);
            return true;
        }
        // A vine, rail, or other non-colliding traversal block can be visibly attached to a
        // collider behind it. The cell ray correctly rejects that as empty space, so earn its
        // state instead from an outline ray whose first hit is this exact cell. The hit owns the
        // only state read; no target state is consulted before visibility succeeds.
        return observeRouteOutlineIfVisible(bot, pos, observed, tick);
    }

    /** Records a plainly visible non-colliding route block from an exact first-hit outline ray. */
    private static boolean observeRouteOutlineIfVisible(AIPlayerEntity bot, BlockPos pos,
                                                        Map<Long, Cell> observed, int tick) {
        Vec3 eye = bot.getEyePosition();
        Vec3 centre = pos.getCenter();
        double dx = centre.x - eye.x;
        double dy = centre.y - eye.y;
        double dz = centre.z - eye.z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int radius = Math.max(1, MinecraftAiConfig.get().perception().radius());
        if (!(distance > 1.0E-9D) || distance > radius) {
            return false;
        }
        // Continue slightly beyond the cell centre so a thin outline attached to its far face
        // (notably a vine on a wall) can become the genuine first hit. A sight ray sees through
        // foliage, fences and water, and never skips the cell itself.
        ObservableWorldQuery.ViewHit view = ObservableWorldQuery.castSightRay(bot, dx, dy, dz,
                Math.min(radius, distance + 1.0D), ObservableWorldQuery.ViewShape.OUTLINE, pos);
        if (!view.hit() || !pos.equals(view.pos()) || view.state() == null) {
            return false;
        }
        put(observed, pos.asLong(), view.state(), tick);
        return true;
    }

    /**
     * Adds only a stance cell whose body and real support surface the bot can separately see.
     * The support ray is aimed just below the support's top face; aiming at a floor-cell centre
     * would otherwise strike a nearer flat floor and falsely call the distant cell hidden.
     */
    private static void observeVisibleStance(AIPlayerEntity bot, BlockPos feet, Map<Long, Cell> observed, int tick) {
        // Baritone validates more than ordinary player headroom for parkour, climbing, and
        // fall transitions.  Each higher cell is independently ray-proven before it is read;
        // this is evidence for the actual movement envelope, not an inferred vertical scan.
        for (int y = 0; y <= NAVIGATION_HEADROOM; y++) {
            observeCellIfVisible(bot, feet.above(y), observed, tick);
        }
        observeFloorTopIfVisible(bot, feet.below(), false, observed, tick);
    }

    /** The landmark-only form retains the same per-cell proof while widening only its support ray. */
    private static void observeVisibleStanceWithinRange(AIPlayerEntity bot, BlockPos feet, Map<Long, Cell> observed,
                                                        int observationRange, int tick) {
        // Baritone validates more than ordinary player headroom for parkour, climbing, and
        // fall transitions.  Each higher cell is independently ray-proven before it is read;
        // this is evidence for the actual movement envelope, not an inferred vertical scan.
        for (int y = 0; y <= NAVIGATION_HEADROOM; y++) {
            observeCellIfVisible(bot, feet.above(y), observed, tick);
        }
        observeFloorTopIfVisibleWithinRange(bot, feet.below(), false, observed, observationRange, tick);
    }

    /**
     * A visible water/shore slice used by a swim corridor. Every cell has an independent
     * through-water sight proof: its support, submerged body and the headroom Baritone checks
     * before it accepts a swim/shore transition. This exposes no sideways or behind-solid terrain.
     */
    private static void observeWaterEnvelope(AIPlayerEntity bot, BlockPos centre, Map<Long, Cell> observed, int tick) {
        for (int y = -1; y <= NAVIGATION_HEADROOM; y++) {
            observeRouteCellIfVisible(bot, centre.offset(0, y, 0), true, observed, tick);
        }
    }

    private static void observeFloorTopIfVisible(AIPlayerEntity bot, BlockPos floor, boolean throughFluids,
                                                 Map<Long, Cell> observed, int tick) {
        Vec3 eye = bot.getEyePosition();
        Vec3 top = new Vec3(floor.getX() + 0.5D, floor.getY() + 0.999D, floor.getZ() + 0.5D);
        double dx = top.x - eye.x;
        double dy = top.y - eye.y;
        double dz = top.z - eye.z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(distance > 1.0E-9D) || distance > Math.max(1, MinecraftAiConfig.get().perception().radius())) {
            return;
        }
        // The floor cell is the ray's target: a leaf, fence or water floor is hit and recorded as what it is,
        // never mistaken for the empty cell a ray that skipped it would reach.
        ObservableWorldQuery.ViewHit view = ObservableWorldQuery.castSightRay(
                bot, dx, dy, dz, distance, ObservableWorldQuery.ViewShape.COLLIDER, floor);
        if (view.hit() && floor.equals(view.pos())) {
            put(observed, floor.asLong(), view.state(), tick);
        } else if (!throughFluids && !view.isUnknown() && !view.hit()) {
            // The endpoint is inside this exact floor cell. A dry, ordinary player-eye ray that
            // reaches it without a first hit proves the cell is currently clear navigation space;
            // record only that ray-proven AIR fact. Do not use this branch through water: its
            // fluid-transparent ray deliberately cannot establish a dry placement destination.
            // Do not downgrade an earlier exact proof of a non-colliding traversal block such as
            // a vine or rail: the collider ray reaches the endpoint precisely because that block
            // has no collider, not because the cell is air.
            Cell prior = observed.get(floor.asLong());
            if (mayReplaceFloorEvidenceWithAir(prior == null ? null : prior.state())) {
                // A lower slab, rail, or other partial block can be visibly supporting the route
                // even though this full-height top aim misses above its real shape. Give it the
                // ordinary state-free cell/outline proof first; only an unproved miss remains AIR.
                if (!observeRouteCellIfVisible(bot, floor, throughFluids, observed, tick)) {
                    put(observed, floor.asLong(), AIR, tick);
                }
            }
        }
    }

    /**
     * Records a proved floor face without silently shrinking a render-distance landmark route
     * back to the general perception tuning. The final ray still clamps to tracked chunks.
     */
    private static void observeFloorTopIfVisibleWithinRange(AIPlayerEntity bot, BlockPos floor,
                                                            boolean throughFluids, Map<Long, Cell> observed,
                                                            int observationRange, int tick) {
        Vec3 eye = bot.getEyePosition();
        Vec3 top = new Vec3(floor.getX() + 0.5D, floor.getY() + 0.999D, floor.getZ() + 0.5D);
        double dx = top.x - eye.x;
        double dy = top.y - eye.y;
        double dz = top.z - eye.z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int range = Math.max(1, observationRange);
        if (!(distance > 1.0E-9D) || distance > range) {
            return;
        }
        ObservableWorldQuery.ViewHit view = ObservableWorldQuery.castSightRay(
                bot, dx, dy, dz, distance, ObservableWorldQuery.ViewShape.COLLIDER, floor);
        if (view.hit() && floor.equals(view.pos())) {
            put(observed, floor.asLong(), view.state(), tick);
        } else if (!throughFluids && !view.isUnknown() && !view.hit()) {
            // The endpoint is inside this exact floor cell. A dry, ordinary player-eye ray that
            // reaches it without a first hit proves the cell is currently clear navigation space;
            // record only that ray-proven AIR fact. Do not use this branch through water: its
            // fluid-transparent ray deliberately cannot establish a dry placement destination.
            // Do not downgrade an earlier exact proof of a non-colliding traversal block such as
            // a vine or rail: the collider ray reaches the endpoint precisely because that block
            // has no collider, not because the cell is air.
            Cell prior = observed.get(floor.asLong());
            if (mayReplaceFloorEvidenceWithAir(prior == null ? null : prior.state())) {
                // A lower slab, rail, or other partial block can be visibly supporting the route
                // even though this full-height top aim misses above its real shape. Give it the
                // ordinary state-free cell/outline proof first; only an unproved miss remains AIR.
                if (!observeRouteCellIfVisible(bot, floor, throughFluids, observed, tick)) {
                    put(observed, floor.asLong(), AIR, tick);
                }
            }
        }
    }

    /** A collider-miss proves AIR only when it cannot erase a prior exact outline/cell proof. */
    static boolean mayReplaceFloorEvidenceWithAir(BlockState prior) {
        return prior == null || prior.isAir();
    }

    /**
     * Captures a narrow, ray-proven walk corridor. When observed-hostile avoidance is enabled,
     * the envelope expands only to the matching Baritone avoidance radius so a real visible
     * detour is available; every added cell is still individually visibility- and
     * perception-radius-gated. Strips use no state except a cell's own first visible surface, so
     * a wall blocks both the proof and the later Baritone plan instead of becoming hidden route
     * knowledge.
     */
    private static void observeVisibleCorridors(AIPlayerEntity bot, BlockPos from, BlockPos to, boolean throughFluids,
                                                Map<Long, Cell> observed, int tick) {
        if (from.getY() != to.getY()) {
            // A diagonal ray fan alone only samples a staircase through a vertical route. A
            // vine/ladder ascent or descent needs the real source and destination columns, so
            // capture them cell-by-cell only where the player's eye can prove them.
            observeVisibleElevationColumns(bot, from, to, throughFluids, observed, tick);
        }
        if (to.getY() < from.getY()) {
            // A descent needs the first actual drop column, not merely diagonal air samples
            // between a cliff edge and its visible landing. This applies to ordinary dry falls
            // as well as water traversal: Baritone reads the fall column before it can plan a
            // safe descent. Every cell remains independently ray-proven, so this adds no
            // terrain authority behind the lip.
            observeVisibleDescentColumn(bot, from, to, throughFluids, observed, tick);
        }
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        if (steps == 0) {
            if (throughFluids) {
                observeWaterEnvelope(bot, from, observed, tick);
            } else {
                observeVisibleStance(bot, from, observed, tick);
            }
            return;
        }
        double horizontal = Math.sqrt((double) dx * dx + (double) dz * dz);
        double sideX = horizontal > 1.0E-9D ? -dz / horizontal : 0.0D;
        double sideZ = horizontal > 1.0E-9D ? dx / horizontal : 0.0D;
        int radius = Math.max(1, MinecraftAiConfig.get().perception().radius());
        int halfWidth = corridorHalfWidth();
        for (int strip = -halfWidth; strip <= halfWidth; strip++) {
            for (int index = 0; index <= steps; index++) {
                double fraction = (double) index / steps;
                int x = (int) Math.floor(from.getX() + dx * fraction + sideX * strip + 0.5D);
                int y = (int) Math.floor(from.getY() + (to.getY() - from.getY()) * fraction + 0.5D);
                int z = (int) Math.floor(from.getZ() + dz * fraction + sideZ * strip + 0.5D);
                BlockPos stance = new BlockPos(x, y, z);
                if (bot.getEyePosition().distanceToSqr(stance.getCenter()) <= (double) radius * radius) {
                    if (throughFluids) {
                        observeWaterEnvelope(bot, stance, observed, tick);
                    } else {
                        observeVisibleStance(bot, stance, observed, tick);
                    }
                }
            }
        }
    }

    /**
     * Captures a corridor whose extent is chosen by the admitted landmark route, while each cell
     * still needs its own eye-ray proof. The caller supplies only the actual tracked view range.
     */
    private static void observeVisibleCorridorsWithinRange(AIPlayerEntity bot, BlockPos from, BlockPos to,
                                                           boolean throughFluids, Map<Long, Cell> observed,
                                                           int observationRange, int tick) {
        if (from.getY() != to.getY()) {
            // A diagonal ray fan alone only samples a staircase through a vertical route. A
            // vine/ladder ascent or descent needs the real source and destination columns, so
            // capture them cell-by-cell only where the player's eye can prove them.
            observeVisibleElevationColumnsWithinRange(bot, from, to, throughFluids, observed, observationRange, tick);
        }
        if (to.getY() < from.getY()) {
            // A descent needs the first actual drop column, not merely diagonal air samples
            // between a cliff edge and its visible landing. This applies to ordinary dry falls
            // as well as water traversal: Baritone reads the fall column before it can plan a
            // safe descent. Every cell remains independently ray-proven, so this adds no
            // terrain authority behind the lip.
            observeVisibleDescentColumnWithinRange(bot, from, to, throughFluids, observed, observationRange, tick);
        }
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        if (steps == 0) {
            if (throughFluids) {
                observeWaterEnvelope(bot, from, observed, tick);
            } else {
                observeVisibleStanceWithinRange(bot, from, observed, observationRange, tick);
            }
            return;
        }
        double horizontal = Math.sqrt((double) dx * dx + (double) dz * dz);
        double sideX = horizontal > 1.0E-9D ? -dz / horizontal : 0.0D;
        double sideZ = horizontal > 1.0E-9D ? dx / horizontal : 0.0D;
        int radius = Math.max(1, observationRange);
        int halfWidth = corridorHalfWidth();
        for (int strip = -halfWidth; strip <= halfWidth; strip++) {
            for (int index = 0; index <= steps; index++) {
                double fraction = (double) index / steps;
                int x = (int) Math.floor(from.getX() + dx * fraction + sideX * strip + 0.5D);
                int y = (int) Math.floor(from.getY() + (to.getY() - from.getY()) * fraction + 0.5D);
                int z = (int) Math.floor(from.getZ() + dz * fraction + sideZ * strip + 0.5D);
                BlockPos stance = new BlockPos(x, y, z);
                if (bot.getEyePosition().distanceToSqr(stance.getCenter()) <= (double) radius * radius) {
                    if (throughFluids) {
                        observeWaterEnvelope(bot, stance, observed, tick);
                    } else {
                        observeVisibleStanceWithinRange(bot, stance, observed, observationRange, tick);
                    }
                }
            }
        }
    }

    /** Gives a seen hostile's Baritone avoidance sphere only ray-proven candidate terrain. */
    private static int corridorHalfWidth() {
        return MinecraftAiConfig.get().nav().baritoneCaps().mobAvoidanceEnabled()
                ? BaritoneSettings.MOB_AVOIDANCE_RADIUS : BASE_CORRIDOR_HALF_WIDTH;
    }

    private static int configuredObservationRange() {
        return Math.max(1, MinecraftAiConfig.get().perception().radius());
    }

    private static boolean isVisibleLandmarkPursuit(NavRoute route) {
        return route != null && route.shape() == NavRoute.Shape.DIRECTIONAL_PURSUIT
                && VISIBLE_LANDMARK_PURSUIT.equals(route.label());
    }

    /**
     * A tree/landmark route is created only after a first-hit view ray or shared player sight
     * nominated it. Its exact target can be re-proved out to the real tracked view boundary, but
     * its walking corridor is capped to one 24-block observed leg. The label is intentionally
     * exact so owner follow, generic exploration, and all pre-existing directional pursuits are
     * unchanged.
     */
    private static int pursuitObservationRange(AIPlayerEntity bot, NavRoute route) {
        if (isVisibleLandmarkPursuit(route)) {
            return Math.min(Math.max(1, ObservableWorldQuery.visibleRangeBlocks(bot)),
                    VISIBLE_LANDMARK_MAX_HOP_RANGE);
        }
        return configuredObservationRange();
    }

    /** Captures the visible ends of an elevation-changing route without manufacturing a column. */
    private static void observeVisibleElevationColumns(AIPlayerEntity bot, BlockPos from, BlockPos to,
                                                        boolean throughFluids, Map<Long, Cell> observed, int tick) {
        int minimumY = Math.min(from.getY(), to.getY()) - 1;
        int maximumY = Math.max(from.getY(), to.getY()) + NAVIGATION_HEADROOM;
        observeVisibleColumn(bot, from.getX(), from.getZ(), minimumY, maximumY,
                throughFluids, observed, tick);
        if (from.getX() != to.getX() || from.getZ() != to.getZ()) {
            observeVisibleColumn(bot, to.getX(), to.getZ(), minimumY, maximumY,
                    throughFluids, observed, tick);
        }
    }

    private static void observeVisibleElevationColumnsWithinRange(AIPlayerEntity bot, BlockPos from, BlockPos to,
                                                                   boolean throughFluids, Map<Long, Cell> observed,
                                                                   int observationRange, int tick) {
        int minimumY = Math.min(from.getY(), to.getY()) - 1;
        int maximumY = Math.max(from.getY(), to.getY()) + NAVIGATION_HEADROOM;
        observeVisibleColumnWithinRange(bot, from.getX(), from.getZ(), minimumY, maximumY,
                throughFluids, observed, observationRange, tick);
        if (from.getX() != to.getX() || from.getZ() != to.getZ()) {
            observeVisibleColumnWithinRange(bot, to.getX(), to.getZ(), minimumY, maximumY,
                    throughFluids, observed, observationRange, tick);
        }
    }

    /**
     * Captures the first possible drop column in the route direction for an observed descent.
     * The planner receives no fallback terrain: a cell outside the player's actual view remains
     * absent, and therefore impassable, in the immutable snapshot.
     */
    private static void observeVisibleDescentColumn(AIPlayerEntity bot, BlockPos from, BlockPos to,
                                                     boolean throughFluids, Map<Long, Cell> observed, int tick) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (dx == 0 && dz == 0) {
            return;
        }
        BlockPos drop = Math.abs(dx) >= Math.abs(dz) && dx != 0
                ? from.offset(Integer.signum(dx), 0, 0)
                : from.offset(0, 0, Integer.signum(dz));
        int minimumY = Math.min(from.getY(), to.getY()) - 1;
        int maximumY = Math.max(from.getY(), to.getY()) + NAVIGATION_HEADROOM;
        observeVisibleColumn(bot, drop.getX(), drop.getZ(), minimumY, maximumY,
                throughFluids, observed, tick);
        // After the visibly proven drop, Baritone needs its actual landing-level approach rather
        // than only the elevated diagonal samples above the cliff. This delegates to the ordinary
        // corridor capture; it cannot recurse because both endpoints share Y.
        observeVisibleCorridors(bot, new BlockPos(drop.getX(), to.getY(), drop.getZ()), to,
                throughFluids, observed, tick);
    }

    private static void observeVisibleDescentColumnWithinRange(AIPlayerEntity bot, BlockPos from, BlockPos to,
                                                                boolean throughFluids, Map<Long, Cell> observed,
                                                                int observationRange, int tick) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (dx == 0 && dz == 0) {
            return;
        }
        BlockPos drop = Math.abs(dx) >= Math.abs(dz) && dx != 0
                ? from.offset(Integer.signum(dx), 0, 0)
                : from.offset(0, 0, Integer.signum(dz));
        int minimumY = Math.min(from.getY(), to.getY()) - 1;
        int maximumY = Math.max(from.getY(), to.getY()) + NAVIGATION_HEADROOM;
        observeVisibleColumnWithinRange(bot, drop.getX(), drop.getZ(), minimumY, maximumY,
                throughFluids, observed, observationRange, tick);
        // A range-aware descent must keep the full landing leg on the same strictly ray-proven
        // landmark corridor; it cannot recurse because both endpoints share Y.
        observeVisibleCorridorsWithinRange(bot, new BlockPos(drop.getX(), to.getY(), drop.getZ()), to,
                throughFluids, observed, observationRange, tick);
    }

    /**
     * Bounded, per-cell evidence capture used by visible climb and descent envelopes. The helper
     * deliberately contains no world read: {@link #observeRouteCellIfVisible} reads a state only
     * after the matching sight ray has succeeded.
     */
    private static void observeVisibleColumn(AIPlayerEntity bot, int x, int z, int minimumY, int maximumY,
                                             boolean throughFluids, Map<Long, Cell> observed, int tick) {
        int radius = Math.max(1, MinecraftAiConfig.get().perception().radius());
        int feetY = bot.blockPosition().getY();
        int lower = Math.max(minimumY, feetY - radius);
        int upper = Math.min(maximumY, feetY + radius);
        for (int y = lower; y <= upper; y++) {
            observeRouteCellIfVisible(bot, new BlockPos(x, y, z), throughFluids, observed, tick);
        }
    }

    private static void observeVisibleColumnWithinRange(AIPlayerEntity bot, int x, int z, int minimumY,
                                                        int maximumY, boolean throughFluids, Map<Long, Cell> observed,
                                                        int observationRange, int tick) {
        int radius = Math.max(1, observationRange);
        int feetY = bot.blockPosition().getY();
        int lower = Math.max(minimumY, feetY - radius);
        int upper = Math.min(maximumY, feetY + radius);
        for (int y = lower; y <= upper; y++) {
            observeRouteCellIfVisible(bot, new BlockPos(x, y, z), throughFluids, observed, tick);
        }
    }

    /** A view-only sampling point for a run-away goal; it is not the navigation goal itself. */
    private static BlockPos fleeObservationPoint(AIPlayerEntity bot, NavRoute route) {
        BlockPos feet = navigationFeet(bot);
        double dx = feet.getX() - route.target().getX();
        double dz = feet.getZ() - route.target().getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length <= 1.0E-9D) {
            Vec3 look = bot.getLookAngle();
            dx = look.x;
            dz = look.z;
            length = Math.sqrt(dx * dx + dz * dz);
        }
        if (length <= 1.0E-9D) {
            return feet;
        }
        int distance = Math.min(Math.max(2, route.radius()),
                Math.max(2, MinecraftAiConfig.get().perception().radius() - 1));
        return feet.offset((int) Math.round(dx / length * distance), 0,
                (int) Math.round(dz / length * distance));
    }

    /**
     * The far end of one directional-pursuit lookahead. It is deliberately a horizontal
     * projection from the bot's own current cell: a remote landmark/player height must not
     * manufacture an unseen cliff, stair, or pillar target. The specially labelled visible
     * landmark form may use the bot's actual tracked render range; every other pursuit retains
     * the configured local observation range.
     */
    private static BlockPos pursuitObservationPoint(AIPlayerEntity bot, NavRoute route) {
        return pursuitObservationPoint(bot, route, pursuitObservationRange(bot, route));
    }

    /** Same direction-only projection with a smaller fresh-evidence lookahead for route refresh. */
    private static BlockPos pursuitObservationPoint(AIPlayerEntity bot, NavRoute route, int observationRange) {
        BlockPos feet = navigationFeet(bot);
        double dx = route.target().getX() - feet.getX();
        double dz = route.target().getZ() - feet.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length <= 1.0E-9D) {
            return feet;
        }
        int perception = Math.max(1, observationRange);
        // Leave two blocks of sight range for a ray-proven feet/head/support envelope at the
        // hop edge. A low configured perception radius simply yields a very short or no hop.
        int distance = Math.min(Math.max(1, route.radius()), Math.max(1, perception - 2));
        return feet.offset((int) Math.round(dx / length * distance), 0,
                (int) Math.round(dz / length * distance));
    }

    /**
     * Adds two small, ray-proven L-shaped detour lanes to a direction-only hop. The forward
     * corridor alone proves only a narrow strip, which can make a plainly visible gap beside a
     * short wall unavailable and turn its top into the first break goal. Each lane remains inside
     * the same local perception/hop bounds and every cell is still admitted by its own view ray;
     * this is a bounded look-around, never a remote terrain query.
     */
    private static void observeDirectionalDetourCorridors(AIPlayerEntity bot, BlockPos from, BlockPos forward,
                                                            NavRoute route, boolean throughFluids,
                                                            Map<Long, Cell> observed, int tick) {
        int dx = forward.getX() - from.getX();
        int dz = forward.getZ() - from.getZ();
        double length = Math.sqrt((double) dx * dx + (double) dz * dz);
        if (length <= 1.0E-9D) {
            return;
        }
        int perception = Math.max(1, MinecraftAiConfig.get().perception().radius());
        int lateral = Math.min(6, Math.max(1, Math.min(route.radius(), perception - 2)));
        double sideX = -dz / length;
        double sideZ = dx / length;
        for (int sign : new int[]{-1, 1}) {
            int offsetX = (int) Math.round(sideX * lateral * sign);
            int offsetZ = (int) Math.round(sideZ * lateral * sign);
            if (offsetX == 0 && offsetZ == 0) {
                continue;
            }
            BlockPos flank = from.offset(offsetX, 0, offsetZ);
            observeVisibleCorridors(bot, from, flank, throughFluids, observed, tick);
            observeVisibleCorridors(bot, flank, forward, throughFluids, observed, tick);
            // A wall can hide the centre ray to a gap at its end even though the bot can really
            // see an inset corner of that gap from its current eye. Prove only that one corner
            // stance; the normal graph still has to connect it through the visible flank lane.
            int advanceX = Math.abs(dx) >= Math.abs(dz) ? Integer.signum(dx) : 0;
            int advanceZ = advanceX == 0 ? Integer.signum(dz) : 0;
            if (advanceX != 0 || advanceZ != 0) {
                observeInsetDetourStance(bot, flank.offset(advanceX, 0, advanceZ),
                        advanceX, advanceZ, offsetX, offsetZ, observed, tick);
            }
        }
    }

    /** The visible-landmark form uses the tracked render range but retains each individual eye-ray proof. */
    private static void observeDirectionalDetourCorridorsWithinRange(AIPlayerEntity bot, BlockPos from, BlockPos forward,
                                                            NavRoute route, boolean throughFluids,
                                                            Map<Long, Cell> observed, int observationRange, int tick) {
        int dx = forward.getX() - from.getX();
        int dz = forward.getZ() - from.getZ();
        double length = Math.sqrt((double) dx * dx + (double) dz * dz);
        if (length <= 1.0E-9D) {
            return;
        }
        int perception = Math.max(1, observationRange);
        int lateral = Math.min(6, Math.max(1, Math.min(route.radius(), perception - 2)));
        double sideX = -dz / length;
        double sideZ = dx / length;
        for (int sign : new int[]{-1, 1}) {
            int offsetX = (int) Math.round(sideX * lateral * sign);
            int offsetZ = (int) Math.round(sideZ * lateral * sign);
            if (offsetX == 0 && offsetZ == 0) {
                continue;
            }
            BlockPos flank = from.offset(offsetX, 0, offsetZ);
            observeVisibleCorridorsWithinRange(bot, from, flank, throughFluids, observed, observationRange, tick);
            observeVisibleCorridorsWithinRange(bot, flank, forward, throughFluids, observed, observationRange, tick);
            // A wall can hide the centre ray to a gap at its end even though the bot can really
            // see an inset corner of that gap from its current eye. Prove only that one corner
            // stance; the normal graph still has to connect it through the visible flank lane.
            int advanceX = Math.abs(dx) >= Math.abs(dz) ? Integer.signum(dx) : 0;
            int advanceZ = advanceX == 0 ? Integer.signum(dz) : 0;
            if (advanceX != 0 || advanceZ != 0) {
                observeInsetDetourStance(bot, flank.offset(advanceX, 0, advanceZ),
                        advanceX, advanceZ, offsetX, offsetZ, observed, tick);
            }
        }
    }

    /**
     * Proves a stance at the visible end of a lateral lane without pretending the bot has already
     * moved there. Each feet/head/support cell gets its own ray from the real current eye, aimed
     * at the corner that faces the lane and away from the blocked forward direction.
     */
    private static void observeInsetDetourStance(AIPlayerEntity bot, BlockPos feet,
                                                  int advanceX, int advanceZ, int lateralX, int lateralZ,
                                                  Map<Long, Cell> observed, int tick) {
        int lateralSignX = Integer.signum(lateralX);
        int lateralSignZ = Integer.signum(lateralZ);
        for (int y = 0; y <= NAVIGATION_HEADROOM; y++) {
            observeInsetRouteCell(bot, feet.above(y), advanceX, advanceZ, lateralSignX, lateralSignZ,
                    0.5D, observed, tick);
        }
        observeInsetRouteCell(bot, feet.below(), advanceX, advanceZ, lateralSignX, lateralSignZ,
                0.999D, observed, tick);
    }

    /** Records only a genuine first hit at this cell, or a collider miss that proves its interior is visible. */
    private static void observeInsetRouteCell(AIPlayerEntity bot, BlockPos cell,
                                               int advanceX, int advanceZ, int lateralSignX, int lateralSignZ,
                                               double height,
                                               Map<Long, Cell> observed, int tick) {
        Vec3 eye = bot.getEyePosition();
        // Keep the endpoint just inside the visible face: this has enough margin to expose a
        // genuine wall-end gap even when the bot has stopped close to the obstruction.
        Vec3 inset = new Vec3(cell.getX() + insetCoordinate(advanceX, lateralSignX),
                cell.getY() + height,
                cell.getZ() + insetCoordinate(advanceZ, lateralSignZ));
        double dx = inset.x - eye.x;
        double dy = inset.y - eye.y;
        double dz = inset.z - eye.z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int radius = Math.max(1, MinecraftAiConfig.get().perception().radius());
        if (!(distance > 1.0E-9D) || distance > radius) {
            return;
        }
        ObservableWorldQuery.ViewHit view = ObservableWorldQuery.castSightRay(bot, dx, dy, dz, distance,
                ObservableWorldQuery.ViewShape.COLLIDER, cell);
        if (view.isUnknown()) {
            return;
        }
        if (view.hit()) {
            if (cell.equals(view.pos()) && view.state() != null) {
                put(observed, cell.asLong(), view.state(), tick);
            }
        } else {
            // The ray reached an interior point of this exact cell without an earlier collider.
            // Like observeCellIfVisible(), that own-eye proof authorizes the one state read.
            put(observed, cell.asLong(), bot.level().getBlockState(cell), tick);
        }
    }

    /** Keeps a diagonal look-around endpoint inside the cell that the miss authorizes us to read. */
    private static double insetCoordinate(int advance, int lateral) {
        return Math.max(0.001D, Math.min(0.999D, 0.5D - 0.499D * advance + 0.499D * lateral));
    }

    /**
     * Selects the farthest forward local stance that the immutable fence has already proven.
     * The remote coordinate is a compass bearing, never a cell to read: each candidate is in the
     * current fence, is locally standable, and stays inside the bounded hop radius. It normally
     * reduces horizontal distance to that coordinate; a graph-reachable local detour may briefly
     * trade straight-line distance for a bounded sideways lane. This prevents a failed pursuit
     * from wandering away from the followed player while still permitting a small sideways detour. A
     * route explicitly allowed to break may use a bounded observed but graph-disconnected stance
     * only when no advancing walking stance exists, so a sealed obstruction remains mineable
     * without making an avoidable wall top its first goal.
     */
    private static BlockPos nearestDirectionalPursuitStance(ObservedNavigationFence fence, BlockPos origin,
                                                             BlockPos remoteTarget, int requestedHop,
                                                             int observationRange, boolean allowBreakFallback) {
        double towardX = remoteTarget.getX() - origin.getX();
        double towardZ = remoteTarget.getZ() - origin.getZ();
        double targetDistanceSq = towardX * towardX + towardZ * towardZ;
        if (targetDistanceSq <= 1.0E-9D) {
            return null;
        }
        double targetDistance = Math.sqrt(targetDistanceSq);
        double unitX = towardX / targetDistance;
        double unitZ = towardZ / targetDistance;
        int perception = Math.max(1, observationRange);
        int maxHop = Math.min(Math.max(1, requestedHop), Math.max(1, perception - 2));
        double maxHopSq = (double) maxHop * maxHop;
        // A locally standable cell is not necessarily a walking destination: the air above a
        // two-high wall is a common example. Prefer stances connected to the bot by the same
        // frozen, observed walking graph that backs other local navigation choices. A breakable
        // route retains a strictly secondary observed fallback for a sealed obstruction, but a
        // walk-only route can never turn an unreachable wall top into a movement goal.
        Map<BlockPos, Double> reachable = ObservedGraphSearch.search(origin, new SnapshotEnvironment(fence));

        DirectionalPursuitStance reachableBest = null;
        DirectionalPursuitStance reachableDetourBest = null;
        DirectionalPursuitStance breakFallback = null;
        for (long packed : fence.cells) {
            BlockPos candidate = BlockPos.of(packed);
            if (!SnapshotEnvironment.isStandable(fence, candidate)
                    || Math.abs(candidate.getY() - origin.getY()) > 3) {
                continue;
            }
            double dx = candidate.getX() - origin.getX();
            double dz = candidate.getZ() - origin.getZ();
            double distanceSq = dx * dx + dz * dz;
            if (distanceSq < 1.0D || distanceSq > maxHopSq) {
                continue;
            }
            double forward = dx * unitX + dz * unitZ;
            // The directional target is FollowTask's standoff point, not the player cell. A
            // local hop may approach it, but must never project through it and erase the
            // player's requested personal-space gap on the last hop.
            if (forward <= 0.25D || forward > targetDistance + 1.0E-6D) {
                continue;
            }
            double remainingX = remoteTarget.getX() - candidate.getX();
            double remainingZ = remoteTarget.getZ() - candidate.getZ();
            boolean closesDistance = remainingX * remainingX + remainingZ * remainingZ < targetDistanceSq;
            double lateral = Math.abs(dx * unitZ - dz * unitX);
            double distance = Math.sqrt(distanceSq);
            DirectionalPursuitStance stance = new DirectionalPursuitStance(candidate.immutable(), forward, lateral, distance);
            if (reachable.containsKey(candidate)) {
                if (closesDistance && isBetterDirectionalPursuitStance(stance, reachableBest)) {
                    reachableBest = stance;
                } else if (!closesDistance && isBetterDirectionalPursuitStance(stance, reachableDetourBest)) {
                    reachableDetourBest = stance;
                }
            } else if (allowBreakFallback && closesDistance
                    && isBetterDirectionalPursuitStance(stance, breakFallback)) {
                breakFallback = stance;
            }
        }
        if (reachableBest != null) {
            return reachableBest.position();
        }
        if (reachableDetourBest != null) {
            return reachableDetourBest.position();
        }
        return breakFallback == null ? null : breakFallback.position();
    }

    private static boolean isBetterDirectionalPursuitStance(DirectionalPursuitStance candidate,
                                                              DirectionalPursuitStance incumbent) {
        return incumbent == null
                || candidate.forward() > incumbent.forward() + 1.0E-6D
                || (Math.abs(candidate.forward() - incumbent.forward()) <= 1.0E-6D
                && candidate.lateral() < incumbent.lateral() - 1.0E-6D)
                || (Math.abs(candidate.forward() - incumbent.forward()) <= 1.0E-6D
                && Math.abs(candidate.lateral() - incumbent.lateral()) <= 1.0E-6D
                && candidate.distance() > incumbent.distance())
                || (Math.abs(candidate.forward() - incumbent.forward()) <= 1.0E-6D
                && Math.abs(candidate.lateral() - incumbent.lateral()) <= 1.0E-6D
                && Math.abs(candidate.distance() - incumbent.distance()) <= 1.0E-6D
                && comparePosition(candidate.position(), incumbent.position()) < 0);
    }

    private record DirectionalPursuitStance(BlockPos position, double forward, double lateral, double distance) {
    }

    private static int scan(AIPlayerEntity bot, Map<Long, Cell> observed, BlockPos target, int rays, int tick) {
        if (rays <= 0) {
            return 0;
        }
        Vec3 eye = bot.getEyePosition();
        double range = Math.max(1, MinecraftAiConfig.get().perception().radius());
        double dx = target.getX() + 0.5D - eye.x;
        double dy = target.getY() + 0.5D - eye.y;
        double dz = target.getZ() + 0.5D - eye.z;
        // The first ray looks where the route is headed. The remaining rays are a rotating-free
        // deterministic fan, like the existing honest view sweeper, not an omniscient volume read.
        scanRay(bot, observed, dx, dy, dz, range, tick);
        int fan = Math.max(1, rays - 1);
        for (int i = 0; i < fan; i++) {
            double angle = (Math.PI * 2.0D * i) / fan;
            double pitch = switch (i % 3) {
                case 0 -> -0.32D;
                case 1 -> 0.0D;
                default -> 0.32D;
            };
            scanRay(bot, observed, Math.cos(angle), pitch, Math.sin(angle), range, tick);
        }
        return rays;
    }

    private static void scanRay(AIPlayerEntity bot, Map<Long, Cell> observed,
                                double dx, double dy, double dz, double range, int tick) {
        ObservableWorldQuery.ViewHit view = ObservableWorldQuery.castSightRay(bot, dx, dy, dz, range,
                ObservableWorldQuery.ViewShape.COLLIDER, null);
        if (view.isUnknown()) {
            return;
        }
        Vec3 eye = bot.getEyePosition();
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(length > 1.0E-9D)) {
            return;
        }
        double distance = view.distance();
        // The eye passes through leaves, fences, glass and water, and Baritone plans on what is stored here: a cell
        // the ray crossed keeps the state it really holds, so foliage and water are never remembered as free air.
        RayGrid.traverse(eye.x, eye.y, eye.z, dx / length, dy / length, dz / length, distance,
                (x, y, z) -> {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState seen = view.seenState(pos);
                    put(observed, pos.asLong(), seen != null ? seen : AIR, tick);
                    return true;
                });
        if (view.hit()) {
            put(observed, view.pos().asLong(), view.state(), tick);
        }
    }

    /**
     * The map may overshoot {@link #MAX_CELLS} while an admission is built; {@link #freeze} sheds
     * the oldest memory once. Evicting inside every put scanned the whole 8192-entry map for each
     * new cell, and with the snapshot saturated from the first admission on that cost a single
     * directional hop 300-900 ms of server thread.
     */
    private static void put(Map<Long, Cell> observed, long pos, BlockState state, int tick) {
        observed.put(pos, new Cell(state, tick));
    }

    private static ObservedNavigationFence freeze(String dimension, int minimumY, long generation, int tick,
                                                  Map<Long, Cell> observed) {
        // Bounded memory: a local walking aid, never an expanding world cache.
        List<Map.Entry<Long, Cell>> entries = ObservedCellRetention.freshest(
                new ArrayList<>(observed.entrySet()), Cell::seenTick, MAX_CELLS);
        entries.sort(Comparator.comparingLong(Map.Entry::getKey));
        long[] cells = new long[entries.size()];
        BlockState[] states = new BlockState[entries.size()];
        int[] seen = new int[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<Long, Cell> entry = entries.get(i);
            cells[i] = entry.getKey();
            states[i] = entry.getValue().state();
            seen[i] = entry.getValue().seenTick();
        }
        return new ObservedNavigationFence(dimension, minimumY, generation, tick, cells, states, seen);
    }

    private int indexOf(long packed) {
        return Arrays.binarySearch(cells, packed);
    }

    /**
     * Pure observed dry-route admission check. It deliberately operates only on this immutable
     * snapshot: an absent cell is not a candidate and no live Level query can turn a gap, cliff,
     * or hidden detour into a route.
     */
    static boolean hasObservedDryGoalPath(ObservedNavigationFence fence, BlockPos source, NavRoute route) {
        if (fence == null || source == null || route == null) {
            return false;
        }
        BlockPos goal = switch (route.shape()) {
            case BLOCK -> nearestStandable(fence, route.target());
            case NEAR -> nearestStandableWithin(fence, route.target(), route.radius());
            case DIRECTIONAL_PURSUIT -> route.resolvedGoal();
            case OWNER_FOLLOW -> null;
            case RUN_AWAY -> null;
        };
        return goal != null && ObservedGraphSearch.path(source, goal, new SnapshotEnvironment(fence)) != null;
    }

    /**
     * Captures a vertical, visibly empty build column above an already reachable base. This is
     * intentionally narrower than a general construction scan: it is only the cells a pillar
     * movement can occupy or place into, and every one earns its own eye-ray proof.
     */
    private static BlockPos observePillarColumn(AIPlayerEntity bot, BlockPos from, BlockPos target,
                                                 Map<Long, Cell> observed, int tick) {
        if (target.getY() <= from.getY()) {
            return null;
        }
        BlockPos base = new BlockPos(target.getX(), from.getY(), target.getZ());
        observeVisibleStance(bot, base, observed, tick);
        for (int y = base.getY(); y <= target.getY() + NAVIGATION_HEADROOM; y++) {
            if (!observeRouteCellIfVisible(bot, new BlockPos(base.getX(), y, base.getZ()), false, observed, tick)) {
                return null;
            }
        }
        return base;
    }

    /**
     * The lanes to a pillar's foot ({@link PillarWalkLanes}), each stance the eye reaches proved by its own rays like the
     * stances of any walk: nothing outside the perception radius, nothing a wall hides.
     */
    private static void observePillarWalk(AIPlayerEntity bot, BlockPos from, BlockPos base,
                                          Map<Long, Cell> observed, int tick) {
        int radius = Math.max(1, MinecraftAiConfig.get().perception().radius());
        for (BlockPos stance : PillarWalkLanes.stances(from, base)) {
            if (bot.getEyePosition().distanceToSqr(stance.getCenter()) <= (double) radius * radius) {
                observeVisibleStance(bot, stance, observed, tick);
            }
        }
    }

    /** True only for an air column that starts on a safe, collision-bearing observed base. */
    private static boolean isObservedPillarColumn(ObservedNavigationFence fence, BlockPos base, BlockPos target) {
        if (target.getY() <= base.getY() || !SnapshotEnvironment.isStandable(fence, base)) {
            return false;
        }
        for (int y = base.getY(); y <= target.getY() + NAVIGATION_HEADROOM; y++) {
            BlockState state = fence.stateAt(base.getX(), y, base.getZ());
            if (state == null || !state.isAir() || Standability.isDangerous(state)) {
                return false;
            }
        }
        return true;
    }

    private static BlockPos nearestStandable(ObservedNavigationFence fence, BlockPos target) {
        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(target.immutable());
        for (int y = -1; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    if (x != 0 || y != 0 || z != 0) {
                        candidates.add(target.offset(x, y, z));
                    }
                }
            }
        }
        candidates.sort(Comparator.comparingInt((BlockPos pos) -> pos.distManhattan(target))
                .thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ).thenComparingInt(BlockPos::getX));
        for (BlockPos candidate : candidates) {
            if (SnapshotEnvironment.isStandable(fence, candidate)) {
                return candidate.immutable();
            }
        }
        return null;
    }

    /** A movement endpoint Baritone recognises without falsely calling a non-colliding block a floor. */
    private static boolean isObservedClimbable(ObservedNavigationFence fence, BlockPos pos) {
        BlockState state = fence.stateAt(pos);
        if (state == null) {
            return false;
        }
        return state.is(Blocks.LADDER) || state.is(Blocks.VINE)
                || state.is(Blocks.WEEPING_VINES) || state.is(Blocks.WEEPING_VINES_PLANT)
                || state.is(Blocks.TWISTING_VINES) || state.is(Blocks.TWISTING_VINES_PLANT);
    }

    /** The nearest snapshot-proven stance satisfying Baritone's spherical GoalNear predicate. */
    private static BlockPos nearestStandableWithin(ObservedNavigationFence fence, BlockPos target, int radius) {
        long radiusSquared = (long) Math.max(0, radius) * Math.max(0, radius);
        BlockPos best = null;
        long bestDistance = Long.MAX_VALUE;
        for (long packed : fence.cells) {
            BlockPos candidate = BlockPos.of(packed);
            long dx = (long) candidate.getX() - target.getX();
            long dy = (long) candidate.getY() - target.getY();
            long dz = (long) candidate.getZ() - target.getZ();
            long distance = dx * dx + dy * dy + dz * dz;
            if (distance > radiusSquared || !SnapshotEnvironment.isStandable(fence, candidate)) {
                continue;
            }
            if (best == null || distance < bestDistance
                    || (distance == bestDistance && comparePosition(candidate, best) < 0)) {
                best = candidate.immutable();
                bestDistance = distance;
            }
        }
        return best;
    }

    private static int comparePosition(BlockPos left, BlockPos right) {
        int compared = Integer.compare(left.getY(), right.getY());
        if (compared != 0) return compared;
        compared = Integer.compare(left.getZ(), right.getZ());
        return compared != 0 ? compared : Integer.compare(left.getX(), right.getX());
    }

    /** Pure observed-state adapter; it never reaches into a Level or causes an observation. */
    private static final class SnapshotEnvironment implements ObservedGraphSearch.Environment {
        private final ObservedNavigationFence fence;

        SnapshotEnvironment(ObservedNavigationFence fence) {
            this.fence = fence;
        }

        static boolean isStandable(ObservedNavigationFence fence, BlockPos pos) {
            BlockState floor = fence.stateAt(pos.below());
            BlockState feet = fence.stateAt(pos);
            BlockState head = fence.stateAt(pos.above());
            return floor != null && feet != null && head != null
                    && !floor.isAir() && floor.getFluidState().isEmpty()
                    && hasCollisionSupport(floor, pos.below())
                    && feet.isAir() && head.isAir()
                    && !Standability.isDangerous(floor)
                    && !Standability.isDangerous(feet)
                    && !Standability.isDangerous(head);
        }

        private static boolean hasCollisionSupport(BlockState state, BlockPos pos) {
            var shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, pos);
            return !shape.isEmpty() && shape.max(Direction.Axis.Y) > 0.0D;
        }

        @Override
        public boolean isStandable(BlockPos pos) {
            return isStandable(fence, pos);
        }

        @Override
        public boolean isForbidden(BlockPos pos) {
            if (!fence.allows(pos.getX(), pos.getY(), pos.getZ())) {
                return true;
            }
            // The graph contract makes known lava unsafe within two cells, not just at the
            // stance. Unknown cells do not become hazards or free space; they are absent from
            // the graph independently.
            for (int dx = -ObservedGraphSearch.LAVA_CLEARANCE; dx <= ObservedGraphSearch.LAVA_CLEARANCE; dx++) {
                for (int dy = -ObservedGraphSearch.LAVA_CLEARANCE; dy <= ObservedGraphSearch.LAVA_CLEARANCE; dy++) {
                    for (int dz = -ObservedGraphSearch.LAVA_CLEARANCE; dz <= ObservedGraphSearch.LAVA_CLEARANCE; dz++) {
                        BlockState state = fence.stateAt(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                        if (state != null && state.getFluidState().is(FluidTags.LAVA)) {
                            return true;
                        }
                    }
                }
            }
            BlockState feet = fence.stateAt(pos);
            BlockState head = fence.stateAt(pos.above());
            BlockState floor = fence.stateAt(pos.below());
            return (feet != null && Standability.isDangerous(feet))
                    || (head != null && Standability.isDangerous(head))
                    || (floor != null && Standability.isDangerous(floor));
        }

        @Override
        public boolean isAdjacentToWater(BlockPos pos) {
            for (var direction : net.minecraft.core.Direction.values()) {
                BlockState state = fence.stateAt(pos.relative(direction));
                if (state != null && !state.getFluidState().isEmpty()) {
                    return true;
                }
            }
            return false;
        }
    }
}
