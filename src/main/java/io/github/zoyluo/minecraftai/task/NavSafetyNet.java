package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mode.FakePlayerMotion;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.block.state.BlockState;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SAFE-1 / NAV-12: Runtime environment safety net. Runs every tick before all other checks, and
 * takes over ActionPack self-rescue immediately only for "genuinely lethal terrain" (drowning /
 * standing in or about to fall into lava / a high fall about to hit ground), yielding control back
 * once the danger has passed.
 *
 * Design notes:
 * - Called at the very front of BotTickCoordinator's per-bot loop; returning true means this tick
 *   has been taken over, and later watchers are skipped.
 * - It runs after TaskManager.tickAll (task-driven ActionPack) (see the tick order in
 *   MinecraftAiMod), so it can override the task's movement input on a dangerous tick, and the
 *   task's input takes effect normally again once the danger is resolved.
 * - Does not kill the task; the fake player has no client-side travel, so returning to shore in
 *   water must use verified, cell-by-cell physical movement.
 */
public final class NavSafetyNet {
    public static final NavSafetyNet INSTANCE = new NavSafetyNet();

    static final int AIR_SURFACE_THRESHOLD = 120; // Max is 300; below this while underwater -> surface to breathe
    private static final int EMERGENCY_AIR = 60;           // Below this with no hope of surfacing -> emergency teleport to a breathable landing spot
    // FollowTask renews this tiny lease only while it is physically swimming toward a waterborne
    // player (or climbing out after one) and still has a generous oxygen margin.  It is
    // deliberately not a general "ignore water" switch: expiry, target transitions, and low air
    // immediately restore normal rescue.
    //
    // Ownership protocol (no ping-pong between follow and the crisis machine below): while the
    // lease is valid follow owns every decision, including turning up for breath early
    // (FollowOxygen.SURFACE_FLOOR_AIR sits above AIR_SURFACE_THRESHOLD, so follow always gets there
    // first).  The moment air reaches AIR_SURFACE_THRESHOLD the lease ends and the crisis machine
    // owns the bot; follow then makes NO movement at all, so neither undoes the other's step.
    // Kept at the original 6 ticks: a 20-tick lease was tried for lag tolerance but the shallow-swim
    // ping-pong GameTest passes identically with 6, so a longer window was never shown to matter.
    private static final int FOLLOW_SWIM_LEASE_TICKS = 6;
    private static final int BREATHE_SCAN_UP = 5;          // Number of cells scanned upward above the head to find air
    private static final int RESCUE_RADIUS_H = 16;
    private static final int RESCUE_RADIUS_V = 16;
    private static final int SUFFOCATION_CLIMB_UP = 24;   // Max cells to climb straight up (toward the surface) when escaping suffocation, tried first
    private final Map<UUID, Integer> nextLogTick = new ConcurrentHashMap<>();
    // SAFE-DROWN2: Water-crisis takeover flag. The old logic only took over on ticks where
    // submerged && air < threshold (one jump for air), and let go as soon as the bot surfaced and
    // air started climbing -> the task's movement input immediately shoved it back into the water
    // -> repeated half-drowning, HP ground down by drowning damage (measured: during hunt roam
    // passing a lake, navsafe fired 12 times in 30s and the bot still drowned). Fix: once
    // triggered, enter crisis state and keep taking over (jump + swim toward the nearest
    // breathable shore point) until the feet are actually on solid ground before releasing control
    // -- the goal of self-rescue is "reach shore", not "grab one breath".
    private final Map<UUID, BlockPos> waterRescueShore = new ConcurrentHashMap<>();
    // SAFE-DROWN3: The tick the water crisis began. Swimming toward the shore point may never
    // succeed (a 2-block-high shore wall that can't be jumped onto / current pushing the bot back
    // -- measured: on a plains-lake expedition the bot still drowned at HP 2.2) -- once the crisis
    // has dragged on past a timeout, give up on doing it gracefully and just emergency-teleport
    // ashore to survive.
    private final Map<UUID, Integer> waterRescueSince = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> followSwimLeaseUntil = new ConcurrentHashMap<>();
    private static final int WATER_RESCUE_TELEPORT_AFTER = 200; // Still not out of the water after 10s -> force a teleport
    // xPerf-NAVSAFE-01 / detour-refactor-navsafetynet-water-search-cost: findPhysicalWaterEscape
    // (a full BFS over up to ~36k cells) and findNearestBreathableStandable (a full triple-nested
    // scan of the same size) used to re-run from scratch on every single tick a bot stayed in a
    // water crisis -- up to WATER_RESCUE_TELEPORT_AFTER (200) ticks in a row. Cache their result per
    // bot, keyed by the bot's own feet cell, and bound how stale that cache may get instead of
    // recomputing every tick:
    // - A changed feet cell invalidates the cache immediately (the very next tick recomputes),
    //   since the search itself starts from that position.
    // - Otherwise the cached result is reused for at most WATER_SEARCH_CACHE_TICKS ticks. Water can
    //   spread/recede every tick from vanilla scheduled fluid ticks (the same reason
    //   Standability.clearCache() below still runs unconditionally every tick for every *other*
    //   standability read this method makes), so an unbounded cache could hand back a route through
    //   a cell that is no longer actually passable. A short, bounded staleness window keeps that
    //   risk negligible without paying the full scan cost every tick: FakePlayerMotion.stepToStandable
    //   and swimStepTo both re-verify the specific destination cell's *current* state before moving
    //   and simply refuse the step (returning false, falling through to the remaining fallbacks
    //   below) if the cached route's next cell turned out to no longer be valid -- so a stale route
    //   can never move the bot into now-unsafe terrain, only delay noticing a shape change by at
    //   most WATER_SEARCH_CACHE_TICKS ticks (a quarter of a second), far below the 200-tick
    //   emergency-teleport timeout that remains the actual safety backstop.
    private static final int WATER_SEARCH_CACHE_TICKS = 5;
    private record WaterSearchCache<T>(BlockPos feet, int computedTick, T result) {
    }
    private final Map<UUID, WaterSearchCache<WaterEscapeStep>> waterEscapeCache = new ConcurrentHashMap<>();
    private final Map<UUID, WaterSearchCache<BlockPos>> breathableStandableCache = new ConcurrentHashMap<>();

