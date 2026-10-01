package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.WalkedStep;
import io.github.zoyluo.minecraftai.action.WalkedStepRules;
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
 * - Does not kill the task. Returning to shore in water is done the way a player does it: cell by cell
 *   with {@link WalkedStep}s (forward and jump keys, real swim physics at about two blocks per second),
 *   never by moving the bot (R5: no micro-teleports). A step runs over several ticks; the crisis
 *   machine plans the next one when it has ended.
 */
public final class NavSafetyNet {
    public static final NavSafetyNet INSTANCE = new NavSafetyNet();

    static final int AIR_SURFACE_THRESHOLD = 120; // Minimum air margin; Max is 300
    // A swimmer needs roughly eleven air ticks per vertical water cell plus a short turn/step
    // margin. The direct-column scan below uses this only when it can actually see a water
    // surface above the bot; a sealed or unknown column keeps the conservative base floor.
    private static final int AIR_PER_SURFACE_BLOCK = 11;
    private static final int AIR_SURFACE_MARGIN = 24;
    private static final int EMERGENCY_AIR = 60;           // Below this with no hope of surfacing -> emergency teleport to a breathable landing spot
    // FollowTask renews this tiny lease only while it is physically swimming toward a waterborne
    // player (or climbing out after one) and still has a generous oxygen margin.  It is
    // deliberately not a general "ignore water" switch: expiry, target transitions, and low air
    // immediately restore normal rescue.
    //
    // Ownership protocol (no ping-pong between follow and the crisis machine below): while the
    // lease is valid follow owns every decision, including turning up for breath early
    // (FollowOxygen.SURFACE_FLOOR_AIR sits above AIR_SURFACE_THRESHOLD, so follow always gets there
    // first). The moment air reaches the measured direct-surface threshold (never lower than
    // AIR_SURFACE_THRESHOLD) the lease ends and the crisis machine owns the bot; follow then
    // makes NO movement at all, so neither undoes the other's step.
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
    // SAFE-DROWN3: A physically proved route gets a deadline proportional to its remaining swim
    // cells, rather than a flat ten seconds. An operator emergency teleport is therefore a last
    // resort for a route that demonstrably stopped making progress, never a shortcut for a long
    // but normally swimmable crossing.
    private record WaterRescueDeadline(BlockPos feet, int routeCells, int deadlineTick) {
    }
    private final Map<UUID, WaterRescueDeadline> waterRescueDeadlines = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> followSwimLeaseUntil = new ConcurrentHashMap<>();
    // The Baritone counterpart of the follow swim lease: renewed on every tick Baritone drives a bot along a route that is
    // allowed to go through water (BaritoneDriver), so the bot is not treated as a drowning rescue case while it swims the
    // segment Baritone planned. Same guards as the follow lease: a few ticks long, void as soon as the air gets low.
    private final Map<UUID, Integer> baritoneWaterLeaseUntil = new ConcurrentHashMap<>();
    private static final int BARITONE_WATER_LEASE_TICKS = 6;
    // xPerf-NAVSAFE-01 / detour-refactor-navsafetynet-water-search-cost: findPhysicalWaterEscape
    // (a full BFS over up to ~36k cells) and findNearestBreathableStandable (a full triple-nested
    // scan of the same size) used to re-run from scratch on every single tick a bot stayed in a
    // water crisis. Cache their result per
    // bot, keyed by the bot's own feet cell, and bound how stale that cache may get instead of
    // recomputing every tick:
    // - A changed feet cell invalidates the cache immediately (the very next tick recomputes),
    //   since the search itself starts from that position.
    // - Otherwise the cached result is reused for at most WATER_SEARCH_CACHE_TICKS ticks. Water can
    //   spread/recede every tick from vanilla scheduled fluid ticks (the same reason
    //   Standability.clearCache() below still runs unconditionally every tick for every *other*
    //   standability read this method makes), so an unbounded cache could hand back a route through
    //   a cell that is no longer actually passable. A short, bounded staleness window keeps that
    //   risk negligible without paying the full scan cost every tick: WalkedStep.refusal
    //   (the check every walked step starts with) re-verifies the specific destination cell's *current* state before moving
    //   and simply refuse the step (returning false, falling through to the remaining fallbacks
    //   below) if the cached route's next cell turned out to no longer be valid -- so a stale route
    //   can never move the bot into now-unsafe terrain, only delay noticing a shape change by at
    //   most WATER_SEARCH_CACHE_TICKS ticks (a quarter of a second), far below even a one-cell
    //   physical swim-step deadline that remains the actual safety backstop.
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
        waterRescueDeadlines.remove(id);
        followSwimLeaseUntil.remove(id);
        baritoneWaterLeaseUntil.remove(id);
        waterEscapeCache.remove(id);
        breathableStandableCache.remove(id);
        suffocationEscapes.remove(id);
        rescueSteps.remove(id);
    }

    public void clearAll() {
        nextLogTick.clear();
        waterRescueShore.clear();
        waterRescueDeadlines.clear();
        followSwimLeaseUntil.clear();
        baritoneWaterLeaseUntil.clear();
        waterEscapeCache.clear();
        breathableStandableCache.clear();
        suffocationEscapes.clear();
        rescueSteps.clear();
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
        if (followSwimMustYield(bot)) {
            clearFollowSwim(bot);
            return;
        }
        followSwimLeaseUntil.put(bot.getUUID(), bot.level().getServer().getTickCount() + FOLLOW_SWIM_LEASE_TICKS);
        // A prior rescue is for an accidental water entry.  An actively renewed, high-air swim
        // follow is a different, short-lived intent and must not inherit that old controller.
        waterRescueShore.remove(bot.getUUID());
        waterRescueDeadlines.remove(bot.getUUID());
    }

    /** Clears the narrow FollowTask swim lease on cancellation or any non-swim transition. */
    void clearFollowSwim(AIPlayerEntity bot) {
        followSwimLeaseUntil.remove(bot.getUUID());
    }

    /**
     * Leases the bot to Baritone for a few ticks: it is driving the bot along a route that may go through water, so the water
     * crisis machine below does not take the bot over (its rescue steps would move a swimming bot off Baritone's path and
     * fight it for the position). Called by {@code BaritoneDriver} on every driven tick of a swim-permitted route; not a general
     * opt-out: the lease is refused (and any rescue in progress carries on) once the air is at the surfacing threshold.
     */
    public void renewBaritoneWater(AIPlayerEntity bot) {
        if (bot.getAirSupply() <= surfaceAirThreshold(bot)) {
            clearBaritoneWater(bot);
            return;
        }
        baritoneWaterLeaseUntil.put(bot.getUUID(), bot.level().getServer().getTickCount() + BARITONE_WATER_LEASE_TICKS);
        waterRescueShore.remove(bot.getUUID());
        waterRescueDeadlines.remove(bot.getUUID());
    }

    /** Ends the Baritone water lease: the drive ended (arrived, cancelled, taken over, bot removed) or the route no longer swims. */
    public void clearBaritoneWater(AIPlayerEntity bot) {
        baritoneWaterLeaseUntil.remove(bot.getUUID());
    }

    /** Whether the bot is currently leased to Baritone for a water crossing (for tests and logs). */
    public boolean hasBaritoneWaterLease(AIPlayerEntity bot) {
        return hasBaritoneWaterLease(bot, bot.level().getServer().getTickCount());
    }

    private boolean hasBaritoneWaterLease(AIPlayerEntity bot, int currentTick) {
        Integer until = baritoneWaterLeaseUntil.get(bot.getUUID());
        if (until == null || until < currentTick || bot.getAirSupply() <= surfaceAirThreshold(bot)) {
            baritoneWaterLeaseUntil.remove(bot.getUUID());
            return false;
        }
        return true;
    }

    private boolean hasFollowSwimLease(AIPlayerEntity bot, int currentTick) {
        Integer until = followSwimLeaseUntil.get(bot.getUUID());
        if (until == null || until < currentTick || followSwimMustYield(bot)) {
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
        boolean buried = !(bot.getVehicle() instanceof AbstractBoat) && !FakePlayerMotion.isBlockCollisionFree(bot);
        if (buried && escapeSuffocation(bot, world, feet)) {
            throttledLog(server, bot, "navsafe_suffocation_snap", feet);
            return true;
        }
        if (!buried && !suffocationEscapes.isEmpty()) {
            releaseSuffocationEscape(bot);
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
        if (hasBaritoneWaterLease(bot, server.getTickCount())) {
            // Baritone is driving this bot through water on a route that was allowed to (same oxygen rule as above).
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
                waterRescueDeadlines.remove(bot.getUUID());
                // The old navigator may still contain the DROP_DOWN edge that caused the rescue.
                // Cancel the complete action stack before returning control or it will execute the
                // same wet edge again on the next tick.
                bot.getActionPack().stopAll();
                return false;
            }
            int now = server.getTickCount();
            // SAFE-DROWN: Air is critical and there's no air above to surface into (a water pocket
            // capped by stone) -> emergency-teleport to the nearest breathable landing spot.
            if (bot.getAirSupply() <= EMERGENCY_AIR && !breathableAbove(world, feet)) {
                if (emergencyTeleportToAir(bot, world, feet, now)) {
                    waterRescueShore.remove(bot.getUUID());
                    waterRescueDeadlines.remove(bot.getUUID());
                    throttledLog(server, bot, "navsafe_drown_teleport", feet);
                    return true;
                }
            }
            // A rescue step in flight carries on by itself: nothing is re-planned until it has ended. A step somebody else
            // left running is dropped, the rescue owns the bot.
            if (rescueStepInFlight(bot)) {
                throttledLog(server, bot, "navsafe_water_step", feet);
                return true;
            }
            // A step that ends on the bank is done at the cell's edge, with the body still partly over the water: the last bit is a
            // walk to the middle of the dry cell (the release above needs the body out of the water), not a new escape from a cell that
            // is already dry.
            if (bot.isInWater() && isDryStandableCell(world, feet) && WalkedStep.supported(bot)
                    && beginRescueRecenter(bot, feet)) {
                throttledLog(server, bot, "navsafe_water_step", feet);
                return true;
            }
            // First prove a physically connected water route. The old Euclidean-only shore
            // choice could select a dry cell directly behind a wall and then reject every first
            // step because it temporarily increased straight-line distance. In a flooded cave
            // that left the bot motionless until strict-survival denied the teleport fallback.
            WaterEscapeStep escape = cachedFindPhysicalWaterEscape(bot, world, feet, now);
            if (escape != null) {
                waterRescueShore.put(bot.getUUID(), escape.shore().immutable());
                scheduleWaterRescueDeadline(bot, feet, escape, now);
                if (waterRescueDeadlineExceeded(bot, feet, now)) {
                    if (emergencyTeleportToAir(bot, world, feet, now)) {
                        waterRescueShore.remove(bot.getUUID());
                        waterRescueDeadlines.remove(bot.getUUID());
                        throttledLog(server, bot, "navsafe_drown_teleport", feet);
                        return true;
                    }
                }
                if (beginRescueStep(bot, escape.next(), "navsafe_water_rescue")) {
                    throttledLog(server, bot, "navsafe_water_step", feet);
                    return true;
                }
            }
            // Surface only when oxygen is actually low. At full air this used to pre-empt the
            // cached shore route from a lower water cell, then the shore controller deliberately
            // stepped back down from the top cell on the next tick. The two correct local actions
            // therefore formed an endless Y/Y+1 policy oscillation. Connected shore movement
            // remains the first choice; emergency breathing is a bounded fallback.
            if (bot.getAirSupply() <= surfaceAirThreshold(bot)
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
                // Advance one validated adjacent swim/shore cell by real inputs (a walked step): ordinary movement.
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

    /**
     * The minimum air supply at which a direct vertical surface is worth taking over for. A
     * shallow or unknown column keeps the historic floor; a measured deep column scales only with
     * the water cells the bot must actually swim through, not with an arbitrary map radius.
     */
    static int surfaceAirThresholdForDepth(int waterCellsToSurface) {
        return Math.max(AIR_SURFACE_THRESHOLD,
                Math.max(0, waterCellsToSurface) * AIR_PER_SURFACE_BLOCK + AIR_SURFACE_MARGIN);
    }

    private static int surfaceAirThreshold(AIPlayerEntity bot) {
        return surfaceAirThresholdForDepth(
                verticalWaterCellsToSurface(bot.level(), bot.blockPosition()));
    }

    /**
     * The oxygen boundary at which FollowSwimming must stop writing movement and let the water
     * rescue own the next tick.  This is deliberately shared with the follow lease so a deep
     * shaft cannot leave Follow and NavSafetyNet taking turns replacing one another's swim step.
     */
    static boolean followSwimMustYield(AIPlayerEntity bot) {
        return bot.getAirSupply() <= surfaceAirThreshold(bot);
    }

    /**
     * Counts a directly connected water column including the feet cell, returning zero when a
     * solid ceiling, another fluid, or the local rescue scan limit hides the surface. This is a
     * local upward check, not a remote-world search: it merely answers whether swimming straight
     * up from the current column can reach air.
     */
    private static int verticalWaterCellsToSurface(ServerLevel world, BlockPos feet) {
        if (!isWaterSwimCell(world, feet)) {
            return 0;
        }
        int depth = 1;
        for (int dy = 1; dy <= RESCUE_RADIUS_V; dy++) {
            BlockPos above = feet.above(dy);
            BlockState state = world.getBlockState(above);
            if (!state.getCollisionShape(world, above).isEmpty()) {
                return 0;
            }
            if (isWaterSwimCell(world, above)) {
                depth++;
                continue;
            }
            return world.getFluidState(above).isEmpty() ? depth : 0;
        }
        return 0;
    }

    /**
     * Uses the same conservative timeout budget as a real {@link WalkedStep.Kind#SWIM}, scaled
     * by the BFS-proved remaining cells. This keeps a 25-cell crossing from receiving the old
     * fixed 200-tick operator teleport while still bounding a one-cell route that is truly stuck.
     */
    static int waterRescueTimeoutTicks(int routeCells) {
        return (int) Math.ceil(WalkedStepRules.timeoutBudget(
                WalkedStep.Kind.SWIM, Math.max(1, routeCells)));
    }

    private void scheduleWaterRescueDeadline(AIPlayerEntity bot, BlockPos feet,
                                             WaterEscapeStep escape, int now) {
        UUID id = bot.getUUID();
        WaterRescueDeadline existing = waterRescueDeadlines.get(id);
        if (existing != null && existing.feet().equals(feet)
                && existing.routeCells() == escape.routeCells()) {
            return;
        }
        waterRescueDeadlines.put(id, new WaterRescueDeadline(feet.immutable(), escape.routeCells(),
                now + waterRescueTimeoutTicks(escape.routeCells())));
    }

    private boolean waterRescueDeadlineExceeded(AIPlayerEntity bot, BlockPos feet, int now) {
        WaterRescueDeadline deadline = waterRescueDeadlines.get(bot.getUUID());
        return deadline != null && deadline.feet().equals(feet) && now > deadline.deadlineTick();
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
                    return new WaterEscapeStep(first.immutable(), candidate.immutable(),
                            cellsFromOrigin(previous, origin, candidate));
                }
                if (isWaterSwimCell(world, candidate)) {
                    queue.addLast(candidate);
                }
            }
        }
        return null;
    }

    private static int cellsFromOrigin(Map<BlockPos, BlockPos> previous, BlockPos origin,
                                       BlockPos destination) {
        int cells = 0;
        for (BlockPos current = destination; current != null && !current.equals(origin);
             current = previous.get(current)) {
            cells++;
        }
        return Math.max(1, cells);
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

    /**
     * The kind of {@link WalkedStep} that takes the bot from its cell onto the adjacent {@code cell}: a swim for a water cell, a walk,
     * hop or drop for a dry landing (null when no walk covers the height difference). Shared by the rescue and the swim follower.
     */
    static WalkedStep.Kind stepKindTo(AIPlayerEntity bot, BlockPos cell) {
        if (isDryStandableCell(bot.level(), cell)) {
            return WalkedStepRules.walkKindFor(cell.getY() - bot.blockPosition().getY());
        }
        return WalkedStep.Kind.SWIM;
    }

    /** Bots whose action pack is running a step this net started (the water rescue). */
    private final java.util.Set<UUID> rescueSteps = ConcurrentHashMap.newKeySet();

    /**
     * Whether a rescue step is still running. Otherwise a step that somebody else left running is dropped: from here on the rescue
     * owns the bot's keys.
     */
    private boolean rescueStepInFlight(AIPlayerEntity bot) {
        var pack = bot.getActionPack();
        if (rescueSteps.contains(bot.getUUID())) {
            if (!pack.stepIdle()) {
                return true;
            }
            rescueSteps.remove(bot.getUUID());
            return false;
        }
        if (!pack.stepIdle()) {
            pack.cancelStep();
        }
        return false;
    }

    /** Starts a walk to the middle of the bot's own dry cell (its body is still partly over the water); false when it is refused. */
    private boolean beginRescueRecenter(AIPlayerEntity bot, BlockPos feet) {
        net.minecraft.world.phys.Vec3 middle = net.minecraft.world.phys.Vec3.atBottomCenterOf(feet);
        if (Math.hypot(middle.x - bot.getX(), middle.z - bot.getZ()) < WalkedStepRules.POINT_TOLERANCE) {
            return false;
        }
        bot.getActionPack().runStep(WalkedStep.begin(bot, middle, WalkedStep.Kind.RECENTER, "navsafe_water_rescue"));
        rescueSteps.add(bot.getUUID());
        return true;
    }

    /** Starts a rescue step onto {@code cell} (never moves the bot itself); false when the step is refused. */
    private boolean beginRescueStep(AIPlayerEntity bot, BlockPos cell, String reason) {
        WalkedStep.Kind kind = stepKindTo(bot, cell);
        if (kind == null || WalkedStep.refusal(bot, cell, kind) != null) {
            return false;
        }
        bot.getActionPack().runStep(WalkedStep.begin(bot, cell, kind, reason));
        rescueSteps.add(bot.getUUID());
        return true;
    }

    private boolean physicalStepTowardAir(AIPlayerEntity bot,
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
        return beginRescueStep(bot, above, "navsafe_water_surface");
    }

    /** The number of input-driven cells from the current feet cell to the proved dry shore. */
    private record WaterEscapeStep(BlockPos next, BlockPos shore, int routeCells) {
    }

    /**
     * Escaping suffocation. The EMERGENCY_TELEPORT decision is made FIRST (the operator profile allows it, strict survival never does),
     * and when it is denied nothing is scanned: the climb-up column scan below looks at blocks the bot cannot see, which only a
     * privileged rescue may do. With it, the old rescue stays: the first standable cell **straight up** (toward the surface) is
     * teleported to (fixes "rescued deeper each time" -- the old omnidirectional snap of a buried bot dragged it one cell at a time
     * deeper into the pit, measured 64->63->62->61). Without it, or when nothing is standable within SUFFOCATION_CLIMB_UP cells
     * upward, the bot gets out the way a player does: {@link #escapeSuffocationByInputs}.
     */
    private boolean escapeSuffocation(AIPlayerEntity bot, ServerLevel world, BlockPos feet) {
        boolean emergency = io.github.zoyluo.minecraftai.mode.CapabilityRuntime.decide(
                bot, io.github.zoyluo.minecraftai.mode.PrivilegedCapability.EMERGENCY_TELEPORT,
                "navsafe_suffocation").allowed();
        if (emergency && suffocationEscapeIdle(bot)) {
            // The cache must be invalidated: reaching this "buried" branch means a block just changed (a cave-in / live-burial
            // scenario calling setBlockState), so the Standability cache still reflects the world before the change.
            Standability.clearCache();
            int top = world.getMinY() + world.getHeight();
            for (int dy = 1; dy <= SUFFOCATION_CLIMB_UP && feet.getY() + dy < top - 1; dy++) {
                BlockPos candidate = feet.above(dy);
                if (Standability.isStandable(world, candidate)) {
                    bot.getActionPack().stopAll();
                    bot.teleportTo(world, candidate.getX() + 0.5D, candidate.getY(), candidate.getZ() + 0.5D,
                            Collections.emptySet(), bot.getYRot(), bot.getXRot(), true);
                    Standability.clearCache();
                    return true;
                }
            }
        }
        return escapeSuffocationByInputs(bot, world, feet);
    }

    /** The state of one bot's escape from a block, kept across ticks: the break it is doing and when it last logged. */
    private static final class SuffocationEscape {
        final io.github.zoyluo.minecraftai.action.BlockMiner miner = new io.github.zoyluo.minecraftai.action.BlockMiner();
        int lastLogTick = -1000;
    }

    private final Map<UUID, SuffocationEscape> suffocationEscapes = new ConcurrentHashMap<>();
    private static final double SUFFOCATION_VIEW_RANGE = 6.0D;

    /** No privileged escape may start while the bot is already walking or digging its own way out (that one carries on). */
    private boolean suffocationEscapeIdle(AIPlayerEntity bot) {
        SuffocationEscape state = suffocationEscapes.get(bot.getUUID());
        return bot.getActionPack().stepIdle() && (state == null || state.miner.target() == null);
    }

    /** The bot is not (or no longer) inside a block: whatever it was digging for its escape is over. */
    private void releaseSuffocationEscape(AIPlayerEntity bot) {
        SuffocationEscape state = suffocationEscapes.remove(bot.getUUID());
        if (state != null && state.miner.target() != null) {
            state.miner.cancel(bot);
        }
    }

    /**
     * Getting out of a block with real inputs and real digging (no teleport, no scan of what the bot cannot see):
     * (1) a body that only partly overlaps a block is shoved out the way a vanilla client shoves it (a small horizontal
     * velocity toward the nearest free side); (2) else a walked step onto an adjacent standable cell (the rules and the
     * once-per-origin-cell guard of a route's start); (3) else the bot digs itself out with the tool it has, at the real break
     * time: the block at its head first, then the one at its feet, only blocks it can see (a view ray from its own eye);
     * (4) else it logs {@code navsafe_suffocation_trapped}. A step or a break in flight carries on across ticks.
     *
     * @return true when this tick was taken over (an escape is in flight or was started)
     */
    boolean escapeSuffocationByInputs(AIPlayerEntity bot, ServerLevel world, BlockPos feet) {
        var pack = bot.getActionPack();
        if (!pack.stepIdle()) {
            return true;
        }
        SuffocationEscape state = suffocationEscapes.computeIfAbsent(bot.getUUID(), ignored -> new SuffocationEscape());
        int now = world.getServer().getTickCount();
        if (state.miner.target() != null) {
            return tickEscapeBreak(bot, state);
        }
        Standability.clearCache();
        if (io.github.zoyluo.minecraftai.action.WalkedStep.canPushOut(bot)) {
            pack.stopAll();
            pack.runStep(io.github.zoyluo.minecraftai.action.WalkedStep.begin(bot, bot.position(),
                    io.github.zoyluo.minecraftai.action.WalkedStep.Kind.PUSH_OUT, "navsafe_suffocation"));
            return true;
        }
        var walked = pack.adjacentStandableStep("navsafe_suffocation");
        if (walked != null) {
            pack.stopAll();
            pack.runStep(walked);
            return true;
        }
        BlockPos target = escapeBreakTarget(bot, world, feet);
        if (target != null) {
            pack.stopAll();
            state.miner.begin(bot, target);
            BotLog.danger(bot, "navsafe_suffocation_dig", "at", target.toShortString());
            return tickEscapeBreak(bot, state);
        }
        if (now - state.lastLogTick >= 40) {
            state.lastLogTick = now;
            BotLog.danger(bot, "navsafe_suffocation_trapped", "pos", feet.toShortString(),
                    "hp", String.format(java.util.Locale.ROOT, "%.1f", bot.getHealth()));
        }
        return false;
    }

    private boolean tickEscapeBreak(AIPlayerEntity bot, SuffocationEscape state) {
        var status = state.miner.tick(bot);
        if (status == io.github.zoyluo.minecraftai.action.BlockMiner.Status.FAILED) {
            BotLog.danger(bot, "navsafe_suffocation_dig_failed", "reason", state.miner.failureReason());
            return false;
        }
        return true;
    }

    /** The block to dig first: the one at the head, else the one at the feet, and only a block a view ray from the bot's eye reaches. */
    private static BlockPos escapeBreakTarget(AIPlayerEntity bot, ServerLevel world, BlockPos feet) {
        for (BlockPos candidate : new BlockPos[]{feet.above(), feet}) {
            BlockState state = world.getBlockState(candidate);
            if (state.getCollisionShape(world, candidate).isEmpty() || state.getDestroySpeed(world, candidate) < 0.0F) {
                continue;
            }
            var center = candidate.getCenter().subtract(bot.getEyePosition());
            var view = io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.castViewRay(bot, center.x, center.y, center.z,
                    SUFFOCATION_VIEW_RANGE, io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.ViewShape.COLLIDER);
            if (view.hit() && candidate.equals(view.pos())) {
                return candidate;
            }
        }
        return null;
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
        // The capability decision comes first: the search for a breathable spot scans the whole volume around the bot, cells it may
        // not be able to see, so it only runs for a rescue that is allowed to happen (never in strict survival).
        if (!io.github.zoyluo.minecraftai.mode.CapabilityRuntime.decide(
                bot, io.github.zoyluo.minecraftai.mode.PrivilegedCapability.EMERGENCY_TELEPORT,
                "navsafe_drowning").allowed()) {
            return false;
        }
        Optional<BlockPos> safe = cachedFindNearestBreathableStandable(bot, world, feet, now);
        if (safe.isEmpty()) {
            return false;
        }
        BlockPos to = safe.get();
        bot.getActionPack().stopAll();
        bot.teleportTo(world, to.getX() + 0.5D, to.getY(), to.getZ() + 0.5D,
                Collections.emptySet(), bot.getYRot(), bot.getXRot(), true);
        Standability.clearCache();
        return true;
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

    private boolean physicalStepTowardShore(AIPlayerEntity bot,
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
            if (beginRescueStep(bot, candidate, "navsafe_water_rescue")) {
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
