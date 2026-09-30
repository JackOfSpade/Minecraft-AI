package dev.spawnbotswrapper.inhabitants.combat;

/**
 * Human aim: how fast an inhabitant's head can turn, how steady its hand is right after a turn, and how close to the
 * target it has to be pointing before it shoots. The pure math, free of Minecraft types so it is unit-testable; the glue
 * that reads and writes the entity's rotation is {@code mc.HumanAimDriver}.
 * <p>
 * <b>Why.</b> PvP BOT snaps an inhabitant's rotation straight onto its target every tick ({@code lookAtTarget} and
 * {@code lookAtTargetWithPrediction} set yaw, pitch and head yaw directly). That is instant and perfect: shot in the back,
 * a bot would spin 180 degrees in one tick and shoot with no delay. A person's aim is neither. These are human limits, not
 * handicaps invented for gameplay: they model a hand and an eye.
 * <ul>
 *   <li><b>Turn speed.</b> The tracked aim rotates toward the direction PvP BOT wants at most {@code maxTurnDegPerSec}
 *       (default 540 deg/s, a fast flick of a mouse: 27 deg per tick; a half turn takes 0.33 s), always the shorter way
 *       round. Yaw and pitch move together along a straight line in (yaw, pitch) space, so the COMBINED step is what is
 *       limited.</li>
 *   <li><b>Tolerance.</b> A shot is allowed once the tracked aim is within a tolerance of the wanted direction: at most
 *       {@code fireToleranceDeg} (1.5 deg) and, farther out, the angle the target's hit radius subtends
 *       ({@code atan(fireTargetRadius / distance)}, radius 0.25 blocks: the half width of a player's head and torso), so a
 *       shot released within tolerance would actually hit what it is aimed at. At 10 blocks that is 1.4 deg, at 40 blocks
 *       0.36 deg.</li>
 *   <li><b>Steadiness.</b> Right after a fast turn a hand shakes. Aim jitter has a standard deviation of
 *       {@code jitterBaseDeg + jitterSettleDeg * exp(-tSettled / jitterSettleSeconds)} (0.3 deg + 2.5 deg * exp(-t / 0.25 s)),
 *       {@code tSettled} being the time since the aim first came within tolerance. It is applied by firing along the
 *       jittered direction. A bot that has just finished a flick is therefore inaccurate for a quarter second, and then
 *       nearly as steady as the base jitter.</li>
 * </ul>
 * Directions are vanilla's: yaw 0 looks along +z, yaw -90 along +x, pitch positive looks down.
 */
public final class HumanAim {
    private HumanAim() {
    }

    /**
     * The tunables (the {@code aggro.aim} block of the wrapper's config).
     *
     * @param enabled            master switch; off = PvP BOT's rotation is left alone (instant and perfect)
     * @param maxTurnDegPerSec   fastest turn, degrees per second
     * @param fireToleranceDeg   the widest aim error at which a shot is still allowed, degrees
     * @param fireTargetRadius   blocks: the target's hit radius, which narrows the tolerance with distance
     * @param jitterBaseDeg      steady-state aim jitter (standard deviation), degrees
     * @param jitterSettleDeg    extra jitter right after the aim first came on target, degrees
     * @param jitterSettleSeconds how fast that extra jitter fades (time constant), seconds
     */
    public record Params(boolean enabled, double maxTurnDegPerSec, double fireToleranceDeg, double fireTargetRadius,
                         double jitterBaseDeg, double jitterSettleDeg, double jitterSettleSeconds) {
        public static Params defaults() {
            return new Params(true, 540.0, 1.5, 0.25, 0.3, 2.5, 0.25);
        }

        /** The largest change of the aim in one server tick, degrees. */
        public double maxStepDegPerTick() {
            return maxTurnDegPerSec * Perception.TICK_SECONDS;
        }

        public Params withEnabled(boolean on) {
            return new Params(on, maxTurnDegPerSec, fireToleranceDeg, fireTargetRadius, jitterBaseDeg, jitterSettleDeg,
                    jitterSettleSeconds);
        }
    }

    /** A yaw/pitch pair in degrees. */
    public record Angles(double yaw, double pitch) {
    }

