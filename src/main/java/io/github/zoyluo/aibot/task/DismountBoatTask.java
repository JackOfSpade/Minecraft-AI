package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.BoatAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.entity.vehicle.AbstractBoatEntity;

/** Stops the current boat and asks vanilla to perform its ordinary passenger dismount. */
public final class DismountBoatTask extends AbstractTask {
    private static final int MAX_TICKS = 40;

    @Override
    public String name() {
        return "exit_boat";
    }

    @Override
    public String describe() {
        return "Exiting boat";
    }

    @Override
    public double progress() {
        return state == TaskState.COMPLETED ? 1.0D : 0.5D;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (!(bot.getVehicle() instanceof AbstractBoatEntity boat)) {
            complete();
            return;
        }
        BoatAction.stopBoat(boat);
        bot.dismountVehicle();
        if (!(bot.getVehicle() instanceof AbstractBoatEntity)) {
            complete();
            return;
        }
        if (elapsed >= MAX_TICKS) {
            fail("boat_dismount_not_confirmed");
        }
    }
}
