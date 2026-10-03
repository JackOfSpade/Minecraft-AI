package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.action.ActionPack;
import io.github.zoyluo.minecraftai.action.WalkedStep;
import io.github.zoyluo.minecraftai.action.WalkedStepRules;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mode.CapabilityRuntime;
import io.github.zoyluo.minecraftai.mode.FakePlayerMotion;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    // lease is valid follow owns every decision, including turning up for breath early. Operator
    // mode measures the direct column and normally leaves FollowOxygen.SURFACE_FLOOR_AIR room to
    // do that first. Strict mode deliberately assumes the full bounded rescue depth instead; its
    // conservative threshold equals that floor, so Nav takes ownership at the tie. Once either
    // threshold is reached the lease ends and follow makes NO movement at all, so neither undoes
    // the other's step.
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
    /**
     * A strict automatic rescue that has actually admitted a physical Nav step. It carries the
     * local strict search across that real stroke until dry ground, and remains part of the public
     * ownership fence between strokes so another task cannot claim the surfaced controller gap.
     * Follow and Baritone retain their explicit, verified high-air handoffs below, which clear
     * this session before publishing their own narrow leases.
     */
    private final Set<UUID> strictAutomaticWaterRescueSessions = ConcurrentHashMap.newKeySet();
    // SAFE-DROWN3: A physically proved route gets a deadline proportional to its remaining swim
    // cells, rather than a flat ten seconds. An operator emergency teleport is therefore a last
    // resort for a route that demonstrably stopped making progress, never a shortcut for a long
    // but normally swimmable crossing.
    private record WaterRescueDeadline(BlockPos feet, BlockPos shore, int routeCells,
                                       boolean hiddenWorldScan, int deadlineTick) {
    }
    private final Map<UUID, WaterRescueDeadline> waterRescueDeadlines = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> followSwimLeaseUntil = new ConcurrentHashMap<>();
    /**
     * A ShowTargetTask surface presentation uses this distinct, short high-air lease. It is not
     * Follow's broader water pursuit: its caller freshly proves a single top-water cell before
     * every renewal, and a low-air or lava condition immediately restores ordinary rescue.
     */
    private final Map<UUID, Integer> surfacePresentationWaterLeaseUntil = new ConcurrentHashMap<>();
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
    //   spread/recede every tick from vanilla scheduled fluid ticks, so an unbounded cache could
    //   hand back a route through a cell that is no longer actually passable. A short, bounded
    //   staleness window keeps that
    //   risk negligible without paying the full scan cost every tick. Before a cached route may
    //   influence a strict action, its immediate step and its proved shore are observed again;
    //   WalkedStep.refusal then re-verifies the specific destination cell's *current* state before
    //   moving. A stale route can therefore neither steer a strict bot with hidden information nor
    //   move it into now-unsafe terrain.
    private static final int WATER_SEARCH_CACHE_TICKS = 5;
    /**
     * Strict survival spreads the same complete bounded rescue BFS across server ticks. This is a
     * scheduling slice, not a route/visibility/movement bound: the continuation retains every
     * visible candidate until it finds a shore or exhausts the established rescue volume. It keeps
     * a wide open lake from turning one drowning tick into tens of thousands of raycasts.
     */
    private static final int STRICT_WATER_SEARCH_CANDIDATES_PER_TICK = 96;
    /**
     * Reserve the exact 26-cell vanilla adjacent swim envelope inside the same strict rescue
     * work slice. The remainder advances the retained BFS; neither number limits a route result.
     */
    private static final int STRICT_WATER_FALLBACK_WORK_PER_TICK = 26;
    private static final int STRICT_WATER_BFS_WORK_PER_TICK = STRICT_WATER_SEARCH_CANDIDATES_PER_TICK
            - STRICT_WATER_FALLBACK_WORK_PER_TICK;
    /** A ray endpoint kept just inside the exposed lower face of an adjacent rescue cell. */
    private static final double WATER_RESCUE_ADJACENT_FACE_INSET = 0.05D;
    /** A short-lived answer is reusable only under the perception context that proved it. */
    private record WaterSearchCache<T>(BlockPos feet, int computedTick, boolean hiddenBlockScan,
                                       int observationRadius, T result) {
    }
    private final Map<UUID, WaterSearchCache<WaterEscapeStep>> waterEscapeCache = new ConcurrentHashMap<>();
    /** Incomplete strict routes retain their BFS frontier instead of synchronously re-scanning it. */
    private final Map<UUID, StrictWaterEscapeSearch> strictWaterEscapeSearches = new ConcurrentHashMap<>();
    private final Map<UUID, WaterSearchCache<BlockPos>> breathableStandableCache = new ConcurrentHashMap<>();
    /**
     * A Nav emergency preempted a guarded Follow/rescue owner and may have started its own
     * successor. Until ActionPack is idle, no normal water planning may reclaim its movement.
     */
    private final Set<UUID> awaitingEmergencySuccessors = ConcurrentHashMap.newKeySet();

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
        strictAutomaticWaterRescueSessions.remove(id);
        waterRescueDeadlines.remove(id);
        followSwimLeaseUntil.remove(id);
        surfacePresentationWaterLeaseUntil.remove(id);
        baritoneWaterLeaseUntil.remove(id);
        waterEscapeCache.remove(id);
        strictWaterEscapeSearches.remove(id);
        breathableStandableCache.remove(id);
        awaitingEmergencySuccessors.remove(id);
        releaseSuffocationEscape(bot);
        releaseRescueStep(bot, true);
    }

    public void clearAll() {
        nextLogTick.clear();
        waterRescueShore.clear();
        strictAutomaticWaterRescueSessions.clear();
        waterRescueDeadlines.clear();
        followSwimLeaseUntil.clear();
        surfacePresentationWaterLeaseUntil.clear();
        baritoneWaterLeaseUntil.clear();
        waterEscapeCache.clear();
        strictWaterEscapeSearches.clear();
        breathableStandableCache.clear();
        for (SuffocationEscape escape : suffocationEscapes.values()) {
            releaseSuffocationEscape(escape.bot);
        }
        suffocationEscapes.clear();
        for (RescueStepAdmission admission : rescueSteps.values()) {
            admission.bot().getActionPack().cancelStep(admission.lease());
        }
        rescueSteps.clear();
        awaitingEmergencySuccessors.clear();
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
        UUID id = bot.getUUID();
        return waterRescueShore.containsKey(id)
                || strictAutomaticWaterRescueSessions.contains(id)
                || rescueStepOwnsMovement(bot);
    }

    /**
     * True only while NavSafetyNet still owns the exact live guarded stroke it admitted. A
     * yielding follower uses this to clear its own lease without zeroing the rescue's inputs
     * between the ActionPack controller update and vanilla movement physics.
     */
    boolean rescueStepOwnsMovement(AIPlayerEntity bot) {
        RescueStepAdmission admission = rescueSteps.get(bot.getUUID());
        return admission != null && bot.getActionPack().stepInFlightFor(admission.lease());
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
        // A prior rescue is for an accidental water entry.  An actively renewed, high-air swim
        // follow is a different, short-lived intent and must not inherit that old controller.
        // Remove its exact guarded admission before publishing the new Follow lease; merely
        // dropping this bookkeeping would make tickBot return early with ActionPack fenced.
        releaseRescueStep(bot, true);
        followSwimLeaseUntil.put(bot.getUUID(), bot.level().getServer().getTickCount() + FOLLOW_SWIM_LEASE_TICKS);
        waterRescueShore.remove(bot.getUUID());
        strictAutomaticWaterRescueSessions.remove(bot.getUUID());
        waterRescueDeadlines.remove(bot.getUUID());
        strictWaterEscapeSearches.remove(bot.getUUID());
    }

    /** Clears the narrow FollowTask swim lease on cancellation or any non-swim transition. */
    void clearFollowSwim(AIPlayerEntity bot) {
        followSwimLeaseUntil.remove(bot.getUUID());
    }

    /**
     * Authorizes a briefly held, observed water-surface presentation. The caller supplies the
     * exact water cell it has just proved to be breathable, so this cannot become a general
     * permission to dive or ignore a hidden water crisis.
     *
     * @return whether the high-air, lava-free surface lease was admitted
     */
    boolean renewObservedSurfacePresentationWater(AIPlayerEntity bot, BlockPos waterCell) {
        if (waterCell == null || followSwimMustYield(bot) || bot.isInLava()
                || bot.level().getFluidState(bot.blockPosition()).is(FluidTags.LAVA)
                || SwimRoute.observedCell(bot, bot.level(), waterCell, false)
                != SwimRoute.Cell.WATER_WITH_AIR_ABOVE) {
            clearObservedSurfacePresentationWater(bot);
            return false;
        }
        // A prior rescue belongs to an accidental entry. Replace only its exact admission after
        // the caller's fresh surface proof, so it cannot fight the guarded surface stroke.
        releaseRescueStep(bot, true);
        surfacePresentationWaterLeaseUntil.put(bot.getUUID(),
                bot.level().getServer().getTickCount() + FOLLOW_SWIM_LEASE_TICKS);
        waterRescueShore.remove(bot.getUUID());
        strictAutomaticWaterRescueSessions.remove(bot.getUUID());
        waterRescueDeadlines.remove(bot.getUUID());
        strictWaterEscapeSearches.remove(bot.getUUID());
        return true;
    }

    /** Ends the narrow show-target surface lease without affecting a real safety successor. */
    void clearObservedSurfacePresentationWater(AIPlayerEntity bot) {
        surfacePresentationWaterLeaseUntil.remove(bot.getUUID());
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
        strictAutomaticWaterRescueSessions.remove(bot.getUUID());
        waterRescueDeadlines.remove(bot.getUUID());
        strictWaterEscapeSearches.remove(bot.getUUID());
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

    private boolean hasObservedSurfacePresentationWaterLease(AIPlayerEntity bot, int currentTick) {
        Integer until = surfacePresentationWaterLeaseUntil.get(bot.getUUID());
        if (until == null || until < currentTick || followSwimMustYield(bot)) {
            surfacePresentationWaterLeaseUntil.remove(bot.getUUID());
            return false;
        }
        return true;
    }

    /** Records the only intentional way a stale guarded owner can hand movement to safety. */
    private void noteEmergencyPreemption(AIPlayerEntity bot, boolean preempted) {
        if (preempted) {
            awaitingEmergencySuccessors.add(bot.getUUID());
        }
    }

    /**
     * A preempted owner must never re-plan over its unguarded/guarded safety successor. Clear the
     * latch only after ActionPack reports no active step; an exact Nav owner then releases any
     * completed emergency lease during its normal lifecycle reconciliation.
     */
    private boolean emergencySuccessorOwnsMovement(AIPlayerEntity bot) {
        UUID id = bot.getUUID();
        if (!awaitingEmergencySuccessors.contains(id)) {
            return false;
        }
        if (!bot.getActionPack().stepIdle()) {
            return true;
        }
        awaitingEmergencySuccessors.remove(id);
        return false;
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
            noteEmergencyPreemption(bot, bot.getActionPack().preemptGuardedStepForEmergency());
            escapeLava(bot, world, feet);
            throttledLog(server, bot, "navsafe_lava_escape", feet);
            return true;
        }

        // A prior emergency successor remains the sole movement owner across later normal water
        // ticks. Keep lava and buried-suffocation above this gate: a new actual emergency may
        // intentionally preempt the earlier one.
        if (emergencySuccessorOwnsMovement(bot)) {
            return true;
        }

        // 2) Drowning/water crisis: once triggered, keep taking over until ashore (see the waterRescueShore comment).
        if (hasFollowSwimLease(bot, server.getTickCount())) {
            // The lease is renewed only by an active FollowTask and only above the safety oxygen
            // threshold.  Once air falls, the normal branch below immediately resumes rescue.
            return false;
        }
        if (hasObservedSurfacePresentationWaterLease(bot, server.getTickCount())) {
            // ShowTargetTask freshly proves a breathable top-water cell before renewing this
            // short lease. Low air, lava, or expiry falls straight through to ordinary rescue.
            return false;
        }
        if (hasBaritoneWaterLease(bot, server.getTickCount())) {
            // Baritone is driving this bot through water on a route that was allowed to (same oxygen rule as above).
            return false;
        }
        UUID waterRescueId = bot.getUUID();
        boolean inCrisis = waterRescueShore.containsKey(waterRescueId)
                || strictAutomaticWaterRescueSessions.contains(waterRescueId);
        if (!inCrisis && bot.isUnderWater()) {
            // Treat the newly submerged bot as a crisis for this tick, but do not publish a
            // placeholder shore latch yet. A high-air Follow recovery may yield to a real rescue
            // step without turning that brief handoff into a persistent water-rescue state.
            inCrisis = true;
        }
        if (inCrisis) {
            // Fluid blocks can disappear/spread from vanilla scheduled ticks without going
            // through our block actions. Water-rescue checks therefore use isStandableFresh for
            // their handful of local proofs rather than evicting every bot's shared standability
            // memo on every drowning tick.
            // Release condition: once the bot reaches a dry, standable position verified by
            // server-side block state -> the crisis is over, hand control back.
            // The fake player's cell-by-cell physical movement has no client landing packet, so
            // isOnGround() can still be false even after it is already standing on solid ground;
            // continuing to rely on it would shuttle the bot back and forth between two dry cells.
            if (isDryStandable(bot, world, feet)) {
                UUID id = bot.getUUID();
                waterRescueShore.remove(id);
                strictAutomaticWaterRescueSessions.remove(id);
                waterRescueDeadlines.remove(id);
                // A retained strict frontier is meaningful only for this water crisis. Do not
                // let it survive a normal landing and later bias a nearby, unrelated entry.
                waterEscapeCache.remove(id);
                strictWaterEscapeSearches.remove(id);
                // The old navigator may still contain the DROP_DOWN edge that caused the rescue.
                // Cancel the complete action stack before returning control or it will execute the
                // same wet edge again on the next tick.
                releaseRescueStep(bot, true);
                bot.getActionPack().stopAll();
                return false;
            }
            int now = server.getTickCount();
            // SAFE-DROWN: Air is critical and there's no air above to surface into (a water pocket
            // capped by stone) -> emergency-teleport to the nearest breathable landing spot.
            // Do not read an unobserved air column merely to decide whether to attempt a rescue
            // that strict survival cannot execute. emergencyTeleportToAir repeats the decision at
            // its privileged boundary before its own volume scan.
            boolean emergencyTeleportAllowed = bot.getAirSupply() <= EMERGENCY_AIR
                    && CapabilityRuntime.decide(bot, PrivilegedCapability.EMERGENCY_TELEPORT,
                    "navsafe_drowning_probe").allowed();
            if (emergencyTeleportAllowed && !breathableAbove(world, feet)) {
                if (emergencyTeleportToAir(bot, world, feet, now)) {
                    UUID id = bot.getUUID();
                    waterRescueShore.remove(id);
                    strictAutomaticWaterRescueSessions.remove(id);
                    waterRescueDeadlines.remove(id);
                    waterEscapeCache.remove(id);
                    strictWaterEscapeSearches.remove(id);
                    throttledLog(server, bot, "navsafe_drown_teleport", feet);
                    return true;
                }
            }
            // A rescue step in flight carries on by itself: nothing is re-planned until it has ended. A step somebody else
            // left running is dropped, the rescue owns the bot.
            // Latch the current decision before an owned step is allowed to carry this tick.
            // rescueStepInFlight cancels a step admitted under a different capability state.
            boolean hiddenWaterScan = canUseHiddenWaterScan(bot);
            if (rescueStepInFlight(bot, hiddenWaterScan)) {
                throttledLog(server, bot, "navsafe_water_step", feet);
                return true;
            }
            // A step that ends on the bank is done at the cell's edge, with the body still partly over the water: the last bit is a
            // walk to the middle of the dry cell (the release above needs the body out of the water), not a new escape from a cell that
            // is already dry.
            boolean dryCellForRecenter = hiddenWaterScan
                    ? isFreshDryStandableCell(world, feet)
                    : observedWaterEscapeCell(bot, world, feet, false) == WaterEscapeCell.DRY;
            if (bot.isInWater() && dryCellForRecenter && WalkedStep.supported(bot)
                    && beginRescueRecenter(bot, feet, hiddenWaterScan)) {
                throttledLog(server, bot, "navsafe_water_step", feet);
                return true;
            }
            // A full-water-volume route proof is an operator capability. Strict survival still
            // runs the same bounded BFS, but its frontier may only enter cells the bot can observe
            // right now; this preserves ordinary physical exploration without inventing a shore
            // behind a wall. The boolean is also part of the cache key so a profile/capability
            // change can never reuse a privileged route in strict survival.
            // First prove a physically connected water route. The old Euclidean-only shore
            // choice could select a dry cell directly behind a wall and then reject every first
            // step because it temporarily increased straight-line distance. In a flooded cave
            // that left the bot motionless until strict-survival denied the teleport fallback.
            WaterEscapeStep escape = cachedFindPhysicalWaterEscape(bot, world, feet, now, hiddenWaterScan);
            if (escape != null && !reproveWaterEscapeStep(bot, world, escape, hiddenWaterScan)) {
                // Do not keep retrying a route whose next action or shore proof went stale. The
                // local observable fallback below can still move this tick; the next one will
                // rebuild the bounded route from the current feet cell.
                waterEscapeCache.remove(bot.getUUID());
                strictWaterEscapeSearches.remove(bot.getUUID());
                waterRescueDeadlines.remove(bot.getUUID());
                escape = null;
            }
            if (escape != null) {
                // An explicit request and an operator-proved route retain the public shore
                // latch. A newly submerged strict bot gets that durable ownership only after
                // beginRescueStep has admitted a real guarded stroke below: otherwise a brief
                // high-air controller handoff can surface once and incorrectly stay latched.
                if (hiddenWaterScan || waterRescueShore.containsKey(waterRescueId)) {
                    waterRescueShore.put(waterRescueId, escape.shore().immutable());
                }
                scheduleWaterRescueDeadline(bot, feet, escape, now, hiddenWaterScan);
                if (waterRescueDeadlineExceeded(bot, feet, now, hiddenWaterScan)) {
                    if (emergencyTeleportToAir(bot, world, feet, now)) {
                        waterRescueShore.remove(waterRescueId);
                        strictAutomaticWaterRescueSessions.remove(waterRescueId);
                        waterRescueDeadlines.remove(waterRescueId);
                        throttledLog(server, bot, "navsafe_drown_teleport", feet);
                        return true;
                    }
                }
                if (beginRescueStep(bot, escape.next(), "navsafe_water_rescue", hiddenWaterScan)) {
                    throttledLog(server, bot, "navsafe_water_step", feet);
                    return true;
                }
            }
            boolean surfaceAirUrgent = bot.getAirSupply() <= surfaceAirThreshold(bot, hiddenWaterScan);
            // Surface only when oxygen is actually low. At full air this used to pre-empt the
            // cached shore route from a lower water cell, then the shore controller deliberately
            // stepped back down from the top cell on the next tick. The two correct local actions
            // therefore formed an endless Y/Y+1 policy oscillation. A completed connected shore
            // route remains the first choice above; without one, urgent breathable air outranks
            // speculative same-level exploration.
            if (surfaceAirUrgent && physicalStepTowardAir(bot, world, feet, hiddenWaterScan)) {
                throttledLog(server, bot, "navsafe_surface_for_air", feet);
                return true;
            }
            // A strict bot may know enough to take one visible water or dry step without yet
            // knowing a complete route to shore. Let it explore that local, observation-proven
            // frontier rather than freezing until it drowns; it will re-prove the next cell after
            // every real walked step.
            if (!hiddenWaterScan && beginObservableWaterRescueStep(bot, world, feet,
                    strictWaterEscapeSearches.get(bot.getUUID()), surfaceAirUrgent)) {
                throttledLog(server, bot, "navsafe_water_step", feet);
                return true;
            }
            // Privileged local rescue path: use the cached shore if it is still valid, otherwise search
            // again (the nearest landing spot that is both standable and has air at feet and head).
            if (hiddenWaterScan) {
                BlockPos shore = waterRescueShore.get(bot.getUUID());
                if (shore == null || shore.equals(feet) || !Standability.isStandableFresh(world, shore)) {
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
            } else {
                // Do not turn or steer toward a shore learned from a hidden-world scan. A strict
                // automatic rescue becomes durable only after a real guarded step is admitted;
                // without one, this tick can yield back to a high-air controller rather than
                // publishing a placeholder crisis latch.
                bot.getActionPack().setForward(0.0F);
            }
            bot.getActionPack().setSprinting(false);
            // A held jump is a physical upward stroke, not an idle-safe key.  The complete
            // shore route above may always take its proved next step, but this no-route fallback
            // may surface only once the same measured oxygen policy made air urgent.
            bot.getActionPack().setJumping(surfaceAirUrgent);
            throttledLog(server, bot, "navsafe_surface_for_air", feet);
            return true;
        }

        return false;
    }

    /** Memoized front for {@link #findPhysicalWaterEscape} -- see WATER_SEARCH_CACHE_TICKS above. */
    private WaterEscapeStep cachedFindPhysicalWaterEscape(AIPlayerEntity bot, ServerLevel world,
                                                            BlockPos feet, int now, boolean hiddenWaterScan) {
        if (hiddenWaterScan) {
            // A strict continuation contains visibility-era facts and must never survive an
            // operator transition only to be reused if strict survival is restored later.
            strictWaterEscapeSearches.remove(bot.getUUID());
        }
        WaterSearchCache<WaterEscapeStep> cached = waterEscapeCache.get(bot.getUUID());
        if (cached != null && cached.hiddenBlockScan() == hiddenWaterScan
                && cached.observationRadius() == SwimRoute.observationRadius(bot)
                && waterSearchCacheValid(cached.feet(), cached.computedTick(), feet, now)) {
            return cached.result();
        }
        if (!hiddenWaterScan) {
            return continueStrictWaterEscapeSearch(bot, world, feet, now);
        }
        WaterEscapeStep escape = findPhysicalWaterEscape(bot, world, feet, hiddenWaterScan);
        waterEscapeCache.put(bot.getUUID(), new WaterSearchCache<>(feet.immutable(), now, hiddenWaterScan,
                SwimRoute.observationRadius(bot), escape));
        return escape;
    }

    /**
     * Advances a strict rescue's retained BFS frontier by a bounded amount of server work. A
     * finished answer remains in the normal short cache so the existing immediate-step/shore
     * reproof applies unchanged; an incomplete answer deliberately returns {@code null} so this
     * tick can use only the adjacent observable fallback or air stroke.
     */
    private WaterEscapeStep continueStrictWaterEscapeSearch(AIPlayerEntity bot, ServerLevel world,
                                                            BlockPos feet, int now) {
        UUID id = bot.getUUID();
        StrictWaterEscapeSearch search = strictWaterEscapeSearches.get(id);
        int observationRadius = SwimRoute.observationRadius(bot);
        if (search == null || !search.accepts(feet, observationRadius)) {
            search = new StrictWaterEscapeSearch(feet);
            strictWaterEscapeSearches.put(id, search);
        }
        if (!search.admitCurrentFeet(feet)) {
            // A non-adjacent displacement cannot be connected to the retained observed tree by
            // an input-driven step, so it is the one safe re-root boundary.
            search = new StrictWaterEscapeSearch(feet);
            strictWaterEscapeSearches.put(id, search);
        }
        // World edits can open a previously occluded ray without moving the bot. Revalidate one
        // retained UNKNOWN edge on a later server tick, rather than requiring a movement that
        // strict survival cannot safely invent. A real viewpoint change still releases its old
        // frontier under the normal bounded work budget.
        search.beginObservationTick(now);
        WaterEscapeStep escape = search.advance(bot, world, feet, STRICT_WATER_BFS_WORK_PER_TICK);
        if (escape != null || search.exhausted()) {
            strictWaterEscapeSearches.remove(id, search);
            waterEscapeCache.put(id, new WaterSearchCache<>(feet.immutable(), now, false,
                    observationRadius, escape));
        }
        return escape;
    }

    /** Memoized front for {@link #findNearestBreathableStandable} -- see WATER_SEARCH_CACHE_TICKS above. */
    private Optional<BlockPos> cachedFindNearestBreathableStandable(AIPlayerEntity bot, ServerLevel world,
                                                                     BlockPos feet, int now) {
        WaterSearchCache<BlockPos> cached = breathableStandableCache.get(bot.getUUID());
        if (cached != null && cached.observationRadius() == SwimRoute.observationRadius(bot)
                && waterSearchCacheValid(cached.feet(), cached.computedTick(), feet, now)) {
            return Optional.ofNullable(cached.result());
        }
        Optional<BlockPos> found = findNearestBreathableStandable(world, feet);
        breathableStandableCache.put(bot.getUUID(),
                new WaterSearchCache<>(feet.immutable(), now, false, SwimRoute.observationRadius(bot),
                        found.orElse(null)));
        return found;
    }

    /** Whether this tick may use a route proof that reads cells outside the bot's observation. */
    private static boolean canUseHiddenWaterScan(AIPlayerEntity bot) {
        return CapabilityRuntime.decide(bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN,
                "navsafe_water_rescue").allowed();
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
        return surfaceAirThreshold(bot, canUseHiddenWaterScan(bot));
    }

    /**
     * Strict survival has no right to measure an unseen vertical water column. It instead assumes
     * the bounded rescue depth, which starts a visible upward swim early enough for the deepest
     * supported shaft without learning anything about that shaft. Operator mode retains the more
     * precise measured-depth oxygen margin.
     */
    private static int surfaceAirThreshold(AIPlayerEntity bot, boolean hiddenWaterScan) {
        if (!hiddenWaterScan) {
            return surfaceAirThresholdForDepth(RESCUE_RADIUS_V);
        }
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
                                             WaterEscapeStep escape, int now, boolean hiddenWorldScan) {
        UUID id = bot.getUUID();
        WaterRescueDeadline existing = waterRescueDeadlines.get(id);
        if (existing != null && existing.feet().equals(feet)
                && existing.shore().equals(escape.shore())
                && existing.routeCells() == escape.routeCells()
                && existing.hiddenWorldScan() == hiddenWorldScan) {
            return;
        }
        waterRescueDeadlines.put(id, new WaterRescueDeadline(feet.immutable(), escape.shore().immutable(), escape.routeCells(),
                hiddenWorldScan, now + waterRescueTimeoutTicks(escape.routeCells())));
    }

    private boolean waterRescueDeadlineExceeded(AIPlayerEntity bot, BlockPos feet, int now,
                                                boolean hiddenWorldScan) {
        WaterRescueDeadline deadline = waterRescueDeadlines.get(bot.getUUID());
        return deadline != null && deadline.feet().equals(feet)
                && deadline.hiddenWorldScan() == hiddenWorldScan && now > deadline.deadlineTick();
    }

    private static WaterEscapeStep findPhysicalWaterEscape(AIPlayerEntity bot, ServerLevel world,
                                                            BlockPos start, boolean hiddenWaterScan) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Map<BlockPos, BlockPos> previous = new HashMap<>();
        HashSet<BlockPos> visited = new HashSet<>();
        BlockPos origin = start.immutable();
        queue.add(origin);
        visited.add(origin);

        while (!queue.isEmpty()) {
            BlockPos current = queue.removeFirst();
            for (BlockPos candidate : waterEscapeNeighbors(current)) {
                if (!withinRescueBounds(candidate, origin) || !visited.add(candidate)) {
                    continue;
                }
                WaterEscapeCell cell = observedWaterEscapeCell(bot, world, candidate, hiddenWaterScan);
                if (cell == null) {
                    continue;
                }
                previous.put(candidate, current);
                if (cell == WaterEscapeCell.DRY) {
                    return waterEscapeStep(previous, origin, candidate);
                }
                if (cell == WaterEscapeCell.WATER) {
                    queue.addLast(candidate);
                }
            }
        }
        return null;
    }

    /** The established physical rescue volume, shared by synchronous operator and sliced strict searches. */
    private static boolean withinRescueBounds(BlockPos candidate, BlockPos origin) {
        return Math.abs(candidate.getX() - origin.getX()) <= RESCUE_RADIUS_H
                && Math.abs(candidate.getZ() - origin.getZ()) <= RESCUE_RADIUS_H
                && Math.abs(candidate.getY() - origin.getY()) <= RESCUE_RADIUS_V;
    }

    private static WaterEscapeStep waterEscapeStep(Map<BlockPos, BlockPos> previous,
                                                   BlockPos origin, BlockPos shore) {
        BlockPos first = shore;
        while (previous.containsKey(first) && !previous.get(first).equals(origin)) {
            first = previous.get(first);
        }
        return new WaterEscapeStep(first.immutable(), shore.immutable(),
                cellsFromOrigin(previous, origin, shore));
    }

    /**
     * Reconstructs a retained BFS tree from the bot's <em>current</em> cell, not merely from the
     * session's original root. A local fallback may have walked a different observed branch while
     * the search was pending; following parent links to the lowest common ancestor then down to
     * the shore yields an adjacent first step and never teleports or invents a new terrain edge.
     */
    private static WaterEscapeStep waterEscapeStepFromCurrent(Map<BlockPos, BlockPos> previous,
                                                              BlockPos current, BlockPos shore) {
        HashSet<BlockPos> currentAncestors = new HashSet<>();
        for (BlockPos cursor = current; cursor != null; cursor = previous.get(cursor)) {
            currentAncestors.add(cursor);
        }
        List<BlockPos> shoreToAncestor = new java.util.ArrayList<>();
        BlockPos common = shore;
        while (!currentAncestors.contains(common)) {
            shoreToAncestor.add(common);
            common = previous.get(common);
            if (common == null) {
                return null;
            }
        }
        BlockPos next = null;
        int cells = 0;
        for (BlockPos cursor = current; !cursor.equals(common); cursor = previous.get(cursor)) {
            BlockPos parent = previous.get(cursor);
            if (parent == null) {
                return null;
            }
            if (next == null) {
                next = parent;
            }
            cells++;
        }
        if (next == null && !shoreToAncestor.isEmpty()) {
            // The list is shore -> parent -> ...; its last member is the first child from the
            // common ancestor toward the shore.
            next = shoreToAncestor.get(shoreToAncestor.size() - 1);
        }
        cells += shoreToAncestor.size();
        return next == null ? null : new WaterEscapeStep(next.immutable(), shore.immutable(), Math.max(1, cells));
    }

    /**
     * A retained strict BFS. The work slice never discards a reachable observed cell: it merely
     * resumes from {@link #queue} on the next server tick. A real adjacent fallback step is
     * attached to the same observed tree, so visible work accumulates while the bot explores;
     * only leaving the established rescue volume, a non-adjacent displacement, or a perception
     * configuration change starts a fresh session.
     */
    private static final class StrictWaterEscapeSearch {
        private final BlockPos origin;
        private final int observationRadius;
        private final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        /** A frontier cell is queued at most once while it awaits expansion. */
        private final HashSet<BlockPos> queued = new HashSet<>();
        private final Map<BlockPos, BlockPos> previous = new HashMap<>();
        /** Candidates examined from the current viewpoint; valid cells also have a tree parent. */
        private final HashSet<BlockPos> visited = new HashSet<>();
        /**
         * Visibility misses are retried after either a real viewpoint change or a later server
         * tick. The latter matters because a nearby occluder can be removed while a strict bot is
         * stationary. A fixed view re-proves only one retained edge on a later tick; a real
         * viewpoint change still releases its old frontier under the cooperative work slice, so a
         * large occluded shoreline cannot make the crisis tick iterate an entire rescue volume.
         */
        private final ArrayDeque<UnknownCandidate> unknowns = new ArrayDeque<>();
        private final HashSet<BlockPos> unknownCells = new HashSet<>();
        private BlockPos current;
        private List<BlockPos> currentNeighbors;
        private int nextNeighbor;
        private BlockPos lastFeet;
        /** The preceding physical feet cell, used only to avoid an immediate observed water reversal. */
        private BlockPos previousFeet;
        /** An unfinished strict frontier may defer the sole observed reverse for one bounded slice, never forever. */
        private boolean immediateWaterBacktrackDeferred;
        private int observationEpoch;
        /** Last server tick whose one stationary UNKNOWN retry allowance was reset. */
        private int lastObservationTick = Integer.MIN_VALUE;
        /** A stationary view re-proves at most one previously retained unknown edge each tick. */
        private boolean stationaryUnknownRetryUsed;
        private boolean retryTurn = true;
        private boolean exhausted;
        /** Retained strict local fallback: scan the legal 26-cell envelope once per feet cell. */
        private BlockPos fallbackFeet;
        private List<BlockPos> fallbackNeighbors;
        private final ArrayList<ObservedWaterEscapeCell> fallbackCandidates = new ArrayList<>();
        private int nextFallbackNeighbor;
        private int nextFallbackCandidate;
        private boolean fallbackScanned;

        private StrictWaterEscapeSearch(BlockPos origin) {
            this.origin = origin.immutable();
            this.observationRadius = Math.max(1, MinecraftAiConfig.get().perception().radius());
            enqueue(this.origin);
            visited.add(this.origin);
            lastFeet = this.origin;
            previousFeet = this.origin;
        }

        private boolean accepts(BlockPos feet, int currentObservationRadius) {
            return observationRadius == currentObservationRadius && withinRescueBounds(feet, origin);
        }

        /**
         * Re-roots the route *view* at a physically adjacent fallback landing without throwing
         * away the known BFS tree. The new feet cell was just admitted by a strict walked step,
         * so attaching it contains no new world read or hidden route fact.
         */
        private boolean admitCurrentFeet(BlockPos feet) {
            boolean moved = !feet.equals(lastFeet);
            BlockPos formerFeet = lastFeet;
            if (moved) {
                // Every retained edge is connected to the last physical landing. A non-adjacent
                // relocation (another controller, a command, or a teleport) must not inherit
                // that tree merely because it happens to name an older visited cell.
                if (!adjacent(lastFeet, feet)) {
                    return false;
                }
                // A real viewpoint change earns one fresh anti-ping-pong deferral. It must not
                // inherit an old pending UNKNOWN edge as a permanent veto of its only known exit.
                immediateWaterBacktrackDeferred = false;
                // Do not synchronously revisit every occluded candidate here. The new epoch
                // merely makes the retained unknown queue eligible for the normal work slice.
                observationEpoch++;
                exhausted = false;
            }
            if (!connected(feet)) {
                if (!connected(lastFeet)) {
                    return false;
                }
                // `feet` may have been an unknown candidate earlier. The successful physical
                // fallback is the new observed edge that makes it part of the retained tree.
                visited.add(feet.immutable());
                unknownCells.remove(feet);
                previous.put(feet.immutable(), lastFeet.immutable());
                enqueue(feet.immutable());
                exhausted = false;
            }
            if (moved) {
                previousFeet = formerFeet.immutable();
            }
            lastFeet = feet.immutable();
            return true;
        }

        /**
         * A ray's result is current observation, not a permanent fact about a stationary bot.
         * When the world changes, an occluding block can disappear without a physical fallback
         * being legal or necessary. Each new server tick grants one retained UNKNOWN edge a fresh
         * ray; a physical viewpoint change still advances the epoch and releases its old
         * frontier. Neither path turns an unobserved edge into a route without a fresh ray and
         * cell proof.
         */
        private void beginObservationTick(int now) {
            if (lastObservationTick == now) {
                return;
            }
            lastObservationTick = now;
            stationaryUnknownRetryUsed = false;
            if (!unknowns.isEmpty()) {
                exhausted = false;
            }
        }

        private boolean exhausted() {
            return exhausted && fallbackScanned && nextFallbackCandidate >= fallbackCandidates.size();
        }

        private WaterEscapeStep advance(AIPlayerEntity bot, ServerLevel world, BlockPos feet, int workBudget) {
            int work = 0;
            while (work < workBudget) {
                boolean retryReady = hasEligibleUnknown();
                boolean frontierReady = current != null || !queue.isEmpty();
                if (!retryReady && !frontierReady) {
                    // An unknown from this exact observation epoch remains deliberately pending.
                    // A physical viewpoint change or its one later server-tick revalidation can
                    // make it eligible; caching an empty answer here would discard that future
                    // observable work.
                    exhausted = unknowns.isEmpty();
                    return null;
                }

                WaterEscapeStep escape;
                if (retryReady && (retryTurn || !frontierReady)) {
                    // A retry dequeue, including a stale entry, is a unit of work. Alternating
                    // it with the BFS frontier makes both the retained exploration and newly
                    // observable edge work live without imposing a route-result cap.
                    work++;
                    escape = retryOneUnknown(bot, world, feet);
                    retryTurn = false;
                } else {
                    // Loading a frontier node, closing its partial neighbour cursor, every
                    // skipped neighbour, and every observed neighbour all consume one unit.
                    // That keeps queue bookkeeping itself from becoming an unbounded tick cost.
                    work++;
                    escape = advanceOneFrontierOperation(bot, world, feet);
                    retryTurn = true;
                }
                if (escape != null) {
                    return escape;
                }
            }
            return null;
        }

        /**
         * Advances the fully legal local swim envelope without adding work beyond this rescue
         * tick's shared 96-operation budget. It retains the observed candidates until every
         * geometry slot is examined, preserving dry-first ranking without rescanning 26 rays
         * after every incomplete BFS slice.
         */
        private BlockPos nextObservableFallback(AIPlayerEntity bot, ServerLevel world, BlockPos feet,
                                                int workBudget, boolean allowWaterExploration) {
            if (!feet.equals(fallbackFeet)) {
                fallbackFeet = feet.immutable();
                fallbackNeighbors = waterEscapeNeighbors(fallbackFeet);
                fallbackCandidates.clear();
                nextFallbackNeighbor = 0;
                nextFallbackCandidate = 0;
                fallbackScanned = false;
            }
            int work = 0;
            while (!fallbackScanned && work < workBudget) {
                if (nextFallbackNeighbor >= fallbackNeighbors.size()) {
                    fallbackCandidates.sort(java.util.Comparator.comparingInt(candidate ->
                            observableWaterStepPriority(fallbackFeet, candidate.cell(), candidate.kind())));
                    fallbackScanned = true;
                    break;
                }
                BlockPos candidate = fallbackNeighbors.get(nextFallbackNeighbor++);
                work++;
                WaterEscapeCell cell = observedWaterEscapeCell(bot, world, candidate, false);
                if (cell != null && !(cell == WaterEscapeCell.WATER
                        && hasVisibleSolidDiagonalWaterCorner(bot, fallbackFeet, candidate))) {
                    fallbackCandidates.add(new ObservedWaterEscapeCell(candidate.immutable(), cell));
                }
            }
            if (!fallbackScanned && nextFallbackNeighbor >= fallbackNeighbors.size()) {
                fallbackCandidates.sort(java.util.Comparator.comparingInt(candidate ->
                        observableWaterStepPriority(fallbackFeet, candidate.cell(), candidate.kind())));
                fallbackScanned = true;
            }
            if (!fallbackScanned || nextFallbackCandidate >= fallbackCandidates.size()) {
                return null;
            }
            ObservedWaterEscapeCell candidate = fallbackCandidates.get(nextFallbackCandidate);
            // A water-rescue request can exist while the bot still has full lungs (for example
            // after a transient entry). Preserve the established oxygen policy: an immediately
            // visible dry bank and same-level water exploration remain useful, but a vertical
            // surface/dive stroke waits until the measured rescue threshold asks for air. The
            // retained cursor is deliberately not consumed, so that exact observed stroke becomes
            // available immediately when the air boundary is crossed.
            if (!allowWaterExploration && candidate.kind() != WaterEscapeCell.DRY
                    && candidate.cell().getY() != fallbackFeet.getY()) {
                return null;
            }
            if (isImmediateWaterBacktrack(candidate)) {
                int alternative = nextNonBacktrackingFallbackCandidate(
                        nextFallbackCandidate + 1, allowWaterExploration);
                if (alternative >= 0) {
                    // The current candidate is an observed, legal escape hatch, but a different
                    // observed stroke is available now. Consume the reversal rather than bouncing
                    // between two surface cells and starving the retained BFS of a new viewpoint.
                    nextFallbackCandidate = alternative;
                    candidate = fallbackCandidates.get(alternative);
                } else if (!exhausted && !immediateWaterBacktrackDeferred) {
                    // A retained frontier (most often one formerly hidden forward edge) gets one
                    // bounded chance to re-prove before the bot reverses. An UNKNOWN that remains
                    // UNKNOWN is pending evidence, not a permanent veto of this only proved exit.
                    immediateWaterBacktrackDeferred = true;
                    return null;
                }
            }
            immediateWaterBacktrackDeferred = false;
            nextFallbackCandidate++;
            return candidate.cell();
        }

        private boolean isImmediateWaterBacktrack(ObservedWaterEscapeCell candidate) {
            return candidate.kind() == WaterEscapeCell.WATER
                    && candidate.cell().equals(previousFeet);
        }

        /** Finds a currently usable observed fallback without bypassing the low-air vertical gate. */
        private int nextNonBacktrackingFallbackCandidate(int first, boolean allowWaterExploration) {
            for (int index = first; index < fallbackCandidates.size(); index++) {
                ObservedWaterEscapeCell alternative = fallbackCandidates.get(index);
                if (isImmediateWaterBacktrack(alternative)
                        || (!allowWaterExploration && alternative.kind() != WaterEscapeCell.DRY
                        && alternative.cell().getY() != fallbackFeet.getY())) {
                    continue;
                }
                return index;
            }
            return -1;
        }

        private boolean hasEligibleUnknown() {
            UnknownCandidate unknown = unknowns.peekFirst();
            return unknown != null && (unknown.observationEpoch() < observationEpoch
                    || (!stationaryUnknownRetryUsed && unknown.observedTick() < lastObservationTick));
        }

        /** Performs exactly one queue/cursor/neighbour operation from the retained BFS. */
        private WaterEscapeStep advanceOneFrontierOperation(AIPlayerEntity bot, ServerLevel world,
                                                             BlockPos feet) {
            if (current == null) {
                current = queue.pollFirst();
                if (current == null) {
                    return null;
                }
                queued.remove(current);
                currentNeighbors = waterEscapeNeighbors(current);
                nextNeighbor = 0;
                return null;
            }
            if (nextNeighbor >= currentNeighbors.size()) {
                current = null;
                currentNeighbors = null;
                return null;
            }
            BlockPos candidate = currentNeighbors.get(nextNeighbor++);
            if (!withinRescueBounds(candidate, origin) || visited.contains(candidate)) {
                return null;
            }
            WaterEscapeProbe probe = probeWaterEscapeCell(bot, world, candidate, false);
            WaterEscapeCell cell = probe.cell();
            if (cell == null) {
                visited.add(candidate);
                // Retain only a true visibility miss for a later fresh observation (from a
                // viewpoint change or the bounded next-tick retry); a visible physical rejection
                // is known and must not keep this rescue PENDING forever.
                if (probe.unknown()) {
                    rememberUnknown(candidate, current);
                }
                return null;
            }
            // This is an edge fact, not a property of candidate.  Do not poison candidate's
            // visited bit when a visible wall blocks only this diagonal parent: a cardinal parent
            // may still reach the same observed water cell on a later frontier operation.
            if (cell == WaterEscapeCell.WATER
                    && hasVisibleSolidDiagonalWaterCorner(bot, current, candidate)) {
                return null;
            }
            visited.add(candidate);
            return admitObservedCell(candidate, current, cell, feet);
        }

        /** Retries one formerly unknown edge after a real viewpoint change or its next observation tick. */
        private WaterEscapeStep retryOneUnknown(AIPlayerEntity bot, ServerLevel world, BlockPos feet) {
            UnknownCandidate unknown = unknowns.removeFirst();
            if (!unknownCells.remove(unknown.cell()) || connected(unknown.cell())
                    || !connected(unknown.parent())) {
                return null;
            }
            // A changed viewpoint can release several older edges under this slice's budget. A
            // fixed view gets one retained ray only; the requeued edge records this tick and is
            // consequently ineligible until the next one.
            stationaryUnknownRetryUsed = true;
            WaterEscapeProbe probe = probeWaterEscapeCell(bot, world, unknown.cell(), false);
            WaterEscapeCell cell = probe.cell();
            if (cell == null) {
                if (probe.unknown()) {
                    rememberUnknown(unknown.cell(), unknown.parent());
                }
                return null;
            }
            if (cell == WaterEscapeCell.WATER
                    && hasVisibleSolidDiagonalWaterCorner(bot, unknown.parent(), unknown.cell())) {
                // The cell was visited only to retain its former UNKNOWN edge.  A fresh visible
                // corner rejection belongs to that one parent, so let another parent retry it.
                visited.remove(unknown.cell());
                return null;
            }
            return admitObservedCell(unknown.cell(), unknown.parent(), cell, feet);
        }

        private WaterEscapeStep admitObservedCell(BlockPos candidate, BlockPos parent,
                                                   WaterEscapeCell cell, BlockPos feet) {
            BlockPos admitted = candidate.immutable();
            previous.put(admitted, parent.immutable());
            if (cell == WaterEscapeCell.DRY) {
                return waterEscapeStepFromCurrent(previous, feet, admitted);
            }
            enqueue(admitted);
            return null;
        }

        private void rememberUnknown(BlockPos candidate, BlockPos parent) {
            BlockPos unknown = candidate.immutable();
            if (unknownCells.add(unknown)) {
                unknowns.addLast(new UnknownCandidate(unknown, parent.immutable(), observationEpoch,
                        lastObservationTick));
            }
        }

        private void enqueue(BlockPos cell) {
            if (queued.add(cell)) {
                queue.addLast(cell);
            }
        }

        private boolean connected(BlockPos cell) {
            return origin.equals(cell) || previous.containsKey(cell);
        }

        private static boolean adjacent(BlockPos from, BlockPos to) {
            int dx = Math.abs(to.getX() - from.getX());
            int dy = Math.abs(to.getY() - from.getY());
            int dz = Math.abs(to.getZ() - from.getZ());
            return dx <= 1 && dy <= 1 && dz <= 1 && (dx != 0 || dy != 0 || dz != 0);
        }

        private record UnknownCandidate(BlockPos cell, BlockPos parent, int observationEpoch, int observedTick) {
        }
    }

    private enum WaterEscapeCell {
        WATER,
        DRY
    }

    /**
     * Classifies a rescue cell while keeping observation failure distinct from a visible physical
     * rejection. The retained strict frontier retries only genuinely unobserved edges; a visible
     * wall/hazard/non-landing is known invalid and must let a search complete normally.
     */
    private static WaterEscapeProbe probeWaterEscapeCell(AIPlayerEntity bot, ServerLevel world,
                                                         BlockPos candidate, boolean hiddenWaterScan) {
        if (!hiddenWaterScan && !canObserveWaterRescueColumn(bot, candidate)) {
            return WaterEscapeProbe.unobserved();
        }
        BlockState feet = world.getBlockState(candidate);
        BlockState head = world.getBlockState(candidate.above());
        if (!feet.getCollisionShape(world, candidate).isEmpty()
                || !head.getCollisionShape(world, candidate.above()).isEmpty()
                || Standability.isDangerous(feet)
                || Standability.isDangerous(head)) {
            return WaterEscapeProbe.invalid();
        }
        if (feet.getFluidState().is(FluidTags.WATER) || head.getFluidState().is(FluidTags.WATER)) {
            return WaterEscapeProbe.valid(WaterEscapeCell.WATER);
        }
        // An exposed empty support is a visible physical rejection, not an unobserved collider:
        // prove the cell before fresh standability checks so strict fallback can exhaust it.
        if (!hiddenWaterScan && !ObservableWorldQuery.canObserveCellThroughFluids(bot, candidate.below())) {
            return WaterEscapeProbe.unobserved();
        }
        return Standability.isStandableFresh(world, candidate)
                ? WaterEscapeProbe.valid(WaterEscapeCell.DRY) : WaterEscapeProbe.invalid();
    }

    /** Immediate caller view of {@link #probeWaterEscapeCell}. */
    private static WaterEscapeCell observedWaterEscapeCell(AIPlayerEntity bot, ServerLevel world,
                                                           BlockPos candidate, boolean hiddenWaterScan) {
        return probeWaterEscapeCell(bot, world, candidate, hiddenWaterScan).cell();
    }

    private record WaterEscapeProbe(WaterEscapeCell cell, boolean observed) {
        private static WaterEscapeProbe valid(WaterEscapeCell cell) {
            return new WaterEscapeProbe(cell, true);
        }

        private static WaterEscapeProbe invalid() {
            return new WaterEscapeProbe(null, true);
        }

        private static WaterEscapeProbe unobserved() {
            return new WaterEscapeProbe(null, false);
        }

        private boolean unknown() {
            return !observed;
        }
    }

    /** Cheap configured-range rejection keeps a strict water BFS from issuing futile rays. */
    private static boolean canObserveWaterRescueColumn(AIPlayerEntity bot, BlockPos candidate) {
        return withinWaterRescueObservationRange(bot, candidate)
                && withinWaterRescueObservationRange(bot, candidate.above())
                && (ObservableWorldQuery.canObserveCellThroughFluids(bot, candidate)
                || canObserveAdjacentWaterRescueCell(bot, candidate))
                && (ObservableWorldQuery.canObserveCellThroughFluids(bot, candidate.above())
                || canObserveAdjacentWaterRescueCell(bot, candidate.above()));
    }

    /**
     * Strict water movement may keep vanilla's legal same-level diagonal strokes, but not through
     * a visibly sealed two-block side column.  This is deliberately an edge predicate: a blocked
     * diagonal parent must not erase a candidate that a cardinal parent can still reach.  Strict
     * callers only use the transparent collider proofs below; they never inspect either side's
     * BlockState just to decide whether that corner is closed.
     */
    private static boolean hasVisibleSolidDiagonalWaterCorner(AIPlayerEntity bot, BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int dz = to.getZ() - from.getZ();
        if (dy != 0 || Math.abs(dx) != 1 || Math.abs(dz) != 1) {
            return false;
        }
        return isVisibleSolidWaterColumn(bot, from.offset(dx, 0, 0),
                dx > 0 ? Direction.WEST : Direction.EAST)
                || isVisibleSolidWaterColumn(bot, from.offset(0, 0, dz),
                dz > 0 ? Direction.NORTH : Direction.SOUTH);
    }

    /** A full player-body side column is known closed only when both near-facing cells are colliders. */
    private static boolean isVisibleSolidWaterColumn(AIPlayerEntity bot, BlockPos feet, Direction faceTowardFrom) {
        return canObserveWaterRescueSideCollider(bot, feet, faceTowardFrom)
                && canObserveWaterRescueSideCollider(bot, feet.above(), faceTowardFrom);
    }

    /**
     * A stacked side wall self-occludes its upper cell from a center ray through its lower one.
     * For the exact diagonal edge instead prove each cell separately at the face exposed toward
     * its source. The target lies just inside that collider; Fluid.NONE makes this a strict
     * through-water first-hit proof without inspecting any BlockState.
     */
    private static boolean canObserveWaterRescueSideCollider(AIPlayerEntity bot, BlockPos side,
                                                              Direction faceTowardFrom) {
        double faceInset = 0.5D - WATER_RESCUE_ADJACENT_FACE_INSET;
        Vec3 sample = new Vec3(side.getX() + 0.5D + faceTowardFrom.getStepX() * faceInset,
                side.getY() + 0.5D,
                side.getZ() + 0.5D + faceTowardFrom.getStepZ() * faceInset);
        double radius = Math.max(1, MinecraftAiConfig.get().perception().radius());
        if (bot.getEyePosition().distanceToSqr(sample) > radius * radius) {
            return false;
        }
        BlockHitResult hit = bot.level().clip(new ClipContext(
                bot.getEyePosition(), sample, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(side);
    }

    /**
     * A two-block-high adjacent bank can be physically open while the center ray to its head cell
     * grazes the ceiling behind that opening. Preserve the normal center-ray proof first; only
     * then let a direct horizontal neighbour use one real ray to the lower point of its near face.
     * This is still an exact Fluid.NONE line-of-sight proof, not a terrain read or a wider search.
     */
    private static boolean canObserveAdjacentWaterRescueCell(AIPlayerEntity bot, BlockPos candidate) {
        BlockPos feet = bot.blockPosition();
        int dx = candidate.getX() - feet.getX();
        int dy = candidate.getY() - feet.getY();
        int dz = candidate.getZ() - feet.getZ();
        if (Math.abs(dx) + Math.abs(dz) != 1 || Math.abs(dy) > 2) {
            return false;
        }
        double sampleX = candidate.getX() + (dx > 0 ? WATER_RESCUE_ADJACENT_FACE_INSET
                : dx < 0 ? 1.0D - WATER_RESCUE_ADJACENT_FACE_INSET : 0.5D);
        double sampleZ = candidate.getZ() + (dz > 0 ? WATER_RESCUE_ADJACENT_FACE_INSET
                : dz < 0 ? 1.0D - WATER_RESCUE_ADJACENT_FACE_INSET : 0.5D);
        Vec3 sample = new Vec3(sampleX, candidate.getY() + WATER_RESCUE_ADJACENT_FACE_INSET, sampleZ);
        BlockHitResult hit = bot.level().clip(new ClipContext(
                bot.getEyePosition(), sample, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot));
        return hit.getType() == HitResult.Type.MISS
                || (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(candidate));
    }

    private static boolean withinWaterRescueObservationRange(AIPlayerEntity bot, BlockPos candidate) {
        double radius = Math.max(1, MinecraftAiConfig.get().perception().radius());
        return bot.getEyePosition().distanceToSqr(candidate.getCenter()) <= radius * radius;
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
        // WalkedStep.Kind.SWIM accepts the complete adjacent 3x3x3 envelope (except the current
        // cell), including diagonal strokes. Do not quietly turn vanilla-legal diagonal water or
        // bank exits into an invented cardinal-only restriction.
        java.util.ArrayList<BlockPos> result = new java.util.ArrayList<>(26);
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dy != 0 || dz != 0) {
                        result.add(current.offset(dx, dy, dz));
                    }
                }
            }
        }
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

    /** Fresh water-crisis form: does not touch the shared memo while fluid shapes are changing. */
    private static boolean isFreshDryStandableCell(ServerLevel world, BlockPos candidate) {
        return world.getFluidState(candidate).isEmpty()
                && world.getFluidState(candidate.above()).isEmpty()
                && Standability.isStandableFresh(world, candidate);
    }

    /**
     * The kind of {@link WalkedStep} that takes the bot from its cell onto the adjacent {@code cell}: a swim for a water cell, a walk,
     * hop or drop for a dry landing (null when no walk covers the height difference). Shared by the rescue and the swim follower.
     */
    static WalkedStep.Kind stepKindTo(AIPlayerEntity bot, BlockPos cell) {
        if (isDryStandableCell(bot.level(), cell)) {
            return dryStepKind(bot, cell);
        }
        return WalkedStep.Kind.SWIM;
    }

    private static WalkedStep.Kind freshStepKindTo(AIPlayerEntity bot, BlockPos cell) {
        if (isFreshDryStandableCell(bot.level(), cell)) {
            return dryStepKind(bot, cell);
        }
        return WalkedStep.Kind.SWIM;
    }

    /** Dry member of the adjacent rescue envelope: a vertical descent is a real gravity drop. */
    private static WalkedStep.Kind dryStepKind(AIPlayerEntity bot, BlockPos cell) {
        BlockPos feet = bot.blockPosition();
        if (cell.getX() == feet.getX() && cell.getZ() == feet.getZ() && cell.getY() < feet.getY()) {
            return WalkedStep.Kind.DROP;
        }
        return WalkedStepRules.walkKindFor(cell.getY() - feet.getY());
    }

    /**
     * Rescue steps this net owns, latched to the hidden-world capability and the short physical
     * corridor that admitted them. A step begun by an operator route must not keep moving after
     * strict survival is restored, nor after another controller relocates the bot away from its
     * own input-driven source/target corridor.
     */
    private final Map<UUID, RescueStepAdmission> rescueSteps = new ConcurrentHashMap<>();

    private record RescueStepAdmission(AIPlayerEntity bot, ActionPack.StepLease lease,
                                       boolean hiddenWorldScan, BlockPos origin, BlockPos destination) {
        private RescueStepAdmission withLease(ActionPack.StepLease nextLease) {
            return new RescueStepAdmission(bot, nextLease, hiddenWorldScan, origin, destination);
        }
    }

    /** Removes a rescue admission and releases only its exact guarded ActionPack lease. */
    private void releaseRescueStep(AIPlayerEntity bot, boolean cancel) {
        RescueStepAdmission admission = rescueSteps.remove(bot.getUUID());
        if (admission == null || admission.lease() == null) {
            return;
        }
        if (cancel) {
            bot.getActionPack().cancelStep(admission.lease());
        } else {
            bot.getActionPack().releaseStepLease(admission.lease());
        }
    }

    /**
     * Whether a rescue step is still running. Otherwise a step that somebody else left running is dropped: from here on the rescue
     * owns the bot's keys.
     */
    private boolean rescueStepInFlight(AIPlayerEntity bot, boolean hiddenWaterScan) {
        var pack = bot.getActionPack();
        if (emergencySuccessorOwnsMovement(bot)) {
            return true;
        }
        RescueStepAdmission admission = rescueSteps.get(bot.getUUID());
        if (admission != null) {
            if (!pack.stepInFlightFor(admission.lease())) {
                // A generic cancellation cannot contribute a rescue result. Read the exact
                // owner's terminal result before releasing its fence: a completed strict stroke
                // to its admitted cell is the one case where the retained observed frontier can
                // be safely attached to the new physical feet on the next planning tick.
                WalkedStep.Result result = pack.stepResultFor(admission.lease());
                boolean emergencySuccessorActive = !pack.stepIdle();
                boolean completedStrictLanding = !admission.hiddenWorldScan()
                        && !hiddenWaterScan
                        && !emergencySuccessorActive
                        && result != null
                        && result.succeeded()
                        && bot.blockPosition().equals(admission.destination());
                // Ordinary foreign starts are fenced while this lease is live; an active
                // non-owned successor here is the higher-priority lava/suffocation handoff and
                // must carry on untouched.
                releaseRescueStep(bot, false);
                waterEscapeCache.remove(bot.getUUID());
                if (!completedStrictLanding) {
                    strictWaterEscapeSearches.remove(bot.getUUID());
                }
                waterRescueDeadlines.remove(bot.getUUID());
                if (emergencySuccessorActive) {
                    awaitingEmergencySuccessors.add(bot.getUUID());
                    return true;
                }
                return false;
            }
            if (admission.hiddenWorldScan() != hiddenWaterScan
                    || !withinRescueStepContinuationEnvelope(bot.blockPosition(), admission,
                    pack.activeStepKind(), pack.activeStepTicks())) {
                // Cancel before the action pack advances. The rest of this safety tick then
                // re-plans under the current capability and can use only a fresh strict proof.
                // A command or another controller may likewise not inherit movement keys that
                // were admitted from a now-distant rescue source.
                releaseRescueStep(bot, true);
                waterEscapeCache.remove(bot.getUUID());
                strictWaterEscapeSearches.remove(bot.getUUID());
                waterRescueDeadlines.remove(bot.getUUID());
                return false;
            }
            return true;
        }
        if (!pack.stepIdle()) {
            pack.cancelStep();
        }
        return false;
    }

    /** Starts a walk to the middle of the bot's own dry cell (its body is still partly over the water); false when it is refused. */
    private boolean beginRescueRecenter(AIPlayerEntity bot, BlockPos feet, boolean hiddenWorldScan) {
        if (!hiddenWorldScan && observedWaterEscapeCell(bot, bot.level(), feet, false) != WaterEscapeCell.DRY) {
            return false;
        }
        net.minecraft.world.phys.Vec3 middle = net.minecraft.world.phys.Vec3.atBottomCenterOf(feet);
        if (Math.hypot(middle.x - bot.getX(), middle.z - bot.getZ()) < WalkedStepRules.POINT_TOLERANCE) {
            return false;
        }
        RescueStepAdmission admission = new RescueStepAdmission(bot, null, hiddenWorldScan,
                feet.immutable(), feet.immutable());
        ActionPack.StepLease lease = bot.getActionPack().runStep(
                WalkedStep.begin(bot, middle, WalkedStep.Kind.RECENTER, "navsafe_water_rescue"),
                (guardBot, step) -> canContinueRescueStep(guardBot, step, admission));
        if (lease == null) {
            return false;
        }
        rescueSteps.put(bot.getUUID(), admission.withLease(lease));
        beginStrictAutomaticWaterRescueSession(bot, hiddenWorldScan);
        return true;
    }

    /** Starts a rescue step onto {@code cell} (never moves the bot itself); false when the step is refused. */
    private boolean beginRescueStep(AIPlayerEntity bot, BlockPos cell, String reason, boolean hiddenWorldScan) {
        // Do the strict observation proof immediately before fresh physical validation. This is
        // deliberately repeated for a cached route: the proof that selected it can be stale.
        if (!hiddenWorldScan && observedWaterEscapeCell(bot, bot.level(), cell, false) == null) {
            return false;
        }
        WalkedStep.Kind kind = freshStepKindTo(bot, cell);
        if (kind == null) {
            return false;
        }
        if (!hiddenWorldScan && kind == WalkedStep.Kind.SWIM
                && hasVisibleSolidDiagonalWaterCorner(bot, bot.blockPosition(), cell)) {
            return false;
        }
        // The destination proof above covers the landing/water cell. The WalkedStep validator
        // additionally reads corner/headroom/drop-sweep cells for dry moves, so prove precisely
        // that vanilla envelope immediately before invoking it in strict survival.
        if (!hiddenWorldScan && !SwimRoute.canObserveWalkedStepRefusalEnvelope(bot, cell, kind)) {
            return false;
        }
        if (WalkedStep.refusal(bot, cell, kind) != null) {
            return false;
        }
        RescueStepAdmission admission = new RescueStepAdmission(bot, null, hiddenWorldScan,
                bot.blockPosition().immutable(), cell.immutable());
        ActionPack.StepLease lease = bot.getActionPack().runStep(WalkedStep.begin(bot, cell, kind, reason),
                (guardBot, step) -> canContinueRescueStep(guardBot, step, admission));
        if (lease == null) {
            return false;
        }
        rescueSteps.put(bot.getUUID(), admission.withLease(lease));
        beginStrictAutomaticWaterRescueSession(bot, hiddenWorldScan);
        return true;
    }

    /**
     * A strict automatic rescue gets durable local ownership only after it has actually leased a
     * physical Nav step.  Explicit requests retain waterRescueShore instead; keeping that public
     * intent separate prevents a one-tick high-air Follow or Baritone handoff from inheriting a
     * surface/re-entry loop.
     */
    private void beginStrictAutomaticWaterRescueSession(AIPlayerEntity bot, boolean hiddenWorldScan) {
        if (!hiddenWorldScan && !waterRescueShore.containsKey(bot.getUUID())) {
            strictAutomaticWaterRescueSessions.add(bot.getUUID());
        }
    }

    /**
     * Runs inside WalkedStep before its first or later raw terrain validation. This is the actual
     * pre-owner tick boundary: ActionPack advances before END_SERVER_TICK can reconcile the
     * rescue map, so a profile flip or a newly hidden strict destination must be rejected here.
     */
    private static boolean canContinueRescueStep(AIPlayerEntity bot, WalkedStep step,
                                                  RescueStepAdmission admission) {
        // This check has no world read and runs before the capability/visibility proof below.
        // It permits only the one-cell target box (or intermediate vertical cells of a legal
        // DROP/STEP_DOWN), plus WalkedStep's one-tick source-settling normalization envelope.
        // A multi-cell external relocation therefore cannot keep old rescue keys alive.
        if (!step.cell().equals(admission.destination())
                || !withinRescueStepContinuationEnvelope(bot.blockPosition(), admission, step.kind(), step.ticks())) {
            return false;
        }
        if (admission.hiddenWorldScan()) {
            return canUseHiddenWaterScan(bot);
        }
        WaterEscapeCell observed = observedWaterEscapeCell(bot, bot.level(), step.cell(), false);
        // A strict target must remain currently observable for every guarded tick. A newly placed
        // wall can turn the target into an UNKNOWN ray miss just as readily as it can make the
        // target visibly invalid; retained admission proof must never bridge either world change.
        if (observed == null) {
            return false;
        }
        if (step.kind() == WalkedStep.Kind.SWIM ? observed != WaterEscapeCell.WATER
                : observed != WaterEscapeCell.DRY) {
            return false;
        }
        if (step.kind() == WalkedStep.Kind.SWIM
                && hasVisibleSolidDiagonalWaterCorner(bot, admission.origin(), admission.destination())) {
            return false;
        }
        return SwimRoute.canObserveWalkedStepRefusalEnvelope(bot, step.cell(), step.kind());
    }

    /** The axis-aligned corridor of one vanilla step; a drop's intermediate vertical cells are legitimate. */
    private static boolean withinRescueStepCorridor(BlockPos feet, BlockPos origin, BlockPos destination) {
        return between(feet.getX(), origin.getX(), destination.getX())
                && between(feet.getY(), origin.getY(), destination.getY())
                && between(feet.getZ(), origin.getZ(), destination.getZ());
    }

    /**
     * Preserve only WalkedStep's documented first-tick adjustment for a falling source: before
     * its first terrain proof, a dry FLAT/STEP_UP/STEP_DOWN can be one cell above or below the
     * planned source and normalize its kind from there. It must still be in the exact source
     * column, and the exception expires after that first action tick.
     */
    private static boolean withinRescueStepContinuationEnvelope(BlockPos feet, RescueStepAdmission admission,
                                                                 WalkedStep.Kind kind, int activeStepTicks) {
        if (withinRescueStepCorridor(feet, admission.origin(), admission.destination())) {
            return true;
        }
        if (kind == WalkedStep.Kind.SWIM
                && admission.origin().getY() == admission.destination().getY()
                && withinRescueStepSwimSettlingEnvelope(feet, admission.origin(), admission.destination())) {
            return true;
        }
        BlockPos origin = admission.origin();
        return isNormalizableDryWalk(kind)
                && activeStepTicks >= 0 && activeStepTicks <= 1
                && feet.getX() == origin.getX()
                && feet.getZ() == origin.getZ()
                && Math.abs(feet.getY() - origin.getY()) == 1;
    }

    /**
     * Swimming physics may bob the body one block above or below an otherwise exact horizontal
     * stroke. Keep that admitted stroke alive only in its original one-cell X/Z corridor; this
     * never permits a second horizontal cell, a new destination, or an unproved terrain read.
     */
    private static boolean withinRescueStepSwimSettlingEnvelope(BlockPos feet, BlockPos origin,
                                                                 BlockPos destination) {
        return between(feet.getX(), origin.getX(), destination.getX())
                && between(feet.getZ(), origin.getZ(), destination.getZ())
                && feet.getY() >= Math.min(origin.getY(), destination.getY()) - 1
                && feet.getY() <= Math.max(origin.getY(), destination.getY()) + 1;
    }

    private static boolean isNormalizableDryWalk(WalkedStep.Kind kind) {
        return kind == WalkedStep.Kind.FLAT
                || kind == WalkedStep.Kind.STEP_UP
                || kind == WalkedStep.Kind.STEP_DOWN;
    }

    private static boolean between(int value, int first, int second) {
        return value >= Math.min(first, second) && value <= Math.max(first, second);
    }

    /**
     * Strict-survival fallback when the observation-bounded BFS has not yet seen a complete shore
     * route. It can take only an adjacent candidate already proved by observedWaterEscapeCell; dry
     * landings win, then same-height water, then upward/downward water. This is exploration by
     * ordinary walked steps, never a steering vector toward an unseen shore.
     */
    private boolean beginObservableWaterRescueStep(AIPlayerEntity bot, ServerLevel world, BlockPos feet,
                                                    StrictWaterEscapeSearch search, boolean allowWaterExploration) {
        if (search == null) {
            return false;
        }
        BlockPos candidate = search.nextObservableFallback(bot, world, feet,
                STRICT_WATER_FALLBACK_WORK_PER_TICK, allowWaterExploration);
        return candidate != null && beginRescueStep(bot, candidate, "navsafe_water_rescue", false);
    }

    private record ObservedWaterEscapeCell(BlockPos cell, WaterEscapeCell kind) {
    }

    /**
     * Re-proves the two facts a cached route would otherwise carry across ticks: its immediate
     * input-driven step and its dry destination. This is deliberately done before publishing the
     * shore, scheduling a deadline, or beginning the step, so strict survival never acts on a
     * route that became hidden behind a newly placed block.
     */
    private static boolean reproveWaterEscapeStep(AIPlayerEntity bot, ServerLevel world,
                                                  WaterEscapeStep escape, boolean hiddenWaterScan) {
        WaterEscapeCell next = observedWaterEscapeCell(bot, world, escape.next(), hiddenWaterScan);
        return next != null
                && (hiddenWaterScan || next != WaterEscapeCell.WATER
                || !hasVisibleSolidDiagonalWaterCorner(bot, bot.blockPosition(), escape.next()))
                && observedWaterEscapeCell(bot, world, escape.shore(), hiddenWaterScan) == WaterEscapeCell.DRY;
    }

    private static int observableWaterStepPriority(BlockPos feet, BlockPos candidate, WaterEscapeCell kind) {
        if (kind == WaterEscapeCell.DRY) {
            return 0;
        }
        int deltaY = candidate.getY() - feet.getY();
        return deltaY == 0 ? 1 : deltaY > 0 ? 2 : 3;
    }

    private boolean physicalStepTowardAir(AIPlayerEntity bot,
                                          ServerLevel world,
                                           BlockPos feet,
                                           boolean hiddenWaterScan) {
        if (!isWaterSwimCell(world, feet)) {
            return false;
        }
        BlockPos above = feet.above();
        if (!hiddenWaterScan && !canObserveWaterRescueColumn(bot, above)) {
            return false;
        }
        if (!world.getBlockState(above).getCollisionShape(world, above).isEmpty()
                || !world.getBlockState(above.above()).getCollisionShape(world, above.above()).isEmpty()) {
            return false;
        }
        if (!isWaterSwimCell(world, above)) {
            return false;
        }
        return beginRescueStep(bot, above, "navsafe_water_surface", hiddenWaterScan);
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
        // Reconcile a terminal physical successor first, but do not self-preempt a live one on
        // the next NavSafetyNet tick. Operator teleport remains allowed to supersede that live
        // step after its capability decision and before its privileged volume scan.
        boolean physicalEscapeActive = suffocationStepInFlight(bot);
        boolean emergency = io.github.zoyluo.minecraftai.mode.CapabilityRuntime.decide(
                bot, io.github.zoyluo.minecraftai.mode.PrivilegedCapability.EMERGENCY_TELEPORT,
                "navsafe_suffocation").allowed();
        // This method normally runs before tickBot's outer lava branch. A live physical
        // suffocation successor must therefore not hide a newly lethal lava cell merely because
        // it is still buried: lava deliberately preempts even this emergency lease before its
        // direct jump/escape inputs are issued. Keep the capability decision above this ordinary
        // local danger probe, so no privileged suffocation volume scan can move ahead of it.
        if (inLava(world, feet) || inLava(world, feet.below())) {
            noteEmergencyPreemption(bot, bot.getActionPack().preemptGuardedStepForEmergency());
            escapeLava(bot, world, feet);
            return true;
        }
        if (emergency && (physicalEscapeActive || suffocationEscapeIdle(bot))) {
            // The cache must be invalidated: reaching this "buried" branch means a block just changed (a cave-in / live-burial
            // scenario calling setBlockState), so the Standability cache still reflects the world before the change.
            Standability.clearCache();
            int top = world.getMinY() + world.getHeight();
            for (int dy = 1; dy <= SUFFOCATION_CLIMB_UP && feet.getY() + dy < top - 1; dy++) {
                BlockPos candidate = feet.above(dy);
                if (Standability.isStandable(world, candidate)) {
                    // An operator teleport replaces any exact emergency ownership before it
                    // changes position; never strand its guarded ActionPack fence.
                    releaseSuffocationEscape(bot);
                    releaseRescueStep(bot, true);
                    bot.getActionPack().stopAll();
                    bot.teleportTo(world, candidate.getX() + 0.5D, candidate.getY(), candidate.getZ() + 0.5D,
                            Collections.emptySet(), bot.getYRot(), bot.getXRot(), true);
                    Standability.clearCache();
                    return true;
                }
            }
        }
        if (physicalEscapeActive) {
            return true;
        }
        // A buried bot outranks a retained Follow/rescue step. This is the only intentional
        // foreign guarded-lease handoff before the strict real-input escape begins.
        noteEmergencyPreemption(bot, bot.getActionPack().preemptGuardedStepForEmergency());
        return escapeSuffocationByInputs(bot, world, feet);
    }

    /** The state of one bot's escape from a block, kept across ticks: the break it is doing and when it last logged. */
    private static final class SuffocationEscape {
        final AIPlayerEntity bot;
        final io.github.zoyluo.minecraftai.action.BlockMiner miner = new io.github.zoyluo.minecraftai.action.BlockMiner();
        /** Exact guarded ActionPack lease for the physical PUSH_OUT/adjacent successor. */
        ActionPack.StepLease stepLease;
        /** Immutable source/destination provenance for that one physical successor. */
        BlockPos stepOrigin;
        BlockPos stepDestination;
        int lastLogTick = -1000;

        private SuffocationEscape(AIPlayerEntity bot) {
            this.bot = bot;
        }
    }

    private final Map<UUID, SuffocationEscape> suffocationEscapes = new ConcurrentHashMap<>();
    private static final double SUFFOCATION_VIEW_RANGE = 6.0D;

    /** No privileged escape may start while the bot is already walking or digging its own way out (that one carries on). */
    private boolean suffocationEscapeIdle(AIPlayerEntity bot) {
        SuffocationEscape state = suffocationEscapes.get(bot.getUUID());
        return bot.getActionPack().stepIdle()
                && (state == null || (state.stepLease == null && state.miner.target() == null));
    }

    /** Reconciles only the exact physical escape lease; a later safety preemption is left alone. */
    private boolean suffocationStepInFlight(AIPlayerEntity bot) {
        SuffocationEscape state = suffocationEscapes.get(bot.getUUID());
        if (state == null || state.stepLease == null) {
            return false;
        }
        ActionPack.StepLease lease = state.stepLease;
        if (bot.getActionPack().stepInFlightFor(lease)) {
            return true;
        }
        bot.getActionPack().releaseStepLease(lease);
        state.stepLease = null;
        state.stepOrigin = null;
        state.stepDestination = null;
        return false;
    }

    /** Re-proves each strict local rescue before WalkedStep can issue another terrain read. */
    private static boolean canContinueSuffocationStep(AIPlayerEntity bot, WalkedStep step,
                                                       SuffocationEscape state) {
        BlockPos origin = state.stepOrigin;
        BlockPos destination = state.stepDestination;
        if (origin == null || destination == null || !step.cell().equals(destination)) {
            return false;
        }
        if (step.kind() == WalkedStep.Kind.PUSH_OUT) {
            // A push-out only resolves the bot's own overlapping body; it has no remote terrain
            // destination. Keep it in the original local envelope so a relocation cannot inherit
            // emergency movement keys.
            return Math.abs(bot.blockPosition().getX() - origin.getX()) <= 1
                    && Math.abs(bot.blockPosition().getY() - origin.getY()) <= 1
                    && Math.abs(bot.blockPosition().getZ() - origin.getZ()) <= 1;
        }
        if (!withinSuffocationStepEnvelope(bot.blockPosition(), origin, destination,
                step.kind(), step.ticks())) {
            return false;
        }
        return SwimRoute.observedCell(bot, bot.level(), destination, false)
                == SwimRoute.Cell.DRY
                && SwimRoute.canObserveWalkedStepRefusalEnvelope(bot, destination, step.kind());
    }

    /** Begins the exact, guarded successor that remains immune to generic ActionPack shutdown. */
    private boolean beginSuffocationEmergencyStep(AIPlayerEntity bot, SuffocationEscape state, WalkedStep step) {
        state.stepOrigin = bot.blockPosition().immutable();
        state.stepDestination = step.cell().immutable();
        ActionPack.StepLease lease = bot.getActionPack().runEmergencyStep(step,
                (guardBot, guardedStep) -> canContinueSuffocationStep(
                        guardBot, guardedStep, state));
        if (lease == null) {
            state.stepOrigin = null;
            state.stepDestination = null;
            return false;
        }
        state.stepLease = lease;
        awaitingEmergencySuccessors.add(bot.getUUID());
        return true;
    }

    /** The bot is not (or no longer) inside a block: whatever it was digging for its escape is over. */
    private void releaseSuffocationEscape(AIPlayerEntity bot) {
        SuffocationEscape state = suffocationEscapes.remove(bot.getUUID());
        if (state == null) {
            return;
        }
        if (state.stepLease != null) {
            bot.getActionPack().cancelStep(state.stepLease);
            state.stepLease = null;
        }
        state.stepOrigin = null;
        state.stepDestination = null;
        if (state.miner.target() != null) {
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
        SuffocationEscape state = suffocationEscapes.computeIfAbsent(bot.getUUID(), ignored -> new SuffocationEscape(bot));
        // This direct helper can be called by GameTests as well as the buried tick path. Never
        // preempt its own exact successor on the next invocation; release a completed lease first.
        if (suffocationStepInFlight(bot)) {
            return true;
        }
        // This package-visible helper is also exercised directly by GameTests; preserve the same
        // high-priority handoff if it is entered outside tickBot's buried branch.
        noteEmergencyPreemption(bot, pack.preemptGuardedStepForEmergency());
        if (!pack.stepIdle()) {
            return true;
        }
        int now = world.getServer().getTickCount();
        if (state.miner.target() != null) {
            return tickEscapeBreak(bot, state);
        }
        Standability.clearCache();
        if (io.github.zoyluo.minecraftai.action.WalkedStep.canPushOut(bot)) {
            pack.stopAll();
            beginSuffocationEmergencyStep(bot, state, WalkedStep.begin(bot, bot.position(),
                    WalkedStep.Kind.PUSH_OUT, "navsafe_suffocation"));
            return true;
        }
        var walked = observedAdjacentSuffocationStep(bot, world, feet);
        if (walked != null) {
            pack.stopAll();
            beginSuffocationEmergencyStep(bot, state, walked);
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
            var center = candidate.getCenter().subtract(bot.getEyePosition());
            var view = io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.castViewRay(bot, center.x, center.y, center.z,
                    SUFFOCATION_VIEW_RANGE, io.github.zoyluo.minecraftai.mode.ObservableWorldQuery.ViewShape.COLLIDER);
            if (!view.hit() || !candidate.equals(view.pos())) {
                continue;
            }
            BlockState state = world.getBlockState(candidate);
            if (!state.getCollisionShape(world, candidate).isEmpty()
                    && state.getDestroySpeed(world, candidate) >= 0.0F) {
                return candidate;
            }
        }
        return null;
    }

    /** Finds one observed dry escape cell without asking ActionPack to scan hidden neighbours. */
    private static WalkedStep observedAdjacentSuffocationStep(AIPlayerEntity bot,
                                                               ServerLevel world,
                                                               BlockPos current) {
        int[][] horizontalOffsets = {
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
        };
        for (int dy : new int[]{0, -1, 1}) {
            for (int[] offset : horizontalOffsets) {
                if (dy != 0 && Math.abs(offset[0]) + Math.abs(offset[1]) > 1) {
                    continue;
                }
                BlockPos candidate = current.offset(offset[0], dy, offset[1]);
                WalkedStep.Kind kind = dy > 0 ? WalkedStep.Kind.STEP_UP
                        : dy < 0 ? WalkedStep.Kind.STEP_DOWN : WalkedStep.Kind.FLAT;
                if (SwimRoute.observedCell(bot, world, candidate, false) != SwimRoute.Cell.DRY
                        || !SwimRoute.canObserveWalkedStepRefusalEnvelope(bot, candidate, kind)
                        || WalkedStep.refusal(bot, candidate, kind) != null) {
                    continue;
                }
                return WalkedStep.begin(bot, candidate, kind, "navsafe_suffocation");
            }
        }
        return null;
    }

    private static boolean withinSuffocationStepEnvelope(BlockPos feet, BlockPos origin,
                                                         BlockPos destination, WalkedStep.Kind kind,
                                                         int activeStepTicks) {
        if (between(feet.getX(), origin.getX(), destination.getX())
                && between(feet.getY(), origin.getY(), destination.getY())
                && between(feet.getZ(), origin.getZ(), destination.getZ())) {
            return true;
        }
        return (kind == WalkedStep.Kind.FLAT
                || kind == WalkedStep.Kind.STEP_UP
                || kind == WalkedStep.Kind.STEP_DOWN)
                && activeStepTicks >= 0 && activeStepTicks <= 1
                && feet.getX() == origin.getX()
                && feet.getZ() == origin.getZ()
                && Math.abs(feet.getY() - origin.getY()) == 1;
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
        // A privileged teleport is the exact Nav lifecycle handoff for any physical suffocation
        // successor too; clear that guarded lease before stopAll releases its former keys.
        releaseSuffocationEscape(bot);
        releaseRescueStep(bot, true);
        bot.getActionPack().stopAll();
        bot.teleportTo(world, to.getX() + 0.5D, to.getY(), to.getZ() + 0.5D,
                Collections.emptySet(), bot.getYRot(), bot.getXRot(), true);
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
                    if (!Standability.isStandableFresh(world, cursor)) {
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
                    if (!waterCell && !Standability.isStandableFresh(world, candidate)) {
                        continue;
                    }
                    candidates.add(candidate.immutable());
                }
            }
        }
        candidates.sort(java.util.Comparator.comparingDouble(pos -> pos.distSqr(shore)));
        for (BlockPos candidate : candidates) {
            if (beginRescueStep(bot, candidate, "navsafe_water_rescue", true)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDryStandable(AIPlayerEntity bot, ServerLevel world, BlockPos feet) {
        return !bot.isInWater()
                && !world.getFluidState(feet).is(FluidTags.WATER)
                && !world.getFluidState(feet.above()).is(FluidTags.WATER)
                && Standability.isStandableFresh(world, feet);
    }

    private static void escapeLava(AIPlayerEntity bot, ServerLevel world, BlockPos feet) {
        // Rush only toward a player-eye-proven dry neighbour. Emergency motion is not authority
        // to inspect a hidden bank; if none is visible the caller still jumps and can use its
        // own observed platform rescue.
        Direction best = null;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos side = feet.relative(dir);
            if (observedDryEmergencySide(bot, world, side)) {
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

    private static boolean observedDryEmergencySide(AIPlayerEntity bot, ServerLevel world,
                                                    BlockPos side) {
        return SwimRoute.observedCell(bot, world, side, false) == SwimRoute.Cell.DRY
                && SwimRoute.canObserveWalkedStepRefusalEnvelope(bot, side, WalkedStep.Kind.FLAT)
                && WalkedStep.refusal(bot, side, WalkedStep.Kind.FLAT) == null;
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
