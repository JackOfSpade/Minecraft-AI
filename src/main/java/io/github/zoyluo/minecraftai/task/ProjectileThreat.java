package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ShieldBlockability;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.perception.CreatureSenses;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Detects projectiles already in flight on a course that will pass close to the bot, so the shield guard can react by turning to
 * face the threat and raising the shield instead of only responding to the mob that fired it. Only projectiles the bot has NOTICED
 * count ({@link CreatureSenses#noticedProjectile}), and only those whose hit the shield in the offhand would actually stop
 * ({@link ShieldBlockability#projectileBlockable}: no splash potions, snowballs, pearls, Piercing arrows, dragon fireballs, ...). There
 * is deliberately no reaction to projectiles the bot itself just fired.
 */
public final class ProjectileThreat {
    /** Generous body/shield interception radius; vanilla hit detection is a similar-order box. */
    private static final double INTERCEPT_RADIUS = 1.25D;
    private static final double INTERCEPT_RADIUS_SQUARED = INTERCEPT_RADIUS * INTERCEPT_RADIUS;
    /** 1.5s of lead time: enough to turn and raise a shield before the projectile arrives. */
    private static final double LEAD_TICKS = 30.0D;

    private ProjectileThreat() {
    }

    public record Incoming(Projectile projectile, double ticksToClosestApproach) {
    }

    /**
     * Every blockable projectile the bot senses ({@link CreatureSenses#noticedProjectile}: seen in flight or its shot heard) that is on
     * a course passing close to its eyes, soonest first. Whether the bot may ACT on one yet (the reaction time of a first sighting) is
     * the shield guard's decision.
     */
    public static List<Incoming> incoming(AIPlayerEntity bot, ItemStack shield) {
        if (!(bot.level() instanceof ServerLevel level) || !ShieldBlockability.isShield(shield)) {
            return List.of();
        }
        // Cheapest first: something that moves (an arrow stuck in the ground does not), not the bot's own, a hit the shield would stop,
        // on a course at the bot; the perception rays last.
        List<Projectile> candidates = level.getEntitiesOfClass(
                Projectile.class,
                // Query exactly as far as a companion can notice a projectile. A shorter shield-only range would make a slow
                // fireball observed across the configured profile disappear until it happened to cross an invented boundary.
                bot.getBoundingBox().inflate(CreatureSenses.observationRadius()),
                projectile -> projectile.isAlive()
                        && velocityOf(projectile).lengthSqr() >= 1.0E-6D
                        && notOwnedByBot(bot, projectile)
                        && ShieldBlockability.projectileBlockable(level, shield, projectile));
        List<Incoming> result = new ArrayList<>();
        for (Projectile projectile : candidates) {
            Double ticks = ticksToClosestApproach(projectile, projectile.position().subtract(bot.getEyePosition()), velocityOf(projectile));
            if (ticks != null && CreatureSenses.INSTANCE.noticedProjectile(bot, projectile)) {
                result.add(new Incoming(projectile, ticks));
            }
        }
        result.sort(Comparator.comparingDouble(Incoming::ticksToClosestApproach));
        return result;
    }

    /**
     * The next usable movement vector: a nonzero last displacement proves the projectile is still in flight, while current
     * {@code deltaMovement} is what vanilla will use on its next tick. For example AbstractArrow moves by {@code v_old} and then stores
     * {@code v_next = 0.99 * v_old - gravity}; replaying the observed displacement from the new position would be one full tick stale.
     * A stuck arrow has no last displacement and is rejected even if it retains stale delta movement; a just-created projectile may use
     * its launch vector before it has moved once.
     */
    static Vec3 velocityOf(Entity projectile) {
        Vec3 moved = projectile.position().subtract(projectile.xo, projectile.yo, projectile.zo);
        return forecastVelocity(moved, projectile.getDeltaMovement(), projectile.tickCount);
    }

    /** Pure form of {@link #velocityOf(Entity)}, isolated so the arrow next-step boundary cannot regress unnoticed. */
    static Vec3 forecastVelocity(Vec3 lastDisplacement, Vec3 currentDelta, int tickCount) {
        if (lastDisplacement.lengthSqr() > 1.0E-6D) {
            return currentDelta.lengthSqr() > 1.0E-6D ? currentDelta : Vec3.ZERO;
        }
        return tickCount <= 1 && currentDelta.lengthSqr() > 1.0E-6D ? currentDelta : Vec3.ZERO;
    }

    /**
     * Chooses the actual observable vanilla recurrence for the next part of this projectile's course. Each curved model is opted in
     * by its exact vanilla entity type: a modded subtype may change drag, gravity, or targeting, so it receives only the observed
     * current-velocity ray and is reconsidered next tick. Hurting projectiles are not straight, zero-gravity arrows: before every
     * move, ghast/blaze fireballs and wither skulls add their current-direction acceleration and then apply inertia. Their direction
     * is the visible current velocity, and accelerationPower is public vanilla entity state, so no target/owner information is
     * inferred here.
     */
    private static Double ticksToClosestApproach(Projectile projectile, Vec3 relativePosition, Vec3 velocityPerTick) {
        if (projectile instanceof AbstractHurtingProjectile hurting && hasVanillaHurtingCourse(projectile.getType())) {
            // AbstractHurtingProjectile applies inertia BEFORE the next move, so deltaMovement (not last displacement) starts this
            // forecast. Its direction and acceleration are public current state; we recompute every tick after any collision/deflection.
            return ticksToClosestApproachAccelerating(relativePosition, projectile.getDeltaMovement(), hurting.accelerationPower,
                    hurtingInertia(hurting));
        }
        if (hasVanillaBallisticCourse(projectile.getType())) {
            double gravity = projectile.getGravity();
            return ticksToClosestApproach(relativePosition, velocityPerTick, gravity, gravity == 0.0D ? 1.0D : 0.99D);
        }
        if (projectile instanceof AbstractHurtingProjectile || projectile.getType() == EntityType.SHULKER_BULLET
                || projectile.getType() == EntityType.FIREWORK_ROCKET) {
            // A modded hurting projectile can override protected inertia, a shulker bullet steers from a private target, and a rocket
            // has its own powered flight. Do not invent those unseen future inputs: use only the current observed ray and refresh it
            // next tick. The known vanilla fireball/skull/wind-charge path above remains exact.
            return ticksToClosestApproach(relativePosition, velocityPerTick);
        }
        return ticksToClosestApproach(relativePosition, velocityPerTick);
    }

    /** Only the current vanilla blockable hurting-projectile classes have a verified inertia contract; unknown mod subclasses do not. */
    private static boolean hasVanillaHurtingCourse(EntityType<?> type) {
        return type == EntityType.SMALL_FIREBALL || type == EntityType.FIREBALL || type == EntityType.WITHER_SKULL
                || type == EntityType.WIND_CHARGE || type == EntityType.BREEZE_WIND_CHARGE;
    }

    /** Exact vanilla registered types whose public gravity and 0.99 inertia are the observed flight contract. */
    static boolean hasVanillaBallisticCourse(EntityType<?> type) {
        if (type == null) {
            return false;
        }
        var key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        return key != null && hasVanillaBallisticCourse(key.toString());
    }

    /** Registry-free seam for the exact vanilla identifiers used by {@link #hasVanillaBallisticCourse(EntityType)}. */
    static boolean hasVanillaBallisticCourse(String entityTypeId) {
        return "minecraft:arrow".equals(entityTypeId) || "minecraft:spectral_arrow".equals(entityTypeId)
                || "minecraft:trident".equals(entityTypeId) || "minecraft:llama_spit".equals(entityTypeId);
    }

    /** The public class state behind AbstractHurtingProjectile.applyInertia: water, dangerous wither skulls, then the 0.95 default. */
    private static double hurtingInertia(AbstractHurtingProjectile projectile) {
        if (projectile.getType() == EntityType.WIND_CHARGE || projectile.getType() == EntityType.BREEZE_WIND_CHARGE) {
            return 1.0F;
        }
        if (projectile.isInWater()) {
            return 0.8F;
        }
        return projectile instanceof WitherSkull skull && skull.isDangerous() ? 0.73F : 0.95F;
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

    /**
     * {@link #ticksToClosestApproach(Vec3, Vec3)} for a projectile that falls: the vanilla recurrence of an arrow in flight
     * ({@code position += velocity; velocity *= drag; velocity.y -= gravity}, see {@link ProjectileBallistics}) is stepped tick by
     * tick, so an arrow launched on the arc a skeleton (or a player) aims is judged by where it WILL be, not by the straight line
     * of its current velocity, which at range passes well over the target. The time returned is fractional (the closest point of
     * the path segment), {@code null} when the path never comes within {@link #INTERCEPT_RADIUS} of the eyes in the lead window.
     */
    static Double ticksToClosestApproach(Vec3 relativePosition, Vec3 velocityPerTick, double gravity, double drag) {
        if (velocityPerTick.lengthSqr() < 1.0E-6D) {
            return null;
        }
        Vec3 position = relativePosition;
        Vec3 velocity = velocityPerTick;
        double bestDistanceSquared = Double.MAX_VALUE;
        double bestTime = -1.0D;
        for (int tick = 0; tick < LEAD_TICKS; tick++) {
            Vec3 next = position.add(velocity);
            // The closest point to the eyes (the origin) of the segment position -> next.
            Vec3 segment = next.subtract(position);
            double length = segment.lengthSqr();
            double along = length < 1.0E-12D ? 0.0D : Math.max(0.0D, Math.min(1.0D, -position.dot(segment) / length));
            Vec3 closest = position.add(segment.scale(along));
            double distanceSquared = closest.lengthSqr();
            if (distanceSquared < bestDistanceSquared) {
                bestDistanceSquared = distanceSquared;
                bestTime = tick + along;
            }
            position = next;
            velocity = new Vec3(velocity.x * drag, velocity.y * drag - gravity, velocity.z * drag);
        }
        return bestDistanceSquared <= INTERCEPT_RADIUS_SQUARED ? Double.valueOf(bestTime) : null;
    }

    /**
     * Exact visible-state step for {@link AbstractHurtingProjectile}: {@code velocity = (velocity + normalize(velocity) *
     * accelerationPower) * inertia; position += velocity}. This is deliberately separate from ordinary zero-gravity projectiles such
     * as wind charges, whose straight-line recurrence is correct.
     */
    static Double ticksToClosestApproachAccelerating(Vec3 relativePosition, Vec3 velocityPerTick, double accelerationPower,
                                                     double inertia) {
        if (velocityPerTick.lengthSqr() < 1.0E-6D || !Double.isFinite(accelerationPower) || !Double.isFinite(inertia)) {
            return null;
        }
        Vec3 position = relativePosition;
        Vec3 velocity = velocityPerTick;
        double bestDistanceSquared = Double.MAX_VALUE;
        double bestTime = -1.0D;
        for (int tick = 0; tick < LEAD_TICKS; tick++) {
            velocity = acceleratingNextVelocity(velocity, accelerationPower, inertia);
            Vec3 next = position.add(velocity);
            Vec3 segment = next.subtract(position);
            double length = segment.lengthSqr();
            double along = length < 1.0E-12D ? 0.0D : Math.max(0.0D, Math.min(1.0D, -position.dot(segment) / length));
            Vec3 closest = position.add(segment.scale(along));
            double distanceSquared = closest.lengthSqr();
            if (distanceSquared < bestDistanceSquared) {
                bestDistanceSquared = distanceSquared;
                bestTime = tick + along;
            }
            position = next;
        }
        return bestDistanceSquared <= INTERCEPT_RADIUS_SQUARED ? Double.valueOf(bestTime) : null;
    }

    /** One exact {@link AbstractHurtingProjectile#tick()} inertia update, kept package-visible for its vanilla-order unit test. */
    static Vec3 acceleratingNextVelocity(Vec3 velocity, double accelerationPower, double inertia) {
        return velocity.add(velocity.normalize().scale(accelerationPower)).scale(inertia);
    }

    private static boolean notOwnedByBot(AIPlayerEntity bot, Projectile projectile) {
        Entity owner = projectile.getOwner();
        return owner != bot;
    }
}
