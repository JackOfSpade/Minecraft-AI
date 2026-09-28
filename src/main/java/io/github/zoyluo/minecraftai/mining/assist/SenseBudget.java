package io.github.zoyluo.minecraftai.mining.assist;

/**
 * Counter-based ray throttle of the sensor (mining-assist design 3.4). Pure integer arithmetic:
 * {@code raysEff = min(cfg.raysPerTick, floor(globalRaysPerTick / activeSweepingBots))}, halved while
 * the tick-headroom latch is set, never below one ray (there is no permanent per-bot disable).
 */
public final class SenseBudget {
    /** Design 7 compile-time constant: rays per tick while a breakthrough sweep runs. */
    public static final int BREAKTHROUGH_RAYS_PER_TICK = 96;
    /** Design 7 compile-time constant: every second slice ray gets an OUTLINE re-cast. */
    public static final int DECOR_STRIDE = 2;
    /**
     * Longest ray of the sensor in blocks. The perception radius is a config value with no upper bound of its own
     * (the default is 16); the ray budget is counted in rays, not blocks, so a configured radius of 64 would make
     * every ray four times as expensive with nothing to compensate. Rays stay honest at any shorter length.
     */
    public static final int MAX_SWEEP_RADIUS = 24;

    private SenseBudget() {
    }

    /**
     * The radius the sensor casts and normalises with: the live perception radius, at least 1 and at most
     * {@link #MAX_SWEEP_RADIUS}. One source for the sweep, the POI pass and the log lines, so the ring's free
     * lengths and the openness they are divided by always use the same number.
     */
    public static double sweepRadius(int perceptionRadius) {
        return Math.min(MAX_SWEEP_RADIUS, Math.max(1, perceptionRadius));
    }

    /**
     * Effective COLLIDER rays for one bot this tick.
     *
     * @param targetRaysPerTick the wanted rate ({@link #target}): the configured rate, or the
     *                          breakthrough rate
     * @param globalRaysPerTick {@code sense.globalRaysPerTick}, shared across sweeping bots
     * @param activeSweepingBots bots sweeping right now, counting this one (values below 1 count as 1)
     * @param halve             true while {@code TickHeadroom.halveRays()} holds and throttling is adaptive
     */
    public static int raysEff(int targetRaysPerTick, int globalRaysPerTick, int activeSweepingBots, boolean halve) {
        int target = Math.max(1, targetRaysPerTick);
        int share = Math.max(1, globalRaysPerTick) / Math.max(1, activeSweepingBots);
        int eff = Math.min(target, share);
        if (halve) {
            eff /= 2;
        }
        return Math.max(1, eff);
    }

    /**
     * The wanted rate before the global share and the headroom halving apply: the configured rate, or
     * at least {@link #BREAKTHROUGH_RAYS_PER_TICK} during a breakthrough sweep.
     */
    public static int target(boolean breakthroughActive, int cfgRaysPerTick) {
        int cfg = Math.max(1, cfgRaysPerTick);
        return breakthroughActive ? Math.max(cfg, BREAKTHROUGH_RAYS_PER_TICK) : cfg;
    }

    /** Ticks one full lattice sweep takes at {@code raysPerTick} (for logs and documentation). */
    public static int sweepTicks(int raysPerTick) {
        return (SphereSchedule.LATTICE_SIZE + Math.max(1, raysPerTick) - 1) / Math.max(1, raysPerTick);
    }

    /** True when visit index {@code k} of a sweep also gets an OUTLINE decor re-cast. */
    public static boolean decorRay(int visitIndex) {
        return visitIndex % DECOR_STRIDE == 0;
    }
}
