package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.FarmAction;
import io.github.zoyluo.minecraftai.action.HarvestCore;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.log.BotLog;
import io.github.zoyluo.minecraftai.mining.OreProspector;
import net.minecraft.block.CropBlock;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.Set;

/**
 * Village/wild crop raiding: wide-area scan for mature crops (wheat/carrots/potatoes/beetroot), walk over, harvest, pick up drops, until the target output count is reached.
 * Unlike FarmTask (which tills and plants within a fixed area), this task looks for mature crop fields that already exist in the world (typically village farmland) — it doesn't plant, only harvests.
 * best-effort: when no mature crops can be found / there's been no progress for a long time, completes once ≥1 has been harvested, and only fails if none were harvested.
 * Behavior boundary: only breaks and picks up the crops themselves (villagers replant on their own; vanilla doesn't dock reputation); doesn't open villager chests or grab trade goods — that's outside the scope of "crop raiding".
 */
public final class RaidCropsTask extends AbstractTask {
    private enum Phase {SCAN, GOTO, HARVEST, DONE}

    private static final int SCAN_RADIUS = 64;
    private static final double REACH = 4.5D;
    private static final int NO_PROGRESS_LIMIT = 1200;
    // Output items to pick up from harvest drops (each crop's yield + bonus seeds).
    private static final Set<Item> CROP_DROPS = Set.of(
            Items.WHEAT, Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO, Items.BEETROOT, Items.BEETROOT_SEEDS);

    private final int target;
    private int harvested;
    private int lastProgressTick;
    private BlockPos current;
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
        phase = Phase.SCAN;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (harvested >= target) {
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
            case DONE -> complete();
        }
    }

    private void scan(AIPlayerEntity bot) {
        ServerWorld world = bot.getEntityWorld();
        BlockPos found = OreProspector.nearest(bot, SCAN_RADIUS, RaidCropsTask::isMatureCrop);
        if (found == null) {
            finishOrFail(bot, "no_mature_crops");
            return;
        }
        current = found;
        phase = Phase.GOTO;
    }

    private void goTo(AIPlayerEntity bot) {
        if (current == null || !isMatureCrop(bot.getEntityWorld().getBlockState(current))) {
            phase = Phase.SCAN; // Target is gone (eaten/harvested), rescan
            return;
        }
        if (bot.getEyePos().distanceTo(current.toCenterPos()) <= REACH) {
            bot.getActionPack().stopAll();
            phase = Phase.HARVEST;
            return;
        }
        BlockPos stand = ContainerSupport.adjacentStand(bot, current);
        if (stand == null) {
            note = "unreachable " + current;
            current = null;
            phase = Phase.SCAN;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            ActionResult path = bot.getActionPack().startPathTo(stand);
            if (path.isFailed()) {
                current = null;
                phase = Phase.SCAN;
            }
        }
    }

    private void harvest(AIPlayerEntity bot) {
        ActionResult result = FarmAction.harvest(bot, current);
        if (result.isSuccess()) {
            // Harvest drops are ItemEntities on the ground; auto-pickup can't reach them from harvest range → force pickup (same fix as FarmTask/dig_down).
            HarvestCore.forcePickupNearbyAnyOf(bot, CROP_DROPS, 5.0D, 4.0D);
            harvested++;
            lastProgressTick = elapsed;
        } else {
            note = result.reason();
        }
        current = null;
        phase = Phase.SCAN;
    }

    private static boolean isMatureCrop(net.minecraft.block.BlockState state) {
        return state.getBlock() instanceof CropBlock crop && crop.isMature(state);
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
