package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.BuildAction;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.MaterialPalette;
import io.github.zoyluo.minecraftai.action.MiningAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.goal.StructureVerifier;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mode.CapabilityRuntime;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import io.github.zoyluo.minecraftai.mode.PrivilegedCapability;
import io.github.zoyluo.minecraftai.pathfinding.Standability;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.OptionalInt;

public final class BuildTask extends AbstractTask {
    private enum Phase {
        SITE,
        FLATTEN,
        BUILD
    }

    private enum FlattenKind {
        CLEAR,
        FILL
    }

    private final BlueprintSchema blueprint;
    private BlockPos anchor;
    private final boolean autoSite;
    private final boolean flatten;
    private final Deque<FlattenTarget> flattenTargets = new ArrayDeque<>();
    private Phase phase = Phase.SITE;
    private FlattenTarget currentFlattenTarget;
    private int flattenTargetTick;   // Tick when the current flatten cell started: a cell that stays out of reach past budget is skipped, to prevent nearbyStand from degenerating into "path to self" and looping forever
    private int nextIndex;
    private int buildTargetTick;      // Tick when the current build cell started: a block that can't be placed past budget is skipped (best-effort), to prevent moveWithinReach from spinning in endless pathfinding
    private int buildTargetIndex = -1;
    private int retryTicks;
    private int placeDelayTicks;
    private int placedBlocks;
    private int skippedBlocks;
    private boolean flattenMiningStarted;
    private boolean buildMiningStarted;
    private String note = "";

    public BuildTask(BlueprintSchema blueprint, BlockPos anchor) {
        this(blueprint, anchor, false, false);
    }

    public BuildTask(BlueprintSchema blueprint, BlockPos anchor, boolean autoSite, boolean flatten) {
        this.blueprint = blueprint;
        this.anchor = anchor == null ? null : anchor.toImmutable();
        this.autoSite = autoSite;
        this.flatten = flatten;
    }

    @Override
    public String name() {
        return "build";
    }

    @Override
    public String describe() {
        String anchorText = anchor == null ? "auto" : compact(anchor);
        return "Building " + blueprint.name() + " at " + anchorText + " " + nextIndex + "/" + blueprint.placements().size()
                + " phase=" + phase + (note.isBlank() ? "" : " note=" + note);
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        double buildProgress = blueprint.placements().isEmpty() ? 1.0D : Math.min(1.0D, (double) nextIndex / blueprint.placements().size());
        return switch (phase) {
            case SITE -> 0.0D;
            case FLATTEN -> Math.min(0.25D, flattenTargets.isEmpty() ? 0.25D : 0.10D);
            case BUILD -> 0.25D + buildProgress * 0.75D;
        };
    }

