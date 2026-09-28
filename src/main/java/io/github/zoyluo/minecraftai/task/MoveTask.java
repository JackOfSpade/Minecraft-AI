package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.BlockMiner;
import io.github.zoyluo.minecraftai.action.DigNav;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import io.github.zoyluo.minecraftai.util.BlockPosText;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

public final class MoveTask extends AbstractTask {
    private static final int DIG_NO_PROGRESS_LIMIT = 200; // Digging straight-line: give up if no block broken / no step taken for 10s
    private static final int DIG_MAX_ELAPSED = 2400;
    private static final double ARRIVE_SQUARED = 4.0D;     // Digging mode: within 2 blocks of goal counts as arrived

    // —— Segmented waypoint-relay navigation (routing around large lakes/large obstacles) ——
    // Failure mechanism (observed in real_nav_far testing): the target is 120 blocks away across a large lake. A* won't
    // path through water columns (deep water isn't standable, and the digging phase also won't dig fluid-containing
    // blocks), and the WALK_MAX_NODES=10k budget isn't enough to search out the whole long detour route around the
    // lake → pathfinding "fails completely" → the old logic immediately fell back to digging straight-line → DigNav
    // dug hard straight at the target and dug straight into the lake → touchingWater circuit-breaker triggered fail.
    // Solution: break the "one-hop direct route" into "multi-segment waypoint stops" — each segment ≤40 blocks, which
    // is always solvable within the A* budget; waypoints are chosen by sweeping deflection angles left/right around
    // the bot→goal bearing, picking only "dry and standable" footholds — the lake is routed around, not dug through.
    private static final int WAYPOINT_MAX_HOPS = 6;             // Waypoint hop cap: prevents endless back-and-forth in lake-bay terrain
    private static final double WAYPOINT_ARRIVE_SQUARED = 9.0D; // Within 3 blocks of a waypoint counts as having arrived at that stop
    private static final int WAYPOINT_PATH_ATTEMPTS = 5;        // Max candidates actually run through A* per waypoint pick (synchronous pathfinding has a ~50ms-scale budget per call; this cap prevents a single tick from stalling)
    private static final int[] WAYPOINT_DEFLECTIONS_DEG = {0, 30, -30, 60, -60, 90, -90}; // Deflection angle sequence: head straight first, then fan out left/right

    private final BlockPos goal;
    private final double startDistance;
    private BlockPos resolvedGoal;
    private boolean digging;                               // Pure pathfinding can't get through → fall back to digging straight-line
    private final BlockMiner miner = new BlockMiner();
    private int digLastProgressTick;
    private BlockPos waypoint;                             // Waypoint mode: current relay point; null = heading straight for the final goal
    private int waypointHops;                              // Number of waypoints already used (capped at WAYPOINT_MAX_HOPS)

    public MoveTask(BlockPos start, BlockPos goal) {
        this.goal = goal.toImmutable();
        this.startDistance = Math.sqrt(start.getSquaredDistance(goal));
    }

    public MoveTask(AIPlayerEntity bot, BlockPos goal) {
        this(bot.getBlockPos(), goal);
    }

    @Override
    public String name() {
        return "move";
    }

    @Override
    public String describe() {
        return (digging ? "Digging to " : "Walking to ") + BlockPosText.compact(goal);
    }