    /** Degrees wrapped to [-180, 180). */
    public static double wrapDegrees(double degrees) {
        double d = degrees % 360.0;
        if (d >= 180.0) {
            d -= 360.0;
        }
        if (d < -180.0) {
            d += 360.0;
        }
        return d;
    }

    /** The unit look vector {x, y, z} of a yaw and pitch, exactly vanilla's {@code Entity.calculateViewVector}. */
    public static double[] direction(double yawDeg, double pitchDeg) {
        double pitch = Math.toRadians(pitchDeg);
        double yaw = Math.toRadians(-yawDeg);
        double cy = Math.cos(yaw);
        double sy = Math.sin(yaw);
        double cp = Math.cos(pitch);
        double sp = Math.sin(pitch);
        return new double[]{sy * cp, -sp, cy * cp};
    }

    /** The angle in degrees between two directions given as yaw and pitch. */
    public static double angleBetween(double yaw1, double pitch1, double yaw2, double pitch2) {
        double[] a = direction(yaw1, pitch1);
        double[] b = direction(yaw2, pitch2);
        double dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
    }

    /** The yaw and pitch (degrees) that look along the vector {@code (x, y, z)} (any length, not zero). */
    public static Angles anglesOf(double x, double y, double z) {
        double horizontal = Math.sqrt(x * x + z * z);
        double yaw = Math.toDegrees(Math.atan2(-x, z));
        double pitch = Math.toDegrees(-Math.atan2(y, horizontal));
        return new Angles(yaw, pitch);
    }

    /**
     * One turn step: the aim moves from {@code (yaw, pitch)} toward {@code (toYaw, toPitch)} by at most {@code maxStep} degrees
     * (the yaw the SHORTER way round, wrapping at +-180). The result keeps the yaw continuous with the one passed in (it is not
     * wrapped), and the pitch is kept within +-90.
     */
    public static Angles turn(double yaw, double pitch, double toYaw, double toPitch, double maxStep) {
        double dy = wrapDegrees(toYaw - yaw);
        double dp = toPitch - pitch;
        double dist = Math.hypot(dy, dp);
        double nyaw;
        double npitch;
        if (dist <= maxStep || dist < 1.0e-9) {
            nyaw = yaw + dy;
            npitch = pitch + dp;
        } else {
            double k = maxStep / dist;
            nyaw = yaw + dy * k;
            npitch = pitch + dp * k;
        }
        return new Angles(nyaw, Math.max(-90.0, Math.min(90.0, npitch)));
    }

    /**
     * The aim error (degrees) at which a shot at a target {@code distance} blocks away is still allowed: the base tolerance,
     * or the angle the target's hit radius subtends when that is smaller. Never below 0.05 degrees.
     */
    public static double toleranceDeg(Params p, double distance) {
        double d = Math.max(distance, 0.5);
        double subtended = Math.toDegrees(Math.atan(p.fireTargetRadius() / d));
        return Math.max(0.05, Math.min(p.fireToleranceDeg(), subtended));
    }

    /** The standard deviation (degrees) of the aim jitter {@code secondsSettled} after the aim first came on target. */
    public static double jitterSigmaDeg(Params p, double secondsSettled) {
        double t = Math.max(0.0, secondsSettled);
        double settle = p.jitterSettleSeconds() <= 0.0 ? 0.0 : Math.exp(-t / p.jitterSettleSeconds());
        return p.jitterBaseDeg() + p.jitterSettleDeg() * settle;
    }

    /**
     * The direction a shot leaves along: the tracked aim plus a jitter of standard deviation {@code sigmaDeg} on each axis.
     * {@code gaussianYaw} and {@code gaussianPitch} are standard normal samples (the caller supplies them, so the math stays
     * deterministic under test).
     */
    public static Angles jittered(double yaw, double pitch, double sigmaDeg, double gaussianYaw, double gaussianPitch) {
        return new Angles(yaw + gaussianYaw * sigmaDeg, Math.max(-90.0, Math.min(90.0, pitch + gaussianPitch * sigmaDeg)));
    }

    /**
     * Where a re-aimed projectile goes: the speed of the launch that was made (the velocity minus the shooter's own
     * movement that vanilla adds), which the re-aim keeps.
     *
     * @return the magnitude of {@code (vx - mx, vy - my, vz - mz)}
     */
    public static double launchSpeed(double vx, double vy, double vz, double mx, double my, double mz) {
        return Math.sqrt((vx - mx) * (vx - mx) + (vy - my) * (vy - my) + (vz - mz) * (vz - mz));
    }

