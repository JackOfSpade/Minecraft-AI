package io.github.zoyluo.minecraftai.action;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogCategory;
import io.github.zoyluo.minecraftai.log.LogFields;
import io.github.zoyluo.minecraftai.mode.FakePlayerMotion;
import io.github.zoyluo.minecraftai.pathfinding.AStarPathfinder;
import io.github.zoyluo.minecraftai.pathfinding.FailureReason;
import io.github.zoyluo.minecraftai.pathfinding.PathExecutor;
import io.github.zoyluo.minecraftai.pathfinding.PathfindingResult;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import java.util.Collections;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class ActionPack {
    /** Failure reason of a request identical to the previous one still inside its cooldown. */
    public static final String PATHFINDING_THROTTLED = "pathfinding_throttled";
    private static final int PATHFIND_SUCCESS_COOLDOWN_TICKS = 5;
    private static final int PATHFIND_FAILURE_COOLDOWN_TICKS = 20;
    // NAV-OPT two-phase pathfinding budget: pure walking only searches air cells (small search
    // space, so give it a generous allowance); the dig-through cap is smaller, to contain the 3D
    // volume search blowing up when trapped/underground.
    private static final int WALK_MAX_NODES = 10_000;
    private static final int DIG_MAX_NODES = 4_000;
    // Large budget dedicated to the approach primitive: approaching ore enclosed in stone
    // necessarily requires digging, so it goes straight to DIG with an enlarged budget (the
    // digging neighbor branching factor is small, so 24k nodes covers ~40 blocks of direct
    // through-mountain travel; the small-budget DIG in ordinary startPathTo is only a walking
    // fallback, and its semantics are unchanged).
    private static final int DIG_APPROACH_MAX_NODES = 24_000;
    private static final long PATHFIND_MAX_MILLIS = 50L;

    private final AIPlayerEntity player;

    private float forward;
    private float strafing;
    private boolean sneaking;
    private boolean sprinting;
    private boolean jumping;
    private int jumpTicks;

    private WalkToController walkTo;
    private MiningController mining;
    private PathExecutor pathExecutor;
    private PathRequestIdentity lastPathRequest;
    private PathRequestIdentity activePathRequest;
    private BlockPos activePathGoal;
    private int nextPathfindTick;
    private final SnapRepeatGuard physicalSnapGuard = new SnapRepeatGuard();

    public ActionPack(AIPlayerEntity player) {
        this.player = player;
    }

    public AIPlayerEntity player() {
        return player;
    }

    public void setForward(float value) {
        this.forward = clampInput(value);
    }

    public void setStrafing(float value) {
        this.strafing = clampInput(value);
    }

    public void setSneaking(boolean sneaking) {
        this.sneaking = sneaking;
        player.setShiftKeyDown(sneaking);
        if (sneaking && sprinting) {
            setSprinting(false);
        }
    }

    public void setSprinting(boolean sprinting) {
        this.sprinting = sprinting;
        player.setSprinting(sprinting);
        if (sprinting && sneaking) {
            setSneaking(false);
        }
    }

    public void setJumping(boolean jumping) {
        this.jumping = jumping;
    }

    public void jumpOnce() {
        this.jumpTicks = 2;
    }

    public ActionResult startWalkTo(Vec3 target) {
        return startWalkTo(target, 0.6D);
    }

    /** Starts a direct walk with a caller-defined horizontal arrival tolerance. */
    public ActionResult startWalkTo(Vec3 target, double arrivalThreshold) {
        clearActivePathExecutor();
        this.walkTo = new WalkToController(target, arrivalThreshold);
        this.mining = null;
        return ActionResult.IN_PROGRESS;
    }

    // Unified entry point for the approach primitive: dig-aware pathfinding (large-budget DIG
    // straight to the goal; the goal may be a solid cell that is "dug open, then stood on" -- see
    // the dig-endpoint exemption in AStarPathfinder.resolveEndpoint). Use this for approaching ore
    // enclosed in stone / going straight through a mountain; ordinary walking still uses
    // startPathTo (WALK first, then small-budget DIG).
    public ActionResult startDigPathTo(BlockPos goal) {
        return startDigPathTo(goal, 0);
    }

    /**
     * Starts a digging approach while preserving a caller-scoped mining-stone reserve.
     * The same reserve gates initial pillar planning, physical pillar execution and replanning.
     */
    public ActionResult startDigPathTo(BlockPos goal, int protectedStoneLikeReserve) {
        int reserve = Math.max(0, protectedStoneLikeReserve);
        int now = player.level().getServer().getTickCount();
        BlockPos immutableGoal = goal.immutable();
        boolean canPillar = PathExecutor.hasPlaceableBlock(player, reserve);
        PathRequestIdentity request = new PathRequestIdentity(
                immutableGoal, canPillar, true, reserve,
                PathExecutor.RouteContract.unrestricted());
        if (preparePathRequest(request, now)) {
            return ActionResult.failed(PATHFINDING_THROTTLED);
        }
        if (!snapPlayerToNearestStandable("path_start_invalid")) {
            lastPathRequest = request;
            nextPathfindTick = now + PATHFIND_FAILURE_COOLDOWN_TICKS;
            return ActionResult.failed("pathfinding_failed: NO_START");
        }
        PathfindingResult result = new AStarPathfinder(player, player.level(), player.blockPosition(), goal,
                DIG_APPROACH_MAX_NODES, PATHFIND_MAX_MILLIS, canPillar, true, 10.0D).findPath();
        if (!result.success()) {
            lastPathRequest = request;
            nextPathfindTick = now + PATHFIND_FAILURE_COOLDOWN_TICKS;
            return ActionResult.failed("pathfinding_failed: " + result.reason());
        }
        lastPathRequest = request;
        nextPathfindTick = now + PATHFIND_SUCCESS_COOLDOWN_TICKS;
        BlockPos resolvedGoal = result.resolvedGoal() == null ? immutableGoal : result.resolvedGoal();
        activePathGoal = resolvedGoal;
        activePathRequest = request;
        this.pathExecutor = new PathExecutor(
                result.path(), resolvedGoal, canPillar, true, reserve);
        this.walkTo = null;
        this.mining = null;
        return ActionResult.IN_PROGRESS;
    }

    public ActionResult startPathTo(BlockPos goal) {
        return startPathTo(goal, 0);
    }

    /**
     * Starts an ordinary route while preserving a caller-scoped mining-stone reserve.
     * Non-reserve path supports remain eligible; protected stone is exposed only above the
     * requested floor.
     */
    public ActionResult startPathTo(BlockPos goal, int protectedStoneLikeReserve) {
        int reserve = Math.max(0, protectedStoneLikeReserve);
        return startPathTo(
                goal, PathExecutor.hasPlaceableBlock(player, reserve), true, reserve);
    }

    /**
     * Starts a surface-exploration path without digging or disposable pillar shortcuts.
     * Hunt/Gather roaming must be able to keep moving after it reaches a waypoint; a path that
     * spends the last few dirt blocks pillaring out of a depression is not a reusable surface route.
     */
    public ActionResult startSurfacePathTo(BlockPos goal) {
        return startPathTo(goal, false, false, 0);
    }

    /**
     * Starts a no-dig/no-pillar surface route that must remain at or above {@code minimumY}.
     * This overload does not require a round-trip proof.
     */
    public ActionResult startSurfacePathTo(BlockPos goal, int minimumY) {
        return startSurfacePathTo(goal, minimumY, null);
    }

    /**
     * Starts a contract-bound surface route. A non-null {@code returnAnchor} additionally requires
     * an exact no-dig/no-pillar route from the requested goal back to that anchor under the same
     * Y floor and search budget.
     */
    public ActionResult startSurfacePathTo(
            BlockPos goal, int minimumY, BlockPos returnAnchor) {
        return startPathTo(goal, false, false, 0,
                PathExecutor.RouteContract.constrainedSurface(minimumY, returnAnchor));
    }

    /**
     * Contract-bound surface route whose outbound leg may dig through obstacles once the
     * walk-only phase has no solution (a hunter digs a stair through a hill instead of
     * rejecting the whole herd). The dig fallback stays above the same Y floor, and the
     * return proof from the goal remains strictly walk-only: a dug stair is itself
     * walk-only returnable, while a goal whose return needs fresh digging is still rejected.
     */
    public ActionResult startSurfaceDigFallbackPathTo(
            BlockPos goal, int minimumY, BlockPos returnAnchor) {
        return startPathTo(goal, false, true, 0,
                PathExecutor.RouteContract.constrainedSurface(minimumY, returnAnchor));
    }

    private ActionResult startPathTo(BlockPos goal, boolean canPillar,
                                     boolean allowDigFallback,
                                     int protectedStoneLikeReserve) {
        return startPathTo(goal, canPillar, allowDigFallback, protectedStoneLikeReserve,
                PathExecutor.RouteContract.unrestricted());
    }

    private ActionResult startPathTo(BlockPos goal, boolean canPillar,
                                     boolean allowDigFallback,
                                     int protectedStoneLikeReserve,
                                     PathExecutor.RouteContract routeContract) {
        int reserve = Math.max(0, protectedStoneLikeReserve);
        int now = player.level().getServer().getTickCount();
        BlockPos immutableGoal = goal.immutable();
        PathRequestIdentity request = new PathRequestIdentity(
                immutableGoal, canPillar, allowDigFallback, reserve, routeContract);
        if (preparePathRequest(request, now)) {
            return ActionResult.failed(PATHFINDING_THROTTLED);
        }
        if (routeContract.constrained()
                && player.blockPosition().getY() < routeContract.minimumY()) {
            lastPathRequest = request;
            nextPathfindTick = now + PATHFIND_FAILURE_COOLDOWN_TICKS;
            return ActionResult.failed("path_contract_failed: start_below_minimum_y");
        }
        boolean startReady = routeContract.constrained()
                ? recenterPlayerInCurrentStandableCell("path_start_invalid")
                : snapPlayerToNearestStandable("path_start_invalid");
        if (!startReady) {
            lastPathRequest = request;
            nextPathfindTick = now + PATHFIND_FAILURE_COOLDOWN_TICKS;
            return ActionResult.failed("pathfinding_failed: NO_START");
        }
        ServerLevel world = player.level();
        BlockPos from = player.blockPosition();
        // NAV-OPT two-phase pathfinding: try pure walking first (no digging allowed, search
        // space = air cells, so it converges fast and won't be blown out to SEARCH_LIMIT by
        // dig-through neighbors); only if pure walking has no solution do we allow the dig-through
        // fallback (tunneling/breaking obstacles), with a smaller dig budget to bound the 3D
        // volume search blowing up when trapped/underground.
        AStarPathfinder walkFinder =
                new AStarPathfinder(
                        player, world, from, goal, WALK_MAX_NODES, PATHFIND_MAX_MILLIS,
                        canPillar, false);
        PathfindingResult result = routeContract.constrained()
                ? walkFinder.findPathUncachedAtOrAbove(routeContract.minimumY())
                : walkFinder.findPath();
        boolean dugOutbound = false;
        if (!result.success() && allowDigFallback) {
            AStarPathfinder digFinder = new AStarPathfinder(
                    player, world, from, goal, DIG_MAX_NODES, PATHFIND_MAX_MILLIS, canPillar, true);
            PathfindingResult dig = routeContract.constrained()
                    ? digFinder.findPathUncachedAtOrAbove(routeContract.minimumY())
                    : digFinder.findPath();
            if (dig.success()) {
                result = dig;
                dugOutbound = true;
            }
        }
        if (!result.success()) {
            lastPathRequest = request;
            nextPathfindTick = now + PATHFIND_FAILURE_COOLDOWN_TICKS;
            return ActionResult.failed("pathfinding_failed: " + result.reason());
        }
        PathfindingResult returnProof = null;
        if (routeContract.requiresReturnProof()) {
            if (dugOutbound && PathExecutor.isReversibleStair(result)) {
                // The pre-dig world cannot prove the walk-only return yet; the dug stair is
                // itself that return, so derive the proof from the reversed outbound nodes.
                java.util.List<io.github.zoyluo.minecraftai.pathfinding.Node> reversed =
                        new java.util.ArrayList<>(result.path());
                java.util.Collections.reverse(reversed);
                returnProof = new PathfindingResult(
                        reversed, true, FailureReason.NONE,
                        result.nodesExplored(), result.elapsedMs(),
                        result.resolvedGoal(), result.resolvedStart());
            } else {
                returnProof = new AStarPathfinder(
                        player, world, immutableGoal, routeContract.returnAnchor(),
                        WALK_MAX_NODES, PATHFIND_MAX_MILLIS, false, false)
                        .findPathUncachedAtOrAbove(routeContract.minimumY());
            }
        }
        PathExecutor.RouteValidation validation = PathExecutor.validateRouteContract(
                result, immutableGoal, routeContract, returnProof);
        if (!validation.accepted()) {
            lastPathRequest = request;
            nextPathfindTick = now + PATHFIND_FAILURE_COOLDOWN_TICKS;
            return ActionResult.failed("path_contract_failed: " + validation.reason());
        }
        lastPathRequest = request;
        nextPathfindTick = now + PATHFIND_SUCCESS_COOLDOWN_TICKS;
        BlockPos resolvedGoal = result.resolvedGoal() == null ? immutableGoal : result.resolvedGoal();
        activePathGoal = resolvedGoal;
        activePathRequest = request;
        this.pathExecutor = routeContract.constrained()
                ? new PathExecutor(
                result.path(), resolvedGoal, canPillar, allowDigFallback, reserve, routeContract)
                : new PathExecutor(
                result.path(), resolvedGoal, canPillar, allowDigFallback, reserve);
        this.walkTo = null;
        this.mining = null;
        return ActionResult.IN_PROGRESS;
    }

    public BlockPos activePathGoal() {
        return activePathGoal;
    }

    /**
     * Repairs only fractional body overlap inside the current supported cell.
     * Constrained routes must never relocate to another block before their full contract is proven.
     */
    public boolean recenterPlayerInCurrentStandableCell(String reason) {
        ServerLevel world = player.level();
        BlockPos current = player.blockPosition();
        Standability.clearCache();
        if (!Standability.isStandable(world, current)) {
            return false;
        }
        if (FakePlayerMotion.isBlockCollisionFree(player)) {
            return true;
        }
        if (!FakePlayerMotion.returnToBlockCenter(
                player, current, "path_start_body_collision:" + reason)) {
            return false;
        }
        Standability.clearCache();
        return player.blockPosition().equals(current)
                && Standability.isStandable(world, current)
                && FakePlayerMotion.isBlockCollisionFree(player);
    }

    public boolean snapPlayerToNearestStandable(String reason) {
        ServerLevel world = player.level();
        BlockPos current = player.blockPosition();
        Standability.clearCache();
        boolean currentCellStandable = Standability.isStandable(world, current);
        if (currentCellStandable && FakePlayerMotion.isBlockCollisionFree(player)) {
            return true;
        }
        // A floored air column can be standable while an off-centre 0.6-wide player body overlaps
        // raised terrain in a neighbouring column. Dedicated-server console commands spawn at the
        // lower corner of the world-spawn BlockPos, which reproduced this exact condition on seed
        // 3000. Re-centre only the collided pose; collision-free fractional positions are valid
        // physical state and must be preserved for edge/pickup transactions.
        if (currentCellStandable
                && FakePlayerMotion.returnToBlockCenter(
                        player, current, "path_start_body_collision:" + reason)) {
            Standability.clearCache();
            if (FakePlayerMotion.isBlockCollisionFree(player)) {
                return true;
            }
        }
        // A fake player can end a jump/drop fractionally inside the neighbouring cell even though
        // an adjacent legal landing exists. Recover that one-cell movement physically before asking
        // for the privileged long-distance snap. In strict_survival this is the difference between
        // continuing a hunt and every subsequent path request failing NO_START in one tick.
        if (physicalSnapSuppressed(current, reason)) {
            // A suppressed snap is a refusal to re-snap out of a cell the bot was just walked back into --
            // it must NOT fall through to the privileged relocation below, which would turn the
            // yo-yo guard into a teleport. The caller's search simply fails this time.
            return false;
        }
        if (tryPhysicalSnap(world, current, reason)) {
            return true;
        }
        // A valid current start is ordinary pathfinding and must not require an emergency
        // capability. Only the fallback relocation to a different cell is privileged.
        if (!io.github.zoyluo.minecraftai.mode.CapabilityRuntime.decide(
                player, io.github.zoyluo.minecraftai.mode.PrivilegedCapability.EMERGENCY_TELEPORT,
                "action_pack_snap:" + reason).allowed()) {
            return false;
        }
        Optional<BlockPos> snapped = Standability.findNearestStandable(world, current, 8, 128, 32);
        if (snapped.isEmpty()) {
            BotLog.warn(LogCategory.PATH, player, "path_start_snap_failed", "reason", reason, "from", io.github.zoyluo.minecraftai.log.LogFields.pos(current));
            return false;
        }
        BlockPos safe = snapped.get();
        stopMovement();
        player.teleportTo(world,
                safe.getX() + 0.5D,
                safe.getY(),
                safe.getZ() + 0.5D,
                Collections.emptySet(),
                player.getYRot(),
                player.getXRot(),
                true);
        // findNearestStandable only just verified a solid landing at `safe`; this can relocate the
        // player up to 128 blocks vertically (e.g. away from a genuine, in-progress vanilla fall),
        // so any real fallDistance/velocity carried into the jump must be cleared here too, or a
        // later unrelated on-ground transition applies stale fall damage for a fall that this exact
        // teleport already resolved.
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;
        player.setOnGround(true);
        Standability.clearCache();
        BotLog.path(player, "path_start_snapped",
                "reason", reason,
                "from", io.github.zoyluo.minecraftai.log.LogFields.pos(current),
                "to", io.github.zoyluo.minecraftai.log.LogFields.pos(safe));
        return true;
    }

    /**
     * True when a second physical snap out of the same cell inside the guard window is being refused:
     * whatever walked the bot back in would just be undone again (see SnapRepeatGuard). One re-snap
     * per stall.
     */
    private boolean physicalSnapSuppressed(BlockPos current, String reason) {
        int nowTick = player.level().getServer().getTickCount();
        if (physicalSnapGuard.allows(current, nowTick)) {
            return false;
        }
        BotLog.path(player, "path_start_physical_snap_suppressed",
                "reason", reason, "from", LogFields.pos(current));
        return true;
    }

    private boolean tryPhysicalSnap(ServerLevel world, BlockPos current, String reason) {
        int nowTick = player.level().getServer().getTickCount();
        // Same-level steps first, then a one-block drop, finally a vanilla-style jump. A vertical
        // move may include one horizontal axis; three-axis corner jumps are never legitimate.
        int[][] horizontalOffsets = {
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1}, {0, 0}
        };
        for (int dy : new int[]{0, -1, 1}) {
            for (int[] offset : horizontalOffsets) {
                int dx = offset[0];
                int dz = offset[1];
                if ((dx == 0 && dz == 0 && dy == 0)
                        || (dy != 0 && Math.abs(dx) + Math.abs(dz) > 1)) {
                    continue;
                }
                BlockPos candidate = current.offset(dx, dy, dz);
                if (!Standability.isStandable(world, candidate)) {
                    continue;
                }
                boolean moved = dy > 0
                        ? FakePlayerMotion.jumpTo(player, candidate, "path_start_physical_snap:" + reason)
                        : FakePlayerMotion.stepTo(player, candidate, "path_start_physical_snap:" + reason);
                if (!moved) {
                    continue;
                }
                Standability.clearCache();
                physicalSnapGuard.record(current, nowTick);
                BotLog.path(player, "path_start_physical_snap",
                        "reason", reason,
                        "from", io.github.zoyluo.minecraftai.log.LogFields.pos(current),
                        "to", io.github.zoyluo.minecraftai.log.LogFields.pos(candidate));
                return true;
            }
        }
        return false;
    }

    /**
     * Actively sinks the bot down one cell into the given (already-air) block.
     * Key point: the bot is a ServerPlayerEntity, and the server side **does not run travel()**
     * (a real player's movement/gravity is driven by the client, and a fake player has no
     * client), so there is **no passive gravity** -- digging out the floor beneath it will not
     * make it fall automatically. Shaft-digging-down tasks (DigDownTask /
     * OreDigTask.digDownOneLayer) must actively drive the sink through this method, or the bot
     * will stand there idling until the watchdog fails (observed in practice: dig_down with y
     * constant the whole time, stuck at 200t no_progress -- this is the shared root cause).
     * Idempotent: if the bot is already at or below that layer, it does not move. teleport clears
     * fallDistance, so no fall damage is taken.
     */
    public boolean descendInto(BlockPos target) {
        if (player.blockPosition().getY() <= target.getY()) {
            return player.blockPosition().equals(target);
        }
        return io.github.zoyluo.minecraftai.mode.FakePlayerMotion.stepToStandable(
                player, target, "descend_into");
    }

    public ActionResult startMining(BlockPos pos, Direction face) {
        this.mining = new MiningController(pos, face);
        clearActivePathExecutor();
        this.forward = 0.0F;
        this.strafing = 0.0F;
        return ActionResult.IN_PROGRESS;
    }

    public void stopMining() {
        if (this.mining != null) {
            this.mining.abort(player);
            this.mining = null;
        }
    }

    public void stopMovement() {
        setSneaking(false);
        setSprinting(false);
        this.forward = 0.0F;
        this.strafing = 0.0F;
        this.jumping = false;
        this.jumpTicks = 0;
        player.setJumping(false);
    }

    /**
     * Cancels the active path executor and direct walk and releases the movement keys.  Unlike
     * {@link #stopMovement()} (keys only -- a live executor re-presses forward on its very next
     * tick and keeps walking a stale route), this really stops navigation, but leaves mining and
     * item use alone.
     */
    public void stopNavigation() {
        clearActivePathExecutor();
        this.walkTo = null;
        stopMovement();
    }

    public void stopAll() {
        clearActivePathExecutor();
        stopMining();
        this.walkTo = null;
        stopMovement();
        player.releaseUsingItem();
    }

    public boolean hasActiveActions() {
        return pathExecutor != null
                || walkTo != null
                || mining != null
                || forward != 0.0F
                || strafing != 0.0F
                || sneaking
                || sprinting
                || jumping
                || jumpTicks > 0
                || player.isUsingItem();
    }

    public boolean isPathExecutorIdle() {
        return pathExecutor == null;
    }

    public boolean isWalkToIdle() {
        return walkTo == null;
    }

    public boolean isMiningIdle() {
        return mining == null;
    }

    public void onUpdate() {
        tickPathExecutor();
        tickWalkTo();
        tickMining();

        float velocity = sneaking ? 0.3F : 1.0F;
        player.zza = forward * velocity;
        player.xxa = strafing * velocity;
        boolean jumpNow = jumping || jumpTicks > 0;
        player.setJumping(jumpNow);
        if (jumpTicks > 0) {
            jumpTicks--;
        }
    }

    private void tickWalkTo() {
        if (walkTo == null) {
            return;
        }

        ActionResult result = walkTo.tick(this);
        if (result.isInProgress()) {
            return;
        }

        if (result.isSuccess()) {
            BotLog.action(player, "walk_complete");
        } else {
            BotLog.warn(LogCategory.ERROR, player, "walk_failed", "reason", result.reason());
        }
        walkTo = null;
        forward = 0.0F;
        strafing = 0.0F;
        jumping = false;
        player.setJumping(false);
    }

    private void tickPathExecutor() {
        if (pathExecutor == null) {
            return;
        }

        ActionResult result = pathExecutor.tick(this);
        if (result.isInProgress()) {
            return;
        }

        if (result.isSuccess()) {
            BotLog.path(player, "path_complete", "ticks", pathExecutor.totalTicks());
        } else {
            BotLog.warn(LogCategory.ERROR, player, "path_failed", "reason", result.reason());
        }
        pathExecutor = null;
        activePathGoal = null;
        activePathRequest = null;
        forward = 0.0F;
        strafing = 0.0F;
        jumping = false;
        player.setJumping(false);
    }

    private void tickMining() {
        if (mining == null) {
            return;
        }

        ActionResult result = mining.tick(this);
        if (result.isInProgress()) {
            return;
        }

        if (result.isSuccess()) {
            // Auditable break record (see docs/LOGGING.md "Auditing a gather"): block is captured
            // BEFORE the break by MiningController, so this reports what was actually destroyed
            // even though the world cell is air by now.
            BlockState brokenState = mining.brokenBlockState();
            ItemStack tool = player.getMainHandItem();
            BotLog.action(player, "mine_complete",
                    "block", brokenState == null ? "unknown" : BuiltInRegistries.BLOCK.getKey(brokenState.getBlock()).toString(),
                    "pos", LogFields.pos(mining.pos()),
                    "tool", tool.isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(tool.getItem()).toString(),
                    "ticks", mining.elapsedTicks());
        } else {
            BotLog.warn(LogCategory.ERROR, player, "mine_failed", "reason", result.reason());
        }
        mining = null;
    }

    /**
     * Returns true when an identical request is still inside its cooldown.
     * A different active identity is stopped before cooldown evaluation so an old, weaker route
     * can never keep moving merely because the replacement happens to share the same goal.
     */
    private boolean preparePathRequest(PathRequestIdentity request, int now) {
        if (pathExecutor != null && !request.equals(activePathRequest)) {
            clearActivePathExecutor();
        }
        if (request.equals(lastPathRequest) && now < nextPathfindTick) {
            return true;
        }
        // An explicit restart after cooldown owns the controller from this point onward.
        clearActivePathExecutor();
        return false;
    }

    private void clearActivePathExecutor() {
        if (pathExecutor != null) {
            pathExecutor.abort(this);
            pathExecutor = null;
        }
        activePathGoal = null;
        activePathRequest = null;
    }

    private record PathRequestIdentity(
            BlockPos goal,
            boolean canPillar,
            boolean allowDig,
            int protectedStoneLikeReserve,
            PathExecutor.RouteContract routeContract) {
        private PathRequestIdentity {
            goal = goal.immutable();
            protectedStoneLikeReserve = Math.max(0, protectedStoneLikeReserve);
            routeContract = java.util.Objects.requireNonNull(routeContract, "routeContract");
        }
    }

    private static float clampInput(float value) {
        return Math.max(-1.0F, Math.min(1.0F, value));
    }
}
