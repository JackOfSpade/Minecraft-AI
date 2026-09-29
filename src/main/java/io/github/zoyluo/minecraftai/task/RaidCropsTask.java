package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.BlockMiner;
import io.github.zoyluo.minecraftai.action.FarmAction;
import io.github.zoyluo.minecraftai.action.HarvestCore;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mining.OreProspector;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.CropBlock;

/**
 * Village/wild crop raiding: wide-area scan for mature crops (wheat/carrots/potatoes/beetroot), walk over, harvest, pick up drops, until the target output count is reached.
 * Unlike FarmTask (which tills and plants within a fixed area), this task looks for mature crop fields that already exist in the world (typically village farmland) — it doesn't plant, only harvests.
 * best-effort: when no mature crops can be found / there's been no progress for a long time, completes once ≥1 has been harvested, and only fails if none were harvested.
 * Behavior boundary: only breaks and picks up the crops themselves (villagers replant on their own; vanilla doesn't dock reputation); doesn't open villager chests or grab trade goods — that's outside the scope of "crop raiding".
 *
 * <p>Survival-legal: crops are found by the outline the bot can actually see (a crop has no collider, so the
 * collider-ray block query never sees one), broken through the real mining action after a reach and
 * visibility proof, and the drops are collected by walking over them (forced pickup is denied in strict_survival).</p>
 */
public final class RaidCropsTask extends AbstractTask {
    private enum Phase {SCAN, GOTO, HARVEST, PICKUP, DONE}

    private static final int SCAN_RADIUS = 64;
    private static final long SCAN_BUDGET_NANOS = 2_000_000L; // per-tick share of a resumable scan
    private static final double REACH = 4.5D;
    private static final int NO_PROGRESS_LIMIT = 1200;
    private static final int PICKUP_BUDGET_TICKS = 100;
    private static final double PICKUP_RADIUS = 8.0D;
    // Output items to pick up from harvest drops (each crop's yield + bonus seeds).
    private static final Set<Item> CROP_DROPS = Set.of(
            Items.WHEAT, Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO, Items.BEETROOT, Items.BEETROOT_SEEDS);

    private final int target;
    private final BlockMiner harvestMiner = new BlockMiner();
    private final Set<BlockPos> skipped = new HashSet<>(); // crops whose click proof failed: not retried
    private int harvested;
    private int lastProgressTick;
    private int pickupTicks;
    private BlockPos current;
    private OreProspector.Scan scan;
    private Phase phase = Phase.SCAN;
    private String note = "";

    public RaidCropsTask(int target) {
        this.target = Math.max(1, target);
    }

    @Override
    public String name() {
        return "raid_crops";
    }

    @Override
    public String describe() {
        return "raid_crops " + harvested + "/" + target + " phase=" + phase
                + (current == null ? "" : " at=" + current.getX() + "," + current.getY() + "," + current.getZ())
                + (note.isBlank() ? "" : " note=" + note);
    }

    @Override
    public double progress() {
        return state == TaskState.COMPLETED ? 1.0D : Math.min(0.95D, (double) harvested / target);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        harvested = 0;
        lastProgressTick = 0;
        pickupTicks = 0;
        scan = null;
        skipped.clear();
        harvestMiner.cancel(bot);
        phase = Phase.SCAN;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (harvested >= target && phase != Phase.PICKUP && phase != Phase.HARVEST) {
            complete();
            return;
        }
        if (elapsed - lastProgressTick > NO_PROGRESS_LIMIT) {
            finishOrFail(bot, "raid_no_progress");
            return;
        }
        switch (phase) {
            case SCAN -> scan(bot);
            case GOTO -> goTo(bot);
            case HARVEST -> harvest(bot);
            case PICKUP -> pickup(bot);
            case DONE -> complete();
        }
    }

    /**
     * Finds the nearest mature crop the bot can see (its real outline, within the perception radius), a
     * few milliseconds per tick. The state filter only narrows which cells get a ray; the answer still
     * comes only from cells whose outline is observed.
     */
    private void scan(AIPlayerEntity bot) {
        if (scan == null) {
            var world = bot.level();
            scan = OreProspector.beginFarmCells(bot, SCAN_RADIUS, RaidCropsTask::isMatureCrop,
                    pos -> !skipped.contains(pos) && isMatureCrop(world.getBlockState(pos)));
        }
        if (!scan.step(SCAN_BUDGET_NANOS)) {
            return;
        }
        BlockPos found = scan.result();
        scan = null;
        if (found == null) {
            finishOrFail(bot, "no_mature_crops");
            return;
        }
        current = found;
        phase = Phase.GOTO;
    }

    private void goTo(AIPlayerEntity bot) {
        if (current == null || !isMatureCrop(bot.level().getBlockState(current))) {
            phase = Phase.SCAN; // Target is gone (eaten/harvested), rescan
            return;
        }
        if (bot.getEyePosition().distanceTo(current.getCenter()) <= REACH) {
            bot.getActionPack().stopAll();
            phase = Phase.HARVEST;
            return;
        }
        BlockPos stand = ContainerSupport.adjacentStand(bot, current);
        if (stand == null) {
            note = "unreachable " + current;
            skipped.add(current);
            current = null;
            phase = Phase.SCAN;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            ActionResult path = bot.getActionPack().startPathTo(stand);
            if (path.isFailed()) {
                skipped.add(current);
                current = null;
                phase = Phase.SCAN;
            }
        }
    }

    private void harvest(AIPlayerEntity bot) {
        if (harvestMiner.target() == null) {
            ActionResult proof = FarmAction.harvestProof(bot, current);
            if (proof.isFailed()) {
                note = proof.reason();
                skipped.add(current);
                current = null;
                phase = Phase.SCAN;
                return;
            }
            harvestMiner.begin(bot, current);
        }
        BlockMiner.Status status = harvestMiner.tick(bot);
        if (status == BlockMiner.Status.MINING) {
            return;
        }
        if (status == BlockMiner.Status.FAILED) {
            note = "harvest_failed:" + harvestMiner.failureReason();
            skipped.add(current);
            current = null;
            phase = Phase.SCAN;
            return;
        }
        harvested++;
        lastProgressTick = elapsed;
        BotLog.action(bot, "raid_harvest", "pos", current);
        // Drops are ItemEntities on the ground that harvest range cannot reach by auto-pickup: walk over them.
        pickupTicks = 0;
        phase = Phase.PICKUP;
    }

    private void pickup(AIPlayerEntity bot) {
        pickupTicks++;
        boolean dropsLeft = HarvestCore.walkOverDrops(bot, CROP_DROPS, PICKUP_RADIUS);
        if (dropsLeft && pickupTicks <= PICKUP_BUDGET_TICKS) {
            return;
        }
        bot.getActionPack().stopMovement();
        if (dropsLeft) {
            note = "pickup_budget_spent";
        }
        current = null;
        phase = Phase.SCAN;
    }

    private static boolean isMatureCrop(net.minecraft.world.level.block.state.BlockState state) {
        return state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state);
    }

    private void finishOrFail(AIPlayerEntity bot, String reason) {
        if (harvested > 0) {
            // task_completed only carries elapsed_ticks, so a best-effort partial completion
            // (harvested < target) would otherwise look identical to a full success in the logs --
            // no way to tell it stopped early, or why (no more mature crops vs. stalled progress).
            BotLog.action(bot, "raid_crops_partial", "harvested", harvested, "target", target, "reason", reason);
            complete();
        } else {
            fail(reason);
        }
    }
}