    @Override
    public boolean isWaiting() {
        // Building = standing in place mining/placing block by block (flattening + laying the structure); staying in the same spot for a long time is normal work here, not being stuck.
        // Without this exemption, StuckWatcher's 200t no-movement check would kill it by mistake (observed in real_build as task_stuck_aborted reason=stuck:build,
        // where the bot was cut down mid-construction while standing still during the flatten phase on real terrain, phase=FLATTEN progress=0.1). Stuck protection is instead handled by this task's own build_timeout (7200t) +
        // moveWithinReach's own retryTicks (counted only on genuine pathfinding failure), which understands build semantics better than StuckWatcher.
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        nextIndex = 0;
        buildTargetTick = 0;
        buildTargetIndex = -1;
        retryTicks = 0;
        placeDelayTicks = 0;
        placedBlocks = 0;
        skippedBlocks = 0;
        flattenTargets.clear();
        currentFlattenTarget = null;
        flattenMiningStarted = false;
        buildMiningStarted = false;
        phase = Phase.SITE;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > 16000) {
            // Building on real terrain = flattening (digging down high spots, filling low spots) + laying 100+ blocks one by one, and this heavy work drags tps down; 7200t isn't enough (observed reaching only 81/116).
            // Relaxed to 16000t so real terrain can also finish flattening + construction; on a flat lab site, building takes only 346t and stays far below this new cap, so there's no regression.
            fail("build_timeout");
            return;
        }
        if (placeDelayTicks > 0) {
            placeDelayTicks--;
            return;
        }
        switch (phase) {
            case SITE -> site(bot);
            case FLATTEN -> flatten(bot);
            case BUILD -> build(bot);
        }
    }

    private void site(AIPlayerEntity bot) {
        if (anchor == null) {
            if (!autoSite) {
                fail("missing_anchor");
                return;
            }
            // When flatten is enabled, use lenient site selection: real, uneven terrain rarely has ready-made flat ground, so pick the flattest usable spot and let FLATTEN level it
            // (fixes real_build no_flat_site 5/10); when flatten is disabled, stay strict (zero regression on a flat build canvas).
            anchor = SiteFinder.findSite(bot, blueprint.width(), blueprint.depth(), 16, flatten).orElse(null);
            if (anchor == null) {
                fail("no_flat_site");
                return;
            }
            note = "auto_site=" + compact(anchor);
        }
        if (flatten) {
            planFlatten(bot);
            phase = Phase.FLATTEN;
        } else {
            phase = Phase.BUILD;
        }
    }

    private void planFlatten(AIPlayerEntity bot) {
        ServerWorld world = bot.getEntityWorld();
        boolean rawTerrainRead = CapabilityRuntime.decide(
                bot, PrivilegedCapability.HIDDEN_BLOCK_SCAN, "build_flatten_plan").allowed();
        flattenTargets.clear();
        for (int dx = 0; dx < blueprint.width(); dx++) {
            for (int dz = 0; dz < blueprint.depth(); dz++) {
                BlockPos ground = anchor.add(dx, -1, dz);
                if (!rawTerrainRead
                        || world.getBlockState(ground).isAir()
                        || !world.getFluidState(ground).isEmpty()) {
                    flattenTargets.addLast(new FlattenTarget(ground, FlattenKind.FILL));
                }
                for (int dy = 0; dy < Math.max(blueprint.height(), 1); dy++) {
                    BlockPos clear = anchor.add(dx, dy, dz);
                    if (!rawTerrainRead
                            || (!world.getBlockState(clear).isAir() && world.getFluidState(clear).isEmpty())) {
                        flattenTargets.addLast(new FlattenTarget(clear, FlattenKind.CLEAR));
                    }
                }
            }
        }
    }

    private void flatten(AIPlayerEntity bot) {
        if (currentFlattenTarget == null) {
            currentFlattenTarget = flattenTargets.pollFirst();
            flattenMiningStarted = false;
            flattenTargetTick = elapsed;
            retryTicks = 0;
            if (currentFlattenTarget == null) {
                phase = Phase.BUILD;
                return;
            }
        }
        // Flatten-cell budget: a cell that stays out of reach (when nearbyStand finds no stand position it degenerates into startPathTo(self), an in-place infinite loop, observed in real_build as
        // path_idle with 0 progress leading to build_timeout) is skipped if not resolved within 50t; flattening/building then continues best-effort, without insisting on cells the bot can't stand near.
        if (elapsed - flattenTargetTick > 50) {
            note = "flatten_skip=" + compact(currentFlattenTarget.pos()); // Skipping a flatten cell that's out of reach (prevents an in-place infinite loop)
            // Unlike a skipped build block (build_block_skipped below), a skipped flatten cell
            // leaves no BotLog trace at all otherwise: the site may still finish the structure and
            // "complete" while a hole/bump was silently left under it, and only StructureVerifier's
            // eventual mismatch (if any) would hint at why -- with nothing pointing back to this cell.
            BotLog.action(bot, "build_flatten_skipped",
                    "pos", compact(currentFlattenTarget.pos()),
                    "kind", currentFlattenTarget.kind());
            currentFlattenTarget = null;
            return;
        }
        if (currentFlattenTarget.kind() == FlattenKind.CLEAR) {
            clearFlattenBlock(bot);
        } else {
            fillFlattenBlock(bot);
        }
    }

    private void clearFlattenBlock(AIPlayerEntity bot) {
        BlockPos pos = currentFlattenTarget.pos();
        if (!ensureObservableWorkPose(bot, pos, "flatten_clear")) {
            return;
        }
        if (bot.getEntityWorld().getBlockState(pos).isAir()) {
            Standability.clearCache();
            currentFlattenTarget = null;
            retryTicks = 0;
            return;
        }
        if (bot.getActionPack().isMiningIdle()) {
            // Self-heal like clearBlueprintAir: a pause (AbstractTask.onPause() -> stopAll())
            // can idle the mining action pack mid-mine without resetting flattenMiningStarted,
            // which would otherwise permanently block re-issuing startMining on resume.
            if (flattenMiningStarted) {
                flattenMiningStarted = false;
            }
            ActionResult result = MiningAction.startMining(bot, pos, Direction.getFacing(bot.getEyePos().subtract(pos.toCenterPos())));
            if (result.isFailed()) {
                fail(result.reason());
                return;
            }
            flattenMiningStarted = true;
        }
    }

    private void fillFlattenBlock(AIPlayerEntity bot) {
        BlockPos pos = currentFlattenTarget.pos();
        if (!ensureObservableWorkPose(bot, pos, "flatten_fill")) {
            return;
        }
        if (!bot.getEntityWorld().getBlockState(pos).isAir()) {
            Standability.clearCache();
            currentFlattenTarget = null;
            retryTicks = 0;
            return;
        }
        OptionalInt slot = MaterialPalette.pickSlot(bot, "dirt_like");
        if (slot.isEmpty()) {
            slot = MaterialPalette.pickAnyBlockSlot(bot);
        }
        if (slot.isEmpty()) {
            fail("missing_flatten_material");
            return;
        }
        InventoryAction.equipFromSlot(bot, slot.getAsInt());
        ActionResult result = BuildAction.placeBlockAt(bot, pos);
        if (result.isSuccess()) {
            Standability.clearCache();
            currentFlattenTarget = null;
            retryTicks = 0;
            placeDelayTicks = 2;
            return;
        }
        retryTicks++;
        BlockPos stand = nearbyStand(bot, pos);
        if (stand != null && bot.getActionPack().isPathExecutorIdle()) {
            bot.getActionPack().startPathTo(stand);
        }
        if (retryTicks > 12) {
            fail("flatten_fill_failed: " + result.reason() + " at " + compact(pos));
        }
    }

    private void build(AIPlayerEntity bot) {
        if (nextIndex >= blueprint.placements().size()) {
            var report = StructureVerifier.verify(
                    bot.getEntityWorld(), blueprint, anchor, placedBlocks, skippedBlocks);
            bot.getActionPack().stopAll();
            if (report.mismatched() > 0 || report.matched() != report.expected()) {
                fail("structure_incomplete: matched=" + report.matched()
                        + "/" + report.expected()
                        + " skipped=" + report.skipped());
            } else {
                complete();
            }
            return;
        }
        BlueprintSchema.BlockPlacement placement = blueprint.placements().get(nextIndex);
        BlockPos pos = anchor.add(placement.dx(), placement.dy(), placement.dz());
        // Registry validation is pure and must happen before visibility/path budgets. Otherwise an
        // invalid ID can be skipped while still unseen and reach terminal structure verification,
        // where it used to surface as an uncaught Identifier exception.
        Block block = resolveBlock(placement.blockId());
        if (block == null) {
            return;
        }
        // Build-cell budget (mirrors flatten's 50t skip): when the same block repeatedly fails to place, record it and keep going with the rest of the structure first, so a single cell
        // can't stall the whole executor into build_timeout. At the end, verify world state against the full blueprint (including AIR); only an exact
        // match counts as complete, otherwise it fails with structure_incomplete -- best-effort progress must never be misreported as finished.
        if (nextIndex != buildTargetIndex) {
            buildTargetIndex = nextIndex;
            buildTargetTick = elapsed;
            retryTicks = 0;
            buildMiningStarted = false;
        } else if (elapsed - buildTargetTick > 80) {
            skipBuildTarget(bot, pos, "target_timeout");
            return;
        }
        if (!ensureObservableWorkPose(bot, pos, "build")) {
            return;
        }
        if (block == Blocks.AIR) {
            clearBlueprintAir(bot, pos);
            return;
        }
        if (bot.getEntityWorld().getBlockState(pos).isOf(block)
                || (placement.palette() != null && MaterialPalette.matchesBlock(bot.getEntityWorld().getBlockState(pos), placement.palette()))) {
            nextIndex++;
            return;
        }
        OptionalInt slot = materialSlot(bot, placement, block);
        if (slot.isEmpty()) {
            fail("missing_material: " + materialName(placement));
            return;
        }
        InventoryAction.equipFromSlot(bot, slot.getAsInt());
        ActionResult result = BuildAction.placeBlockAt(bot, pos);
        if (result.isSuccess()) {
            placedBlocks++;
            nextIndex++;
            retryTicks = 0;
            placeDelayTicks = 2;
            return;
        }
        retryTicks++;
        BlockPos stand = nearbyStand(bot, pos);
        if (stand != null && bot.getActionPack().isPathExecutorIdle()) {
            bot.getActionPack().startPathTo(stand);
        }
        placeDelayTicks = 5;
        if (retryTicks > 12) {
            // best-effort execution: when a single block repeatedly fails, keep going with the rest of the structure first; the final state will still
            // fail explicitly because skippedBlocks>0. This is a double safeguard alongside the 80t budget above -- whichever triggers first records the gap.
            skipBuildTarget(bot, pos, "place_failed:" + result.reason());
        }
    }

    private void clearBlueprintAir(AIPlayerEntity bot, BlockPos pos) {
        if (!ensureObservableWorkPose(bot, pos, "build_air")) {
            return;
        }
        if (bot.getEntityWorld().getBlockState(pos).isAir()) {
            if (buildMiningStarted) {
                placedBlocks++;
            }
            buildMiningStarted = false;
            nextIndex++;
            retryTicks = 0;
            return;
        }
        if (!bot.getActionPack().isMiningIdle()) {
            return;
        }
        if (buildMiningStarted) {
            buildMiningStarted = false;
            retryTicks++;
        }
        ActionResult result = MiningAction.startMining(
                bot, pos, Direction.getFacing(bot.getEyePos().subtract(pos.toCenterPos())));
        if (result.isFailed()) {
            retryTicks++;
            if (retryTicks > 12) {
                skipBuildTarget(bot, pos, "air_clear_failed:" + result.reason());
            }
            return;
        }
        buildMiningStarted = true;
    }

    private boolean ensureObservableWorkPose(AIPlayerEntity bot, BlockPos pos, String reason) {
        double reach = bot.getBlockInteractionRange();
        if (ObservableWorldQuery.canObserveCell(bot, pos)
                && moveWithinReach(bot, pos, reason, reach * reach)) {
            return true;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            BlockPos stand = nearbyStand(bot, pos);
            if (stand != null) {
                ActionResult path = bot.getActionPack().startPathTo(stand);
                if (path.isFailed() && "pathfinding_throttled".equals(path.reason())) {
                    placeDelayTicks = 4;
                }
            }
        }
        return false;
    }

    private void skipBuildTarget(AIPlayerEntity bot, BlockPos pos, String reason) {
        bot.getActionPack().stopAll();
        BotLog.action(bot, "build_block_skipped",
                "index", nextIndex,
                "pos", compact(pos),
                "reason", reason);
        note = "build_skip=" + nextIndex + ":" + reason;
        skippedBlocks++;
        nextIndex++;
        retryTicks = 0;
        buildMiningStarted = false;
    }

    private OptionalInt materialSlot(AIPlayerEntity bot, BlueprintSchema.BlockPlacement placement, Block block) {
        if (placement.palette() != null && !placement.palette().isBlank()) {
            if (!MaterialPalette.isKnown(placement.palette())) {
                fail("unknown_palette: " + placement.palette());
                return OptionalInt.empty();
            }
            OptionalInt slot = MaterialPalette.pickSlot(bot, placement.palette());
            if (slot.isPresent()) {
                return slot;
            }
        }
        Item item = block.asItem();
        if (!(item instanceof BlockItem)) {
            fail("not_placeable_item: " + placement.blockId());
            return OptionalInt.empty();
        }
        return InventoryAction.findItem(bot, item);
    }

    private Block resolveBlock(String blockId) {
        Identifier id;
        try {
            id = Identifier.of(blockId);
        } catch (RuntimeException exception) {
            fail("invalid_block_id: " + blockId);
            return null;
        }
        return Registries.BLOCK.getOptionalValue(id)
                .orElseGet(() -> {
                    fail("unknown_block_id: " + id);
                    return null;
                });
    }

    private boolean moveWithinReach(AIPlayerEntity bot, BlockPos pos, String reason, double maxDistanceSquared) {
        // Never place a blueprint block through the bot's feet or head. Operator mode used to
        // hide this mistake with an emergency teleport; strict survival must first walk to a
        // real adjacent stand position.
        BlockPos feet = bot.getBlockPos();
        if (pos.equals(feet) || pos.equals(feet.up())) {
            if (bot.getActionPack().isPathExecutorIdle()) {
                BlockPos stand = nearbyStand(bot, pos);
                if (stand == null || stand.equals(feet)) {
                    fail("no_safe_stand_for_" + reason + ": " + compact(pos));
                    return false;
                }
                ActionResult path = bot.getActionPack().startPathTo(stand);
                if (path.isFailed() && !"pathfinding_throttled".equals(path.reason())) {
                    retryTicks++;
                    if (retryTicks > 12) {
                        fail("path_to_safe_stand_for_" + reason + "_failed: " + path.reason());
                    }
                }
            }
            return false;
        }
        // A path started to gain a safe angle is part of this placement attempt. Let it finish;
        // stopping it merely because the block is already within raw reach leaves the bot on the
        // same occluded side forever.
        if (!bot.getActionPack().isPathExecutorIdle()) {
            return false;
        }
        if (bot.getEyePos().squaredDistanceTo(pos.toCenterPos()) > maxDistanceSquared) {
            BlockPos stand = nearbyStand(bot, pos);
            if (stand == null) {
                fail("no_stand_position_for_" + reason + ": " + compact(pos));
                return false;
            }
            ActionResult path = bot.getActionPack().startPathTo(stand);
            if (path.isFailed()) {
                if ("pathfinding_throttled".equals(path.reason())) {
                    // Throttle backoff: pathfinding is rate-limited globally. When flattening requests a new path every tick for each cell, retrying every single tick would [always hit the rate limit] ->
                    // the bot never reaches even its first flatten target, 0 place/0 mine -> build_timeout (observed in real_build as flatten stuck at 0 progress).
                    // Back off 4 ticks to let the rate-limit window clear before retrying (not counted against the failure budget); the placeDelayTicks>0 check at the top of onTick skips it correctly.
                    placeDelayTicks = 4;
                } else {
                    // Only count against the retry budget when there's genuinely no path (no stand position / real obstruction); declare it dead past 12.
                    retryTicks++;
                    if (retryTicks > 12) {
                        fail("path_to_" + reason + "_failed: " + path.reason());
                    }
                }
            }
            return false;
        }
        return true;
    }

    private BlockPos nearbyStand(AIPlayerEntity bot, BlockPos pos) {
        Standability.clearCache();
        BlockPos current = bot.getBlockPos();
        BlockPos exterior = preferredExteriorStand(bot, pos, current);
        if (exterior != null) {
            return exterior;
        }
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        // Search a small shell around the target at practical foot heights. Using only target.y
        // misses the ordinary case where a wall block is one to four blocks above the ground.
        for (int radius = 1; radius <= 3; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    for (int y = pos.getY() - 4; y <= pos.getY() + 1; y++) {
                        BlockPos candidate = new BlockPos(pos.getX() + dx, y, pos.getZ() + dz);
                        int fromDx = candidate.getX() - current.getX();
                        int fromDz = candidate.getZ() - current.getZ();
                        boolean pathAlreadyConsidersArrived = fromDx * fromDx + fromDz * fromDz <= 1
                                && Math.abs(candidate.getY() - current.getY()) <= 1;
                        if (candidate.equals(current)
                                || pathAlreadyConsidersArrived
                                || candidate.getSquaredDistance(pos) > 100.0D
                                || !isObservableStandable(bot, candidate)) {
                            continue;
                        }
                        double score = candidate.getSquaredDistance(current)
                                + candidate.getSquaredDistance(pos) * 0.05D;
                        if (score < bestScore) {
                            bestScore = score;
                            best = candidate.toImmutable();
                        }
                    }
                }
            }
        }
        return best;
    }

    /**
     * Pick a predictable work position outside the known blueprint bounds. This keeps the bot
     * from slowly walling itself into the structure and gives it a clear angle to the nearest
     * facade without inspecting hidden terrain inside the footprint.
     */
    private BlockPos preferredExteriorStand(AIPlayerEntity bot, BlockPos target, BlockPos current) {
        if (anchor == null) {
            return null;
        }
        int localX = target.getX() - anchor.getX();
        int localZ = target.getZ() - anchor.getZ();
        int left = Math.abs(localX);
        int right = Math.abs(blueprint.width() - 1 - localX);
        int front = Math.abs(localZ);
        int back = Math.abs(blueprint.depth() - 1 - localZ);
        int min = Math.min(Math.min(left, right), Math.min(front, back));

        BlockPos horizontal;
        if (min == left) {
            horizontal = new BlockPos(anchor.getX() - 2, anchor.getY(), target.getZ());
        } else if (min == right) {
            horizontal = new BlockPos(anchor.getX() + blueprint.width() + 1, anchor.getY(), target.getZ());
        } else if (min == front) {
            horizontal = new BlockPos(target.getX(), anchor.getY(), anchor.getZ() - 2);
        } else {
            horizontal = new BlockPos(target.getX(), anchor.getY(), anchor.getZ() + blueprint.depth() + 1);
        }

        for (int delta = 0; delta <= 4; delta++) {
            for (int y : delta == 0
                    ? new int[]{anchor.getY()}
                    : new int[]{anchor.getY() + delta, anchor.getY() - delta}) {
                BlockPos candidate = new BlockPos(horizontal.getX(), y, horizontal.getZ());
                if (!pathAlreadyConsidersArrived(current, candidate)
                        && candidate.getSquaredDistance(target) <= 100.0D
                        && isObservableStandable(bot, candidate)) {
                    return candidate.toImmutable();
                }
            }
        }
        return null;
    }

    /**
     * Work-pose selection is part of planning, not the pathfinder's local collision adapter. In
     * strict survival it therefore has to prove the ground, feet, and head cells observable before
     * consulting raw standability. Operator mode passes these queries through its explicit hidden
     * scan capability.
     */
    private static boolean isObservableStandable(AIPlayerEntity bot, BlockPos candidate) {
        return ObservableWorldQuery.canObserveBlock(bot, candidate.down())
                && ObservableWorldQuery.canObserveCell(bot, candidate)
                && ObservableWorldQuery.canObserveCell(bot, candidate.up())
                && Standability.isStandable(bot.getEntityWorld(), candidate);
    }

    private static boolean pathAlreadyConsidersArrived(BlockPos current, BlockPos candidate) {
        int dx = candidate.getX() - current.getX();
        int dz = candidate.getZ() - current.getZ();
        return dx * dx + dz * dz <= 1 && Math.abs(candidate.getY() - current.getY()) <= 1;
    }

    private static String materialName(BlueprintSchema.BlockPlacement placement) {
        return placement.palette() == null || placement.palette().isBlank() ? placement.blockId() : "palette:" + placement.palette();
    }

    private static String compact(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    public BlockPos anchor() {
        return anchor == null ? null : anchor.toImmutable();
    }

    public BlueprintSchema blueprint() {
        return blueprint;
    }

    public int placedBlocks() {
        return placedBlocks;
    }

    public int skippedBlocks() {
        return skippedBlocks;
    }

    public void restoreAnchor(BlockPos restoredAnchor) {
        if (restoredAnchor != null && phase == Phase.SITE) {
            this.anchor = restoredAnchor.toImmutable();
            this.note = "restored_anchor=" + compact(this.anchor);
        }
    }

    private record FlattenTarget(BlockPos pos, FlattenKind kind) {
    }
}
