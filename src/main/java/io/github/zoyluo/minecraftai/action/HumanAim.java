package io.github.zoyluo.minecraftai.action;

import com.mojang.datafixers.util.Either;
import io.github.zoyluo.minecraftai.MinecraftAiConfig;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import java.util.Collection;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The aim of a fighting companion, at human speed (there is no instant spin-and-shoot).
 *
 * <ul>
 *   <li>The head turns at most {@code behaviour.combat.aim.maxTurnDegPerSec} (default 540 degrees per second) while the
 *       bot aims a weapon or strikes: one shared budget per game tick, so several callers in one tick still add up to
 *       the cap. The view (yaw and pitch of the real look vector) is the aim: a shot leaves along it.</li>
 *   <li>A weapon fires only when the tracked aim is within {@link #SHOT_TOLERANCE_DEG} of the shot direction
 *       ({@link #isOnTarget}). After a fast turn the aim carries a human settle jitter that fades away:
 *       sigma = 0.3 deg + 2.5 deg * exp(-tSettled / 0.25 s), where tSettled counts from the last turn that used the
 *       whole per-tick budget.</li>
 *   <li>A melee strike only lands on what is under the crosshair: vanilla's own pick along the real look vector within the
 *       weapon's vanilla {@link AttackRange} ({@link #crosshairEntity}).</li>
 * </ul>
 *
 * <p>Scope: combat aiming only (weapon aim, strikes, the shield reaction). Walking, mining and placing keep turning as they
 * did: their facing is a by-product of the walk, not a flick at an enemy.
 */
public final class HumanAim {
    /** A shot leaves only when the aim is this close (degrees) to the desired shot direction. */
    public static final double SHOT_TOLERANCE_DEG = 1.5D;
    /** The settle jitter's floor and its extra size right after a flick, degrees. */
    public static final double BASE_SIGMA_DEG = 0.3D;
    public static final double FLICK_SIGMA_DEG = 2.5D;
    /** How fast the flick jitter dies away, seconds. */
    public static final double SETTLE_SECONDS = 0.25D;

    private static final Map<AIPlayerEntity, State> STATES = new WeakHashMap<>();

    private HumanAim() {
    }

    /** The numeric core: pure, and free of any Minecraft class. */
    public static final class Core {
        private Core() {
        }

        /** The settle jitter's standard deviation, degrees, {@code secondsSettled} after the last full-budget turn. */
        public static double settleSigma(double secondsSettled) {
            return BASE_SIGMA_DEG + FLICK_SIGMA_DEG * Math.exp(-Math.max(0.0D, secondsSettled) / SETTLE_SECONDS);
        }

        /** {@code degrees} wrapped into [-180, 180). */
        public static double wrap(double degrees) {
            double wrapped = degrees % 360.0D;
            if (wrapped >= 180.0D) {
                wrapped -= 360.0D;
            } else if (wrapped < -180.0D) {
                wrapped += 360.0D;
            }
            return wrapped;
        }

        /**
         * One turn from {@code (yaw, pitch)} toward {@code (wantYaw, wantPitch)}: the combined yaw/pitch move is at most
         * {@code maxStepDeg}. Returns {@code {newYaw, newPitch, saturated}} ({@code saturated} is 1.0 when the whole budget
         * was needed and the target is still not reached). The yaw takes the short way round and keeps its continuity.
         */
        public static double[] turn(double yaw, double pitch, double wantYaw, double wantPitch, double maxStepDeg) {
            double deltaYaw = wrap(wantYaw - yaw);
            double deltaPitch = wantPitch - pitch;
            double distance = Math.hypot(deltaYaw, deltaPitch);
            double budget = Math.max(0.0D, maxStepDeg);
            if (distance <= budget) {
                return new double[]{yaw + deltaYaw, pitch + deltaPitch, 0.0D};
            }
            double scale = budget / distance;
            return new double[]{yaw + deltaYaw * scale, pitch + deltaPitch * scale, 1.0D};
        }

        /** The angle (degrees) between two look directions given as yaw/pitch. */
        public static double angleBetween(double yawA, double pitchA, double yawB, double pitchB) {
            double[] a = direction(yawA, pitchA);
            double[] b = direction(yawB, pitchB);
            double dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
            return Math.toDegrees(Math.acos(Math.max(-1.0D, Math.min(1.0D, dot))));
        }

        private static double[] direction(double yawDeg, double pitchDeg) {
            double yaw = Math.toRadians(yawDeg);
            double pitch = Math.toRadians(pitchDeg);
            double cos = Math.cos(pitch);
            return new double[]{-Math.sin(yaw) * cos, -Math.sin(pitch), Math.cos(yaw) * cos};
        }
    }

    private static final class State {
        long tick = Long.MIN_VALUE;
        /** The game tick of the last aim call, and the aim (degrees, unwrapped yaw) it left: an unbroken session continues from it. */
        long lastAimTick = Long.MIN_VALUE / 2;
        double aimYaw;
        double aimPitch;
        double used;
        double noiseYaw;
        double noisePitch;
        long lastFlickTick = Long.MIN_VALUE / 2;
    }

    /** The configured flick speed in degrees per second ({@code behaviour.combat.aim.maxTurnDegPerSec}). */
    public static double maxTurnDegPerSec() {
        MinecraftAiConfig config = MinecraftAiConfig.get();
        if (config == null || config.behaviour() == null) {
            return MinecraftAiConfig.Aim.DEFAULT_MAX_TURN_DEG_PER_SEC;
        }
        return config.behaviour().combatOrDefaults().aimOrDefaults().maxTurnDegPerSec();
    }

    /** The most the aim may turn in one game tick, degrees. */
    public static double maxTurnDegPerTick() {
        return maxTurnDegPerSec() / 20.0D;
    }

    /** The yaw (degrees) that looks from {@code eye} toward {@code point}. */
    public static float yawTo(Vec3 eye, Vec3 point) {
        return Mth.wrapDegrees((float) (Math.toDegrees(Math.atan2(point.z - eye.z, point.x - eye.x)) - 90.0D));
    }

    /** The pitch (degrees, positive looks down) that looks straight from {@code eye} toward {@code point}. */
    public static float pitchTo(Vec3 eye, Vec3 point) {
        double dx = point.x - eye.x;
        double dz = point.z - eye.z;
        return (float) -Math.toDegrees(Math.atan2(point.y - eye.y, Math.sqrt(dx * dx + dz * dz)));
    }

    /** Turns toward looking at {@code point} in a straight line, at human speed. Returns the angle left to turn (degrees). */
    public static double lookToward(AIPlayerEntity bot, Vec3 point) {
        Vec3 eye = bot.getEyePosition();
        return turnToward(bot, yawTo(eye, point), pitchTo(eye, point));
    }

    /**
     * Turns toward {@code (yaw, pitch)} at human speed: at most the per-tick budget, shared by every caller of the same tick,
     * plus the settle jitter. Returns the angle (degrees) still to go to the exact direction afterwards.
     */
    public static double turnToward(AIPlayerEntity bot, float yaw, float pitch) {
        State state = stateOf(bot);
        long now = bot.level().getGameTime();
        if (state.tick != now) {
            state.tick = now;
            state.used = 0.0D;
            double sigma = Core.settleSigma((now - state.lastFlickTick) / 20.0D);
            state.noiseYaw = bot.getRandom().nextGaussian() * sigma;
            state.noisePitch = bot.getRandom().nextGaussian() * sigma;
        }
        double budget = Math.max(0.0D, maxTurnDegPerTick() - state.used);
        // An unbroken aiming session (the previous tick aimed too) continues from its own aim: whatever else steered the body in
        // between (a walker facing its next waypoint) is the body walking, not the aim, and must not restart the turn every tick.
        boolean continuing = state.lastAimTick >= now - 1L;
        double currentYaw = continuing ? state.aimYaw : bot.getYRot();
        double currentPitch = continuing ? state.aimPitch : bot.getXRot();
        double wantPitch = Mth.clamp(pitch + state.noisePitch, -90.0D, 90.0D);
        double[] turned = Core.turn(currentYaw, currentPitch, yaw + state.noiseYaw, wantPitch, budget);
        double moved = Math.hypot(Core.wrap(turned[0] - currentYaw), turned[1] - currentPitch);
        state.used += moved;
        if (turned[2] > 0.0D) {
            state.lastFlickTick = now;
        }
        state.lastAimTick = now;
        state.aimYaw = turned[0];
        state.aimPitch = turned[1];
        LookAction.setYawPitch(bot, (float) turned[0], (float) turned[1]);
        return Core.angleBetween(bot.getYRot(), bot.getXRot(), yaw, pitch);
    }

    /** The angle (degrees) between the bot's real look direction and {@code (yaw, pitch)}. */
    public static double errorTo(AIPlayerEntity bot, float yaw, float pitch) {
        return Core.angleBetween(bot.getYRot(), bot.getXRot(), yaw, pitch);
    }

    /** True when the tracked aim is within {@link #SHOT_TOLERANCE_DEG} of {@code (yaw, pitch)}. */
    public static boolean isOnTarget(AIPlayerEntity bot, float yaw, float pitch) {
        return errorTo(bot, yaw, pitch) <= SHOT_TOLERANCE_DEG;
    }

    /**
     * The entity under the bot's crosshair: vanilla's pick along the real look vector within the vanilla
     * {@link AttackRange} of the weapon in hand ({@link net.minecraft.world.entity.LivingEntity#entityAttackRange}), the closest
     * hit, with a colliding block in front hiding what is behind it. Null when the crosshair holds no entity.
     *
     * <p>Blocks are clipped by their collision shape, as in {@link StrikeLegality}: a meadow of plants does not take the crosshair.
     */
    public static Entity crosshairEntity(AIPlayerEntity bot) {
        AttackRange range = bot.entityAttackRange();
        Either<BlockHitResult, Collection<EntityHitResult>> along = ProjectileUtil.getHitEntitiesAlong(
                bot, range, entity -> entity != bot && !entity.isSpectator() && entity.isPickable(),
                ClipContext.Block.COLLIDER);
        if (along.right().isEmpty()) {
            return null;
        }
        Vec3 eye = bot.getEyePosition();
        Entity closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (EntityHitResult hit : along.right().get()) {
            double distance = eye.distanceToSqr(hit.getLocation());
            if (distance < closestDistance) {
                closestDistance = distance;
                closest = hit.getEntity();
            }
        }
        return closest;
    }

    /** True when {@code target} is the entity under the bot's crosshair right now. */
    public static boolean isUnderCrosshair(AIPlayerEntity bot, Entity target) {
        return crosshairEntity(bot) == target;
    }

    private static State stateOf(AIPlayerEntity bot) {
        synchronized (STATES) {
            return STATES.computeIfAbsent(bot, ignored -> new State());
        }
    }
}
