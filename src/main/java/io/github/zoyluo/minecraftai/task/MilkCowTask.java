package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.action.MilkCowAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.item.Items;

/**
 * Milk cow task: find the nearest adult cow, approach it, and use an empty bucket to milk out `target` buckets of milk (MILK_BUCKET).
 * best-effort: when there is no empty bucket / no cow nearby / no progress for a long time, complete if at least 1 bucket has already been milked, and only fail if zero buckets have been milked (does not block the parent goal).
 */
public final class MilkCowTask extends AbstractTask {
    private static final double SEARCH = 32.0D;
    private static final double MILK_RANGE = 3.5D;
    private static final int NO_PROGRESS_LIMIT = 600;

    private final int target;
    private int milked;
    private int lastProgressTick;
    private String note = "";

    public MilkCowTask(int target) {
        this.target = Math.max(1, target);
    }

    @Override
    public String name() {
        return "milk_cow";
    }

    @Override
    public String describe() {
        return "milk_cow " + milked + "/" + target + (note.isBlank() ? "" : " note=" + note);
    }

    @Override
    public double progress() {
        return state == TaskState.COMPLETED ? 1.0D : Math.min(0.95D, (double) milked / target);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        milked = 0;
        lastProgressTick = 0;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (milked >= target) {
            complete();
            return;
        }
        if (InventoryAction.countItem(bot, Items.BUCKET) <= 0) {
            finishOrFail("missing_bucket");
            return;
        }
        if (elapsed - lastProgressTick > NO_PROGRESS_LIMIT) {
            // note is the specific reason the most recent milk() call failed (cow out of milking range / action rejected, etc.); looking only at
            // "milk_no_progress" tells you there has been no progress for 600 ticks, but not whether it is repeatedly missing the same cow or something else.
            finishOrFail("milk_no_progress" + (note.isBlank() ? "" : ":" + note));
            return;
        }
        CowEntity cow = MilkCowAction.nearestCow(bot, SEARCH);
        if (cow == null) {
            finishOrFail("no_cow");
            return;
        }
        if (bot.getEyePos().distanceTo(cow.getEyePos()) <= MILK_RANGE) {
            bot.getActionPack().stopMovement();
            ActionResult result = MilkCowAction.milk(bot);
            if (result.isSuccess()) {
                milked++;
                lastProgressTick = elapsed;
            } else {
                note = result.reason();
            }
            return;
        }
        // Walk toward the cow (the cow moves, so keep re-targeting; fall back to a straight-line walk if A* fails).
        if (bot.getActionPack().isPathExecutorIdle()) {
            ActionResult path = bot.getActionPack().startPathTo(cow.getBlockPos());
            if (path.isFailed()) {
                bot.getActionPack().startWalkTo(cow.getEntityPos());
            }
        }
    }

    private void finishOrFail(String reason) {
        if (milked > 0) {
            complete();
        } else {
            fail(reason);
        }
    }
}