    /**
     * Pure cache-validity check for the water-search memoization above, package-private so it can
     * be unit-tested without a Minecraft bootstrap. A cache entry is reusable only while it names
     * the bot's exact current feet cell and is younger than {@link #WATER_SEARCH_CACHE_TICKS}.
     */
    static boolean waterSearchCacheValid(BlockPos cachedFeet, int cachedTick,
                                         BlockPos currentFeet, int currentTick) {
        return cachedFeet != null
                && cachedFeet.equals(currentFeet)
                && currentTick >= cachedTick
                && currentTick - cachedTick < WATER_SEARCH_CACHE_TICKS;
    }

    private NavSafetyNet() {
    }

    public void clear(AIPlayerEntity bot) {
        UUID id = bot.getUUID();
        nextLogTick.remove(id);
        waterRescueShore.remove(id);
        waterRescueSince.remove(id);
        followSwimLeaseUntil.remove(id);
        waterEscapeCache.remove(id);
        breathableStandableCache.remove(id);
    }

    public void clearAll() {
        nextLogTick.clear();
        waterRescueShore.clear();
        waterRescueSince.clear();
        followSwimLeaseUntil.clear();
        waterEscapeCache.clear();
        breathableStandableCache.clear();
    }

    /**
     * Lets a task hand water recovery to the shared physical safety controller before air is low.
     * The placeholder is replaced with the nearest dry shore in {@link #tickBot}; no teleport or
     * inventory mutation is performed here.
     */
    public void requestWaterRescue(AIPlayerEntity bot) {
        waterRescueShore.putIfAbsent(bot.getUUID(), bot.blockPosition().immutable());
    }

