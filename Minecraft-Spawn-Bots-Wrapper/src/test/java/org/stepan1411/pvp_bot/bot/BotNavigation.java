package org.stepan1411.pvp_bot.bot;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.stepan1411.testdouble.Recorder;

import java.util.ArrayList;
import java.util.List;

/**
 * Test-only fake of the upstream navigation helper: the per-bot state removal and the two movement calls the
 * aggro walk back makes (recorded, never executed).
 */
public class BotNavigation {

    /** Test observation: every look / move call, in order, as "look x,y,z" / "move x,y,z@speed". */
    public static final List<String> CALLS = new ArrayList<>();

    public static void removeState(String botName) {
        Recorder.guard("removeState", botName);
    }

    public static void lookAtPosition(ServerPlayer bot, Vec3 target) {
        Recorder.guard("lookAtPosition", "");
        CALLS.add("look " + target.x + "," + target.y + "," + target.z);
    }

    public static void moveTowardPosition(ServerPlayer bot, Vec3 target, double speed) {
        Recorder.guard("moveTowardPosition", "");
        CALLS.add("move " + target.x + "," + target.y + "," + target.z + "@" + speed);
    }
}
