package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.BlockMiner;
import io.github.zoyluo.minecraftai.action.HarvestCore;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.log.LogCategory;
import io.github.zoyluo.minecraftai.mining.OreScan;
import io.github.zoyluo.minecraftai.mining.ToolTier;
import io.github.zoyluo.minecraftai.mining.ValuableScan;
import io.github.zoyluo.minecraftai.mode.ObservableWorldQuery;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * "mine all valuables you can see within N blocks" -- a single deterministic, self-contained
 * task (assigned via the plain {@code TaskManager}/{@code assignLlm} path, not the re-entrant
 * {@code GoalExecutor}, since a frozen one-time snapshot is the opposite of a re-plannable goal).
 *
 * <p><b>Scope discipline is the whole point of this task.</b> On {@link #onStart}, it captures the
 * bot's position at the instant the command was issued ({@code origin}) and, over the following
 * ticks, takes ONE honest look around: every position within the true sphere of radius
 * {@code radius} that the bot could actually see from {@code origin} at that moment (through the
 * project's real raycast gate, {@link ObservableWorldQuery#canObserveBlockWithin}, never a bare
 * {@code getBlockState}) and that held a {@linkplain ValuableScan valuable} block becomes one
 * immutable snapshot entry. That list is built exactly once and is never appended to again --
 * only ever drained as entries are mined, found gone, or skipped. However far the bot may walk,
 * dig, pillar or bridge while working through it, nothing newly exposed along the way is ever
 * added. This is the literal fix for "do NOT infinitely expand scope".
 *
 * <p>The scan itself never reads a block's type before {@code canObserveBlockWithin} has proven
 * that exact cell observable at that exact tick -- the same discipline {@link OreScan} already
 * follows. A true {@code radius}-block sphere scan (up to the schema's 50-block cap) is far larger
 * than anything else in this codebase attempts honestly (existing large-radius scans quietly rely
 * on the default 16-block perception cap to keep cheap-reject rays cheap; this feature explicitly
 * needs to bypass that cap). Doing every raycast in one tick would stall the server, so the scan
 * is paced across many ticks with a small fixed per-tick budget of real observability checks
 * ({@link #SCAN_BUDGET_PER_TICK}) -- the bot does not move or rotate while scanning, so the frozen
 * eye position from {@code onStart} stays valid throughout.
 *
 * <p>Reaching a target reuses the ordinary {@code ActionPack.startPathTo}/{@code startDigPathTo}
 * machinery (free pillar-up whenever a placeable block is carried) plus the new horizontal
 * {@code MoveType.BRIDGE} wired through {@code NeighborEnumerator}/{@code PathExecutor} for
 * crossing open gaps. Mining itself reuses {@link BlockMiner} and {@link HarvestCore}'s pickup
 * helpers exactly as {@link MineTask} does. A snapshot entry is re-verified with
 * {@link OreScan#observe} immediately before it is actually mined (it may have been mined by
 * something else, or turn out to be occluded/unconfirmable up close); {@code UNKNOWN} is never
 * treated as either present or gone, and is simply skipped like a confirmed miss.
 */
public final class MineValuablesTask extends AbstractTask {
    public static final int DEFAULT_RADIUS = 20;
    public static final int MAX_RADIUS = 50;

    /** Real observability checks (each up to 6 raycasts) performed per tick during the scan. */
    private static final int SCAN_BUDGET_PER_TICK = 150;
    /** Safety cap on one snapshot's size; geometry alone rarely gets near this for real ore. */
    private static final int MAX_TARGETS = 300;
    /** Per-target travel+mine+pickup budget; the overall mining deadline scales by target count. */
    private static final int PER_TARGET_BUDGET_TICKS = 400;
    /** Generous ceiling on the paced scan phase itself (worst case: a fully exposed 50-radius sphere). */
    private static final int SCAN_TIMEOUT_TICKS = 6000;
    private static final int TORCH_CHECK_INTERVAL_TICKS = 20;

    private enum Phase {
        SCANNING,
        SELECTING,
        MOVING,
        MINING,
        PICKING_UP,
        DONE
    }

    /** One frozen, legitimately-observed sighting: what was seen, not merely where. */
    private record Sighting(BlockPos pos, BlockState state) {
    }

    private final int radius;
    private final BlockMiner miner = new BlockMiner();

    private BlockPos origin;
    private int scanSide;
    private long scanRadiusSq;
    private int scanIndex;
    private List<Sighting> scanBuffer;

    private Deque<Sighting> queue;
    private int totalTargets;
    private int minedCount;
    private int skippedGoneCount;
    private int skippedToolCount;
    private int skippedUnreachableCount;
    private int miningPhaseStartTick;

    private Phase phase = Phase.SCANNING;
    private BlockPos targetPos;
    private Set<Item> currentTargetDrops;
    private int inventoryCountBeforeMining;
    private int pickupTicks;
    private boolean pickupSweepAttempted;
    private int lastTorchCheckTick = -TORCH_CHECK_INTERVAL_TICKS;

    public MineValuablesTask(int radius) {
        this.radius = Math.min(MAX_RADIUS, Math.max(1, radius));
    }

    @Override
    public String name() {
        return "mine_valuables";
    }

    @Override
    public String describe() {
        if (phase == Phase.SCANNING) {
            return "Scanning valuables radius=" + radius;
        }
        return "Mining valuables " + minedCount + "/" + totalTargets + " phase=" + phase;
    }

    @Override
    public double progress() {
        if (phase == Phase.SCANNING) {
            long total = (long) scanSide * scanSide * scanSide;
            return total <= 0 ? 0.0D : Math.min(0.2D, (double) scanIndex / total * 0.2D);
        }
        if (totalTargets <= 0) {
            return state == TaskState.COMPLETED ? 1.0D : 0.2D;
        }
        int resolved = minedCount + skippedGoneCount + skippedToolCount + skippedUnreachableCount;
        return 0.2D + 0.8D * Math.min(1.0D, (double) resolved / totalTargets);
    }

    @Override
    public boolean isWaiting() {
        // Self-managed watchdogs below (the paced scan, the per-target and per-mission deadlines)
        // cover this task; the generic 200-tick no-progress StuckWatcher would otherwise kill a
        // multi-minute radius-50 scan or a long, perfectly healthy walk down the snapshot list.
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        // The frozen vantage point: captured once, here, synchronously with tool dispatch
        // (TaskManager.assign -> task.start runs in the same call as the LLM tool call), and never
        // updated again regardless of where the bot later walks.
        origin = bot.blockPosition().immutable();
        scanSide = 2 * radius + 1;
        scanRadiusSq = (long) radius * radius;
        scanIndex = 0;
        scanBuffer = new ArrayList<>();
        phase = Phase.SCANNING;
        BotLog.action(bot, "mine_valuables_start", "radius", radius, "origin", origin.toShortString());
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        maybePlaceTorch(bot);
        if (phase == Phase.SCANNING) {
            if (elapsed > SCAN_TIMEOUT_TICKS) {
                fail("mine_valuables_scan_timeout");
                return;
            }
            scanStep(bot);
            return;
        }
        if (phase != Phase.DONE) {
            int deadline = miningPhaseStartTick + PER_TARGET_BUDGET_TICKS * Math.max(1, totalTargets);
            if (elapsed > deadline) {
                fail(minedCount > 0 ? "mine_valuables_timeout_partial" : "mine_valuables_timeout");
                return;
            }
        }
        switch (phase) {
            case SELECTING -> select(bot);
            case MOVING -> move(bot);
            case MINING -> mine(bot);
            case PICKING_UP -> pickup(bot);
            case DONE -> complete();
            case SCANNING -> { /* handled above */ }
        }
    }

    // Paced, budgeted sphere scan. Cells outside the true sphere are skipped by cheap position
    // arithmetic alone (never consumes budget); every cell inside the sphere costs one real,
    // content-independent observability check before anything about it is read. Resumable across
    // ticks via the flat scanIndex cursor so a single tick's raycast cost stays bounded regardless
    // of the requested radius.
    private void scanStep(AIPlayerEntity bot) {
        var world = bot.level();
        int side = scanSide;
        long total = (long) side * side * side;
        int budget = SCAN_BUDGET_PER_TICK;
        while (budget > 0 && scanIndex < total) {
            int i = scanIndex++;
            int dz = i % side;
            int dy = (i / side) % side;
            int dx = i / (side * side);
            int ddx = dx - radius;
            int ddy = dy - radius;
            int ddz = dz - radius;
            long distSq = (long) ddx * ddx + (long) ddy * ddy + (long) ddz * ddz;
            if (distSq > scanRadiusSq) {
                continue; // outside the true sphere: pure arithmetic, never touches the world
            }
            BlockPos pos = origin.offset(ddx, ddy, ddz);
            budget--;
            if (!ObservableWorldQuery.canObserveBlockWithin(bot, pos, radius)) {
                continue; // not actually visible from the frozen vantage point -- never read
            }
            BlockState state = world.getBlockState(pos);
            if (!ValuableScan.isValuable(state.getBlock())) {
                continue;
            }
            scanBuffer.add(new Sighting(pos.immutable(), state));
            if (scanBuffer.size() >= MAX_TARGETS) {
                scanIndex = (int) total;
                break;
            }
        }
        if (scanIndex >= total) {
            finishScan(bot);
        }
    }

    private void finishScan(AIPlayerEntity bot) {
        if (scanBuffer.isEmpty()) {
            fail("no_valuables_in_radius");
            return;
        }
        scanBuffer.sort(Comparator.comparingDouble(s -> s.pos().distSqr(origin)));
        queue = new ArrayDeque<>(scanBuffer);
        totalTargets = queue.size();
        scanBuffer = null;
        boolean anyMinable = queue.stream().anyMatch(s -> ToolTier.canHarvestWithInventory(bot, s.state()));
        if (!anyMinable) {
            fail("mine_valuables_need_better_tool");
            return;
        }
        miningPhaseStartTick = elapsed;
        BotLog.action(bot, "mine_valuables_snapshot", "count", totalTargets, "radius", radius,
                "origin", origin.toShortString());
        phase = Phase.SELECTING;
    }

    private void select(AIPlayerEntity bot) {
        Sighting sighting = queue.poll();
        if (sighting == null) {
            finishMission(bot);
            return;
        }
        targetPos = sighting.pos();
        // Cheap up-front tool-tier check using the state legitimately observed during the scan
        // itself (remembered fact, not a fresh unobserved read) -- skip before spending any travel
        // time on a target this bot cannot yet harvest. startMiningTarget() still re-checks against
        // a freshly re-observed state right before actually mining, since durability or inventory
        // may have changed since.
        if (!ToolTier.canHarvestWithInventory(bot, sighting.state())) {
            BotLog.action(bot, "mine_valuables_tool_skip", "pos", targetPos.toShortString(),
                    "need", ToolTier.requiredPickaxeItemId(sighting.state().getBlock()));
            skippedToolCount++;
            targetPos = null;
            return;
        }
        HarvestCore.TargetChoice choice = HarvestCore.targetChoice(bot, targetPos);
        if (choice == null) {
            // No direct reach and no adjacent standable cell right now. Never digs/pillars toward
            // it speculatively on its own -- that would be this task inventing its own new scope
            // decisions; just drop this frozen entry and move to the next one.
            skippedUnreachableCount++;
            targetPos = null;
            return; // stays in SELECTING; the next tick polls the following entry
        }
        if (choice.direct()) {
            startMiningTarget(bot);
            return;
        }
        phase = Phase.MOVING;
        bot.getActionPack().startPathTo(choice.stand());
    }

    private void move(AIPlayerEntity bot) {
        if (targetPos == null) {
            phase = Phase.SELECTING;
            return;
        }
        if (HarvestCore.canReach(bot, targetPos)) {
            bot.getActionPack().stopAll();
            startMiningTarget(bot);
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            // The path finished or gave up without reaching interaction range. This frozen target
            // is simply unreachable right now -- skip it, never substitute a newly-seen one.
            skippedUnreachableCount++;
            targetPos = null;
            phase = Phase.SELECTING;
        }
    }

    private void startMiningTarget(AIPlayerEntity bot) {
        // Re-verify right before committing to mine: a snapshot entry may have been mined by
        // something else since the scan, or (rarely) prove unconfirmable from this exact approach
        // angle. UNKNOWN is never treated as present or gone -- it is simply skipped, same as a
        // confirmed miss.
        OreScan.Observation observation = OreScan.observe(bot, targetPos,
                state -> ValuableScan.isValuable(state.getBlock()));
        if (observation == OreScan.Observation.OBSERVED_GONE) {
            BotLog.action(bot, "mine_valuables_target_gone", "pos", targetPos.toShortString());
            skippedGoneCount++;
            targetPos = null;
            phase = Phase.SELECTING;
            return;
        }
        if (observation == OreScan.Observation.UNKNOWN) {
            BotLog.action(bot, "mine_valuables_target_unconfirmed", "pos", targetPos.toShortString());
            skippedUnreachableCount++;
            targetPos = null;
            phase = Phase.SELECTING;
            return;
        }
        BlockState state = bot.level().getBlockState(targetPos);
        Block block = state.getBlock();
        if (!ToolTier.canHarvestWithInventory(bot, state)) {
            BotLog.action(bot, "mine_valuables_tool_skip", "pos", targetPos.toShortString(),
                    "need", ToolTier.requiredPickaxeItemId(block));
            skippedToolCount++;
            targetPos = null;
            phase = Phase.SELECTING;
            return;
        }
        if (hazardFluidAdjacent(bot, targetPos)) {
            BotLog.warn(LogCategory.TASK, bot, "mine_valuables_hazard_skip", "pos", targetPos.toShortString());
            skippedUnreachableCount++;
            targetPos = null;
            phase = Phase.SELECTING;
            return;
        }
        currentTargetDrops = expectedDropsForValuable(block);
        inventoryCountBeforeMining = HarvestCore.countInventoryItems(bot, currentTargetDrops);
        pickupSweepAttempted = false;
        miner.begin(bot, targetPos);
        phase = Phase.MINING;
    }

    private void mine(AIPlayerEntity bot) {
        if (targetPos == null) {
            phase = Phase.SELECTING;
            return;
        }
        BlockMiner.Status status = miner.tick(bot);
        if (status == BlockMiner.Status.DONE || status == BlockMiner.Status.FAILED) {
            pickupTicks = 120;
            phase = Phase.PICKING_UP;
        }
    }

    private void pickup(AIPlayerEntity bot) {
        int collected = HarvestCore.countInventoryItems(bot, currentTargetDrops) - inventoryCountBeforeMining;
        if (collected > 0) {
            bot.getActionPack().stopAll();
            BotLog.action(bot, "mine_valuables_collected", "count", collected, "pos", targetPos.toShortString());
            minedCount++;
            targetPos = null;
            phase = Phase.SELECTING;
            return;
        }
        pickupTicks--;
        HarvestCore.chaseDropAnyOf(bot, currentTargetDrops, 8.0D);
        if (pickupTicks <= 0) {
            if (!pickupSweepAttempted && HarvestCore.nearestDropAnyOf(bot, currentTargetDrops, 8.0D).isPresent()) {
                pickupSweepAttempted = true;
                HarvestCore.sweepPickupAnyOf(bot, currentTargetDrops, 8);
                pickupTicks = 60;
                return;
            }
            int partial = HarvestCore.countInventoryItems(bot, currentTargetDrops) - inventoryCountBeforeMining;
            bot.getActionPack().stopAll();
            if (partial > 0) {
                minedCount++;
            }
            // The block is already broken either way; move on to the next frozen target rather
            // than failing the whole mission over one missed pickup.
            targetPos = null;
            phase = Phase.SELECTING;
        }
    }

    private void finishMission(AIPlayerEntity bot) {
        BotLog.action(bot, "mine_valuables_done",
                "mined", minedCount, "total", totalTargets,
                "gone", skippedGoneCount, "tool_skip", skippedToolCount,
                "unreachable_skip", skippedUnreachableCount);
        if (minedCount == 0) {
            String reason;
            if (skippedToolCount == totalTargets) {
                reason = "mine_valuables_need_better_tool";
            } else if (skippedGoneCount == totalTargets) {
                reason = "mine_valuables_all_gone";
            } else {
                reason = "mine_valuables_none_reachable";
            }
            fail(reason);
            return;
        }
        phase = Phase.DONE;
        complete();
    }

    // Reactive, fully self-contained lighting -- deliberately never takes over TaskManager,
    // DangerWatcher or GoalExecutor. The shared helper accepts only an immediate vanilla-reachable
    // floor mount, so this frozen-snapshot miner never walks away from its current ore/drop just
    // to light an area. It is throttled and never blocks progress on missing torches.
    private void maybePlaceTorch(AIPlayerEntity bot) {
        if (!AutomaticLighting.miningTorchAutomationEnabled()
                || elapsed - lastTorchCheckTick < TORCH_CHECK_INTERVAL_TICKS) {
            return;
        }
        lastTorchCheckTick = elapsed;
        // A live block break or physical pickup owns the player controls and drop position.  Wait
        // for a quiet inter-target/scanning boundary rather than swapping to a torch mid-ledger.
        if (phase == Phase.PICKING_UP
                || miner.target() != null
                || !bot.getActionPack().isPathExecutorIdle()
                || !bot.getActionPack().isWalkToIdle()) {
            return;
        }
        var world = bot.level();
        AutomaticLighting.Placement placement = AutomaticLighting.tryPlaceDarkestReachable(bot);
        if (placement == AutomaticLighting.Placement.PLACED) {
            BotLog.action(bot, "mine_valuables_torch", "pos", bot.blockPosition().toShortString());
        }
        // Slot identity is not stable once a torch is equipped from a full inventory; restore the
        // active mining tool from the factual in-progress target rather than a stale slot index.
        if (miner.target() != null) {
            io.github.zoyluo.minecraftai.action.ToolSelector.equipBestTool(bot, world.getBlockState(miner.target()));
        }
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        miner.cancel(bot);
        bot.getActionPack().stopAll();
    }

    /**
     * {@link HarvestCore#expectedDropsFor(Block)} covers the vanilla ore family loot tables
     * exactly, but two entries in {@link ValuableScan#VALUABLES} have a loot table it does not
     * model (and would otherwise fall through to its generic "drops itself" default, which is
     * wrong for these two and would make pickup verification wait forever for an item that can
     * never appear): nether gold ore drops gold nuggets, and an amethyst cluster mined without
     * Silk Touch (this bot never carries an enchanted tool) drops amethyst shards, not the
     * cluster block itself.
     */
    private static Set<Item> expectedDropsForValuable(Block block) {
        if (block == Blocks.NETHER_GOLD_ORE) {
            return Set.of(Items.GOLD_NUGGET);
        }
        if (block == Blocks.AMETHYST_CLUSTER) {
            return Set.of(Items.AMETHYST_SHARD);
        }
        return HarvestCore.expectedDropsFor(block);
    }

    /**
     * Strict-survival, observation-gated hazard check (mirrors OreDigTask's own pre-mine adjacency
     * gate): only an ALREADY genuinely observable adjacent lava/water cell skips this target. A
     * still-hidden neighbour is UNKNOWN, never treated as a hazard -- reading raw fluid state
     * directly here (bypassing the observability gate) would be exactly the x-ray shortcut this
     * project's mining tasks have deliberately closed everywhere else.
     */
    private static boolean hazardFluidAdjacent(AIPlayerEntity bot, BlockPos pos) {
        return OreScan.adjacentHazard(bot, pos) == OreScan.Observation.OBSERVED_PRESENT;
    }
}
