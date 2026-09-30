package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.perception.CreatureSenses;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.Vec3;

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

    public record Incoming(AbstractArrow projectile, double ticksToClosestApproach) {
    }

    public static Optional<Incoming> mostImminent(AIPlayerEntity bot) {
        List<AbstractArrow> candidates = bot.level().getEntitiesOfClass(
                AbstractArrow.class,
                bot.getBoundingBox().inflate(SCAN_RANGE),
                projectile -> projectile.isAlive()
                        && notOwnedByBot(bot, projectile)
                        && CreatureSenses.INSTANCE.noticedProjectile(bot, projectile));
        Incoming best = null;
        for (AbstractArrow projectile : candidates) {
            Double ticks = ticksToClosestApproach(
                    projectile.position().subtract(bot.getEyePosition()), projectile.getDeltaMovement());
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
    static Double ticksToClosestApproach(Vec3 relativePosition, Vec3 velocityPerTick) {
        double speedSquared = velocityPerTick.lengthSqr();
        if (speedSquared < 1.0E-6D) {
            return null;
        }
        double t = -relativePosition.dot(velocityPerTick) / speedSquared;
        if (t < 0.0D || t > LEAD_TICKS) {
            return null;
        }
        Vec3 closestOffset = relativePosition.add(velocityPerTick.scale(t));
        if (closestOffset.lengthSqr() > INTERCEPT_RADIUS_SQUARED) {
            return null;
        }
        return t;
    }

    private static boolean notOwnedByBot(AIPlayerEntity bot, AbstractArrow projectile) {
        Entity owner = projectile.getOwner();
        return owner != bot;
    }
}
