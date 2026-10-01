package io.github.zoyluo.minecraftai.task;

/**
 * Pure oxygen arithmetic for a follower that dives after a swimming player.  Nothing here touches
 * the world, so every rule can be unit-tested:
 *
 * <ul>
 *   <li>{@link LossEstimator} measures how fast the bot's own air is really falling (an EMA of the
 *       per-tick air deltas while its head is under water).  Water Breathing / Conduit Power /
 *       a turtle helmet measure as ~0, Respiration as a slower, chance-based loss -- nothing about
 *       those effects is hard-coded.</li>
 *   <li>{@link #shouldSurface} compares the air that is left, in ticks, with how long the way back
 *       up takes (distance to breathable air over a deliberately slow ascent speed, times a safety
 *       factor, plus a fixed margin) and with a baseline shallow-water air floor. FollowSwimming
 *       separately yields at NavSafetyNet's depth-aware boundary, so a deep column cannot make
 *       the two writers fight over a swim step.</li>
 *   <li>{@link #mayResumeDive} gives the decision hysteresis: once follow has turned up for
 *       breath it stays up until the lungs are (almost) full again, instead of flapping around the
 *       trigger level.</li>
 * </ul>
 */
final class FollowOxygen {
    /** Baseline shallow-water rescue floor; deep columns use NavSafetyNet's depth-aware boundary. */
    static final int RESCUE_AIR = NavSafetyNet.AIR_SURFACE_THRESHOLD;
    /**
     * Follow always turns up at least this early, so it is never the rescue that has to. A bot swims by real inputs now (about
     * 0.11 blocks per tick up, measured by NaturalSwimGameTests.legacyInputsSwimAndSurface), so a way up from a few blocks down takes
     * a few seconds: 80 units of air (four seconds) above the rescue level.
     */
    static final int SURFACE_FLOOR_AIR = RESCUE_AIR + 80;
    /** A little slower than the measured real swim-up speed (about 0.11 blocks per tick), so the ascent estimate errs on the safe side. */
    static final double ASCENT_BLOCKS_PER_TICK = 0.1D;
    static final double SAFETY_FACTOR = 1.5D;
    static final int MARGIN_TICKS = 40;
    /** Fraction of full air that ends a "went up for breath" phase. */
    static final double RESUME_FRACTION = 0.9D;
    /** A breathing effect with at least this long left is treated as "no air loss". */
    static final int LONG_EFFECT_TICKS = 400;

    private FollowOxygen() {
    }

    /**
     * @param air              the bot's current air
     * @param lossPerTick      measured (or effect-derived) air loss per tick while submerged
     * @param blocksToAir      distance to breathable air along the way the bot would actually
     *                         ascend; {@link Double#POSITIVE_INFINITY} when no way up is known
     * @param ascentBlocksPerTick conservative ascent speed
     * @param marginTicks      fixed extra time, in ticks
     * @param floorAir         air level that always forces the ascent (only while air is being lost)
     * @return true when the bot must stop going down and head for air now
     */
    static boolean shouldSurface(int air, double lossPerTick, double blocksToAir,
                                 double ascentBlocksPerTick, int marginTicks, int floorAir) {
        if (!(lossPerTick > 0.0D)) {
            // Air is not falling (Water Breathing, Conduit Power, ...): never forced up.
            return false;
        }
        if (air <= floorAir) {
            return true;
        }
        double ticksLeft = air / lossPerTick;
        double ticksToBreathe = blocksToAir <= 0.0D ? 0.0D : blocksToAir / ascentBlocksPerTick;
        return ticksLeft <= ticksToBreathe * SAFETY_FACTOR + marginTicks;
    }

    /** {@link #shouldSurface} with the production constants. */
    static boolean shouldSurface(int air, double lossPerTick, double blocksToAir) {
        return shouldSurface(air, lossPerTick, blocksToAir, ASCENT_BLOCKS_PER_TICK, MARGIN_TICKS, SURFACE_FLOOR_AIR);
    }

    /** True once a bot that went up for breath may go back down. */
    static boolean mayResumeDive(int air, int maxAir, double lossPerTick) {
        if (!(lossPerTick > 0.0D)) {
            return true;
        }
        return air >= maxAir * RESUME_FRACTION;
    }

    /** True for a breathing effect that will comfortably outlast an ascent. */
    static boolean effectCoversDive(boolean infinite, int durationTicks) {
        return infinite || durationTicks >= LONG_EFFECT_TICKS;
    }

    /**
     * Exponential moving average of the per-tick air loss while the head is under water.  Starts
     * pessimistic (one full air unit per tick, the vanilla rate without protection) and converges
     * to whatever the bot actually experiences.
     */
    static final class LossEstimator {
        private static final double ALPHA = 0.08D;
        private static final double PESSIMISTIC_START = 1.0D;
        /** After this many confirmed no-loss ticks, a tiny EMA residue is reported as exactly zero. */
        private static final int ZERO_CONFIRM_SAMPLES = 100;
        private static final double ZERO_EPSILON = 0.01D;
        private static final int NO_AIR = Integer.MIN_VALUE;

        private int lastAir = NO_AIR;
        private int lastTick = Integer.MIN_VALUE;
        private double ema = PESSIMISTIC_START;
        private int samples;

        void reset() {
            lastAir = NO_AIR;
            lastTick = Integer.MIN_VALUE;
            ema = PESSIMISTIC_START;
            samples = 0;
        }

        /**
         * @param air        current air
         * @param submerged  whether the bot's head is under water right now
         * @param serverTick monotonically increasing tick counter (gaps are tolerated)
         */
        void observe(int air, boolean submerged, int serverTick) {
            if (!submerged) {
                reset();
                return;
            }
            if (lastAir != NO_AIR && serverTick > lastTick) {
                int elapsed = serverTick - lastTick;
                if (elapsed > 40) {
                    // The task did not tick for a while (server lag / pause): the delta says
                    // nothing reliable about the current rate.
                    samples = 0;
                } else {
                    double perTick = Math.max(0, lastAir - air) / (double) elapsed;
                    ema += (1.0D - Math.pow(1.0D - ALPHA, elapsed)) * (perTick - ema);
                    ema = Math.max(0.0D, Math.min(PESSIMISTIC_START, ema));
                    samples += elapsed;
                }
            }
            lastAir = air;
            lastTick = serverTick;
        }

        double lossPerTick() {
            if (samples >= ZERO_CONFIRM_SAMPLES && ema < ZERO_EPSILON) {
                return 0.0D;
            }
            return ema;
        }

        int samples() {
            return samples;
        }
    }
}
