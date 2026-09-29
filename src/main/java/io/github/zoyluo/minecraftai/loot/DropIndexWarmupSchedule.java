package io.github.zoyluo.minecraftai.loot;

/**
 * The pure scheduling decision of {@link RuntimeDropIndex}'s warm-up build (no Minecraft types, so it is unit
 * testable). The build costs 200-650 ms, so it prefers an idle moment (no running bot task). A bot that
 * always holds a task (following a player all session) would otherwise postpone it forever and leave the
 * hitch to land inside the first gather request, so the wait is bounded: once the delay has elapsed and
 * {@link #MAX_IDLE_WAIT_TICKS} more ticks passed without an idle moment, it is built at the first tick the
 * server is not under load.
 */
final class DropIndexWarmupSchedule {
    /** How long (ticks, 30 s at 20 tps) the build waits for an idle moment before it settles for a low-load tick. */
    static final int MAX_IDLE_WAIT_TICKS = 600;

    private DropIndexWarmupSchedule() {
    }

    /**
     * @param idle            no bot task is running
     * @param lowLoad         the server is not degraded / under load this tick
     * @param waitedTicks     ticks spent waiting since the initial delay elapsed (0 on the first eligible tick)
     * @return true when the index should be built now
     */
    static boolean buildNow(boolean idle, boolean lowLoad, int waitedTicks) {
        if (idle) {
            return true;
        }
        return waitedTicks >= MAX_IDLE_WAIT_TICKS && lowLoad;
    }
}