    public boolean isWaterRescueActive(AIPlayerEntity bot) {
        return waterRescueShore.containsKey(bot.getUUID());
    }

    /**
     * Authorizes one short, high-air continuation of FollowTask's verified swim movement.
     * Package-private on purpose: no arbitrary task may opt out of the drowning safety net.
     */
    void renewFollowSwim(AIPlayerEntity bot) {
        if (bot.getAirSupply() <= AIR_SURFACE_THRESHOLD) {
            clearFollowSwim(bot);
            return;
        }
        followSwimLeaseUntil.put(bot.getUUID(), bot.level().getServer().getTickCount() + FOLLOW_SWIM_LEASE_TICKS);
        // A prior rescue is for an accidental water entry.  An actively renewed, high-air swim
        // follow is a different, short-lived intent and must not inherit that old controller.
        waterRescueShore.remove(bot.getUUID());
        waterRescueSince.remove(bot.getUUID());
    }

    /** Clears the narrow FollowTask swim lease on cancellation or any non-swim transition. */
    void clearFollowSwim(AIPlayerEntity bot) {
        followSwimLeaseUntil.remove(bot.getUUID());
    }

    private boolean hasFollowSwimLease(AIPlayerEntity bot, int currentTick) {
        Integer until = followSwimLeaseUntil.get(bot.getUUID());
        if (until == null || until < currentTick || bot.getAirSupply() <= AIR_SURFACE_THRESHOLD) {
            followSwimLeaseUntil.remove(bot.getUUID());
            return false;
        }
        return true;
    }

