package org.stepan1411.pvp_bot.bot;

import net.minecraft.world.entity.Entity;
import org.stepan1411.testdouble.Recorder;

import java.util.HashMap;
import java.util.Map;

/**
 * Test-only fake of the upstream combat class: the per-bot combat state with its public forced-target field
 * and the three target calls the aggro range makes. Mirrors upstream's behaviour where it matters:
 * {@code getState} creates the state on first access, {@code clearTarget} resets more than the forced name and
 * {@code setTarget(bot, null)} resets nothing else.
 */
public class BotCombat {

    private static final Map<String, CombatState> STATES = new HashMap<>();

    public static CombatState getState(String botName) {
        Recorder.guard("getState", botName);
        return STATES.computeIfAbsent(botName, k -> new CombatState());
    }

    public static void setTarget(String botName, String targetName) {
        Recorder.guard("setTarget", botName + "->" + targetName);
        getState(botName).forcedTargetName = targetName;
    }

    public static void clearTarget(String botName) {
        Recorder.guard("clearTarget", botName);
        CombatState s = getState(botName);
        s.forcedTargetName = null;
        s.target = null;
        s.lastAttacker = null;
        s.lastAttackReset = true;
    }

    public static Entity getTarget(String botName) {
        Recorder.guard("getTarget", botName);
        return getState(botName).target;
    }

    /** Test control (not part of upstream). */
    public static void resetAll() {
        STATES.clear();
    }

    public static class CombatState {
        public Entity target = null;
        public String forcedTargetName = null;
        public Entity lastAttacker = null;
        public boolean lastAttackReset;
        public boolean isRetreating = false;
    }
}
