package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.EatAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;

public final class EatTask extends AbstractTask {
    private enum Phase {
        FINDING,
        STARTING,
        WAITING
    }

    private Phase phase = Phase.FINDING;
    private int startingFoodLevel;
    private int startingStackCount;
    private int waitTicks;

    @Override
    public String name() {
        return "eat";
    }

    @Override
    public String describe() {
        return "Eating phase=" + phase + " starting_food=" + startingFoodLevel;
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
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (bot.getHungerManager().getFoodLevel() >= 20) {
            complete();
            return;
        }
        if (elapsed > 160) {
            fail("eat_timeout");
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
            fail("no_food");
            return;
        }
        startingFoodLevel = bot.getHungerManager().getFoodLevel();
        phase = Phase.STARTING;
    }

    private void startEating(AIPlayerEntity bot) {
        ActionResult result = EatAction.startEating(bot);
        if (result.isFailed()) {
            fail(result.reason());
            return;
        }
        ItemStack stack = bot.getStackInHand(Hand.MAIN_HAND);
        startingStackCount = stack.isEmpty() ? 0 : stack.getCount();
        waitTicks = 0;
        phase = Phase.WAITING;
    }

    private void waitForFinish(AIPlayerEntity bot) {
        waitTicks++;
        ItemStack stack = bot.getStackInHand(Hand.MAIN_HAND);
        int currentCount = stack.isEmpty() ? 0 : stack.getCount();
        if (!bot.isUsingItem() && waitTicks > 5) {
            if (bot.getHungerManager().getFoodLevel() > startingFoodLevel || currentCount < startingStackCount) {
                complete();
            } else {
                fail("eat_not_consumed");
            }
        }
    }
}
