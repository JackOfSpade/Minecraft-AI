package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ActionResult;
import io.github.zoyluo.minecraftai.action.EatAction;
import io.github.zoyluo.minecraftai.action.InventoryAction;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

public final class EatTask extends AbstractTask {
    private enum Phase {
        FINDING,
        STARTING,
        WAITING
    }

    /** Per-bite budget: unchanged from the original single-item timeout. */
    private static final int PER_ITEM_TIMEOUT_TICKS = 160;
    // Eat-to-full: keep taking bites until the food bar is 20, not just one item. Bounding the
    // number of bites (rather than only a flat tick count) keeps the task's overall budget scaled
    // to how much work it actually has to do -- one bite for a near-full bar, several for an
    // empty one -- while still failing closed instead of looping forever if something is wrong.
    private static final int MAX_ITEMS_PER_PASS = 12;

    private Phase phase = Phase.FINDING;
    private int startingFoodLevel;
    private int startingStackCount;
    private int waitTicks;
    /** Ticks spent on the current bite; reset at the start of each new bite. */
    private int itemElapsed;
    /** Bites successfully consumed so far this task instance. */
    private int itemsConsumed;

    @Override
    public String name() {
        return "eat";
    }

    @Override
    public String describe() {
        return "Eating phase=" + phase + " starting_food=" + startingFoodLevel
                + " items_consumed=" + itemsConsumed;
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return switch (phase) {
            case FINDING -> 0.0D;
            case STARTING -> 0.25D;
            case WAITING -> Math.min(0.95D, waitTicks / 40.0D);
        };
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.FINDING;
        itemsConsumed = 0;
        itemElapsed = 0;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (bot.getFoodData().getFoodLevel() >= 20) {
            complete();
            return;
        }
        if (itemsConsumed >= MAX_ITEMS_PER_PASS) {
            // Bounded: took as many bites as this pass budgets for. Still real progress (food
            // level only goes up), so this is a completion, not a failure.
            complete();
            return;
        }
        itemElapsed++;
        if (itemElapsed > PER_ITEM_TIMEOUT_TICKS) {
            finishOnTimeoutOrFailure("eat_timeout");
            return;
        }
        switch (phase) {
            case FINDING -> find(bot);
            case STARTING -> startEating(bot);
            case WAITING -> waitForFinish(bot);
        }
    }

    private void find(AIPlayerEntity bot) {
        if (InventoryAction.findFoodSlot(bot) < 0) {
            // No food at all: on the very first bite this is a real failure. Once at least one
            // bite has already landed (hunger already improved, or a stack was consumed), running
            // out of suitable food mid-pass is an ordinary stopping point, not a failure.
            finishOnTimeoutOrFailure("no_food");
            return;
        }
        startingFoodLevel = bot.getFoodData().getFoodLevel();
        phase = Phase.STARTING;
    }

    private void startEating(AIPlayerEntity bot) {
        ActionResult result = EatAction.startEating(bot);
        if (result.isFailed()) {
            finishOnTimeoutOrFailure(result.reason());
            return;
        }
        ItemStack stack = bot.getItemInHand(InteractionHand.MAIN_HAND);
        startingStackCount = stack.isEmpty() ? 0 : stack.getCount();
        waitTicks = 0;
        phase = Phase.WAITING;
    }

    private void waitForFinish(AIPlayerEntity bot) {
        waitTicks++;
        ItemStack stack = bot.getItemInHand(InteractionHand.MAIN_HAND);
        int currentCount = stack.isEmpty() ? 0 : stack.getCount();
        if (!bot.isUsingItem() && waitTicks > 5) {
            if (bot.getFoodData().getFoodLevel() > startingFoodLevel || currentCount < startingStackCount) {
                itemsConsumed++;
                if (bot.getFoodData().getFoodLevel() >= 20) {
                    complete();
                    return;
                }
                // Keep eating: line up the next bite with a fresh per-item timeout budget.
                phase = Phase.FINDING;
                itemElapsed = 0;
            } else {
                finishOnTimeoutOrFailure("eat_not_consumed");
            }
        }
    }

    /**
     * A stop that isn't "food level reached 20" is only a task failure when nothing was eaten yet.
     * Once at least one bite has already landed, the food already consumed is real, permanent
     * progress (the hunger bar doesn't roll back), so running out of food, hitting a per-bite
     * timeout, or a bite failing to start/land ends the pass as a completion instead of discarding
     * that progress as a failure.
     */
    private void finishOnTimeoutOrFailure(String reason) {
        if (itemsConsumed > 0) {
            complete();
        } else {
            fail(reason);
        }
    }
}