    public boolean tickBot(MinecraftServer server, AIPlayerEntity bot) {
        if (!bot.isAlive()) {
            return false;
        }
        ServerLevel world = bot.level();
        BlockPos feet = bot.blockPosition();

        // 0) Suffocation/stuck-in-block: when the player's body hitbox is actually intersecting a
        // solid collision shape, prefer climbing **upward** out to the surface first.
        // Can't just look at the whole cell at getBlockPos(): low supports like dirt_path/slabs put
        // a normally standing player's floored BlockPos inside the support cell, even though the
        // entity's AABB only touches its top face and the player isn't actually buried.
        // A boat rider is never "buried": vanilla riders skip block collision and a boat resting on a
        // bank seats its passenger a little below the hull, i.e. inside the ground.  Snapping the
        // bot out of the seat every tick (then re-boarding, forever) is what made a beached boat
        // impossible to use.  Other vehicles (minecart, horse, ...) keep the normal snap.
        if (!(bot.getVehicle() instanceof AbstractBoat)
                && !FakePlayerMotion.isBlockCollisionFree(bot)
                && escapeSuffocation(bot, world, feet)) {
            throttledLog(server, bot, "navsafe_suffocation_snap", feet);
            return true;
        }

        // 1) Lava: standing in lava / lava underfoot -> escape immediately (highest priority)
        if (inLava(world, feet) || inLava(world, feet.below())) {
            escapeLava(bot, world, feet);
            throttledLog(server, bot, "navsafe_lava_escape", feet);
            return true;
        }

        // 2) Drowning/water crisis: once triggered, keep taking over until ashore (see the waterRescueShore comment).
        if (hasFollowSwimLease(bot, server.getTickCount())) {
            // The lease is renewed only by an active FollowTask and only above the safety oxygen
            // threshold.  Once air falls, the normal branch below immediately resumes rescue.
            return false;
        }
        boolean inCrisis = waterRescueShore.containsKey(bot.getUUID());
        if (!inCrisis && bot.isUnderWater()) {
            inCrisis = true; // newly triggered
        }
        if (inCrisis) {
            // Fluid blocks can disappear/spread from vanilla scheduled ticks without going
            // through our block actions, so the global standability cache may describe the
            // previous water shape. Every standability read this method makes below (other than
            // the memoized full-volume searches, which accept their own small bounded staleness --
            // see WATER_SEARCH_CACHE_TICKS above) must use the current shape every tick.
            Standability.clearCache();
            // Release condition: once the bot reaches a dry, standable position verified by
            // server-side block state -> the crisis is over, hand control back.
            // The fake player's cell-by-cell physical movement has no client landing packet, so
            // isOnGround() can still be false even after it is already standing on solid ground;
            // continuing to rely on it would shuttle the bot back and forth between two dry cells.
            if (isDryStandable(bot, world, feet)) {
                waterRescueShore.remove(bot.getUUID());
                waterRescueSince.remove(bot.getUUID());
                // The old navigator may still contain the DROP_DOWN edge that caused the rescue.
                // Cancel the complete action stack before returning control or it will execute the
                // same wet edge again on the next tick.
                bot.getActionPack().stopAll();
                return false;
            }
            int now = server.getTickCount();
            Integer since = waterRescueSince.putIfAbsent(bot.getUUID(), now);
            // SAFE-DROWN: Air is critical and there's no air above to surface into (a water pocket
            // capped by stone) -> emergency-teleport to the nearest breathable landing spot.
            // SAFE-DROWN3: Or the crisis has dragged on too long (can't reach the shore point: tall
            // shore wall / current pushing back) -> likewise force a teleport to survive.
            boolean rescueTimedOut = since != null && now - since > WATER_RESCUE_TELEPORT_AFTER;
            if (rescueTimedOut || (bot.getAirSupply() <= EMERGENCY_AIR && !breathableAbove(world, feet))) {
                if (emergencyTeleportToAir(bot, world, feet, now)) {
                    waterRescueShore.remove(bot.getUUID());
                    waterRescueSince.remove(bot.getUUID());
                    throttledLog(server, bot, "navsafe_drown_teleport", feet);
                    return true;
                }
            }
            // First prove a physically connected water route. The old Euclidean-only shore
            // choice could select a dry cell directly behind a wall and then reject every first
            // step because it temporarily increased straight-line distance. In a flooded cave
            // that left the bot motionless until strict-survival denied the teleport fallback.
            WaterEscapeStep escape = cachedFindPhysicalWaterEscape(bot, world, feet, now);
            if (escape != null) {
                waterRescueShore.put(bot.getUUID(), escape.shore().immutable());
                boolean dryLanding = isDryStandableCell(world, escape.next());
                boolean moved = dryLanding
                        ? FakePlayerMotion.stepToStandable(
                                bot, escape.next(), "navsafe_water_rescue")
                        : FakePlayerMotion.swimStepTo(bot, escape.next(), "navsafe_water_rescue");
                if (moved) {
                    throttledLog(server, bot, "navsafe_water_step", feet);
                    return true;
                }
            }
            // Surface only when oxygen is actually low. At full air this used to pre-empt the
            // cached shore route from a lower water cell, then the shore controller deliberately
            // stepped back down from the top cell on the next tick. The two correct local actions
            // therefore formed an endless Y/Y+1 policy oscillation. Connected shore movement
            // remains the first choice; emergency breathing is a bounded fallback.
            if (bot.getAirSupply() <= AIR_SURFACE_THRESHOLD
                    && physicalStepTowardAir(bot, world, feet)) {
                throttledLog(server, bot, "navsafe_surface_for_air", feet);
                return true;
            }
            // Legacy local fallback: use the cached shore if it is still valid, otherwise search
            // again (the nearest landing spot that is both standable and has air at feet and head).
            BlockPos shore = waterRescueShore.get(bot.getUUID());
            if (shore == null || shore.equals(feet) || !Standability.isStandable(world, shore)) {
                shore = cachedFindNearestBreathableStandable(bot, world, feet, now).orElse(null);
            }
            if (shore != null) {
                waterRescueShore.put(bot.getUUID(), shore.immutable());
                // Server-side fake players do not execute client-authored travel, so merely setting
                // forward/jump leaves them motionless. Advance one validated adjacent swim/shore
                // cell per tick; this is ordinary local movement, not privileged teleportation.
                if (physicalStepTowardShore(bot, world, feet, shore)) {
                    throttledLog(server, bot, "navsafe_water_step", feet);
                    return true;
                }
                double yaw = Math.toDegrees(Math.atan2(
                        -(shore.getX() + 0.5D - bot.getX()), shore.getZ() + 0.5D - bot.getZ()));
                bot.setYRot((float) yaw);
                bot.setYHeadRot((float) yaw);
                bot.setYBodyRot((float) yaw);
                bot.getActionPack().setForward(1.0F); // swim toward shore
            } else {
                waterRescueShore.put(bot.getUUID(), feet.immutable()); // no shore point (open deep water): placeholder to hold crisis state, surface for air first
                bot.getActionPack().setForward(0.0F);
            }
            bot.getActionPack().setSprinting(false);
            bot.getActionPack().setJumping(true); // continuous jump in water = surfacing/swimming
            throttledLog(server, bot, "navsafe_surface_for_air", feet);
            return true;
        }

        return false;
    }