    @Override
    public double progress() {
        if (startDistance <= 0.1D || state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return Math.min(0.95D, elapsed / Math.max(20.0D, startDistance * 12.0D));
    }

    @Override
    public boolean isWaiting() {
        // While digging straight-line the bot stands and digs in place, barely moving → treat this as waiting so
        // StuckWatcher doesn't misjudge it as stuck (this task's own watchdog backstops it instead).
        return digging;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        // Fast-fail for out-of-bounds targets: a y outside the world range (below the void / above the build limit)
        // is physically unreachable, and any walking/digging just spins its wheels (observed in testing: digging
        // toward a y330 target "dug at the sky" for the full 2400 ticks without giving up — spinning wheels is one
        // of the most insidious failure modes in practice).
        ServerWorld world = bot.getEntityWorld();
        int bottom = world.getBottomY();
        int top = bottom + world.getHeight();
        if (goal.getY() < bottom || goal.getY() >= top) {
            fail("goal_out_of_world y=" + goal.getY());
            return;
        }
        startWalkOrDig(bot);
    }

    @Override
    protected void onResume(AIPlayerEntity bot) {
        digging = false;
        startWalkOrDig(bot);
    }

    private void startWalkOrDig(AIPlayerEntity bot) {
        ActionResult result = bot.getActionPack().startPathTo(goal);
        if (result.isFailed()) {
            // Waypoint relay takes priority over falling back to digging: digging straight-line is a "last resort" —
            // it ignores terrain and digs hard straight toward the coordinate, so when the target is across water
            // it's bound to dig straight into the lake and trigger the drowning circuit-breaker, failing the task
            // for sure. On pathfinding failure, try segmented waypoint relay first (don't touch the ground if a
            // walkable route exists); only fall back to digging straight-line if the relay can't find a foothold either.
            if (tryWaypointRelay(bot, "path_start:" + result.reason())) {
                return;
            }
            beginDigging(bot, result.reason()); // Pure pathfinding failed right from the start (blocked by a wall / SEARCH_LIMIT) → go straight to digging straight-line
            return;
        }
        waypoint = null; // Direct pathfinding succeeded → no waypoint stop needed (also clears any stale waypoint left over from a resume)
        resolvedGoal = bot.getActionPack().activePathGoal();
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        miner.cancel(bot);
        bot.getActionPack().stopAll();
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (bot.getBlockPos().getSquaredDistance(currentGoal()) <= 2.25D
                || (digging && bot.getBlockPos().getSquaredDistance(goal) <= ARRIVE_SQUARED)) {
            miner.cancel(bot);
            complete();
            return;
        }
        if (digging) {
            digTick(bot);
            return;
        }
        // Waypoint mode: currently heading to the relay point. Arriving at the waypoint != task complete —
        // waypointTick handles the "transfer" (re-heading straight for the final goal).
        if (waypoint != null) {
            waypointTick(bot);
            return;
        }
        // Pure pathfinding mode: the path executor is idle (can't reach it) → fall back to digging straight-line
        // instead of just getting stuck on did_not_reach.
        if (bot.getActionPack().isPathExecutorIdle() && elapsed > 5) {
            beginDigging(bot, "path_idle");
            return;
        }
        if (elapsed > 1200) {
            fail("move_timeout");
        }
    }

    private void beginDigging(AIPlayerEntity bot, String reason) {
        digging = true;
        waypoint = null; // Mutually exclusive: entering digging mode abandons the waypoint relay
        digLastProgressTick = elapsed;
        bot.getActionPack().stopAll(); // Clear the pathfinding state; DigNav takes over driving from here
        BotLog.action(bot, "move_dig_fallback", "goal", BlockPosText.compact(goal), "reason", reason);
    }

    private void digTick(AIPlayerEntity bot) {
        // Safety circuit-breaker (observed root cause of death in testing): digging straight-line **digs through
        // anything** toward the coordinate, which is the most dangerous mode. The instant it digs the bot underwater
        // (drowning) or into a mob pile (currently being hit), abandon immediately and hand off to the survival
        // layer (NavSafetyNet/DangerWatcher) or the brain — never keep digging all the way to drowning or being
        // beaten to death.
        // Root cause: the brain's move_to blindly digs toward the coordinate → digStep digs straight into a body of
        // water → the bot's head goes under; NavSafetyNet surfaces for air every tick, but the next tick this task's
        // digStep digs the bot back into the water → a "surface <-> dig back in" livelock for minutes with zero
        // progress, eventually drowning / being killed by mobs (observed two deaths in testing). Here,
        // submersion/being hit triggers the circuit-breaker immediately, breaking the livelock at the root —
        // survival first.
        // Circuit-breaker triggers early: stop as soon as the feet touch water (originally it waited until the head
        // went under / submerged before stopping — by then it's already half-submerged, and the safety net takes a
        // long time to drag it ashore; observed in real_nav_far testing: digging to the lake edge floods with water
        // at 73 ticks). When touchingWater fires, the water hasn't reached the head yet — stop digging immediately
        // and hand off to the safety net to get to shore.
        if (bot.isTouchingWater()) {
            miner.cancel(bot);
            // Division of labor between the safety circuit-breaker and waypoint relay: the circuit-breaker is only
            // responsible for "don't drown," not for "finish the route" — touching water means digging straight-line
            // is currently sending the bot into a body of water, and continuing to dig would certainly replay the
            // livelock. So before failing, first try segmented waypoint relay: pick a dry foothold away from the
            // water and re-path around it (a lake can only be routed around, never dug through). Only if the relay
            // also can't find one (surrounded by water on all sides / hops exhausted) does it keep the original
            // fail("move_dig_drowning") semantics, handing off to the safety net to reach shore and the brain to
            // find another way.
            if (tryWaypointRelay(bot, "dig_drowning")) {
                return;
            }
            fail("move_dig_drowning");
            return;
        }
        if (bot.hurtTime > 0) {
            miner.cancel(bot);
            fail("move_dig_under_attack");
            return;
        }
        if (elapsed > DIG_MAX_ELAPSED) {
            miner.cancel(bot);
            fail("move_dig_timeout");
            return;
        }
        if (elapsed - digLastProgressTick > DIG_NO_PROGRESS_LIMIT) {
            miner.cancel(bot);
            fail("move_dig_no_progress"); // Can't dig / blocked (e.g. surrounded by lava) → hand back control to the survival layer/brain
            return;
        }
        if (DigNav.digStep(bot, miner, goal)) {
            digLastProgressTick = elapsed;
        }
    }

    // ==================== Segmented waypoint-relay navigation ====================

    /**
     * Waypoint mode main loop: on reaching the relay point (<=3 blocks) or this segment breaking off early
     * (executor idle) → clear the waypoint, re-attempt direct pathfinding to the final goal; if direct pathing
     * still fails, pick the next waypoint and chip away at the route segment by segment.
     */
    private void waypointTick(AIPlayerEntity bot) {
        if (elapsed > 1200) {
            fail("move_timeout"); // Same master timeout gate as pure pathfinding mode — waypoint detours aren't allowed to run indefinitely either
            return;
        }
        boolean arrived = bot.getBlockPos().getSquaredDistance(waypoint) <= WAYPOINT_ARRIVE_SQUARED;
        if (!arrived && !bot.getActionPack().isPathExecutorIdle()) {
            return; // Still en route to the waypoint
        }
        // Arrived at the waypoint (or transfer on the spot even if this segment broke off early): re-head straight
        // for the final goal — now closer to the lake with a different vantage, direct pathing may be solvable now.
        waypoint = null;
        ActionResult result = bot.getActionPack().startPathTo(goal);
        if (!result.isFailed()) {
            resolvedGoal = bot.getActionPack().activePathGoal();
            return;
        }
        if (tryWaypointRelay(bot, "relay_next:" + result.reason())) {
            return;
        }
        // Relay exhausted → fall back to the original failure path (fall back to digging straight-line, its
        // circuit-breaker/watchdog decides the outcome)
        beginDigging(bot, "waypoint_exhausted");
    }

    /**
     * Try to enter/continue waypoint mode: pick a dry, standable relay point and successfully path to it → record
     * state + log. Returns false to mean the relay can't help (hops exhausted, or no foothold found within the fan
     * sweep); the caller falls back to the original failure path.
     */
    private boolean tryWaypointRelay(AIPlayerEntity bot, String reason) {
        if (waypointHops >= WAYPOINT_MAX_HOPS) {
            BotLog.action(bot, "move_waypoint_exhausted",
                    "why", "hops_limit", "hops", waypointHops, "goal", BlockPosText.compact(goal), "reason", reason);
            return false;
        }
        BlockPos picked = pickWaypoint(bot, goal);
        if (picked == null) {
            BotLog.action(bot, "move_waypoint_exhausted",
                    "why", "no_candidate", "hops", waypointHops, "goal", BlockPosText.compact(goal), "reason", reason);
            return false;
        }
        waypoint = picked;
        waypointHops++;
        digging = false; // May be transitioning in from a digging circuit-breaker: the waypoint segment proceeds as pure pathfinding, no more digging
        BotLog.action(bot, "move_waypoint",
                "to", waypoint.toShortString(), "hop", waypointHops, "goal", BlockPosText.compact(goal), "reason", reason);
        return true;
    }

    /**
     * Waypoint selection: using the bot→goal bearing θ as the baseline, deflection angles {0°, ±30°, ±60°, ±90°}
     * (outer loop) x forward distances {half the goal distance clamped to <=40, 24, 12} (inner loop) generate
     * candidates; each candidate takes the surface foothold y, and must simultaneously satisfy: standable + dry
     * column (lake-surface/shallow-water points all excluded) + not more than 10% farther from goal than the
     * current distance (prevents backward regression). The first candidate that's geometrically valid and whose
     * startPathTo doesn't fail is adopted (a successful path also kicks off the leg). Distance clamp <=40: ensures
     * every segment falls within the range reliably solvable within the A* walking budget (10k nodes) — that's the
     * whole point of segmenting.
     */
    private BlockPos pickWaypoint(AIPlayerEntity bot, BlockPos target) {
        ServerWorld world = bot.getEntityWorld();
        double bx = bot.getX();
        double bz = bot.getZ();
        double dxGoal = target.getX() + 0.5D - bx;
        double dzGoal = target.getZ() + 0.5D - bz;
        double goalDist = Math.sqrt(dxGoal * dxGoal + dzGoal * dzGoal);
        if (goalDist < 1.0D) {
            return null; // Already right up against it — there's no such thing as a "forward relay" here
        }
        double theta = Math.atan2(dzGoal, dxGoal);
        double maxGoalDist = goalDist * 1.10D;
        double maxGoalDistSq = maxGoalDist * maxGoalDist;
        double[] distances = {Math.min(goalDist / 2.0D, 40.0D), 24.0D, 12.0D};
        int pathAttempts = 0;
        for (int deg : WAYPOINT_DEFLECTIONS_DEG) {
            double phi = theta + Math.toRadians(deg);
            double cos = Math.cos(phi);
            double sin = Math.sin(phi);
            for (double dist : distances) {
                int x = (int) Math.floor(bx + dist * cos);
                int z = (int) Math.floor(bz + dist * sin);
                int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos candidate = new BlockPos(x, y, z);
                if (!Standability.isStandable(world, candidate)) {
                    continue;
                }
                if (!isDryColumn(world, candidate)) {
                    continue; // Excludes airborne cells picked up above a lake surface / water-covered feet on a shallow shore — the waypoint itself must not stand in water
                }
                if (candidate.getSquaredDistance(target) > maxGoalDistSq) {
                    continue;
                }
                if (pathAttempts >= WAYPOINT_PATH_ATTEMPTS) {
                    return null; // Synchronous A* runs at ~50ms-scale per call; cap the number of actual attempts to prevent a single tick from stalling
                }
                pathAttempts++;
                if (!bot.getActionPack().startPathTo(candidate).isFailed()) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /**
     * Dry-column check: a candidate counts as "dry" only if its feet cell and the 4 cells below it are all free of
     * fluid. MOTION_BLOCKING_NO_LEAVES picks up an airborne cell above the water on a lake surface, and on a
     * shallow shore the feet cell itself is water — both cases must be excluded, otherwise the waypoint would lead
     * the bot straight into the water, turning the detour into a death trap.
     */
    private static boolean isDryColumn(ServerWorld world, BlockPos feet) {
        for (int i = 0; i <= 4; i++) {
            if (!world.getFluidState(feet.down(i)).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private BlockPos currentGoal() {
        return resolvedGoal == null ? goal : resolvedGoal;
    }
}
