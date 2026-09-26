package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.mode.ObservableWorldQuery;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Optional;

/**
 * Detects arrows/tridents already in flight on a course that will pass close to the bot, so combat
 * can react by turning to face the threat and raising a shield instead of only responding to the
 * mob that fired it. There is deliberately no reaction to projectiles the bot itself just fired.
 */
public final class ProjectileThreat {
    private static final double SCAN_RANGE = 24.0D;
    /** Generous body/shield interception radius; vanilla hit detection is a similar-order box. */
    private static final double INTERCEPT_RADIUS = 1.25D;
    private static final double INTERCEPT_RADIUS_SQUARED = INTERCEPT_RADIUS * INTERCEPT_RADIUS;
    /** 1.5s of lead time: enough to snap-turn and raise a shield before the projectile arrives. */
    private static final double LEAD_TICKS = 30.0D;

    private ProjectileThreat() {
    }

    public record Incoming(PersistentProjectileEntity projectile, double ticksToClosestApproach) {
    }

    public static Optional<Incoming> mostImminent(AIPlayerEntity bot) {
        List<PersistentProjectileEntity> candidates = bot.getServerWorld().getEntitiesByClass(
                PersistentProjectileEntity.class,
                bot.getBoundingBox().expand(SCAN_RANGE),
                projectile -> projectile.isAlive()
                        && notOwnedByBot(bot, projectile)
                        && ObservableWorldQuery.canObserveEntity(bot, projectile));
        Incoming best = null;
        for (PersistentProjectileEntity projectile : candidates) {
            Double ticks = ticksToClosestApproach(
                    projectile.getPos().subtract(bot.getEyePos()), projectile.getVelocity());
            if (ticks == null) {
                continue;
            }
            if (best == null || ticks < best.ticksToClosestApproach()) {
                best = new Incoming(projectile, ticks);
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Pure trajectory math: ticks until the projectile's closest approach to the bot's eyes, or
     * {@code null} when it is not on an imminent intercepting course (moving away, too slow to be
     * a real projectile, further out than {@link #LEAD_TICKS}, or would miss outside {@link
     * #INTERCEPT_RADIUS}). Isolated from world/entity state so it is directly unit-testable.
     */
    static Double ticksToClosestApproach(Vec3d relativePosition, Vec3d velocityPerTick) {
        double speedSquared = velocityPerTick.lengthSquared();
        if (speedSquared < 1.0E-6D) {
            return null;
        }
        double t = -relativePosition.dotProduct(velocityPerTick) / speedSquared;
        if (t < 0.0D || t > LEAD_TICKS) {
            return null;
        }
        Vec3d closestOffset = relativePosition.add(velocityPerTick.multiply(t));
        if (closestOffset.lengthSquared() > INTERCEPT_RADIUS_SQUARED) {
            return null;
        }
        return t;
    }

    private static boolean notOwnedByBot(AIPlayerEntity bot, PersistentProjectileEntity projectile) {
        Entity owner = projectile.getOwner();
        return owner != bot;
    }
}