    /** Memoized front for {@link #findPhysicalWaterEscape} -- see WATER_SEARCH_CACHE_TICKS above. */
    private WaterEscapeStep cachedFindPhysicalWaterEscape(AIPlayerEntity bot, ServerLevel world,
                                                           BlockPos feet, int now) {
        WaterSearchCache<WaterEscapeStep> cached = waterEscapeCache.get(bot.getUUID());
        if (cached != null && waterSearchCacheValid(cached.feet(), cached.computedTick(), feet, now)) {
            return cached.result();
        }
        WaterEscapeStep escape = findPhysicalWaterEscape(world, feet);
        waterEscapeCache.put(bot.getUUID(), new WaterSearchCache<>(feet.immutable(), now, escape));
        return escape;
    }

    /** Memoized front for {@link #findNearestBreathableStandable} -- see WATER_SEARCH_CACHE_TICKS above. */
    private Optional<BlockPos> cachedFindNearestBreathableStandable(AIPlayerEntity bot, ServerLevel world,
                                                                     BlockPos feet, int now) {
        WaterSearchCache<BlockPos> cached = breathableStandableCache.get(bot.getUUID());
        if (cached != null && waterSearchCacheValid(cached.feet(), cached.computedTick(), feet, now)) {
            return Optional.ofNullable(cached.result());
        }
        Optional<BlockPos> found = findNearestBreathableStandable(world, feet);
        breathableStandableCache.put(bot.getUUID(),
                new WaterSearchCache<>(feet.immutable(), now, found.orElse(null)));
        return found;
    }

    private static WaterEscapeStep findPhysicalWaterEscape(ServerLevel world, BlockPos start) {
        Standability.clearCache();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Map<BlockPos, BlockPos> previous = new HashMap<>();
        HashSet<BlockPos> visited = new HashSet<>();
        BlockPos origin = start.immutable();
        queue.add(origin);
        visited.add(origin);

        while (!queue.isEmpty()) {
            BlockPos current = queue.removeFirst();
            for (BlockPos candidate : waterEscapeNeighbors(current)) {
                if (Math.abs(candidate.getX() - origin.getX()) > RESCUE_RADIUS_H
                        || Math.abs(candidate.getZ() - origin.getZ()) > RESCUE_RADIUS_H
                        || Math.abs(candidate.getY() - origin.getY()) > RESCUE_RADIUS_V
                        || !visited.add(candidate)) {
                    continue;
                }
                if (!passableWaterColumn(world, candidate)) {
                    continue;
                }
                previous.put(candidate, current);
                if (isDryStandableCell(world, candidate)) {
                    BlockPos first = candidate;
                    while (previous.containsKey(first)
                            && !previous.get(first).equals(origin)) {
                        first = previous.get(first);
                    }
                    return new WaterEscapeStep(first.immutable(), candidate.immutable());
                }
                if (isWaterSwimCell(world, candidate)) {
                    queue.addLast(candidate);
                }
            }
        }
        return null;
    }

