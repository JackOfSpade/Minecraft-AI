package io.github.zoyluo.minecraftai.task;

import io.github.zoyluo.minecraftai.action.ShieldBlockability;
import io.github.zoyluo.minecraftai.entity.AIPlayerEntity;
import io.github.zoyluo.minecraftai.perception.CreatureSenses;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
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
    private static final double SCAN_RANGE = 24.0D;
    /** Generous body/shield interception radius; vanilla hit detection is a similar-order box. */
    private static final double INTERCEPT_RADIUS = 1.25D;
    private static final double INTERCEPT_RADIUS_SQUARED = INTERCEPT_RADIUS * INTERCEPT_RADIUS;
    /** 1.5s of lead time: enough to turn and raise a shield before the projectile arrives. */
    private static final double LEAD_TICKS = 30.0D;

    private ProjectileThreat() {
    }

    public record Incoming(Projectile projectile, double ticksToClosestApproach) {
    }

    /** The soonest blockable, sensed projectile heading for the bot, judged against {@code shield} (the offhand item that would block). */
    public static Optional<Incoming> mostImminent(AIPlayerEntity bot, ItemStack shield) {
        List<Incoming> all = incoming(bot, shield);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
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
        List<Projectile> candidates = level.getEntitiesOfClass(
                Projectile.class,
                bot.getBoundingBox().inflate(SCAN_RANGE),
                projectile -> projectile.isAlive()
                        && notOwnedByBot(bot, projectile)
                        && CreatureSenses.INSTANCE.noticedProjectile(bot, projectile)
                        && ShieldBlockability.projectileBlockable(level, shield, projectile));
        List<Incoming> result = new ArrayList<>();
        for (Projectile projectile : candidates) {
            double gravity = projectile.getGravity();
            Double ticks = ticksToClosestApproach(
                    projectile.position().subtract(bot.getEyePosition()), velocityOf(projectile), gravity, gravity == 0.0D ? 1.0D : 0.99D);
            if (ticks != null) {
                result.add(new Incoming(projectile, ticks));
            }
        }
        result.sort(Comparator.comparingDouble(Incoming::ticksToClosestApproach));
        return result;
    }

    /**
     * How the projectile really moves per tick: its last displacement (an arrow stuck in a block has none, however stale its
     * {@code deltaMovement} is), or the launch velocity on the tick it was created.
     */
    static Vec3 velocityOf(Entity projectile) {
        Vec3 moved = projectile.position().subtract(projectile.xo, projectile.yo, projectile.zo);
        if (moved.lengthSqr() > 1.0E-6D) {
            return moved;
        }
        return projectile.tickCount <= 1 ? projectile.getDeltaMovement() : Vec3.ZERO;
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

    private static boolean notOwnedByBot(AIPlayerEntity bot, Projectile projectile) {
        Entity owner = projectile.getOwner();
        return owner != bot;
    }
}
