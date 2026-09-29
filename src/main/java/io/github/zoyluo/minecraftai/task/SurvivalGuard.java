package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;

/**
 * Unified survival layer (Phase 2 V1): survival circuit-breaking is consolidated here from individual tasks, executed indiscriminately before every task tick.
 *
 * Historical root cause: MoveTask had a drowning circuit-breaker, OreDigTask did not -- the bot drowned while mining on a lake bed, air 278->0,
 * with the task showing no reaction the entire time (observed in geo_lake testing). This cross-cutting concern was scattered across tasks, forcing a rethink on every new task, and missing just one spot cost a life.
 *
 * Division of responsibilities (three orthogonal layers):
 *  - DangerWatcher: threat response (combat/flee/respawn/dark-area retreat) -- handles "external threats".
 *  - NavSafetyNet: position correction (suffocation snap / surfacing in water) -- "self-rescue actions".
 *  - SurvivalGuard (this layer): task-terminating circuit-breaking -- "halting the work". Only once the task stops can the self-rescue of the two layers above
 *    avoid being overridden every tick by the work action (observed livelock: the safety net surfaces for one tick, then mining drags the bot back into the water the next tick).
 *
 * This layer's role is a **last-resort backstop**: a task's own domain-specific circuit-breaking (e.g. OreDigTask blacklisting the underwater ore in passing when drowning, or MoveTask switching
 * to a relay detour on contact with water) can act earlier and smarter than this layer -- but no task may do worse than this layer.
 */
public final class SurvivalGuard {
    public static final SurvivalGuard INSTANCE = new SurvivalGuard();

    private SurvivalGuard() {
    }

    /**
     * Circuit-breaker check: returns non-null = circuit-break reason; the caller (TaskManager) terminates the task with that reason.
     * Self-rescue-type tasks are exempt -- they are themselves the response to danger, so interrupting them means interrupting the self-rescue.
     */
    public String check(AIPlayerEntity bot, Task task) {
        if (task instanceof EvadeTask || task instanceof CreeperDefenseTask
                || task instanceof CombatTask
                || task instanceof EmergencyShelterTask || task instanceof MiningBarricadeTask
                || task instanceof EatTask
                || task instanceof LavaEscapeTask
                || task instanceof FireExtinguishTask
                || task instanceof PowderSnowEscapeTask) {
            return null; // the lava/fire/powder-snow self-rescue tasks are themselves the response, and must never be interrupted by guard_in_lava / guard_on_fire in turn
        }
        // Note: RecoverDropsTask is deliberately **not** exempt -- cutting it off when air is critical during an underwater corpse run is correct:
        // exempting it = the task continues = drowning and dropping a whole second set of gear; accepting the item loss to save the bot's life is the only correct call (this was suggested for exemption during review; do not change it).
        // (1) Drowning: head submerged in water and air is down to only 5 seconds left -- no matter how urgent the work is, staying alive to breathe comes first.
        if (bot.isUnderWater() && bot.getAirSupply() < 100) {
            // OreDigTask has its own drowning circuit-breaker (line 169) and will [exclude this underwater ore block] -- defer to it; this layer does not preempt with an interrupt.
            // Otherwise this layer would interrupt without excluding the ore -> the bot surfaces (NavSafetyNet) and then re-locks onto the same underwater ore -> repeated guard_drowning
            // infinite loop (observed 2026-06-10 near a cliffside body of water: mining iron while diving, navsafe_surface fired 32 times and was still repeatedly interrupted -> goal failed).
            if (task instanceof OreDigTask) {
                return null;
            }
            return "guard_drowning";
        }
        // (2) Stuck in lava: burning every tick, any work stops immediately, yielding to DangerWatcher, which dispatches LavaEscapeTask (and FireExtinguishTask once out of the lava).
        if (bot.isInLava()) {
            return "guard_in_lava";
        }
        // (3) On fire and past half health lost: the fire source may be right next to the work target (mining next to lava), so continuing work = staying pinned in the fire. DangerWatcher dispatches FireExtinguishTask (punch out the fire block, water bucket at the feet, or observed water/rain) when the bot has any means to put itself out.
        if (bot.isOnFire() && bot.getHealth() < 10.0F) {
            return "guard_on_fire";
        }
        // (4) Dying under attack: hp <= 3 hearts and currently being hit -- sticking with the work is suicide, so DangerWatcher takes over (flee / last-stand counterattack).
        if (bot.getHealth() <= 6.0F && bot.hurtTime > 0) {
            return "guard_low_hp_under_attack";
        }
        return null;
    }
}