    static List<BlockPos> waterEscapeNeighbors(BlockPos current) {
        java.util.ArrayList<BlockPos> result = new java.util.ArrayList<>(14);
        result.add(current.above());
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            result.add(current.relative(direction));
            result.add(current.relative(direction).above());
            result.add(current.relative(direction).below());
        }
        result.add(current.below());
        return result;
    }

    static boolean passableWaterColumn(ServerLevel world, BlockPos candidate) {
        BlockState feet = world.getBlockState(candidate);
        BlockState head = world.getBlockState(candidate.above());
        return feet.getCollisionShape(world, candidate).isEmpty()
                && head.getCollisionShape(world, candidate.above()).isEmpty()
                && !Standability.isDangerous(feet)
                && !Standability.isDangerous(head)
                && (isWaterSwimCell(world, candidate) || isDryStandableCell(world, candidate));
    }

    static boolean isWaterSwimCell(ServerLevel world, BlockPos candidate) {
        return world.getFluidState(candidate).is(FluidTags.WATER)
                || world.getFluidState(candidate.above()).is(FluidTags.WATER);
    }

    static boolean isDryStandableCell(ServerLevel world, BlockPos candidate) {
        return world.getFluidState(candidate).isEmpty()
                && world.getFluidState(candidate.above()).isEmpty()
                && Standability.isStandable(world, candidate);
    }

    private static boolean physicalStepTowardAir(AIPlayerEntity bot,
                                                  ServerLevel world,
                                                  BlockPos feet) {
        if (!isWaterSwimCell(world, feet)) {
            return false;
        }
        BlockPos above = feet.above();
        if (!world.getBlockState(above).getCollisionShape(world, above).isEmpty()
                || !world.getBlockState(above.above()).getCollisionShape(world, above.above()).isEmpty()) {
            return false;
        }
        if (!isWaterSwimCell(world, above)) {
            return false;
        }
        return FakePlayerMotion.swimStepTo(bot, above, "navsafe_water_surface");
    }

    private record WaterEscapeStep(BlockPos next, BlockPos shore) {
    }

    /**
     * Escaping suffocation: prefer finding the first standable cell **straight up** (toward the
     * surface) first and teleport onto it.
     * Fixes "rescued deeper each time" -- the old implementation used
     * snapPlayerToNearestStandable to find the Euclidean-nearest standable cell, but while the bot
     * is buried that nearest cell is often below/diagonally below it, so repeated snaps dragged the
     * bot one cell at a time deeper into the pit (measured: at column 994 it got stuck going
     * 64->63->62->61). Climbing straight up is the correct fix for being buried
     * (Standability.isStandable already guarantees the landing cell has air at feet and head, with
     * support underfoot = can stand and breathe); only fall back to the omnidirectional nearest
     * standable cell -- at least escaping the current suffocating cell -- if nothing works within
     * SUFFOCATION_CLIMB_UP cells upward (buried deep, capped overhead).
     */
    private boolean escapeSuffocation(AIPlayerEntity bot, ServerLevel world, BlockPos feet) {
        // The cache must be invalidated: reaching this "buried" branch means a block just changed
        // (a cave-in / live-burial scenario calling setBlockState), so the Standability cache still
        // reflects the world before the change -- judging "no standable cell upward" from stale
        // values would fall back to the omnidirectional snap and drag the bot into a distant hole
        // (measured: a plains live-burial case where 2 cells up was clearly standable, but the bot
        // was still snapped into a y20 black hole and then triggered a life-saving teleport,
        // aborting the scenario).
        Standability.clearCache();
        int top = world.getMinY() + world.getHeight();
        for (int dy = 1; dy <= SUFFOCATION_CLIMB_UP && feet.getY() + dy < top - 1; dy++) {
            BlockPos candidate = feet.above(dy);
            if (Standability.isStandable(world, candidate)) {
                boolean moved = io.github.zoyluo.minecraftai.mode.CapabilityRuntime.run(
                        bot, io.github.zoyluo.minecraftai.mode.PrivilegedCapability.EMERGENCY_TELEPORT,
                        "navsafe_suffocation", () -> {
                            bot.getActionPack().stopAll();
                            bot.teleportTo(world, candidate.getX() + 0.5D, candidate.getY(), candidate.getZ() + 0.5D,
                                    Collections.emptySet(), bot.getYRot(), bot.getXRot(), true);
                        });
                if (moved) {
                    Standability.clearCache();
                    return true;
                }
                // strict_survival deliberately denies the long-range upward teleport.  That denial
                // must not suppress the ordinary adjacent escape below: a freshly collapsed gravel
                // column commonly leaves the previous tunnel cell one physical step away.  The old
                // early return left OreDig driving forward while the bot suffocated in place.
                break;
            }
        }
        // No solution upward (buried deep / capped overhead) -> fall back to the original logic: nearest standable cell in any direction.
        boolean escaped = bot.getActionPack().snapPlayerToNearestStandable("navsafe_suffocation");
        if (escaped) {
            // The path that entered the collision is no longer valid after an emergency side-step.
            // Keeping its executor alive lets it replay the same blocked edge on the next entity
            // tick. In strict survival that produced an endless two-cell jump oscillation: the
            // safety net escaped onto a standable neighbour, then the stale hunt route drove the
            // clientless player straight back into the obstruction. Retire every old controller;
            // the owning task will observe an idle route and choose a fresh waypoint.
            bot.getActionPack().stopAll();
        }
        return escaped;
    }

    // Whether the bot can surface and breathe within BREATHE_SCAN_UP cells above its head (a non-water passable cell = can breathe; hitting a solid block ceiling = sealed off)
    private static boolean breathableAbove(ServerLevel world, BlockPos feet) {
        for (int dy = 1; dy <= BREATHE_SCAN_UP; dy++) {
            BlockPos p = feet.above(dy);
            BlockState s = world.getBlockState(p);
            boolean water = s.getFluidState().is(FluidTags.WATER);
            boolean solid = !s.getCollisionShape(world, p).isEmpty();
            if (!water && !solid) {
                return true;   // a non-water air cell -> can surface and breathe
            }
            if (solid) {
                return false;  // hit a solid block ceiling, no hope of surfacing
            }
        }
        return false;
    }

    private boolean emergencyTeleportToAir(AIPlayerEntity bot, ServerLevel world, BlockPos feet, int now) {
        Optional<BlockPos> safe = cachedFindNearestBreathableStandable(bot, world, feet, now);
        if (safe.isEmpty()) {
            return false;
        }
        BlockPos to = safe.get();
        boolean moved = io.github.zoyluo.minecraftai.mode.CapabilityRuntime.run(
                bot, io.github.zoyluo.minecraftai.mode.PrivilegedCapability.EMERGENCY_TELEPORT,
                "navsafe_drowning", () -> {
                    bot.getActionPack().stopAll();
                    bot.teleportTo(world, to.getX() + 0.5D, to.getY(), to.getZ() + 0.5D,
                            Collections.emptySet(), bot.getYRot(), bot.getXRot(), true);
                });
        if (moved) {
            Standability.clearCache();
        }
        return moved;
    }

    // The nearest landing spot that is both standable and has air at feet and head (breathable, not water)
    private static Optional<BlockPos> findNearestBreathableStandable(ServerLevel world, BlockPos origin) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -RESCUE_RADIUS_H; dx <= RESCUE_RADIUS_H; dx++) {
            for (int dz = -RESCUE_RADIUS_H; dz <= RESCUE_RADIUS_H; dz++) {
                for (int dy = -RESCUE_RADIUS_V; dy <= RESCUE_RADIUS_V; dy++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (cursor.equals(origin)) {
                        continue; // proactive recovery must actually leave the wet edge/origin
                    }
                    if (!Standability.isStandable(world, cursor)) {
                        continue;
                    }
                    if (!world.getBlockState(cursor).isAir() || !world.getBlockState(cursor.above()).isAir()) {
                        continue;   // feet or head cell is water/solid block -> not breathable
                    }
                    double distance = cursor.distSqr(origin);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = cursor.immutable();
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean physicalStepTowardShore(AIPlayerEntity bot,
                                                   ServerLevel world,
                                                   BlockPos feet,
                                                   BlockPos shore) {
        double currentDistance = feet.distSqr(shore);
        java.util.List<BlockPos> candidates = new java.util.ArrayList<>();
        for (int dy : new int[]{1, 0, -1}) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int changedAxes = (dx == 0 ? 0 : 1) + (dy == 0 ? 0 : 1) + (dz == 0 ? 0 : 1);
                    if (changedAxes == 0 || changedAxes > 2) {
                        continue;
                    }
                    BlockPos candidate = feet.offset(dx, dy, dz);
                    if (candidate.distSqr(shore) >= currentDistance) {
                        continue;
                    }
                    BlockState at = world.getBlockState(candidate);
                    BlockState head = world.getBlockState(candidate.above());
                    if (!at.getCollisionShape(world, candidate).isEmpty()
                            || !head.getCollisionShape(world, candidate.above()).isEmpty()
                            || Standability.isDangerous(at)
                            || Standability.isDangerous(head)) {
                        continue;
                    }
                    boolean waterCell = world.getFluidState(candidate).is(FluidTags.WATER);
                    // Air immediately above water is breathable but not a landing. Treating it as
                    // a swim cell made the fake player step out of the water for one tick, fall
                    // back, and repeat forever between the same two Y levels. Water cells may be
                    // traversed explicitly; dry cells still need real footing.
                    if (!waterCell && !Standability.isStandable(world, candidate)) {
                        continue;
                    }
                    candidates.add(candidate.immutable());
                }
            }
        }
        candidates.sort(java.util.Comparator.comparingDouble(pos -> pos.distSqr(shore)));
        for (BlockPos candidate : candidates) {
            boolean waterCell = world.getFluidState(candidate).is(FluidTags.WATER)
                    || world.getFluidState(candidate.above()).is(FluidTags.WATER);
            boolean moved = waterCell
                    ? FakePlayerMotion.swimStepTo(bot, candidate, "navsafe_water_rescue")
                    : FakePlayerMotion.stepToStandable(bot, candidate, "navsafe_water_rescue");
            if (moved) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDryStandable(AIPlayerEntity bot, ServerLevel world, BlockPos feet) {
        return !bot.isInWater()
                && !world.getFluidState(feet).is(FluidTags.WATER)
                && !world.getFluidState(feet.above()).is(FluidTags.WATER)
                && Standability.isStandable(world, feet);
    }

    private static void escapeLava(AIPlayerEntity bot, ServerLevel world, BlockPos feet) {
        // Rush out toward the nearest horizontal direction that is "safe and standable" + jump
        Direction best = null;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos side = feet.relative(dir);
            if (!inLava(world, side) && !inLava(world, side.below())
                    && io.github.zoyluo.minecraftai.pathfinding.Standability.isStandable(world, side)) {
                best = dir;
                break;
            }
        }
        if (best != null) {
            double yaw = Math.toDegrees(Math.atan2(-best.getStepX(), best.getStepZ()));
            bot.setYRot((float) yaw);
            bot.setYHeadRot((float) yaw);
            bot.setYBodyRot((float) yaw);
            bot.getActionPack().setForward(1.0F);
        }
        // Jump to get out of the lava regardless of whether a direction was found
        bot.getActionPack().setJumping(true);
        bot.getActionPack().jumpOnce();
    }

    private static boolean inLava(ServerLevel world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.getFluidState().is(FluidTags.LAVA);
    }

    private void throttledLog(MinecraftServer server, AIPlayerEntity bot, String event, BlockPos pos) {
        int now = server.getTickCount();
        if (now < nextLogTick.getOrDefault(bot.getUUID(), 0)) {
            return;
        }
        nextLogTick.put(bot.getUUID(), now + 40);
        BotLog.danger(bot, event,
                "pos", pos.getX() + "," + pos.getY() + "," + pos.getZ(),
                "air", bot.getAirSupply(),
                "hp", String.format(java.util.Locale.ROOT, "%.1f", bot.getHealth()));
    }
}