    /**
     * The tracked aim of one inhabitant. Each server tick, after PvP BOT's own tick, {@link #advance} is given the rotation
     * the entity has then: if it differs from what was written last tick it is the direction PvP BOT (or the hunt's steering)
     * WANTS now; the tracked aim turns toward the wanted direction at the limited speed and the caller writes it back into the
     * entity. When nobody set a new direction the bot goes on turning toward the last one until it is reached.
     */
    public static final class State {
        private static final double SAME = 1.0e-4;
        private boolean started;
        private double yaw;
        private double pitch;
        private double wantedYaw;
        private double wantedPitch;
        private double writtenYaw;
        private double writtenPitch;
        private long settledSince = -1;
        private double error;
        private long wantedTick = Long.MIN_VALUE;
        private long lastTick = Long.MIN_VALUE;

        /** True once the state has seen a rotation. */
        public boolean started() {
            return started;
        }

        /** Tracked aim yaw, degrees (continuous, not wrapped). */
        public double yaw() {
            return yaw;
        }

        /** Tracked aim pitch, degrees. */
        public double pitch() {
            return pitch;
        }

        /** The yaw PvP BOT wants, degrees. */
        public double wantedYaw() {
            return wantedYaw;
        }

        /** The pitch PvP BOT wants, degrees. */
        public double wantedPitch() {
            return wantedPitch;
        }

        /** The angle between the tracked aim and the wanted direction, degrees, as of the last {@link #advance}. */
        public double errorDeg() {
            return error;
        }

        /** The tick of the last {@link #advance}. */
        public long lastTick() {
            return lastTick;
        }

        /** The tick a new wanted direction was last seen. */
        public long wantedTick() {
            return wantedTick;
        }

        /** Whether the aim is currently within {@code toleranceDeg} of the wanted direction. */
        public boolean onTarget(double toleranceDeg) {
            return started && error <= toleranceDeg;
        }

        /** Seconds since the aim first came within tolerance and stayed there (0 when it is not settled). */
        public double settledSeconds(long tick) {
            return settledSince < 0 ? 0.0 : Math.max(0, tick - settledSince) * Perception.TICK_SECONDS;
        }

        /** Pins the aim to a rotation at once (a spawn, a teleport, a test's fixture): no turning, no jitter memory of a turn. */
        public void snapTo(double newYaw, double newPitch, long tick) {
            started = true;
            yaw = newYaw;
            pitch = newPitch;
            wantedYaw = newYaw;
            wantedPitch = newPitch;
            writtenYaw = (float) newYaw;
            writtenPitch = (float) newPitch;
            error = 0.0;
            settledSince = tick;
            lastTick = tick;
            wantedTick = tick;
        }

        /**
         * One tick. {@code entityYaw} and {@code entityPitch} are the entity's rotation now; {@code baseToleranceDeg} is the
         * tolerance that starts and ends the settle timer.
         *
         * @return the aim to write back into the entity
         */
        public Angles advance(Params p, long tick, double entityYaw, double entityPitch, double baseToleranceDeg) {
            if (!started) {
                snapTo(entityYaw, entityPitch, tick);
                return new Angles(yaw, pitch);
            }
            lastTick = tick;
            if (Math.abs(wrapDegrees(entityYaw - writtenYaw)) > SAME || Math.abs(entityPitch - writtenPitch) > SAME) {
                // Somebody set a rotation this tick: that is what PvP BOT (or the hunt) wants the bot to look at.
                wantedYaw = entityYaw;
                wantedPitch = entityPitch;
                wantedTick = tick;
            }
            Angles next = turn(yaw, pitch, wantedYaw, wantedPitch, p.maxStepDegPerTick());
            yaw = wrapDegrees(next.yaw());
            pitch = next.pitch();
            writtenYaw = (float) yaw;
            writtenPitch = (float) pitch;
            error = angleBetween(yaw, pitch, wantedYaw, wantedPitch);
            if (error <= baseToleranceDeg) {
                if (settledSince < 0) {
                    settledSince = tick;
                }
            } else if (error > 2.0 * baseToleranceDeg) {
                settledSince = -1;
            }
            return new Angles(yaw, pitch);
        }
    }
}
